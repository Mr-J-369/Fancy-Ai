package com.mrj.fancyai.sd

import android.content.Context
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.os.storage.StorageManager
import java.io.BufferedInputStream
import java.io.File
import java.io.FilterInputStream
import java.io.InputStream
import java.io.OutputStream
import java.io.PushbackInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.UUID
import java.util.concurrent.Executors
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

object SdModel {

    private const val IMPORT_BUFFER_BYTES = 16 * 1024 * 1024
    private const val IMPORT_PROGRESS_INTERVAL_BYTES = 128L * 1024 * 1024
    private const val ZIP_INFLATE_BUFFER_BYTES = 64 * 1024
    private const val EXPORT_BUFFER_BYTES = 1024 * 1024
    private class ProgressReporter(
        private val totalBytes: Long,
        private val onProgress: (Long, Long) -> Unit,
    ) {
        private var bytesRead = 0L
        private var lastReported = 0L

        fun advanced(count: Long) {
            bytesRead += count
            if ((bytesRead - lastReported) >= IMPORT_PROGRESS_INTERVAL_BYTES) {
                lastReported = bytesRead
                onProgress(bytesRead, totalBytes)
            }
        }

        fun complete() {
            val finalBytes = if (totalBytes > 0L) totalBytes else bytesRead
            onProgress(finalBytes, totalBytes)
        }
    }

    private class ProgressInputStream(
        input: InputStream,
        private val progress: ProgressReporter,
    ) : FilterInputStream(input) {
        override fun read(): Int = super.read().also { if (it >= 0) progress.advanced(1) }

        override fun read(buffer: ByteArray, offset: Int, length: Int): Int =
            super.read(buffer, offset, length).also { if (it > 0) progress.advanced(it.toLong()) }
    }

    /** ZipInputStream otherwise feeds the inflater just 512 bytes at a time. */
    private class FastZipInputStream(input: InputStream) : ZipInputStream(input) {
        init {
            `in` = PushbackInputStream(input, ZIP_INFLATE_BUFFER_BYTES)
            buf = ByteArray(ZIP_INFLATE_BUFFER_BYTES)
        }
    }

    private val sd15Qnn = listOf(
        "clip_v2.mnn", "token_emb.bin", "pos_emb.bin", "tokenizer.json",
        "unet.bin", "vae_decoder.bin",
    )

    private val sd15Mnn = listOf(
        "clip_v2.mnn", "clip_v2.mnn.weight", "token_emb.bin", "pos_emb.bin",
        "unet.mnn", "unet.mnn.weight",
        "vae_encoder.mnn", "vae_encoder.mnn.weight",
        "vae_decoder.mnn", "vae_decoder.mnn.weight",
        "tokenizer.json",
    )
    private val sdxlCommon = listOf(
        "clip.mnn", "token_emb.bin", "pos_emb.bin",
        "clip_2.mnn", "token_emb_2.bin", "pos_emb_2.bin", "tokenizer.json",
    )
    private val sdxlQnn = listOf("unet.bin", "vae_decoder.bin")

    /** SDXL MNN package emitted by convertsdxl/export_sdxl_mnn.sh. */
    private val sdxlMnn = listOf(
        "clip_2.mnn.weight",
        "unet.mnn", "unet.mnn.weight",
        "vae_encoder.mnn", "vae_decoder.mnn",
        "vae_tile_size.txt",
    )

    fun sanitize(name: String): String {
        val base = name.replace(Regex("\\.(zip|safetensors|gguf)$", RegexOption.IGNORE_CASE), "")
        val clean = base.trim().replace(Regex("[^A-Za-z0-9._-]"), "_").trim('_')
        return clean.ifBlank { "model_${System.currentTimeMillis()}" }
    }

    private fun checkSafetensorsUnsupportedDtype(file: File): String? {
        if (file.length() <= 8L) return null
        return runCatching {
            file.inputStream().use { stream ->
                val headerLenBytes = ByteArray(8)
                if (stream.read(headerLenBytes) == 8) {
                    val headerLen = ByteBuffer.wrap(headerLenBytes)
                        .order(ByteOrder.LITTLE_ENDIAN).long
                    if (headerLen in 10L..10_000_000L) {
                        val headerBytes = ByteArray(headerLen.toInt())
                        var read = 0
                        while (read < headerBytes.size) {
                            val count = stream.read(headerBytes, read, headerBytes.size - read)
                            if (count <= 0) break
                            read += count
                        }
                        val headerText = String(headerBytes, 0, read, Charsets.UTF_8)
                        if (headerText.contains("\"NVFP4\"", ignoreCase = true) || headerText.contains("nvfp4", ignoreCase = true)) {
                            return "NVIDIA NVFP4"
                        }
                    }
                }
            }
            null
        }.getOrNull()
    }

    private fun isDitInventory(dir: File): Boolean {
        val ditFile = dir.listFiles()?.firstOrNull {
            it.isFile && it.length() > 0L &&
                (it.name.endsWith(".safetensors", ignoreCase = true) || it.name.endsWith(".gguf", ignoreCase = true)) &&
                it.name != "vae.safetensors" && it.name != "ae.safetensors" && it.name != "llm.gguf" &&
                !it.name.contains("clip", ignoreCase = true) &&
                !it.name.contains("text_encoder", ignoreCase = true) &&
                !it.name.contains("t5xxl", ignoreCase = true)
        } ?: File(dir, "diffusion_model.gguf").takeIf { it.length() > 0L }
            ?: File(dir, "dit.gguf").takeIf { it.length() > 0L }
            ?: File(dir, "dit.safetensors").takeIf { it.length() > 0L }
        ditFile ?: return false

        if (ditFile.name.endsWith(".safetensors", ignoreCase = true)) {
            val unsupported = checkSafetensorsUnsupportedDtype(ditFile)
            if (unsupported != null) {
                error("This model uses $unsupported quantization, which is unsupported on mobile ARM devices. Please use GGUF (Q4_0), FP8, or BF16 models.")
            }
        }
        return true
    }

    fun validateImported(dir: File) {
        if (isDitInventory(dir)) return
        if (File(dir, "SDXL").exists()) {
            val requestedMnn = File(dir, "unet.mnn").exists()
            val missingCommon = sdxlCommon.filterNot { packageFilePresent(dir, it, requestedMnn) }
            val requiredRuntime = if (requestedMnn) sdxlMnn else sdxlQnn
            val missingRuntime = requiredRuntime.filterNot { packageFilePresent(dir, it, requestedMnn) }
            if (missingCommon.isNotEmpty() || missingRuntime.isNotEmpty()) {
                val runtime = if (requestedMnn) "MNN" else "QNN"
                error("Not a complete SDXL Aura $runtime package. Missing: ${missingCommon + missingRuntime}")
            }
            if (requestedMnn) {
                val tileSize = File(dir, "vae_tile_size.txt").readText().trim()
                require(tileSize == "640" || tileSize == "1024") {
                    "This MNN SDXL runtime requires 640px or 1024px VAE graphs."
                }
            }
            return
        }
        val requestedMnn = File(dir, "unet.mnn").exists()
        val required = if (requestedMnn) sd15Mnn else sd15Qnn
        val missing = required.filterNot { packageFilePresent(dir, it, requestedMnn) }
        if (missing.isNotEmpty()) {
            val runtime = if (requestedMnn) "MNN" else "QNN"
            error("Not a complete SD 1.5 Aura $runtime package. Missing: $missing")
        }
    }

    fun packageType(dir: File): SdPackageType {
        validateImported(dir)
        if (isDitInventory(dir)) {
            return SdPackageType(SdModelType.DIT, SdRuntimeType.DIT)
        }
        val model = if (File(dir, "SDXL").exists()) {
            SdModelType.SDXL
        } else {
            SdModelType.SD15
        }
        val sd15MnnRuntime = (model == SdModelType.SD15) && isMnnInventory(dir, sd15Mnn)
        val sdxlMnnRuntime = (model == SdModelType.SDXL) && isMnnInventory(dir, sdxlMnn)
        val runtime = if ((sd15MnnRuntime || sdxlMnnRuntime)) {
            SdRuntimeType.MNN
        } else {
            SdRuntimeType.QNN
        }
        return SdPackageType(model, runtime)
    }

    private fun isMnnInventory(dir: File, names: List<String>): Boolean = names.all { name ->
        nonEmptyPackageFile(dir, name)
    }

    private fun packageFilePresent(dir: File, name: String, requireContent: Boolean): Boolean {
        if (requireContent) return nonEmptyPackageFile(dir, name)
        return File(dir, name).isFile
    }

    private fun nonEmptyPackageFile(dir: File, name: String): Boolean {
        val file = File(dir, name)
        if (!file.isFile) return false
        return file.length() > 0L
    }

    fun import(
        ctx: Context,
        zip: InputStream,
        name: String,
        sizeBytes: Long = -1L,
        onStage: (ImportStage) -> Unit = {},
        onProgress: (Long, Long) -> Unit = { _, _ -> },
    ): File = importZipInto(File(ctx.filesDir, "sd_models"), zip, name, sizeBytes, onStage, onProgress)

    /**
     * Imports a document URI. Local/seekable documents use ZipFile; cloud providers and pipes
     * automatically retain the streaming path.
     */
    fun import(
        ctx: Context,
        uri: Uri,
        name: String,
        sizeBytes: Long = -1L,
        onStage: (ImportStage) -> Unit = {},
        onProgress: (Long, Long) -> Unit = { _, _ -> },
    ): File {
        val descriptor = ctx.contentResolver.openFileDescriptor(uri, "r")
            ?: error("Couldn't open this model package.")
        descriptor.use { pfd ->
            val randomAccessZip = runCatching {
                ZipFile(File("/proc/self/fd/${pfd.fd}"))
            }.getOrNull()
            randomAccessZip?.use {
                return importZipFileInto(
                    File(ctx.filesDir, "sd_models"),
                    it,
                    name,
                    onStage,
                    onProgress,
                )
            }
        }

        val fallbackDescriptor = ctx.contentResolver.openFileDescriptor(uri, "r")
            ?: error("Couldn't reopen this model package for streaming.")
        ParcelFileDescriptor.AutoCloseInputStream(fallbackDescriptor).use { input ->
            return importZipInto(
                File(ctx.filesDir, "sd_models"),
                input,
                name,
                sizeBytes,
                onStage,
                onProgress,
            )
        }
    }

    /** Uses the same random-access fast path for packages already stored as app-private files. */
    fun import(
        ctx: Context,
        zip: File,
        name: String,
        onStage: (ImportStage) -> Unit = {},
        onProgress: (Long, Long) -> Unit = { _, _ -> },
    ): File = ZipFile(zip).use { archive ->
        importZipFileInto(File(ctx.filesDir, "sd_models"), archive, name, onStage, onProgress)
    }

    /** Extracts and validates away from the installed model, then swaps it in as one operation. */
    fun importZipInto(
        modelsRoot: File,
        zip: InputStream,
        name: String,
        sizeBytes: Long = -1L,
        onStage: (ImportStage) -> Unit = {},
        onProgress: (Long, Long) -> Unit = { _, _ -> },
    ): File = prepareThenReplace(modelsRoot, name) { staging ->
        onStage(ImportStage.EXTRACTING)
        val written = mutableSetOf<String>()
        val progress = ProgressReporter(sizeBytes, onProgress)
        val progressInput = ProgressInputStream(zip, progress)
        val buffer = ByteArray(IMPORT_BUFFER_BYTES)
        FastZipInputStream(BufferedInputStream(progressInput, IMPORT_BUFFER_BYTES)).use { input ->
            var entry = input.nextEntry
            while (entry != null) {
                if (!entry.isDirectory) {
                    // Aura packages are intentionally flat. Reject duplicate basenames instead of
                    // silently letting a nested file overwrite another one during extraction.
                    val fileName = File(entry.name).name
                    require(fileName.isNotBlank() && written.add(fileName)) {
                        "This Aura package contains duplicate file names."
                    }
                    File(staging, fileName).outputStream().use { output ->
                        while (true) {
                            val count = input.read(buffer)
                            if (count < 0) break
                            output.write(buffer, 0, count)
                        }
                    }
                }
                input.closeEntry()
                entry = input.nextEntry
            }
        }
        progress.complete()
        validateImported(staging)
    }

    /** Extracts a seekable ZIP in archive order without staging the ZIP. */
    fun importZipFileInto(
        modelsRoot: File,
        zip: ZipFile,
        name: String,
        onStage: (ImportStage) -> Unit = {},
        onProgress: (Long, Long) -> Unit = { _, _ -> },
    ): File = prepareThenReplace(modelsRoot, name) { staging ->
        onStage(ImportStage.EXTRACTING)
        val entries = zip.entries().asSequence()
            .filterNot { it.isDirectory }
            .toList()
        val names = mutableSetOf<String>()
        val totalBytes = entries.sumOf { entry -> entry.size.coerceAtLeast(0L) }
        val progress = ProgressReporter(totalBytes, onProgress)
        val buffer = ByteArray(IMPORT_BUFFER_BYTES)

        for (entry in entries) {
            val fileName = File(entry.name).name
            require(fileName.isNotBlank() && names.add(fileName)) {
                "This Aura package contains duplicate file names."
            }
            val target = File(staging, fileName)
            zip.getInputStream(entry).use { input ->
                target.outputStream().use { output ->
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        output.write(buffer, 0, count)
                        progress.advanced(count.toLong())
                    }
                }
            }
        }
        progress.complete()
        validateImported(staging)
    }

    enum class ImportStage { EXTRACTING, COPYING, CONVERTING }

    /**
     * Writes an installed model back out as an importable package. Entries are flat, which is
     * what import expects: it rejects packages whose files collide by basename, and the
     * conversion markers (SDXL, qnn_context.txt, vae_tile_size.txt) are plain files.
     */
    fun export(
        dir: File,
        output: OutputStream,
        onProgress: (Long, Long) -> Unit = { _, _ -> },
    ) {
        val entries = dir.listFiles()
            ?.asSequence()
            ?.filter { it.isFile }
            ?.sortedBy { it.name }
            ?.toList()
            .orEmpty()
        require(entries.isNotEmpty()) { "This image model has no files to export." }
        val totalBytes = entries.sumOf { it.length() }
        val progress = ProgressReporter(totalBytes, onProgress)
        val buffer = ByteArray(EXPORT_BUFFER_BYTES)
        ZipOutputStream(output.buffered()).use { zip ->
            for (entry in entries) {
                writeZipEntry(zip, entry, buffer, progress)
            }
        }
        progress.complete()
    }

    private fun writeZipEntry(
        zip: ZipOutputStream,
        entry: File,
        buffer: ByteArray,
        progress: ProgressReporter,
    ) {
        zip.putNextEntry(ZipEntry(entry.name))
        entry.inputStream().use { input ->
            var count = input.read(buffer)
            while (count >= 0) {
                zip.write(buffer, 0, count)
                progress.advanced(count.toLong())
                count = input.read(buffer)
            }
        }
        zip.closeEntry()
    }

    class Lora(val name: String, val strength: Float, val open: () -> InputStream)

    fun importSafetensorsAsMnn(
        ctx: Context,
        input: InputStream,
        name: String,
        sizeBytes: Long,
        loras: List<Lora> = emptyList(),
        onStage: (ImportStage) -> Unit = {},
        onProgress: (Long, Long) -> Unit = { _, _ -> },
    ): File {
        if (sizeBytes > 0L) {
            val requiredBytes = sizeBytes + CONVERT_HEADROOM_BYTES
            val storage = ctx.getSystemService(StorageManager::class.java)
            val volume = storage.getUuidForPath(ctx.filesDir)
            require(storage.getAllocatableBytes(volume) >= requiredBytes) {
                "Not enough free space — conversion needs about %.1f GB free."
                    .format(requiredBytes / 1e9)
            }
            storage.allocateBytes(volume, requiredBytes)
        }
        return prepareThenReplace(File(ctx.filesDir, "sd_models"), name) { staging ->
            onStage(ImportStage.COPYING)
            val checkpoint = File(staging, "model.safetensors")
            copyWithProgress(input, checkpoint, sizeBytes, onProgress)
            val loraFiles = loras.mapIndexed { index, lora ->
                File(staging, "lora_$index.safetensors").also { target ->
                    lora.open().use { source ->
                        target.outputStream().buffered(IMPORT_BUFFER_BYTES).use(source::copyTo)
                    }
                }
            }
            mnnBaseAssets.forEach { asset ->
                ctx.assets.open("$MNN_ASSET_DIRECTORY/$asset").use { source ->
                    File(staging, asset).outputStream().buffered(IMPORT_BUFFER_BYTES).use(source::copyTo)
                }
            }

            onStage(ImportStage.CONVERTING)
            check(
                SdConvert.nativeConvert(
                    application = ctx,
                    dir = staging.path,
                    safetensorsName = checkpoint.name,
                    clipSkip2 = true,
                    loraNames = loraFiles.map(File::getName).toTypedArray(),
                    loraStrengths = FloatArray(loras.size) { loras[it].strength },
                ),
            ) {
                "Couldn't convert this checkpoint. Only standard SD 1.5 .safetensors models are supported."
            }
            checkpoint.delete()
            loraFiles.forEach(File::delete)
            validateImported(staging)
        }
    }

    private fun copyWithProgress(
        source: InputStream,
        target: File,
        totalBytes: Long,
        onProgress: (Long, Long) -> Unit,
    ) {
        var copied = 0L
        var lastReported = 0L
        val buffer = ByteArray(IMPORT_BUFFER_BYTES)
        source.use { input ->
            target.outputStream().buffered(IMPORT_BUFFER_BYTES).use { output ->
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    output.write(buffer, 0, count)
                    copied += count
                    if ((copied - lastReported) >= IMPORT_PROGRESS_INTERVAL_BYTES) {
                        lastReported = copied
                        onProgress(copied, totalBytes)
                    }
                }
            }
        }
        onProgress(copied, totalBytes)
    }

    @PublishedApi
    internal val cleanupExecutor by lazy { Executors.newSingleThreadExecutor() }

    /**
     * Keeps the currently installed model untouched until its replacement is complete. Both
     * directories live below [modelsRoot], so the final rename stays on one filesystem.
     */
    inline fun prepareThenReplace(
        modelsRoot: File,
        name: String,
        prepare: (File) -> Unit,
    ): File {
        modelsRoot.mkdirs()
        val safeName = sanitize(name)
        val nonce = UUID.randomUUID().toString()
        val staging = File(modelsRoot, ".$safeName.importing-$nonce")
        val installed = File(modelsRoot, safeName)
        val previous = File(modelsRoot, ".$safeName.previous-$nonce")
        check(staging.mkdirs()) { "Couldn't prepare storage for this model." }
        try {
            prepare(staging)
            if (installed.exists() && !installed.renameTo(previous)) {
                error("Couldn't replace the existing $safeName model.")
            }
            if (!staging.renameTo(installed)) {
                if (previous.exists()) previous.renameTo(installed)
                error("Couldn't finish installing $safeName.")
            }
            return installed
        } catch (failure: Throwable) {
            if (!installed.exists() && previous.exists()) previous.renameTo(installed)
            throw failure
        } finally {
            staging.deleteRecursively()
            if (previous.exists()) {
                if (installed.exists()) {
                    cleanupExecutor.execute { previous.deleteRecursively() }
                } else {
                    previous.deleteRecursively()
                }
            }
        }
    }

    fun hasVPredMarker(dir: String): Boolean = dir.isNotBlank() && File(dir, "V_PRED").exists()

    fun countTokens(dir: File, text: String): Int {
        val tokenizer = File(dir, "tokenizer.json")
        require(tokenizer.isFile) { "This model package has no tokenizer.json." }
        check(QnnDiffusion.nativeAbiVersion() == QnnDiffusion.ABI_VERSION) {
            "Unsupported QNN diffusion native ABI."
        }
        return QnnDiffusion.nativeCountTokens(tokenizer.path, text)
    }

    fun list(ctx: Context): List<File> = listIn(File(ctx.filesDir, "sd_models"))

    fun listIn(modelsRoot: File): List<File> =
        modelsRoot.listFiles()
            ?.asSequence()
            ?.filter { it.isDirectory && !it.name.startsWith('.') }
            ?.sorted()
            ?.toList()
            ?: emptyList()

    private const val CONVERT_HEADROOM_BYTES = 1_500_000_000L
    private const val MNN_ASSET_DIRECTORY = "aura_sd15"
    private val mnnBaseAssets = listOf(
        "unet.mnn",
        "vae_decoder.mnn",
        "vae_encoder.mnn",
        "clip_v2.mnn",
        "tokenizer.json",
    )
}
