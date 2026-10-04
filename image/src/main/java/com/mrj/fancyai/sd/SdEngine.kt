package com.mrj.fancyai.sd

import android.content.Context
import android.graphics.Bitmap

class SdEngine(private val runtimeFor: (String) -> SdRuntime) {

    constructor(ctx: Context) : this({ QnnRuntime(ctx) })

    @Volatile var loadedDir: String? = null
        private set

    @Volatile var loadedWidth: Int = 512
        private set
    @Volatile var loadedHeight: Int = 512
        private set

    private var runtime: SdRuntime? = null

    fun ensureLoaded(
        dir: String,
        width: Int,
        height: Int,
        memoryPolicy: MnnMemoryPolicy? = null,
        backend: MnnBackend? = null,
    ): Boolean {
        memoryPolicy?.let { runtime?.setMemoryPolicy(it) }
        backend?.let { runtime?.setBackend(it) }
        if (loadedDir == dir && loadedWidth == width && loadedHeight == height) return true
        return loadModel(dir, width, height, memoryPolicy, backend)
    }

    fun loadModel(
        dir: String,
        width: Int = 512,
        height: Int = 512,
        memoryPolicy: MnnMemoryPolicy? = null,
        backend: MnnBackend? = null,
    ): Boolean {
        unload()
        val rt = runtimeFor(dir)
        memoryPolicy?.let(rt::setMemoryPolicy)
        backend?.let(rt::setBackend)
        val ok = rt.load(dir, width, height)
        runtime = if (ok) rt else null
        loadedDir = if (ok) dir else null
        loadedWidth = if (ok) width else 512
        loadedHeight = if (ok) height else 512
        return ok
    }

    fun unload() {
        runtime?.unload()
        runtime = null
        loadedDir = null
        loadedWidth = 512
        loadedHeight = 512
    }

    fun finishRequest() = runtime?.finishRequest()

    fun cancel() = runtime?.cancel()

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
        onProgress: (Int) -> Unit
    ): Bitmap {
        val rt = runtime ?: error("No SD 1.5 model is loaded.")
        return rt.generate(
            prompt = prompt,
            negative = negative,
            width = loadedWidth,
            height = loadedHeight,
            steps = steps,
            cfg = cfg,
            seed = seed,
            source = source,
            strength = strength,
            sampler = sampler,
            schedule = schedule,
            vPred = vPred,
            onProgress = onProgress,
        )
    }

}
