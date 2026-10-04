package com.mrj.fancyai.service

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.Process
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import com.mrj.fancyai.R
import com.mrj.fancyai.sd.DitRuntime
import com.mrj.fancyai.sd.MnnSd15Runtime
import com.mrj.fancyai.sd.MnnSdxlRuntime
import com.mrj.fancyai.sd.MnnUpscaler
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
import kotlinx.coroutines.currentCoroutineContext
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
    internal lateinit var mnnSdxl: SdEngine
        private set
    internal lateinit var ditEngine: SdEngine
        private set
    @Volatile private var generation: Job? = null
    @Volatile internal var upscalerLoaded = false

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
            imageRefineEnabled: Boolean,
            imageRefineStrength: Float,
            imageRefinePrompt: String?,
            callback: IImageCallback?,
        ) {
            startOperation(callback, steps, cfg, "generation") { onProgress ->
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
                    imageRefineEnabled = imageRefineEnabled,
                    imageRefineStrength = imageRefineStrength,
                    imageRefinePrompt = imageRefinePrompt.orEmpty(),
                    onProgress = onProgress,
                )
            }
        }

        override fun enhance(
            sourcePath: String?,
            upscalerModelPath: String?,
            outputPath: String?,
            refineStrength: Float,
            modelDir: String?,
            prompt: String?,
            negativePrompt: String?,
            steps: Int,
            cfg: Float,
            sampler: String?,
            schedule: String?,
            vPred: Boolean,
            mnnBackend: String?,
            mnnMemoryPolicy: String?,
            callback: IImageCallback?,
        ) {
            startOperation(callback, steps, cfg, "enhancement") { onProgress ->
                enhanceImage(
                    sourcePath = requireNotNull(sourcePath),
                    upscalerModelPath = requireNotNull(upscalerModelPath),
                    outputPath = requireNotNull(outputPath),
                    refineStrength = refineStrength,
                    modelDir = modelDir.orEmpty(),
                    prompt = prompt.orEmpty(),
                    negativePrompt = negativePrompt.orEmpty(),
                    steps = steps,
                    cfg = cfg,
                    sampler = sampler.orEmpty(),
                    schedule = schedule.orEmpty(),
                    vPred = vPred,
                    backend = mnnBackend.orEmpty(),
                    memoryPolicy = mnnMemoryPolicy.orEmpty(),
                    job = currentCoroutineContext()[Job],
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
        render: suspend ((Int) -> Unit) -> Output,
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
                var output: Output? = null
                var error: String? = null
                try {
                    val manager = getSystemService(NotificationManager::class.java)
                    if (manager.getNotificationChannel("image_service") == null) {
                        manager.createNotificationChannel(
                            NotificationChannel(
                                "image_service",
                                getString(R.string.notification_active_session_channel),
                                NotificationManager.IMPORTANCE_LOW,
                            )
                        )
                    }
                    val notification = NotificationCompat.Builder(this@ImageService, "image_service")
                        .setContentTitle(getString(R.string.notification_active_session_title))
                        .setContentText("Generating Image...")
                        .setSmallIcon(R.mipmap.ic_launcher)
                        .setPriority(NotificationCompat.PRIORITY_LOW)
                        .setOngoing(true)
                        .setOnlyAlertOnce(true)
                        .build()
                    ServiceCompat.startForeground(
                        this@ImageService,
                        200,
                        notification,
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                            ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
                        } else {
                            0
                        },
                    )

                    output = render { percent ->
                        runCatching { callback.onProgress(percent.coerceIn(0, 100)) }
                    }
                } catch (_: CancellationException) {
                    AppLog.write(android.util.Log.INFO, TAG, "Image $operation cancelled")
                    error = "Image $operation cancelled."
                } catch (failure: Throwable) {
                    AppLog.write(android.util.Log.ERROR, TAG, "Image $operation failed", failure)
                    error = failure.message ?: failure.javaClass.simpleName
                } finally {
                    stopForeground(STOP_FOREGROUND_REMOVE)
                    if (output?.retainModels != true) unload()
                    generation = null
                    AppLog.write(android.util.Log.INFO, TAG, "Image operation ended completed=${output != null} retained=${output?.retainModels == true} elapsedMs=${SystemClock.elapsedRealtime() - started}")
                    runCatching {
                        val completed = output
                        if (completed == null) callback.onError(error)
                        else callback.onComplete(completed.path, completed.width, completed.height)
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
        mnnSdxl = SdEngine { MnnSdxlRuntime(this) }
        ditEngine = SdEngine { DitRuntime(this) }
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onUnbind(intent: Intent?): Boolean {
        val active = generation
        cancelRuntimes()
        scope.launch {
            active?.cancelAndJoin()
            unload()
            Process.killProcess(Process.myPid())
        }
        return false
    }

    override fun onDestroy() {
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
        runCatching { mnnSd15.unload() }
        runCatching { qnnSdxl.unload() }
        runCatching { mnnSdxl.unload() }
        runCatching { ditEngine.unload() }
        if (upscalerLoaded) {
            runCatching { MnnUpscaler.nativeUnload() }
            upscalerLoaded = false
        }
    }

    internal fun cancelGeneration(reason: String) {
        cancelRuntimes()
        generation?.cancel(CancellationException(reason))
    }

    private fun cancelRuntimes() {
        qnnSd15.cancel()
        mnnSd15.cancel()
        qnnSdxl.cancel()
        mnnSdxl.cancel()
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
        const val UPSCALER_DIRECTORY = "upscalers"
    }
}
