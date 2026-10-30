package com.mrj.fancyai.service

import android.app.Service
import android.content.Intent
import android.graphics.Bitmap
import android.os.Debug
import android.os.IBinder
import android.os.PowerManager
import android.os.SystemClock
import com.mrj.fancyai.sd.DitRuntime
import com.mrj.fancyai.sd.MnnSd15Runtime
import com.mrj.fancyai.sd.SdEngine
import com.mrj.fancyai.sd.SdModel
import com.mrj.fancyai.sd.SdxlEngine
import com.mrj.fancyai.util.AppLog
import com.mrj.fancyai.util.SkelExtractor
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import java.io.File
import kotlinx.coroutines.cancel as cancelScope

class ImageService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    internal lateinit var qnnSd15: SdEngine
        private set
    internal lateinit var mnnSd15: SdEngine
        private set
    internal lateinit var qnnSdxl: SdxlEngine
        private set
    internal lateinit var ditEngine: SdEngine
        private set
    @Volatile private var generation: Job? = null

    private val binder = object : IImageService.Stub() {
        override fun generate(
            modelDir: String?,
            outputPath: String?,
            sourcePath: String?,
            prompt: String?,
            negativePrompt: String?,
            width: Int,
            height: Int,
            steps: Int,
            cfg: Float,
            seed: Long,
            denoising: Float,
            sampler: String?,
            schedule: String?,
            vPred: Boolean,
            mnnBackend: String?,
            mnnMemoryPolicy: String?,
            ditCacheMode: String?,
            ditCacheOption: String?,
            guidance: Float,
            maskPath: String?,
            referenceImagePaths: List<String>?,
            livePreview: Boolean,
            loraPaths: List<String>?,
            loraStrengths: FloatArray?,
            callback: IImageCallback?,
        ) {
            val validLoras = loraPaths.orEmpty().mapNotNull { path ->
                runCatching {
                    val root = File(filesDir, "loras").canonicalFile
                    val target = File(path).canonicalFile
                    if (target.isFile && target.path.startsWith(root.path + File.separator)) target.path else null
                }.getOrNull()
            }
            startOperation(callback, steps, cfg, "generation", livePreview) { onProgress, onPreview ->
                generateImage(
                    modelDir = requireNotNull(modelDir),
                    outputPath = requireNotNull(outputPath),
                    sourcePath = sourcePath.orEmpty(),
                    prompt = requireNotNull(prompt),
                    negativePrompt = negativePrompt.orEmpty(),
                    width = width,
                    height = height,
                    steps = steps,
                    cfg = cfg,
                    seed = seed,
                    denoising = denoising,
                    sampler = sampler.orEmpty(),
                    schedule = schedule.orEmpty(),
                    vPred = vPred,
                    backend = mnnBackend.orEmpty(),
                    memoryPolicy = mnnMemoryPolicy.orEmpty(),
                    ditCacheMode = ditCacheMode.orEmpty(),
                    ditCacheOption = ditCacheOption.orEmpty(),
                    guidance = guidance,
                    maskPath = maskPath.orEmpty(),
                    referenceImagePaths = referenceImagePaths.orEmpty(),
                    loraPaths = validLoras,
                    loraStrengths = loraStrengths ?: FloatArray(0),
                    onProgress = onProgress,
                    onPreview = onPreview,
                )
            }
        }

        override fun upscale(
            upscalerModelPath: String?,
            inputImagePath: String?,
            outputPath: String?,
            callback: IImageCallback?,
        ) {
            startOperation(callback, steps = 1, cfg = 0f, "upscale") { onProgress, _ ->
                upscaleImage(
                    upscalerModelPath = requireNotNull(upscalerModelPath),
                    inputImagePath = requireNotNull(inputImagePath),
                    outputPath = requireNotNull(outputPath),
                    onProgress = onProgress,
                )
            }
        }

        override fun countTokens(modelDir: String?, text: String?): Int {
            val model = privateInput(
                File(filesDir, "sd_models"),
                requireNotNull(modelDir),
            )
            return SdModel.countTokens(model, text.orEmpty())
        }

        override fun unloadModels() {
            synchronized(this@ImageService) {
                runBlocking { generation?.join() }
                unload()
            }
        }

        override fun cancel() {
            cancelGeneration("Image generation cancelled.")
        }
    }

    internal fun startOperation(
        callback: IImageCallback?,
        steps: Int,
        cfg: Float,
        operation: String,
        livePreview: Boolean = false,
        render: suspend ((Int) -> Unit, ((Bitmap) -> Unit)?) -> Output,
    ) {
        callback ?: return
        synchronized(this) {
            if (generation?.isActive == true) {
                runCatching { callback.onError("Image generation is already running.") }
                return
            }
            generation = scope.launch {
                val started = SystemClock.elapsedRealtime()
                AppLog.write(android.util.Log.INFO, TAG, "Image operation started steps=$steps cfg=$cfg")
                val wakeLock = (getSystemService(POWER_SERVICE) as PowerManager)
                    .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "FancyAI:ImageOperation")
                var output: Output? = null
                var error: String? = null
                try {
                    wakeLock.acquire(20 * 60_000L)
                    startActiveSessionForeground(NOTIFICATION_ID)
                    val onProgress: (Int) -> Unit = { percent ->
                        runCatching { callback.onProgress(percent.coerceIn(0, 100)) }
                    }
                    var previewLogged = false
                    val onPreview: ((Bitmap) -> Unit)? = if (livePreview) {
                        { preview ->
                            try {
                                if (!previewLogged) {
                                    previewLogged = true
                                    AppLog.write(android.util.Log.INFO, TAG, "Live image preview received ${preview.width}x${preview.height}")
                                }
                                callback.onPreview(preview)
                            } catch (failure: Exception) {
                                AppLog.write(android.util.Log.WARN, TAG, "Image preview delivery failed", failure)
                            } finally {
                                preview.recycle()
                            }
                        }
                    } else null
                    output = render(onProgress, onPreview)
                } catch (_: CancellationException) {
                    AppLog.write(android.util.Log.INFO, TAG, "Image $operation cancelled")
                    error = "Image $operation cancelled."
                } catch (failure: Throwable) {
                    val mem = runCatching {
                        var total = -1L
                        var avail = -1L
                        var swapTotal = -1L
                        var swapFree = -1L
                        File("/proc/meminfo").forEachLine { line ->
                            val parts = line.split(Regex("\\s+"))
                            when (parts.firstOrNull()) {
                                "MemTotal:" -> total = parts.getOrNull(1)?.toLongOrNull() ?: total
                                "MemAvailable:" -> avail = parts.getOrNull(1)?.toLongOrNull() ?: avail
                                "SwapTotal:" -> swapTotal = parts.getOrNull(1)?.toLongOrNull() ?: swapTotal
                                "SwapFree:" -> swapFree = parts.getOrNull(1)?.toLongOrNull() ?: swapFree
                            }
                        }
                        val processMemory = Debug.MemoryInfo().also(Debug::getMemoryInfo)
                        " memTotal=${total / 1024}MiB memAvail=${avail / 1024}MiB " +
                            "swapTotal=${swapTotal / 1024}MiB swapFree=${swapFree / 1024}MiB " +
                            "processPssKiB=${processMemory.totalPss} nativePssKiB=${processMemory.nativePss} " +
                            "dalvikPssKiB=${processMemory.dalvikPss}"
                    }.getOrElse { " memorySnapshotFailed=${it.javaClass.name}:${it.message}" }
                    val reason = failure.message ?: failure.javaClass.simpleName
                    AppLog.write(android.util.Log.ERROR, TAG, "Image $operation failed: $reason$mem", failure)
                    error = failure.message ?: failure.javaClass.simpleName
                } finally {
                    if (wakeLock.isHeld) runCatching { wakeLock.release() }
                    stopForeground(STOP_FOREGROUND_REMOVE)
                    if (output?.retainModels != true) unload()
                    generation = null
                    AppLog.write(android.util.Log.INFO, TAG, "Image operation ended completed=${output != null} retained=${output?.retainModels == true} elapsedMs=${SystemClock.elapsedRealtime() - started}")
                    runCatching {
                        val completed = output
                        if (completed == null) callback.onError(error)
                        else callback.onComplete(completed.path, completed.width, completed.height)
                    }.onFailure {
                        AppLog.write(android.util.Log.ERROR, TAG, "Image callback delivery failed operation=$operation", it)
                    }
                }
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        SkelExtractor.extractAll(this)
        qnnSd15 = SdEngine(this)
        mnnSd15 = SdEngine { MnnSd15Runtime(this) }
        qnnSdxl = SdxlEngine(this)
        ditEngine = SdEngine { DitRuntime(this) }
    }

    override fun onBind(intent: Intent?): IBinder {
        AppLog.write(android.util.Log.INFO, TAG, "Image service bound")
        return binder
    }

    override fun onUnbind(intent: Intent?): Boolean {
        val active = generation
        AppLog.write(
            android.util.Log.INFO,
            TAG,
            "Last image client unbound generationActive=${active?.isActive == true}; canceling and releasing runtimes",
        )
        cancelRuntimes()
        scope.launch {
            active?.cancelAndJoin()
            unload()
        }
        return false
    }

    override fun onDestroy() {
        AppLog.write(android.util.Log.INFO, TAG, "Image service destroyed")
        cancelGeneration("Image service stopped.")
        unload()
        scope.cancelScope()
        super.onDestroy()
    }

    internal fun privateInput(root: File, path: String): File {
        val base = root.canonicalFile
        val target = File(path).canonicalFile
        require(target.path.startsWith(base.path + File.separator)) { "Input is outside private storage." }
        return target
    }

    internal fun privateOutput(path: String): File {
        val root = File(cacheDir, OUTPUT_DIRECTORY).apply { mkdirs() }.canonicalFile
        val output = File(path).canonicalFile
        require(output.parentFile?.canonicalFile == root) { "Output is outside private cache." }
        require(output.extension.equals("jpg", ignoreCase = true)) { "Output must be JPEG." }
        return output
    }

    internal fun unload() {
        AppLog.write(android.util.Log.INFO, TAG, "Releasing image runtimes")
        runCatching { qnnSd15.unload() }
            .onFailure { AppLog.write(android.util.Log.ERROR, TAG, "QNN SD 1.5 runtime release failed", it) }
        runCatching { mnnSd15.unload() }
            .onFailure { AppLog.write(android.util.Log.ERROR, TAG, "MNN SD 1.5 runtime release failed", it) }
        runCatching { qnnSdxl.unload() }
            .onFailure { AppLog.write(android.util.Log.ERROR, TAG, "QNN SDXL runtime release failed", it) }
        runCatching { ditEngine.unload() }
            .onFailure { AppLog.write(android.util.Log.ERROR, TAG, "DiT runtime release failed", it) }
    }

    internal fun cancelGeneration(reason: String) {
        cancelRuntimes()
        generation?.cancel(CancellationException(reason))
    }

    private fun cancelRuntimes() {
        qnnSd15.cancel()
        mnnSd15.cancel()
        qnnSdxl.cancel()
        ditEngine.cancel()
    }

    internal data class Output(
        val path: String,
        val width: Int,
        val height: Int,
        val retainModels: Boolean,
    )

    internal companion object {
        const val TAG = "ImageService"
        const val OUTPUT_DIRECTORY = "image_service"
        private const val NOTIFICATION_ID = 200
    }
}
