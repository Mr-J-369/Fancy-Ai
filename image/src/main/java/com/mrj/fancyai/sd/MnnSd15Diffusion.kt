package com.mrj.fancyai.sd

import android.graphics.Bitmap

/** Complete-operation JNI surface for Local Dream SD 1.5 MNN packages. */
internal object MnnSd15Diffusion {
    const val ABI_VERSION = 2

    init {
        System.loadLibrary("fancy_mnn_diffusion")
    }

    external fun nativeAbiVersion(): Int
    external fun nativeLoad(application: android.content.Context, dir: String): Boolean
    external fun nativeSetBackend(backend: Int)
    external fun nativeSetMemoryPolicy(policy: Int)
    external fun nativeFinishRequest()
    external fun nativeUnload()
    external fun nativeCancel()
    external fun nativeWasCancelled(): Boolean
    external fun nativeGenerate(
        prompt: String,
        negativePrompt: String,
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
