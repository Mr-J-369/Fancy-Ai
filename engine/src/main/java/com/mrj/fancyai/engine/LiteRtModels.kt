package com.mrj.fancyai.engine

import com.google.ai.edge.litertlm.LlmCapability
import com.google.ai.edge.litertlm.ModelInfo
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.UUID

enum class LocalLlmRuntime { LITERT, LLAMA }

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
        val files = directory.listFiles()
            .orEmpty()
            .asSequence()
            .filter { it.isFile && it.extension.equals(EXTENSION, ignoreCase = true) }
            .sortedBy { it.name.lowercase() }
            .toList()
        synchronized(cacheLock) {
            val cached = inspected.getOrPut(directory.absolutePath, ::mutableMapOf)
            cached.keys.retainAll(files.map { it.absolutePath }.toSet())
            return files.map { file ->
                val entry = cached[file.absolutePath]
                if (entry != null && entry.length == file.length() && entry.modified == file.lastModified()) {
                    entry.model
                } else {
                    inspect(file).also { cached[file.absolutePath] = InspectedModel(file.length(), file.lastModified(), it) }
                }
            }
        }
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
        return ModelInfo.from(file.absolutePath).use { info ->
            LiteRtModel(
                file.name,
                file.absolutePath,
                (info as? LlmCapability)?.hasSpeculativeDecodingSupport() == true,
                info.inputModalities().vision,
            )
        }
    }

    private data class InspectedModel(val length: Long, val modified: Long, val model: LiteRtModel)

    private companion object {
        const val EXTENSION = "litertlm"
        val cacheLock = Any()
        val inspected = mutableMapOf<String, MutableMap<String, InspectedModel>>()
    }
}
