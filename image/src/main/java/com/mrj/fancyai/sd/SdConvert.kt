package com.mrj.fancyai.sd

internal object SdConvert {
    init {
        System.loadLibrary("fancy_mnn_convert")
    }

    @JvmStatic external fun nativeConvert(
        application: android.content.Context,
        dir: String,
        safetensorsName: String,
        clipSkip2: Boolean,
        loraNames: Array<String>,
        loraStrengths: FloatArray,
    ): Boolean
}
