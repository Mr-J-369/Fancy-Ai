package com.mrj.fancyai.engine

import kotlinx.serialization.json.*
import java.io.File
import java.io.InputStream
import java.io.BufferedInputStream
import java.io.FilterInputStream
import java.io.PushbackInputStream
import java.util.zip.ZipFile
import java.util.zip.ZipEntry
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.UUID
import java.util.zip.ZipInputStream

data class MnnModel(override val name: String, override val path: String) : LocalLlmModel {
    override val sizeBytes: Long by lazy { MnnModels.sizeBytes(path) }
    override val runtime = LocalLlmRuntime.MNN
    override val supportsSpeculativeDecoding = true
}

/** Imports a complete MNN text model package atomically, never a lone graph without its weights. */
class MnnModels(private val directory: File) {
    fun installed(): List<MnnModel> = directory.listFiles().orEmpty().asSequence()
        .filter { it.isDirectory && !it.name.startsWith('.') && File(it, "config.json").isFile }
        .map { MnnModel(it.name, File(it, "config.json").absolutePath) }.toList()

    fun import(displayName: String, input: InputStream, sizeBytes: Long, onProgress: (Long, Long) -> Unit): MnnModel =
        install(displayName) { staging ->
            val progress = ImportProgress(sizeBytes, onProgress)
            val counted = object : FilterInputStream(input) {
                override fun read(): Int = `in`.read().also { if (it >= 0) progress.advance(1) }
                override fun read(b: ByteArray, off: Int, len: Int): Int =
                    `in`.read(b, off, len).also { if (it > 0) progress.advance(it.toLong()) }
            }
            val buffer = ByteArray(IMPORT_BUFFER_BYTES)
            var total = 0L
            var count = 0
            FastZipInputStream(BufferedInputStream(counted, IMPORT_BUFFER_BYTES)).use { zip ->
                while (true) {
                    val entry = zip.nextEntry ?: break
                    require(++count <= 512) { "Too many package files" }
                    val target = entryTarget(staging, entry)
                    if (!entry.isDirectory) {
                        target.outputStream().buffered(IMPORT_BUFFER_BYTES).use { output ->
                            while (true) {
                                val read = zip.read(buffer)
                                if (read < 0) break
                                total += read
                                require(total <= MAX_PACKAGE_BYTES) { "Package is too large" }
                                output.write(buffer, 0, read)
                            }
                        }
                    }
                    zip.closeEntry()
                }
            }
            progress.complete()
        }

    /** Like Aura, extract seekable documents directly without first copying the archive. */
    fun import(displayName: String, zip: ZipFile, onProgress: (Long, Long) -> Unit): MnnModel =
        install(displayName) { staging ->
            val entries = zip.entries().asSequence().toList()
            require(entries.size <= 512) { "Too many package files" }
            val total = entries.asSequence().filterNot { it.isDirectory }.sumOf { it.size.coerceAtLeast(0L) }
            require(total in (0..MAX_PACKAGE_BYTES)) { "Package is too large" }
            val progress = ImportProgress(total, onProgress)
            val buffer = ByteArray(IMPORT_BUFFER_BYTES)
            var written = 0L
            for (entry in entries) {
                val target = entryTarget(staging, entry)
                if (entry.isDirectory) continue
                zip.getInputStream(entry).use { input ->
                    target.outputStream().buffered(IMPORT_BUFFER_BYTES).use { output ->
                        while (true) {
                            val read = input.read(buffer)
                            if (read < 0) break
                            written += read
                            require(written <= MAX_PACKAGE_BYTES) { "Package is too large" }
                            output.write(buffer, 0, read)
                            progress.advance(read.toLong())
                        }
                    }
                }
            }
            progress.complete()
        }

    private fun entryTarget(staging: File, entry: ZipEntry): File {
        require(!entry.name.contains('\\') && !entry.name.startsWith('/')) { "Invalid package path" }
        val file = File(staging, entry.name).canonicalFile
        require(file.toPath().startsWith(staging.canonicalFile.toPath()) && (file != staging.canonicalFile)) { "Invalid package path" }
        if (entry.isDirectory) {
            file.mkdirs()
        } else {
            require(!file.exists()) { "Duplicate package file" }
            file.parentFile?.mkdirs()
        }
        return file
    }

    private fun install(displayName: String, extract: (File) -> Unit): MnnModel {
        val name = File(displayName).name.substringBeforeLast('.')
        require(name.isNotBlank() && !name.startsWith('.') && name.none(Char::isISOControl)) { "Invalid package name" }
        directory.mkdirs()
        val destination = File(directory, name)
        require(!destination.exists()) { "Model is already installed" }
        val staging = File(directory, ".${UUID.randomUUID()}").apply { mkdirs() }
        return try {
            extract(staging)
            val configs = staging.walkTopDown().filter { it.isFile && (it.name == "config.json") }.toList()
            require(configs.size == 1) { "Package must contain one model config.json" }
            val root = checkNotNull(configs.single().parentFile)
            validatePackage(root)
            Files.move(root.toPath(), destination.toPath(), StandardCopyOption.ATOMIC_MOVE)
            MnnModel(name, File(destination, "config.json").absolutePath)
        } finally {
            if (staging.exists()) staging.deleteRecursively()
        }
    }

    private class ImportProgress(private val total: Long, private val report: (Long, Long) -> Unit) {
        private var copied = 0L
        private var lastReport = System.nanoTime()
        init { report(0L, total) }
        fun advance(bytes: Long) {
            copied += bytes
            val now = System.nanoTime()
            if ((now - lastReport) >= 100_000_000L) {
                lastReport = now
                report(copied, total)
            }
        }
        fun complete() = report(copied, total)
    }

    private class FastZipInputStream(input: InputStream) : ZipInputStream(input) {
        init {
            `in` = PushbackInputStream(input, 64 * 1024)
            buf = ByteArray(64 * 1024)
        }
    }

    fun remove(model: MnnModel) {
        val root = checkNotNull(File(model.path).parentFile)
        require(root.parentFile?.canonicalFile == directory.canonicalFile) { "Model is outside its library" }
        check(!root.exists() || root.deleteRecursively()) { "Could not remove model" }
    }

    private fun validatePackage(root: File) {
        fun json(name: String): JsonObject {
            val file = File(root, name)
            require(file.isFile && (file.length() <= 2_000_000)) { "Missing or invalid $name" }
            return Json.parseToJsonElement(file.readText()).jsonObject
        }
        var runtime = json("config.json")
        require((runtime["base_dir"] as? JsonPrimitive)?.contentOrNull.orEmpty() in setOf("", "./")) { "Package must use relative model paths" }
        // Portable packages sometimes explicitly use ./; resolve it beside config.json.
        runtime = JsonObject(runtime - "base_dir")
        require(((runtime["llm_config"] as? JsonPrimitive)?.contentOrNull ?: "llm_config.json") == "llm_config.json") { "Package needs llm_config.json" }
        val model = json("llm_config.json")
        val isSingle = (model["is_single"] as? JsonPrimitive)?.booleanOrNull != false
        require(!model.containsKey("base_dir")) { "Package must use relative model paths" }
        checkPaths(root, runtime)
        checkPaths(root, model)
        val merged = JsonObject(runtime + model)
        require(File(root, ((merged["tokenizer_file"] as? JsonPrimitive)?.contentOrNull ?: "tokenizer.txt")).let { it.isFile && (it.length() > 0) }) {
            "Incomplete MNN package: tokenizer_file"
        }
        checkModelWeights(root, merged, isSingle)
        File(root, "config.json").outputStream().use { output ->
            output.write(runtime.toString().toByteArray(Charsets.UTF_8))
            output.fd.sync()
        }
    }

    private fun checkModelWeights(root: File, merged: JsonObject, isSingle: Boolean) {
        if (isSingle) {
            listOf("llm_model" to "llm.mnn", "llm_weight" to "llm.mnn.weight").forEach { (key, fallback) ->
                val fileName = (merged[key] as? JsonPrimitive)?.contentOrNull ?: fallback
                require(File(root, fileName).let { it.isFile && (it.length() > 0) }) { "Incomplete MNN package: $key" }
            }
        } else {
            val lmFile = File(root, ((merged["lm_model"] as? JsonPrimitive)?.contentOrNull ?: "lm.mnn"))
            require(lmFile.isFile && (lmFile.length() > 0)) { "Incomplete MNN package: lm_model" }
            val block0 = File(root, ((merged["block_model"] as? JsonPrimitive)?.contentOrNull ?: "block_0.mnn"))
            require(block0.isFile && (block0.length() > 0)) { "Incomplete MNN package: block_0.mnn" }
        }
        checkEmbeddings(root, merged)
    }

    private fun checkEmbeddings(root: File, merged: JsonObject) {
        val weightOffset = when (val tied = merged["tie_embeddings"]) {
            is JsonArray -> if (tied.size >= 5) ((tied[0] as? JsonPrimitive)?.longOrNull ?: 0L) else 0L
            is JsonObject -> ((tied["weight_offset"] as? JsonPrimitive)?.longOrNull ?: 0L)
            else -> 0L
        }
        if (weightOffset <= 0L) {
            val embFile = (merged["embedding_file"] as? JsonPrimitive)?.contentOrNull ?: "embeddings_bf16.bin"
            require(File(root, embFile).let { it.isFile && (it.length() > 0) }) {
                "Package is missing embedding weights"
            }
        }
    }

    private fun checkPaths(root: File, value: JsonObject) {
        value.forEach { (key, child) ->
            if (child is JsonObject) {
                checkPaths(root, child)
            } else if ((child is JsonPrimitive) && child.isString && (key.endsWith("_file") || key.endsWith("_model") ||
                    key.endsWith("_weight") || key.endsWith("_path") || key.endsWith("_dir") || (key == "llm_config"))) {
                require(!child.content.contains('\\') && !File(child.content).isAbsolute && !child.content.contains("://")) { "External model path" }
                require(File(root, child.content).canonicalFile.toPath().startsWith(root.canonicalFile.toPath())) { "External model path" }
            }
        }
    }

    companion object {
        private const val IMPORT_BUFFER_BYTES = 16 * 1024 * 1024
        private const val MAX_PACKAGE_BYTES = 32L * 1024 * 1024 * 1024

        fun sizeBytes(path: String): Long = File(path).parentFile?.walkTopDown()
            ?.filter(File::isFile)?.sumOf(File::length) ?: 0L
    }
}
