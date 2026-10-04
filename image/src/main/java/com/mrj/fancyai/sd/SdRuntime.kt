package com.mrj.fancyai.sd

import android.content.Context
import android.graphics.Bitmap
import androidx.core.graphics.createBitmap
import kotlinx.coroutines.CancellationException
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

internal fun interface NativeImageProgress {
    fun onProgress(percent: Int)
}

interface SdRuntime {
    fun load(dir: String, width: Int, height: Int): Boolean
    fun unload()
    fun cancel()
    fun setBackend(backend: MnnBackend) = Unit
    fun setMemoryPolicy(policy: MnnMemoryPolicy) = Unit
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
    if (File(dir, "KLEIN").exists() || File(dir, "FLUX").exists()) return 1
    if (File(dir, "QWEN").exists()) return 2

    val dirName = dir.name.lowercase()
    if (dirName.contains("z_image") || dirName.contains("zimage") || dirName.contains("zit")) return 0
    if (dirName.contains("klein") || dirName.contains("flux")) return 1
    if (dirName.contains("qwen")) return 2

    return detectKindFromGguf(dir) ?: detectKindFromSafetensors(dir) ?: 0
}

class DitRuntime(private val ctx: Context) : SdRuntime {
    override fun load(dir: String, width: Int, height: Int): Boolean {
        check(DitDiffusion.nativeAbiVersion() == DitDiffusion.ABI_VERSION) {
            "Unsupported DiT diffusion native ABI"
        }
        val kind = detectDitModelKind(File(dir))
        return DitDiffusion.nativeLoad(
            application = ctx,
            modelDir = dir,
            nativeLibDir = ctx.applicationInfo.nativeLibraryDir,
            dspDir = File(ctx.filesDir, "dsp").absolutePath,
            sharedDir = File(ctx.filesDir, "dit_components").absolutePath,
            kind = kind,
            threads = 4,
        )
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
            )
        ) {
            output.recycle()
            if (DitDiffusion.nativeWasCancelled()) {
                throw CancellationException("DiT image generation cancelled")
            }
            throw NativeInferenceException("DiT generation failed")
        }
    }
}
