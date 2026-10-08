package com.mrj.fancyai.vision

import android.content.Context
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.UUID
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

data class VisionModel(val name: String, val path: String, val sizeBytes: Long)

class VisionModels(context: Context) {
    private val directory = directory(context)
    private val recommendedUrl =
        if (
            context.getSharedPreferences("app", Context.MODE_PRIVATE)
                .getBoolean("hf_mirror", false)
        ) {
            RECOMMENDED_URL.replaceFirst("https://huggingface.co/", "https://hf-mirror.com/")
        } else {
            RECOMMENDED_URL
        }

    fun installed(): List<VisionModel> {
        return directory.listFiles()
            .orEmpty()
            .asSequence()
            .filter { it.isFile && it.extension.equals(EXTENSION, ignoreCase = true) }
            .sortedBy { it.name.lowercase() }
            .map { VisionModel(it.name, it.absolutePath, it.length()) }
            .toList()
    }

    suspend fun import(
        displayName: String,
        input: InputStream,
        sizeBytes: Long,
        onProgress: (Long, Long) -> Unit,
    ): VisionModel = withContext(Dispatchers.IO) {
        val name = File(displayName).name
        directory.mkdirs()
        val target = File(directory, name)
        val temporary = File(directory, ".${UUID.randomUUID()}.$EXTENSION")
        try {
            FileOutputStream(temporary).use { output ->
                input.use { source ->
                    copy(
                        source,
                        output,
                        sizeBytes,
                        0L,
                        currentCoroutineContext(),
                        onProgress,
                    )
                }
                output.fd.sync()
            }
            Files.move(temporary.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE)
            VisionModel(target.name, target.absolutePath, target.length())
        } finally {
            temporary.delete()
        }
    }

    suspend fun downloadRecommended(onProgress: (Long, Long) -> Unit): VisionModel =
        withContext(Dispatchers.IO) {
            directory.mkdirs()
            val target = File(directory, RECOMMENDED_FILE_NAME)
            val partial = File(directory, ".$RECOMMENDED_FILE_NAME.download")
            download(partial, onProgress)
            Files.move(
                partial.toPath(),
                target.toPath(),
                StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING,
            )
            VisionModel(target.name, target.absolutePath, target.length())
        }

    private suspend fun download(partial: File, onProgress: (Long, Long) -> Unit) =
        withContext(Dispatchers.IO) {
            val offset = partial.length()
            val connection = URL(recommendedUrl).openConnection() as HttpURLConnection
            connection.connectTimeout = 20_000
            connection.readTimeout = 30_000
            connection.instanceFollowRedirects = true
            if (offset > 0L) connection.setRequestProperty("Range", "bytes=$offset-")
            try {
                val append = (offset > 0L) &&
                    (connection.responseCode == HttpURLConnection.HTTP_PARTIAL)
                val copied = if (append) offset else 0L
                connection.inputStream.use { input ->
                    FileOutputStream(partial, append).use { output ->
                        copy(
                            input,
                            output,
                            RECOMMENDED_SIZE_BYTES,
                            copied,
                            currentCoroutineContext(),
                            onProgress,
                        )
                        output.fd.sync()
                    }
                }
            } finally {
                connection.disconnect()
            }
        }

    private fun copy(
        input: InputStream,
        output: FileOutputStream,
        total: Long,
        initial: Long,
        cancellation: CoroutineContext,
        onProgress: (Long, Long) -> Unit,
    ) {
        val buffer = ByteArray(COPY_BUFFER_BYTES)
        var copied = initial
        onProgress(copied, total)
        while (true) {
            cancellation.ensureActive()
            val count = input.read(buffer)
            if (count < 0) break
            output.write(buffer, 0, count)
            copied += count
            onProgress(copied, total)
        }
    }

    companion object {
        const val RECOMMENDED_FILE_NAME = "InternVL3.5-1B.litertlm"
        const val RECOMMENDED_SIZE_BYTES = 817_517_936L
        private const val RECOMMENDED_REVISION = "0b5d2401e3f2ee38a7c5aaa9395c1987462b9657"
        private const val RECOMMENDED_URL =
            "https://huggingface.co/litert-community/InternVL3_5-1B/resolve/" +
                RECOMMENDED_REVISION + "/model.litertlm"

        fun directory(context: Context): File {
            val models = context.getExternalFilesDir("models") ?: File(context.filesDir, "models")
            return File(models, "vision")
        }

        private const val EXTENSION = "litertlm"
        private const val COPY_BUFFER_BYTES = 1024 * 1024
    }
}
