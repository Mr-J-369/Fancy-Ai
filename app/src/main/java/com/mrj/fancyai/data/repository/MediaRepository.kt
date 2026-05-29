package com.mrj.fancyai.data.repository

import android.content.Context
import android.graphics.Bitmap
import java.io.File
import java.io.FileOutputStream
import java.util.UUID

class MediaRepository(private val context: Context) {

    private val mediaDir: File
        get() = File(context.filesDir, "media").apply { mkdirs() }

    fun saveImage(bitmap: Bitmap): String {
        val filename = "img_${UUID.randomUUID()}.png"
        val file = File(mediaDir, filename)

        FileOutputStream(file).use { fos ->
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, fos)
        }

        return "db:$filename"
    }

    fun getImageFile(dbRef: String): File? {
        if (!dbRef.startsWith("db:")) return null
        val filename = dbRef.substring(3)
        val file = File(mediaDir, filename)
        return if (file.exists()) file else null
    }

    fun getAllImages(): List<File> =
        mediaDir.listFiles { file -> file.isFile && file.name.startsWith("img_") }
            ?.sortedByDescending { it.lastModified() }
            ?: emptyList()

    fun deleteImage(dbRef: String) {
        getImageFile(dbRef)?.delete()
    }
}
