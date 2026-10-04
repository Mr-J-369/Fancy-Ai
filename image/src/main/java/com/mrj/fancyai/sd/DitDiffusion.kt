package com.mrj.fancyai.sd

import android.content.Context
import android.graphics.Bitmap

/** Complete-operation JNI surface for the Hexagon DiT text/image pipeline. */
internal object DitDiffusion {
    const val ABI_VERSION = 1

    init {
        System.loadLibrary("fancy_dit_diffusion")
    }

    external fun nativeAbiVersion(): Int

    external fun nativeLoad(
        application: Context,
        modelDir: String,
        nativeLibDir: String,
        dspDir: String,
        sharedDir: String,
        kind: Int,
        threads: Int,
    ): Boolean

    external fun nativeUnload()

    external fun nativeCancel()

    external fun nativeWasCancelled(): Boolean

    external fun nativeGenerate(
        prompt: String,
        negativePrompt: String,
        width: Int,
        height: Int,
        steps: Int,
        cfg: Float,
        seed: Long,
        source: Bitmap?,
        strength: Float,
        output: Bitmap,
        progress: NativeImageProgress?,
    ): Boolean
}
