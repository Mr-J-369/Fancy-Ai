package com.mrj.fancyai.sd.hd

internal object Rgb {
    fun red(p: Int) = (p ushr 16) and 0xFF
    fun green(p: Int) = (p ushr 8) and 0xFF
    fun blue(p: Int) = p and 0xFF
    fun argb(r: Int, g: Int, b: Int): Int =
        (0xFF shl 24) or
            (r.coerceIn(0, 255) shl 16) or
            (g.coerceIn(0, 255) shl 8) or
            b.coerceIn(0, 255)
}
