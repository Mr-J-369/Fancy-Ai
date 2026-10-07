package com.mrj.fancyai.sd.face.swap

import com.mrj.fancyai.sd.hd.Rgb

/**
 * Face alignment for the swap pipeline — independent of the MNN models, so it's testable
 * on its own (SwapAlignTest: template round-trip + warp sampling).
 *
 * Uses the canonical insightface 5-point ArcFace template and a closed-form 2D similarity
 * (Procrustes: uniform scale + rotation + translation, no shear/reflection) to map a face's
 * detected landmarks onto the template. The same transform feeds ArcFace (112), inswapper
 * (128) and the inverse pastes the swapped face back into the full-res target.
 */
object SwapAlign {

    /** insightface arcface_dst, defined in a 112×112 reference frame. */
    private val TEMPLATE_112 = floatArrayOf(
        38.2946f, 51.6963f,
        73.5318f, 51.5014f,
        56.0252f, 71.7366f,
        41.5493f, 92.3655f,
        70.7299f, 92.2041f,
    )

    /** A 2×3 affine [a, -b, tx, b, a, ty] representing scale·rotation + translation. */
    data class Affine(val a: Float, val b: Float, val tx: Float, val ty: Float) {
        fun mapX(x: Float, y: Float) = a * x - b * y + tx
        fun mapY(x: Float, y: Float) = b * x + a * y + ty

        /** Inverse of a similarity transform is a similarity transform. */
        fun inverse(): Affine {
            val det = a * a + b * b
            val ia = a / det
            val ib = -b / det
            // inv maps q->p: p = R^-1 (q - t)
            val itx = -(ia * tx - ib * ty)
            val ity = -(ib * tx + ia * ty)
            return Affine(ia, ib, itx, ity)
        }
    }

    /**
     * Closed-form least-squares similarity mapping the 5 detected [landmarks] (src px,
     * 10 floats: x0,y0,…x4,y4) onto the template scaled to [outSide].
     */
    fun similarityToTemplate(landmarks: FloatArray, outSide: Int): Affine {
        val scale = outSide / 112f
        // dst = template scaled to outSide; src = landmarks.
        var msx = 0f; var msy = 0f; var mdx = 0f; var mdy = 0f
        for (i in 0 until 5) {
            msx += landmarks[2 * i]; msy += landmarks[2 * i + 1]
            mdx += TEMPLATE_112[2 * i] * scale; mdy += TEMPLATE_112[2 * i + 1] * scale
        }
        msx /= 5f; msy /= 5f; mdx /= 5f; mdy /= 5f

        var sxx = 0f  // Σ(x·u + y·v)
        var sxy = 0f  // Σ(x·v − y·u)
        var spp = 0f  // Σ(x² + y²)
        for (i in 0 until 5) {
            val x = landmarks[2 * i] - msx
            val y = landmarks[2 * i + 1] - msy
            val u = TEMPLATE_112[2 * i] * scale - mdx
            val v = TEMPLATE_112[2 * i + 1] * scale - mdy
            sxx += x * u + y * v
            sxy += x * v - y * u
            spp += x * x + y * y
        }
        if (spp < 1e-6f) spp = 1e-6f
        val a = sxx / spp
        val b = sxy / spp
        val tx = mdx - (a * msx - b * msy)
        val ty = mdy - (b * msx + a * msy)
        return Affine(a, b, tx, ty)
    }

    /**
     * Warp [src] into an aligned [outSide]² CHW float buffer using the inverse of [toTemplate]
     * (output→source sampling, bilinear), normalized as (px/255 − mean)/std per channel, RGB.
     */
    fun warpToChw(
        src: IntArray, w: Int, h: Int,
        toTemplate: Affine, outSide: Int,
        mean: Float, std: Float,
    ): FloatArray {
        val inv = toTemplate.inverse()
        val out = FloatArray(3 * outSide * outSide)
        val plane = outSide * outSide
        for (oy in 0 until outSide) {
            for (ox in 0 until outSide) {
                val sx = inv.mapX(ox.toFloat(), oy.toFloat())
                val sy = inv.mapY(ox.toFloat(), oy.toFloat())
                val i = oy * outSide + ox
                if (sx < 0f || sy < 0f || sx > w - 1f || sy > h - 1f) {
                    out[i] = (-mean) / std; out[plane + i] = (-mean) / std; out[2 * plane + i] = (-mean) / std
                    continue
                }
                val x0 = sx.toInt(); val y0 = sy.toInt()
                val x1 = (x0 + 1).coerceAtMost(w - 1); val y1 = (y0 + 1).coerceAtMost(h - 1)
                val fx = sx - x0; val fy = sy - y0
                val p00 = src[y0 * w + x0]; val p01 = src[y0 * w + x1]
                val p10 = src[y1 * w + x0]; val p11 = src[y1 * w + x1]
                val r = bilerp(Rgb.red(p00), Rgb.red(p01), Rgb.red(p10), Rgb.red(p11), fx, fy)
                val g = bilerp(Rgb.green(p00), Rgb.green(p01), Rgb.green(p10), Rgb.green(p11), fx, fy)
                val bl = bilerp(Rgb.blue(p00), Rgb.blue(p01), Rgb.blue(p10), Rgb.blue(p11), fx, fy)
                out[i] = (r / 255f - mean) / std
                out[plane + i] = (g / 255f - mean) / std
                out[2 * plane + i] = (bl / 255f - mean) / std
            }
        }
        return out
    }

    private fun bilerp(c00: Int, c01: Int, c10: Int, c11: Int, fx: Float, fy: Float): Float {
        val top = c00 + (c01 - c00) * fx
        val bot = c10 + (c11 - c10) * fx
        return top + (bot - top) * fy
    }
}
