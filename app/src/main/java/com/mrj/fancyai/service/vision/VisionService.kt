package com.mrj.fancyai.service.vision

import android.app.Service
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.net.Uri
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import com.mrj.fancyai.util.AppLog
import com.mrj.fancyai.util.prepareImage
import com.mrj.fancyai.vision.VisionModels
import com.mrj.fancyai.vision.VisionRuntime
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.CancellationException
import java.util.concurrent.atomic.AtomicLong
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.cancel as cancelScope

internal enum class VisionFailure {
    INVALID_REQUEST,
    INITIALIZATION,
    OUT_OF_MEMORY,
    INFERENCE,
    PROCESS_DIED,
}

internal data class VisionRequest(
    val modelPath: String,
    val imagePath: String,
    val prompt: String,
)

internal interface VisionEvents {
    fun onLoading()
    fun onChunk(text: String)
    fun onComplete()
    fun onError(failure: VisionFailure)
    fun onCancelled()
}

class VisionService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    @Volatile private var operation: Job? = null
    @Volatile private var runtime: VisionRuntime? = null
    @Volatile private var activeRequestId = 0L

    private val binder = object : IVisionService.Stub() {
        @OptIn(DelicateCoroutinesApi::class)
        override fun project(
            requestId: Long,
            modelPath: String?,
            imagePath: String?,
            prompt: String?,
            callback: IVisionCallback?,
        ) {
            callback ?: return
            synchronized(this@VisionService) {
                if (operation?.isCompleted == false) {
                    callback.error(requestId, VisionFailure.INVALID_REQUEST, "Vision is busy")
                    return
                }
                activeRequestId = requestId
                // Even immediate cancellation must reach cleanup and the terminal callback.
                operation = scope.launch(start = CoroutineStart.ATOMIC) {
                    AppLog.write(android.util.Log.INFO, "Vision", "Projection started request=$requestId")
                    var initialized = false
                    var cancelled = false
                    var error: VisionFailure? = null
                    var detail = ""
                    var prepared: File? = null
                    try {
                        coroutineContext.ensureActive()
                        require(requestId > 0L) { "Invalid request" }
                        val model = privateModel(requireNotNull(modelPath))
                        val source = privateImage(requireNotNull(imagePath))
                        val image = if (source == File(cacheDir, INPUT_IMAGE).canonicalFile) source else {
                            File(cacheDir, "vision/chat-$requestId.jpg").also {
                                prepared = it
                                prepareImage(this@VisionService, Uri.fromFile(source), it).recycle()
                            }
                        }
                        val question = requireNotNull(prompt).trim()
                        require(question.isNotEmpty() && (question.length <= MAX_PROMPT_CHARS)) {
                            "Invalid prompt"
                        }
                        coroutineContext.ensureActive()
                        // Keep ownership if cancellation arrives while native initialization is running.
                        val opened = withContext(NonCancellable) {
                            VisionRuntime.open(
                                modelPath = model.path,
                                cacheDirectory = File(cacheDir, CACHE_DIRECTORY).apply { mkdirs() }.path,
                            )
                        }
                        runtime = opened
                        coroutineContext.ensureActive()
                        initialized = true
                        AppLog.write(android.util.Log.INFO, "Vision", "Model loaded request=$requestId")
                        callback.onReady(requestId)
                        opened.project(image.path, question).collect { text ->
                            var start = 0
                            while (start < text.length) {
                                val end = (start + MAX_CHUNK_CHARS).coerceAtMost(text.length)
                                callback.onChunk(requestId, text.substring(start, end))
                                start = end
                            }
                        }
                    } catch (_: CancellationException) {
                        cancelled = true
                        AppLog.write(android.util.Log.INFO, "Vision", "Projection cancelled request=$requestId")
                    } catch (failure: Throwable) {
                        error = when {
                            failure is OutOfMemoryError -> VisionFailure.OUT_OF_MEMORY
                            (!initialized) && (failure is IllegalArgumentException) -> VisionFailure.INVALID_REQUEST
                            !initialized -> VisionFailure.INITIALIZATION
                            else -> VisionFailure.INFERENCE
                        }
                        AppLog.write(android.util.Log.ERROR, "Vision", "Projection failed request=$requestId error=$error", failure)
                        detail = failure.message.orEmpty()
                    } finally {
                        runCatching { runtime?.close() }
                        runtime = null
                        prepared?.delete()
                        operation = null
                        AppLog.write(android.util.Log.INFO, "Vision", "Projection ended request=$requestId completed=${!cancelled && error == null}; runtime released")
                    }
                    // Terminal callbacks mean native memory has already been released.
                    when {
                        cancelled -> runCatching { callback.onCancelled(requestId) }
                        error != null -> callback.error(requestId, error, detail)
                        else -> runCatching { callback.onComplete(requestId) }
                    }
                }
            }
        }

        override fun cancel(requestId: Long) {
            synchronized(this@VisionService) {
                if (requestId == activeRequestId) {
                    runtime?.cancel()
                    operation?.cancel(CancellationException("Vision request cancelled"))
                }
            }
        }
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onDestroy() {
        runtime?.cancel()
        operation?.cancel(CancellationException("Vision service stopped"))
        scope.cancelScope()
        super.onDestroy()
    }

    internal fun privateModel(path: String): File {
        val file = File(path).canonicalFile
        require(file.isFile && (file.parentFile == VisionModels.directory(this).canonicalFile)) {
            "Invalid vision model"
        }
        return file
    }

    internal fun privateImage(path: String): File {
        val file = File(path).canonicalFile
        val expected = File(cacheDir, INPUT_IMAGE).canonicalFile
        val attachments = File(filesDir, "chat_attachments").canonicalFile
        require(file.isFile && ((file == expected) || file.path.startsWith(attachments.path + File.separator))) {
            "Invalid vision image"
        }
        return file
    }

    internal fun IVisionCallback.error(requestId: Long, type: VisionFailure, detail: String) {
        runCatching { onError(requestId, type.ordinal, detail.take(MAX_ERROR_DETAIL)) }
    }

    companion object {
        const val INPUT_IMAGE = "vision/input.jpg"
        private const val CACHE_DIRECTORY = "vision/runtime"
        private const val MAX_PROMPT_CHARS = 16_384
        private const val MAX_CHUNK_CHARS = 16_384
        private const val MAX_ERROR_DETAIL = 500
    }
}

internal class VisionException(val failure: VisionFailure) : IllegalStateException(failure.name)

internal class VisionClient(context: Context) : AutoCloseable {
    private val app = context.applicationContext
    private val main = Handler(Looper.getMainLooper())
    private var service: IVisionService? = null
    private var events: VisionEvents? = null
    private var requestId = 0L
    private var bound = false

    private val callback = object : IVisionCallback.Stub() {
        override fun onReady(id: Long) = dispatch(id) { it.onLoading() }
        override fun onChunk(id: Long, text: String?) = dispatch(id) { it.onChunk(text.orEmpty()) }
        override fun onComplete(id: Long) = finish(id) { it.onComplete() }
        override fun onCancelled(id: Long) = finish(id) { it.onCancelled() }
        override fun onError(id: Long, error: Int, detail: String?) = finish(id) {
            it.onError(VisionFailure.entries.getOrElse(error) { VisionFailure.INFERENCE })
        }
    }

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            service = IVisionService.Stub.asInterface(binder)
            val pending = request ?: return
            runCatching {
                service?.project(
                    requestId,
                    pending.modelPath,
                    pending.imagePath,
                    pending.prompt,
                    callback,
                )
            }.onFailure { finish(requestId) { it.onError(VisionFailure.PROCESS_DIED) } }
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            service = null
            finish(requestId) { it.onError(VisionFailure.PROCESS_DIED) }
        }

        override fun onBindingDied(name: ComponentName?) = onServiceDisconnected(name)
        override fun onNullBinding(name: ComponentName?) = onServiceDisconnected(name)
    }

    private var request: VisionRequest? = null

    fun project(request: VisionRequest, events: VisionEvents) {
        check(this.events == null) { "Vision is already running" }
        requestId = NEXT_REQUEST.incrementAndGet()
        this.request = request
        this.events = events
        val intent = Intent(app, VisionService::class.java)
        bound = app.bindService(intent, connection, Context.BIND_AUTO_CREATE)
        if (!bound) finish(requestId) { it.onError(VisionFailure.PROCESS_DIED) }
    }

    suspend fun describe(request: VisionRequest): String = suspendCancellableCoroutine { continuation ->
        val description = StringBuilder()
        project(
            request,
            object : VisionEvents {
                override fun onLoading() = Unit
                override fun onChunk(text: String) { description.append(text) }
                override fun onComplete() {
                    val text = description.toString().trim()
                    if (text.isEmpty()) continuation.resumeWithException(VisionException(VisionFailure.INFERENCE))
                    else continuation.resume(text)
                }
                override fun onError(failure: VisionFailure) { continuation.resumeWithException(VisionException(failure)) }
                override fun onCancelled() { continuation.cancel() }
            },
        )
        val id = requestId
        continuation.invokeOnCancellation {
            if (Looper.myLooper() == Looper.getMainLooper()) cancel()
            else main.post { if (id == requestId) cancel() }
        }
    }

    fun cancel() {
        val remote = service
        if (remote == null) {
            request = null
            finish(requestId) { it.onCancelled() }
        } else runCatching { remote.cancel(requestId) }
            .onFailure { finish(requestId) { it.onError(VisionFailure.PROCESS_DIED) } }
    }

    override fun close() {
        events = null
        request = null
        service = null
        if (bound) {
            app.unbindService(connection)
            bound = false
        }
    }

    internal fun dispatch(id: Long, block: (VisionEvents) -> Unit) {
        main.post {
            if (id == requestId) events?.let(block)
        }
    }

    internal fun finish(id: Long, block: (VisionEvents) -> Unit) {
        main.post {
            if (id != requestId) return@post
            val completed = events
            close()
            completed?.let(block)
        }
    }

    private companion object {
        val NEXT_REQUEST = AtomicLong()
    }
}
