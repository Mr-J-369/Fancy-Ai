package com.mrj.fancyai.sd

/** JNI adapter owned exclusively by the private `:image` process. */
object MnnUpscaler {
    const val ABI_VERSION = 2

    init {
        System.loadLibrary("fancy_mnn_upscale")
    }

    @JvmStatic external fun nativeAbiVersion(): Int
    @JvmStatic external fun nativeLoad(
        application: android.content.Context,
        modelPath: String,
        backend: Int,
        tileSide: Int,
    ): Boolean

    @JvmStatic external fun nativeUpscaleImage(
        argb: IntArray,
        width: Int,
        height: Int,
        coreTile: Int,
        pad: Int,
    ): IntArray

    @JvmStatic external fun nativeScale(): Int

    @JvmStatic external fun nativeUnload()
}
