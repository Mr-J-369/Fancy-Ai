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
import com.mrj.fancyai.sd.DitDiffusion
import com.mrj.fancyai.ui.settings.ImportDocument
import com.mrj.fancyai.ui.settings.documentInfo
import com.mrj.fancyai.util.AppLog
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import com.mrj.fancyai.sd.SdModel
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.util.UUID

enum class ConverterTarget(val label: String) {
    GGUF("GGUF"),
    MNN("MNN"),
}

enum class GgufQuant(val typeId: Int, val label: String) {
    Q8_0(8, "Q8_0"),
    Q4_0(2, "Q4_0"),
    F16(1, "F16"),
}

internal class AuraConverterController(val app: Context, private val scope: CoroutineScope) {
    private val preferences = app.getSharedPreferences(AURA_PREFERENCES, Context.MODE_PRIVATE)
    var checkpointUri by mutableStateOf<Uri?>(null)
        private set
    var checkpoint by mutableStateOf<ImportDocument?>(null)
        private set
    var target by mutableStateOf(ConverterTarget.GGUF)
    var importFamily by mutableStateOf(AuraImportFamily.SD15)
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
            listLoras(app, AuraLoraFamily.SD15)
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
            val lower = document.name.lowercase()
            if (lower.contains("sdxl") || lower.contains("pony") || lower.contains("photoxl") || lower.contains("xl")) {
                importFamily = AuraImportFamily.SDXL
            } else if (lower.contains("sd15") || lower.contains("sd1.5") || lower.contains("v1-5") || lower.contains("v1.5")) {
                importFamily = AuraImportFamily.SD15
            }
        } catch (failure: Exception) {
            AppLog.write(Log.ERROR, "AuraConvert", "Checkpoint document lookup failed", failure)
            error = app.getString(R.string.aura_import_failed)
        }
    }

    fun convert(quant: GgufQuant = this.ggufQuant) {
        val uri = checkpointUri ?: return
        val document = checkpoint ?: return
        operation = app.getString(R.string.aura_converter_copying)
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
                        ConverterTarget.GGUF -> convertToGguf(
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
                        ConverterTarget.MNN -> convertToMnn(
                            uri = uri,
                            name = document.name,
                            size = document.size,
                            onLine = { line ->
                                scope.launch {
                                    operation = line
                                    conversionLog = conversionLog + line
                                }
                            },
                        )
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

    private suspend fun convertToMnn(
        uri: Uri,
        name: String,
        size: Long,
        onLine: (String) -> Unit,
    ): File = withContext(Dispatchers.IO) {
        val job = currentCoroutineContext()
        val baseName = name.removeSuffix(".safetensors").removeSuffix(".ckpt")
        val stream = app.contentResolver.openInputStream(uri) ?: throw IOException("Cannot open $uri")
        val adapters = loras.filter { it.strength > 0f }
        onLine(app.getString(R.string.aura_converter_copying))
        val modelDir = stream.use { input ->
            SdModel.importSafetensorsAsMnn(
                ctx = app,
                input = input,
                name = baseName,
                sizeBytes = size,
                loras = adapters.map { lora ->
                    SdModel.Lora(lora.file.name, lora.strength, lora.file::inputStream)
                },
                onStage = { stage ->
                    job.ensureActive()
                    when (stage) {
                        SdModel.ImportStage.COPYING -> onLine(app.getString(R.string.aura_converter_copying))
                        SdModel.ImportStage.CONVERTING -> onLine(app.getString(R.string.aura_converter_converting_mnn))
                        else -> {}
                    }
                },
                onProgress = { copied, total ->
                    job.ensureActive()
                    if (total > 0L) {
                        progress = copied.toFloat() / total.toFloat()
                    }
                    if (copied % (64 * 1024 * 1024) == 0L || copied == total) {
                        onLine(app.getString(R.string.aura_converter_copied, copied / 1_000_000))
                    }
                },
            )
        }
        onLine("MNN conversion complete: ${modelDir.name}")
        modelDir
    }

    fun importLora(uri: Uri?) {
        uri ?: return
        operation = app.getString(R.string.aura_copying_lora)
        error = null
        job = scope.launch {
            try {
                withContext(Dispatchers.IO) {
                    com.mrj.fancyai.ui.aura.importLora(app, uri, AuraLoraFamily.SD15)
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
        saveLoraStrength(app, lora, value)
        loras = loras.map { if (it.file == lora.file) it.copy(strength = value) else it }
    }

    fun removeLora(lora: AuraLora) {
        deleteLora(app, lora)
        loras = loras.filterNot { it.file == lora.file }
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
                onLine(app.getString(R.string.aura_converter_copying))
                copyWithProgress(app.contentResolver.openInputStream(uri)!!, tempInput, size) { copied, total ->
                    job.ensureActive()
                    if (copied % (64 * 1024 * 1024) == 0L || copied == total) {
                        onLine(app.getString(R.string.aura_converter_copied, copied / 1_000_000))
                    }
                }
                tempInput
            }

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

            importFamily.marker?.let { File(destDir, it).createNewFile() }
            onLine("GGUF conversion complete: ${destFile.name} (${destFile.length() / (1024 * 1024)} MB)")
            destDir
        } finally {
            tempDir.deleteRecursively()
        }
    }

    companion object {
        val supportedFamilies: List<AuraImportFamily> = AuraImportFamily.entries.filter { it.marker != null }
    }
}
