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
    @Volatile private var loadedDitBackend: DitBackend? = null
    @Volatile private var loadedDitPower: DitPower? = null

    private var runtime: SdRuntime? = null

    private var loadedDitLowRam: Boolean? = null

    fun ensureLoaded(
        dir: String,
        width: Int,
        height: Int,
        memoryPolicy: MnnMemoryPolicy? = null,
        backend: MnnBackend? = null,
        ditBackend: DitBackend? = null,
        ditPower: DitPower? = null,
        ditLowRam: Boolean? = null,
    ): Boolean {
        memoryPolicy?.let { runtime?.setMemoryPolicy(it) }
        backend?.let { runtime?.setBackend(it) }
        ditBackend?.let { runtime?.setDitBackend(it) }
        ditPower?.let { runtime?.setDitPower(it) }
        ditLowRam?.let { runtime?.setDitLowRam(it) }
        return (loadedDir == dir && loadedWidth == width && loadedHeight == height &&
            (ditBackend == null || loadedDitBackend == ditBackend) &&
            (ditPower == null || loadedDitPower == ditPower) &&
            (ditLowRam == null || loadedDitLowRam == ditLowRam)) ||
            loadModel(dir, width, height, memoryPolicy, backend, ditBackend, ditPower, ditLowRam)
    }

    fun loadModel(
        dir: String,
        width: Int = 512,
        height: Int = 512,
        memoryPolicy: MnnMemoryPolicy? = null,
        backend: MnnBackend? = null,
        ditBackend: DitBackend? = null,
        ditPower: DitPower? = null,
        ditLowRam: Boolean? = null,
    ): Boolean {
        unload()
        val rt = runtimeFor(dir)
        memoryPolicy?.let(rt::setMemoryPolicy)
        backend?.let(rt::setBackend)
        ditBackend?.let(rt::setDitBackend)
        ditPower?.let(rt::setDitPower)
        ditLowRam?.let(rt::setDitLowRam)
        val ok = rt.load(dir, width, height)
        runtime = if (ok) rt else null
        loadedDir = if (ok) dir else null
        loadedWidth = if (ok) width else 512
        loadedHeight = if (ok) height else 512
        loadedDitBackend = if (ok) ditBackend else null
        loadedDitPower = if (ok) ditPower else null
        loadedDitLowRam = if (ok) ditLowRam else null
        return ok
    }

    fun unload() {
        runtime?.unload()
        runtime = null
        loadedDir = null
        loadedWidth = 512
        loadedHeight = 512
        loadedDitBackend = null
        loadedDitPower = null
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
        ditCacheMode: String = "",
        ditCacheOption: String = "",
        guidance: Float = 3.5f,
        mask: Bitmap? = null,
        referenceImages: Array<Bitmap>? = null,
        onProgress: (Int) -> Unit,
        onPreview: ((Bitmap) -> Unit)? = null,
        loraPaths: List<String> = emptyList(),
        loraStrengths: FloatArray = FloatArray(0),
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
            ditCacheMode = ditCacheMode,
            ditCacheOption = ditCacheOption,
            guidance = guidance,
            mask = mask,
            referenceImages = referenceImages,
            onProgress = onProgress,
            onPreview = onPreview,
            loraPaths = loraPaths,
            loraStrengths = loraStrengths,
        )
    }

}
