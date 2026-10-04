package com.mrj.fancyai.engine

import android.content.Context
import android.net.Uri
import java.util.zip.ZipFile
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.UUID

data class GgufModel(
    override val name: String,
    override val path: String,
) : LocalLlmModel {
    override val sizeBytes: Long by lazy { File(path).length() }
    override val runtime = LocalLlmRuntime.LLAMA
    override val supportsSpeculativeDecoding = false
}

class GgufModels(private val directory: File) {
    fun installed(): List<GgufModel> {
        directory.mkdirs()
        return directory.listFiles()
            .orEmpty()
            .asSequence()
            .filter { it.isFile && it.extension.equals(EXTENSION, ignoreCase = true) }
            .sortedBy { it.name.lowercase() }
            .map { GgufModel(it.name, it.absolutePath) }
            .toList()
    }

    fun import(
        displayName: String,
        input: InputStream,
        sizeBytes: Long = -1L,
        onProgress: (Long, Long) -> Unit = { _, _ -> },
    ): GgufModel {
        val name = File(displayName).name
        require(name.endsWith(".$EXTENSION", ignoreCase = true)) { "Select a .$EXTENSION model." }
        require(name.none(Char::isISOControl)) { "The model filename is invalid." }
        directory.mkdirs()
        val target = File(directory, name)
        require(!target.exists()) { "$name is already installed." }
        val temporary = File(directory, ".${UUID.randomUUID()}.$EXTENSION")
        return try {
            FileOutputStream(temporary).use { output ->
                val magic = ByteArray(MAGIC.size)
                var offset = 0
                while (offset < magic.size) {
                    val read = input.read(magic, offset, magic.size - offset)
                    require(read > 0) { "The selected file is not GGUF." }
                    offset += read
                }
                require(magic.contentEquals(MAGIC)) { "The selected file is not GGUF." }
                output.write(magic)
                copyModelWithProgress(input, output, sizeBytes, magic.size.toLong(), onProgress)
                output.fd.sync()
            }
            Files.move(temporary.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE)
            GgufModel(target.name, target.absolutePath)
        } finally {
            temporary.delete()
        }
    }

    fun remove(model: GgufModel) {
        val file = File(model.path)
        require(file.parentFile?.canonicalFile == directory.canonicalFile) {
            "The selected model is outside Fancy's GGUF directory."
        }
        check(!file.exists() || file.delete()) { "Could not remove ${model.name}." }
    }

    private companion object {
        const val EXTENSION = "gguf"
        val MAGIC = byteArrayOf('G'.code.toByte(), 'G'.code.toByte(), 'U'.code.toByte(), 'F'.code.toByte())
    }
}

class LocalLlmModels(
    private val liteRt: LiteRtModels,
    private val llama: GgufModels,
    private val mnn: MnnModels,
) {
    fun installed(): List<LocalLlmModel> = (liteRt.installed() + llama.installed() + mnn.installed())
        .sortedWith(compareBy(LocalLlmModel::runtime, LocalLlmModel::name))

    fun import(
        displayName: String,
        input: InputStream,
        sizeBytes: Long = -1L,
        onProgress: (Long, Long) -> Unit = { _, _ -> },
    ): LocalLlmModel = when {
        displayName.endsWith(".litertlm", ignoreCase = true) ->
            liteRt.import(displayName, input, sizeBytes, onProgress)
        displayName.endsWith(".gguf", ignoreCase = true) ->
            llama.import(displayName, input, sizeBytes, onProgress)
        displayName.endsWith(".zip", ignoreCase = true) -> mnn.import(displayName, input, sizeBytes, onProgress)
        else -> throw IllegalArgumentException("Select a .litertlm, .gguf, or MNN .zip package.")
    }

    fun import(
        context: Context,
        uri: Uri,
        displayName: String,
        sizeBytes: Long,
        onProgress: (Long, Long) -> Unit,
    ): LocalLlmModel {
        if (displayName.endsWith(".zip", ignoreCase = true)) {
            context.contentResolver.openFileDescriptor(uri, "r")?.use { descriptor ->
                val archive = runCatching { ZipFile(File("/proc/self/fd/${descriptor.fd}")) }.getOrNull()
                archive?.use { return mnn.import(displayName, it, onProgress) }
            }
        }
        return checkNotNull(context.contentResolver.openInputStream(uri)).use {
            import(displayName, it, sizeBytes, onProgress)
        }
    }

    fun remove(model: LocalLlmModel) = when (model) {
        is LiteRtModel -> liteRt.remove(model)
        is GgufModel -> llama.remove(model)
        is MnnModel -> mnn.remove(model)
    }
}

internal fun copyModelWithProgress(
    input: InputStream,
    output: OutputStream,
    totalBytes: Long,
    initialBytes: Long = 0L,
    onProgress: (Long, Long) -> Unit,
) {
    var copied = initialBytes
    var lastReported = initialBytes
    val buffer = ByteArray(MODEL_IMPORT_BUFFER_BYTES)
    while (true) {
        val count = input.read(buffer)
        if (count < 0) break
        output.write(buffer, 0, count)
        copied += count
        if ((copied - lastReported) >= MODEL_IMPORT_PROGRESS_INTERVAL_BYTES) {
            lastReported = copied
            onProgress(copied, totalBytes)
        }
    }
    onProgress(if (totalBytes > 0L) totalBytes else copied, totalBytes)
}

private const val MODEL_IMPORT_BUFFER_BYTES = 1024 * 1024
private const val MODEL_IMPORT_PROGRESS_INTERVAL_BYTES = 8L * 1024 * 1024
