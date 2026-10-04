package com.mrj.fancyai.sd.hd

import android.graphics.Bitmap
import androidx.core.graphics.createBitmap
import androidx.core.graphics.scale
import com.mrj.fancyai.sd.MnnUpscaler

class AuraUpscaler(
    private val tile: Int,
    private val overlap: Int,
    private val feather: Int,
    private val refineStrength: Float,
    private val nativeScale: Int,
    private val refineTile: suspend (tilePx: IntArray, tileW: Int, tileH: Int, strength: Float) -> IntArray,
    private val onProgress: (Int) -> Unit = {},
) {

    suspend fun enhance(source: Bitmap, size: UpscaleSize): Bitmap = try {
        val pixels = IntArray(source.width * source.height).also {
            source.getPixels(it, 0, source.width, 0, 0, source.width, source.height)
        }
        var img = MnnUpscaler.nativeUpscaleImage(
            pixels,
            source.width,
            source.height,
            UpscalerModels.CORE_TILE,
            UpscalerModels.PAD,
        )
        var iw = source.width * nativeScale
        var ih = source.height * nativeScale
        if (size == UpscaleSize.X2) {
            val bitmap = createBitmap(iw, ih).also { it.setPixels(img, 0, iw, 0, 0, iw, ih) }
            val scaled = bitmap.scale(iw / 2, ih / 2)
            img = IntArray(scaled.width * scaled.height).also {
                scaled.getPixels(it, 0, scaled.width, 0, 0, scaled.width, scaled.height)
            }
            scaled.recycle()
            bitmap.recycle()
            iw /= 2; ih /= 2
        }
        onProgress(40)

        val xs = TileGrid.cover(iw, minOf(tile, iw), overlap)
        val ys = TileGrid.cover(ih, minOf(tile, ih), overlap)
        val blender = TileBlender(iw, ih)
        val totalTiles = xs.size * ys.size
        var done = 0
        for (oy in ys) for (ox in xs) {
            val tw = minOf(tile, iw - ox); val th = minOf(tile, ih - oy)
            val tilePx = IntArray(tw * th)
            for (ty in 0 until th) {
                System.arraycopy(img, (oy + ty) * iw + ox, tilePx, ty * tw, tw)
            }
            val refined = refineTile(tilePx, tw, th, refineStrength)
            blender.accumulate(
                refined, tw, th, ox, oy,
                featherLeft = ox > 0, featherTop = oy > 0,
                featherRight = ox + tw < iw, featherBottom = oy + th < ih,
                feather = feather,
            )
            done++; onProgress(40 + (done * 60) / totalTiles)
        }
        val result = blender.finalize()
        createBitmap(iw, ih).also { it.setPixels(result, 0, iw, 0, 0, iw, ih) }
    } finally {
        source.recycle()
    }

}
