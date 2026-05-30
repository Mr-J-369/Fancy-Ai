package com.mrj.fancyai.service

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Base64
import android.util.Log
import com.google.gson.Gson
import com.google.gson.JsonObject
import com.mrj.fancyai.data.repository.MediaRepository
import com.mrj.fancyai.data.repository.SettingsRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.POST
import java.util.concurrent.TimeUnit

interface ForgeApi {
    @POST("/sdapi/v1/txt2img")
    suspend fun txt2img(@Body request: Map<String, @JvmSuppressWildcards Any>): ForgeResponse

    @POST("/sdapi/v1/img2img")
    suspend fun img2img(@Body request: Map<String, @JvmSuppressWildcards Any>): ForgeResponse

    @GET("/sdapi/v1/progress")
    suspend fun progress(): ProgressResponse

    data class ForgeResponse(val images: List<String>)
    data class ProgressResponse(val progress: Float)
}

interface LocalDreamApi {
    // NOTE: /generate is NOT declared here — it returns a Server-Sent Events stream
    // (data: {...} lines), not a single JSON object, so it's handled with raw OkHttp
    // streaming in ImageService.runLocalDream() instead of a Retrofit JSON converter.

    @POST("/tokenize")
    suspend fun tokenize(@Body request: Map<String, String>): TokenResponse

    data class TokenResponse(val count: Int)
}

/** A generated image plus its `db:` storage reference (null if saving failed). */
data class GeneratedImage(val bitmap: Bitmap, val ref: String?)

/**
 * 100% Kotlin Native Imaging Service.
 * Unified management for Forge and Snapdragon NPU (Local Dream) backends.
 */
class ImageService(
    private val settingsRepository: SettingsRepository,
    private val mediaRepository: MediaRepository
) {
    // 3-minute read timeout: Flux / SDXL on a paired Forge/A1111 server can queue and take
    // a couple minutes per image, so don't time out the wait. write timeout matches.
    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(180, TimeUnit.SECONDS)
        .writeTimeout(180, TimeUnit.SECONDS)
        .build()

    private fun <T> createApi(baseUrl: String, service: Class<T>): T {
        return Retrofit.Builder()
            .baseUrl(baseUrl.ifBlank { "http://127.0.0.1:7860" })
            .client(client)
            .addConverterFactory(GsonConverterFactory.create())
            .build()
            .create(service)
    }

    /**
     * @param onProgress invoked with 0..100 as generation advances. Only Local Dream
     *   reports granular progress (via its SSE stream); Forge stays indeterminate.
     */
    suspend fun generate(
        prompt: String,
        params: Map<String, Any>,
        sourceImageB64: String? = null,
        onProgress: (Int) -> Unit = {}
    ): Result<GeneratedImage> = withContext(Dispatchers.IO) {
        try {
            if (settingsRepository.useLocalDream) {
                runLocalDream(settingsRepository.localDreamUrl, prompt, params, sourceImageB64, onProgress)
            } else {
                runForge(settingsRepository.forgeUrl, prompt, params, sourceImageB64, onProgress)
            }
        } catch (e: Exception) {
            Log.e("ImageService", "Generation failed", e)
            Result.failure(e)
        }
    }

    private suspend fun runForge(baseUrl: String, prompt: String, params: Map<String, Any>, sourceImageB64: String?, onProgress: (Int) -> Unit): Result<GeneratedImage> = coroutineScope {
        val api = createApi(baseUrl, ForgeApi::class.java)
        val request = mutableMapOf<String, Any>(
            "prompt" to prompt,
            "steps" to (params["steps"] ?: 20),
            "cfg_scale" to (params["cfg"] ?: 7),
            "width" to (params["width"] ?: 512),
            "height" to (params["height"] ?: 512)
        )

        // Forge has no streaming endpoint, so poll /sdapi/v1/progress in parallel while
        // the (long-running) txt2img/img2img call is in flight.
        val poller = launch {
            while (isActive) {
                delay(1000)
                try {
                    val pct = (api.progress().progress * 100).toInt().coerceIn(0, 100)
                    if (pct > 0) onProgress(pct)
                } catch (_: Exception) { /* progress is best-effort */ }
            }
        }

        try {
            val response = if (sourceImageB64 != null) {
                request["init_images"] = listOf(sourceImageB64)
                request["denoising_strength"] = params["denoising"] ?: 0.75f
                api.img2img(request)
            } else {
                api.txt2img(request)
            }
            onProgress(100)
            decodeResponse(response.images.firstOrNull())
        } finally {
            poller.cancel()
        }
    }

    /**
     * Local Dream (on-device Snapdragon NPU server) streams its result as Server-Sent
     * Events: newline-delimited `data: {...}` lines with a `type` of "progress" or
     * "complete". The "complete" event carries the final image as base64-encoded **raw
     * RGB** pixel bytes (3 bytes/pixel) plus width/height — not an encoded PNG. We read
     * the stream directly with OkHttp and rebuild the bitmap from those bytes.
     */
    private suspend fun runLocalDream(baseUrl: String, prompt: String, params: Map<String, Any>, sourceImageB64: String?, onProgress: (Int) -> Unit): Result<GeneratedImage> {
        val url = baseUrl.ifBlank { "http://127.0.0.1:8081" }.trimEnd('/')
            .replace(Regex("/generate/?$"), "")
        val width = (params["width"] as? Int) ?: 512
        val height = (params["height"] as? Int) ?: 512

        val payload = mutableMapOf<String, Any>(
            "prompt" to prompt,
            "negative_prompt" to "blurry, distorted, low quality",
            "steps" to (params["steps"] ?: 20),
            "cfg" to (params["cfg"] ?: 7.0f),
            "scheduler" to settingsRepository.localDreamScheduler,
            "use_opencl" to false,
            "show_diffusion_process" to false,
            "show_diffusion_stride" to 1,
            "width" to width,
            "height" to height
        )
        if (sourceImageB64 != null) {
            val cleanB64 = sourceImageB64.replace(Regex("^data:image/[a-z]+;base64,"), "")
            payload["image"] = cleanB64
            payload["init_image"] = cleanB64
            payload["strength"] = params["denoising"] ?: 0.75f
        }

        val body = Gson().toJson(payload).toRequestBody("application/json".toMediaType())
        val request = Request.Builder().url("$url/generate").post(body).build()

        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                val detail = runCatching { response.body?.string()?.take(300) }.getOrNull()
                return Result.failure(Exception("Local Dream error: HTTP ${response.code}${detail?.let { " — $it" } ?: ""}"))
            }
            val source = response.body?.source()
                ?: return Result.failure(Exception("Empty Local Dream response"))

            val gson = Gson()
            while (!source.exhausted()) {
                val line = source.readUtf8Line() ?: break
                if (!line.startsWith("data:")) continue
                val jsonText = line.removePrefix("data:").trim()
                if (jsonText.isEmpty()) continue
                val event = try {
                    gson.fromJson(jsonText, JsonObject::class.java)
                } catch (e: Exception) {
                    Log.w("ImageService", "Skipping unparseable SSE line", e); continue
                }
                when (event.get("type")?.asString) {
                    "progress" -> {
                        val step = event.get("step")?.asInt ?: 0
                        val total = event.get("total_steps")?.asInt
                            ?: (params["steps"] as? Int) ?: 20
                        if (total > 0) onProgress(((step * 100) / total).coerceIn(0, 100))
                    }
                    "complete" -> {
                        val w = event.get("width")?.asInt ?: width
                        val h = event.get("height")?.asInt ?: height
                        val imageB64 = event.get("image")?.asString
                            ?: return Result.failure(Exception("Local Dream 'complete' event had no image"))
                        val bitmap = rgbBytesToBitmap(Base64.decode(imageB64, Base64.DEFAULT), w, h)
                        onProgress(100)
                        val ref = mediaRepository.saveBitmap(bitmap)
                        return Result.success(GeneratedImage(bitmap, ref))
                    }
                }
            }
        }
        return Result.failure(Exception("Local Dream stream ended without a completed image"))
    }

    /** Rebuilds an ARGB_8888 bitmap from packed raw RGB bytes (3 bytes per pixel). */
    private fun rgbBytesToBitmap(rgb: ByteArray, width: Int, height: Int): Bitmap {
        val pixels = IntArray(width * height)
        var j = 0
        var i = 0
        while (i < pixels.size && j + 2 < rgb.size) {
            val r = rgb[j].toInt() and 0xFF
            val g = rgb[j + 1].toInt() and 0xFF
            val b = rgb[j + 2].toInt() and 0xFF
            pixels[i] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
            j += 3
            i++
        }
        return Bitmap.createBitmap(pixels, width, height, Bitmap.Config.ARGB_8888)
    }

    suspend fun getTokenCount(prompt: String): Int = withContext(Dispatchers.IO) {
        try {
            val api = createApi(settingsRepository.localDreamUrl, LocalDreamApi::class.java)
            api.tokenize(mapOf("prompt" to prompt)).count
        } catch (e: Exception) { 0 }
    }

    /** Decodes a base64-encoded image file (PNG/JPEG), e.g. a Forge result, into a bitmap. */
    private suspend fun decodeResponse(base64: String?): Result<GeneratedImage> {
        if (base64 == null) return Result.failure(Exception("No image in response"))

        val cleanB64 = base64.replace(Regex("^data:image/[a-z]+;base64,"), "")
        val bytes = Base64.decode(cleanB64, Base64.DEFAULT)
        val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
            ?: return Result.failure(Exception("Could not decode image bytes"))

        val ref = mediaRepository.saveBitmap(bitmap)
        return Result.success(GeneratedImage(bitmap, ref))
    }
}
