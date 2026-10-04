package com.mrj.fancyai.util

import android.content.ContentResolver
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.ImageDecoder
import android.graphics.drawable.BitmapDrawable
import android.net.Uri
import androidx.core.graphics.createBitmap
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.util.Properties

private const val DEFAULT_MAX_DECODE_EDGE = 2048

/** ImageDecoder applies source orientation before fitting the requested display size. */
internal fun decodeImage(context: Context, uri: Uri, maxEdge: Int? = null): Bitmap {
    val limit = (maxEdge ?: DEFAULT_MAX_DECODE_EDGE).coerceAtMost(DEFAULT_MAX_DECODE_EDGE)
    return try {
        val file = if (uri.scheme == ContentResolver.SCHEME_FILE || uri.scheme == null) {
            uri.path?.let(::File)?.takeIf(File::isFile)
        } else null
        val source = if (file != null) {
            ImageDecoder.createSource(file)
        } else {
            ImageDecoder.createSource(context.contentResolver, uri)
        }
        val drawable = ImageDecoder.decodeDrawable(source) { decoder, info, _ ->
            val longest = maxOf(info.size.width, info.size.height)
            if (longest > limit) {
                val scale = limit.toFloat() / longest
                decoder.setTargetSize(
                    (info.size.width * scale).toInt().coerceAtLeast(1),
                    (info.size.height * scale).toInt().coerceAtLeast(1),
                )
            }
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            decoder.setOnPartialImageListener { true }
        }
        when (drawable) {
            is BitmapDrawable -> drawable.bitmap
            else -> {
                val width = drawable.intrinsicWidth.coerceAtLeast(1)
                val height = drawable.intrinsicHeight.coerceAtLeast(1)
                val bitmap = createBitmap(width, height)
                val canvas = Canvas(bitmap)
                drawable.setBounds(0, 0, width, height)
                drawable.draw(canvas)
                bitmap
            }
        }
    } catch (_: Throwable) {
        decodeWithBitmapFactory(context, uri, limit)
    }
}

private fun decodeWithBitmapFactory(context: Context, uri: Uri, limit: Int): Bitmap {
    val file = if (uri.scheme == ContentResolver.SCHEME_FILE || uri.scheme == null) {
        uri.path?.let(::File)?.takeIf(File::isFile)
    } else null
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    if (file != null) {
        FileInputStream(file).use { BitmapFactory.decodeStream(it, null, bounds) }
    } else {
        context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
    }
    val longest = maxOf(bounds.outWidth, bounds.outHeight)
    val sampleSize = if (longest > limit) {
        var sample = 1
        while ((longest / (sample * 2)) >= limit) sample *= 2
        sample
    } else 1
    val decodeOptions = BitmapFactory.Options().apply {
        inSampleSize = sampleSize
        inPreferredConfig = Bitmap.Config.ARGB_8888
    }
    val bitmap = if (file != null) {
        FileInputStream(file).use { BitmapFactory.decodeStream(it, null, decodeOptions) }
    } else {
        context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, decodeOptions) }
    }
    return checkNotNull(bitmap) { "Cannot decode $uri" }
}

internal fun prepareImage(context: Context, uri: Uri, output: File): Bitmap {
    val bitmap = decodeImage(context, uri, 1024)
    return try {
        output.parentFile?.mkdirs()
        FileOutputStream(output).use { stream ->
            bitmap.compress(Bitmap.CompressFormat.JPEG, 90, stream)
            stream.fd.sync()
        }
        bitmap
    } catch (failure: Throwable) {
        bitmap.recycle()
        output.delete()
        throw failure
    }
}

internal fun contentImageFile(context: Context, relativePath: String): File? = runCatching {
    val root = context.filesDir.canonicalFile
    val source = File(relativePath)
    (if (source.isAbsolute) source else File(root, relativePath)).canonicalFile
        .takeIf { file ->
            file.isFile && listOf(root, context.cacheDir.canonicalFile).any {
                file.path.startsWith(it.path + File.separator)
            }
        }
}.getOrNull()

internal fun deleteReferencedImages(context: Context, folder: File) {
    for (file in folder.walkBottomUp()) {
        if (!file.isFile || file.extension != "properties") continue
        val values = Properties().apply { file.inputStream().use(::load) }
        val photoPath = values.getProperty("photoPath") ?: values.getProperty("imagePath")
        if (!photoPath.isNullOrBlank()) {
            val image = File(context.filesDir, photoPath)
            image.delete()
            File(image.parentFile, "${image.nameWithoutExtension}.json").delete()
        }
    }
}

internal val IMAGE_EXTENSIONS = setOf("gif", "jpeg", "jpg", "png", "webp")

internal fun auraSidecar(image: File): File? {
    if (!image.name.startsWith("aura-")) return null
    return File(image.parentFile, "${image.nameWithoutExtension}.json").takeIf(File::isFile)
}
