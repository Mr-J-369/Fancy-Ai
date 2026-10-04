package com.mrj.fancyai.ui.vision

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.edit
import com.mrj.fancyai.R
import com.mrj.fancyai.service.llm.ImagePrompt
import com.mrj.fancyai.service.llm.MacroBus
import com.mrj.fancyai.service.llm.generatePromptImage
import com.mrj.fancyai.service.llm.llmErrorResource
import com.mrj.fancyai.service.vision.VisionClient
import com.mrj.fancyai.service.vision.VisionEvents
import com.mrj.fancyai.service.vision.VisionFailure
import com.mrj.fancyai.service.vision.VisionRequest
import com.mrj.fancyai.service.vision.VisionService
import com.mrj.fancyai.ui.settings.documentInfo
import com.mrj.fancyai.util.decodeImage
import com.mrj.fancyai.util.prepareImage
import com.mrj.fancyai.vision.VisionModel
import com.mrj.fancyai.vision.VisionModels
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.CancellationException
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.coroutines.cancel as cancelScope

internal class VisionController(context: Context, responseState: MutableState<String>) : AutoCloseable,
    VisionEvents {
    private val app = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val preferences = app.getSharedPreferences(VISION_PREFERENCES, Context.MODE_PRIVATE)
    private val modelsStore = VisionModels(app)
    private val client = VisionClient(app)
    private val inputFile = File(app.cacheDir, VisionService.INPUT_IMAGE)
    private var copyJob: Job? = null
    private var imageJob: Job? = null

    var models by mutableStateOf<List<VisionModel>>(emptyList())
        private set
    var selectedPath by mutableStateOf(preferences.getString(KEY_MODEL, null))
        private set
    var prompt by mutableStateOf(preferences.getString(KEY_PROMPT, "").orEmpty())
        private set
    var image by mutableStateOf<Bitmap?>(null)
        private set
    var response by responseState
        private set
    var status by mutableStateOf<Int?>(null)
        private set
    var error by mutableStateOf<Int?>(null)
        private set
    var working by mutableStateOf(false)
        private set
    var preparingImage by mutableStateOf(false)
        private set
    var modelStatus by mutableStateOf<String?>(null)
        private set
    var modelProgress by mutableFloatStateOf(0f)
        private set
    var imagePath by mutableStateOf<String?>(null)
        private set
    private var imageRequested = false
    private val imageResponse = StringBuilder()
    var copied by mutableStateOf(value = false)
        private set

    fun load() {
        scope.launch {
            refreshModels()
            if (!inputFile.isFile) return@launch
            image = withContext(Dispatchers.IO) { decodeImage(app, Uri.fromFile(inputFile)) }
        }
    }

    fun updatePrompt(value: String) {
        prompt = value
        preferences.edit { putString(KEY_PROMPT, value) }
    }

    fun chooseImage(uri: Uri) {
        if (working || preparingImage) return
        preparingImage = true
        error = null
        scope.launch {
            try {
                val prepared = withContext(Dispatchers.IO) { prepareImage(app, uri, inputFile) }
                image = prepared
                response = ""
                status = null
            } finally {
                preparingImage = false
            }
        }
    }

    fun project() {
        val modelPath = selectedPath
        val question = MacroBus(app).text(prompt).trim()
        when {
            modelPath == null -> error = R.string.vision_model_required
            image == null -> error = R.string.vision_image_required
            question.isEmpty() -> error = R.string.vision_prompt_required
            (working || preparingImage || modelStatus != null) -> Unit
            else -> {
                response = ""
                imagePath = null
                imageResponse.clear()
                val imageInstruction = ImagePrompt.requestedInstruction(MacroBus(app), prompt)
                imageRequested = imageInstruction != null
                error = null
                status = R.string.vision_starting
                working = true
                try {
                    client.project(
                        VisionRequest(modelPath, inputFile.path, listOfNotNull(imageInstruction, question).joinToString("\n\n")),
                        this,
                    )
                } catch (failure: Exception) {
                    working = false
                    status = null
                    client.close()
                    if (failure is CancellationException) throw failure
                    error = R.string.vision_initialization_failed
                }
            }
        }
    }

    fun cancel() {
        if (working) client.cancel()
        imageJob?.cancel()
        imageJob = null
        working = false
        status = null
    }

    fun copyResponse() {
        app.getSystemService(ClipboardManager::class.java).setPrimaryClip(
            ClipData.newPlainText(app.getString(R.string.vision_response), response),
        )
        copied = true
        copyJob?.cancel()
        copyJob = scope.launch {
            delay(COPY_CONFIRMATION_MILLIS.milliseconds)
            copied = false
        }
    }

    fun importModel(uri: Uri) {
        if (working || modelStatus != null) return
        scope.launch {
            modelStatus = app.getString(R.string.vision_importing_model)
            modelProgress = 0f
            try {
                val document = documentInfo(app, uri, app.getString(R.string.vision_default_model_filename))
                val input = checkNotNull(app.contentResolver.openInputStream(uri))
                val imported = modelsStore.import(document.name, input, document.size) { copiedBytes, total ->
                    if (total > 0L) {
                        scope.launch { modelProgress = copiedBytes.toFloat() / total }
                    }
                }
                refreshModels(imported.path)
            } finally {
                modelStatus = null
            }
        }
    }

    fun downloadRecommended() {
        if (working || modelStatus != null) return
        scope.launch {
            modelStatus = app.getString(R.string.vision_downloading_model)
            modelProgress = 0f
            try {
                val installed = modelsStore.downloadRecommended { copiedBytes, total ->
                    if (total > 0L) {
                        scope.launch { modelProgress = copiedBytes.toFloat() / total }
                    }
                }
                refreshModels(installed.path)
            } finally {
                modelStatus = null
            }
        }
    }

    fun removeModel(model: VisionModel) {
        if (working || modelStatus != null) return
        scope.launch {
            withContext(Dispatchers.IO) { File(model.path).delete() }
            refreshModels()
        }
    }

    fun selectModel(model: VisionModel) {
        selectedPath = model.path
        preferences.edit { putString(KEY_MODEL, model.path) }
    }

    private suspend fun refreshModels(preferred: String? = selectedPath) {
        val installed = withContext(Dispatchers.IO) { modelsStore.installed() }
        val selected = preferred?.takeIf { path -> installed.any { it.path == path } }
            ?: installed.firstOrNull()?.path
        models = installed
        selectedPath = selected
        preferences.edit {
            selected?.let { putString(KEY_MODEL, it) } ?: remove(KEY_MODEL)
        }
    }

    override fun onLoading() {
        status = R.string.vision_reading_image
    }

    override fun onChunk(text: String) {
        if (imageRequested) imageResponse.append(text) else response += text
        status = R.string.vision_answering
    }

    override fun onComplete() {
        val (body, scene) = ImagePrompt.split(MacroBus(app).text(if (imageRequested) imageResponse.toString() else response), imageOnly = imageRequested)
        response = body
        imageJob = scope.launch {
            try {
                scene?.let {
                    status = R.string.chat_image_generating
                    imagePath = generatePromptImage(app, it, characterId = null).path
                }
                if (body.isBlank() && scene == null) {
                    status = null
                    error = R.string.vision_inference_failed
                } else status = R.string.vision_complete
            } catch (cancelled: CancellationException) {
                status = null
                throw cancelled
            } catch (failure: Exception) {
                status = null
                error = llmErrorResource(failure, R.string.aura_generation_failed)
            } finally {
                working = false
            }
        }
    }

    override fun onError(failure: VisionFailure) {
        working = false
        status = null
        error = failure.messageResource()
    }

    override fun onCancelled() {
        working = false
        status = null
    }

    override fun close() {
        client.close()
        scope.cancelScope()
    }

    companion object {
        private const val VISION_PREFERENCES = "vision"
        private const val KEY_MODEL = "model"
        private const val KEY_PROMPT = "prompt"
        private const val COPY_CONFIRMATION_MILLIS = 1_600L
    }
}

internal fun VisionFailure.messageResource(): Int = when (this) {
    VisionFailure.INVALID_REQUEST -> R.string.vision_invalid_request
    VisionFailure.INITIALIZATION -> R.string.vision_initialization_failed
    VisionFailure.OUT_OF_MEMORY -> R.string.vision_out_of_memory
    VisionFailure.INFERENCE -> R.string.vision_inference_failed
    VisionFailure.PROCESS_DIED -> R.string.vision_process_died
}
