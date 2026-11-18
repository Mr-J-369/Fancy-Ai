package com.mrj.fancyai.ui.video

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.SystemClock
import android.text.format.DateUtils
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.edit
import com.mrj.fancyai.R
import com.mrj.fancyai.sd.SeedPolicy
import com.mrj.fancyai.ui.gallery.GENERATED_DIRECTORY
import com.mrj.fancyai.ui.gallery.MEDIA_DIRECTORY
import com.mrj.fancyai.ui.gallery.shareMedia
import com.mrj.fancyai.ui.settings.CloudSettingsStore
import com.mrj.fancyai.util.AppLog
import com.mrj.fancyai.util.IMAGE_EXTENSIONS
import com.mrj.fancyai.util.decodeImage
import com.mrj.fancyai.util.exportDocument
import com.mrj.fancyai.util.writeAtomicFile
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import java.io.File
import java.io.FileOutputStream
import java.util.Locale

private const val VIDEO_PREFERENCES = "video_generation"
private const val KEY_PROVIDER = "provider"
private const val KEY_PROMPT = "prompt"
private const val KEY_NEGATIVE_PROMPT = "negative_prompt"
private const val KEY_DURATION = "duration"
private const val KEY_RESOLUTION = "resolution"
private const val KEY_ASPECT_RATIO = "aspect_ratio"
private const val KEY_WIDTH = "width"
private const val KEY_HEIGHT = "height"
private const val KEY_AUDIO = "generate_audio"
private const val KEY_SEED = "seed"
private const val KEY_SEED_LOCKED = "seed_locked"
private const val VIDEO_CACHE_DIRECTORY = "video_frames"

private val videoJson = Json { ignoreUnknownKeys = true; encodeDefaults = true; explicitNulls = false }

internal class VideoController(
    private val app: Context,
    private val scope: CoroutineScope,
) {
    private val preferences = app.getSharedPreferences(VIDEO_PREFERENCES, Context.MODE_PRIVATE)
    private val client = VideoCloudClient(app)
    private var fetchJob: Job? = null
    private var generationJob: Job? = null

    var page by mutableStateOf(VideoPage.STUDIO)
    var state by mutableStateOf(loadInitialState())
        private set

    val filteredModels: List<VideoModelInfo>
        get() {
            val terms = state.query.trim().lowercase(Locale.ROOT).split(' ').filter(String::isNotBlank)
            return if (terms.isEmpty()) state.models
            else state.models.filter { model ->
                val haystack = "${model.id} ${model.name} ${model.description}".lowercase(Locale.ROOT)
                terms.all(haystack::contains)
            }
        }

    fun navigateBack(onExit: () -> Unit) {
        if (page == VideoPage.STUDIO) onExit() else page = VideoPage.STUDIO
    }

    fun refreshGalleryImages() {
        scope.launch(Dispatchers.IO) {
            val mediaRoot = File(app.filesDir, MEDIA_DIRECTORY)
            val files = mediaRoot.walkTopDown()
                .filter { it.isFile && (it.extension.lowercase(Locale.ROOT) in IMAGE_EXTENSIONS) }
                .sortedByDescending(File::lastModified)
                .take(80)
                .toList()
            withContext(Dispatchers.Main) {
                state = state.copy(galleryImages = files)
            }
        }
    }

    fun selectProvider(provider: VideoProvider) {
        if (state.provider == provider || state.generating) return
        fetchJob?.cancel()
        preferences.edit { putString(KEY_PROVIDER, provider.name) }
        val models = loadCachedModels(provider)
        val selected = loadSelectedModel(provider, models)
        state = reconcileSelection(
            state.copy(
                provider = provider,
                apiKey = CloudSettingsStore.cloudApiKey(app, provider.cloudProvider),
                models = models,
                selectedModel = selected,
                query = loadQuery(provider),
                loadingModels = false,
                modelsError = null,
                error = null,
            ),
            selected,
        )
        if (models.isEmpty()) fetchModels()
    }

    fun updateApiKey(value: String) {
        CloudSettingsStore.saveCloudApiKey(app, state.provider.cloudProvider, value)
        state = state.copy(apiKey = value, modelsError = null, error = null)
    }

    fun updateQuery(value: String) {
        preferences.edit { putString("query.${state.provider.name}", value) }
        state = state.copy(query = value)
    }

    fun fetchModels() {
        val provider = state.provider
        val key = state.apiKey.trim()
        fetchJob?.cancel()
        state = state.copy(loadingModels = true, modelsError = null)
        fetchJob = scope.launch {
            try {
                val fetched = client.fetchModels(provider, key)
                if (state.provider != provider) return@launch
                saveCachedModels(provider, fetched)
                val currentId = state.selectedModel?.id ?: preferences.getString("selected.${provider.name}", null)
                val selected = fetched.firstOrNull { it.id == currentId } ?: fetched.firstOrNull()
                selected?.let { saveSelectedModel(provider, it) }
                state = reconcileSelection(
                    state.copy(
                        models = fetched,
                        selectedModel = selected,
                        loadingModels = false,
                        modelsError = if (fetched.isEmpty()) app.getString(R.string.cloud_models_empty) else null,
                    ),
                    selected,
                )
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                if (state.provider == provider) {
                    AppLog.write(Log.ERROR, VIDEO_TAG, "Model fetch failed", failure)
                    state = state.copy(
                        loadingModels = false,
                        modelsError = failure.message ?: app.getString(R.string.cloud_models_failed),
                    )
                }
            }
        }
    }

    fun selectModel(model: VideoModelInfo) {
        saveSelectedModel(state.provider, model)
        state = reconcileSelection(state.copy(selectedModel = model, error = null), model)
    }

    fun updatePrompt(value: String) {
        preferences.edit { putString(KEY_PROMPT, value) }
        state = state.copy(prompt = value, error = null)
    }

    fun updateNegativePrompt(value: String) {
        preferences.edit { putString(KEY_NEGATIVE_PROMPT, value) }
        state = state.copy(negativePrompt = value)
    }

    fun updateDuration(seconds: Int?) {
        preferences.edit {
            if (seconds != null) putInt(KEY_DURATION, seconds) else remove(KEY_DURATION)
        }
        state = state.copy(durationSeconds = seconds)
    }

    fun updateResolution(value: String) {
        preferences.edit { putString(KEY_RESOLUTION, value) }
        state = state.copy(resolution = value)
    }

    fun updateAspectRatio(value: String) {
        preferences.edit { putString(KEY_ASPECT_RATIO, value) }
        state = state.copy(aspectRatio = value)
    }

    fun updateWidth(value: String) {
        val digits = value.filter(Char::isDigit)
        preferences.edit { putString(KEY_WIDTH, digits) }
        state = state.copy(width = digits)
    }

    fun updateHeight(value: String) {
        val digits = value.filter(Char::isDigit)
        preferences.edit { putString(KEY_HEIGHT, digits) }
        state = state.copy(height = digits)
    }

    fun selectSize(size: String) {
        val w = size.substringBefore('x').filter(Char::isDigit)
        val h = size.substringAfter('x', "").filter(Char::isDigit)
        if (w.isNotEmpty() && h.isNotEmpty()) {
            preferences.edit {
                putString(KEY_WIDTH, w)
                putString(KEY_HEIGHT, h)
            }
            state = state.copy(width = w, height = h)
        }
    }

    fun updateAudio(enabled: Boolean) {
        preferences.edit { putBoolean(KEY_AUDIO, enabled) }
        state = state.copy(generateAudio = enabled)
    }

    fun updateSeed(value: String) {
        preferences.edit { putString(KEY_SEED, value) }
        state = state.copy(seed = value)
    }

    fun toggleSeedLock() {
        val locked = !state.seedLocked
        preferences.edit { putBoolean(KEY_SEED_LOCKED, locked) }
        state = state.copy(seedLocked = locked)
    }

    fun rollSeed() {
        val rolled = SeedPolicy.roll().toString()
        preferences.edit { putString(KEY_SEED, rolled) }
        state = state.copy(seed = rolled)
    }

    fun pickFrameFromUri(slot: VideoFrameSlot, uri: Uri?) {
        uri ?: return
        scope.launch(Dispatchers.IO) {
            val saved = runCatching {
                val preview = decodeImage(app, uri, 1024)
                val cacheDir = File(app.cacheDir, VIDEO_CACHE_DIRECTORY).apply { mkdirs() }
                val file = File(cacheDir, "${slot.name.lowercase(Locale.ROOT)}_${System.currentTimeMillis()}.jpg")
                FileOutputStream(file).use { out ->
                    check(preview.compress(Bitmap.CompressFormat.JPEG, 92, out)) { "Compress failed" }
                    out.fd.sync()
                }
                file to preview
            }.getOrNull()
            withContext(Dispatchers.Main) {
                if (saved != null) {
                    applyFrame(slot, saved.first, saved.second)
                } else {
                    state = state.copy(error = app.getString(R.string.faceswap_failed))
                }
            }
        }
    }

    fun pickFrameFromGallery(slot: VideoFrameSlot, file: File) {
        scope.launch(Dispatchers.IO) {
            val preview = runCatching { decodeImage(app, Uri.fromFile(file), 512) }.getOrNull()
            withContext(Dispatchers.Main) {
                if (preview != null) {
                    applyFrame(slot, file, preview)
                } else {
                    state = state.copy(error = app.getString(R.string.faceswap_failed))
                }
            }
        }
    }

    fun clearFrame(slot: VideoFrameSlot) {
        when (slot) {
            VideoFrameSlot.FIRST -> {
                state.firstFramePreview?.recycle()
                state = state.copy(firstFrameFile = null, firstFramePreview = null)
            }
            VideoFrameSlot.LAST -> {
                state.lastFramePreview?.recycle()
                state = state.copy(lastFrameFile = null, lastFramePreview = null)
            }
        }
    }

    fun generate() {
        val model = state.selectedModel ?: return
        val key = state.apiKey.trim()
        if (key.isEmpty()) {
            state = state.copy(error = app.getString(R.string.cloud_key_required))
            page = VideoPage.MODELS
            return
        }
        val prompt = state.prompt.trim()
        if (prompt.isEmpty() && state.firstFrameFile == null) return
        if (state.generating) return

        val effectiveSeed = if (state.seedLocked) {
            state.seed.toLongOrNull()
        } else {
            SeedPolicy.roll().also { rolled ->
                preferences.edit { putString(KEY_SEED, rolled.toString()) }
                state = state.copy(seed = rolled.toString())
            }
        }
        val firstFile = state.firstFrameFile
        val lastFile = state.lastFrameFile?.takeIf { model.supportsLastFrame }

        state = state.copy(generating = true, statusStage = VideoStatusStage.SUBMITTING, error = null)
        val startedAt = SystemClock.elapsedRealtime()
        generationJob = scope.launch {
            try {
                val (firstUri, lastUri) = withContext(Dispatchers.IO) {
                    val first = firstFile?.let { VideoCloudClient.encodeFrameDataUri(app, it) }
                    val last = lastFile?.let { VideoCloudClient.encodeFrameDataUri(app, it) }
                    first to last
                }
                val dir = File(File(app.filesDir, MEDIA_DIRECTORY), GENERATED_DIRECTORY).apply { mkdirs() }
                val targetFile = File(dir, "video-${System.currentTimeMillis()}.mp4")
                val size = if (model.supportedSizes.isNotEmpty() || model.supportedResolutions.isEmpty()) {
                    val w = state.width.toIntOrNull()?.takeIf { it > 0 }
                    val h = state.height.toIntOrNull()?.takeIf { it > 0 }
                    if (w != null && h != null) "${w}x${h}" else ""
                } else {
                    ""
                }
                val request = VideoGenerationRequest(
                    provider = state.provider,
                    apiKey = key,
                    model = model,
                    prompt = prompt,
                    negativePrompt = state.negativePrompt.trim(),
                    durationSeconds = state.durationSeconds,
                    resolution = state.resolution.trim(),
                    aspectRatio = state.aspectRatio.trim(),
                    size = size,
                    generateAudio = state.generateAudio,
                    seed = effectiveSeed,
                    firstFrameDataUri = firstUri,
                    lastFrameDataUri = lastUri,
                )
                val outcome = client.generate(request, targetFile) { stage ->
                    scope.launch(Dispatchers.Main) {
                        if (state.generating) state = state.copy(statusStage = stage)
                    }
                }
                val elapsedMs = SystemClock.elapsedRealtime() - startedAt
                val metadata = withContext(Dispatchers.IO) {
                    val (width, height, probedSeconds) = probeVideoDimensions(outcome.file)
                    val meta = VideoGenerationMetadata(
                        cloudProvider = app.getString(state.provider.label),
                        model = model.id,
                        prompt = prompt,
                        negative = state.negativePrompt.trim(),
                        durationSeconds = probedSeconds ?: outcome.durationSeconds,
                        resolution = size.ifBlank { state.resolution.trim() },
                        aspectRatio = if (size.isNotBlank()) "" else state.aspectRatio.trim(),
                        audio = if (model.supportsAudio) state.generateAudio else null,
                        seed = effectiveSeed,
                        width = width,
                        height = height,
                        imageToVideo = firstFile != null,
                        costUsd = outcome.costUsd,
                        generationDurationMs = elapsedMs,
                    )
                    val sidecar = File(outcome.file.parentFile, "${outcome.file.nameWithoutExtension}.json")
                    writeAtomicFile(
                        sidecar,
                        videoJson.encodeToString(VideoGenerationMetadata.serializer(), meta).toByteArray(Charsets.UTF_8),
                    )
                    meta
                }
                state = state.copy(
                    generating = false,
                    statusStage = null,
                    resultFile = outcome.file,
                    resultMetadata = metadata,
                )
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                AppLog.write(Log.ERROR, VIDEO_TAG, "Video generation failed", failure)
                state = state.copy(
                    generating = false,
                    statusStage = null,
                    error = failure.message ?: app.getString(R.string.video_generation_failed),
                )
            }
        }
    }

    fun stop() {
        val wasGenerating = state.generating
        generationJob?.cancel()
        generationJob = null
        client.cancel()
        state = state.copy(
            generating = false,
            statusStage = null,
            error = if (wasGenerating) app.getString(R.string.video_stopped) else state.error,
        )
    }

    fun shareResult(context: Context = app) {
        val file = state.resultFile ?: return
        shareMedia(context, file)
    }

    fun exportResult(uri: Uri) {
        val file = state.resultFile ?: return
        scope.launch {
            withContext(Dispatchers.IO) {
                exportDocument(app, uri) { out ->
                    file.inputStream().use { it.copyTo(out) }
                }
            }
        }
    }

    fun deleteResult() {
        val file = state.resultFile ?: return
        scope.launch {
            val deleted = withContext(Dispatchers.IO) {
                val removed = file.delete()
                if (removed) {
                    File(file.parentFile, "${file.nameWithoutExtension}.json").delete()
                }
                removed
            }
            if (deleted) {
                state = state.copy(resultFile = null, resultMetadata = null)
            }
        }
    }

    fun copyResultDetails() {
        val meta = state.resultMetadata ?: return
        val lines = buildList {
            if (meta.prompt.isNotBlank()) add(meta.prompt)
            if (meta.negative.isNotBlank()) add("${app.getString(R.string.aura_negative_prompt)}: ${meta.negative}")
            add("${app.getString(R.string.gallery_details_provider)}: ${meta.cloudProvider}")
            add("${app.getString(R.string.label_model)}: ${meta.model}")
            meta.durationSeconds?.let { add("${app.getString(R.string.video_duration)}: ${it}s") }
            if (meta.resolution.isNotBlank()) add("${app.getString(R.string.video_resolution)}: ${meta.resolution}")
            if (meta.aspectRatio.isNotBlank()) add("${app.getString(R.string.video_aspect_ratio)}: ${meta.aspectRatio}")
            meta.audio?.let {
                add("${app.getString(R.string.gallery_details_audio)}: ${app.getString(if (it) R.string.gallery_audio_on else R.string.gallery_audio_off)}")
            }
            meta.seed?.let { add("${app.getString(R.string.field_seed)}: $it") }
            meta.costUsd?.let { add(app.getString(R.string.video_cost_reported, VideoCloudClient.formatUsdValue(it))) }
            meta.generationDurationMs?.let {
                add(app.getString(R.string.aura_generation_duration, DateUtils.formatElapsedTime((it + 500L) / 1000L)))
            }
        }
        app.getSystemService(ClipboardManager::class.java)
            ?.setPrimaryClip(ClipData.newPlainText(app.getString(R.string.home_app_video_generation), lines.joinToString("\n")))
    }

    fun release() {
        fetchJob?.cancel()
        generationJob?.cancel()
        client.cancel()
        state.firstFramePreview?.recycle()
        state.lastFramePreview?.recycle()
    }

    private fun applyFrame(slot: VideoFrameSlot, file: File, preview: Bitmap) {
        when (slot) {
            VideoFrameSlot.FIRST -> {
                state.firstFramePreview?.recycle()
                state = state.copy(firstFrameFile = file, firstFramePreview = preview, error = null)
            }
            VideoFrameSlot.LAST -> {
                state.lastFramePreview?.recycle()
                state = state.copy(lastFrameFile = file, lastFramePreview = preview, error = null)
            }
        }
    }

    private fun loadInitialState(): VideoStudioState {
        val provider = runCatching {
            VideoProvider.valueOf(preferences.getString(KEY_PROVIDER, VideoProvider.OPENROUTER.name).orEmpty())
        }.getOrDefault(VideoProvider.OPENROUTER)
        val models = loadCachedModels(provider)
        val selected = loadSelectedModel(provider, models)
        val storedDuration = if (preferences.contains(KEY_DURATION)) preferences.getInt(KEY_DURATION, 0).takeIf { it > 0 } else null
        return reconcileSelection(
            VideoStudioState(
                provider = provider,
                apiKey = CloudSettingsStore.cloudApiKey(app, provider.cloudProvider),
                models = models,
                selectedModel = selected,
                query = loadQuery(provider),
                prompt = preferences.getString(KEY_PROMPT, "").orEmpty(),
                negativePrompt = preferences.getString(KEY_NEGATIVE_PROMPT, "").orEmpty(),
                durationSeconds = storedDuration,
                resolution = preferences.getString(KEY_RESOLUTION, "").orEmpty(),
                aspectRatio = preferences.getString(KEY_ASPECT_RATIO, "").orEmpty(),
                width = preferences.getString(KEY_WIDTH, "").orEmpty(),
                height = preferences.getString(KEY_HEIGHT, "").orEmpty(),
                generateAudio = preferences.getBoolean(KEY_AUDIO, true),
                seed = preferences.getString(KEY_SEED, "").orEmpty(),
                seedLocked = preferences.getBoolean(KEY_SEED_LOCKED, false),
            ),
            selected,
        )
    }

    private fun reconcileSelection(current: VideoStudioState, model: VideoModelInfo?): VideoStudioState {
        if (model == null) return current
        val durations = model.durationOptions()
        val duration = when {
            current.durationSeconds != null && current.durationSeconds > 0 &&
                (durations.isEmpty() || current.durationSeconds in durations ||
                    (model.minDuration != null && model.maxDuration != null &&
                        current.durationSeconds in model.minDuration..model.maxDuration)) -> current.durationSeconds
            durations.isNotEmpty() -> model.defaultDuration?.takeIf { it in durations } ?: durations.first()
            model.minDuration != null && model.maxDuration != null -> {
                current.durationSeconds?.coerceIn(model.minDuration, model.maxDuration) ?: model.defaultDuration
            }
            else -> null
        }
        val resolution = when {
            model.supportedResolutions.isEmpty() -> ""
            current.resolution in model.supportedResolutions -> current.resolution
            current.resolution.isNotBlank() && model.supportedResolutions.any { it.equals(current.resolution, ignoreCase = true) } ->
                model.supportedResolutions.first { it.equals(current.resolution, ignoreCase = true) }
            else -> model.supportedResolutions.firstOrNull { it.equals("720p", ignoreCase = true) }
                ?: model.supportedResolutions.first()
        }
        val aspectRatio = when {
            model.supportedAspectRatios.isEmpty() -> ""
            current.aspectRatio.isNotBlank() -> current.aspectRatio
            else -> model.supportedAspectRatios.first()
        }
        var width = current.width
        var height = current.height
        if (model.supportedSizes.isNotEmpty() && (width.toIntOrNull() == null || height.toIntOrNull() == null)) {
            val defaultSize = model.supportedSizes.first()
            width = defaultSize.substringBefore('x', "")
            height = defaultSize.substringAfter('x', "")
        }
        return current.copy(
            durationSeconds = duration,
            resolution = resolution,
            aspectRatio = aspectRatio,
            width = width,
            height = height,
        )
    }

    private fun loadCachedModels(provider: VideoProvider): List<VideoModelInfo> {
        val raw = preferences.getString("models_v2.${provider.name}", null) ?: return emptyList()
        return runCatching { videoJson.decodeFromString<List<VideoModelInfo>>(raw) }.getOrDefault(emptyList())
    }

    private fun saveCachedModels(provider: VideoProvider, models: List<VideoModelInfo>) {
        preferences.edit {
            putString("models_v2.${provider.name}", videoJson.encodeToString(models))
        }
    }

    private fun loadSelectedModel(provider: VideoProvider, models: List<VideoModelInfo>): VideoModelInfo? {
        val id = preferences.getString("selected.${provider.name}", null)
        return models.firstOrNull { it.id == id } ?: models.firstOrNull()
    }

    private fun saveSelectedModel(provider: VideoProvider, model: VideoModelInfo) {
        preferences.edit { putString("selected.${provider.name}", model.id) }
    }

    private fun loadQuery(provider: VideoProvider): String =
        preferences.getString("query.${provider.name}", "").orEmpty()

    private fun probeVideoDimensions(file: File): Triple<Int, Int, Int?> {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(file.path)
            val width = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: 0
            val height = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: 0
            val durationMs = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull()
            val seconds = durationMs?.let { ((it + 500L) / 1000L).toInt().takeIf { s -> s > 0 } }
            Triple(width, height, seconds)
        } catch (_: Exception) {
            Triple(0, 0, null)
        } finally {
            runCatching { retriever.release() }
        }
    }

    companion object {
        private const val VIDEO_TAG = "VideoGen"
    }
}
