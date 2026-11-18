package com.mrj.fancyai.ui.aura

import android.content.Context
import android.net.Uri
import android.util.Log
import android.widget.Toast
import androidx.core.content.edit
import com.mrj.fancyai.R
import com.mrj.fancyai.sd.SdModel
import com.mrj.fancyai.ui.settings.documentInfo
import com.mrj.fancyai.util.AppLog
import com.mrj.fancyai.util.PrivateHttp
import com.mrj.fancyai.util.PrivateHttp.hfDownloadUrl
import com.mrj.fancyai.util.downloadFile
import com.mrj.fancyai.util.exportDocument
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.longOrNull
import okhttp3.Request
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.util.concurrent.atomic.AtomicBoolean

internal const val STARTER_IMAGE_MODEL_ID = "CyberRealistic-LCM-Startup-model"
internal const val STARTER_IMAGE_MODEL_BYTES = 1_313_116_830L
internal const val UPSCALER_DEFAULT_NAME = "RealESRGAN_x2plus.pth"
private const val UPSCALER_DEFAULT_URL = "https://github.com/xinntao/Real-ESRGAN/releases/download/v0.2.1/RealESRGAN_x2plus.pth"
internal const val UPSCALER_DEFAULT_BYTES = 67_061_725L
private const val STARTER_IMAGE_MODEL_REPOSITORY = "Mr-J-369/Fancy-AI"
private const val TAE_TOTAL_BYTES = 9_848_636L + 10_723_524L + 22_642_902L
internal const val DIT_BASE_TOTAL_BYTES = 3_020_099_828L + TAE_TOTAL_BYTES
private const val AURA_TAG = "Aura"

private val TAE_FILES = listOf(
    DitBaseFile(
        "taef1.safetensors",
        "https://huggingface.co/madebyollin/taef1/resolve/main/diffusion_pytorch_model.safetensors",
        9_848_636L,
    ),
    DitBaseFile(
        "taef2.safetensors",
        "https://huggingface.co/madebyollin/taef2/resolve/main/taef2.safetensors",
        10_723_524L,
    ),
    DitBaseFile(
        "taew2_1.safetensors",
        "https://huggingface.co/OzzyGT/taew2_1/resolve/main/taew2_1.safetensors",
        22_642_902L,
    ),
)

private val TAE_DOWNLOAD_RUNNING = AtomicBoolean(false)

internal suspend fun ensureTaeComponentsInstalled(context: Context) {
    if (!TAE_DOWNLOAD_RUNNING.compareAndSet(false, true)) return
    try {
        val baseDir = File(context.filesDir, "dit_components")
        baseDir.mkdirs()
        for ((name, url, size) in TAE_FILES) {
            val target = File(baseDir, name)
            if ((!target.isFile) || (target.length() == 0L)) {
                try {
                    downloadFile(context, url, target, size)
                } catch (c: CancellationException) {
                    throw c
                } catch (error: Exception) {
                    AppLog.write(Log.WARN, AURA_TAG, "Auto-download failed for $name", error)
                }
            }
        }
    } finally {
        TAE_DOWNLOAD_RUNNING.set(false)
    }
}

internal fun isDitBaseSuiteInstalled(context: Context): Boolean {
    val baseDir = File(context.filesDir, "dit_components")
    return ((File(baseDir, "llm.gguf").length() > 0L) &&
        (File(baseDir, "vae.safetensors").length() > 0L) &&
        (File(baseDir, "ae.safetensors").length() > 0L) &&
        (File(baseDir, "tokenizer.json").length() > 0L))
}

internal fun installedUpscalers(context: Context): List<File> =
    File(context.filesDir, "upscalers").listFiles { file ->
        file.isFile && file.length() > 0L && file.extension.lowercase() in UPSCALER_EXTENSIONS
    }.orEmpty().sortedBy { it.name.lowercase() }

private val UPSCALER_EXTENSIONS = setOf("safetensors", "gguf", "pth", "bin")

internal suspend fun installUpscalerModel(
    context: Context,
    onProgress: (Long, Long) -> Unit,
): File {
    val targetDir = File(context.filesDir, "upscalers").apply { mkdirs() }
    val target = File(targetDir, UPSCALER_DEFAULT_NAME)
    downloadFile(
        context = context,
        url = UPSCALER_DEFAULT_URL,
        target = target,
        expectedSize = UPSCALER_DEFAULT_BYTES,
        applyHfMirror = false,
        onProgress = onProgress,
    )
    return target
}

internal fun copyWithProgress(
    source: InputStream,
    target: File,
    total: Long,
    onProgress: (Long, Long) -> Unit,
) {
    var copied = 0L
    var lastReportedTime = System.currentTimeMillis()
    onProgress(0L, total)
    val buffer = ByteArray(2 * 1024 * 1024)
    source.use { input ->
        target.outputStream().buffered(buffer.size).use { output ->
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                output.write(buffer, 0, count)
                copied += count
                val now = System.currentTimeMillis()
                if (now - lastReportedTime >= 250L) {
                    lastReportedTime = now
                    onProgress(copied, total)
                }
            }
        }
    }
    onProgress(copied, total)
}

internal fun AuraController.installStarterModel() {
    if (state.operation != null || state.generating) return
    scope.launch {
        val installed = try {
            withContext(Dispatchers.IO) {
                operation(app.getString(R.string.aura_downloading_starter), 0f)
                installStarterImageModel(app) { read, total ->
                    operation(
                        app.getString(R.string.aura_downloading_starter),
                        if (total > 0L) read.toFloat() / total else null,
                    )
                }
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            AppLog.write(Log.ERROR, AURA_TAG, "Starter image model download failed", failure)
            null
        } finally {
            operation(null)
        }
        if (installed == null) {
            state = state.copy(error = app.getString(R.string.aura_starter_install_failed))
        } else {
            refresh(installed.path)
        }
    }
}

internal fun AuraController.exportModel(model: AuraModel, destination: Uri) {
    if (state.operation != null || state.generating) return
    state = state.copy(operation = app.getString(R.string.action_export), operationProgress = 0f, error = null)
    scope.launch {
        try {
            withContext(Dispatchers.IO) {
                exportDocument(app, destination) { output ->
                    SdModel.export(model.file, output) { read, total ->
                        if (total > 0L) operation(app.getString(R.string.action_export), read.toFloat() / total)
                    }
                }
            }
            Toast.makeText(app, R.string.files_exported, Toast.LENGTH_LONG).show()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            AppLog.write(Log.ERROR, AURA_TAG, "Model export failed", failure)
            state = state.copy(error = app.getString(R.string.aura_export_failed))
        } finally {
            operation(null)
        }
    }
}

internal fun AuraController.importModel(uri: Uri?, family: AuraImportFamily) {
    uri ?: return
    if (state.operation != null || state.generating) return
    state = state.copy(operation = app.getString(R.string.aura_import_model), error = null)
    importJob = scope.launch {
        val (docName, docSize) = try {
            documentInfo(app, uri, "aura-model.zip")
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            AppLog.write(Log.ERROR, AURA_TAG, "Model document lookup failed", failure)
            state = state.copy(error = app.getString(R.string.aura_import_failed), operation = null)
            return@launch
        }
        val marker = family.marker
        val imported = try {
            withContext(Dispatchers.IO) {
                when {
                    docName.equals("vae.safetensors", ignoreCase = true) ||
                        docName.equals("ae.safetensors", ignoreCase = true) ->
                        copyModelFile(File(app.filesDir, "dit_components"), docName.lowercase(), docName, docSize, uri)
                    docName.equals("llm.gguf", ignoreCase = true) ||
                        docName.startsWith("clip", ignoreCase = true) ||
                        docName.startsWith("text_encoder", ignoreCase = true) ->
                        copyModelFile(File(app.filesDir, "dit_components"), "llm.gguf", docName, docSize, uri)
                    family == AuraImportFamily.UPSCALER ->
                        copyModelFile(File(app.filesDir, "upscalers"), docName, docName, docSize, uri)
                    docName.endsWith(".safetensors", ignoreCase = true) ||
                        docName.endsWith(".gguf", ignoreCase = true) -> {
                        val folderName = SdModel.sanitize(docName)
                        val folder = File(File(app.filesDir, "sd_models"), folderName)
                        val installed = copyModelFile(folder, docName, docName, docSize, uri)
                        marker?.let { File(folder, it).createNewFile() }
                        installed
                    }
                    else -> SdModel.import(
                        ctx = app,
                        uri = uri,
                        name = docName,
                        sizeBytes = docSize,
                        onStage = { operation(app.getString(R.string.aura_unpacking_model, docName)) },
                    ) { read, total ->
                        operation(
                            app.getString(R.string.aura_unpacking_model, docName),
                            if (total > 0L) read.toFloat() / total else null,
                        )
                    }
                }
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            AppLog.write(Log.ERROR, AURA_TAG, "Model import failed", failure)
            null
        } finally {
            operation(null)
        }
        if (imported == null) {
            state = state.copy(error = app.getString(R.string.aura_import_failed))
        } else if (family == AuraImportFamily.UPSCALER) {
            preferences.edit { putString(KEY_UPSCALER, File(imported, docName).absolutePath) }
            refresh()
        } else {
            unloadModel()
            val preferred = imported.path.takeIf { it != File(app.filesDir, "dit_components").path }
            refresh(preferred)
        }
    }
}

private fun AuraController.copyModelFile(
    targetDir: File,
    targetName: String,
    docName: String,
    docSize: Long,
    uri: Uri,
): File {
    targetDir.mkdirs()
    val target = File(targetDir, targetName)
    val partial = File(targetDir, ".$targetName.part")
    operation(app.getString(R.string.aura_copying_model, docName), 0f)
    val input = app.contentResolver.openInputStream(uri) ?: error("Could not open $docName")
    if (docName.endsWith(".safetensors", ignoreCase = true)) {
        input.use { source ->
            partial.outputStream().buffered().use { output ->
                AuraFp8Widen.copy(source, output) { read ->
                    operation(
                        app.getString(R.string.aura_copying_model, docName),
                        if (docSize > 0L) read.toFloat() / docSize else null,
                    )
                }
            }
        }
    } else {
        copyWithProgress(input, partial, docSize) { read, total ->
            operation(
                app.getString(R.string.aura_copying_model, docName),
                if (total > 0L) read.toFloat() / total else null,
            )
        }
    }
    if (target.exists()) target.delete()
    check(partial.renameTo(target)) { "Could not install $docName" }
    return targetDir
}

private data class StarterAsset(
    val path: String,
    val size: Long,
) {
    suspend fun install(context: Context, staging: File, onProgress: (Long) -> Unit): File {
        val relativePath = path.substringAfter("$STARTER_IMAGE_MODEL_ID/")
        val target = File(staging, relativePath)
        if (target.isFile && target.length() > 0L) return target
        val encodedPath = Uri.encode(path, "/")
        val downloadUrl = "https://huggingface.co/$STARTER_IMAGE_MODEL_REPOSITORY/resolve/main/$encodedPath"
        downloadFile(
            context = context,
            url = downloadUrl,
            target = target,
            expectedSize = size,
            onProgress = { copied, _ -> onProgress(copied) },
        )
        return target
    }
}

internal data class DitBaseFile(val name: String, val url: String, val size: Long)

private val DIT_BASE_FILES = listOf(
    DitBaseFile("tokenizer.json", "https://huggingface.co/Qwen/Qwen3-4B/resolve/main/tokenizer.json", 7_120_000L),
    DitBaseFile("vae.safetensors", "https://huggingface.co/zhiyuanasad/flux2_klein_adreno/resolve/main/vae.safetensors", 335_362_480L),
    DitBaseFile("ae.safetensors", "https://huggingface.co/Comfy-Org/z_image_turbo/resolve/main/split_files/vae/ae.safetensors", 335_304_388L),
    DitBaseFile("llm.gguf", "https://huggingface.co/zhiyuanasad/flux2_klein_adreno/resolve/main/llm.gguf", 2_342_312_960L),
) + TAE_FILES

internal fun AuraController.installDitBaseSuite() {
    if (state.operation != null || state.generating) return
    scope.launch {
        val baseDir = File(app.filesDir, "dit_components").apply { mkdirs() }
        var completedBytes = 0L
        val success = try {
            withContext(Dispatchers.IO) {
                operation(app.getString(R.string.aura_downloading_dit_base), 0f)
                DIT_BASE_FILES.forEach { (name, url, size) ->
                    val target = File(baseDir, name)
                    if ((!target.isFile) || (target.length() == 0L)) {
                        downloadFile(app, url, target, size) { copied, _ ->
                            val currentOverall = completedBytes + copied
                            val total = currentOverall.toFloat() / DIT_BASE_TOTAL_BYTES
                            operation(app.getString(R.string.aura_downloading_dit_base), total)
                        }
                    }
                    completedBytes += target.length()
                }
            }
            true
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            AppLog.write(Log.ERROR, AURA_TAG, "DiT Base Suite download failed", failure)
            false
        } finally {
            operation(null)
        }
        if (success) {
            refresh()
        } else {
            state = state.copy(error = app.getString(R.string.aura_dit_base_install_failed))
        }
    }
}

internal fun AuraController.installUpscalerModel(
    onCompleted: ((File) -> Unit)? = null,
) {
    if (state.operation != null || state.generating) return
    scope.launch {
        val installed = try {
            withContext(Dispatchers.IO) {
                operation(app.getString(R.string.aura_downloading_upscaler), 0f)
                installUpscalerModel(app) { copied, total ->
                    val progress = if (total > 0L) copied.toFloat() / total else null
                    operation(app.getString(R.string.aura_downloading_upscaler), progress)
                }
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            AppLog.write(Log.ERROR, AURA_TAG, "$UPSCALER_DEFAULT_NAME download failed", failure)
            null
        } finally {
            operation(null)
        }
        if (installed != null) {
            refresh()
            onCompleted?.invoke(installed)
        } else {
            state = state.copy(error = app.getString(R.string.aura_upscale_failed))
        }
    }
}


internal suspend fun installStarterImageModel(
    context: Context,
    onProgress: (Long, Long) -> Unit,
): File {
    val listingUrl = hfDownloadUrl(
        context,
        "https://huggingface.co/api/models/$STARTER_IMAGE_MODEL_REPOSITORY/tree/main/$STARTER_IMAGE_MODEL_ID?expand=true",
    )
    val listingReq = Request.Builder()
        .url(listingUrl)
        .header("User-Agent", "FancyAI/1.0 (Android)")
        .get()
        .build()
    val listing = PrivateHttp.apiClient.newCall(listingReq).execute().use { response ->
        if (!response.isSuccessful) throw IOException("Starter model listing failed with HTTP ${response.code}")
        response.body.string()
    }
    val entries = Json.parseToJsonElement(listing).jsonArray
    val assets = entries.asSequence().filterIsInstance<JsonObject>().mapNotNull { entry ->
        if (((entry["type"] as? JsonPrimitive)?.contentOrNull ?: "file") != "file") return@mapNotNull null
        val path = (entry["path"] as? JsonPrimitive)?.contentOrNull?.takeIf(String::isNotBlank) ?: return@mapNotNull null
        val lfs = entry["lfs"] as? JsonObject
        StarterAsset(
            path = path,
            size = ((entry["size"] as? JsonPrimitive)?.longOrNull ?: 0L).takeIf { it > 0L }
                ?: (lfs?.get("size") as? JsonPrimitive)?.longOrNull?.takeIf { it > 0L }
                ?: 0L,
        )
    }.toList()
    val models = File(context.filesDir, "sd_models")
    val destination = File(models, SdModel.sanitize(STARTER_IMAGE_MODEL_ID))
    val staging = File(models, ".${destination.name}.downloading").apply { mkdirs() }
    val total = assets.sumOf(StarterAsset::size)
    var completed = 0L
    assets.forEach { asset ->
        val target = asset.install(context, staging) { copied -> onProgress(completed + copied, total) }
        completed += target.length()
        onProgress(completed, total)
    }
    SdModel.validateImported(staging)
    check(destination.deleteRecursively())
    check(staging.renameTo(destination))
    return destination
}
