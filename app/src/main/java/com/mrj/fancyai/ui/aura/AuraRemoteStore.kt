package com.mrj.fancyai.ui.aura

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Base64
import androidx.core.content.edit
import com.mrj.fancyai.sd.SeedPolicy
import com.mrj.fancyai.service.llm.MacroBus
import com.mrj.fancyai.util.PrivateHttp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import java.io.File
import java.security.MessageDigest
import java.util.UUID
import kotlin.time.Duration.Companion.milliseconds

internal suspend fun generateRemoteImage(
    config: AuraRemoteGenerationConfig,
    output: File,
    source: File?,
    enhance: Boolean,
    denoising: Float,
    redraw: Float,
    onProgress: (Int) -> Unit,
): AuraRenderedImage = withContext(Dispatchers.IO) {
    val api = AuraHttp(config.connection)
    var metadata = config.metadata
    val sourceImage = source?.let {
        Base64.encodeToString(it.readBytes(), Base64.NO_WRAP)
    }
    val bitmap = if (config.connection.engine == AuraEngine.LOCAL_DREAM) {
        val result = Json.parseToJsonElement(api.request("generate", buildJsonObject {
            put("prompt", metadata.prompt)
            put("negative_prompt", metadata.negative)
            put("steps", metadata.steps)
            put("cfg", metadata.cfg)
            put("seed", metadata.seed)
            put("scheduler", metadata.schedule)
            put("width", metadata.width)
            put("height", metadata.height)
            put("aspect_ratio", "${metadata.width}:${metadata.height}")
            if (sourceImage != null) {
                put("image", sourceImage)
                put("denoise_strength", denoising)
            }
        }, onEvent = { event ->
            if (event["type"]?.jsonPrimitive?.content == "progress") {
                val step = event.getValue("step").jsonPrimitive.content.toInt()
                val total = event.getValue("total_steps").jsonPrimitive.content.toInt()
                onProgress(step * 100 / total)
            }
        })).jsonObject
        val width = result.getValue("width").jsonPrimitive.content.toInt()
        val height = result.getValue("height").jsonPrimitive.content.toInt()
        val rgb = Base64.decode(result.getValue("image").jsonPrimitive.content, Base64.DEFAULT)
        val pixels = IntArray(width * height) { index ->
            val offset = index * 3
            (0xff shl 24) or ((rgb[offset].toInt() and 0xff) shl 16) or
                ((rgb[offset + 1].toInt() and 0xff) shl 8) or (rgb[offset + 2].toInt() and 0xff)
        }
        metadata = metadata.copy(seed = result["seed"]?.jsonPrimitive?.longOrNull ?: metadata.seed)
        onProgress(100)
        Bitmap.createBitmap(pixels, width, height, Bitmap.Config.ARGB_8888)
    } else {
        val encoded = if (enhance) {
            val upscaled = Json.parseToJsonElement(api.request("sdapi/v1/extra-single-image", buildJsonObject {
                put("image", sourceImage)
                put("upscaler_1", config.upscaler)
                put("upscaling_resize", 2)
                put("resize_mode", 0)
                put("show_extras_results", true)
            })).jsonObject.getValue("image").jsonPrimitive.content
            onProgress(if (redraw > 0f) 50 else 95)
            if (redraw > 0f) {
                val data = Base64.decode(upscaled.substringAfter(','), Base64.DEFAULT)
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeByteArray(data, 0, data.size, bounds)
                metadata = metadata.copy(width = bounds.outWidth, height = bounds.outHeight)
                val result = remoteDiffusion(api, config, metadata, upscaled, redraw) { onProgress(50 + it / 2) }
                metadata = result.second
                result.first
            } else upscaled
        } else {
            val result = remoteDiffusion(api, config, metadata, sourceImage, denoising, onProgress)
            metadata = result.second
            result.first
        }
        val bytes = Base64.decode(encoded.substringAfter(','), Base64.DEFAULT)
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size)!!
    }
    try {
        output.parentFile?.mkdirs()
        output.outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, 94, it) }
        AuraRenderedImage(output, metadata.copy(width = bitmap.width, height = bitmap.height))
    } finally {
        bitmap.recycle()
    }
}

private suspend fun remoteDiffusion(
    api: AuraHttp,
    config: AuraRemoteGenerationConfig,
    metadata: AuraImageMetadata,
    source: String?,
    denoising: Float,
    onProgress: (Int) -> Unit,
): Pair<String, AuraImageMetadata> = coroutineScope {
    val task = "task(aura-${UUID.randomUUID()})"
    var progress: Job? = null
    val payload = buildJsonObject {
        put("prompt", metadata.prompt)
        put("negative_prompt", metadata.negative)
        put("steps", metadata.steps)
        put("cfg_scale", metadata.cfg.toDouble())
        put("width", metadata.width)
        put("height", metadata.height)
        put("seed", metadata.seed)
        put("sampler_name", metadata.sampler)
        put("batch_size", 1)
        put("n_iter", 1)
        put("send_images", true)
        put("save_images", false)
        put("override_settings", buildJsonObject {
            put("sd_model_checkpoint", metadata.model)
        })
        put("override_settings_restore_afterwards", true)
        if (metadata.schedule.isNotBlank()) put("scheduler", metadata.schedule)
        if (source != null) {
            put("init_images", buildJsonArray { add(JsonPrimitive(source)) })
            put("denoising_strength", denoising.toDouble())
            put("resize_mode", 0)
        }
        if (config.catalog?.taskProgress == true) {
            put("force_task_id", task)
            progress = launch {
                while (isActive) {
                    delay(750.milliseconds)
                    try {
                        val status = Json.parseToJsonElement(api.request("internal/progress", buildJsonObject {
                            put("id_task", task)
                            put("live_preview", false)
                        })).jsonObject
                        if ((status["active"] as? JsonPrimitive)?.booleanOrNull == true) onProgress((((status["progress"] as? JsonPrimitive)?.doubleOrNull ?: 0.0) * 100).toInt().coerceIn(0, 99))
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (_: Exception) {
                    }
                }
            }
        }
    }
    try {
        val response = Json.parseToJsonElement(api.request(if (source == null) "sdapi/v1/txt2img" else "sdapi/v1/img2img", payload)).jsonObject
        val images = response["images"]!!.jsonArray
        val info = Json.parseToJsonElement(response["info"]!!.jsonPrimitive.content).jsonObject
        images[0].jsonPrimitive.content to metadata.copy(
            seed = ((info["seed"] as? JsonPrimitive)?.longOrNull ?: metadata.seed),
            steps = ((info["steps"] as? JsonPrimitive)?.intOrNull ?: metadata.steps),
            cfg = ((info["cfg_scale"] as? JsonPrimitive)?.doubleOrNull ?: metadata.cfg.toDouble()).toFloat(),
            sampler = ((info["sampler_name"] as? JsonPrimitive)?.contentOrNull ?: metadata.sampler),
            schedule = ((info["scheduler"] as? JsonPrimitive)?.contentOrNull ?: metadata.schedule),
            model = ((info["sd_model_name"] as? JsonPrimitive)?.contentOrNull ?: metadata.model),
        )
    } finally {
        progress?.cancelAndJoin()
    }
}

internal fun remoteConnection(context: Context, engine: AuraEngine): AuraRemoteConnection {
    val prefs = context.getSharedPreferences(AURA_PREFERENCES, Context.MODE_PRIVATE)
    return AuraRemoteConnection(engine, prefs.getString("remote.${engine.name}.url", if (engine == AuraEngine.LOCAL_DREAM) "http://127.0.0.1:8081" else "").orEmpty(), prefs.getString("remote.${engine.name}.username", "").orEmpty(), prefs.getString("remote.${engine.name}.password", "").orEmpty())
}

internal fun saveRemoteConnection(context: Context, connection: AuraRemoteConnection) {
    context.getSharedPreferences(AURA_PREFERENCES, Context.MODE_PRIVATE).edit {
        putString("remote.${connection.engine.name}.url", connection.url)
        putString("remote.${connection.engine.name}.username", connection.username)
        putString("remote.${connection.engine.name}.password", connection.password)
        if (connection.engine == AuraEngine.WEBUI) {
            val root = remoteKey(connection)
            putString("$root.username", connection.username)
            putString("$root.password", connection.password)
        }
    }
}

internal fun remoteKey(connection: AuraRemoteConnection): String {
    val normalized = runCatching { PrivateHttp.requiredBaseUrl(connection.url) }.getOrDefault(connection.url.trim())
    val hash = MessageDigest.getInstance("SHA-256").digest(normalized.toByteArray())
        .joinToString("") { "%02x".format(it) }
    return "remote.${connection.engine.name}.$hash"
}

internal fun remoteCatalog(context: Context, connection: AuraRemoteConnection): WebUiCatalog? =
    context.getSharedPreferences(AURA_PREFERENCES, Context.MODE_PRIVATE).getString("${remoteKey(connection)}.catalog", null)?.let {
        runCatching { WebUiCatalog.fromJson(it) }.getOrNull()
    }

internal fun remoteSettings(context: Context, connection: AuraRemoteConnection): AuraRemoteSettings {
    val prefs = context.getSharedPreferences(AURA_PREFERENCES, Context.MODE_PRIVATE)
    val root = remoteKey(connection)
    val model = prefs.getString("$root.model", "").orEmpty()
    return AuraRemoteSettings(
        model = model, steps = prefs.getString("$root.$model.steps", "20").orEmpty(), cfg = prefs.getString("$root.$model.cfg", "7").orEmpty(),
        width = prefs.getString("$root.$model.width", "512").orEmpty(), height = prefs.getString("$root.$model.height", "512").orEmpty(),
        sampler = prefs.getString("$root.$model.sampler", "").orEmpty(), scheduler = prefs.getString("$root.$model.scheduler", if (connection.engine == AuraEngine.LOCAL_DREAM) "dpm" else "").orEmpty(), seed = prefs.getString("$root.$model.seed", "0").orEmpty(),
        seedLocked = prefs.getBoolean("$root.$model.seed_locked", false),
        promptPrefix = prefs.getString("$root.$model.prefix", "").orEmpty(), upscaler = prefs.getString("$root.$model.upscaler", "").orEmpty(),
        denoising = prefs.getFloat("$root.$model.denoising", 0.75f),
        redraw = prefs.getFloat("$root.$model.redraw", 0f),
    )
}

internal fun saveRemoteSettings(context: Context, connection: AuraRemoteConnection, settings: AuraRemoteSettings) {
    val root = remoteKey(connection)
    context.getSharedPreferences(AURA_PREFERENCES, Context.MODE_PRIVATE).edit {
        putString("$root.model", settings.model)
        val key = "$root.${settings.model}"
        putString("$key.steps", settings.steps)
        putString("$key.cfg", settings.cfg)
        putString("$key.width", settings.width)
        putString("$key.height", settings.height)
        putString("$key.sampler", settings.sampler)
        putString("$key.scheduler", settings.scheduler)
        putString("$key.seed", settings.seed)
        putBoolean("$key.seed_locked", settings.seedLocked)
        putString("$key.prefix", settings.promptPrefix)
        putString("$key.upscaler", settings.upscaler)
        putFloat("$key.denoising", settings.denoising)
        putFloat("$key.redraw", settings.redraw)
    }
}

internal fun auraRemoteGenerationConfig(
    context: Context,
    prompt: String,
    includePrefix: Boolean,
    rollSeed: Boolean,
): AuraRemoteGenerationConfig {
    val connection = remoteConnection(context, auraEngine(context))
    val catalog = remoteCatalog(context, connection)
    val settings = remoteSettings(context, connection)
    val seed = if (settings.seedLocked || !rollSeed) settings.seed.toLong() else SeedPolicy.roll()
    if (rollSeed && !settings.seedLocked) saveRemoteSettings(context, connection, settings.copy(seed = seed.toString()))
    return AuraRemoteGenerationConfig(
        connection, catalog, settings.upscaler,
        AuraImageMetadata(
            prompt = MacroBus(context).text(if (includePrefix) studioPrompt(settings.promptPrefix, prompt) else prompt),
            negative = MacroBus(context).text(context.getSharedPreferences(AURA_PREFERENCES, Context.MODE_PRIVATE).getString(KEY_NEGATIVE_PROMPT, "").orEmpty().trim()),
            seed = seed, steps = settings.steps.toInt(), cfg = settings.cfg.toFloat(),
            width = settings.width.toInt(), height = settings.height.toInt(),
            sampler = settings.sampler, schedule = settings.scheduler, model = settings.model,
            modelType = "", runtime = connection.engine.name, backend = connection.engine.name,
            memoryPolicy = "", denoising = 0f,
        ),
    )
}

internal fun savedWebUiConnections(context: Context): List<AuraRemoteConnection> {
    val prefs = context.getSharedPreferences(AURA_PREFERENCES, Context.MODE_PRIVATE)
    return prefs.getStringSet("webui_addresses", emptySet()).orEmpty().sorted().map { url ->
        val connection = AuraRemoteConnection(AuraEngine.WEBUI, url)
        val root = remoteKey(connection)
        connection.copy(username = prefs.getString("$root.username", "").orEmpty(), password = prefs.getString("$root.password", "").orEmpty())
    }
}
