package com.mrj.fancyai.service

import android.graphics.Bitmap
import android.net.Uri
import com.mrj.fancyai.sd.MnnBackend
import com.mrj.fancyai.sd.MnnMemoryPolicy
import com.mrj.fancyai.sd.SamplerType
import com.mrj.fancyai.sd.Schedule
import com.mrj.fancyai.sd.SdEngine
import com.mrj.fancyai.sd.SdModel
import com.mrj.fancyai.sd.SdModelType
import com.mrj.fancyai.sd.SdRuntimeType
import com.mrj.fancyai.service.ImageService.Companion.OUTPUT_DIRECTORY
import com.mrj.fancyai.service.ImageService.Companion.TAG
import com.mrj.fancyai.service.ImageService.Output
import com.mrj.fancyai.util.AppLog
import com.mrj.fancyai.util.decodeImage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream

internal suspend fun ImageService.generateImage(
    modelDir: String,
    outputPath: String,
    sourcePath: String,
    prompt: String,
    negativePrompt: String,
    width: Int,
    height: Int,
    steps: Int,
    cfg: Float,
    seed: Long,
    denoising: Float,
    sampler: String,
    schedule: String,
    vPred: Boolean,
    backend: String,
    memoryPolicy: String,
    imageRefineEnabled: Boolean,
    imageRefineStrength: Float,
    imageRefinePrompt: String,
    onProgress: (Int) -> Unit,
): Output {
    val model = privateInput(File(filesDir, "sd_models"), modelDir)
    AppLog.write(android.util.Log.INFO, TAG, "Preparing model width=$width height=$height steps=$steps cfg=$cfg img2img=${sourcePath.isNotBlank()}")
    val output = privateOutput(outputPath)
    val source = sourcePath.takeIf(String::isNotBlank)?.let {
        val file = privateInput(File(cacheDir, OUTPUT_DIRECTORY), it)
        decodeImage(this, Uri.fromFile(file)).asArgb8888()
    }
    val packageType = SdModel.packageType(model)
    val selectedSampler = SamplerType.fromName(sampler)
    val selectedSchedule = runCatching { Schedule.valueOf(schedule) }.getOrDefault(Schedule.KARRAS)
    val selectedBackend = MnnBackend.fromName(backend)
    val policy = MnnMemoryPolicy.fromName(memoryPolicy)

    val engine = when {
        packageType.model == SdModelType.DIT -> ditEngine
        (packageType.model == SdModelType.SDXL && packageType.runtime == SdRuntimeType.MNN) -> mnnSdxl
        packageType.model == SdModelType.SDXL -> null
        packageType.runtime == SdRuntimeType.MNN -> mnnSd15
        else -> qnnSd15
    }
    var input = source
    try {
        listOf(qnnSd15, mnnSd15, mnnSdxl, ditEngine).filterNot { it === engine }.forEach { it.unload() }
        if (engine != null) qnnSdxl.unload()
        when (engine) {
            ditEngine -> check(ditEngine.ensureLoaded(model.path, width, height)) {
                "DiT model load failed."
            }
            mnnSdxl -> check(mnnSdxl.ensureLoaded(model.path, 1024, 1024, policy, selectedBackend)) {
                "MNN SDXL model load failed."
            }
            mnnSd15 -> {
                check(mnnSd15.ensureLoaded(model.path, 512, 512, policy, selectedBackend)) {
                    "MNN SD 1.5 model load failed."
                }
            }
            qnnSd15 -> check(qnnSd15.ensureLoaded(model.path, width, height)) {
                "QNN SD 1.5 model load failed."
            }
            else -> check(qnnSdxl.ensureLoaded(model.path)) { "QNN SDXL model load failed." }
        }
        val passes = if (imageRefineEnabled) 2 else 1
        lateinit var rendered: Bitmap
        var positive = prompt
        var negative = negativePrompt
        var strength = denoising
        repeat(passes) { pass ->
            currentCoroutineContext().ensureActive()
            val progress: (Int) -> Unit = { onProgress((pass * 100 + it) / passes) }
            rendered = engine?.generate(
                positive, negative, steps, cfg, seed, input, strength,
                selectedSampler, selectedSchedule, vPred, progress,
            ) ?: qnnSdxl.generate(
                positive, negative, steps, cfg, seed, input, strength,
                selectedSampler, selectedSchedule, vPred, progress,
            )
            input?.recycle()
            input = rendered
            positive = imageRefinePrompt
            negative = ""
            strength = imageRefineStrength
        }
        input = null
        return rendered.writeImageOutput(
            output, 90, "Generated image could not be saved.",
            engine.takeIf { packageType.runtime == SdRuntimeType.MNN }, policy,
        )
    } finally {
        input?.recycle()
    }
}

internal suspend fun Bitmap.writeImageOutput(
    output: File,
    quality: Int,
    failureMessage: String,
    mnnEngine: SdEngine?,
    policy: MnnMemoryPolicy,
): Output {
    output.parentFile?.mkdirs()
    try {
        withContext(Dispatchers.IO) {
            check(FileOutputStream(output).use { compress(Bitmap.CompressFormat.JPEG, quality, it) }) {
                failureMessage
            }
        }
        mnnEngine?.finishRequest()
        return Output(
            path = output.path,
            width = width,
            height = height,
            retainModels = mnnEngine != null &&
                policy != MnnMemoryPolicy.LOW_MEMORY,
        )
    } catch (failure: Throwable) {
        output.delete()
        throw failure
    } finally {
        recycle()
    }
}
