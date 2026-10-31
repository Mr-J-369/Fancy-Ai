package com.mrj.fancyai.ui.aura

import android.content.Context
import android.net.Uri
import android.util.Log
import android.widget.Toast
import com.mrj.fancyai.R
import com.mrj.fancyai.sd.SdModel
import com.mrj.fancyai.ui.settings.documentInfo
import com.mrj.fancyai.util.AppLog
import com.mrj.fancyai.util.PrivateHttp.hfDownloadUrl
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
import java.io.File
import java.io.InputStream
import java.io.RandomAccessFile
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.atomic.AtomicBoolean

internal const val STARTER_IMAGE_MODEL_ID = "CyberRealistic-LCM-Startup-model"
internal const val STARTER_IMAGE_MODEL_BYTES = 1_313_116_830L
internal const val UPSCALER_MODEL_NAME = "RealESRGAN_x4plus_anime_6B.safetensors"
internal const val UPSCALER_MODEL_URL = "https://huggingface.co/easygoing0114/AI_upscalers/resolve/main/RealESRGAN_x4plus_anime_6B.safetensors"
internal const val UPSCALER_MODEL_BYTES = 17_888_780L
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

internal fun ensureTaeComponentsInstalled(context: Context) {
    if (!TAE_DOWNLOAD_RUNNING.compareAndSet(false, true)) return
    try {
        val baseDir = File(context.filesDir, "dit_components")
        baseDir.mkdirs()
        TAE_FILES.forEach { (name, url, size) ->
            val target = File(baseDir, name)
            if ((!target.isFile) || (target.length() == 0L)) {
                runCatching {
                    downloadDirect(context, url, target, size)
                }.onFailure {
                    AppLog.write(Log.WARN, AURA_TAG, "Auto-download failed for $name", it)
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

internal fun getInstalledUpscaler(context: Context): File? {
    val upscalerDir = File(context.filesDir, "upscalers")
    if (upscalerDir.isDirectory) {
        val preferred = File(upscalerDir, UPSCALER_MODEL_NAME)
        if (preferred.isFile && preferred.length() > 0L) return preferred

        val candidate = upscalerDir.listFiles()?.firstOrNull { file ->
            file.isFile && file.length() > 0L && (
                file.extension.equals("safetensors", ignoreCase = true) ||
                file.extension.equals("gguf", ignoreCase = true) ||
                file.extension.equals("pth", ignoreCase = true) ||
                file.extension.equals("bin", ignoreCase = true)
            )
        }
        candidate?.let { return it }
    }
    val ditDir = File(context.filesDir, "dit_components")
    if (ditDir.isDirectory) {
        val candidate = ditDir.listFiles()?.firstOrNull { file ->
            file.isFile && file.length() > 0L && file.name.contains("esrgan", ignoreCase = true) &&
                (file.extension.equals("safetensors", ignoreCase = true) || file.extension.equals("gguf", ignoreCase = true))
        }
        candidate?.let { return it }
    }
    return null
}

internal suspend fun installUpscalerModel(context: Context, onProgress: (Long, Long) -> Unit): File {
    val targetDir = File(context.filesDir, "upscalers").apply { mkdirs() }
    val target = File(targetDir, UPSCALER_MODEL_NAME)
    downloadResuming(
        context = context,
        logTag = "Aura",
        url = UPSCALER_MODEL_URL,
        target = target,
        expectedSize = UPSCALER_MODEL_BYTES,
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
                    docName.contains("esrgan", ignoreCase = true) ||
                        docName.contains("realesrgan", ignoreCase = true) ||
                        docName.contains("upscal", ignoreCase = true) ->
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
    fun install(context: Context, staging: File, onProgress: (Long) -> Unit): File {
        val relativePath = path.substringAfter("$STARTER_IMAGE_MODEL_ID/")
        val target = File(staging, relativePath)
        if (target.isFile && target.length() > 0L) return target
        target.parentFile?.mkdirs()
        val partial = File(target.parentFile, ".${target.name}.part")
        val existing = partial.length().takeIf {
            if (size <= 0L) it > 0L else it in 1L until size
        } ?: 0L
        val encodedPath = Uri.encode(path, "/")
        val downloadUrl = hfDownloadUrl(
            context,
            "https://huggingface.co/$STARTER_IMAGE_MODEL_REPOSITORY/resolve/main/$encodedPath",
        )
        val connection = (URL(downloadUrl).openConnection() as HttpURLConnection).apply {
            connectTimeout = 20_000
            readTimeout = 30_000
            instanceFollowRedirects = true
            if (existing > 0L) setRequestProperty("Range", "bytes=$existing-")
        }
        try {
            val resuming = connection.responseCode == HttpURLConnection.HTTP_PARTIAL
            val start = if (resuming) existing else 0L
            RandomAccessFile(partial, "rw").use { output ->
                if (!resuming) output.setLength(0L)
                output.seek(start)
                var copied = start
                val buffer = ByteArray(1024 * 1024)
                connection.inputStream.buffered(buffer.size).use { input ->
                    var count = input.read(buffer)
                    while (count >= 0) {
                        output.write(buffer, 0, count)
                        copied += count
                        onProgress(copied)
                        count = input.read(buffer)
                    }
                }
            }
            check(partial.renameTo(target))
        } finally {
            connection.disconnect()
        }
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

internal fun downloadDirect(
    context: Context,
    url: String,
    target: File,
    expectedSize: Long = 0L,
    onProgress: (Long, Long) -> Unit = { _, _ -> },
) {
    target.parentFile?.mkdirs()
    val partial = File(target.parentFile, ".${target.name}.download")
    val existing = partial.length().takeIf { length ->
        if (expectedSize <= 0L) length > 0L else length in 1L..<maxOf(2L, expectedSize)
    } ?: 0L
    val connection = (URL(hfDownloadUrl(context, url)).openConnection() as HttpURLConnection).apply {
        connectTimeout = 20_000
        readTimeout = 60_000
        instanceFollowRedirects = true
        setRequestProperty("User-Agent", "FancyAI/1.0 (Android)")
        if (existing > 0L) setRequestProperty("Range", "bytes=$existing-")
    }
    try {
        connection.connect()
        val responseCode = connection.responseCode
        val resuming = responseCode == HttpURLConnection.HTTP_PARTIAL
        val start = if (resuming) existing else 0L
        val contentLength = connection.contentLengthLong
        val total = when {
            resuming && contentLength > 0L -> start + contentLength
            contentLength > 0L -> contentLength
            expectedSize > 0L -> expectedSize
            else -> 0L
        }
        check(responseCode in 200..299) { "Download failed with HTTP $responseCode" }
        RandomAccessFile(partial, "rw").use { output ->
            if (!resuming) output.setLength(0L)
            output.seek(start)
            connection.inputStream.buffered(2 * 1024 * 1024).use { input ->
                streamToFile(input, output, start, total, onProgress)
            }
        }
        if (target.exists()) target.delete()
        check(partial.renameTo(target)) { "Could not install ${target.name}" }
    } finally {
        connection.disconnect()
    }
}

private fun streamToFile(
    input: InputStream,
    output: RandomAccessFile,
    start: Long,
    total: Long,
    onProgress: (Long, Long) -> Unit,
) {
    var copied = start
    var lastReportedTime = System.currentTimeMillis()
    onProgress(copied, total)
    val buffer = ByteArray(2 * 1024 * 1024)
    var count = input.read(buffer)
    while (count >= 0) {
        output.write(buffer, 0, count)
        copied += count
        val now = System.currentTimeMillis()
        if (now - lastReportedTime >= 250L) {
            lastReportedTime = now
            onProgress(copied, total)
        }
        count = input.read(buffer)
    }
    onProgress(copied, total)
}

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
                        downloadResuming(app, AURA_TAG, url, target, size) { copied, _ ->
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

internal fun AuraController.installUpscalerSuite(onCompleted: (() -> Unit)? = null) {
    if (state.operation != null || state.generating) return
    scope.launch {
        val success = try {
            withContext(Dispatchers.IO) {
                operation(app.getString(R.string.aura_downloading_upscaler), 0f)
                installUpscalerModel(app) { copied, total ->
                    val progress = if (total > 0L) copied.toFloat() / total else null
                    operation(app.getString(R.string.aura_downloading_upscaler), progress)
                }
            }
            true
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            AppLog.write(Log.ERROR, AURA_TAG, "Upscaler download failed", failure)
            false
        } finally {
            operation(null)
        }
        if (success) {
            refresh()
            onCompleted?.invoke()
        } else {
            state = state.copy(error = app.getString(R.string.aura_upscale_failed))
        }
    }
}


internal fun installStarterImageModel(
    context: Context,
    onProgress: (Long, Long) -> Unit,
): File {
    val listingUrl = hfDownloadUrl(
        context,
        "https://huggingface.co/api/models/$STARTER_IMAGE_MODEL_REPOSITORY/tree/main/$STARTER_IMAGE_MODEL_ID?expand=true",
    )
    val listing = (URL(listingUrl).openConnection() as HttpURLConnection).run {
        connectTimeout = 20_000
        readTimeout = 30_000
        instanceFollowRedirects = true
        try {
            inputStream.bufferedReader().use { it.readText() }
        } finally {
            disconnect()
        }
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
