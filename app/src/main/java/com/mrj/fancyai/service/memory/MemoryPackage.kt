package com.mrj.fancyai.service.memory

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.io.InputStream
import java.net.URL
import java.util.zip.ZipFile
import javax.net.ssl.HttpsURLConnection
import com.mrj.fancyai.util.PrivateHttp.hfDownloadUrl

internal object MemoryPackage {
    val installation = Mutex()
    val isInstalling: Boolean get() = installation.isLocked
    private const val DIRECTORY = "memory-multilingual-v1"
    private const val RETIRED_DIRECTORY = "memory-english-v1"
    private const val ARCHIVE = "memory-multilingual-v1.zip"
    private const val DOWNLOAD_URL = "https://huggingface.co/Mr-J-369/Fancy-AI/resolve/main/memory/fancy-memory-multilingual-v1.zip"
    private const val EXPECTED_BYTES = 77_000_000f
    private val files = listOf(
        "minilm/model.onnx",
        "minilm/tokenizer.json",
        "NOTICE.txt",
    )

    fun directory(context: Context): File =
        File(context.getExternalFilesDir("models") ?: File(context.filesDir, "models"), DIRECTORY)

    fun installed(context: Context): Boolean = directory(context).let { root ->
        files.all { name -> File(root, name).isFile }
    }

    suspend fun download(context: Context, progress: suspend (Float) -> Unit) = withContext(Dispatchers.IO) {
        val connection = URL(hfDownloadUrl(context, DOWNLOAD_URL)).openConnection() as HttpsURLConnection
        connection.connectTimeout = 15_000
        connection.readTimeout = 15_000
        try {
            install(context, { connection.inputStream }, progress)
        } finally {
            connection.disconnect()
        }
    }

    suspend fun install(context: Context, source: () -> InputStream, progress: suspend (Float) -> Unit) = withContext(Dispatchers.IO) {
        installation.withLock {
            val target = directory(context)
            val archive = File(context.cacheDir, ARCHIVE)
            val staging = File(target.parentFile, ".$DIRECTORY.installing")
            try {
                staging.deleteRecursively()
                source().use { input ->
                    archive.outputStream().use { output ->
                        val buffer = ByteArray(256 * 1024)
                        var total = 0L
                        while (true) {
                            currentCoroutineContext().ensureActive()
                            val count = input.read(buffer)
                            if (count < 0) break
                            total += count
                            output.write(buffer, 0, count)
                            progress((total / EXPECTED_BYTES).coerceAtMost(1f) * 0.5f)
                        }
                    }
                }
                staging.mkdirs()
                ZipFile(archive).use { zip ->
                    files.forEachIndexed { index, name ->
                        currentCoroutineContext().ensureActive()
                        val entry = zip.getEntry(name)
                        val destination = File(staging, name)
                        destination.parentFile!!.mkdirs()
                        zip.getInputStream(entry).use { input -> destination.outputStream().use { output -> input.copyTo(output) } }
                        progress(0.5f + (index + 1f) / files.size * 0.5f)
                    }
                }
                currentCoroutineContext().ensureActive()
                target.deleteRecursively()
                staging.renameTo(target)
                // The replacement is in place; the retired payload is now unreferenced.
                File(target.parentFile, RETIRED_DIRECTORY).deleteRecursively()
            } finally {
                archive.delete()
                staging.deleteRecursively()
            }
        }
    }
}
