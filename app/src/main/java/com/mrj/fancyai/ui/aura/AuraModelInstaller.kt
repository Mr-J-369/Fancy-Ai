package com.mrj.fancyai.ui.aura

import android.content.Context
import android.net.Uri
import android.util.Log
import android.widget.Toast
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import com.mrj.fancyai.R
import com.mrj.fancyai.sd.SdModel
import com.mrj.fancyai.sd.hd.UpscaleStyle
import com.mrj.fancyai.sd.hd.UpscalerModels
import com.mrj.fancyai.ui.settings.documentInfo
import com.mrj.fancyai.util.AppLog
import com.mrj.fancyai.util.exportDocument
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
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

internal const val STARTER_IMAGE_MODEL_ID = "CyberRealistic-LCM-Startup-model"
internal const val STARTER_IMAGE_MODEL_BYTES = 1_313_116_830L
private const val STARTER_IMAGE_MODEL_REPOSITORY = "Mr-J-369/Fancy-AI"
internal const val DIT_BASE_TOTAL_BYTES = 3_020_099_828L
internal const val FLUX2_KLEIN_4B_BYTES = 4_355_000_000L
private const val FLUX2_KLEIN_4B_URL = "https://huggingface.co/black-forest-labs/FLUX.2-klein-4b-fp8/resolve/main/flux-2-klein-4b-fp8.safetensors"
internal const val ZIT_KHV_BYTES = 6_154_974_736L
private const val ZIT_KHV_URL = "https://huggingface.co/Mr-J-369/Fancy-AI/resolve/main/zitKHV_v10khv.safetensors"
private const val AURA_TAG = "Aura"

internal fun isDitBaseSuiteInstalled(context: Context): Boolean {
    val baseDir = File(context.filesDir, "dit_components")
    return (File(baseDir, "llm.gguf").length() > 0L &&
        File(baseDir, "vae.safetensors").length() > 0L &&
        File(baseDir, "ae.safetensors").length() > 0L &&
        File(baseDir, "tokenizer.json").length() > 0L)
}


internal fun auraUpscalerFile(context: Context, style: UpscaleStyle): File =
    File(File(context.filesDir, "upscalers"), style.fileName)

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

internal fun AuraController.installUpscaler(style: UpscaleStyle) {
    if (state.operation != null) return
    scope.launch {
        val model = UpscalerModels.of(style)
        val installed = try {
            withContext(Dispatchers.IO) {
                operation(app.getString(R.string.aura_downloading_upscaler), 0f)
                downloadVerified(model.url, auraUpscalerFile(app, style)) { read, total ->
                    operation(
                        app.getString(R.string.aura_downloading_upscaler),
                        if (total > 0L) read.toFloat() / total else null,
                    )
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
        state = if (installed) {
            state.copy(upscalerRevision = state.upscalerRevision + 1, error = null)
        } else {
            state.copy(error = app.getString(R.string.aura_upscaler_install_failed))
        }
    }
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

private val SDXL_VAE_FILES = listOf("vae_decoder.bin", "vae_encoder.bin")
private val sdxlVaeMutex = Mutex()

internal suspend fun ensureSdxlVaeCache(
    context: Context,
    onProgress: ((name: String, read: Long, total: Long) -> Unit)? = null,
): File = withContext(Dispatchers.IO) {
    val cacheDir = File(context.filesDir, "sdxl_vae").apply { mkdirs() }
    sdxlVaeMutex.withLock {
        val partials = mutableListOf<File>()
        try {
            val job = currentCoroutineContext()
            SDXL_VAE_FILES.forEach { name ->
                job.ensureActive()
                val target = File(cacheDir, name)
                if (!target.isFile || target.length() == 0L) {
                    val partial = File(cacheDir, ".$name.download")
                    partials += partial
                    val connection = URL(
                        "https://huggingface.co/Mr-J-369/Fancy-AI/resolve/main/$name",
                    ).openConnection() as HttpURLConnection
                    connection.connectTimeout = 20_000
                    connection.readTimeout = 60_000
                    connection.instanceFollowRedirects = true
                    try {
                        connection.connect()
                        check(connection.responseCode in 200..299) { "Download failed for $name" }
                        val fileTotal = connection.contentLengthLong
                        onProgress?.invoke(name, 0L, fileTotal)
                        copyWithProgress(connection.inputStream, partial, fileTotal) { read, total ->
                            job.ensureActive()
                            onProgress?.invoke(name, read, total)
                        }
                    } finally {
                        connection.disconnect()
                    }
                    job.ensureActive()
                    if (target.exists()) target.delete()
                    check(partial.renameTo(target)) { "Could not install $name" }
                    partials.remove(partial)
                }
            }
        } finally {
            partials.forEach { it.delete() }
        }
    }
    cacheDir
}

internal suspend fun installSdxlVae(
    context: Context,
    destination: File,
    onProgress: ((name: String, read: Long, total: Long) -> Unit)? = null,
) = withContext(Dispatchers.IO) {
    val cacheDir = ensureSdxlVaeCache(context, onProgress)
    val job = currentCoroutineContext()
    SDXL_VAE_FILES.forEach { name ->
        job.ensureActive()
        val source = File(cacheDir, name)
        val target = File(destination, name)
        if (target.exists()) target.delete()
        source.copyTo(target, overwrite = true)
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

internal fun AuraController.importModel(uri: Uri?) {
    uri ?: return
    if (state.operation != null || state.generating) return
    state = state.copy(operation = app.getString(R.string.aura_import_model), error = null)
    importJob = scope.launch {
        val document = try {
            documentInfo(app, uri, "aura-model.zip")
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            AppLog.write(Log.ERROR, AURA_TAG, "Model document lookup failed", failure)
            state = state.copy(error = app.getString(R.string.aura_import_failed), operation = null)
            return@launch
        }
        val imported = try {
            withContext(Dispatchers.IO) {
                when {
                    document.name.equals("vae.safetensors", ignoreCase = true) ||
                        document.name.equals("ae.safetensors", ignoreCase = true) ->
                        copyModelFile(File(app.filesDir, "dit_components"), document.name.lowercase(), document.name, document.size, uri)
                    document.name.equals("llm.gguf", ignoreCase = true) ||
                        document.name.startsWith("clip", ignoreCase = true) ||
                        document.name.startsWith("text_encoder", ignoreCase = true) ->
                        copyModelFile(File(app.filesDir, "dit_components"), "llm.gguf", document.name, document.size, uri)
                    document.name.endsWith(".safetensors", ignoreCase = true) ||
                        document.name.endsWith(".gguf", ignoreCase = true) -> {
                        val folderName = SdModel.sanitize(document.name)
                        copyModelFile(File(File(app.filesDir, "sd_models"), folderName), document.name, document.name, document.size, uri)
                    }
                    else -> SdModel.import(
                        ctx = app,
                        uri = uri,
                        name = document.name,
                        sizeBytes = document.size,
                        onStage = { stage -> operation(importStageLabel(app, stage, document.name)) },
                    ) { read, total ->
                        operation(
                            importStageLabel(app, SdModel.ImportStage.EXTRACTING, document.name),
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
    copyWithProgress(input, partial, docSize) { read, total ->
        operation(
            app.getString(R.string.aura_copying_model, docName),
            if (total > 0L) read.toFloat() / total else null,
        )
    }
    if (target.exists()) target.delete()
    check(partial.renameTo(target)) { "Could not install $docName" }
    return targetDir
}

private data class StarterAsset(
    val path: String,
    val size: Long,
) {
    fun install(staging: File, onProgress: (Long) -> Unit): File {
        val relativePath = path.substringAfter("$STARTER_IMAGE_MODEL_ID/")
        val target = File(staging, relativePath)
        if (target.isFile && target.length() > 0L) return target
        target.parentFile?.mkdirs()
        val partial = File(target.parentFile, ".${target.name}.part")
        val existing = partial.length().takeIf {
            if (size <= 0L) it > 0L else it in 1L until size
        } ?: 0L
        val encodedPath = Uri.encode(path, "/")
        val connection = (URL("https://huggingface.co/$STARTER_IMAGE_MODEL_REPOSITORY/resolve/main/$encodedPath").openConnection() as HttpURLConnection).apply {
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
)

internal fun downloadDirect(
    url: String,
    target: File,
    expectedSize: Long = 0L,
    onProgress: (Long, Long) -> Unit,
) {
    target.parentFile?.mkdirs()
    val partial = File(target.parentFile, ".${target.name}.download")
    val existing = partial.length().takeIf { length ->
        if (expectedSize <= 0L) length > 0L else length in 1L..<maxOf(2L, expectedSize)
    } ?: 0L
    val connection = (URL(url).openConnection() as HttpURLConnection).apply {
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

internal fun downloadVerified(
    url: String,
    target: File,
    onProgress: (Long, Long) -> Unit,
) = downloadDirect(url, target, onProgress = onProgress)

internal fun AuraController.installDitBaseSuite() {
    if (state.operation != null || state.generating) return
    scope.launch {
        val baseDir = File(app.filesDir, "dit_components").apply { mkdirs() }
        var completedBytes = 0L
        val success = try {
            withContext(Dispatchers.IO) {
                operation(app.getString(R.string.aura_downloading_dit_base), 0f)
                DIT_BASE_FILES.forEach { item ->
                    val target = File(baseDir, item.name)
                    if (!target.isFile || target.length() == 0L) {
                        downloadDirect(item.url, target, item.size) { copied, _ ->
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


internal fun AuraController.installDirectModel(
    modelId: String,
    marker: String,
    url: String,
    fileName: String = "dit.safetensors",
    expectedBytes: Long,
    downloadingRes: Int,
    failedRes: Int,
    logTag: String,
) {
    if (state.operation != null || state.generating) return
    scope.launch {
        val modelDir = File(File(app.filesDir, "sd_models"), modelId)
        val staging = File(File(app.filesDir, "sd_models"), ".$modelId.downloading").apply { mkdirs() }
        val target = File(staging, fileName)
        val success = try {
            withContext(Dispatchers.IO) {
                File(staging, marker).createNewFile()
                operation(app.getString(downloadingRes), 0f)
                downloadDirect(url, target, expectedBytes) { copied, total ->
                    val fileTotal = if (total > 0L) total else expectedBytes
                    operation(app.getString(downloadingRes), copied.toFloat() / fileTotal)
                }
                SdModel.validateImported(staging)
                modelDir.deleteRecursively()
                check(staging.renameTo(modelDir)) { "Could not finalize model installation" }
            }
            true
        } catch (cancelled: CancellationException) {
            withContext(Dispatchers.IO) { staging.deleteRecursively() }
            throw cancelled
        } catch (failure: Exception) {
            AppLog.write(Log.ERROR, AURA_TAG, "$logTag download failed", failure)
            withContext(Dispatchers.IO) { staging.deleteRecursively() }
            false
        } finally {
            operation(null)
        }
        if (success) {
            refresh(modelDir.path)
        } else {
            refresh(null)
            state = state.copy(error = app.getString(failedRes))
        }
    }
}

internal fun AuraController.installFlux2Klein() = installDirectModel(
    modelId = "flux2_klein_4b", marker = "KLEIN", url = FLUX2_KLEIN_4B_URL,
    fileName = "dit.safetensors", expectedBytes = FLUX2_KLEIN_4B_BYTES,
    downloadingRes = R.string.aura_downloading_flux2_klein,
    failedRes = R.string.aura_flux2_klein_install_failed, logTag = "FLUX.2 Klein 4B",
)

internal fun AuraController.installZitKhv() = installDirectModel(
    modelId = "zit_khv", marker = "ZIT", url = ZIT_KHV_URL,
    fileName = "dit.safetensors", expectedBytes = ZIT_KHV_BYTES,
    downloadingRes = R.string.aura_downloading_zit_khv,
    failedRes = R.string.aura_zit_khv_install_failed, logTag = "ZiT KHV",
)


internal fun installStarterImageModel(
    context: Context,
    onProgress: (Long, Long) -> Unit,
): File {
    val listingUrl = "https://huggingface.co/api/models/$STARTER_IMAGE_MODEL_REPOSITORY/" +
        "tree/main/$STARTER_IMAGE_MODEL_ID?expand=true"
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
        val target = asset.install(staging) { copied -> onProgress(completed + copied, total) }
        completed += target.length()
        onProgress(completed, total)
    }
    SdModel.validateImported(staging)
    check(destination.deleteRecursively())
    check(staging.renameTo(destination))
    return destination
}

internal fun importStageLabel(context: Context, stage: SdModel.ImportStage, name: String): String =
    context.getString(
        when (stage) {
            SdModel.ImportStage.EXTRACTING -> R.string.aura_unpacking_model
            SdModel.ImportStage.COPYING -> R.string.aura_copying_model
            SdModel.ImportStage.CONVERTING -> R.string.aura_converting_model
        },
        name,
    )
