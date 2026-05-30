package com.mrj.fancyai.data.repository

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Base64
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.util.UUID

/**
 * Native implementation of db.js and images.js logic.
 * Central authority for all media storage, bypasses WebView memory limits.
 */
class MediaRepository(private val context: Context) {
    private val TAG = "MediaRepository"

    private val mediaDir: File
        get() = File(context.filesDir, "media").apply { mkdirs() }

    /**
     * Saves image bytes to Android Disk. Returns a "db:ID" reference.
     * Standardizes the ID prefix (avatar_, img_, src_, post_, etc.) as per images.js
     */
    suspend fun saveImage(base64: String, prefix: String = "img"): String? = withContext(Dispatchers.IO) {
        try {
            if (!base64.startsWith("data:image")) return@withContext null

            val cleanBase64 = base64.substringAfter(",")
            val bytes = Base64.decode(cleanBase64, Base64.DEFAULT)
            
            // Generate filename based on timestamp/uuid to ensure uniqueness
            val filename = "${prefix}_${System.currentTimeMillis()}.png"
            val file = File(mediaDir, filename)

            FileOutputStream(file).use { it.write(bytes) }
            
            Log.d(TAG, "Saved image to disk: $filename")
            return@withContext "db:$filename"
        } catch (e: Exception) {
            Log.e(TAG, "Failed to save image", e)
            null
        }
    }

    /**
     * Saves a Bitmap directly. Uses JPEG (q90): generated photos/avatars are opaque, and
     * JPEG compresses ~5-10× faster than lossless PNG-100 with far smaller files — the
     * PNG-100 path was the main lag perceived after generation finished.
     */
    suspend fun saveBitmap(bitmap: Bitmap, prefix: String = "img"): String? = withContext(Dispatchers.IO) {
        try {
            val filename = "${prefix}_${System.currentTimeMillis()}.jpg"
            val file = File(mediaDir, filename)

            FileOutputStream(file).use { fos ->
                bitmap.compress(Bitmap.CompressFormat.JPEG, 90, fos)
            }
            return@withContext "db:$filename"
        } catch (e: Exception) {
            Log.e(TAG, "Failed to save bitmap", e)
            null
        }
    }

    /**
     * Resolves a "db:ID" reference to a physical File.
     */
    fun resolveToFile(ref: String): File? {
        if (!ref.startsWith("db:")) return null
        val filename = ref.substringAfter("db:")
        val file = File(mediaDir, filename)
        return if (file.exists()) file else null
    }

    /**
     * Resolves a "db:ID" reference to a Base64 string (for server transmission).
     * Replicates ImageDB.get(ref, true)
     */
    suspend fun resolveToBase64(ref: String): String? = withContext(Dispatchers.IO) {
        val file = resolveToFile(ref) ?: return@withContext null
        try {
            val bytes = file.readBytes()
            val encoded = Base64.encodeToString(bytes, Base64.NO_WRAP)
            return@withContext "data:image/png;base64,$encoded"
        } catch (e: Exception) {
            Log.e(TAG, "Failed to resolve to base64", e)
            null
        }
    }

    /**
     * Deletes an image from disk by its "db:ID" reference.
     */
    suspend fun deleteImage(ref: String) = withContext(Dispatchers.IO) {
        resolveToFile(ref)?.delete()
    }

    /**
     * Rebuilds the registry (returns list of all db refs) by scanning the physical media directory.
     * Useful for gallery lazy loading. Replicates ImageDB.getRegistry()
     */
    fun getAllMediaRefs(): List<String> {
        return mediaDir.listFiles { file -> file.isFile }
            ?.sortedByDescending { it.lastModified() }
            ?.map { "db:${it.name}" }
            ?: emptyList()
    }

    /**
     * Returns all media files on disk, newest first. Used by the gallery, which
     * works directly with [File] objects rather than db: references.
     */
    fun getAllImages(): List<File> {
        return mediaDir.listFiles { file -> file.isFile }
            ?.sortedByDescending { it.lastModified() }
            ?: emptyList()
    }

    /** Resolves a "db:" ref to a JPEG data URL (the media files are stored as JPEG q90). */
    suspend fun resolveToJpegDataUrl(ref: String): String? = withContext(Dispatchers.IO) {
        val file = resolveToFile(ref) ?: return@withContext null
        try {
            "data:image/jpeg;base64," + Base64.encodeToString(file.readBytes(), Base64.NO_WRAP)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to build data URL", e); null
        }
    }

    /** Decodes an arbitrary image [Uri] and stores it as JPEG, returning its "db:" ref. */
    suspend fun importImage(context: Context, uri: android.net.Uri): String? = withContext(Dispatchers.IO) {
        try {
            val bitmap = context.contentResolver.openInputStream(uri)?.use {
                BitmapFactory.decodeStream(it)
            } ?: return@withContext null
            saveBitmap(bitmap, "src")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to import image", e); null
        }
    }

    /** Writes raw image bytes under the given file name in the media dir (used by restore). */
    suspend fun writeMediaFile(name: String, bytes: ByteArray) = withContext(Dispatchers.IO) {
        runCatching { File(mediaDir, name).writeBytes(bytes) }
    }

    /** Deletes every file in the media dir (used before a restore overwrites it). */
    suspend fun clearMedia() = withContext(Dispatchers.IO) {
        mediaDir.listFiles()?.forEach { it.delete() }
        Unit
    }

    /**
     * Purges orphaned files that aren't in a provided list of active refs.
     * Replicates ImageDB.purgeOrphanedFiles()
     */
    suspend fun purgeOrphanedFiles(activeRefs: Set<String>) = withContext(Dispatchers.IO) {
        val files = mediaDir.listFiles { file -> file.isFile } ?: return@withContext
        var deletedCount = 0
        for (file in files) {
            val ref = "db:${file.name}"
            if (!activeRefs.contains(ref)) {
                if (file.delete()) deletedCount++
            }
        }
        if (deletedCount > 0) {
            Log.d(TAG, "Purged $deletedCount orphaned media files from disk")
        }
    }
}
