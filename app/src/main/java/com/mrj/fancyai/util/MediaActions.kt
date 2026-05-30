package com.mrj.fancyai.util

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Save-to-gallery and share helpers shared by the Imaging Studio and Gallery.
 *
 * Sharing reuses the existing FileProvider, which only exposes `cache/shared_images/`,
 * so images are first copied there before being handed to other apps.
 */
object MediaActions {

    private const val ALBUM = "FancyAI"

    /** Saves a bitmap to the device's public Pictures/FancyAI album. */
    suspend fun saveBitmapToGallery(context: Context, bitmap: Bitmap, displayName: String): Boolean =
        withContext(Dispatchers.IO) {
            runCatching {
                val resolver = context.contentResolver
                val values = ContentValues().apply {
                    put(MediaStore.Images.Media.DISPLAY_NAME, ensurePng(displayName))
                    put(MediaStore.Images.Media.MIME_TYPE, "image/png")
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        put(MediaStore.Images.Media.RELATIVE_PATH, "${Environment.DIRECTORY_PICTURES}/$ALBUM")
                        put(MediaStore.Images.Media.IS_PENDING, 1)
                    }
                }
                val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
                    ?: return@withContext false
                resolver.openOutputStream(uri)?.use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    values.clear()
                    values.put(MediaStore.Images.Media.IS_PENDING, 0)
                    resolver.update(uri, values, null, null)
                }
                true
            }.getOrDefault(false)
        }

    /** Saves an on-disk image file to the device's public Pictures/FancyAI album. */
    suspend fun saveFileToGallery(context: Context, file: File): Boolean =
        withContext(Dispatchers.IO) {
            val bitmap = BitmapFactory.decodeFile(file.absolutePath) ?: return@withContext false
            saveBitmapToGallery(context, bitmap, file.nameWithoutExtension)
        }

    /** Shares a bitmap via the system share sheet. */
    suspend fun shareBitmap(context: Context, bitmap: Bitmap, displayName: String) {
        val file = withContext(Dispatchers.IO) {
            val dir = File(context.cacheDir, "shared_images").apply { mkdirs() }
            File(dir, ensurePng(displayName)).also { out ->
                out.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            }
        }
        launchShare(context, file)
    }

    /** Shares an on-disk image file via the system share sheet. */
    suspend fun shareFile(context: Context, file: File) {
        val shareFile = withContext(Dispatchers.IO) {
            val dir = File(context.cacheDir, "shared_images").apply { mkdirs() }
            File(dir, file.name).also { file.copyTo(it, overwrite = true) }
        }
        launchShare(context, shareFile)
    }

    private fun launchShare(context: Context, file: File) {
        val uri: Uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "image/png"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(Intent.createChooser(intent, "Share image").apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        })
    }

    private fun ensurePng(name: String): String =
        if (name.endsWith(".png", ignoreCase = true)) name else "$name.png"
}
