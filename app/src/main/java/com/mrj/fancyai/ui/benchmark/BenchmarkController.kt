package com.mrj.fancyai.ui.benchmark

import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.PowerManager
import android.os.SystemClock
import android.text.format.Formatter
import android.util.AtomicFile
import androidx.annotation.StringRes
import androidx.core.content.edit
import com.mrj.fancyai.R
import com.mrj.fancyai.engine.LlamaBackend
import com.mrj.fancyai.engine.LocalLlmModel
import com.mrj.fancyai.engine.LocalLlmRuntime
import com.mrj.fancyai.service.llm.AssistantProtocol
import com.mrj.fancyai.service.llm.LlmEngineClient
import com.mrj.fancyai.service.llm.LlmInput
import com.mrj.fancyai.service.llm.LlmPerformanceMetrics
import com.mrj.fancyai.service.llm.LlmRequest
import com.mrj.fancyai.service.llm.MacroBus
import com.mrj.fancyai.ui.kit.UiSymbols
import com.mrj.fancyai.ui.settings.GenerationSettings
import com.mrj.fancyai.ui.settings.LlmSettings
import com.mrj.fancyai.ui.settings.LlmSettingsStore
import com.mrj.fancyai.ui.settings.SelectedEngine
import com.mrj.fancyai.ui.settings.activeSystemPrompt
import com.mrj.fancyai.ui.settings.runtime
import com.mrj.fancyai.ui.settings.sessionConfig
import com.mrj.fancyai.util.writeAtomicFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.text.DateFormat
import java.util.Date
import java.util.UUID
import kotlin.math.sqrt


internal const val BENCHMARK_PROMPT_TARGET_TOKENS = 512
internal const val BENCHMARK_OUTPUT_TOKENS = 128
internal val BENCHMARK_REPETITION_CHOICES = listOf(1, 3, 5)

internal enum class BenchmarkPhase { IDLE, RESETTING, LOADING, WARMING, PASS }

internal data class BenchmarkProgress(
    val phase: BenchmarkPhase = BenchmarkPhase.IDLE,
    val pass: Int = 0,
    val repetitions: Int = 0,
    val completedPasses: Int = 0,
    val latestDecodeTokensPerSecond: Double? = null,
)

@Serializable
internal data class BenchmarkConfiguration(
    val modelName: String,
    val modelPath: String,
    val modelSizeBytes: Long,
    val runtime: LocalLlmRuntime,
    val backend: String,
    val deviceModel: String,
    val cpuThreads: Int,
    val promptThreads: Int,
    val contextTokens: Int,
    val speculativeDecoding: Boolean,
    val batchTokens: Int,
    val microBatchTokens: Int,
    val flashAttention: Boolean?,
    val quantizedKvCache: Boolean,
    val cacheTypeK: String = "f16",
    val cacheTypeV: String = "f16",
    val cpuRepack: Boolean,
    val useMmap: Boolean,
    val llamaOffloadLayers: Int,
    val temperature: Float,
    val dynamicTemperature: Float,
    val topK: Int,
    val topP: Float,
    val minP: Float,
    val repetitionPenalty: Float,
    val presencePenalty: Float,
    val frequencyPenalty: Float,
    val penaltyWindow: Int,
    val noRepeatNgramSize: Int,
    val noRepeatNgramWindow: Int,
)

@Serializable
internal data class BenchmarkPass(
    val number: Int,
    val timeToFirstTokenMilliseconds: Double,
    val promptTokenCount: Int,
    val generatedTokenCount: Int,
    val promptTokensPerSecond: Double,
    val decodeTokensPerSecond: Double,
    val totalGenerationMilliseconds: Double,
)

@Serializable
internal data class BenchmarkRun(
    val id: String,
    val createdAtEpochMilliseconds: Long,
    val configuration: BenchmarkConfiguration,
    val promptTargetTokens: Int,
    val outputTokenLimit: Int,
    val repetitions: Int,
    val coldReadyMilliseconds: Double,
    val peakPssKilobytes: Long,
    val minimumAvailableMemoryBytes: Long,
    val thermalBefore: Int,
    val thermalPeak: Int,
    val thermalAfter: Int,
    val passes: List<BenchmarkPass>,
    val actualContextTokens: Int? = null,
)

private fun List<BenchmarkPass>.standardDeviationOf(value: (BenchmarkPass) -> Double): Double {
    if (size < 2) return 0.0
    val mean = sumOf(value) / size
    return sqrt(
        sumOf { pass ->
            val difference = value(pass) - mean
            difference * difference
        } / size,
    )
}

internal class BenchmarkRunner(context: Context) {
    private val app = context.applicationContext
    @Volatile var activeClient: LlmEngineClient? = null
        private set

    suspend fun run(
        model: LocalLlmModel,
        repetitions: Int,
        onProgress: (BenchmarkProgress) -> Unit,
    ): BenchmarkRun? = withContext(Dispatchers.IO) {
        val snapshot = LlmSettingsStore.localSnapshot(app, model)
        val savedPrompt = activeSystemPrompt(app)
        val macros = MacroBus(app)
        val client = LlmEngineClient(app)
        activeClient = client
        try {
            withContext(NonCancellable + Dispatchers.Main.immediate) { onProgress(BenchmarkProgress(BenchmarkPhase.RESETTING, repetitions = repetitions)) }
            client.unload()
            coroutineContext.ensureActive()

            val power = app.getSystemService(PowerManager::class.java)
            val thermalBefore = runCatching { power.currentThermalStatus }.getOrDefault(THERMAL_UNKNOWN)
            var thermalPeak = thermalBefore
            withContext(NonCancellable + Dispatchers.Main.immediate) { onProgress(BenchmarkProgress(BenchmarkPhase.LOADING, repetitions = repetitions)) }
            val warmupTurn = if (model.runtime == LocalLlmRuntime.LLAMA) null
                else AssistantProtocol.compile(snapshot.runtime, macros, savedPrompt, "0. $BENCHMARK_BODY$BENCHMARK_TAIL")
            val warmup = LlmRequest(
                config = snapshot.sessionConfig(systemInstruction = warmupTurn?.systemInstruction.orEmpty()).copy(
                    historyLimit = 0, maxOutputTokens = if (model.runtime == LocalLlmRuntime.LLAMA) 1 else WARMUP_OUTPUT_TOKENS, benchmarking = true,
                ),
                input = warmupTurn?.input ?: LlmInput(),
                thinking = false,
            )
            val loadStarted = SystemClock.elapsedRealtimeNanos()
            client.prepare(warmup.config)
            val coldReadyMilliseconds = (SystemClock.elapsedRealtimeNanos() - loadStarted) / 1_000_000.0

            withContext(NonCancellable + Dispatchers.Main.immediate) { onProgress(BenchmarkProgress(BenchmarkPhase.WARMING, repetitions = repetitions)) }
            client.generateMeasured(warmup)
            thermalPeak = maxOf(thermalPeak, runCatching { power.currentThermalStatus }.getOrDefault(THERMAL_UNKNOWN))

            val passes = mutableListOf<BenchmarkPass>()
            var lastMetrics: LlmPerformanceMetrics? = null
            repeat(repetitions) { index ->
                coroutineContext.ensureActive()
                val turn = if (model.runtime == LocalLlmRuntime.LLAMA) null
                    else AssistantProtocol.compile(snapshot.runtime, macros, savedPrompt, "${index + 1}. $BENCHMARK_BODY$BENCHMARK_TAIL")
                val request = warmup.copy(
                    config = warmup.config.copy(systemInstruction = turn?.systemInstruction.orEmpty(), maxOutputTokens = BENCHMARK_OUTPUT_TOKENS),
                    input = turn?.input ?: LlmInput(),
                )
                client.prepare(request.config)
                withContext(NonCancellable + Dispatchers.Main.immediate) {
                    onProgress(BenchmarkProgress(
                        phase = BenchmarkPhase.PASS,
                        pass = index + 1,
                        repetitions = repetitions,
                        completedPasses = index,
                        latestDecodeTokensPerSecond = lastMetrics?.decodeTokensPerSecond,
                    ))
                }
                val metrics = client.generateMeasured(request)
                metrics?.let {
                    passes += BenchmarkPass(
                        number = index + 1,
                        timeToFirstTokenMilliseconds = it.timeToFirstTokenMilliseconds,
                        promptTokenCount = it.promptTokenCount,
                        generatedTokenCount = it.generatedTokenCount,
                        promptTokensPerSecond = it.promptTokensPerSecond,
                        decodeTokensPerSecond = it.decodeTokensPerSecond,
                        totalGenerationMilliseconds = it.totalGenerationMilliseconds,
                    )
                    lastMetrics = it
                }
                thermalPeak = maxOf(thermalPeak, runCatching { power.currentThermalStatus }.getOrDefault(THERMAL_UNKNOWN))
                withContext(NonCancellable + Dispatchers.Main.immediate) {
                    onProgress(BenchmarkProgress(
                        phase = BenchmarkPhase.PASS,
                        pass = index + 1,
                        repetitions = repetitions,
                        completedPasses = index + 1,
                        latestDecodeTokensPerSecond = metrics?.decodeTokensPerSecond,
                    ))
                }
            }
            val thermalAfter = runCatching { power.currentThermalStatus }.getOrDefault(THERMAL_UNKNOWN)
            thermalPeak = maxOf(thermalPeak, thermalAfter)
            lastMetrics?.let { memory ->
                BenchmarkRun(
                    id = UUID.randomUUID().toString(),
                    createdAtEpochMilliseconds = System.currentTimeMillis(),
                    configuration = benchmarkConfiguration(snapshot),
                    actualContextTokens = memory.actualContextTokens,
                    promptTargetTokens = BENCHMARK_PROMPT_TARGET_TOKENS,
                    outputTokenLimit = BENCHMARK_OUTPUT_TOKENS,
                    repetitions = repetitions,
                    coldReadyMilliseconds = coldReadyMilliseconds,
                    peakPssKilobytes = memory.peakPssKilobytes,
                    minimumAvailableMemoryBytes = memory.minimumAvailableMemoryBytes,
                    thermalBefore = thermalBefore,
                    thermalPeak = thermalPeak,
                    thermalAfter = thermalAfter,
                    passes = passes,
                )
            }
        } finally {
            activeClient = null
            client.close()
            withContext(NonCancellable + Dispatchers.Main.immediate) { onProgress(BenchmarkProgress()) }
        }
    }
}

internal fun benchmarkConfiguration(snapshot: LlmSettings): BenchmarkConfiguration {
    val engine = snapshot.engine as SelectedEngine
    val generation = snapshot.generation as GenerationSettings
    return BenchmarkConfiguration(
        modelName = engine.model.name,
        modelPath = engine.model.path,
        modelSizeBytes = engine.model.sizeBytes,
        runtime = engine.model.runtime,
        backend = when (engine.model.runtime) {
            LocalLlmRuntime.LITERT -> engine.liteRtBackend.name
            LocalLlmRuntime.LLAMA -> engine.llamaBackend.name
            LocalLlmRuntime.MNN -> if (engine.llamaBackend == LlamaBackend.OPENCL) "OpenCL" else "CPU"
        },
        deviceModel = sequenceOf(Build.MANUFACTURER, Build.MODEL).filter(String::isNotBlank)
            .joinToString(" ").replaceFirstChar { it.uppercase() },
        cpuThreads = engine.cpuThreads,
        promptThreads = engine.promptThreads,
        contextTokens = engine.contextTokens,
        speculativeDecoding = engine.speculativeDecoding,
        batchTokens = engine.batchTokens,
        microBatchTokens = engine.microBatchTokens,
        flashAttention = engine.flashAttention,
        quantizedKvCache = engine.quantizedKvCache,
        cacheTypeK = engine.cacheTypeK,
        cacheTypeV = engine.cacheTypeV,
        cpuRepack = engine.cpuRepack,
        useMmap = engine.useMmap,

        llamaOffloadLayers = engine.llamaOffloadLayers,
        temperature = generation.temperature,
        dynamicTemperature = generation.dynamicTemperature,
        topK = generation.topK,
        topP = generation.topP,
        minP = generation.minP,
        repetitionPenalty = generation.repetitionPenalty,
        presencePenalty = generation.presencePenalty,
        frequencyPenalty = generation.frequencyPenalty,
        penaltyWindow = generation.penaltyWindow,
        noRepeatNgramSize = generation.noRepeatNgramSize.takeIf {
            engine.model.runtime == LocalLlmRuntime.LITERT
        } ?: 0,
        noRepeatNgramWindow = generation.noRepeatNgramWindow.takeIf {
            engine.model.runtime == LocalLlmRuntime.LITERT
        } ?: 0,
    )
}

private val BENCHMARK_BODY = buildString {
    val targetCharacters = (BENCHMARK_PROMPT_TARGET_TOKENS * CHARACTERS_PER_TOKEN).toInt() -
        BENCHMARK_TAIL.length
    while (length < targetCharacters) append(BENCHMARK_SEED)
}

private const val CHARACTERS_PER_TOKEN = 4.6
private const val BENCHMARK_SEED =
    "Local inference speed reflects model structure, runtime kernels, memory traffic, and device heat. "
private const val BENCHMARK_TAIL =
    "\n\nRestate every claim above in different words, adding concrete detail until the output limit stops you."

internal class BenchmarkStore(context: Context) {
    private val app = context.applicationContext
    private val root = File(app.filesDir, DIRECTORY)
    private val preferences = app.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
    private val json = Json { ignoreUnknownKeys = true }

    suspend fun loadModels(): List<LocalLlmModel> = withContext(Dispatchers.IO) {
        LlmSettingsStore.localModels(app).installed().onEach { it.sizeBytes }
    }

    fun selectedModelPath(): String? = preferences.getString(KEY_MODEL, null)

    fun setSelectedModelPath(path: String) {
        preferences.edit { putString(KEY_MODEL, path) }
    }

    fun repetitions(): Int = preferences.getInt(KEY_REPETITIONS, DEFAULT_REPETITIONS)

    fun setRepetitions(repetitions: Int) {
        preferences.edit { putInt(KEY_REPETITIONS, repetitions) }
    }

    suspend fun load(): List<BenchmarkRun> = withContext(Dispatchers.IO) {
        root.listFiles().orEmpty().asSequence()
            .filter { file -> file.isFile && (file.extension == EXTENSION) }
            .map { file ->
                AtomicFile(file).openRead().bufferedReader(Charsets.UTF_8).use { json.decodeFromString<BenchmarkRun>(it.readText()) }
            }
            .sortedByDescending(BenchmarkRun::createdAtEpochMilliseconds)
            .toList()
    }

    suspend fun save(run: BenchmarkRun) = withContext(Dispatchers.IO) {
        writeAtomicFile(File(root, "${run.id}.$EXTENSION"), json.encodeToString(run).toByteArray(Charsets.UTF_8))
    }

    suspend fun delete(id: String) = withContext(Dispatchers.IO) {
        val target = File(root, "$id.$EXTENSION")
        AtomicFile(target).delete()
    }

    suspend fun clear() = withContext(Dispatchers.IO) {
        root.deleteRecursively()
    }

    private companion object {
        const val DIRECTORY = "benchmark"
        const val EXTENSION = "json"
        const val PREFERENCES = "benchmark_preferences"
        const val KEY_MODEL = "model"
        const val KEY_REPETITIONS = "repetitions"
        const val DEFAULT_REPETITIONS = 3
    }
}

internal const val THERMAL_UNKNOWN = -1
private const val WARMUP_OUTPUT_TOKENS = 8

internal data class Fact(@param:StringRes val label: Int, val value: String)

internal fun summaryFacts(context: Context, run: BenchmarkRun): List<Fact> = listOfNotNull(
    run.actualContextTokens?.let { Fact(R.string.benchmark_actual_context, it.toString()) },
    Fact(R.string.benchmark_decode, if (run.passes.isEmpty()) UiSymbols.UNAVAILABLE else formatRate(context, (run.passes.sumOf { it.decodeTokensPerSecond } / run.passes.size),
        run.passes.standardDeviationOf(BenchmarkPass::decodeTokensPerSecond), run.repetitions)),
    Fact(R.string.benchmark_prefill, if (run.passes.isEmpty()) UiSymbols.UNAVAILABLE else formatRate(context, (run.passes.sumOf { it.promptTokensPerSecond } / run.passes.size),
        run.passes.standardDeviationOf(BenchmarkPass::promptTokensPerSecond), run.repetitions)),
    Fact(R.string.benchmark_first_token, if (run.passes.isEmpty()) UiSymbols.UNAVAILABLE else context.getString(R.string.benchmark_seconds_value, (run.passes.sumOf { it.timeToFirstTokenMilliseconds } / run.passes.size) / 1_000.0)),
    Fact(R.string.benchmark_cold_ready, context.getString(R.string.benchmark_seconds_value, run.coldReadyMilliseconds / 1_000.0)),
    Fact(R.string.benchmark_total_time, if (run.passes.isEmpty()) UiSymbols.UNAVAILABLE else context.getString(R.string.benchmark_seconds_value, (run.passes.sumOf { it.totalGenerationMilliseconds } / run.passes.size) / 1_000.0)),
    Fact(R.string.benchmark_peak_memory,
        Formatter.formatShortFileSize(context, run.peakPssKilobytes * BYTES_PER_KILOBYTE)),
    Fact(R.string.benchmark_lowest_free_ram,
        Formatter.formatShortFileSize(context, run.minimumAvailableMemoryBytes)),
    Fact(R.string.benchmark_thermal, context.getString(R.string.benchmark_thermal_timeline, context.thermalName(run.thermalBefore), context.thermalName(run.thermalPeak), context.thermalName(run.thermalAfter))),
)

internal fun configurationFacts(
    context: Context,
    configuration: BenchmarkConfiguration,
): Pair<List<Fact>, List<Fact>> {
    val engine = buildList {
        add(Fact(R.string.benchmark_config_runtime, context.runtimeName(configuration.runtime)))
        add(Fact(R.string.benchmark_config_backend, context.backendName(configuration.backend)))
        add(Fact(R.string.benchmark_config_context, context.contextValue(configuration.contextTokens)))
        when (configuration.runtime) {
            LocalLlmRuntime.LITERT -> {
                add(Fact(R.string.engine_cpu_threads, context.threadValue(configuration.cpuThreads)))
                add(Fact(R.string.engine_speculative_decoding, context.enabledName(configuration.speculativeDecoding)))
            }
            LocalLlmRuntime.MNN -> {
                add(Fact(R.string.engine_cpu_threads, context.threadValue(configuration.cpuThreads)))
                add(Fact(R.string.engines_mnn_quantized_keys, context.enabledName(configuration.quantizedKvCache)))
                add(Fact(R.string.engines_mmap, context.enabledName(configuration.useMmap)))
            }
            LocalLlmRuntime.LLAMA -> {
                add(Fact(R.string.engine_decode_threads, context.threadValue(configuration.cpuThreads)))
                add(Fact(R.string.benchmark_config_prompt_threads, context.threadValue(configuration.promptThreads)))
                add(Fact(R.string.benchmark_config_batch, configuration.batchTokens.toString()))
                add(Fact(R.string.benchmark_config_micro_batch, configuration.microBatchTokens.toString()))
                add(Fact(R.string.benchmark_config_offload, context.offloadName(configuration.llamaOffloadLayers)))
                add(
                    Fact(
                        R.string.engine_flash_attention,
                        configuration.flashAttention?.let(context::enabledName)
                            ?: context.getString(R.string.aura_processor_auto),
                    ),
                )
                add(Fact(R.string.engines_cache_type_k, configuration.cacheTypeK.uppercase()))
                add(Fact(R.string.engines_cache_type_v, configuration.cacheTypeV.uppercase()))
                add(Fact(R.string.engines_mmap, context.enabledName(configuration.useMmap)))
                add(Fact(R.string.benchmark_config_cpu_repack, context.enabledName(configuration.cpuRepack)))
            }
        }
    }
    val sampling = buildList {
        add(Fact(R.string.sampling_temperature, context.getString(R.string.format_decimal_two_places, configuration.temperature)))
        if (configuration.runtime == LocalLlmRuntime.LLAMA) {
            add(Fact(R.string.sampling_dynamic_temperature, context.getString(R.string.format_decimal_two_places, configuration.dynamicTemperature)))
        }
        add(Fact(R.string.generation_top_k, configuration.topK.toString()))
        add(Fact(R.string.generation_top_p, context.getString(R.string.format_decimal_two_places, configuration.topP)))
        if (configuration.runtime == LocalLlmRuntime.LLAMA) {
            add(Fact(R.string.generation_min_p, context.getString(R.string.format_decimal_two_places, configuration.minP)))
        }
        add(Fact(R.string.sampling_repetition_penalty, context.getString(R.string.format_decimal_two_places, configuration.repetitionPenalty)))
        add(Fact(R.string.sampling_presence_penalty, context.getString(R.string.format_decimal_two_places, configuration.presencePenalty)))
        add(Fact(R.string.sampling_frequency_penalty, context.getString(R.string.format_decimal_two_places, configuration.frequencyPenalty)))
        add(Fact(R.string.sampling_penalty_window, if (configuration.runtime == LocalLlmRuntime.LLAMA) {
            when (configuration.penaltyWindow) {
                0 -> context.getString(R.string.settings_off)
                else -> context.windowValue(configuration.penaltyWindow)
            }
        } else context.windowValue(configuration.penaltyWindow)))
        if (configuration.runtime == LocalLlmRuntime.LITERT) {
            add(Fact(R.string.benchmark_config_no_repeat_size, configuration.noRepeatNgramSize.toString()))
            add(Fact(R.string.sampling_no_repeat_window, context.windowValue(configuration.noRepeatNgramWindow)))
        }
    }
    val runtimeDefaults = listOf(
        R.string.generation_top_k,
        R.string.generation_top_p,
        R.string.generation_min_p,
        R.string.sampling_temperature,
        R.string.sampling_dynamic_temperature,
        R.string.sampling_penalty_window,
        R.string.sampling_repetition_penalty,
        R.string.sampling_presence_penalty,
        R.string.sampling_frequency_penalty,
    )
    return engine to sampling.map { fact ->
        if (configuration.runtime == LocalLlmRuntime.LLAMA && fact.label in runtimeDefaults) {
            fact.copy(value = context.getString(R.string.engines_runtime_default))
        } else fact
    }
}

internal fun passFacts(context: Context, pass: BenchmarkPass): List<Fact> = listOf(
    Fact(R.string.benchmark_decode, context.getString(R.string.benchmark_rate_value, pass.decodeTokensPerSecond)),
    Fact(R.string.benchmark_prefill, context.getString(R.string.benchmark_rate_value, pass.promptTokensPerSecond)),
    Fact(R.string.benchmark_first_token, context.getString(R.string.benchmark_seconds_value, pass.timeToFirstTokenMilliseconds / 1_000.0)),
    Fact(R.string.benchmark_total_time, context.getString(R.string.benchmark_seconds_value, pass.totalGenerationMilliseconds / 1_000.0)),
    Fact(R.string.benchmark_prompt_tokens, pass.promptTokenCount.toString()),
    Fact(R.string.benchmark_output_tokens, pass.generatedTokenCount.toString()),
)

internal fun Context.runtimeName(runtime: LocalLlmRuntime): String = getString(
    when (runtime) {
        LocalLlmRuntime.LITERT -> R.string.engines_litert
        LocalLlmRuntime.LLAMA -> R.string.engines_llama
        LocalLlmRuntime.MNN -> R.string.engines_mnn
    },
)

internal fun Context.backendName(backend: String): String = getString(
    when (backend) {
        "CPU" -> R.string.engines_cpu
        "GPU" -> R.string.engines_gpu
        "OPENCL" -> R.string.engines_opencl
        "HEXAGON" -> R.string.engines_hexagon
        else -> return backend
    },
)

private fun Context.enabledName(enabled: Boolean): String = getString(
    if (enabled) R.string.label_enabled else R.string.benchmark_disabled,
)

private fun Context.contextValue(tokens: Int): String = if (tokens == 0) {
    getString(R.string.benchmark_model_default)
} else {
    resources.getQuantityString(R.plurals.benchmark_token_count, tokens, tokens)
}

private fun Context.threadValue(threads: Int): String = if (threads == 0) {
    getString(R.string.benchmark_runtime_managed)
} else {
    resources.getQuantityString(R.plurals.benchmark_thread_count, threads, threads)
}

private fun Context.windowValue(window: Int): String = if (window == 0) {
    getString(R.string.benchmark_full_context)
} else {
    resources.getQuantityString(R.plurals.benchmark_token_count, window, window)
}

private fun Context.offloadName(layers: Int): String = when (layers) {
    -2 -> getString(R.string.benchmark_runtime_managed)
    -1 -> getString(R.string.benchmark_offload_all)
    0 -> getString(R.string.benchmark_offload_none)
    else -> resources.getQuantityString(R.plurals.benchmark_offload_layers, layers, layers)
}

internal fun Context.thermalName(status: Int): String = getString(
    when (status) {
        PowerManager.THERMAL_STATUS_NONE -> R.string.benchmark_thermal_none
        PowerManager.THERMAL_STATUS_LIGHT -> R.string.benchmark_thermal_light
        PowerManager.THERMAL_STATUS_MODERATE -> R.string.benchmark_thermal_moderate
        PowerManager.THERMAL_STATUS_SEVERE -> R.string.benchmark_thermal_severe
        PowerManager.THERMAL_STATUS_CRITICAL -> R.string.benchmark_thermal_critical
        PowerManager.THERMAL_STATUS_EMERGENCY -> R.string.benchmark_thermal_emergency
        PowerManager.THERMAL_STATUS_SHUTDOWN -> R.string.benchmark_thermal_shutdown
        else -> R.string.benchmark_thermal_unknown
    },
)

private fun formatRate(context: Context, mean: Double, deviation: Double, repetitions: Int): String =
    if (repetitions > 1) context.getString(R.string.benchmark_rate_spread, mean, deviation)
    else context.getString(R.string.benchmark_rate_value, mean)

internal const val BYTES_PER_KILOBYTE = 1_024L

internal fun shareRun(context: Context, run: BenchmarkRun) {
    val (engine, sampling) = configurationFacts(context, run.configuration)
    val lines = buildList {
        add(context.getString(R.string.benchmark_share_title))
        add(run.configuration.modelName)
        add(DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(run.createdAtEpochMilliseconds)))
        add(context.getString(R.string.format_label_value, context.getString(R.string.benchmark_test_label), context.getString(R.string.benchmark_test_value, run.promptTargetTokens, run.outputTokenLimit, run.repetitions)))
        summaryFacts(context, run).forEach { fact ->
            add(context.getString(R.string.format_label_value, context.getString(fact.label), fact.value))
        }
        add("")
        add(context.getString(R.string.benchmark_engine_inputs_label))
        (engine + sampling).forEach { fact ->
            add(context.getString(R.string.format_label_value, context.getString(fact.label), fact.value))
        }
        run.passes.forEach { pass ->
            add("")
            add(context.getString(R.string.benchmark_pass_title, pass.number))
            passFacts(context, pass).forEach { fact ->
                add(context.getString(R.string.format_label_value, context.getString(fact.label), fact.value))
            }
        }
    }
    context.startActivity(
        Intent.createChooser(
            Intent(Intent.ACTION_SEND)
                .setType("text/plain")
                .putExtra(Intent.EXTRA_SUBJECT, context.getString(R.string.benchmark_share_subject, run.configuration.modelName))
                .putExtra(Intent.EXTRA_TEXT, lines.joinToString("\n")),
            context.getString(R.string.benchmark_share_chooser),
        ),
    )
}
