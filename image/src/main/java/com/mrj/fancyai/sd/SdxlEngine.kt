package com.mrj.fancyai.sd

import android.content.Context
import android.graphics.Bitmap
import androidx.core.graphics.createBitmap
import kotlinx.coroutines.CancellationException

class SdxlEngine(private val ctx: Context) {

    @Volatile var loadedDir: String? = null
        private set
    fun ensureLoaded(dir: String): Boolean {
        if (loadedDir == dir) return true
        return loadModel(dir)
    }

    fun loadModel(dir: String): Boolean {
        check(QnnDiffusion.nativeAbiVersion() == QnnDiffusion.ABI_VERSION) {
            "Unsupported QNN diffusion native ABI"
        }
        if (loadedDir != null) QnnDiffusion.nativeUnload()
        val loaded = QnnDiffusion.nativeLoadSdxl(
            ctx,
            dir,
            ctx.applicationInfo.nativeLibraryDir,
            java.io.File(ctx.filesDir, "dsp").absolutePath,
        )
        loadedDir = dir.takeIf { loaded }
        return loaded
    }

    fun unload() {
        loadedDir ?: return
        QnnDiffusion.nativeUnload()
        loadedDir = null
    }

    fun cancel() = QnnDiffusion.nativeCancel()

    fun generate(
        prompt: String,
        negative: String,
        steps: Int,
        cfg: Float,
        seed: Long,
        source: Bitmap? = null,
        strength: Float = 0.75f,
        sampler: SamplerType = SamplerType.DPMPP_2M,
        schedule: Schedule = Schedule.KARRAS,
        vPred: Boolean = false,
        onProgress: (Int) -> Unit,
    ): Bitmap {
        if (loadedDir == null) throw NativeInferenceException("SDXL runtime is not loaded")
        val size = SdxlResolution.FIXED
        return createBitmap(size.width, size.height).also { output ->
            if (!QnnDiffusion.nativeGenerateSdxl(
                    prompt = prompt,
                    negativePrompt = negative,
                    width = size.width,
                    height = size.height,
                    steps = steps,
                    cfg = cfg,
                    seed = seed,
                    source = source,
                    strength = strength,
                    sampler = sampler.wireValue,
                    schedule = schedule.wireValue,
                    vPred = vPred,
                    output = output,
                    progress = onProgress,
                )
            ) {
                output.recycle()
                if (QnnDiffusion.nativeWasCancelled()) throw CancellationException("QNN SDXL generation cancelled")
                throw NativeInferenceException("QNN SDXL generation failed")
            }
        }
    }
}
