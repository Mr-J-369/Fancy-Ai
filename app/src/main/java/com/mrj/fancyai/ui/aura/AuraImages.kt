package com.mrj.fancyai.ui.aura

import android.content.ComponentName
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.DeadObjectException
import android.os.Environment
import android.os.IBinder
import android.os.SystemClock
import android.provider.MediaStore
import android.util.Log
import android.widget.Toast
import androidx.core.graphics.scale
import com.mrj.fancyai.R
import com.mrj.fancyai.sd.hd.UpscalerModels
import com.mrj.fancyai.service.IImageCallback
import com.mrj.fancyai.service.IImageService
import com.mrj.fancyai.service.ImageService
import com.mrj.fancyai.service.llm.LlmEngineClient
import com.mrj.fancyai.service.llm.LlmSessionConfig
import com.mrj.fancyai.util.AppLog
import com.mrj.fancyai.util.decodeImage
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.cancellation.CancellationException
import kotlin.coroutines.resumeWithException
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

internal fun prepareSource(
    context: Context,
    uri: Uri,
    width: Int,
    height: Int,
    output: File,
): Pair<File, Bitmap> {
    val decoded = decodeImage(context, uri, maxOf(width, height) * 2)
    val scaled = if (decoded.width == width && decoded.height == height) {
        decoded
    } else {
        decoded.scale(width, height).also { decoded.recycle() }
    }
    output.parentFile?.mkdirs()
    check(FileOutputStream(output).use { scaled.compress(Bitmap.CompressFormat.JPEG, 92, it) })
    return output to scaled
}

internal fun saveToPictures(context: Context, source: File): Uri {
    val values = ContentValues().apply {
        put(MediaStore.Images.Media.DISPLAY_NAME, "Aura-${System.currentTimeMillis()}.jpg")
        put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
        put(
            MediaStore.Images.Media.RELATIVE_PATH,
            Environment.DIRECTORY_PICTURES + File.separator + "FancyAI",
        )
        put(MediaStore.Images.Media.IS_PENDING, 1)
    }
    val uri = requireNotNull(
        context.contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values),
    )
    try {
        context.contentResolver.openOutputStream(uri)?.use { output ->
            source.inputStream().use { input -> input.copyTo(output) }
        } ?: error("Picture could not be saved.")
        values.clear()
        values.put(MediaStore.Images.Media.IS_PENDING, 0)
        context.contentResolver.update(uri, values, null, null)
        return uri
    } catch (failure: Throwable) {
        context.contentResolver.delete(uri, null, null)
        throw failure
    }
}

internal fun archiveAuraResult(
    context: Context,
    rendered: AuraRenderedImage,
    settings: AuraGenerationConfig,
    source: File?,
    enhance: Boolean,
    denoising: Float,
    redrawStrength: Float,
    sourceMetadata: AuraImageMetadata?,
    characterId: String?,
    temporary: Boolean,
    startedAt: Long,
    onMetadata: (AuraImageMetadata) -> Unit,
): File {
    try {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(rendered.file.path, bounds)
        val metadata = (if (settings !is AuraLocalGenerationConfig && (!enhance || redrawStrength > 0f)) {
            rendered.metadata
        } else sourceMetadata ?: rendered.metadata).copy(
            width = bounds.outWidth, height = bounds.outHeight, enhanced = enhance,
            denoising = if (enhance) sourceMetadata?.denoising ?: 0f else denoising,
            generationDurationMs = (SystemClock.elapsedRealtime() - startedAt).coerceAtLeast(0L),
        )
        val sourceAlbum = source?.parentFile?.takeIf { folder ->
            folder.parentFile?.canonicalFile == File(context.filesDir, "media/characters").canonicalFile
        }?.name
        val album = characterId ?: sourceAlbum
        val media = File(if (temporary) context.cacheDir else context.filesDir, "media")
        val directory = if (album == null) {
            File(media, "Generated")
        } else {
            require(album == File(album).name)
            File(File(media, "characters"), album)
        }.apply { mkdirs() }
        var image = File(directory, "aura-${System.currentTimeMillis()}.jpg")
        var counter = 1
        while (image.exists()) {
            image = File(directory, "aura-${System.currentTimeMillis()}-${counter++}.jpg")
        }
        val imagePartial = File(directory, ".${image.name}.part")
        val sidecar = File(directory, "${image.nameWithoutExtension}.json")
        val sidecarPartial = File(directory, ".${sidecar.name}.part")
        try {
            rendered.file.copyTo(imagePartial, overwrite = true)
            sidecarPartial.writeText(metadata.toJson().toString())
            Files.move(imagePartial.toPath(), image.toPath(), ATOMIC_MOVE)
            Files.move(sidecarPartial.toPath(), sidecar.toPath(), ATOMIC_MOVE)
        } catch (failure: Throwable) {
            imagePartial.delete()
            sidecarPartial.delete()
            image.delete()
            sidecar.delete()
            throw failure
        }
        onMetadata(metadata)
        return image
    } catch (failure: Exception) {
        throw AuraImageException(AuraImageFailure.SAVE, failure)
    } finally { rendered.file.delete() }
}

internal fun deleteAuraResult(image: File): Boolean {
    if (image.exists() && !image.isFile) return false
    val imageDeleted = !image.exists() || image.delete()
    val sidecar = File(image.parentFile, "${image.nameWithoutExtension}.json")
    val sidecarDeleted = !sidecar.exists() || sidecar.delete()
    return imageDeleted && sidecarDeleted
}

internal const val IMAGE_CACHE_DIRECTORY = "image_service"
internal const val SOURCE_FILE = "aura-source.jpg"
private const val AURA_TAG = "Aura"

internal enum class AuraImageFailure { SERVICE_UNAVAILABLE, UPSCALE_UNSUPPORTED, UPSCALER_MISSING, GENERATION, SAVE }
internal class AuraImageException(val failure: AuraImageFailure, cause: Throwable? = null) : Exception(failure.name, cause)

internal object AuraImages {
    suspend fun execute(
        context: Context,
        source: File? = null,
        settings: AuraGenerationConfig,
        enhance: Boolean = source != null,
        denoising: Float = 0f,
        redrawStrength: Float = 0f,
        sourceMetadata: AuraImageMetadata? = null,
        characterId: String? = null,
        archive: Boolean = true,
        onMetadata: (AuraImageMetadata) -> Unit = {},
        onProgress: (Int) -> Unit = {},
    ): File {
        val app = context.applicationContext
        val output = File(File(app.cacheDir, "image_service").apply(File::mkdirs), "aura-${UUID.randomUUID()}.jpg")
        val startedAt = SystemClock.elapsedRealtime()
        return try {
            val archive: suspend (AuraRenderedImage) -> File = { rendered ->
                withContext(NonCancellable + Dispatchers.IO) {
                    archiveAuraResult(
                        app, rendered, settings, source, enhance, denoising,
                        redrawStrength, sourceMetadata, characterId, !archive, startedAt, onMetadata,
                    )
                }
            }
            when (settings) {
                is AuraLocalGenerationConfig -> executeLocal(
                    app, settings, source, enhance, denoising, redrawStrength, output, onProgress, archive,
                )
                is AuraLanGenerationConfig -> {
                    require(source == null && !enhance)
                    archive(generateLanImage(settings, output, onProgress))
                }
                is AuraRemoteGenerationConfig -> archive(
                    generateRemoteImage(settings, output, source, enhance, denoising, redrawStrength, onProgress),
                )
            }
        } finally {
            output.delete()
        }
    }

    private suspend fun executeLocal(
        app: Context,
        settings: AuraLocalGenerationConfig,
        source: File?,
        enhance: Boolean,
        denoising: Float,
        redrawStrength: Float,
        output: File,
        onProgress: (Int) -> Unit,
        archive: suspend (AuraRenderedImage) -> File,
    ): File {
        var localSession: Pair<LlmEngineClient, LlmSessionConfig>? = null
        var localModelsReleased = false
        var stagedSource: File? = null
        LlmEngineClient.sessionMutex.lock()
        var archivedFile: File? = null
        return try {
            val localSource = source?.let { image ->
                val input = File(output.parentFile, "source-${output.name}")
                stagedSource = input
                withContext(Dispatchers.IO) { image.copyTo(input) }
            }
            val upscaler = withContext(Dispatchers.IO) {
                source?.takeIf { enhance }?.let { image ->
                    if (!auraSupportsUpscale(image)) throw AuraImageException(AuraImageFailure.UPSCALE_UNSUPPORTED)
                    val file = auraUpscalerFile(app, settings.upscalerStyle)
                    if (!file.isFile) throw AuraImageException(AuraImageFailure.UPSCALER_MISSING)
                    file
                }
            }
            val ready = CompletableDeferred<IImageService>()
            val disconnected = CompletableDeferred<Unit>()
            val connection = object : ServiceConnection {
                override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
                    val service = IImageService.Stub.asInterface(binder)
                    if (service == null) onNullBinding(name) else ready.complete(service)
                }
                override fun onServiceDisconnected(name: ComponentName?) {
                    ready.completeExceptionally(AuraImageException(AuraImageFailure.SERVICE_UNAVAILABLE))
                    disconnected.complete(Unit)
                }
                override fun onBindingDied(name: ComponentName?) = onServiceDisconnected(name)
                override fun onNullBinding(name: ComponentName?) = onServiceDisconnected(name)
            }
            localSession = LlmEngineClient.captureForImage()
            val bound = app.bindService(Intent(app, ImageService::class.java), connection, Context.BIND_AUTO_CREATE or Context.BIND_IMPORTANT)
            if (!bound) throw AuraImageException(AuraImageFailure.SERVICE_UNAVAILABLE)
            var service: IImageService? = null
            val result = try {
                val active = try { withTimeout(15.seconds) { ready.await() } }
                catch (failure: TimeoutCancellationException) { throw AuraImageException(AuraImageFailure.SERVICE_UNAVAILABLE, failure) }
                service = active
                coroutineScope {
                    val generated = async {
                        try { withTimeout(15.minutes) { generateAuraOutput(active, settings, output, onProgress, localSource, upscaler, denoising, redrawStrength) } }
                        catch (failure: TimeoutCancellationException) { throw AuraImageException(AuraImageFailure.GENERATION, failure) }
                    }
                    select {
                        generated.onAwait { it }
                        disconnected.onAwait { throw AuraImageException(AuraImageFailure.SERVICE_UNAVAILABLE) }
                    }
                }
            } finally {
                try {
                    withContext(NonCancellable) {
                        try {
                            service?.let { active ->
                                withContext(Dispatchers.IO) { active.unloadModels() }
                                localModelsReleased = true
                            }
                        } catch (_: DeadObjectException) {
                            localModelsReleased = true
                        } catch (_: Exception) {
                            // Release is unconfirmed; keep LLM restoration disabled and continue unbinding.
                        }
                    }
                } finally {
                    app.unbindService(connection)
                }
            }
            val created = archive(AuraRenderedImage(result, settings.metadata))
            archivedFile = created
            created
        } finally {
            stagedSource?.delete()
            val sessionToRestore = localSession?.takeIf { localModelsReleased }
            val async = (archivedFile != null) && (sessionToRestore != null)
            restoreLlmSession(app, sessionToRestore, localSession != null, async)
        }
    }

    private suspend fun restoreLlmSession(
        app: Context,
        sessionToRestore: Pair<LlmEngineClient, LlmSessionConfig>?,
        hasLocalSession: Boolean,
        async: Boolean,
    ) {
        if (async && sessionToRestore != null) {
            CoroutineScope(Dispatchers.IO).launch {
                try {
                    withContext(NonCancellable) {
                        val (client, config) = sessionToRestore
                        try {
                            client.restoreAfterImage(config)
                        } catch (_: CancellationException) {
                            AppLog.write(Log.INFO, AURA_TAG, "Text model restoration was cancelled")
                        } catch (failure: Exception) {
                            AppLog.write(Log.ERROR, AURA_TAG, "Text model restoration after image generation failed", failure)
                            withContext(Dispatchers.Main.immediate) {
                                Toast.makeText(app, R.string.llm_image_restore_failed, Toast.LENGTH_LONG).show()
                            }
                        }
                    }
                } finally {
                    LlmEngineClient.sessionMutex.unlock()
                }
            }
        } else {
            try {
                sessionToRestore?.let { (client, config) ->
                    try {
                        client.restoreAfterImage(config)
                    } catch (_: CancellationException) {
                        AppLog.write(Log.INFO, AURA_TAG, "Text model restoration was cancelled")
                    } catch (failure: Exception) {
                        AppLog.write(Log.ERROR, AURA_TAG, "Text model restoration after image generation failed", failure)
                    }
                }
            } finally {
                if (hasLocalSession) LlmEngineClient.sessionMutex.unlock()
            }
        }
    }

    private suspend fun generateAuraOutput(
        service: IImageService,
        config: AuraLocalGenerationConfig,
        output: File,
        onProgress: (Int) -> Unit = {},
        source: File? = null,
        upscaler: File? = null,
        denoising: Float = 0f,
        redrawStrength: Float = 0f,
    ): File = suspendCancellableCoroutine { continuation ->
        val completed = AtomicBoolean()
        fun fail(failure: Throwable) {
            if (!completed.compareAndSet(false, true)) return
            output.delete()
            continuation.resumeWithException(failure)
        }
        val callback = object : IImageCallback.Stub() {
            override fun onProgress(percent: Int) {
                if (!completed.get()) onProgress(percent.coerceIn(0, 100))
            }

            override fun onComplete(outputPath: String?, width: Int, height: Int) {
                if (!completed.compareAndSet(false, true)) {
                    outputPath?.let(::File)?.delete()
                    return
                }
                val file = outputPath?.takeIf(String::isNotBlank)?.let(::File)
                if (file == null) {
                    output.delete()
                    continuation.resumeWithException(IllegalStateException("No image was returned."))
                } else {
                    continuation.resume(file) { _, value, _ -> value.delete() }
                }
            }

            override fun onError(detail: String?) = fail(IllegalStateException(detail.orEmpty()))
        }
        continuation.invokeOnCancellation {
            if (completed.compareAndSet(false, true)) {
                runCatching { service.cancel() }
                output.delete()
            }
        }
        runCatching {
            if (source != null && upscaler != null) {
                service.enhance(
                    source.path, upscaler.path, output.path, redrawStrength,
                    config.modelPath, config.prompt, config.negativePrompt, config.steps,
                    config.cfg, config.sampler, config.schedule, config.vPred,
                    config.backend, config.memoryPolicy, callback,
                )
            } else service.generate(
                config.modelPath,
                output.path,
                source?.path.orEmpty(),
                config.prompt,
                config.negativePrompt,
                config.width,
                config.height,
                config.steps,
                config.cfg,
                config.seed,
                denoising,
                config.sampler,
                config.schedule,
                config.vPred,
                config.backend,
                config.memoryPolicy,
                config.imageRefine,
                config.imageRefineStrength,
                config.imageRefinePrompt,
                callback,
            )
        }.onFailure(::fail)
    }
}

internal fun auraSupportsUpscale(file: File): Boolean {
    if (!file.isFile) return false
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeFile(file.path, bounds)
    return (bounds.outWidth to bounds.outHeight) in UpscalerModels.SOURCE_SIZES
}
