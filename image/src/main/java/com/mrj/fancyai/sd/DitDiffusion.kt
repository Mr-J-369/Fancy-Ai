package com.mrj.fancyai.sd

import android.content.Context
import android.graphics.Bitmap

/** Complete-operation JNI surface for the native DiT text/image pipeline. */
object DitDiffusion {
    const val ABI_VERSION = 8

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
        backend: String?,
        kind: Int,
        threads: Int,
    ): Boolean

    external fun nativeUnload()

    external fun nativeCancel()

    external fun nativeWasCancelled(): Boolean

    external fun nativeLastError(): String

    external fun nativeConvert(
        inputPath: String,
        vaePath: String?,
        outputPath: String,
        outputType: Int,
    ): Boolean

    external fun nativeUpscale(
        esrganPath: String,
        input: Bitmap,
        upscaleFactor: Int = 0,
        tileSize: Int = 256,
        backend: String? = "default",
    ): Bitmap?

    external fun nativeGenerate(
        prompt: String,
        negativePrompt: String,
        width: Int,
        height: Int,
        steps: Int,
        cfg: Float,
        guidance: Float,
        seed: Long,
        source: Bitmap?,
        mask: Bitmap?,
        referenceImages: Array<Bitmap>?,
        strength: Float,
        output: Bitmap,
        progress: NativeImageProgress?,
        preview: NativeImagePreview?,
        sampler: String,
        scheduler: String,
        cacheMode: String,
        cacheOption: String,
        clipSkip: Int = 1,
        loraPaths: Array<String>? = null,
        loraStrengths: FloatArray? = null,
    ): Boolean
}
