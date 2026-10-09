package com.mrj.fancyai.service.llm

import android.app.ActivityManager
import android.app.Service
import android.content.Intent
import android.os.Debug
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Parcel
import android.os.Process
import android.os.SharedMemory
import android.os.SystemClock
import com.mrj.fancyai.engine.LiteRtRuntime
import com.mrj.fancyai.engine.LlamaRuntime
import com.mrj.fancyai.util.AppLog
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.parcelize.parcelableCreator
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import kotlin.time.Duration.Companion.milliseconds

/** Owns every local native inference object inside the private `:engine` crash boundary. */
class LlmEngineService : Service() {
    private val serviceJob = SupervisorJob()
    private val scope = CoroutineScope(serviceJob + Dispatchers.IO)
    internal val operation = AtomicReference<Operation?>()
    internal val shuttingDown = AtomicBoolean(false)
    internal val cleanupStarted = AtomicBoolean(false)
    internal val benchmarkPeakPssKilobytes = AtomicLong()
    internal val benchmarkMinimumAvailableMemoryBytes = AtomicLong()
    private val mainHandler = Handler(Looper.getMainLooper())

    @Volatile internal var ownerCallback: ILlmEngineCallback? = null
    @Volatile internal var liteRtRuntime: LiteRtRuntime? = null
    @Volatile internal var llamaRuntime: LlamaRuntime? = null
    @Volatile internal var engineSignature: EngineSignature? = null
    @Volatile internal var benchmarkSampling = false
    @Volatile internal var benchmarkSampleOnNextTick = true

    private val binder = object : ILlmEngineService.Stub() {
        override fun open(
            requestId: Long,
            request: SharedMemory,
            clientCallback: ILlmEngineCallback?,
        ) {
            val config: LlmSessionConfig
            try {
                config = decodeRequest(request) { parcelableCreator<LlmSessionConfig>().createFromParcel(it) }
            } catch (failure: Throwable) {
                clientCallback?.let {
                    terminal(it, requestId, classify(failure, loading = false), failure.message.orEmpty())
                }
                return
            }
            if (clientCallback == null) return
            startOperation(
                requestId = requestId,
                loading = true,
                clientCallback = clientCallback,
            ) {
                openSession(config)
                null
            }
        }

        override fun generate(
            requestId: Long,
            request: SharedMemory,
            thinking: Boolean,
            clientCallback: ILlmEngineCallback?,
        ) {
            lateinit var config: LlmSessionConfig
            lateinit var input: LlmInput
            try {
                decodeRequest(request) { parcel ->
                    config = parcelableCreator<LlmSessionConfig>().createFromParcel(parcel)
                    input = parcelableCreator<LlmInput>().createFromParcel(parcel)
                }
            } catch (failure: Throwable) {
                clientCallback?.let {
                    terminal(it, requestId, classify(failure, loading = false), failure.message.orEmpty())
                }
                return
            }
            if (clientCallback == null) return
            startOperation(
                requestId = requestId,
                loading = false,
                clientCallback = clientCallback,
            ) {
                operation.get()?.loading = true
                try {
                    openSession(config, input)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (failure: Throwable) {
                    throw LlmEngineException(classify(failure, loading = true), failure.message.orEmpty(), failure)
                } finally {
                    operation.get()?.loading = false
                }
                ownerCallback = clientCallback
                generateExecution(requestId, input, thinking, clientCallback)
            }
        }

        override fun cancel(requestId: Long) {
            operation.get()?.takeIf { (requestId <= 0L) || (it.requestId == requestId) }?.let { active ->
                cancelRuntime()
                active.job?.cancel(CancellationException("Cancelled by client"))
            } ?: cancelRuntime()
        }

        override fun shutdown(requestId: Long, clientCallback: ILlmEngineCallback?) {
            beginShutdown(
                requestId,
                LlmError.NONE,
                "",
                clientCallback ?: ownerCallback,
            )
        }
    }

    internal fun <T> decodeRequest(request: SharedMemory, decode: (Parcel) -> T): T {
        val parcel = Parcel.obtain()
        return try {
            request.use { payload ->
                val buffer = payload.mapReadOnly()
                val bytes = try {
                    ByteArray(buffer.remaining()).also { buffer.get(it) }
                } finally {
                    SharedMemory.unmap(buffer)
                }
                parcel.unmarshall(bytes, 0, bytes.size)
                parcel.setDataPosition(0)
                decode(parcel)
            }
        } finally {
            parcel.recycle()
        }
    }

    override fun onCreate() {
        super.onCreate()
        scope.launch {
            while (isActive) {
                delay(MEMORY_CHECK_INTERVAL_MS.milliseconds)
                if (benchmarkSampling) {
                    benchmarkSampleOnNextTick = !benchmarkSampleOnNextTick
                    if (benchmarkSampleOnNextTick) sampleBenchmarkMemory()
                }
                if (
                    (hasRuntime() || (operation.get()?.loading == true)) &&
                    isUnderHardMemoryPressure()
                ) {
                    val recipient = operation.get()?.callback ?: ownerCallback
                    beginShutdown(
                        requestId = 0,
                        error = LlmError.MEMORY_PRESSURE,
                        detail = "Android reported hard system-memory pressure",
                        recipient = recipient,
                    )
                }
            }
        }
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onUnbind(intent: Intent?): Boolean {
        beginShutdown(0, LlmError.NONE, "", ownerCallback)
        return false
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        beginShutdown(0, LlmError.NONE, "", ownerCallback)
        super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy() {
        serviceJob.cancel()
        super.onDestroy()
    }

    internal fun startOperation(
        requestId: Long,
        loading: Boolean,
        clientCallback: ILlmEngineCallback,
        block: suspend () -> LlmPerformanceMetrics?,
    ) {
        if ((requestId <= 0) || shuttingDown.get()) {
            terminal(
                clientCallback,
                requestId,
                LlmError.INVALID_REQUEST,
                "Engine is unavailable",
            )
            return
        }
        val next = Operation(requestId, loading, clientCallback)
        val job = scope.launch(start = CoroutineStart.LAZY) {
            val started = SystemClock.elapsedRealtime()
            AppLog.write(android.util.Log.INFO, "LocalLLM", "Operation started request=$requestId loading=$loading")
            try {
                val performance = block()
                AppLog.write(android.util.Log.INFO, "LocalLLM", "Operation completed request=$requestId loading=$loading elapsedMs=${SystemClock.elapsedRealtime() - started}")
                operation.compareAndSet(next, null)
                if (loading) {
                    ownerCallback = clientCallback
                    clientCallback.onReady(requestId)
                } else {
                    clientCallback.onTerminal(
                        LlmTerminal(
                            requestId = requestId,
                            reason = LlmTerminalReason.COMPLETED,
                            performance = performance,
                        ),
                    )
                }
            } catch (_: CancellationException) {
                AppLog.write(android.util.Log.INFO, "LocalLLM", "Operation cancelled request=$requestId loading=$loading")
                operation.compareAndSet(next, null)
                runCatching {
                    clientCallback.onTerminal(
                        LlmTerminal(requestId, LlmTerminalReason.CANCELLED),
                    )
                }
            } catch (failure: Throwable) {
                val error = classify(failure, loading)
                AppLog.write(android.util.Log.ERROR, "LocalLLM", "Operation failed request=$requestId loading=$loading error=$error", failure)
                operation.compareAndSet(next, null)
                terminal(clientCallback, requestId, error, failure.message.orEmpty())
                if (
                    (error == LlmError.OUT_OF_MEMORY) ||
                    (error == LlmError.MODEL_LOAD)
                ) {
                    beginShutdown(requestId, error, failure.message.orEmpty(), clientCallback)
                }
            } finally {
                operation.compareAndSet(next, null)
            }
        }
        next.job = job
        if (!operation.compareAndSet(null, next)) {
            job.cancel()
            terminal(
                clientCallback,
                requestId,
                LlmError.INVALID_REQUEST,
                "Engine is busy",
            )
            return
        }
        job.start()
    }

    internal suspend fun openSession(config: LlmSessionConfig, input: LlmInput? = null) {
        AppLog.write(android.util.Log.INFO, "LocalLLM", "Session runtime=${config.runtime} context=${config.contextTokens} threads=${config.cpuThreads} mmap=${config.useMmap} llamaBackend=${config.llamaBackend} liteRtBackend=${config.liteRtBackend} history=${config.history.size}")
        if (config.benchmarking) {
            beginBenchmarkMemorySampling()
        } else {
            benchmarkSampling = false
        }
        val signature = EngineSignature(
            runtime = config.runtime,
            modelPath = config.modelPath,
            liteRtBackend = config.liteRtBackend,
            llamaBackend = config.llamaBackend,
            llamaOffloadLayers = config.llamaOffloadLayers,
            cpuThreads = config.cpuThreads,
            promptThreads = config.promptThreads,
            contextTokens = config.contextTokens,
            speculativeDecoding = config.effectiveSpeculativeDecoding,
            batchTokens = config.batchTokens,
            microBatchTokens = config.microBatchTokens,
            flashAttention = config.flashAttention,
            quantizedKvCache = config.quantizedKvCache,
            cacheTypeK = config.cacheTypeK,
            cacheTypeV = config.cacheTypeV,
            cpuRepack = config.cpuRepack,
            useMmap = config.useMmap,

            benchmarking = config.benchmarking,
        )
        val loadModel = !hasRuntime() || (signature != engineSignature)
        AppLog.write(android.util.Log.INFO, "LocalLLM", "Model action=${if (loadModel) "load" else "reuse"}")
        if (loadModel) {
            liteRtConversation = null
            val closeFailure = runCatching(::closeRuntime)
            engineSignature = null
            closeFailure.getOrThrow()
            when (config.runtime) {
                LlmRuntime.LITERT -> liteRtRuntime = openLiteRtRuntime(config)
                LlmRuntime.LLAMA -> llamaRuntime = LlamaRuntime.open(
                    application = this,
                    nativeLibraryDirectory = applicationInfo.nativeLibraryDir,
                    cacheDirectory = cacheDir.absolutePath,
                    config = config.llamaEngineConfig(),
                )
                LlmRuntime.CLOUD -> error("Unsupported local runtime")
            }
            engineSignature = signature
        }
        // A live LiteRT conversation keeps native KV cache across sends. Reopening it
        // re-prefills the whole history, so reuse it when this turn continues the
        // conversation it already holds. Any doubt rebuilds exactly as before.
        // Preloads (input == null) only warm the model; the first turn builds once.
        when (config.runtime) {
            LlmRuntime.LITERT -> ensureLiteRtConversation(config, input)
            LlmRuntime.LLAMA -> checkNotNull(llamaRuntime).openConversation(
                LlamaTranscript.conversation(config),
            )
            LlmRuntime.CLOUD -> error("Unsupported local runtime")
        }
        currentSessionConfig = config
    }

    /**
     * What the live LiteRT conversation holds: the full history it was built with,
     * plus the exact turn inputs sent since. The next turn reuses it only when its
     * history is that same prefix (edits change inputs and force a rebuild) and its
     * input was not just sent (a resend would duplicate it natively).
     */
    private data class LiteRtConversationState(
        val modelPath: String,
        val systemInstruction: String,
        val openingMessage: String,
        val temperature: Float,
        val topK: Int,
        val topP: Float,
        val baseHistory: List<LlmExchange>,
        val sentInputs: List<LlmInput>,
    ) {
        fun matches(config: LlmSessionConfig, input: LlmInput): Boolean {
            if (config.benchmarking) return false
            if (config.modelPath != modelPath || config.systemInstruction != systemInstruction || config.openingMessage != openingMessage) return false
            if (config.temperature != temperature || config.topK != topK || config.topP != topP) return false
            if (config.history.size != baseHistory.size + sentInputs.size) return false
            if (config.history.take(baseHistory.size) != baseHistory) return false
            if (config.history.drop(baseHistory.size).map { it.input } != sentInputs) return false
            return sentInputs.isEmpty() || (input != sentInputs.last())
        }
    }

    private var liteRtConversation: LiteRtConversationState? = null

    private fun ensureLiteRtConversation(config: LlmSessionConfig, input: LlmInput?) {
        val state = liteRtConversation
        if ((input != null) && (state != null) && state.matches(config, input)) {
            android.util.Log.i("LocalLLM", "Conversation reuse historyTurns=${config.history.size}")
            return
        }
        if (input == null) return
        checkNotNull(liteRtRuntime).openConversation(config.liteRtConversationConfig())
        liteRtConversation = LiteRtConversationState(
            modelPath = config.modelPath,
            systemInstruction = config.systemInstruction,
            openingMessage = config.openingMessage,
            temperature = config.temperature,
            topK = config.topK,
            topP = config.topP,
            baseHistory = config.history,
            sentInputs = emptyList(),
        )
    }

    internal fun recordLiteRtSend(input: LlmInput) {
        val current = liteRtConversation ?: return
        liteRtConversation = current.copy(sentInputs = current.sentInputs + input)
    }

    internal fun dropLiteRtConversation() {
        liteRtConversation = null
    }



    @Volatile internal var currentSessionConfig: LlmSessionConfig? = null

    /*
        LiteRT emits structured message/channel objects; llama.cpp emits already parsed text/reasoning
        deltas. Both are normalized into the same bounded AIDL chunks below.
    */





    internal fun terminal(
        clientCallback: ILlmEngineCallback?,
        requestId: Long,
        error: LlmError,
        detail: String,
    ) {
        runCatching {
            clientCallback?.onTerminal(
                LlmTerminal(
                    requestId = requestId,
                    reason = LlmTerminalReason.FAILED,
                    error = error,
                    detail = detail.take(MAX_ERROR_DETAIL),
                ),
            )
        }
    }

    internal fun classify(failure: Throwable, loading: Boolean): LlmError {
        (failure as? LlmEngineException)?.let { return it.error }
        if (failure is OutOfMemoryError) return LlmError.OUT_OF_MEMORY
        val detail = generateSequence(failure) { it.cause }
            .joinToString(separator = " ") { it.message.orEmpty() }
            .lowercase()
        return when {
            ("out of memory" in detail) || ("allocation failed" in detail) ||
                ("resource exhausted" in detail) || ("std::bad_alloc" in detail) ->
                LlmError.OUT_OF_MEMORY
            ("context" in detail) || ("kv cache" in detail) || ("max token" in detail) ->
                LlmError.CONTEXT_EXHAUSTED
            loading -> LlmError.MODEL_LOAD
            else -> LlmError.INTERNAL
        }
    }

    internal fun beginShutdown(
        requestId: Long,
        error: LlmError,
        detail: String,
        recipient: ILlmEngineCallback?,
    ) {
        if (!shuttingDown.compareAndSet(false, true)) return
        AppLog.write(android.util.Log.INFO, "LocalLLM", "Shutdown request=$requestId reason=$error")
        if (error != LlmError.NONE) {
            runCatching {
                recipient?.onEvicted(
                    LlmTerminal(
                        requestId = requestId,
                        reason = LlmTerminalReason.FAILED,
                        error = error,
                        detail = detail.take(MAX_ERROR_DETAIL),
                    ),
                )
            }
        }
        if (
            (error == LlmError.MEMORY_PRESSURE) ||
            (error == LlmError.OUT_OF_MEMORY)
        ) {
            Process.killProcess(Process.myPid())
            return
        }
        mainHandler.postDelayed({ Process.killProcess(Process.myPid()) }, FORCE_EXIT_DELAY_MS)
        cancelRuntime()
        val active = operation.get()
        active?.job?.cancel(CancellationException("Engine is shutting down"))
        if (active == null) {
            finishShutdown(requestId, recipient)
        } else {
            active.job?.invokeOnCompletion { finishShutdown(requestId, recipient) }
        }
    }

    private fun finishShutdown(requestId: Long, recipient: ILlmEngineCallback?) {
        if (!cleanupStarted.compareAndSet(false, true)) return
        scope.launch {
            runCatching { closeRuntime() }
            engineSignature = null
            currentSessionConfig = null
            ownerCallback = null
            runCatching { recipient?.onShutdownReady(requestId) }
            stopSelf()
            Process.killProcess(Process.myPid())
        }
    }

    private fun hasRuntime(): Boolean =
        (liteRtRuntime != null) || (llamaRuntime != null)

    internal fun cancelRuntime() {
        liteRtRuntime?.cancel()
        llamaRuntime?.cancel()
    }

    private fun closeRuntime() {
        AppLog.write(android.util.Log.INFO, "LocalLLM", "Releasing native runtimes loaded=${hasRuntime()}")
        val liteRt = liteRtRuntime
        val llama = llamaRuntime
        liteRtRuntime = null
        llamaRuntime = null
        val liteRtClose = runCatching { liteRt?.close() }
        val llamaClose = runCatching { llama?.close() }
        liteRtClose.getOrThrow()
        llamaClose.getOrThrow()
    }

    private fun isUnderHardMemoryPressure(): Boolean {
        val manager = getSystemService(ActivityManager::class.java)
        val memory = ActivityManager.MemoryInfo().also(manager::getMemoryInfo)
        val pressure = isHardMemoryPressure(memory.totalMem, memory.availMem)
        if (pressure && !shuttingDown.get()) {
            AppLog.write(android.util.Log.WARN, "LocalLLM", "Memory pressure totalMiB=${memory.totalMem / 1048576} availableMiB=${memory.availMem / 1048576} androidLowMemory=${memory.lowMemory}")
        }
        return pressure
    }

    private fun beginBenchmarkMemorySampling() {
        if (benchmarkSampling) return
        benchmarkPeakPssKilobytes.set(0L)
        benchmarkMinimumAvailableMemoryBytes.set(0L)
        benchmarkSampleOnNextTick = true
        benchmarkSampling = true
        sampleBenchmarkMemory()
    }

    internal fun sampleBenchmarkMemory() {
        if (!benchmarkSampling) return
        benchmarkPeakPssKilobytes.accumulateAndGet(Debug.getPss(), ::maxOf)
        val manager = getSystemService(ActivityManager::class.java)
        val availableBytes = ActivityManager.MemoryInfo().also(manager::getMemoryInfo).availMem
        benchmarkMinimumAvailableMemoryBytes.accumulateAndGet(availableBytes) { previous, sample ->
            if (previous == 0L) sample else minOf(previous, sample)
        }
    }

    internal data class Operation(
        val requestId: Long,
        @Volatile var loading: Boolean,
        val callback: ILlmEngineCallback,
        var job: Job? = null,
    )

    private companion object {
        const val MEMORY_CHECK_INTERVAL_MS = 250L
        const val FORCE_EXIT_DELAY_MS = 2_000L
    }
}
