package com.mrj.fancyai.engine

import com.google.ai.edge.litertlm.Capabilities
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.UUID

enum class LocalLlmRuntime { LITERT, LLAMA, MNN }

sealed interface LocalLlmModel {
    val name: String
    val path: String
    val sizeBytes: Long
    val runtime: LocalLlmRuntime
    val supportsSpeculativeDecoding: Boolean
}

data class LiteRtModel(
    override val name: String,
    override val path: String,
    override val supportsSpeculativeDecoding: Boolean,
    val supportsVision: Boolean = false,
) : LocalLlmModel {
    override val sizeBytes: Long by lazy { File(path).length() }
    override val runtime = LocalLlmRuntime.LITERT
}

class LiteRtModels(private val directory: File) {
    fun installed(): List<LiteRtModel> {
        directory.mkdirs()
        return directory.listFiles()
            .orEmpty()
            .asSequence()
            .filter { it.isFile && it.extension.equals(EXTENSION, ignoreCase = true) }
            .sortedBy { it.name.lowercase() }
            .map(::inspect)
            .toList()
    }

    fun import(
        displayName: String,
        input: InputStream,
        sizeBytes: Long = -1L,
        onProgress: (Long, Long) -> Unit = { _, _ -> },
    ): LiteRtModel {
        val name = File(displayName).name
        require(name.endsWith(".$EXTENSION", ignoreCase = true)) {
            "Select a .$EXTENSION model package."
        }
        require(name.none(Char::isISOControl)) { "The model filename is invalid." }
        directory.mkdirs()
        val target = File(directory, name)
        require(!target.exists()) { "$name is already installed." }
        val temporary = File(directory, ".${UUID.randomUUID()}.$EXTENSION")
        return try {
            FileOutputStream(temporary).use { output ->
                copyModelWithProgress(input, output, sizeBytes, onProgress = onProgress)
                output.fd.sync()
            }
            inspect(temporary)
            Files.move(temporary.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE)
            inspect(target)
        } finally {
            temporary.delete()
        }
    }

    fun remove(model: LiteRtModel) {
        val file = File(model.path)
        require(file.parentFile?.canonicalFile == directory.canonicalFile) {
            "The selected model is outside Fancy's model directory."
        }
        check(!file.exists() || file.delete()) { "Could not remove ${model.name}." }
    }

    private fun inspect(file: File): LiteRtModel {
        return Capabilities(file.absolutePath).use {
            LiteRtModel(file.name, file.absolutePath, it.hasSpeculativeDecodingSupport(), it.inputModalities().vision)
        }
    }

    private companion object {
        const val EXTENSION = "litertlm"
    }
}
