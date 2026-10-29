package com.mrj.fancyai.service

import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.os.SystemClock
import com.mrj.fancyai.sd.MnnBackend
import com.mrj.fancyai.sd.MnnMemoryPolicy
import com.mrj.fancyai.sd.DitBackend
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
    ditCacheMode: String,
    ditCacheOption: String,
    guidance: Float = 3.5f,
    maskPath: String = "",
    referenceImagePaths: List<String> = emptyList(),
    onProgress: (Int) -> Unit,
    onPreview: ((Bitmap) -> Unit)?,
): Output {
    val model = privateInput(File(filesDir, "sd_models"), modelDir)
    val packageType = SdModel.packageType(model)
    val ggufMiB = model.walkTopDown().filter { it.isFile && it.extension.equals("gguf", ignoreCase = true) }.sumOf { it.length() } / 1048576
    AppLog.write(
        android.util.Log.INFO,
        TAG,
        "Preparing model file=${model.name} size=${ggufMiB}MiB kind=${packageType.model}/${packageType.runtime} " +
            "width=$width height=$height steps=$steps cfg=$cfg sampler=$sampler schedule=$schedule seed=$seed " +
            "img2img=${sourcePath.isNotBlank()} backend=$backend memoryPolicy=$memoryPolicy cache=$ditCacheMode " +
            "soc=${Build.SOC_MODEL} sdk=${Build.VERSION.SDK_INT} abis=${Build.SUPPORTED_ABIS.joinToString()}",
    )
    val output = privateOutput(outputPath)
    val source = sourcePath.takeIf(String::isNotBlank)?.let {
        val file = privateInput(File(cacheDir, OUTPUT_DIRECTORY), it)
        decodeImage(this, Uri.fromFile(file)).asArgb8888()
    }
    val maskBitmap = maskPath.takeIf(String::isNotBlank)?.let {
        val file = privateInput(File(cacheDir, OUTPUT_DIRECTORY), it)
        decodeImage(this, Uri.fromFile(file)).asArgb8888()
    }
    val refBitmaps = referenceImagePaths.mapNotNull { path ->
        val file = privateInput(File(cacheDir, OUTPUT_DIRECTORY), path)
        decodeImage(this, Uri.fromFile(file)).asArgb8888()
    }
    val selectedSampler = SamplerType.fromName(sampler)
    val selectedSchedule = runCatching { Schedule.valueOf(schedule) }.getOrDefault(Schedule.KARRAS)
    val selectedBackend = MnnBackend.fromName(backend)
    val selectedDitBackend = DitBackend.fromName(backend)
    val policy = MnnMemoryPolicy.fromName(memoryPolicy)

    val engine = when {
        packageType.model == SdModelType.DIT -> ditEngine
        packageType.model == SdModelType.SDXL -> null
        packageType.runtime == SdRuntimeType.MNN -> mnnSd15
        else -> qnnSd15
    }
    var input = source
    try {
        listOf(qnnSd15, mnnSd15, ditEngine).filterNot { it === engine }.forEach { it.unload() }
        if (engine != null) qnnSdxl.unload()
        val runtimeLoadStarted = SystemClock.elapsedRealtime()
        AppLog.write(
            android.util.Log.INFO,
            TAG,
            "Runtime load started kind=${packageType.model}/${packageType.runtime} " +
                "backend=${if (packageType.model == SdModelType.DIT) selectedDitBackend else selectedBackend} memoryPolicy=$policy",
        )
        when (engine) {
            ditEngine -> check(ditEngine.ensureLoaded(model.path, width, height, ditBackend = selectedDitBackend)) {
                "DiT model load failed."
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
        AppLog.write(
            android.util.Log.INFO,
            TAG,
            "Runtime load completed kind=${packageType.model}/${packageType.runtime} elapsedMs=${SystemClock.elapsedRealtime() - runtimeLoadStarted}",
        )
        currentCoroutineContext().ensureActive()
        val rendered = engine?.generate(
            prompt, negativePrompt, steps, cfg, seed, input, denoising,
            selectedSampler, selectedSchedule, vPred, ditCacheMode, ditCacheOption,
            guidance, maskBitmap, refBitmaps.takeIf { it.isNotEmpty() }?.toTypedArray(),
            onProgress, onPreview,
        ) ?: qnnSdxl.generate(
            prompt, negativePrompt, steps, cfg, seed, input, denoising,
            selectedSampler, selectedSchedule, vPred, onProgress,
        )
        return rendered.writeImageOutput(
            output, 90, "Generated image could not be saved.",
            engine.takeIf { packageType.runtime == SdRuntimeType.MNN }, policy,
        )
    } finally {
        input?.recycle()
        maskBitmap?.recycle()
        refBitmaps.forEach { it.recycle() }
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

internal fun Bitmap.asArgb8888(): Bitmap {
    if (config == Bitmap.Config.ARGB_8888) return this
    return requireNotNull(copy(Bitmap.Config.ARGB_8888, false)) {
        "Source image could not be prepared."
    }.also { recycle() }
}

internal suspend fun ImageService.upscaleImage(
    upscalerModelPath: String,
    inputImagePath: String,
    outputPath: String,
    onProgress: (Int) -> Unit,
): Output {
    val modelFile = File(upscalerModelPath)
    require(modelFile.isFile && modelFile.length() > 0L) { "Upscaler model not found: $upscalerModelPath" }
    val inputFile = File(inputImagePath)
    require(inputFile.isFile && inputFile.length() > 0L) { "Input image not found: $inputImagePath" }
    val output = privateOutput(outputPath)

    onProgress(15)
    currentCoroutineContext().ensureActive()

    val sourceBitmap = withContext(Dispatchers.IO) {
        android.graphics.BitmapFactory.decodeFile(inputFile.absolutePath)
    } ?: error("Failed to decode image from $inputImagePath")

    val rgbBitmap = sourceBitmap.asArgb8888()

    try {
        onProgress(35)
        currentCoroutineContext().ensureActive()

        AppLog.write(
            android.util.Log.INFO,
            TAG,
            "Starting native ESRGAN upscale input=${inputFile.name} (${rgbBitmap.width}x${rgbBitmap.height}) model=${modelFile.name}",
        )

        val upscaledBitmap = withContext(Dispatchers.Default) {
            com.mrj.fancyai.sd.DitDiffusion.nativeUpscale(
                esrganPath = modelFile.absolutePath,
                input = rgbBitmap,
                upscaleFactor = 0,
                tileSize = 256,
                backend = "default",
            )
        } ?: error(com.mrj.fancyai.sd.DitDiffusion.nativeLastError().ifEmpty { "ESRGAN upscaling failed" })

        onProgress(85)
        currentCoroutineContext().ensureActive()

        AppLog.write(
            android.util.Log.INFO,
            TAG,
            "ESRGAN upscale finished, saving ${upscaledBitmap.width}x${upscaledBitmap.height} to ${output.name}",
        )

        return upscaledBitmap.writeImageOutput(
            output = output,
            quality = 95,
            failureMessage = "Upscaled image could not be saved.",
            mnnEngine = null,
            policy = MnnMemoryPolicy.LOW_MEMORY,
        )
    } finally {
        rgbBitmap.recycle()
    }
}
