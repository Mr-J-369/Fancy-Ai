package com.mrj.fancyai.sd

import java.io.File

enum class AuraOrientation(val width: Int, val height: Int) {
    SQUARE(512, 512),
    LANDSCAPE(768, 512),
    PORTRAIT(512, 768),
}

object AuraSizes {
    private val patchName = Regex("(\\d+)x(\\d+)\\.patch")

    fun available(dir: File): List<AuraOrientation> {
        val sizes = dir.listFiles()
            ?.mapNotNull { file ->
                patchName.matchEntire(file.name)?.destructured?.let { (width, height) ->
                    width.toInt() to height.toInt()
                }
            }
            ?.toSet()
            .orEmpty() + (512 to 512)
        return AuraOrientation.entries.filter { (it.width to it.height) in sizes }
    }
}
