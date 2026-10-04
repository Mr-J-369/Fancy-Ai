package com.mrj.fancyai.sd.hd

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.min

class TileBlender(private val width: Int, private val height: Int) {
    private val sumR = FloatArray(width * height)
    private val sumG = FloatArray(width * height)
    private val sumB = FloatArray(width * height)
    private val sumW = FloatArray(width * height)

    fun accumulate(
        tile: IntArray, tileW: Int, tileH: Int, ox: Int, oy: Int,
        featherLeft: Boolean, featherTop: Boolean, featherRight: Boolean, featherBottom: Boolean,
        feather: Int,
    ) {
        for (ty in 0 until tileH) {
            val gy = oy + ty
            if (gy !in 0 until height) continue
            val wy = edgeWeight(ty, tileH, featherTop, featherBottom, feather)
            for (tx in 0 until tileW) {
                val gx = ox + tx
                if (gx !in 0 until width) continue
                val wx = edgeWeight(tx, tileW, featherLeft, featherRight, feather)
                val w = (wx * wy).toFloat().coerceAtLeast(1e-4f)
                val p = tile[ty * tileW + tx]
                val i = gy * width + gx
                sumR[i] += w * Rgb.red(p); sumG[i] += w * Rgb.green(p); sumB[i] += w * Rgb.blue(p)
                sumW[i] += w
            }
        }
    }

    fun finalize(): IntArray = IntArray(width * height) { i ->
        val w = if (sumW[i] <= 0f) 1f else sumW[i]
        Rgb.argb((sumR[i] / w).toInt(), (sumG[i] / w).toInt(), (sumB[i] / w).toInt())
    }

    private fun edgeWeight(i: Int, len: Int, low: Boolean, high: Boolean, feather: Int): Double {
        if (feather <= 0) return 1.0
        var w = 1.0
        if (low) w = min(w, ramp(i, feather))
        if (high) w = min(w, ramp(len - 1 - i, feather))
        return w.coerceIn(1e-4, 1.0)
    }

    private fun ramp(d: Int, feather: Int): Double {
        if (d >= feather) return 1.0
        val t = (d + 0.5) / feather
        return 0.5 - 0.5 * cos(PI * t)
    }
}
