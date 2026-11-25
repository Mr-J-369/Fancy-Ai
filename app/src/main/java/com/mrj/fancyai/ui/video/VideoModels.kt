package com.mrj.fancyai.ui.video

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.util.Base64
import androidx.annotation.StringRes
import com.mrj.fancyai.R
import com.mrj.fancyai.service.llm.CloudProvider
import com.mrj.fancyai.util.PrivateHttp
import com.mrj.fancyai.util.PrivateHttp.routePrivateHttp
import com.mrj.fancyai.util.decodeImage
import com.mrj.fancyai.util.downloadFile
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.job
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import okhttp3.Call
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.Locale
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.time.Duration.Companion.seconds

internal enum class VideoProvider(@param:StringRes val label: Int) {
    OPENROUTER(R.string.cloud_openrouter),
    DEEPINFRA(R.string.cloud_deepinfra),
}

internal val VideoProvider.cloudProvider: CloudProvider
    get() = when (this) {
        VideoProvider.OPENROUTER -> CloudProvider.OPENROUTER
        VideoProvider.DEEPINFRA -> CloudProvider.DEEPINFRA
    }

internal enum class VideoPage(@param:StringRes val label: Int) {
    STUDIO(R.string.aura_tab_studio),
    MODELS(R.string.aura_tab_models),
}

@Serializable
internal data class VideoPricingEntry(
    val label: String,
    val price: String,
)

@Serializable
internal data class VideoModelInfo(
    val id: String,
    val name: String = "",
    val description: String = "",
    val provider: VideoProvider,
    val supportedDurations: List<Int> = emptyList(),
    val minDuration: Int? = null,
    val maxDuration: Int? = null,
    val defaultDuration: Int? = null,
    val supportedResolutions: List<String> = emptyList(),
    val supportedAspectRatios: List<String> = emptyList(),
    val supportedSizes: List<String> = emptyList(),
    val supportedFrameImages: List<String> = emptyList(),
    val supportsTextToVideo: Boolean = true,
    val supportsImageToVideo: Boolean = false,
    val supportsAudio: Boolean = false,
    val supportsSeed: Boolean = true,
    val supportsNegativePrompt: Boolean = true,
    val pricingSummary: String = "",
    val pricingDetails: List<VideoPricingEntry> = emptyList(),
    val perSecondUsd: Double? = null,
    val perVideoUsd: Double? = null,
) {
    val displayName: String get() = name.takeIf(String::isNotBlank) ?: id
    val supportsFirstFrame: Boolean
        get() = supportsImageToVideo || ("first_frame" in supportedFrameImages)
    val supportsLastFrame: Boolean
        get() = "last_frame" in supportedFrameImages

    fun durationOptions(): List<Int> {
        if (supportedDurations.isNotEmpty()) {
            return supportedDurations.filter { it > 0 }.distinct().sorted()
        }
        val min = minDuration ?: return emptyList()
        val max = maxDuration ?: return listOf(min)
        if (max - min <= 8) return (min..max).toList()
        return listOfNotNull(min, (min + max) / 2, max, 5, 10)
            .filter { it in min..max }
            .distinct()
            .sorted()
    }

    fun capabilitySummary(): String = buildList {
        if (supportsTextToVideo) add("T2V")
        if (supportsFirstFrame) add("I2V")
        if (supportsAudio) add("Audio")
        val durations = supportedDurations.filter { it > 0 }.sorted()
        when {
            durations.isNotEmpty() -> add(durations.joinToString("/") { "${it}s" })
            minDuration != null && maxDuration != null -> {
                if (minDuration == maxDuration) add("${minDuration}s")
                else add("$minDuration–${maxDuration}s")
            }
        }
        if (supportedResolutions.isNotEmpty()) add(supportedResolutions.joinToString("/"))
        else if (supportedSizes.isNotEmpty()) add(supportedSizes.take(3).joinToString("/"))
        else if (supportedAspectRatios.isNotEmpty()) add(supportedAspectRatios.take(3).joinToString("/"))
    }.joinToString(" · ")

    fun estimateUsd(durationSeconds: Int?): Double? {
        perVideoUsd?.let { return it }
        val rate = perSecondUsd ?: return null
        val seconds = durationSeconds?.takeIf { it > 0 } ?: defaultDuration ?: return null
        return rate * seconds
    }
}

@Serializable
internal data class VideoGenerationMetadata(
    val provider: String = "video",
    val cloudProvider: String = "",
    val model: String = "",
    val prompt: String = "",
    val negative: String = "",
    val durationSeconds: Int? = null,
    val resolution: String = "",
    val aspectRatio: String = "",
    val audio: Boolean? = null,
    val seed: Long? = null,
    val width: Int = 0,
    val height: Int = 0,
    val imageToVideo: Boolean = false,
    val costUsd: Double? = null,
    val generationDurationMs: Long? = null,
)

internal data class VideoGenerationRequest(
    val provider: VideoProvider,
    val apiKey: String,
    val model: VideoModelInfo,
    val prompt: String,
    val negativePrompt: String,
    val durationSeconds: Int?,
    val resolution: String,
    val aspectRatio: String,
    val size: String,
    val generateAudio: Boolean,
    val seed: Long?,
    val firstFrameDataUri: String?,
    val lastFrameDataUri: String?,
)

internal data class VideoGenerationOutcome(
    val file: File,
    val costUsd: Double?,
    val durationSeconds: Int?,
)

internal enum class VideoFrameSlot { FIRST, LAST }

internal enum class VideoStatusStage {
    SUBMITTING,
    POLLING,
    DOWNLOADING,
}

internal data class VideoStudioState(
    val provider: VideoProvider = VideoProvider.OPENROUTER,
    val apiKey: String = "",
    val models: List<VideoModelInfo> = emptyList(),
    val selectedModel: VideoModelInfo? = null,
    val query: String = "",
    val loadingModels: Boolean = false,
    val modelsError: String? = null,
    val prompt: String = "",
    val negativePrompt: String = "",
    val durationSeconds: Int? = null,
    val resolution: String = "",
    val aspectRatio: String = "",
    val width: String = "",
    val height: String = "",
    val generateAudio: Boolean = true,
    val seed: String = "",
    val seedLocked: Boolean = false,
    val firstFrameFile: File? = null,
    val firstFramePreview: Bitmap? = null,
    val lastFrameFile: File? = null,
    val lastFramePreview: Bitmap? = null,
    val generating: Boolean = false,
    val statusStage: VideoStatusStage? = null,
    val error: String? = null,
    val resultFile: File? = null,
    val resultMetadata: VideoGenerationMetadata? = null,
    val galleryImages: List<File> = emptyList(),
)

internal class VideoCloudClient(context: Context) {
    private val app = context.applicationContext
    private val activeCall = AtomicReference<Call?>()
    private val activeDownloadCancel = AtomicReference<(() -> Unit)?>()
    private val client = PrivateHttp.apiClient.newBuilder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(600, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .build()

    fun cancel() {
        activeCall.get()?.cancel()
        activeDownloadCancel.get()?.invoke()
    }

    suspend fun fetchModels(provider: VideoProvider, apiKey: String): List<VideoModelInfo> =
        withContext(Dispatchers.IO) {
            when (provider) {
                VideoProvider.OPENROUTER -> fetchOpenRouterModels(apiKey)
                VideoProvider.DEEPINFRA -> fetchDeepInfraModels(apiKey)
            }
        }

    suspend fun generate(
        request: VideoGenerationRequest,
        outputFile: File,
        onStage: (VideoStatusStage) -> Unit,
    ): VideoGenerationOutcome = withContext(Dispatchers.IO) {
        when (request.provider) {
            VideoProvider.OPENROUTER -> generateOpenRouter(request, outputFile, onStage)
            VideoProvider.DEEPINFRA -> generateDeepInfra(request, outputFile, onStage)
        }
    }

    private suspend fun fetchOpenRouterModels(apiKey: String): List<VideoModelInfo> {
        val builder = requestBuilder("$OPENROUTER_BASE_URL/api/v1/videos/models", VideoProvider.OPENROUTER, apiKey)
        val body = executeString(builder.get().build())
        val root = Json.parseToJsonElement(body).jsonObject
        val data = root["data"]?.jsonArray ?: return emptyList()
        return data.mapNotNull { element ->
            val obj = element as? JsonObject ?: return@mapNotNull null
            val id = obj.string("id")?.takeIf(String::isNotBlank) ?: return@mapNotNull null
            val durations = (obj["supported_durations"] as? JsonArray)
                ?.mapNotNull { (it as? JsonPrimitive)?.intOrNull }
                .orEmpty()
            val resolutions = (obj["supported_resolutions"] as? JsonArray)
                ?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
                .orEmpty()
            val aspectRatios = (obj["supported_aspect_ratios"] as? JsonArray)
                ?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
                .orEmpty()
            val sizes = (obj["supported_sizes"] as? JsonArray)
                ?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
                .orEmpty()
            val frameImages = (obj["supported_frame_images"] as? JsonArray)
                ?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
                .orEmpty()
            val pricingSkus = (obj["pricing_skus"] as? JsonObject)?.entries
                ?.mapNotNull { (k, v) ->
                    (v as? JsonPrimitive)?.contentOrNull?.let { price ->
                        VideoPricingEntry(formatSkuLabel(k), formatUsd(price.toDoubleOrNull(), price))
                    }
                }
                .orEmpty()
            val (summary, perSecond, perVideo) = summarizeOpenRouterPricing(obj["pricing_skus"] as? JsonObject)
            VideoModelInfo(
                id = id,
                name = obj.string("name").orEmpty(),
                description = obj.string("description").orEmpty(),
                provider = VideoProvider.OPENROUTER,
                supportedDurations = durations,
                defaultDuration = durations.firstOrNull { it > 0 },
                supportedResolutions = resolutions,
                supportedAspectRatios = aspectRatios,
                supportedSizes = sizes,
                supportedFrameImages = frameImages,
                supportsTextToVideo = true,
                supportsImageToVideo = frameImages.isNotEmpty(),
                supportsAudio = (obj["generate_audio"] as? JsonPrimitive)?.booleanOrNull == true,
                supportsSeed = (obj["seed"] as? JsonPrimitive)?.booleanOrNull != false,
                supportsNegativePrompt = false,
                pricingSummary = summary,
                pricingDetails = pricingSkus,
                perSecondUsd = perSecond,
                perVideoUsd = perVideo,
            )
        }.sortedBy(VideoModelInfo::id)
    }

    private suspend fun fetchDeepInfraModels(apiKey: String): List<VideoModelInfo> {
        val builder = requestBuilder(DEEPINFRA_MODELS_LIST, VideoProvider.DEEPINFRA, apiKey)
        val body = executeString(builder.get().build())
        val items = Json.parseToJsonElement(body).jsonArray
        return items.mapNotNull { element ->
            val obj = element as? JsonObject ?: return@mapNotNull null
            if (obj["deprecated"] != null && obj["deprecated"] !is JsonNull) return@mapNotNull null
            val id = obj.string("model_name")?.takeIf(String::isNotBlank) ?: return@mapNotNull null
            val type = obj.string("reported_type") ?: obj.string("type").orEmpty()
            val tags = (obj["tags"] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }.orEmpty()
            val isVideoTask = type == "text-to-video" || type == "image-to-video" ||
                ("text-to-video" in tags) || ("image-to-video" in tags)
            if (!isVideoTask || isDeepInfraUtilityModel(id)) return@mapNotNull null
            val pricingObj = obj["pricing"] as? JsonObject
            val pricingShort = pricingObj?.string("short").orEmpty()
            val pricingFull = pricingObj?.string("full").orEmpty()
            val centsPerSec = pricingObj?.doubleFieldOrNull("cents_per_sec")
            val pricingType = pricingObj?.string("type").orEmpty()
            val pricingEntries = buildDeepInfraPricingDetails(pricingObj, pricingFull)
            val perSecond = when {
                centsPerSec != null && centsPerSec > 0.0 -> centsPerSec / 100.0
                else -> parseLeadingDollarRate(pricingShort, "/ second") ?: parseLeadingDollarRate(pricingShort, "/s")
            }
            val perVideo = if (pricingType == "image_units" || "/ video" in pricingShort) {
                parseLeadingDollarRate(pricingShort, "/ video")
            } else null
            val caps = inferDeepInfraCapabilities(id, type, tags)
            VideoModelInfo(
                id = id,
                name = id.substringAfter('/'),
                description = obj.string("description").orEmpty(),
                provider = VideoProvider.DEEPINFRA,
                supportedDurations = caps.supportedDurations,
                minDuration = caps.minDuration,
                maxDuration = caps.maxDuration,
                defaultDuration = caps.defaultDuration,
                supportedResolutions = caps.supportedResolutions,
                supportedAspectRatios = caps.supportedAspectRatios,
                supportedFrameImages = caps.supportedFrameImages,
                supportsTextToVideo = caps.supportsTextToVideo,
                supportsImageToVideo = caps.supportsImageToVideo,
                supportsAudio = caps.supportsAudio,
                supportsSeed = true,
                supportsNegativePrompt = caps.supportsNegativePrompt,
                pricingSummary = pricingShort.ifBlank { pricingFull },
                pricingDetails = pricingEntries,
                perSecondUsd = perSecond,
                perVideoUsd = perVideo,
            )
        }.distinctBy(VideoModelInfo::id).sortedBy(VideoModelInfo::id)
    }

    private suspend fun generateOpenRouter(
        request: VideoGenerationRequest,
        outputFile: File,
        onStage: (VideoStatusStage) -> Unit,
    ): VideoGenerationOutcome {
        onStage(VideoStatusStage.SUBMITTING)
        val payload = buildJsonObject {
            put("model", request.model.id)
            put("prompt", request.prompt)
            request.durationSeconds?.takeIf { it != 0 }?.let { put("duration", it) }
            if (request.size.isNotBlank() && (request.model.supportedSizes.isNotEmpty() || request.resolution.isBlank())) {
                put("size", request.size)
            }
            if (request.resolution.isNotBlank()) put("resolution", request.resolution)
            if (request.aspectRatio.isNotBlank()) put("aspect_ratio", request.aspectRatio)
            if (request.model.supportsAudio) put("generate_audio", request.generateAudio)
            if (request.model.supportsSeed) request.seed?.let { put("seed", it) }
            val frames = buildList {
                request.firstFrameDataUri?.let { add("first_frame" to it) }
                request.lastFrameDataUri?.let { add("last_frame" to it) }
            }
            if (frames.isNotEmpty()) {
                put(
                    "frame_images",
                    buildJsonArray {
                        frames.forEach { (frameType, dataUri) ->
                            add(
                                buildJsonObject {
                                    put("type", "image_url")
                                    put("frame_type", frameType)
                                    put("image_url", buildJsonObject { put("url", dataUri) })
                                },
                            )
                        }
                    },
                )
            }
        }
        val submitReq = requestBuilder("$OPENROUTER_BASE_URL/api/v1/videos", VideoProvider.OPENROUTER, request.apiKey)
            .post(payload.toString().toRequestBody(JSON_MEDIA_TYPE))
            .build()
        val submitJson = Json.parseToJsonElement(executeString(submitReq)).jsonObject
        val jobId = submitJson.string("id").orEmpty()
        val pollingUrl = resolveOpenRouterUrl(
            submitJson.string("polling_url") ?: "/api/v1/videos/$jobId",
        )
        onStage(VideoStatusStage.POLLING)
        var statusObj = submitJson
        while (true) {
            currentCoroutineContext().ensureActive()
            when (val status = statusObj.string("status").orEmpty()) {
                "completed" -> break
                "failed", "cancelled", "expired" -> {
                    val detail = statusObj.string("error")?.takeIf(String::isNotBlank)
                        ?: "Video job $status"
                    throw IOException(detail)
                }
            }
            delay(5.seconds)
            val pollReq = requestBuilder(pollingUrl, VideoProvider.OPENROUTER, request.apiKey).get().build()
            statusObj = Json.parseToJsonElement(executeString(pollReq)).jsonObject
        }
        onStage(VideoStatusStage.DOWNLOADING)
        val signedUrl = (statusObj["urls"] as? JsonArray)
            ?.firstOrNull()
            ?.let { (it as? JsonPrimitive)?.contentOrNull }
        val unsignedUrl = (statusObj["unsigned_urls"] as? JsonArray)
            ?.firstOrNull()
            ?.let { (it as? JsonPrimitive)?.contentOrNull }
            ?.let(::resolveOpenRouterUrl)
            ?: jobId.takeIf(String::isNotBlank)?.let { "$OPENROUTER_BASE_URL/api/v1/videos/$it/content?index=0" }
        val downloadReq = when {
            !signedUrl.isNullOrBlank() -> requestBuilder(signedUrl, VideoProvider.OPENROUTER, "").get().build()
            !unsignedUrl.isNullOrBlank() -> requestBuilder(unsignedUrl, VideoProvider.OPENROUTER, request.apiKey).get().build()
            else -> throw IOException("No video output URL returned")
        }
        downloadToFile(downloadReq, outputFile)
        val cost = (statusObj["usage"] as? JsonObject)?.doubleFieldOrNull("cost")
        return VideoGenerationOutcome(outputFile, cost, request.durationSeconds)
    }

    private suspend fun generateDeepInfra(
        request: VideoGenerationRequest,
        outputFile: File,
        onStage: (VideoStatusStage) -> Unit,
    ): VideoGenerationOutcome {
        onStage(VideoStatusStage.SUBMITTING)
        val id = request.model.id
        val lower = id.lowercase(Locale.ROOT)
        val payload = buildJsonObject {
            put("prompt", request.prompt)
            if (request.negativePrompt.isNotBlank() && request.model.supportsNegativePrompt) {
                put("negative_prompt", request.negativePrompt)
            }
            request.seed?.let { put("seed", it) }
            request.durationSeconds?.takeIf { it > 0 }?.let { seconds ->
                when {
                    lower.startsWith("wan-ai/wan2.2") || lower.startsWith("fastvideo/") -> put("seconds", seconds)
                    lower.startsWith("google/veo") -> Unit
                    else -> put("duration", seconds)
                }
            }
            if (lower.startsWith("lightricks/ltx")) {
                val ltxResolution = request.size.takeIf(String::isNotBlank)
                    ?: request.resolution.takeIf { 'x' in it }
                if (!ltxResolution.isNullOrBlank()) {
                    put("resolution", ltxResolution)
                }
            } else {
                if (request.resolution.isNotBlank()) {
                    val normalized = if (lower.startsWith("wan-ai/wan2.7") || lower.startsWith("wan-ai/wan3.0")) {
                        request.resolution.uppercase(Locale.ROOT)
                    } else {
                        request.resolution.lowercase(Locale.ROOT)
                    }
                    put("resolution", normalized)
                }
                if (request.aspectRatio.isNotBlank()) {
                    if (lower.startsWith("wan-ai/wan3.0") || lower.startsWith("wan-ai/wan2.7-r2v")) {
                        put("ratio", request.aspectRatio)
                    } else {
                        put("aspect_ratio", request.aspectRatio)
                    }
                }
            }
            if (request.model.supportsAudio) {
                if (lower.startsWith("wan-ai/wan3.0")) {
                    put("audio", request.generateAudio)
                } else {
                    put("generate_audio", request.generateAudio)
                }
            }
            val first = request.firstFrameDataUri
            val last = request.lastFrameDataUri
            if (first != null || last != null) {
                when {
                    lower.startsWith("wan-ai/wan3.0") || lower.startsWith("wan-ai/wan2.7-r2v") -> {
                        put(
                            "media",
                            buildJsonArray {
                                first?.let {
                                    add(buildJsonObject {
                                        put("type", "first_frame")
                                        put("url", it)
                                    })
                                }
                                last?.let {
                                    add(buildJsonObject {
                                        put("type", "last_frame")
                                        put("url", it)
                                    })
                                }
                            },
                        )
                    }
                    lower.startsWith("wan-ai/wan2.7-i2v") -> {
                        first?.let { put("first_frame", it) }
                        last?.let { put("last_frame", it) }
                    }
                    lower.startsWith("bytedance/seedance") -> {
                        first?.let { put("first_frame_image", it) }
                        last?.let { put("last_frame_image", it) }
                    }
                    lower.startsWith("lightricks/ltx") -> {
                        first?.let { put("image_url", it) }
                        last?.let { put("last_frame_url", it) }
                    }
                    lower.startsWith("wan-ai/wan2.6-i2v") -> {
                        first?.let { put("image_url", it) }
                    }
                    else -> {
                        first?.let { put("image", it) }
                    }
                }
            }
        }
        val req = requestBuilder("$DEEPINFRA_INFERENCE_BASE/$id", VideoProvider.DEEPINFRA, request.apiKey)
            .post(payload.toString().toRequestBody(JSON_MEDIA_TYPE))
            .build()
        val responseJson = Json.parseToJsonElement(executeString(req)).jsonObject
        onStage(VideoStatusStage.DOWNLOADING)
        val mediaRef = extractDeepInfraVideoRef(responseJson)
            ?: throw IOException(responseJson.string("error") ?: "No video returned by DeepInfra")
        if (mediaRef.startsWith("data:", ignoreCase = true)) {
            val base64 = mediaRef.substringAfter("base64,", "")
            if (base64.isEmpty()) throw IOException("Invalid inline video payload")
            val bytes = Base64.decode(base64, Base64.DEFAULT)
            writeStreamBytes(outputFile, bytes)
        } else {
            val dlReq = requestBuilder(mediaRef, VideoProvider.DEEPINFRA, "").get().build()
            downloadToFile(dlReq, outputFile)
        }
        val statusObj = responseJson["inference_status"] as? JsonObject
        val cost = statusObj?.doubleFieldOrNull("cost")
        val outputLength = statusObj?.doubleFieldOrNull("output_length")?.toInt()?.takeIf { it > 0 }
        return VideoGenerationOutcome(outputFile, cost, outputLength ?: request.durationSeconds)
    }

    private suspend fun executeString(request: Request): String {
        val call = client.newCall(request)
        check(activeCall.compareAndSet(null, call)) { "A video request is already active" }
        currentCoroutineContext().job.invokeOnCompletion { cause ->
            if (cause != null) call.cancel()
        }
        try {
            return call.execute().use { response ->
                val body = response.body.string()
                if (!response.isSuccessful) {
                    throw IOException(extractErrorMessage(response.code, body))
                }
                body
            }
        } catch (failure: IOException) {
            if (call.isCanceled()) {
                throw CancellationException("Video request cancelled").also { it.initCause(failure) }
            }
            throw failure
        } finally {
            activeCall.compareAndSet(call, null)
        }
    }

    private suspend fun downloadToFile(request: Request, destination: File) {
        val headers = buildMap {
            for (i in 0 until request.headers.size) {
                put(request.headers.name(i), request.headers.value(i))
            }
        }
        downloadFile(
            context = app,
            url = request.url.toString(),
            target = destination,
            headers = headers,
            applyHfMirror = false,
            activeCancel = activeDownloadCancel,
        )
    }

    private fun writeStreamBytes(destination: File, bytes: ByteArray) {
        val tempFile = File(destination.parentFile, ".${destination.name}.part")
        try {
            destination.parentFile?.mkdirs()
            FileOutputStream(tempFile).use { output ->
                output.write(bytes)
                output.fd.sync()
            }
            if (destination.exists()) destination.delete()
            if (!tempFile.renameTo(destination)) {
                tempFile.copyTo(destination, overwrite = true)
                tempFile.delete()
            }
        } catch (failure: Throwable) {
            tempFile.delete()
            throw failure
        }
    }

    private fun requestBuilder(url: String, provider: VideoProvider, apiKey: String): Request.Builder {
        val endpoint = routePrivateHttp(url)
        return Request.Builder().url(endpoint.url).apply {
            endpoint.hostHeader?.let { header("Host", it) }
            if (apiKey.isNotBlank()) {
                header("Authorization", "Bearer ${apiKey.trim()}")
            }
            if (provider == VideoProvider.OPENROUTER && url.startsWith(OPENROUTER_BASE_URL)) {
                header("HTTP-Referer", OPENROUTER_REFERER)
                header("X-OpenRouter-Title", OPENROUTER_TITLE)
            }
        }
    }

    companion object {
        internal const val OPENROUTER_BASE_URL = "https://openrouter.ai"
        private const val OPENROUTER_REFERER = "https://fancyai-os.com"
        private const val OPENROUTER_TITLE = "Fancy AI"
        private const val DEEPINFRA_MODELS_LIST = "https://api.deepinfra.com/models/list"
        private const val DEEPINFRA_INFERENCE_BASE = "https://api.deepinfra.com/v1/inference"
        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()

        internal fun encodeFrameDataUri(context: Context, file: File): String {
            val bitmap = decodeImage(context, Uri.fromFile(file), 1536)
            return try {
                val out = ByteArrayOutputStream()
                check(bitmap.compress(Bitmap.CompressFormat.JPEG, 90, out)) { "Failed to encode frame image" }
                val base64 = Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP)
                "data:image/jpeg;base64,$base64"
            } finally {
                bitmap.recycle()
            }
        }

        internal fun formatUsdValue(value: Double): String {
            val formatted = String.format(Locale.US, "%.4f", value).trimEnd('0').trimEnd('.')
                .ifEmpty { "0" }
            return "$$formatted"
        }
    }
}

internal fun resolveOpenRouterUrl(pathOrUrl: String): String =
    if (pathOrUrl.startsWith("http://") || pathOrUrl.startsWith("https://")) pathOrUrl
    else VideoCloudClient.OPENROUTER_BASE_URL + if (pathOrUrl.startsWith("/")) pathOrUrl else "/$pathOrUrl"

internal fun extractDeepInfraVideoRef(obj: JsonObject): String? {
    obj.string("video_url")?.takeIf(String::isNotBlank)?.let { return it }
    obj.string("video")?.takeIf(String::isNotBlank)?.let { return it }
    val videos = obj["videos"] as? JsonArray ?: return null
    val first = videos.firstOrNull() ?: return null
    return when (first) {
        is JsonPrimitive -> first.contentOrNull?.takeIf(String::isNotBlank)
        is JsonObject -> first.string("url") ?: first.string("video_url")
        else -> null
    }
}

internal fun extractErrorMessage(code: Int, body: String): String {
    val parsed = runCatching { Json.parseToJsonElement(body).jsonObject }.getOrNull()
    val message = when (val error = parsed?.get("error")) {
        is JsonPrimitive -> error.contentOrNull
        is JsonObject -> error.string("message")
        else -> parsed?.string("detail")?.takeIf(String::isNotBlank)
            ?: (parsed?.get("detail") as? JsonArray)?.firstOrNull()?.let { item ->
                (item as? JsonObject)?.string("msg")
            }
    }
    return message?.takeIf(String::isNotBlank)?.take(500)
        ?: body.takeIf(String::isNotBlank)?.take(300)
        ?: "Request failed with HTTP $code"
}

internal fun summarizeOpenRouterPricing(skus: JsonObject?): Triple<String, Double?, Double?> {
    if (skus.isNullOrEmpty()) return Triple("", null, null)
    val entries = skus.entries.mapNotNull { (k, v) ->
        (v as? JsonPrimitive)?.contentOrNull?.toDoubleOrNull()?.let { k to it }
    }
    if (entries.isEmpty()) return Triple("", null, null)
    val perSecEntries = entries.filter { (k, _) ->
        "second" in k || "sec" in k
    }
    val perGenEntries = entries.filter { (k, _) ->
        k == "generate" || "video" in k && "second" !in k
    }
    val perSecond = perSecEntries.minOfOrNull { it.second }
    val perVideo = perGenEntries.minOfOrNull { it.second }
    val min = entries.minOf { it.second }
    val max = entries.maxOf { it.second }
    val unit = when {
        perSecEntries.isNotEmpty() -> "/s"
        perGenEntries.isNotEmpty() -> "/video"
        else -> ""
    }
    val summary = if (min == max) "${VideoCloudClient.formatUsdValue(min)}$unit"
    else "${VideoCloudClient.formatUsdValue(min)}–${VideoCloudClient.formatUsdValue(max)}$unit"
    return Triple(summary, perSecond, perVideo)
}

internal fun buildDeepInfraPricingDetails(pricingObj: JsonObject?, full: String): List<VideoPricingEntry> {
    val table = pricingObj?.get("table") as? JsonObject
    val rows = table?.get("rows") as? JsonArray
    if (!rows.isNullOrEmpty()) {
        return rows.mapNotNull { row ->
            val cols = row as? JsonArray ?: return@mapNotNull null
            val label = (cols.getOrNull(0) as? JsonPrimitive)?.contentOrNull ?: return@mapNotNull null
            val value = (cols.getOrNull(1) as? JsonPrimitive)?.contentOrNull ?: return@mapNotNull null
            VideoPricingEntry(label, value)
        }
    }
    if (full.isNotBlank()) return listOf(VideoPricingEntry("Rate", full))
    val short = pricingObj?.string("short").orEmpty()
    return if (short.isNotBlank()) listOf(VideoPricingEntry("Rate", short)) else emptyList()
}

internal fun parseLeadingDollarRate(text: String, unitMarker: String): Double? {
    if (unitMarker !in text) return null
    val idx = text.indexOf('$')
    if (idx < 0) return null
    val num = text.substring(idx + 1).takeWhile { it.isDigit() || it == '.' }
    return num.toDoubleOrNull()
}

internal fun formatSkuLabel(raw: String): String =
    raw.replace('_', ' ').replace('-', ' ').trim().replaceFirstChar {
        if (it.isLowerCase()) it.titlecase(Locale.ROOT) else it.toString()
    }

internal fun formatUsd(value: Double?, fallback: String): String =
    if (value != null) VideoCloudClient.formatUsdValue(value) else "$$fallback"

internal fun isDeepInfraUtilityModel(id: String): Boolean {
    val lower = id.lowercase(Locale.ROOT)
    return lower.startsWith("bria/video_") ||
        lower.endsWith("-upscale") ||
        lower.endsWith("-reframe") ||
        lower.endsWith("-alphagen") ||
        lower.endsWith("-hdr")
}

internal data class DeepInfraCaps(
    val supportedDurations: List<Int> = emptyList(),
    val minDuration: Int? = null,
    val maxDuration: Int? = null,
    val defaultDuration: Int? = null,
    val supportedResolutions: List<String> = emptyList(),
    val supportedAspectRatios: List<String> = emptyList(),
    val supportedSizes: List<String> = emptyList(),
    val supportedFrameImages: List<String> = emptyList(),
    val supportsTextToVideo: Boolean = true,
    val supportsImageToVideo: Boolean = false,
    val supportsAudio: Boolean = false,
    val supportsNegativePrompt: Boolean = true,
)

internal fun inferDeepInfraCapabilities(id: String, type: String, tags: List<String>): DeepInfraCaps {
    val lower = id.lowercase(Locale.ROOT)
    val isI2vTag = type == "image-to-video" || ("image-to-video" in tags)
    val isT2vTag = type == "text-to-video" || ("text-to-video" in tags)
    val ratios = listOf("16:9", "9:16", "1:1", "4:3", "3:4", "21:9")
    val ltxStandardSizes = listOf(
        "1280x720", "720x1280",
        "1920x1080", "1080x1920",
        "2560x1440", "1440x2560",
        "3840x2160", "2160x3840",
    )
    val ltx25Sizes = ltxStandardSizes + listOf("1080x1080", "1440x1440", "1620x1080", "1080x1620")
    return when {
        lower.startsWith("wan-ai/wan3.0") -> DeepInfraCaps(
            minDuration = 2, maxDuration = 30, defaultDuration = 5,
            supportedResolutions = listOf("480P", "720P", "1080P"),
            supportedAspectRatios = listOf("adaptive") + ratios,
            supportedFrameImages = listOf("first_frame", "last_frame"),
            supportsTextToVideo = true, supportsImageToVideo = true, supportsAudio = true,
            supportsNegativePrompt = false,
        )
        lower.startsWith("wan-ai/wan2.7-i2v") -> DeepInfraCaps(
            minDuration = 2, maxDuration = 15, defaultDuration = 5,
            supportedResolutions = listOf("720P", "1080P"),
            supportedFrameImages = listOf("first_frame", "last_frame"),
            supportsTextToVideo = false, supportsImageToVideo = true,
        )
        lower.startsWith("wan-ai/wan2.7") -> DeepInfraCaps(
            minDuration = 2, maxDuration = 15, defaultDuration = 5,
            supportedResolutions = listOf("720P", "1080P"),
            supportedAspectRatios = ratios,
            supportedFrameImages = listOf("first_frame"),
            supportsTextToVideo = true, supportsImageToVideo = true,
        )
        lower.startsWith("wan-ai/wan2.6") -> DeepInfraCaps(
            minDuration = 2, maxDuration = 15, defaultDuration = 5,
            supportedResolutions = listOf("720p", "1080p"),
            supportedAspectRatios = ratios,
            supportedFrameImages = if ("i2v" in lower) listOf("first_frame") else emptyList(),
            supportsTextToVideo = "i2v" !in lower, supportsImageToVideo = "i2v" in lower,
        )
        lower.startsWith("wan-ai/wan2.2") || lower.startsWith("fastvideo/") -> DeepInfraCaps(
            supportedDurations = listOf(5), defaultDuration = 5,
            supportedResolutions = listOf("480p", "720p"),
            supportedAspectRatios = ratios,
            supportedFrameImages = if ("ti2v" in lower || isI2vTag) listOf("first_frame") else emptyList(),
            supportsTextToVideo = isT2vTag || "t2v" in lower || "ti2v" in lower,
            supportsImageToVideo = isI2vTag || "ti2v" in lower,
        )
        lower.startsWith("google/veo") -> DeepInfraCaps(
            supportedResolutions = listOf("720p", "1080p"),
            supportedAspectRatios = listOf("16:9", "9:16"),
            supportedFrameImages = listOf("first_frame"),
            supportsTextToVideo = true, supportsImageToVideo = true, supportsAudio = true,
        )
        lower.startsWith("lightricks/ltx") && "pro" in lower -> DeepInfraCaps(
            supportedDurations = listOf(6, 8, 10), defaultDuration = 6,
            supportedSizes = if ("2.5" in lower) ltx25Sizes else ltxStandardSizes,
            supportedFrameImages = listOf("first_frame", "last_frame"),
            supportsTextToVideo = true, supportsImageToVideo = true, supportsAudio = true,
            supportsNegativePrompt = false,
        )
        lower.startsWith("lightricks/ltx") -> DeepInfraCaps(
            supportedDurations = listOf(6, 8, 10, 12, 16, 20),
            minDuration = 6, maxDuration = 20, defaultDuration = 6,
            supportedSizes = if ("2.5" in lower) ltx25Sizes else ltxStandardSizes,
            supportedFrameImages = listOf("first_frame", "last_frame"),
            supportsTextToVideo = true, supportsImageToVideo = true, supportsAudio = true,
            supportsNegativePrompt = false,
        )
        lower.startsWith("bytedance/seedance-2") -> DeepInfraCaps(
            minDuration = 4, maxDuration = 15, defaultDuration = 5,
            supportedResolutions = listOf("480p", "720p", "1080p"),
            supportedAspectRatios = ratios,
            supportedFrameImages = listOf("first_frame", "last_frame"),
            supportsTextToVideo = true, supportsImageToVideo = true, supportsAudio = true,
            supportsNegativePrompt = false,
        )
        lower.startsWith("bytedance/seedance") -> DeepInfraCaps(
            supportedDurations = listOf(5, 10), defaultDuration = 5,
            supportedResolutions = listOf("480p", "720p", "1080p"),
            supportedAspectRatios = ratios,
            supportedFrameImages = listOf("first_frame"),
            supportsTextToVideo = true, supportsImageToVideo = true, supportsAudio = true,
            supportsNegativePrompt = false,
        )
        lower.startsWith("prunaai/") -> DeepInfraCaps(
            minDuration = 1, maxDuration = 10, defaultDuration = 5,
            supportedResolutions = listOf("720p", "1080p"),
            supportedAspectRatios = ratios,
            supportedFrameImages = listOf("first_frame"),
            supportsTextToVideo = true, supportsImageToVideo = true, supportsAudio = true,
        )
        else -> DeepInfraCaps(
            minDuration = 5, maxDuration = 15, defaultDuration = 5,
            supportedResolutions = listOf("720p", "1080p"),
            supportedAspectRatios = ratios,
            supportedFrameImages = if (isI2vTag) listOf("first_frame") else emptyList(),
            supportsTextToVideo = isT2vTag || !isI2vTag,
            supportsImageToVideo = isI2vTag,
        )
    }
}

internal fun JsonObject.string(name: String): String? =
    (this[name] as? JsonPrimitive)?.contentOrNull

internal fun JsonObject.doubleFieldOrNull(name: String): Double? =
    (this[name] as? JsonPrimitive)?.contentOrNull?.toDoubleOrNull()
