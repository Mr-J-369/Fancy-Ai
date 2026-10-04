package com.mrj.fancyai.service

import android.graphics.Bitmap
import android.net.Uri
import androidx.core.graphics.createBitmap
import androidx.core.graphics.scale
import com.mrj.fancyai.sd.MnnBackend
import com.mrj.fancyai.sd.MnnMemoryPolicy
import com.mrj.fancyai.sd.MnnUpscaler
import com.mrj.fancyai.sd.SamplerType
import com.mrj.fancyai.sd.Schedule
import com.mrj.fancyai.sd.SdModel
import com.mrj.fancyai.sd.SdRuntimeType
import com.mrj.fancyai.sd.hd.AuraUpscaler
import com.mrj.fancyai.sd.hd.UpscaleSize
import com.mrj.fancyai.sd.hd.UpscalerModels
import com.mrj.fancyai.service.ImageService.Companion.OUTPUT_DIRECTORY
import com.mrj.fancyai.service.ImageService.Companion.UPSCALER_DIRECTORY
import com.mrj.fancyai.service.ImageService.Output
import com.mrj.fancyai.util.decodeImage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import java.io.File

internal suspend fun ImageService.enhanceImage(
    sourcePath: String,
    upscalerModelPath: String,
    outputPath: String,
    refineStrength: Float,
    modelDir: String,
    prompt: String,
    negativePrompt: String,
    steps: Int,
    cfg: Float,
    sampler: String,
    schedule: String,
    vPred: Boolean,
    backend: String,
    memoryPolicy: String,
    job: Job?,
    onProgress: (Int) -> Unit,
): Output {
    val sourceFile = privateInput(File(cacheDir, OUTPUT_DIRECTORY), sourcePath)
    val source = decodeImage(this, Uri.fromFile(sourceFile)).asArgb8888()
    val upscaler = privateInput(
        File(filesDir, UPSCALER_DIRECTORY),
        upscalerModelPath,
    )
    val output = privateOutput(outputPath)
    val strength = refineStrength.coerceIn(0f, 0.9f)
    val refineModel = if (strength > 0.01f) privateInput(File(filesDir, "sd_models"), modelDir) else null
    val refinePackage = refineModel?.let(SdModel::packageType)
    unload()
    check(MnnUpscaler.nativeAbiVersion() == MnnUpscaler.ABI_VERSION) {
        "Unsupported MNN upscaler native ABI."
    }
    check(
        MnnUpscaler.nativeLoad(
            application = this,
            modelPath = upscaler.path,
            backend = MnnBackend.fromName(backend).wireValue,
            tileSide = UpscalerModels.TILE_SIDE,
        ),
    ) {
        "HD upscaler model load failed."
    }
    upscalerLoaded = true
    val nativeScale = MnnUpscaler.nativeScale()

    val selectedSampler = SamplerType.fromName(sampler)
    val selectedSchedule = runCatching { Schedule.valueOf(schedule) }.getOrDefault(Schedule.KARRAS)
    val refineTile: suspend (IntArray, Int, Int, Float) -> IntArray = if (refineModel != null && refinePackage != null) {
        val engine = if (refinePackage.runtime == SdRuntimeType.MNN) mnnSd15 else qnnSd15
        val loaded = if (refinePackage.runtime == SdRuntimeType.MNN) {
            engine.ensureLoaded(
                refineModel.path,
                512,
                512,
                MnnMemoryPolicy.fromName(memoryPolicy),
                MnnBackend.fromName(backend),
            )
        } else {
            engine.ensureLoaded(refineModel.path, 512, 512)
        }
        check(loaded) { "HD redraw model load failed." };
        { pixels, width, height, tileStrength ->
            renderRegion(pixels, width, height, width, height) { tile ->
                engine.generate(
                    prompt = prompt,
                    negative = negativePrompt,
                    steps = steps,
                    cfg = cfg,
                    seed = 0L,
                    source = tile,
                    strength = tileStrength,
                    sampler = selectedSampler,
                    schedule = selectedSchedule,
                    vPred = vPred,
                    onProgress = {},
                )
            }
        }
    } else {
        { pixels, _, _, _ -> pixels }
    }

    val pipeline = AuraUpscaler(
        tile = 512,
        overlap = if (refineModel != null) 64 else 0,
        feather = if (refineModel != null) 48 else 0,
        refineStrength = strength,
        nativeScale = nativeScale,
        refineTile = refineTile,
        onProgress = { percent ->
            if (job?.isActive == false) throw CancellationException("Image enhancement cancelled.")
            onProgress(percent)
        },
    )

    val result = pipeline.enhance(source, UpscaleSize.X2)
    return result.writeImageOutput(
        output, 94, "Enhanced image could not be saved.",
        mnnSd15.takeIf { refinePackage?.runtime == SdRuntimeType.MNN },
        MnnMemoryPolicy.fromName(memoryPolicy),
    )
}

/** Converts a pixel region through the model's bitmap size and releases its intermediate bitmaps. */
private fun renderRegion(
    pixels: IntArray,
    width: Int,
    height: Int,
    modelWidth: Int,
    modelHeight: Int,
    generate: (Bitmap) -> Bitmap,
): IntArray {
    val crop = createBitmap(width, height).also { it.setPixels(pixels, 0, width, 0, 0, width, height) }
    val modelInput = if (width == modelWidth && height == modelHeight) {
        crop
    } else {
        crop.scale(modelWidth, modelHeight).also { crop.recycle() }
    }
    val refined = try {
        generate(modelInput)
    } finally {
        modelInput.recycle()
    }
    val output = if (refined.width == width && refined.height == height) {
        refined
    } else {
        refined.scale(width, height).also { refined.recycle() }
    }
    return IntArray(output.width * output.height).also {
        output.getPixels(it, 0, output.width, 0, 0, output.width, output.height)
    }.also { output.recycle() }
}

internal fun Bitmap.asArgb8888(): Bitmap {
    if (config == Bitmap.Config.ARGB_8888) return this
    return requireNotNull(copy(Bitmap.Config.ARGB_8888, false)) {
        "Source image could not be prepared."
    }.also { recycle() }
}
