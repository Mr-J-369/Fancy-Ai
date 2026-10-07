package com.mrj.fancyai.sd.face.swap

import com.mrj.fancyai.sd.hd.Rgb

/**
 * Blend-back for the swap pipeline: paste a restored, template-aligned face back into the
 * full-resolution target via the inverse of its alignment transform, with a feathered edge.
 *
 * Self-contained and testable (no models). The feather here is a soft inset toward the
 * template border; a later pass can intersect this with the segmenter's face-parse mask
 * (MnnSegmenter on the target) to exclude hair/background for a tighter composite — the hook
 * is [alphaAt], which a caller can override by supplying a precomputed alpha.
 */
object SwapBlend {

    /** CHW float (normalized as (px/255−mean)/std) → ARGB IntArray, de-normalizing. */
    fun chwToArgb(chw: FloatArray, side: Int, mean: Float, std: Float): IntArray {
        val plane = side * side
        val out = IntArray(plane)
        for (i in 0 until plane) {
            val r = ((chw[i] * std + mean) * 255f).toInt()
            val g = ((chw[plane + i] * std + mean) * 255f).toInt()
            val b = ((chw[2 * plane + i] * std + mean) * 255f).toInt()
            out[i] = Rgb.argb(r, g, b)
        }
        return out
    }

    /**
     * Paste [alignedFace] ([side]² ARGB, in template space) back into [target] ([tw]×[th]).
     * [toTemplate] maps target pixels → template space (computed at outSide == [side]); its
     * inverse is used implicitly by sampling the template forward. [featherFrac] is the soft
     * border as a fraction of [side]. Returns a new composited target buffer.
     */
    fun pasteBack(
        target: IntArray, tw: Int, th: Int,
        alignedFace: IntArray, side: Int,
        toTemplate: SwapAlign.Affine, featherFrac: Float = 0.08f,
    ): IntArray {
        val out = target.copyOf()
        val feather = (featherFrac * side).coerceAtLeast(1f)
        // Bounding box in target space that the template square covers (inverse-mapped corners).
        val inv = toTemplate.inverse()
        var minX = tw; var minY = th; var maxX = 0; var maxY = 0
        for (c in 0 until 4) {
            val cx = if (c and 1 == 0) 0f else side - 1f
            val cy = if (c and 2 == 0) 0f else side - 1f
            val px = inv.mapX(cx, cy); val py = inv.mapY(cx, cy)
            if (px < minX) minX = px.toInt(); if (px > maxX) maxX = px.toInt() + 1
            if (py < minY) minY = py.toInt(); if (py > maxY) maxY = py.toInt() + 1
        }
        minX = minX.coerceIn(0, tw); minY = minY.coerceIn(0, th)
        maxX = maxX.coerceIn(0, tw); maxY = maxY.coerceIn(0, th)

        for (gy in minY until maxY) {
            for (gx in minX until maxX) {
                val tplX = toTemplate.mapX(gx.toFloat(), gy.toFloat())
                val tplY = toTemplate.mapY(gx.toFloat(), gy.toFloat())
                if (tplX < 0f || tplY < 0f || tplX > side - 1f || tplY > side - 1f) continue
                val a = alphaAt(tplX, tplY, side, feather)
                if (a <= 0f) continue
                val rp = sampleArgb(alignedFace, side, side, tplX, tplY)
                val bi = gy * tw + gx
                val bp = out[bi]
                val r = (Rgb.red(bp) * (1 - a) + Rgb.red(rp) * a).toInt()
                val g = (Rgb.green(bp) * (1 - a) + Rgb.green(rp) * a).toInt()
                val b = (Rgb.blue(bp) * (1 - a) + Rgb.blue(rp) * a).toInt()
                out[bi] = Rgb.argb(r, g, b)
            }
        }
        return out
    }

    /** Soft alpha: 1 in the interior, ramping to 0 within [feather] px of the template border. */
    private fun alphaAt(x: Float, y: Float, side: Int, feather: Float): Float {
        val d = minOf(x, y, side - 1f - x, side - 1f - y)
        return (d / feather).coerceIn(0f, 1f)
    }

    private fun sampleArgb(img: IntArray, w: Int, h: Int, fx: Float, fy: Float): Int {
        val x0 = fx.toInt(); val y0 = fy.toInt()
        val x1 = (x0 + 1).coerceAtMost(w - 1); val y1 = (y0 + 1).coerceAtMost(h - 1)
        val ax = fx - x0; val ay = fy - y0
        val p00 = img[y0 * w + x0]; val p01 = img[y0 * w + x1]
        val p10 = img[y1 * w + x0]; val p11 = img[y1 * w + x1]
        val r = lerp2(Rgb.red(p00), Rgb.red(p01), Rgb.red(p10), Rgb.red(p11), ax, ay)
        val g = lerp2(Rgb.green(p00), Rgb.green(p01), Rgb.green(p10), Rgb.green(p11), ax, ay)
        val b = lerp2(Rgb.blue(p00), Rgb.blue(p01), Rgb.blue(p10), Rgb.blue(p11), ax, ay)
        return Rgb.argb(r.toInt(), g.toInt(), b.toInt())
    }

    private fun lerp2(c00: Int, c01: Int, c10: Int, c11: Int, ax: Float, ay: Float): Float {
        val top = c00 + (c01 - c00) * ax
        val bot = c10 + (c11 - c10) * ax
        return top + (bot - top) * ay
    }
}
