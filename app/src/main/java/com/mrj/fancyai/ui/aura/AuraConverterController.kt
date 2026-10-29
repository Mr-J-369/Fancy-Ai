package com.mrj.fancyai.ui.aura

import android.content.Context
import android.net.Uri
import android.os.SystemClock
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.edit
import com.mrj.fancyai.R
import com.mrj.fancyai.sd.SdModel
import com.mrj.fancyai.service.IImageService
import com.mrj.fancyai.service.InferenceForegroundService
import com.mrj.fancyai.service.llm.LlmEngineClient
import com.mrj.fancyai.ui.settings.ImportDocument
import com.mrj.fancyai.ui.settings.documentInfo
import com.mrj.fancyai.util.AppLog
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import com.mrj.fancyai.sd.DitDiffusion
import com.mrj.fancyai.sd.SdRuntime
import java.io.File
import java.io.IOException
import java.util.UUID

enum class ConverterTarget {
    GGUF,
    NPU,
}

enum class GgufQuant(val typeId: Int, val label: String) {
    Q8_0(8, "Q8_0"),
    Q4_0(2, "Q4_0"),
    F16(1, "F16"),
}

internal class AuraConverterController(val app: Context, private val scope: CoroutineScope) {
    private val preferences = app.getSharedPreferences(AURA_PREFERENCES, Context.MODE_PRIVATE)
    var service: IImageService? = null
    var checkpointUri by mutableStateOf<Uri?>(null)
        private set
    var checkpoint by mutableStateOf<ImportDocument?>(null)
        private set
    var target by mutableStateOf(ConverterTarget.GGUF)
    var ggufQuant by mutableStateOf(GgufQuant.Q8_0)
    var loras by mutableStateOf<List<AuraLora>>(emptyList())
        private set
    var operation by mutableStateOf<String?>(null)
        private set
    var progress by mutableStateOf<Float?>(null)
        private set
    var converting by mutableStateOf(false)
        private set
    var startedAt by mutableLongStateOf(0L)
        private set
    var installedModel by mutableStateOf<File?>(null)
        private set
    var cancelled by mutableStateOf(false)
        private set
    var error by mutableStateOf<String?>(null)
        private set
    var conversionLog by mutableStateOf<List<String>>(emptyList())
        private set
    var job: Job? = null
        private set

    suspend fun loadLoras() {
        loras = withContext(Dispatchers.IO) {
            File(app.filesDir, LORA_DIRECTORY).apply { mkdirs() }.listFiles()
                ?.filter { it.isFile && it.extension.equals("safetensors", ignoreCase = true) }
                ?.sortedBy(File::getName)
                ?.map { AuraLora(it, preferences.getFloat("lora_strength_${it.name}", 1f)) }
                .orEmpty()
        }
    }

    fun chooseCheckpoint(uri: Uri?) {
        uri ?: return
        try {
            val document = documentInfo(app, uri, "model.safetensors")
            checkpoint = document
            checkpointUri = uri
            installedModel = null
            cancelled = false
            error = null
            conversionLog = emptyList()
        } catch (failure: Exception) {
            AppLog.write(Log.ERROR, "AuraConvert", "Checkpoint document lookup failed", failure)
            error = app.getString(R.string.aura_import_failed)
        }
    }

    fun convert(target: ConverterTarget = this.target, quant: GgufQuant = this.ggufQuant) {
        val uri = checkpointUri ?: return
        val document = checkpoint ?: return
        val adapters = loras.filter { it.strength != 0f }
        operation = app.getString(R.string.aura_qnn_copying)
        progress = null
        converting = true
        cancelled = false
        startedAt = SystemClock.elapsedRealtime()
        installedModel = null
        error = null
        conversionLog = emptyList()
        job = scope.launch {
            try {
                val model = withContext(Dispatchers.IO) {
                    when (target) {
                        ConverterTarget.GGUF -> {
                            convertToGguf(
                                uri = uri,
                                name = document.name,
                                size = document.size,
                                quant = quant,
                                onLine = { line ->
                                    scope.launch {
                                        operation = line
                                        conversionLog = conversionLog + line
                                    }
                                },
                            )
                        }
                        ConverterTarget.NPU -> {
                            InferenceForegroundService.start(app)
                            LlmEngineClient.sessionMutex.withLock {
                                val session = LlmEngineClient.captureForImage()
                                try {
                                    service?.unloadModels()
                                    session?.first?.unload()
                                    AuraQnnConversion(app) { line ->
                                        scope.launch {
                                            operation = line
                                            conversionLog = conversionLog + line
                                        }
                                    }.convert(uri, document.name, document.size, adapters)
                                } finally {
                                    withContext(NonCancellable) {
                                        try {
                                            session?.let { (client, config) -> client.restoreAfterImage(config) }
                                        } catch (failure: Exception) {
                                            AppLog.write(Log.ERROR, "AuraConvert", "Text model restoration after conversion failed", failure)
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
                preferences.edit {
                    putString(KEY_MODEL, model.path)
                    putString(KEY_AURA_ENGINE, AuraEngine.LOCAL.name)
                }
                installedModel = model
            } catch (failure: CancellationException) {
                cancelled = true
                throw failure
            } catch (failure: Exception) {
                AppLog.write(Log.ERROR, "AuraConvert", "Checkpoint conversion failed", failure)
                error = app.getString(R.string.aura_import_failed)
            } finally {
                converting = false
                operation = null
                progress = null
            }
        }
    }

    private suspend fun convertToGguf(
        uri: Uri,
        name: String,
        size: Long,
        quant: GgufQuant,
        onLine: (String) -> Unit,
    ): File = withContext(Dispatchers.IO) {
        val tempDir = File(app.cacheDir, "gguf_convert_${UUID.randomUUID()}").apply { mkdirs() }
        val tempInput = File(tempDir, name)
        try {
            val job = currentCoroutineContext()
            val directPath = if (uri.scheme == "file") uri.path else null
            val inputFile = directPath?.let(::File)?.takeIf { it.isFile && it.canRead() } ?: run {
                onLine(app.getString(R.string.aura_qnn_copying))
                copyWithProgress(app.contentResolver.openInputStream(uri)!!, tempInput, size) { copied, total ->
                    job.ensureActive()
                    if (copied % (64 * 1024 * 1024) == 0L || copied == total) {
                        onLine(app.getString(R.string.aura_qnn_copied, copied / 1_000_000))
                    }
                }
                tempInput
            }

            val kind = com.mrj.fancyai.sd.detectDitModelKind(inputFile)
            val isSdxl = (kind == com.mrj.fancyai.sd.DIT_MODEL_SDXL)
            val familyMarker = if (isSdxl) "SDXL" else "SD15"
            val baseName = name.removeSuffix(".safetensors").removeSuffix(".ckpt")
            val folderName = "$baseName-${quant.label}"
            val modelsRoot = File(app.filesDir, "sd_models").apply { mkdirs() }
            var destDir = File(modelsRoot, folderName)
            var n = 2
            while (destDir.exists()) {
                destDir = File(modelsRoot, "$folderName-$n")
                n++
            }
            destDir.mkdirs()
            val destFile = File(destDir, "model.gguf")

            onLine(app.getString(R.string.aura_converter_converting_gguf, quant.label))

            val success = DitDiffusion.nativeConvert(
                inputPath = inputFile.absolutePath,
                vaePath = null,
                outputPath = destFile.absolutePath,
                outputType = quant.typeId,
            )
            if (!success || !destFile.exists() || destFile.length() == 0L) {
                destDir.deleteRecursively()
                val err = DitDiffusion.nativeLastError().ifEmpty { "GGUF conversion failed" }
                throw IOException(err)
            }

            File(destDir, familyMarker).createNewFile()
            onLine("GGUF conversion complete: ${destFile.name} (${destFile.length() / (1024 * 1024)} MB)")
            destDir
        } finally {
            tempDir.deleteRecursively()
        }
    }

    fun importLora(uri: Uri?) {
        uri ?: return
        operation = app.getString(R.string.aura_copying_lora)
        error = null
        job = scope.launch {
            try {
                val document = documentInfo(app, uri, "lora.safetensors")
                require(document.name.endsWith(".safetensors", ignoreCase = true))
                withContext(Dispatchers.IO) {
                    val root = File(app.filesDir, LORA_DIRECTORY).apply { mkdirs() }
                    val target = File(root, SdModel.sanitize(document.name) + ".safetensors")
                    val source = app.contentResolver.openInputStream(uri)!!
                    copyWithProgress(source, target, document.size) { read, total ->
                        scope.launch { progress = if (total > 0L) read.toFloat() / total else null }
                    }
                    preferences.edit { putFloat("lora_strength_${target.name}", 1f) }
                }
                loadLoras()
            } catch (failure: CancellationException) {
                throw failure
            } catch (failure: Exception) {
                AppLog.write(Log.ERROR, "AuraConvert", "LoRA import failed", failure)
                error = app.getString(R.string.aura_lora_import_failed)
            } finally {
                operation = null
                progress = null
            }
        }
    }

    fun setStrength(lora: AuraLora, value: Float) {
        preferences.edit { putFloat("lora_strength_${lora.file.name}", value) }
        loras = loras.map { if (it.file == lora.file) it.copy(strength = value) else it }
    }

    fun removeLora(lora: AuraLora) {
        if (lora.file.delete()) {
            preferences.edit { remove("lora_strength_${lora.file.name}") }
            loras = loras.filterNot { it.file == lora.file }
        }
    }
}
