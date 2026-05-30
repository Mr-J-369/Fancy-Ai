package com.mrj.fancyai

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.Base64
import android.util.Log
import android.webkit.WebResourceResponse
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.ConcurrentHashMap

class FileService(private val context: Context) {
    private val filesDir get() = context.filesDir
    private val cacheDir get() = context.cacheDir
    private val backupChunks = ConcurrentHashMap<String, ByteArrayOutputStream>()

    fun saveTextFile(fileName: String, content: String): Boolean {
        return try {
            File(filesDir, fileName).writeText(content, Charsets.UTF_8)
            true
        } catch (e: Exception) {
            Log.e("FileService", "Save to file failed", e)
            false
        }
    }

    fun readTextFile(fileName: String): String? {
        return try {
            val file = File(filesDir, fileName)
            if (!file.exists()) null else file.readText(Charsets.UTF_8)
        } catch (_: Exception) {
            null
        }
    }

    fun saveImageToDisk(base64Data: String?): String? {
        if (base64Data == null || !base64Data.contains(",")) return null
        return try {
            val pureBase64 = base64Data.substring(base64Data.indexOf(",") + 1)
            val decodedBytes = Base64.decode(pureBase64, Base64.DEFAULT)
            val dir = File(filesDir, "media")
            if (!dir.exists() && !dir.mkdirs()) return null
            val fileName = "img_${System.currentTimeMillis()}.png"
            FileOutputStream(File(dir, fileName)).use { it.write(decodedBytes) }
            fileName
        } catch (e: Exception) {
            Log.e("FileService", "Disk save failed", e)
            null
        }
    }

    fun loadImageFromDisk(fileName: String): String? {
        return try {
            val file = File(filesDir, "media/$fileName")
            if (!file.exists()) null
            else "data:image/png;base64,${Base64.encodeToString(file.readBytes(), Base64.NO_WRAP)}"
        } catch (_: Exception) {
            null
        }
    }

    fun deleteFile(fileName: String): Boolean {
        return try {
            File(filesDir, fileName).takeIf { it.exists() }?.delete() == true
        } catch (e: Exception) {
            Log.e("FileService", "File delete failed", e)
            false
        }
    }

    fun listMediaFiles(): String {
        return try {
            val dir = File(filesDir, "media")
            if (!dir.exists() || !dir.isDirectory) return "[]"
            "[${(dir.listFiles() ?: emptyArray()).joinToString(",") { "\"${it.name}\"" }}]"
        } catch (_: Exception) {
            "[]"
        }
    }

    fun saveRawData(bytes: ByteArray, fileName: String): Boolean {
        return try {
            val downloadFolder = File(
                android.os.Environment.getExternalStoragePublicDirectory(android.os.Environment.DIRECTORY_DOWNLOADS),
                "FancyAI"
            )
            if (!downloadFolder.exists() && !downloadFolder.mkdirs()) return false
            FileOutputStream(File(downloadFolder, fileName)).use { it.write(bytes) }
            true
        } catch (_: Exception) {
            false
        }
    }

    fun saveBase64File(dataUrl: String?, mimeType: String?): Boolean {
        if (dataUrl == null || !dataUrl.startsWith("data:")) return false
        return try {
            var mt = mimeType
            if (mt.isNullOrEmpty() || mt.contains("octet-stream")) {
                val start = dataUrl.indexOf(":") + 1
                val end = dataUrl.indexOf(";")
                if (start > 0 && end > start) mt = dataUrl.substring(start, end)
            }
            val base64Content = dataUrl.substring(dataUrl.indexOf(",") + 1)
            val decodedBytes = Base64.decode(base64Content, Base64.DEFAULT)
            val extension = when {
                mt?.contains("png") == true -> ".png"
                mt?.contains("jpeg") == true || mt?.contains("jpg") == true -> ".jpg"
                mt?.contains("json") == true -> ".json"
                mt?.contains("zip") == true -> ".zip"
                else -> ".bin"
            }
            saveRawData(decodedBytes, "FancyAI_${System.currentTimeMillis()}$extension")
        } catch (_: Exception) {
            false
        }
    }

    fun startBackup(): String {
        val id = "bk_${System.currentTimeMillis()}"
        backupChunks[id] = ByteArrayOutputStream()
        return id
    }

    fun appendBackupChunk(backupId: String, base64Chunk: String) {
        val baos = backupChunks[backupId] ?: return
        try {
            val decoded = Base64.decode(base64Chunk, Base64.DEFAULT)
            synchronized(baos) { baos.write(decoded) }
        } catch (_: Exception) {}
    }

    fun finishBackup(backupId: String, extension: String?): Boolean {
        val baos = backupChunks.remove(backupId) ?: return false
        return try {
            val allBytes = synchronized(baos) { baos.toByteArray() }
            saveRawData(allBytes, "Backup_${System.currentTimeMillis()}${if (!extension.isNullOrEmpty()) extension else ".zip"}")
        } catch (e: Exception) {
            Log.e("FileService", "Finish backup failed", e)
            false
        }
    }

    fun serveThumbnail(uri: Uri): WebResourceResponse? {
        return try {
            val fileName = uri.lastPathSegment ?: return null
            val file = File(filesDir, "media/$fileName")
            if (!file.exists()) return null

            val target = 256
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(file.absolutePath, bounds)
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

            var sample = 1
            if (bounds.outHeight > target || bounds.outWidth > target) {
                val halfH = bounds.outHeight / 2
                val halfW = bounds.outWidth / 2
                while ((halfH / sample) >= target && (halfW / sample) >= target) sample *= 2
            }

            val bmp = BitmapFactory.decodeFile(file.absolutePath, BitmapFactory.Options().apply { inSampleSize = sample })
                ?: return null
            val baos = ByteArrayOutputStream()
            bmp.compress(Bitmap.CompressFormat.JPEG, 80, baos)
            bmp.recycle()

            WebResourceResponse(
                "image/jpeg", null, 200, "OK",
                mapOf("Cache-Control" to "max-age=86400", "Access-Control-Allow-Origin" to "*"),
                ByteArrayInputStream(baos.toByteArray())
            )
        } catch (e: Exception) {
            Log.w("FileService", "Thumbnail decode failed: ${e.message}")
            null
        }
    }
}
