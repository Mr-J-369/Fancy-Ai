package com.mrj.fancyai.service.voice

import android.Manifest
import android.app.Service
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.IBinder
import androidx.annotation.RequiresPermission
import androidx.core.content.ContextCompat
import com.mrj.fancyai.service.llm.CloudProvider
import com.mrj.fancyai.ui.settings.VoiceSettingsStore
import com.mrj.fancyai.util.AppLog
import com.mrj.fancyai.voice.AndroidSttEngine
import com.mrj.fancyai.voice.AndroidTtsEngine
import com.mrj.fancyai.voice.SttEngine
import com.mrj.fancyai.voice.TtsEngine
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.CancellationException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.cancel as cancelScope

/** Owns Android, cloud and local speech engines inside the private voice process. */
class VoiceService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var sttJob: Job? = null
    private var ttsJob: Job? = null
    private var sttEngine: SttEngine? = null
    private var ttsEngine: TtsEngine? = null
    private var sttRequestId = 0L
    private var ttsRequestId = 0L
    private var localStt: Pair<String, LocalSttEngine>? = null
    private var localTts: Pair<String, LocalTtsEngine>? = null

    private val binder = object : IVoiceService.Stub() {
        override fun listen(
            requestId: Long,
            provider: String?,
            apiKey: String?,
            model: String?,
            callback: IVoiceCallback?,
        ) {
            callback ?: return
            scope.launch {
                when {
                    requestId <= 0L -> callback.sendError(
                        requestId,
                        IllegalArgumentException("Invalid voice request"),
                    )
                    ContextCompat.checkSelfPermission(
                        this@VoiceService,
                        Manifest.permission.RECORD_AUDIO,
                    ) != PackageManager.PERMISSION_GRANTED -> callback.sendError(
                        requestId,
                        IllegalStateException("Microphone permission is not granted"),
                    )
                    sttJob?.isActive == true -> callback.sendError(
                        requestId,
                        IllegalStateException("Voice input is already active"),
                    )
                    else -> startListening(
                        requestId,
                        provider.orEmpty(),
                        apiKey.orEmpty(),
                        model.orEmpty(),
                        callback,
                    )
                }
            }
        }

        override fun speak(
            requestId: Long,
            provider: String?,
            apiKey: String?,
            model: String?,
            voice: String?,
            text: String?,
            callback: IVoiceCallback?,
        ) {
            callback ?: return
            scope.launch {
                when {
                    requestId <= 0L -> callback.sendError(
                        requestId,
                        IllegalArgumentException("Invalid voice request"),
                    )
                    ttsJob?.isActive == true -> callback.sendError(
                        requestId,
                        IllegalStateException("Voice playback is already active"),
                    )
                    else -> startSpeaking(
                        requestId,
                        provider.orEmpty(),
                        apiKey.orEmpty(),
                        model.orEmpty(),
                        voice.orEmpty(),
                        text.orEmpty(),
                        callback,
                    )
                }
            }
        }

        override fun cancel(requestId: Long) {
            scope.launch {
                if (requestId == sttRequestId) {
                    sttEngine?.cancel()
                    sttJob?.cancel(CancellationException("Voice input cancelled"))
                }
                if (requestId == ttsRequestId) {
                    ttsEngine?.stop()
                    ttsJob?.cancel(CancellationException("Voice playback stopped"))
                }
            }
        }

        override fun finishSample(requestId: Long) {
            scope.launch {
                if (requestId == sttRequestId) (sttEngine as? VoiceSampleEngine)?.finish()
            }
        }
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onUnbind(intent: Intent?): Boolean {
        releaseEngines()
        android.os.Process.killProcess(android.os.Process.myPid())
        return false
    }

    override fun onDestroy() {
        releaseEngines()
        scope.cancelScope()
        super.onDestroy()
    }

    private fun releaseEngines() {
        sttEngine?.cancel()
        ttsEngine?.stop()
        sttJob?.cancel(CancellationException("Voice service released"))
        ttsJob?.cancel(CancellationException("Voice service released"))
        sttEngine?.release()
        sttEngine = null
        ttsEngine?.release()
        ttsEngine = null
        localStt?.second?.release()
        localStt = null
        localTts?.second?.release()
        localTts = null
    }

    @RequiresPermission(Manifest.permission.RECORD_AUDIO)
    internal fun startListening(
        requestId: Long,
        provider: String,
        apiKey: String,
        model: String,
        callback: IVoiceCallback,
    ) {
        lateinit var operation: Job
        operation = scope.launch(start = CoroutineStart.LAZY) {
            var engine: SttEngine? = null
            AppLog.write(android.util.Log.INFO, "Voice", "STT started request=$requestId")
            try {
                engine = when (provider) {
                    "LOCAL" -> localStt?.takeIf { it.first == model }?.second ?: run {
                        localStt?.second?.release()
                        LocalSttEngine(this@VoiceService, model).also { localStt = model to it }
                    }
                    "SAMPLE" -> VoiceSampleEngine(this@VoiceService)
                    ANDROID_PROVIDER -> AndroidSttEngine(this@VoiceService)
                    else -> CloudSttEngine(cloudProvider(provider), apiKey, model)
                }
                sttEngine = engine
                val result = coroutineScope {
                    val partials = launch {
                        engine.partial.drop(1).collect { runCatching { callback.onPartial(requestId, it) } }
                    }
                    try {
                        engine.listen()
                    } finally {
                        partials.cancel()
                    }
                }
                AppLog.write(android.util.Log.INFO, "Voice", "STT completed request=$requestId")
                runCatching { callback.onComplete(requestId, result) }
            } catch (_: CancellationException) {
                AppLog.write(android.util.Log.INFO, "Voice", "STT cancelled request=$requestId")
                runCatching { callback.onCancelled(requestId) }
            } catch (failure: Throwable) {
                AppLog.write(android.util.Log.ERROR, "Voice", "STT failed request=$requestId", failure)
                callback.sendError(requestId, failure)
            } finally {
                if (engine !is LocalSttEngine) engine?.release()
                if (sttJob === operation) {
                    sttEngine = null
                    sttJob = null
                    sttRequestId = 0L
                }
            }
        }
        sttRequestId = requestId
        sttJob = operation
        operation.start()
    }

    internal fun startSpeaking(
        requestId: Long,
        provider: String,
        apiKey: String,
        model: String,
        voice: String,
        text: String,
        callback: IVoiceCallback,
    ) {
        lateinit var operation: Job
        operation = scope.launch(start = CoroutineStart.LAZY) {
            var engine: TtsEngine? = null
            AppLog.write(android.util.Log.INFO, "Voice", "TTS started request=$requestId")
            try {
                engine = when (provider) {
                    "LOCAL" -> {
                        val key = "$model@$voice"
                        localTts?.takeIf { it.first == key }?.second ?: run {
                            localTts?.second?.release()
                            LocalTtsEngine(this@VoiceService, model, voice).also { localTts = key to it }
                        }
                    }
                    ANDROID_PROVIDER -> AndroidTtsEngine.create(this@VoiceService)
                    else -> CloudTtsEngine(this@VoiceService, cloudProvider(provider), apiKey, model, voice)
                }
                ttsEngine = engine
                engine.speak(text)
                AppLog.write(android.util.Log.INFO, "Voice", "TTS completed request=$requestId")
                runCatching { callback.onComplete(requestId, "") }
            } catch (_: CancellationException) {
                AppLog.write(android.util.Log.INFO, "Voice", "TTS cancelled request=$requestId")
                runCatching { callback.onCancelled(requestId) }
            } catch (failure: Throwable) {
                AppLog.write(android.util.Log.ERROR, "Voice", "TTS failed request=$requestId", failure)
                callback.sendError(requestId, failure)
            } finally {
                if (engine !is LocalTtsEngine) engine?.release()
                if (ttsJob === operation) {
                    ttsEngine = null
                    ttsJob = null
                    ttsRequestId = 0L
                }
            }
        }
        ttsRequestId = requestId
        ttsJob = operation
        operation.start()
    }

    private fun cloudProvider(provider: String): CloudProvider = when (provider) {
        CloudProvider.DEEPINFRA.name -> CloudProvider.DEEPINFRA
        CloudProvider.OPENROUTER.name -> CloudProvider.OPENROUTER
        else -> error("Unsupported voice provider")
    }

    private companion object {
        const val ANDROID_PROVIDER = "ANDROID"
    }
}

internal class VoiceSttEngine(
    context: Context,
    private val provider: String,
    private val apiKey: String,
    private val model: String,
) : SttEngine {
    private val lock = Mutex()
    private val mutablePartial = MutableStateFlow("")
    private val operation = VoiceOperation(String::toString) { mutablePartial.value = it }
    private val connection = VoiceConnection(context, operation::fail)
    private val released = AtomicBoolean(false)

    override val partial: StateFlow<String> = mutablePartial

    @RequiresPermission(Manifest.permission.RECORD_AUDIO)
    override suspend fun listen(): String = lock.withLock {
        check(!released.get()) { "Voice input engine is released" }
        mutablePartial.value = ""
        val remote = connection.connect()
        operation.await(remote) { requestId, callback ->
            remote.listen(requestId, provider, apiKey, model, callback)
        }
    }

    override fun cancel() = operation.cancel(connection::cancel)

    fun finishSample() = operation.finish(connection::finishSample)

    override fun release() {
        if (!released.compareAndSet(false, true)) return
        cancel()
        connection.close()
    }
}

internal class VoiceTtsEngine(
    private val context: Context,
    private val provider: String,
    private val apiKey: String,
    private val model: String,
    private val voice: String,
) : TtsEngine {
    private val lock = Mutex()
    private val operation = VoiceOperation(result = {})
    private val connection = VoiceConnection(context, operation::fail)
    private val released = AtomicBoolean(false)

    private val playback = AtomicReference<Job?>()

    override suspend fun speak(text: String) = speak(flowOf(text), Int.MAX_VALUE)

    suspend fun speak(
        chunks: Flow<String>,
        minimumCharacters: Int = VoiceSettingsStore.playbackCharacters(context),
        onSpeaking: () -> Unit = {},
    ) = lock.withLock {
        coroutineScope {
            check(!released.get()) { "Voice playback engine is released" }
            // Carry the current language over Binder; the voice service has its own process.
            val configuredVoice = if (provider == "LOCAL" && model == LocalVoicePack.SUPERTONIC.id) {
                val language = VoiceSettingsStore.supertonicLanguage(context)
                require(language.isNotBlank()) { "Choose a Supertonic language in Voice settings" }
                "$voice@$language"
            } else voice
            val job = currentCoroutineContext()[Job]!!
            playback.set(job)
            try {
                spokenPhrases(chunks, minimumCharacters, VoiceSettingsStore.skipActions(context))
                    .buffer(1).collect { spoken ->
                        currentCoroutineContext().ensureActive()
                        onSpeaking()
                        val remote = connection.connect()
                        operation.await(remote) { requestId, callback ->
                            remote.speak(requestId, provider, apiKey, model, configuredVoice, spoken, callback)
                        }
                    }
            } finally {
                playback.compareAndSet(job, null)
            }
        }
    }

    override fun stop() {
        playback.get()?.cancel(CancellationException("Voice playback stopped"))
        operation.cancel(connection::cancel)
    }

    override fun release() {
        if (!released.compareAndSet(false, true)) return
        stop()
        connection.close()
    }
}

/** Keeps action delimiters across LLM tokens; only audible text counts toward startup. */
internal fun spokenPhrases(chunks: Flow<String>, minimumCharacters: Int, skipActions: Boolean): Flow<String> = flow {
    val pending = StringBuilder()
    val actions = SpeechActionFilter(pending)
    chunks.collect { chunk ->
        for (character in chunk) {
            if (!skipActions) pending.append(character)
            else actions.append(character)
            val boundary = pending.lastOrNull()?.let { last ->
                last in "。！？；，：\n" ||
                    (last.isWhitespace() && (pending.trimEnd().lastOrNull()?.let { it in ".!?,;:" } == true))
            } == true
            if (pending.length >= minimumCharacters && boundary) {
                val phrase = pending.toString().trim()
                pending.clear()
                if (phrase.isNotEmpty()) emit(phrase)
            }
        }
    }
    if (skipActions) actions.append(null)
    val remainder = pending.toString().trim()
    if (remainder.isNotEmpty()) emit(remainder)
}

private class SpeechActionFilter(private val pending: StringBuilder) {
    private var action = false
    private var stars = 0
    private var escaped = false

    fun append(character: Char?) {
        if (escaped) {
            if (!action) pending.append(if (character == '*') "*" else "\\${character?.toString().orEmpty()}")
            escaped = false
        } else if (character == '*') {
            stars++
        } else {
            when {
                stars == 1 && action -> {
                    action = false
                    if (pending.lastOrNull()?.isWhitespace() == false) pending.append(' ')
                }
                stars == 1 && character?.isWhitespace() == false -> action = true
                !action -> repeat(stars) { pending.append('*') }
            }
            stars = 0
            if (character == '\\') escaped = true
            else if (!action && character != null) pending.append(character)
        }
    }
}

private class VoiceOperation<T>(
    private val result: (String) -> T,
    private val partial: (String) -> Unit = {},
) : IVoiceCallback.Stub() {
    private val active = AtomicReference<Request<T>?>(null)

    suspend fun await(
        remote: IVoiceService,
        start: (Long, IVoiceCallback) -> Unit,
    ): T = suspendCancellableCoroutine { continuation ->
        val request = Request(voiceRequestIds.getAndIncrement(), continuation)
        check(active.compareAndSet(null, request)) { "Voice operation is already active" }
        continuation.invokeOnCancellation {
            if (active.compareAndSet(request, null)) runCatching { remote.cancel(request.id) }
        }
        runCatching { start(request.id, this) }.onFailure { fail(request.id, it) }
    }

    fun cancel(cancelRemote: (Long) -> Unit) {
        active.get()?.let { request ->
            if (active.compareAndSet(request, null)) {
                cancelRemote(request.id)
                request.continuation.cancel(CancellationException("Voice operation cancelled"))
            }
        }
    }

    fun finish(finishRemote: (Long) -> Unit) {
        active.get()?.let { finishRemote(it.id) }
    }

    fun fail(failure: Throwable) {
        active.get()?.let { fail(it.id, failure) }
    }

    override fun onPartial(requestId: Long, text: String?) {
        if (active.get()?.id == requestId) partial(text.orEmpty())
    }

    override fun onComplete(requestId: Long, text: String?) {
        val request = take(requestId) ?: return
        runCatching { result(text.orEmpty()) }
            .onSuccess { request.continuation.complete(it) }
            .onFailure { request.continuation.fail(it) }
    }

    override fun onError(
        requestId: Long,
        androidError: Int,
        cloudFailure: String?,
        detail: String?,
    ) {
        fail(requestId, voiceFailure(androidError, cloudFailure, detail))
    }

    override fun onCancelled(requestId: Long) {
        take(requestId)?.continuation?.cancel(CancellationException("Voice operation cancelled"))
    }

    private fun fail(requestId: Long, failure: Throwable) {
        take(requestId)?.continuation?.fail(failure)
    }

    private fun take(requestId: Long): Request<T>? = active.get()
        ?.takeIf { it.id == requestId }
        ?.takeIf { active.compareAndSet(it, null) }

    private data class Request<T>(
        val id: Long,
        val continuation: CancellableContinuation<T>,
    )
}

private class VoiceConnection(
    context: Context,
    private val onLost: (Throwable) -> Unit,
) : ServiceConnection {
    private val appContext = context.applicationContext
    @Volatile private var remote: IVoiceService? = null
    private var pending: CompletableDeferred<IVoiceService>? = null
    private var bound = false
    private var closed = false

    override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
        if (closed) {
            if (bound) runCatching { appContext.unbindService(this) }
            bound = false
            return
        }
        val connected = service?.let { IVoiceService.Stub.asInterface(it) }
        if (connected == null) {
            bindingDied(IllegalStateException("Voice service returned no binder"))
            return
        }
        remote = connected
        pending?.complete(connected)
        pending = null
    }

    override fun onServiceDisconnected(name: ComponentName?) {
        remote = null
        pending?.completeExceptionally(IllegalStateException("Voice process disconnected"))
        pending = null
        onLost(IllegalStateException("Voice process disconnected"))
    }

    override fun onBindingDied(name: ComponentName?) =
        bindingDied(IllegalStateException("Voice service binding died"))

    override fun onNullBinding(name: ComponentName?) =
        bindingDied(IllegalStateException("Voice service returned a null binding"))

    suspend fun connect(): IVoiceService {
        check(!closed) { "Voice engine is released" }
        remote?.let { return it }
        val waiting = pending ?: CompletableDeferred<IVoiceService>().also { pending = it }
        if (!bound) {
            bound = runCatching {
                appContext.bindService(
                    Intent(appContext, VoiceService::class.java),
                    this,
                    Context.BIND_AUTO_CREATE,
                )
            }.getOrDefault(defaultValue = false)
            if (!bound) {
                pending = null
                waiting.completeExceptionally(IllegalStateException("Unable to bind voice service"))
            }
        }
        return waiting.await()
    }

    fun cancel(requestId: Long) { runCatching { remote?.cancel(requestId) } }

    fun finishSample(requestId: Long) { runCatching { remote?.finishSample(requestId) } }

    fun close() {
        if (closed) return
        closed = true
        remote = null
        pending?.completeExceptionally(CancellationException("Voice engine released"))
        pending = null
        if (bound) runCatching { appContext.unbindService(this) }
        bound = false
    }

    private fun bindingDied(failure: Throwable) {
        remote = null
        pending?.completeExceptionally(failure)
        pending = null
        if (bound) runCatching { appContext.unbindService(this) }
        bound = false
        onLost(failure)
    }
}

private val voiceRequestIds = AtomicLong(1)
