package com.mrj.fancyai.service.llm

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import android.os.Parcel
import android.os.SharedMemory
import android.system.OsConstants
import com.mrj.fancyai.util.AppLog
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.channels.SendChannel
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.channels.trySendBlocking
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.lang.ref.WeakReference
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlin.time.Duration.Companion.milliseconds

/** Main-process session facade. Every local-model screen uses this same IPC path. */
internal class LlmEngineClient(context: Context) {
    private val appContext = context.applicationContext
    private val pending = ConcurrentHashMap<Long, PendingOperation>()
    private val activeGeneration = AtomicLong(0)
    private val closed = AtomicBoolean(false)
    private val cloudRuntime = CloudLlmRuntime()
    private val connectionLock = Any()
    private val configLock = Any()

    @Volatile private var remote: ILlmEngineService? = null
    @Volatile private var rawBinder: IBinder? = null
    @Volatile private var bound = false
    @Volatile private var bindDeferred: CompletableDeferred<ILlmEngineService>? = null
    @Volatile private var currentConfig: LlmSessionConfig? = null
    @Volatile private var shutdownReady: CompletableDeferred<Unit>? = null
    @Volatile private var memoryEvicted = false

    private val deathRecipient = IBinder.DeathRecipient {
        if (rawBinder?.isBinderAlive == false) onBinderDeath()
    }

    private val callback = object : ILlmEngineCallback.Stub() {
        override fun onReady(requestId: Long) {
            (pending.remove(requestId) as? OpenOperation)?.let { operation ->
                latestLocalOwner = WeakReference(this@LlmEngineClient)
                memoryEvicted = false
                operation.result.complete(Unit)
            }
        }

        override fun onChunk(chunk: LlmChunk?) {
            chunk ?: return
            (pending[chunk.requestId] as? GenerationOperation)?.let { generation ->
                latestLocalOwner = WeakReference(this@LlmEngineClient)
                // Binder delivers these callbacks in order on a worker thread. Wait for
                // the consumer instead of dropping text when its bounded buffer fills.
                generation.output.trySendBlocking(chunk)
            }
        }

        override fun onTerminal(terminal: LlmTerminal?) {
            terminal ?: return
            when (val operation = pending.remove(terminal.requestId)) {
                is OpenOperation -> {
                    operation.result.completeExceptionally(terminal.asException())
                }
                is GenerationOperation -> {
                    activeGeneration.compareAndSet(terminal.requestId, 0)
                    operation.completion?.complete(terminal)
                    when (terminal.reason) {
                        LlmTerminalReason.COMPLETED -> {
                            latestLocalOwner = WeakReference(this@LlmEngineClient)
                            operation.output.close()
                        }
                        LlmTerminalReason.CANCELLED ->
                            operation.output.close(CancellationException("Generation cancelled"))
                        LlmTerminalReason.FAILED -> {
                            operation.output.close(
                                terminal.asException(),
                            )
                        }
                    }
                }
                null -> Unit
            }
        }

        override fun onEvicted(terminal: LlmTerminal?) {
            terminal ?: return
            memoryEvicted = terminal.error == LlmError.MEMORY_PRESSURE
            synchronized(connectionLock) {
                // Eviction starts native cleanup; only Binder death finishes it.
                if ((rawBinder != null) && (shutdownReady == null)) {
                    shutdownReady = CompletableDeferred()
                }
            }
            failPending(terminal.asException())
        }

        override fun onShutdownReady(requestId: Long) = Unit
    }

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            if (service == null) {
                failBinding(LlmEngineException(LlmError.PROCESS_DIED, "Engine returned no binder"))
                return
            }
            if (closed.get()) {
                failBinding(CancellationException("Engine client is closed"))
                return
            }
            val typed = ILlmEngineService.Stub.asInterface(service)
            runCatching { service.linkToDeath(deathRecipient, 0) }
                .onFailure {
                    failBinding(
                        LlmEngineException(
                            LlmError.PROCESS_DIED,
                            "Engine process died during bind",
                            it,
                        ),
                    )
                    return
                }
            if (closed.get()) {
                runCatching { service.unlinkToDeath(deathRecipient, 0) }
                failBinding(CancellationException("Engine client is closed"))
                return
            }
            synchronized(connectionLock) {
                rawBinder = service
                activeBinder = service
                remote = typed
                bindDeferred?.complete(typed)
                bindDeferred = null
            }
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            if (rawBinder?.isBinderAlive == false) onBinderDeath()
        }

        override fun onBindingDied(name: ComponentName?) = onBinderDeath()

        override fun onNullBinding(name: ComponentName?) {
            failBinding(LlmEngineException(LlmError.PROCESS_DIED, "Engine returned a null binding"))
        }
    }

    /** Optional model preload; each generation still supplies its own complete request. */
    suspend fun prepare(config: LlmSessionConfig) {
        val packed = packPolicy(LlmRequest(config, LlmInput(""))).config
        if (packed.runtime == LlmRuntime.CLOUD) openSession(packed)
        else sessionMutex.withLock { openSession(packed) }
    }

    /** Runtime-to-policy dispatch lives here; each policy owns its own budgets. */
    private fun packPolicy(request: LlmRequest): LlmRequest = when (request.config.runtime) {
        LlmRuntime.LLAMA, LlmRuntime.LITERT, LlmRuntime.MNN -> request
        LlmRuntime.CLOUD -> CloudContextPolicy.pack(request)
    }

    private suspend fun openSession(config: LlmSessionConfig, preload: Boolean = true) {
        check(!closed.get()) { "Engine client is closed" }
        memoryEvicted = false
        if (config.runtime == LlmRuntime.CLOUD) {
            latestLocalOwner.takeIf { it.get() === this }?.clear()
            cloudRuntime.cancel()
            failPending(CancellationException("Local engine released for cloud inference"))
            shutdownRemote()
            synchronized(configLock) { currentConfig = config }
        } else {
            cloudRuntime.cancel()
            synchronized(configLock) { currentConfig = config }
            if (preload) openRemote(config)
        }
    }

    /** Preparation, dispatch, and terminal cleanup belong to this request alone. */
    fun generate(request: LlmRequest): Flow<LlmChunk> = flow {
        val packed = packPolicy(request)
        if (packed.config.runtime == LlmRuntime.CLOUD) {
            openSession(packed.config, preload = false)
            startCloudGeneration(packed.config, packed.input, packed.thinking).collect(::emit)
        } else sessionMutex.withLock {
            openSession(packed.config, preload = false)
            startLocalGeneration(packed.config, packed.input, packed.thinking).collect(::emit)
        }
    }

    fun cancel() {
        cloudRuntime.cancel()
        val active = activeGeneration.get()
        if (active > 0) runCatching { remote?.cancel(active) }
        pending.keys.forEach { runCatching { remote?.cancel(it) } }
        runCatching { remote?.cancel(0L) }
    }

    suspend fun unload() {
        check(!closed.get()) { "Engine client is closed" }
        connect()
        shutdownRemote()
    }

    suspend fun generateMeasured(
        request: LlmRequest,
        onChunk: (LlmChunk) -> Unit = {},
    ): LlmPerformanceMetrics? = sessionMutex.withLock {
        val packed = packPolicy(request)
        require((packed.config.runtime != LlmRuntime.CLOUD) && packed.config.benchmarking) {
            "Measured generation requires a local benchmark session"
        }
        openSession(packed.config, preload = false)
        val completion = CompletableDeferred<LlmTerminal>()
        startLocalGeneration(packed.config, packed.input, packed.thinking, completion).collect(onChunk)
        completion.await().performance
    }

    internal suspend fun closeAndAwait() {
        withContext(NonCancellable) { shutdownRemote() }
        close()
    }

    fun close() {
        if (!closed.compareAndSet(false, true)) return
        latestLocalOwner.takeIf { it.get() === this }?.clear()
        cloudRuntime.cancel()
        pending.keys.forEach { requestId -> runCatching { remote?.cancel(requestId) } }
        failPending(CancellationException("Engine session closed"))
        disconnect()
    }

    // Local Aura generation holds sessionMutex until cleanup and restoration finish.
    internal suspend fun restoreAfterImage(config: LlmSessionConfig) {
        if (closed.get() || (!memoryEvicted && remote != null)) return
        shutdownRemote()
        openRemote(config)
    }

    private suspend fun shutdownRemote() {
        latestLocalOwner.takeIf { it.get() === this }?.clear()
        memoryEvicted = false
        val service = remote
        if (service == null) {
            disconnect()
            return
        }
        val requestId = requestIds.getAndIncrement()
        val ready = synchronized(connectionLock) {
            shutdownReady ?: CompletableDeferred<Unit>().also { shutdownReady = it }
        }
        runCatching { service.shutdown(requestId, callback) }
            .onFailure { ready.complete(Unit) }
        runCatching { withTimeout(SHUTDOWN_TIMEOUT_MS.milliseconds) { ready.await() } }
        synchronized(connectionLock) {
            if (shutdownReady === ready) shutdownReady = null
        }
        disconnect()
    }

    private suspend fun openRemote(config: LlmSessionConfig) {
        val service = connect()
        val requestId = requestIds.getAndIncrement()
        val operation = OpenOperation(CompletableDeferred())
        check(pending.putIfAbsent(requestId, operation) == null)
        runCatching {
            val parcel = Parcel.obtain()
            val bytes = try {
                config.writeToParcel(parcel, 0)
                parcel.marshall()
            } finally {
                parcel.recycle()
            }
            SharedMemory.create("llm-request", bytes.size).use { payload ->
                val buffer = payload.mapReadWrite()
                try {
                    buffer.put(bytes)
                } finally {
                    SharedMemory.unmap(buffer)
                }
                payload.setProtect(OsConstants.PROT_READ)
                service.open(requestId, payload, callback)
            }
        }
            .onFailure {
                pending.remove(requestId)
                onBinderDeath()
                operation.result.completeExceptionally(
                    LlmEngineException(
                        LlmError.PROCESS_DIED,
                        "Could not open the engine session",
                        it,
                    ),
                )
            }
        try {
            withTimeout(OPEN_TIMEOUT_MS.milliseconds) { operation.result.await() }
        } catch (timeout: TimeoutCancellationException) {
            pending.remove(requestId, operation)
            runCatching { service.shutdown(requestIds.getAndIncrement(), callback) }
            disconnect()
            throw LlmEngineException(
                LlmError.MODEL_LOAD,
                "Local model loading timed out",
                timeout,
            )
        } catch (cancelled: CancellationException) {
            runCatching { service.cancel(requestId) }
            withContext(NonCancellable) {
                runCatching {
                    withTimeout(OPEN_CANCEL_TIMEOUT_MS.milliseconds) { operation.result.await() }
                }
            }
            throw cancelled
        } finally {
            pending.remove(requestId, operation)
        }
    }

    private fun startLocalGeneration(
        config: LlmSessionConfig,
        input: LlmInput,
        thinking: Boolean,
        completion: CompletableDeferred<LlmTerminal>? = null,
    ): Flow<LlmChunk> = callbackFlow {
        val service = connect()
        val requestId = requestIds.getAndIncrement()
        val finished = completion ?: CompletableDeferred()
        val operation = GenerationOperation(
            output = channel,
            completion = finished,
        )
        check(activeGeneration.compareAndSet(0, requestId)) { "A generation is already active" }
        check(pending.putIfAbsent(requestId, operation) == null)
        runCatching {
            val parcel = Parcel.obtain()
            val bytes = try {
                config.writeToParcel(parcel, 0)
                input.writeToParcel(parcel, 0)
                parcel.marshall()
            } finally {
                parcel.recycle()
            }
            SharedMemory.create("llm-request", bytes.size).use { payload ->
                val buffer = payload.mapReadWrite()
                try {
                    buffer.put(bytes)
                } finally {
                    SharedMemory.unmap(buffer)
                }
                payload.setProtect(OsConstants.PROT_READ)
                service.generate(requestId, payload, thinking, callback)
            }
        }
            .onFailure {
                finished.completeExceptionally(it)
                pending.remove(requestId)
                activeGeneration.compareAndSet(requestId, 0)
                onBinderDeath()
                close(
                    LlmEngineException(
                        LlmError.PROCESS_DIED,
                        "Could not reach the engine process",
                        it,
                    ),
                )
            }
        try {
            awaitClose {
                if (pending[requestId] === operation) runCatching { remote?.cancel(requestId) }
            }
        } finally {
            // Native cancellation must finish before another session can be opened.
            withContext(NonCancellable) {
                runCatching {
                    withTimeout(GENERATION_CANCEL_TIMEOUT_MS.milliseconds) { finished.await() }
                }.onFailure { failure ->
                    if (failure is TimeoutCancellationException) shutdownRemote()
                }
            }
            pending.remove(requestId, operation)
            activeGeneration.compareAndSet(requestId, 0)
        }
    }

    private fun startCloudGeneration(
        config: LlmSessionConfig,
        input: LlmInput,
        thinking: Boolean,
    ): Flow<LlmChunk> = flow {
        val requestId = requestIds.getAndIncrement()
        check(activeGeneration.compareAndSet(0, requestId)) { "A generation is already active" }
        try {
            cloudRuntime.stream(config, input, thinking, appContext.filesDir).collect { delta ->
                emit(
                    LlmChunk(
                        requestId = requestId,
                        text = delta.text,
                        channels = delta.reasoning.takeIf(String::isNotEmpty)?.let { mapOf("reasoning" to it) }.orEmpty(),
                    ),
                )
            }
        } finally {
            activeGeneration.compareAndSet(requestId, 0)
        }
    }

    private suspend fun connect(): ILlmEngineService {
        check(!closed.get()) { "Engine client is closed" }
        val retiring = retiringBinderDeath ?: synchronized(connectionLock) { shutdownReady }
        if (retiring != null && !retiring.isCompleted) {
            runCatching {
                withTimeout(SHUTDOWN_TIMEOUT_MS.milliseconds) { retiring.await() }
            }
            if (retiringBinderDeath === retiring) retiringBinderDeath = null
            synchronized(connectionLock) {
                if (shutdownReady === retiring) shutdownReady = null
            }
            check(!closed.get()) { "Engine client is closed" }
        }
        remote?.let { return it }
        val (waiting, shouldBind) = synchronized(connectionLock) {
            remote?.let { return it }
            val existing = bindDeferred
            if (existing != null) {
                existing to false
            } else {
                CompletableDeferred<ILlmEngineService>().also { bindDeferred = it } to !bound
            }
        }
        if (shouldBind) {
            val intent = Intent(appContext, LlmEngineService::class.java)
            val started = runCatching { appContext.startService(intent) != null }
                .getOrDefault(defaultValue = false)
            if (!started) {
                val failure = LlmEngineException(
                    LlmError.PROCESS_DIED,
                    "Could not start the engine process",
                )
                failBinding(failure)
                throw failure
            }
            val didBind = runCatching {
                appContext.bindService(
                    intent,
                    connection,
                    Context.BIND_AUTO_CREATE,
                )
            }.getOrDefault(defaultValue = false)
            synchronized(connectionLock) { bound = didBind }
            if (!didBind) {
                appContext.stopService(intent)
                failBinding(
                    LlmEngineException(
                        LlmError.PROCESS_DIED,
                        "Could not bind the engine process",
                    ),
                )
            }
        }
        return try {
            withTimeout(BIND_TIMEOUT_MS.milliseconds) { waiting.await() }
        } catch (timeout: TimeoutCancellationException) {
            val failure = LlmEngineException(
                LlmError.PROCESS_DIED,
                "Binding the engine process timed out",
                timeout,
            )
            failBinding(failure)
            throw failure
        }
    }

    internal fun onBinderDeath() {
        AppLog.write(android.util.Log.WARN, "LLMClient", "Engine process disconnected pending=${pending.size} shutdownExpected=${shutdownReady != null}")
        val failure = LlmEngineException(
            LlmError.PROCESS_DIED,
            "The engine process stopped unexpectedly",
        )
        val ready = synchronized(connectionLock) {
            rawBinder?.let { binder -> runCatching { binder.unlinkToDeath(deathRecipient, 0) } }
            if (activeBinder === rawBinder) activeBinder = null
            rawBinder = null
            remote = null
            bindDeferred?.completeExceptionally(failure)
            bindDeferred = null
            if (bound) runCatching { appContext.unbindService(connection) }
            bound = false
            shutdownReady
        }
        retiringBinderDeath?.complete(Unit)
        retiringBinderDeath = null
        failPending(failure)
        ready?.complete(Unit)
    }

    internal fun failBinding(failure: Throwable) {
        val (shouldUnbind, binder) = synchronized(connectionLock) {
            bindDeferred?.completeExceptionally(failure)
            bindDeferred = null
            val wasBound = bound
            val b = rawBinder
            bound = false
            rawBinder = null
            remote = null
            wasBound to b
        }
        if (binder != null && binder === activeBinder) activeBinder = null
        if (shouldUnbind) runCatching { appContext.unbindService(connection) }
    }

    private fun disconnect() {
        val retiring = synchronized(connectionLock) {
            val binder = rawBinder
            if (bound) runCatching { appContext.unbindService(connection) }
            bound = false
            binder?.let { b -> runCatching { b.unlinkToDeath(deathRecipient, 0) } }
            rawBinder = null
            remote = null
            bindDeferred?.cancel()
            bindDeferred = null
            binder
        }
        if (retiring != null && retiring === activeBinder) {
            val death = CompletableDeferred<Unit>()
            retiringBinderDeath = death
            runCatching {
                retiring.linkToDeath({
                    death.complete(Unit)
                    if (retiringBinderDeath === death) retiringBinderDeath = null
                    if (activeBinder === retiring) activeBinder = null
                }, 0)
            }.onFailure {
                death.complete(Unit)
                if (retiringBinderDeath === death) retiringBinderDeath = null
                if (activeBinder === retiring) activeBinder = null
            }
        }
    }

    internal fun failPending(failure: Throwable) {
        pending.keys.toList().forEach { requestId ->
            when (val operation = pending.remove(requestId)) {
                is OpenOperation -> operation.result.completeExceptionally(failure)
                is GenerationOperation -> {
                    operation.completion?.completeExceptionally(failure)
                    operation.output.close(failure)
                }
                null -> Unit
            }
        }
        activeGeneration.set(0)
    }

    private sealed interface PendingOperation

    private class OpenOperation(val result: CompletableDeferred<Unit>) : PendingOperation

    internal class GenerationOperation(
        val output: SendChannel<LlmChunk>,
        val completion: CompletableDeferred<LlmTerminal>? = null,
    ) : PendingOperation

    companion object {
        internal val sessionMutex = Mutex()
        private val requestIds = AtomicLong(1)
        @Volatile private var latestLocalOwner = WeakReference<LlmEngineClient>(null)
        @Volatile private var activeBinder: IBinder? = null
        @Volatile private var retiringBinderDeath: CompletableDeferred<Unit>? = null

        internal fun captureForImage(): Pair<LlmEngineClient, LlmSessionConfig>? {
            val client = latestLocalOwner.get() ?: return null
            return synchronized(client.configLock) {
                val config = client.currentConfig ?: return@synchronized null
                if (client.closed.get() || client.memoryEvicted || (config.runtime == LlmRuntime.CLOUD)) null else client to config
            }
        }

        private const val BIND_TIMEOUT_MS = 10_000L
        private const val OPEN_TIMEOUT_MS = 300_000L
        private const val OPEN_CANCEL_TIMEOUT_MS = 2_000L
        private const val GENERATION_CANCEL_TIMEOUT_MS = 10_000L
        private const val SHUTDOWN_TIMEOUT_MS = 3_000L
    }
}
