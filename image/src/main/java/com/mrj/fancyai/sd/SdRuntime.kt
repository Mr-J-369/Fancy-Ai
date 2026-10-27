package com.mrj.fancyai.sd

import android.content.Context
import android.graphics.Bitmap
import androidx.core.graphics.createBitmap
import kotlinx.coroutines.CancellationException
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

private const val DIT_MODEL_ANIMA = 7
private const val ANIMA_OPFUSION_MASK = "246"

internal fun interface NativeImageProgress {
    fun onProgress(percent: Int)
}

interface SdRuntime {
    fun load(dir: String, width: Int, height: Int): Boolean
    fun unload()
    fun cancel()
    fun setBackend(backend: MnnBackend) = Unit
    fun setMemoryPolicy(policy: MnnMemoryPolicy) = Unit
    fun setDitBackend(backend: DitBackend) = Unit
    fun finishRequest() = Unit

    fun generate(
        prompt: String,
        negative: String,
        width: Int,
        height: Int,
        steps: Int,
        cfg: Float,
        seed: Long,
        source: Bitmap?,
        strength: Float,
        sampler: SamplerType,
        schedule: Schedule,
        vPred: Boolean,
        onProgress: (Int) -> Unit,
    ): Bitmap
}

internal class NativeInferenceException(detail: String) :
    IllegalStateException("$detail — see logcat for the exact backend error")

class QnnRuntime(private val ctx: Context) : SdRuntime {
    override fun load(dir: String, width: Int, height: Int): Boolean {
        check(QnnDiffusion.nativeAbiVersion() == QnnDiffusion.ABI_VERSION) {
            "Unsupported QNN diffusion native ABI"
        }
        return QnnDiffusion.nativeLoad(
            ctx,
            dir,
            ctx.applicationInfo.nativeLibraryDir,
            File(ctx.filesDir, "dsp").absolutePath,
            width,
            height,
        )
    }

    override fun unload() = QnnDiffusion.nativeUnload()
    override fun cancel() = QnnDiffusion.nativeCancel()

    override fun generate(
        prompt: String,
        negative: String,
        width: Int,
        height: Int,
        steps: Int,
        cfg: Float,
        seed: Long,
        source: Bitmap?,
        strength: Float,
        sampler: SamplerType,
        schedule: Schedule,
        vPred: Boolean,
        onProgress: (Int) -> Unit,
    ): Bitmap = createBitmap(width, height).also { output ->
        if (!QnnDiffusion.nativeGenerateSd15(
                prompt = prompt,
                negativePrompt = negative,
                width = width,
                height = height,
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
            if (QnnDiffusion.nativeWasCancelled()) throw CancellationException("QNN image generation cancelled")
            throw NativeInferenceException("QNN SD 1.5 generation failed")
        }
    }
}

class MnnSd15Runtime(private val ctx: Context) : SdRuntime {
    override fun setBackend(backend: MnnBackend) =
        MnnSd15Diffusion.nativeSetBackend(backend.wireValue)

    override fun setMemoryPolicy(policy: MnnMemoryPolicy) =
        MnnSd15Diffusion.nativeSetMemoryPolicy(policy.wireValue)

    override fun finishRequest() = MnnSd15Diffusion.nativeFinishRequest()

    override fun load(dir: String, width: Int, height: Int): Boolean {
        check(MnnSd15Diffusion.nativeAbiVersion() == MnnSd15Diffusion.ABI_VERSION) {
            "Unsupported MNN diffusion native ABI"
        }
        return MnnSd15Diffusion.nativeLoad(
            ctx,
            dir,
        )
    }

    override fun unload() = MnnSd15Diffusion.nativeUnload()
    override fun cancel() = MnnSd15Diffusion.nativeCancel()

    override fun generate(
        prompt: String,
        negative: String,
        width: Int,
        height: Int,
        steps: Int,
        cfg: Float,
        seed: Long,
        source: Bitmap?,
        strength: Float,
        sampler: SamplerType,
        schedule: Schedule,
        vPred: Boolean,
        onProgress: (Int) -> Unit,
    ): Bitmap = createBitmap(width, height).also { output ->
        if (!MnnSd15Diffusion.nativeGenerate(
                prompt = prompt,
                negativePrompt = negative,
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
            if (MnnSd15Diffusion.nativeWasCancelled()) throw CancellationException("MNN image generation cancelled")
            throw NativeInferenceException("MNN SD 1.5 generation failed")
        }
    }
}

class MnnSdxlRuntime(private val ctx: Context) : SdRuntime {
    override fun setBackend(backend: MnnBackend) =
        MnnSdxlDiffusion.nativeSetBackend(backend.wireValue)

    override fun setMemoryPolicy(policy: MnnMemoryPolicy) =
        MnnSdxlDiffusion.nativeSetMemoryPolicy(policy.wireValue)

    override fun finishRequest() = MnnSdxlDiffusion.nativeFinishRequest()

    override fun load(dir: String, width: Int, height: Int): Boolean {
        check(MnnSdxlDiffusion.nativeAbiVersion() == MnnSdxlDiffusion.ABI_VERSION) {
            "Unsupported MNN SDXL diffusion native ABI"
        }
        return MnnSdxlDiffusion.nativeLoad(
            ctx,
            dir,
        )
    }

    override fun unload() = MnnSdxlDiffusion.nativeUnload()
    override fun cancel() = MnnSdxlDiffusion.nativeCancel()

    override fun generate(
        prompt: String,
        negative: String,
        width: Int,
        height: Int,
        steps: Int,
        cfg: Float,
        seed: Long,
        source: Bitmap?,
        strength: Float,
        sampler: SamplerType,
        schedule: Schedule,
        vPred: Boolean,
        onProgress: (Int) -> Unit,
    ): Bitmap {
        return createBitmap(width, height).also { output ->
            if (!MnnSdxlDiffusion.nativeGenerate(
                    prompt = prompt,
                    negativePrompt = negative,
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
                if (MnnSdxlDiffusion.nativeWasCancelled()) {
                    throw CancellationException("MNN SDXL image generation cancelled")
                }
                throw NativeInferenceException("MNN SDXL generation failed")
            }
        }
    }
}

private fun detectKindFromGguf(dir: File): Int? {
    val ggufFile = dir.listFiles()?.firstOrNull {
        it.isFile && it.name.endsWith(".gguf", ignoreCase = true) &&
            !it.name.contains("clip", ignoreCase = true) &&
            !it.name.contains("text_encoder", ignoreCase = true) &&
            !it.name.contains("qwen3", ignoreCase = true) &&
            it.name != "llm.gguf"
    } ?: return null
    if (ggufFile.length() <= 64) return null
    return runCatching {
        ggufFile.inputStream().use { stream ->
            val buffer = ByteArray(65536)
            val count = stream.read(buffer)
            if (count > 0) {
                val sample = String(buffer, 0, count, Charsets.ISO_8859_1)
                when {
                    sample.contains("llm_adapter") -> DIT_MODEL_ANIMA
                    sample.contains("cap_embedder") -> 0
                    sample.contains("double_blocks") || sample.contains("img_in") -> 1
                    sample.contains("qwen") -> 2
                    else -> null
                }
            } else null
        }
    }.getOrNull()
}

private fun detectKindFromSafetensors(dir: File): Int? {
    val safetensorsFile = dir.listFiles()?.firstOrNull {
        it.isFile && it.name.endsWith(".safetensors", ignoreCase = true) &&
            it.name != "vae.safetensors" && it.name != "ae.safetensors"
    } ?: File(dir, "dit.safetensors")

    if (!safetensorsFile.isFile || safetensorsFile.length() <= 8L) return null
    return runCatching {
        safetensorsFile.inputStream().use { stream ->
            val headerLenBytes = ByteArray(8)
            if (stream.read(headerLenBytes) == 8) {
                val headerLen = ByteBuffer.wrap(headerLenBytes).order(ByteOrder.LITTLE_ENDIAN).long
                if (headerLen in 10L..10_000_000L) {
                    val headerBytes = ByteArray(headerLen.toInt())
                    var read = 0
                    while (read < headerBytes.size) {
                        val count = stream.read(headerBytes, read, headerBytes.size - read)
                        if (count <= 0) break
                        read += count
                    }
                    val headerText = String(headerBytes, 0, read, Charsets.UTF_8)
                    when {
                        headerText.contains("llm_adapter") -> DIT_MODEL_ANIMA
                        headerText.contains("cap_embedder") -> 0
                        headerText.contains("3840") -> 1
                        headerText.contains("960") -> 0
                        else -> null
                    }
                } else null
            } else null
        }
    }.getOrNull()
}

internal fun detectDitModelKind(dir: File): Int {
    if (File(dir, "Z_IMAGE").exists() || File(dir, "ZIT").exists()) return 0
    if (File(dir, "MOSS").exists()) return 3
    if (File(dir, "KREA2").exists()) return 4
    if (File(dir, "ANIMA").exists()) return DIT_MODEL_ANIMA
    if (File(dir, "FLUX1").exists()) return 6
    if (File(dir, "KLEIN9B").exists()) return 1
    if (File(dir, "KLEIN").exists() || File(dir, "FLUX").exists()) return 1
    if (File(dir, "QWEN").exists()) return 2

    val dirName = dir.name.lowercase()
    if (dirName.contains("z_image") || dirName.contains("zimage") || dirName.contains("zit")) return 0
    if (dirName.contains("moss")) return 3
    if (dirName.contains("krea")) return 4
    if (dirName.contains("anima")) return DIT_MODEL_ANIMA
    if (dirName.contains("flux1") || dirName.contains("flux_1") || dirName.contains("flux schnell")) return 6
    if (dirName.contains("klein") || dirName.contains("flux")) return 1
    if (dirName.contains("qwen")) return 2

    return detectKindFromGguf(dir) ?: detectKindFromSafetensors(dir) ?: 0
}

private val DIT_SOC_ARCH = mapOf(
    "SM8550" to 73, "SM8550P" to 73, "QCS8550" to 73, "QCM8550" to 73,
    "SM8650" to 75, "SM8650P" to 75,
    "SM8750" to 79, "SM8750P" to 79,
    "SM8850" to 81, "SM8850P" to 81,
    "SM8950" to 85, "SM8975" to 85,
    "SM8735" to 73, "SM8635" to 73,
)

private val DIT_NO_HMX_SOCS = setOf("SM8635", "SM8735")
private val DIT_SKEL_ARCHES = setOf(73, 75, 79, 81, 85)

private fun inferDitArchFromSoc(socModel: String?): Int? {
    val number = socModel?.let { Regex("SM(\\d+)").find(it.uppercase()) }?.groupValues?.getOrNull(1)?.toIntOrNull() ?: return null
    return when {
        number >= 8950 -> 85
        number >= 8850 -> 81
        number >= 8750 -> 79
        number >= 8650 -> 75
        number >= 8550 -> 73
        else -> null
    }
}

internal fun applyDitLowArchEnvironment(forcedArch: Int? = null) {
    val socModel = android.os.Build.SOC_MODEL
    val arch = forcedArch ?: DIT_SOC_ARCH[socModel] ?: inferDitArchFromSoc(socModel)
    if (arch == null) {
        android.system.Os.setenv("GGML_HEXAGON_OPFILTER", "CONV_2D_UPSCALE|CONCAT|SWIGLU", true)
        android.system.Os.setenv("DIT_MAX_VRAM_GIB", "2.0", true)
        return
    }
    val verified = forcedArch ?: DIT_SOC_ARCH[socModel]
    if (verified != null && arch in DIT_SKEL_ARCHES) {
        android.system.Os.setenv("GGML_HEXAGON_ARCH", arch.toString(), true)
    }
    if (arch < 79) {
        val filter = if (arch < 75) "CONV_2D_UPSCALE|CONCAT|SWIGLU" else "CONV_2D_UPSCALE|CONCAT"
        android.system.Os.setenv("GGML_HEXAGON_OPFILTER", filter, true)
    }
    if (android.os.Build.SOC_MODEL in DIT_NO_HMX_SOCS) {
        android.system.Os.setenv("GGML_HEXAGON_NHMX", "0", true)
    }
    when {
        arch < 73 -> Unit
        arch < 75 -> android.system.Os.setenv("DIT_MAX_VRAM_GIB", "2.0", true)
        arch < 79 -> android.system.Os.setenv("DIT_MAX_VRAM_GIB", "2.75", true)
        verified != null -> android.system.Os.setenv("DIT_MAX_VRAM_GIB", "6.0", true)
    }
}

class DitRuntime(private val ctx: Context) : SdRuntime {
    @Volatile private var loadedFacts = "dit=unloaded"
    private var backend = DitBackend.AUTOMATIC

    override fun setDitBackend(backend: DitBackend) {
        this.backend = backend
    }

    override fun load(dir: String, width: Int, height: Int): Boolean {
        check(DitDiffusion.nativeAbiVersion() == DitDiffusion.ABI_VERSION) {
            "Unsupported DiT diffusion native ABI"
        }
        val kind = detectDitModelKind(File(dir))
        val forcedArch = File(ctx.filesDir, "dit_arch_override.txt").takeIf { it.isFile }
            ?.readText()?.trim()?.toIntOrNull()
        applyDitLowArchEnvironment(forcedArch)
        if (kind == DIT_MODEL_ANIMA) {
            android.system.Os.setenv("GGML_HEXAGON_OPFUSION", ANIMA_OPFUSION_MASK, true)
        } else {
            android.system.Os.unsetenv("GGML_HEXAGON_OPFUSION")
        }
        loadedFacts = buildString {
            append("kind=").append(kind)
            append(" model=").append(File(dir).name)
            var ggufBytes = 0L
            File(dir).listFiles()?.forEach { f ->
                if (f.isFile && f.name.endsWith(".gguf", ignoreCase = true)) ggufBytes += f.length()
            }
            if (ggufBytes > 0) append(" size=").append(ggufBytes / 1048576).append("MiB")
            append(" arch=").append(forcedArch ?: DIT_SOC_ARCH[android.os.Build.SOC_MODEL] ?: inferDitArchFromSoc(android.os.Build.SOC_MODEL) ?: -1)
            if (backend == DitBackend.GPU) {
                append(" GPU budget=auto RAM budget=4GiB")
            } else {
                System.getenv("DIT_MAX_VRAM_GIB")?.let { append(" budget=").append(it).append("GiB") }
            }
        }
        val loaded = DitDiffusion.nativeLoad(
            application = ctx,
            modelDir = dir,
            nativeLibDir = ctx.applicationInfo.nativeLibraryDir,
            dspDir = File(ctx.filesDir, "dsp").absolutePath,
            sharedDir = File(ctx.filesDir, "dit_components").absolutePath,
            backend = backend.deviceName?.let { "diffusion=$it,te=$it,vae=$it" },
            kind = kind,
            threads = 4,
        )
        check(loaded) { "${DitDiffusion.nativeLastError().take(500)} | $loadedFacts" }
        return true
    }

    override fun unload() = DitDiffusion.nativeUnload()
    override fun cancel() = DitDiffusion.nativeCancel()

    override fun generate(
        prompt: String,
        negative: String,
        width: Int,
        height: Int,
        steps: Int,
        cfg: Float,
        seed: Long,
        source: Bitmap?,
        strength: Float,
        sampler: SamplerType,
        schedule: Schedule,
        vPred: Boolean,
        onProgress: (Int) -> Unit,
    ): Bitmap = createBitmap(width, height).also { output ->
        if (!DitDiffusion.nativeGenerate(
                prompt = prompt,
                negativePrompt = negative,
                width = width,
                height = height,
                steps = steps,
                cfg = cfg,
                seed = seed,
                source = source,
                strength = strength,
                output = output,
                progress = onProgress,
                sampler = sampler.name,
                scheduler = schedule.name,
            )
        ) {
            output.recycle()
            if (DitDiffusion.nativeWasCancelled()) {
                throw CancellationException("DiT image generation cancelled")
            }
            throw NativeInferenceException("${DitDiffusion.nativeLastError().take(500)} | $loadedFacts res=${width}x${height} steps=$steps sampler=${sampler.name} schedule=${schedule.name}")
        }
    }
}
