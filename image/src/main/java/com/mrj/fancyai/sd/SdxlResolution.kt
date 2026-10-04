package com.mrj.fancyai.sd

data class SdxlResolution(val width: Int, val height: Int) {
    companion object {
        /** Current QNN SDXL packages use the fixed 1024×1024 graph. */
        val FIXED = SdxlResolution(1024, 1024)
    }
}
