package com.mrj.fancyai.ui.aura

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.net.Uri
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.edit
import com.mrj.fancyai.R
import com.mrj.fancyai.sd.SdModelType
import com.mrj.fancyai.service.IImageService
import com.mrj.fancyai.service.llm.llmErrorResource
import com.mrj.fancyai.util.AppLog
import com.mrj.fancyai.util.decodeImage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.time.Duration.Companion.milliseconds

internal class AuraController(
    val app: Context,
    val scope: CoroutineScope,
) {
    val preferences: SharedPreferences = app.getSharedPreferences(AURA_PREFERENCES, Context.MODE_PRIVATE)
    var service by mutableStateOf<IImageService?>(null)
    var page by mutableStateOf(AuraPage.STUDIO)
    var state by mutableStateOf(
        AuraState(
            engine = auraEngine(app),
            selectedModelPath = preferences.getString(KEY_MODEL, null),
            prompt = preferences.getString(KEY_PROMPT, "").orEmpty(),
            negativePrompt = preferences.getString(KEY_NEGATIVE_PROMPT, "").orEmpty(),
        ),
    )

    fun dockAction(): AuraDockAction? = when {
        state.generating -> AuraDockAction.STOP
        page == AuraPage.STUDIO -> AuraDockAction.CREATE
        page != AuraPage.ENHANCE -> null
        (state.enhancementAvailability == EnhancementAvailability.READY) &&
            (state.engine == AuraEngine.LOCAL) && (!auraUpscalerFile(app, state.upscalerStyle).isFile) -> AuraDockAction.INSTALL_UPSCALER
        else -> AuraDockAction.ENHANCE
    }

    fun saveText(key: String, value: String) {
        if (key in setOf(KEY_PROMPT, KEY_NEGATIVE_PROMPT, "lan_address")) {
            preferences.edit { putString(key, value) }
            return
        }
        if (state.engine != AuraEngine.LOCAL && key == KEY_PROMPT_PREFIX) {
            updateRemoteSettings(state.remote.copy(promptPrefix = value))
            return
        }
        state.selectedModelPath?.let { path ->
            preferences.edit { putString("model.${File(path).name}.$key", value) }
        }
    }

    fun saveModelBoolean(key: String, value: Boolean) {
        state.selectedModelPath?.let { path ->
            preferences.edit { putBoolean("model.${File(path).name}.$key", value) }
        }
    }

    fun saveFloat(key: String, value: Float) {
        if (state.engine != AuraEngine.LOCAL) {
            when (key) {
                KEY_DENOISING -> updateRemoteSettings(state.remote.copy(denoising = value))
                KEY_REDRAW -> updateRemoteSettings(state.remote.copy(redraw = value))
            }
            return
        }
        state.selectedModelPath?.let { path ->
            preferences.edit { putFloat("model.${File(path).name}.$key", value) }
        }
    }

    fun operation(status: String?, progress: Float? = null) {
        scope.launch { state = state.copy(operation = status, operationProgress = progress) }
    }

    fun refresh(preferredModel: String? = state.selectedModelPath) {
        scope.launch {
            val models = withContext(Dispatchers.IO) { installedModels(app) }
            val ditBaseInstalled = withContext(Dispatchers.IO) { isDitBaseSuiteInstalled(app) }
            val qwenComponentsInstalled = withContext(Dispatchers.IO) { isQwenComponentsInstalled(app) }
            val selected = preferredModel
                ?.takeIf { path -> models.any { (file) -> file.path == path } }
                ?: models.firstOrNull()?.file?.path
            preferences.edit {
                selected?.let { putString(KEY_MODEL, it) } ?: remove(KEY_MODEL)
            }
            val refreshed = state.copy(
                models = models,
                selectedModelPath = selected,
                ditBaseInstalled = ditBaseInstalled,
                qwenComponentsInstalled = qwenComponentsInstalled,
                error = null,
            )
            state = models.firstOrNull { it.file.path == selected }
                ?.let { loadAuraModelSettings(preferences, it, refreshed) }
                ?: refreshed
            if (state.engine != AuraEngine.LOCAL) reloadRemoteSettings()
        }
    }

    fun navigateBack(onExit: () -> Unit) {
        if (page == AuraPage.STUDIO) onExit() else page = page.parent ?: AuraPage.STUDIO
    }

    fun selectEngine(engine: AuraEngine) {
        if (state.generating || state.operation != null) return
        preferences.edit { putString(KEY_AURA_ENGINE, engine.name) }
        state = state.copy(engine = engine, error = null, tokenCount = null)
        refresh()
    }

    fun reloadRemoteSettings() {
        if (state.engine == AuraEngine.LAN) {
            state = state.copy(lanAddress = auraLanAddress(app), tokenCount = null)
            return
        }
        val connection = remoteConnection(app, state.engine)
        val settings = remoteSettings(app, connection)
        state = state.copy(remote = settings, remoteCatalog = remoteCatalog(app, connection), promptPrefix = settings.promptPrefix, denoising = settings.denoising, redrawStrength = settings.redraw)
    }

    fun updateRemoteSettings(settings: AuraRemoteSettings) {
        saveRemoteSettings(app, remoteConnection(app, state.engine), settings)
        state = state.copy(remote = settings, promptPrefix = settings.promptPrefix, denoising = settings.denoising, redrawStrength = settings.redraw)
    }

    fun selectModel(model: AuraModel) {
        if (state.generating || state.operation != null) return
        preferences.edit {
            putString(KEY_MODEL, model.file.path)
            putString(KEY_AURA_ENGINE, AuraEngine.LOCAL.name)
        }
        state = loadAuraModelSettings(preferences, model, state.copy(engine = AuraEngine.LOCAL))
    }

    private var imageJob: Job? = null
    internal var importJob: Job? = null

    fun stop() {
        imageJob?.cancel()
        imageJob = null
        importJob?.cancel()
        importJob = null
        state = state.copy(
            generating = false,
            progress = 0,
            error = if (state.generating && (state.engine == AuraEngine.WEBUI || state.engine == AuraEngine.LOCAL_DREAM)) {
                app.getString(R.string.aura_remote_stopped)
            } else state.error,
        )
    }

    fun unloadModel() {
        scope.launch {
            try {
                withContext(Dispatchers.IO) { service?.unloadModels() }
                state = state.copy(operation = app.getString(R.string.aura_model_unloaded))
                delay(1600.milliseconds)
                if (state.operation == app.getString(R.string.aura_model_unloaded)) operation(null)
            } finally {
                operation(null)
            }
        }
    }

    fun outputFile(name: String): File =
        File(app.cacheDir, "$IMAGE_CACHE_DIRECTORY/$name").apply { parentFile?.mkdirs() }

    private fun validationError(enhance: Boolean): String? {
        if (enhance && state.enhancementAvailability != EnhancementAvailability.READY) {
            return app.getString(state.enhancementAvailability.message)
        }
        if (state.engine == AuraEngine.LOCAL && state.selectedModel?.type == SdModelType.DIT) {
            val isQwen = state.selectedModel?.file?.name?.contains("qwen", ignoreCase = true) == true
            if (isQwen && !state.qwenComponentsInstalled) {
                return app.getString(R.string.aura_qwen_components_note)
            } else if (!isQwen && !state.ditBaseInstalled) {
                return app.getString(R.string.aura_dit_base_note)
            }
        }
        return null
    }

    fun generate(enhance: Boolean = false) {
        if (state.generating || (!enhance && state.prompt.isBlank())) return
        val error = validationError(enhance)
        if (error != null) {
            state = state.copy(error = error)
            return
        }
        val config = auraGenerationConfig(app, state.prompt, rollSeed = !enhance)
        if (state.engine != AuraEngine.LOCAL) reloadRemoteSettings()
        val source = (if (state.engine == AuraEngine.LAN) null else if (enhance) state.resultPath else state.sourcePath)?.let(::File)
        val denoising = if (source != null) state.denoising else 0f
        val redraw = state.redrawStrength
        var metadata = if (enhance) state.resultMetadata else null
        state = state.copy(generating = true, progress = 0, error = null, seed = config.seed.toString())
        imageJob = scope.launch {
            try {
                val image = AuraImages.execute(
                    app,
                    source = source,
                    settings = config,
                    enhance = enhance,
                    denoising = denoising,
                    redrawStrength = redraw,
                    sourceMetadata = metadata,
                    onMetadata = { metadata = it },
                ) { percent ->
                    scope.launch {
                        if (state.generating) state = state.copy(progress = percent)
                    }
                }
                val bitmap = withContext(Dispatchers.IO) { decodeImage(app, Uri.fromFile(image)) }
                state.result?.recycle()
                state = state.copy(result = bitmap, resultPath = image.path, resultMetadata = metadata, progress = 100)
                if (enhance) page = AuraPage.STUDIO
            } catch (cancelled: CancellationException) {
                state = state.copy(progress = 0)
                throw cancelled
            } catch (failure: Exception) {
                AppLog.write(Log.ERROR, AURA_TAG, "Image operation failed", failure)
                state = state.copy(
                    progress = 0,
                    error = app.getString(
                        llmErrorResource(
                            failure,
                            (failure as? AuraNetworkException)?.messageResource
                                ?: if (enhance) R.string.aura_enhance_failed else R.string.aura_generation_failed,
                        ),
                    ),
                )
            } finally {
                state = state.copy(generating = false)
                imageJob = null
            }
        }
    }

    fun saveResult(share: Boolean) {
        val resultPath = state.resultPath ?: return
        scope.launch {
            val uri = try {
                withContext(Dispatchers.IO) { saveToPictures(app, File(resultPath)) }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                AppLog.write(Log.ERROR, AURA_TAG, "Image save failed", failure)
                null
            }
            if (uri == null) {
                state = state.copy(error = app.getString(R.string.aura_save_failed))
            } else if (share) {
                app.startActivity(
                    Intent(Intent.ACTION_SEND)
                        .setType("image/jpeg")
                        .putExtra(Intent.EXTRA_STREAM, uri)
                        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK),
                )
            } else {
                state = state.copy(operation = app.getString(R.string.aura_saved), operationProgress = null)
                delay(1600.milliseconds)
                if (state.operation == app.getString(R.string.aura_saved)) operation(null)
            }
        }
    }

    fun copyResultDetails() {
        val metadata = state.resultMetadata ?: return
        app.getSystemService(ClipboardManager::class.java).setPrimaryClip(
            ClipData.newPlainText(
                app.getString(R.string.aura_image_details),
                metadata.toCopyText(app),
            ),
        )
        state = state.copy(operation = app.getString(R.string.aura_details_copied))
        scope.launch {
            delay(1600.milliseconds)
            if (state.operation == app.getString(R.string.aura_details_copied)) operation(null)
        }
    }

    fun removeModel(model: AuraModel) {
        scope.launch {
            withContext(Dispatchers.IO) { model.file.deleteRecursively() }
            refresh(null)
        }
    }

    fun removeSource() {
        outputFile(SOURCE_FILE).delete()
        state.sourcePreview?.recycle()
        state = state.copy(sourcePath = null, sourcePreview = null)
    }

    fun chooseSource(uri: Uri?) {
        uri ?: return
        val model = state.selectedModel
        val size = if (state.engine == AuraEngine.LOCAL) {
            model?.size(state.orientation) ?: return
        } else {
            state.remote.width.toInt() to state.remote.height.toInt()
        }
        scope.launch {
            val prepared = try {
                withContext(Dispatchers.IO) {
                    prepareSource(app, uri, size.first, size.second, outputFile(SOURCE_FILE))
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                AppLog.write(Log.ERROR, AURA_TAG, "Source image preparation failed", failure)
                null
            }
            state = if (prepared == null) {
                state.copy(error = app.getString(R.string.aura_source_failed))
            } else {
                state.sourcePreview?.recycle()
                state.copy(
                    sourcePath = prepared.first.path,
                    sourcePreview = prepared.second,
                    error = null,
                )
            }
        }
    }
}

private const val AURA_TAG = "Aura"
