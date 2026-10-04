package com.mrj.fancyai.sd

import android.graphics.Bitmap

/** Complete-operation QNN SD 1.5 and SDXL JNI surface. */
internal object QnnDiffusion {
    const val ABI_VERSION = 2

    init {
        System.loadLibrary("fancy_qnn_diffusion")
    }

    @JvmStatic external fun nativeAbiVersion(): Int
    @JvmStatic external fun nativeCountTokens(tokenizerJsonPath: String, text: String): Int
    @JvmStatic external fun nativeLoad(
        application: android.content.Context,
        dir: String,
        libDir: String,
        skelDir: String,
        width: Int,
        height: Int,
    ): Boolean
    @JvmStatic external fun nativeLoadSdxl(
        application: android.content.Context,
        dir: String,
        libDir: String,
        skelDir: String,
    ): Boolean
    @JvmStatic external fun nativeUnload()
    @JvmStatic external fun nativeCancel()
    @JvmStatic external fun nativeWasCancelled(): Boolean

    @JvmStatic external fun nativeGenerateSd15(
        prompt: String,
        negativePrompt: String,
        width: Int,
        height: Int,
        steps: Int,
        cfg: Float,
        seed: Long,
        source: Bitmap?,
        strength: Float,
        sampler: Int,
        schedule: Int,
        vPred: Boolean,
        output: Bitmap,
        progress: NativeImageProgress,
    ): Boolean

    @JvmStatic external fun nativeGenerateSdxl(
        prompt: String,
        negativePrompt: String,
        width: Int,
        height: Int,
        steps: Int,
        cfg: Float,
        seed: Long,
        source: Bitmap?,
        strength: Float,
        sampler: Int,
        schedule: Int,
        vPred: Boolean,
        output: Bitmap,
        progress: NativeImageProgress,
    ): Boolean
}
