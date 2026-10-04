package com.mrj.fancyai.ui.settings

import android.content.Context
import android.content.SharedPreferences
import android.net.Uri
import androidx.core.content.edit
import com.mrj.fancyai.engine.GgufModels
import com.mrj.fancyai.engine.LiteRtBackend
import com.mrj.fancyai.engine.LiteRtModels
import com.mrj.fancyai.engine.LlamaBackend
import com.mrj.fancyai.engine.LocalLlmModel
import com.mrj.fancyai.engine.LocalLlmModels
import com.mrj.fancyai.engine.LocalLlmRuntime
import com.mrj.fancyai.engine.MnnModels
import com.mrj.fancyai.service.llm.CloudProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL

internal data class StarterLlmModel(
    val name: String,
    val fileName: String,
    val url: String,
    val sizeBytes: Long,
    val runtime: LocalLlmRuntime,
)

private const val ENGINE_PREFERENCES = "engine"
private const val GENERATION_PREFERENCES = "generation"
private const val ADVANCED_GENERATION_PREFERENCES = "advanced_generation"
private const val CLOUD_GENERATION_PREFERENCES = "cloud_generation"
private const val KEY_ACTIVE_PROVIDER = "active_provider"
private const val KEY_SELECTED_MODEL = "selected_model"
private const val KEY_LITERT_BACKEND = "litert_backend"
private const val KEY_LLAMA_BACKEND = "llama_backend"
private const val KEY_LLAMA_OFFLOAD_LAYERS = "llama_offload_layers"
private const val KEY_CPU_THREADS = "cpu_threads"
private const val KEY_PROMPT_THREADS = "prompt_threads"
private const val KEY_CONTEXT_TOKENS = "context_tokens"
private const val KEY_SPECULATIVE_DECODING = "speculative_decoding"
private const val KEY_BATCH_TOKENS = "batch_tokens"
private const val KEY_MICRO_BATCH_TOKENS = "micro_batch_tokens"
private const val KEY_FLASH_ATTENTION = "flash_attention"
private const val KEY_QUANTIZED_KV = "quantized_kv_cache"
private const val KEY_CACHE_TYPE_K = "cache_type_k"
private const val KEY_CACHE_TYPE_V = "cache_type_v"
private const val KEY_CPU_REPACK = "cpu_repack"
private const val KEY_USE_MMAP = "use_mmap"
private const val KEY_TEMPERATURE = "temperature"
private const val KEY_DYNAMIC_TEMPERATURE = "dynamic_temperature"
private const val KEY_TOP_K = "top_k"
private const val KEY_TOP_P = "top_p"
private const val KEY_MIN_P = "min_p"
private const val KEY_OUTPUT_TOKENS = "max_output_tokens"
private const val KEY_REPETITION_PENALTY = "repetition_penalty"
private const val KEY_PRESENCE_PENALTY = "presence_penalty"
private const val KEY_FREQUENCY_PENALTY = "frequency_penalty"
private const val KEY_PENALTY_WINDOW = "penalty_window"
private const val KEY_NO_REPEAT_NGRAM = "no_repeat_ngram_size"
private const val KEY_NO_REPEAT_WINDOW = "no_repeat_ngram_window"
private const val KEY_HISTORY_LIMIT = "history_limit"
private const val DEFAULT_BATCH_TOKENS = 512
private const val DEFAULT_MICRO_BATCH_TOKENS = 512

private val LLAMA_GENERATION_DEFAULT = GenerationSettings(
    temperature = 0.8f,
    dynamicTemperature = 0f,
    topK = 40,
    topP = 0.95f,
    minP = 0.05f,
    maxOutputTokens = -1,
    repetitionPenalty = 1.1f,
    presencePenalty = 0f,
    frequencyPenalty = 0f,
    penaltyWindow = 64,
)

private val MNN_GENERATION_DEFAULT = GenerationSettings(
    temperature = 0.7f,
    dynamicTemperature = 0f,
    topK = 40,
    topP = 0.9f,
    minP = 0.05f,
    maxOutputTokens = 512,
    repetitionPenalty = 1.1f,
    presencePenalty = 0f,
    frequencyPenalty = 0f,
    penaltyWindow = 0,
)

internal enum class LlamaOffload(val layers: Int) {
    AUTOMATIC(-1),
    NONE(0),
}

internal object LlmSettingsStore {
    suspend fun importModel(
        context: Context,
        uri: Uri,
        onProgress: (String, Float?) -> Unit,
    ): LocalLlmModel = withContext(Dispatchers.IO) {
        val document = documentInfo(context, uri)
        onProgress(document.name, if (document.size > 0L) 0f else null)
        localModels(context).import(context, uri, document.name, document.size) { read, total ->
            onProgress(document.name, if (total > 0L) (read.toFloat() / total).coerceIn(0f, 1f) else null)
        }.also { selectModel(context, it.path) }
    }

    val starterModels = listOf(
        StarterLlmModel(
            name = "Gemma 4 E4B (LiteRT)",
            fileName = "gemma-4-E4B-it.litertlm",
            url = "https://huggingface.co/litert-community/gemma-4-E4B-it-litert-lm/resolve/main/gemma-4-E4B-it.litertlm",
            sizeBytes = 3_659_530_240L,
            runtime = LocalLlmRuntime.LITERT,
        ),
        StarterLlmModel(
            name = "Gemma 4 E4B (GGUF)",
            fileName = "gemma-4-E4B-it-Q4_0.gguf",
            url = "https://huggingface.co/unsloth/gemma-4-E4B-it-GGUF/resolve/main/gemma-4-E4B-it-Q4_0.gguf",
            sizeBytes = 4_836_002_944L,
            runtime = LocalLlmRuntime.LLAMA,
        ),
    )

    private suspend fun copyDownloadStream(
        connection: HttpURLConnection,
        partial: File,
        append: Boolean,
        starter: StarterLlmModel,
        initialOffset: Long,
        onProgress: (String, Float?) -> Unit,
    ) {
        var copied = if (append) initialOffset else 0L
        val total = if (connection.contentLengthLong > 0) copied + connection.contentLengthLong else starter.sizeBytes
        onProgress(starter.name, if (total > 0L) (copied.toFloat() / total).coerceIn(0f, 1f) else null)
        connection.inputStream.use { input ->
            FileOutputStream(partial, append).use { output ->
                val buffer = ByteArray(1024 * 1024)
                while (true) {
                    currentCoroutineContext().ensureActive()
                    val count = input.read(buffer)
                    if (count < 0) break
                    output.write(buffer, 0, count)
                    copied += count
                    onProgress(starter.name, if (total > 0L) (copied.toFloat() / total).coerceIn(0f, 1f) else null)
                }
                output.fd.sync()
            }
        }
    }

    suspend fun downloadStarterModel(
        context: Context,
        starter: StarterLlmModel,
        onProgress: (String, Float?) -> Unit,
    ): LocalLlmModel = withContext(Dispatchers.IO) {
        val targetDir = when (starter.runtime) {
            LocalLlmRuntime.LITERT -> liteRtDirectory(context)
            LocalLlmRuntime.LLAMA -> llamaDirectory(context)
            LocalLlmRuntime.MNN -> File(context.getExternalFilesDir("models") ?: File(context.filesDir, "models"), "mnn")
        }.apply { mkdirs() }
        val target = File(targetDir, starter.fileName)
        val partial = File(targetDir, ".${starter.fileName}.download")
        val offset = if (partial.exists()) partial.length() else 0L
        val connection = (URL(starter.url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 20_000
            readTimeout = 30_000
            instanceFollowRedirects = true
            if (offset > 0L) setRequestProperty("Range", "bytes=$offset-")
        }
        try {
            val append = (offset > 0L) && (connection.responseCode == HttpURLConnection.HTTP_PARTIAL)
            copyDownloadStream(connection, partial, append, starter, offset, onProgress)
            if (target.exists()) target.delete()
            check(partial.renameTo(target)) { "Failed to install downloaded model." }
            selectModel(context, target.absolutePath)
            localModels(context).installed().first { it.path == target.absolutePath }
        } catch (e: Throwable) {
            partial.delete()
            throw e
        } finally {
            connection.disconnect()
        }
    }

    val contextLadder = listOf(2_048, 4_096, 8_192, 16_384, 32_768)
    val outputLadder = listOf(128, 256, 512, 1_024, 2_048, 4_096)
    val windowLadder = listOf(0, 32, 64, 128, 256, 512, 1_024)
    val ngramLadder = (0..10).toList()
    val historyLadder = listOf(8, 16, DEFAULT_HISTORY_LIMIT, 24, 32, 48, 64)

    fun snapshot(context: Context): LlmSettings? = activeEngine(context)?.let { engine ->
        val target = generationTarget(engine)
        val generation = when (engine) {
            is SelectedCloudEngine -> CloudSettingsStore.cloudGeneration(context, engine)
            is SelectedEngine -> generation(context, target)
        }
        LlmSettings(engine, generation, memory(context, target))
    }

    fun localSnapshot(context: Context, model: LocalLlmModel): LlmSettings =
        configuredEngine(context, model).let { engine ->
            val target = generationTarget(engine)
            LlmSettings(
                engine = engine,
                generation = generation(context, target),
                memory = memory(context, target),
            )
        }

    fun generationTarget(context: Context): GenerationTarget? = activeEngine(context)?.let(::generationTarget)

    fun activeEngine(context: Context): EngineChoice? = activeCloudProvider(context)?.let { provider ->
        CloudSettingsStore.selectedCloudEngine(context, provider)
    } ?: if (activeCloudProvider(context) == null) {
        selectedEngine(context)
    } else {
        null
    }

    fun activeCloudProvider(context: Context): CloudProvider? = context.getSharedPreferences(ENGINE_PREFERENCES, Context.MODE_PRIVATE)
        .getString(KEY_ACTIVE_PROVIDER, null)
        ?.let { stored -> runCatching { CloudProvider.valueOf(stored) }.getOrNull() }

    fun activateLocal(context: Context): Boolean {
        if (selectedEngine(context) == null) return false
        context.getSharedPreferences(ENGINE_PREFERENCES, Context.MODE_PRIVATE).edit { remove(KEY_ACTIVE_PROVIDER) }
        return true
    }

    fun selectedEngine(context: Context): SelectedEngine? {
        val models = localModels(context).installed()
        val selectedPath = selectedModelPath(context)
        val model = models.find { it.path == selectedPath } ?: models.firstOrNull() ?: return null
        return configuredEngine(context, model)
    }

    private fun configuredEngine(context: Context, model: LocalLlmModel): SelectedEngine {
        val preferences = context.getSharedPreferences(ENGINE_PREFERENCES, Context.MODE_PRIVATE)
        val liteRtBackend = runCatching {
            LiteRtBackend.valueOf(
                preferences.getString(modelKey(KEY_LITERT_BACKEND, model.path), null)
                    ?: LiteRtBackend.CPU.name,
            )
        }.getOrDefault(LiteRtBackend.CPU).takeIf { model.runtime == LocalLlmRuntime.LITERT }
            ?: LiteRtBackend.CPU
        val llamaBackend = runCatching {
            LlamaBackend.valueOf(
                preferences.getString(modelKey(KEY_LLAMA_BACKEND, model.path), null)
                    ?: LlamaBackend.CPU.name,
            )
        }.getOrDefault(LlamaBackend.CPU).takeIf { (model.runtime == LocalLlmRuntime.LLAMA) || (model.runtime == LocalLlmRuntime.MNN) }
            ?: LlamaBackend.CPU
        val storedOffloadLayers = preferences.getInt(
            modelKey(KEY_LLAMA_OFFLOAD_LAYERS, model.path),
            LlamaOffload.AUTOMATIC.layers,
        )
        val llamaOffloadLayers = if (llamaBackend == LlamaBackend.CPU) LlamaOffload.NONE.layers else storedOffloadLayers
        val coreCount = Runtime.getRuntime().availableProcessors().coerceAtLeast(1)
        val configuredCpuThreads = preferences.getInt(modelKey(KEY_CPU_THREADS, model.path), 0)
        val contextTokens = preferences.getInt(modelKey(KEY_CONTEXT_TOKENS, model.path), 4_096)
            .takeIf { it in contextLadder } ?: 4_096
        val batchTokens = preferences.getInt(modelKey(KEY_BATCH_TOKENS, model.path), DEFAULT_BATCH_TOKENS)
        val microBatchTokens = preferences.getInt(
            modelKey(KEY_MICRO_BATCH_TOKENS, model.path),
            DEFAULT_MICRO_BATCH_TOKENS,
        )

        val quantizedKvCache = preferences.getBoolean(modelKey(KEY_QUANTIZED_KV, model.path), false)
        return SelectedEngine(
            model = model,
            liteRtBackend = liteRtBackend,
            llamaBackend = llamaBackend,
            llamaOffloadLayers = llamaOffloadLayers,
            cpuThreads = when {
                model.runtime == LocalLlmRuntime.MNN -> configuredCpuThreads.coerceIn(0, coreCount).takeIf { it > 0 } ?: minOf(4, coreCount)
                model.runtime == LocalLlmRuntime.LLAMA -> configuredCpuThreads
                liteRtBackend == LiteRtBackend.CPU -> configuredCpuThreads.coerceIn(0, coreCount)
                else -> 0
            },
            promptThreads = if (model.runtime == LocalLlmRuntime.LLAMA) {
                preferences.getInt(modelKey(KEY_PROMPT_THREADS, model.path), 0)
            } else {
                0
            },
            contextTokens = contextTokens,
            speculativeDecoding = model.supportsSpeculativeDecoding &&
                preferences.getBoolean(modelKey(KEY_SPECULATIVE_DECODING, model.path), false) &&
                ((model.runtime != LocalLlmRuntime.LITERT) ||
                    (generation(context, GenerationTarget.LITERT).topK == 1)),
            batchTokens = batchTokens,
            microBatchTokens = microBatchTokens,
            flashAttention = flashAttention(preferences, modelKey(KEY_FLASH_ATTENTION, model.path)),
            quantizedKvCache = quantizedKvCache,
            cacheTypeK = preferences.getString(modelKey(KEY_CACHE_TYPE_K, model.path), "f16") ?: "f16",
            cacheTypeV = preferences.getString(modelKey(KEY_CACHE_TYPE_V, model.path), "f16") ?: "f16",
            cpuRepack = preferences.getBoolean(modelKey(KEY_CPU_REPACK, model.path), true),
            useMmap = preferences.getBoolean(modelKey(KEY_USE_MMAP, model.path), true),
        )
    }

    fun selectedModelPath(context: Context): String? = context.getSharedPreferences(ENGINE_PREFERENCES, Context.MODE_PRIVATE)
        .getString(KEY_SELECTED_MODEL, null)

    fun selectModel(context: Context, path: String?, activate: Boolean = true) = context.getSharedPreferences(ENGINE_PREFERENCES, Context.MODE_PRIVATE).edit {
        path?.let {
            putString(KEY_SELECTED_MODEL, it)
            if (activate) remove(KEY_ACTIVE_PROVIDER)
        } ?: remove(KEY_SELECTED_MODEL)
    }

    fun liteRtBackend(context: Context): LiteRtBackend = runCatching {
        LiteRtBackend.valueOf(
            context.getSharedPreferences(ENGINE_PREFERENCES, Context.MODE_PRIVATE).getString(modelKey(context, KEY_LITERT_BACKEND), null)
                ?: LiteRtBackend.CPU.name,
        )
    }.getOrDefault(LiteRtBackend.CPU)

    fun saveLiteRtBackend(context: Context, value: LiteRtBackend) = context.getSharedPreferences(ENGINE_PREFERENCES, Context.MODE_PRIVATE).edit {
        putString(modelKey(context, KEY_LITERT_BACKEND), value.name)
    }

    fun llamaBackend(context: Context): LlamaBackend = runCatching {
        LlamaBackend.valueOf(
            context.getSharedPreferences(ENGINE_PREFERENCES, Context.MODE_PRIVATE).getString(modelKey(context, KEY_LLAMA_BACKEND), null)
                ?: LlamaBackend.CPU.name,
        )
    }.getOrDefault(LlamaBackend.CPU)

    fun saveLlamaBackend(context: Context, backend: LlamaBackend) =
        context.getSharedPreferences(ENGINE_PREFERENCES, Context.MODE_PRIVATE).edit {
            putString(modelKey(context, KEY_LLAMA_BACKEND), backend.name)
        }

    fun llamaOffloadLayers(context: Context): Int = if (llamaBackend(context) == LlamaBackend.CPU) {
        LlamaOffload.NONE.layers
    } else {
        context.getSharedPreferences(ENGINE_PREFERENCES, Context.MODE_PRIVATE)
            .getInt(modelKey(context, KEY_LLAMA_OFFLOAD_LAYERS), LlamaOffload.AUTOMATIC.layers)
    }

    fun saveLlamaOffloadLayers(context: Context, layers: Int) = context.getSharedPreferences(ENGINE_PREFERENCES, Context.MODE_PRIVATE).edit {
        putInt(modelKey(context, KEY_LLAMA_OFFLOAD_LAYERS), layers)
    }

    fun speculativeDecoding(context: Context): Boolean =
        context.getSharedPreferences(ENGINE_PREFERENCES, Context.MODE_PRIVATE).getBoolean(modelKey(context, KEY_SPECULATIVE_DECODING), false)

    fun saveSpeculativeDecoding(context: Context, enabled: Boolean) = context.getSharedPreferences(ENGINE_PREFERENCES, Context.MODE_PRIVATE).edit {
        putBoolean(modelKey(context, KEY_SPECULATIVE_DECODING), enabled)
    }

    fun contextTokens(context: Context): Int = context.getSharedPreferences(ENGINE_PREFERENCES, Context.MODE_PRIVATE)
        .getInt(modelKey(context, KEY_CONTEXT_TOKENS), 4_096)
        .takeIf { it in contextLadder } ?: 4_096

    fun saveContextTokens(context: Context, tokens: Int) = context.getSharedPreferences(ENGINE_PREFERENCES, Context.MODE_PRIVATE).edit {
        putInt(modelKey(context, KEY_CONTEXT_TOKENS), tokens)
    }

    fun cpuThreads(context: Context): Int {
        val threads = context.getSharedPreferences(ENGINE_PREFERENCES, Context.MODE_PRIVATE)
            .getInt(modelKey(context, KEY_CPU_THREADS), 0)
        return if (selectedEngine(context)?.model?.runtime == LocalLlmRuntime.LLAMA) threads
        else threads.coerceIn(0, Runtime.getRuntime().availableProcessors().coerceAtLeast(1))
    }

    fun saveCpuThreads(context: Context, threads: Int, llama: Boolean = false) = context.getSharedPreferences(ENGINE_PREFERENCES, Context.MODE_PRIVATE).edit {
        putInt(
            modelKey(context, KEY_CPU_THREADS),
            if (llama) threads else threads.coerceIn(0, Runtime.getRuntime().availableProcessors().coerceAtLeast(1)),
        )
    }

    fun promptThreads(context: Context): Int = context.getSharedPreferences(ENGINE_PREFERENCES, Context.MODE_PRIVATE)
        .getInt(modelKey(context, KEY_PROMPT_THREADS), 0)

    fun savePromptThreads(context: Context, threads: Int) = context.getSharedPreferences(ENGINE_PREFERENCES, Context.MODE_PRIVATE).edit {
        putInt(modelKey(context, KEY_PROMPT_THREADS), threads)
    }

    fun batchTokens(context: Context): Int = context.getSharedPreferences(ENGINE_PREFERENCES, Context.MODE_PRIVATE)
        .getInt(modelKey(context, KEY_BATCH_TOKENS), DEFAULT_BATCH_TOKENS)

    fun saveBatchTokens(context: Context, tokens: Int) {
        context.getSharedPreferences(ENGINE_PREFERENCES, Context.MODE_PRIVATE).edit {
            putInt(modelKey(context, KEY_BATCH_TOKENS), tokens)
        }
    }

    fun microBatchTokens(context: Context): Int = context.getSharedPreferences(ENGINE_PREFERENCES, Context.MODE_PRIVATE)
        .getInt(modelKey(context, KEY_MICRO_BATCH_TOKENS), DEFAULT_MICRO_BATCH_TOKENS)

    fun saveMicroBatchTokens(context: Context, tokens: Int) = context.getSharedPreferences(ENGINE_PREFERENCES, Context.MODE_PRIVATE).edit {
        putInt(
            modelKey(context, KEY_MICRO_BATCH_TOKENS),
            tokens,
        )
    }

    private fun flashAttention(preferences: SharedPreferences, key: String): Boolean? =
        if (preferences.contains(key)) preferences.getBoolean(key, true) else null

    fun flashAttention(context: Context): Boolean? =
        flashAttention(context.getSharedPreferences(ENGINE_PREFERENCES, Context.MODE_PRIVATE), modelKey(context, KEY_FLASH_ATTENTION))

    fun saveFlashAttention(context: Context, enabled: Boolean?) = context.getSharedPreferences(ENGINE_PREFERENCES, Context.MODE_PRIVATE).edit {
        val key = modelKey(context, KEY_FLASH_ATTENTION)
        enabled?.let { putBoolean(key, it) } ?: remove(key)
    }

    fun quantizedKvCache(context: Context): Boolean = context.getSharedPreferences(ENGINE_PREFERENCES, Context.MODE_PRIVATE)
        .getBoolean(modelKey(context, KEY_QUANTIZED_KV), false)

    fun saveQuantizedKvCache(context: Context, enabled: Boolean) = context.getSharedPreferences(ENGINE_PREFERENCES, Context.MODE_PRIVATE).edit {
        putBoolean(modelKey(context, KEY_QUANTIZED_KV), enabled)
    }

    val cacheTypes = listOf("f32", "f16", "bf16", "q8_0", "q4_0", "q4_1", "iq4_nl", "q5_0", "q5_1")

    fun cacheType(context: Context, key: Boolean): String = context.getSharedPreferences(ENGINE_PREFERENCES, Context.MODE_PRIVATE)
        .getString(modelKey(context, if (key) KEY_CACHE_TYPE_K else KEY_CACHE_TYPE_V), "f16") ?: "f16"

    fun saveCacheType(context: Context, key: Boolean, type: String) = context.getSharedPreferences(ENGINE_PREFERENCES, Context.MODE_PRIVATE).edit {
        putString(modelKey(context, if (key) KEY_CACHE_TYPE_K else KEY_CACHE_TYPE_V), type)
    }

    fun cpuRepack(context: Context): Boolean = context.getSharedPreferences(ENGINE_PREFERENCES, Context.MODE_PRIVATE)
        .getBoolean(modelKey(context, KEY_CPU_REPACK), true)

    fun saveCpuRepack(context: Context, enabled: Boolean) = context.getSharedPreferences(ENGINE_PREFERENCES, Context.MODE_PRIVATE).edit {
        putBoolean(modelKey(context, KEY_CPU_REPACK), enabled)
    }

    fun useMmap(context: Context): Boolean = context.getSharedPreferences(ENGINE_PREFERENCES, Context.MODE_PRIVATE)
        .getBoolean(modelKey(context, KEY_USE_MMAP), true)

    fun saveUseMmap(context: Context, enabled: Boolean) = context.getSharedPreferences(ENGINE_PREFERENCES, Context.MODE_PRIVATE).edit {
        putBoolean(modelKey(context, KEY_USE_MMAP), enabled)
    }

    fun resetSelectedEngine(context: Context) {
        val path = selectedModelPath(context) ?: return
        context.getSharedPreferences(ENGINE_PREFERENCES, Context.MODE_PRIVATE).edit {
            remove(modelKey(KEY_LITERT_BACKEND, path))
            remove(modelKey(KEY_LLAMA_BACKEND, path))
            remove(modelKey(KEY_LLAMA_OFFLOAD_LAYERS, path))
            remove(modelKey(KEY_SPECULATIVE_DECODING, path))
            remove(modelKey(KEY_CONTEXT_TOKENS, path))
            remove(modelKey(KEY_CPU_THREADS, path))
            remove(modelKey(KEY_PROMPT_THREADS, path))
            remove(modelKey(KEY_BATCH_TOKENS, path))
            remove(modelKey(KEY_MICRO_BATCH_TOKENS, path))
            remove(modelKey(KEY_FLASH_ATTENTION, path))
            remove(modelKey(KEY_QUANTIZED_KV, path))
            remove(modelKey(KEY_CACHE_TYPE_K, path))
            remove(modelKey(KEY_CACHE_TYPE_V, path))
            remove(modelKey(KEY_CPU_REPACK, path))
            remove(modelKey(KEY_USE_MMAP, path))
        }
    }

    fun generation(context: Context, target: GenerationTarget): GenerationSettings {
        require(target != GenerationTarget.CLOUD) { "Cloud generation settings use their provider contract" }
        val defaults = defaultGeneration(target)
        val preferences = context.getSharedPreferences(generationPreferencesName(target), Context.MODE_PRIVATE)
        if (target == GenerationTarget.LLAMA) return GenerationSettings(
            temperature = preferences.getFloat(KEY_TEMPERATURE, defaults.temperature),
            dynamicTemperature = preferences.getFloat(KEY_DYNAMIC_TEMPERATURE, defaults.dynamicTemperature),
            topK = preferences.getInt(KEY_TOP_K, defaults.topK),
            topP = preferences.getFloat(KEY_TOP_P, defaults.topP),
            minP = preferences.getFloat(KEY_MIN_P, defaults.minP),
            maxOutputTokens = preferences.getInt(KEY_OUTPUT_TOKENS, defaults.maxOutputTokens),
            repetitionPenalty = preferences.getFloat(KEY_REPETITION_PENALTY, defaults.repetitionPenalty),
            presencePenalty = preferences.getFloat(KEY_PRESENCE_PENALTY, defaults.presencePenalty),
            frequencyPenalty = preferences.getFloat(KEY_FREQUENCY_PENALTY, defaults.frequencyPenalty),
            penaltyWindow = preferences.getInt(KEY_PENALTY_WINDOW, defaults.penaltyWindow),
        )
        return GenerationSettings(
            temperature = preferences.getFloat(KEY_TEMPERATURE, defaults.temperature).coerceIn(0f, 2f),
            dynamicTemperature = preferences.getFloat(KEY_DYNAMIC_TEMPERATURE, defaults.dynamicTemperature)
                .coerceIn(0f, 2f),
            topK = preferences.getInt(KEY_TOP_K, defaults.topK).coerceIn(1, 100),
            topP = preferences.getFloat(KEY_TOP_P, defaults.topP).coerceIn(0f, 1f),
            minP = preferences.getFloat(KEY_MIN_P, defaults.minP).coerceIn(0f, 1f),
            maxOutputTokens = preferences.getInt(KEY_OUTPUT_TOKENS, defaults.maxOutputTokens)
                .takeIf(outputLadder::contains) ?: defaults.maxOutputTokens,
            repetitionPenalty = preferences.getFloat(KEY_REPETITION_PENALTY, defaults.repetitionPenalty)
                .coerceIn(1f, 1.5f),
            presencePenalty = preferences.getFloat(KEY_PRESENCE_PENALTY, defaults.presencePenalty)
                .coerceIn(if (target == GenerationTarget.MNN) 0f else -2f, 2f),
            frequencyPenalty = preferences.getFloat(KEY_FREQUENCY_PENALTY, defaults.frequencyPenalty)
                .coerceIn(if (target == GenerationTarget.MNN) 0f else -2f, 2f),
            penaltyWindow = preferences.getInt(KEY_PENALTY_WINDOW, defaults.penaltyWindow)
                .takeIf(windowLadder::contains) ?: defaults.penaltyWindow,
            noRepeatNgramSize = preferences.getInt(KEY_NO_REPEAT_NGRAM, defaults.noRepeatNgramSize)
                .takeIf(ngramLadder::contains) ?: defaults.noRepeatNgramSize,
            noRepeatNgramWindow = preferences.getInt(KEY_NO_REPEAT_WINDOW, defaults.noRepeatNgramWindow)
                .takeIf(windowLadder::contains) ?: defaults.noRepeatNgramWindow,
        )
    }

    fun saveGeneration(context: Context, target: GenerationTarget, settings: GenerationSettings) {
        require(target != GenerationTarget.CLOUD) { "Cloud generation settings use their provider contract" }
        context.getSharedPreferences(generationPreferencesName(target), Context.MODE_PRIVATE).edit {
            putFloat(KEY_TEMPERATURE, settings.temperature)
            putFloat(KEY_DYNAMIC_TEMPERATURE, settings.dynamicTemperature)
            putInt(KEY_TOP_K, settings.topK)
            putFloat(KEY_TOP_P, settings.topP)
            putFloat(KEY_MIN_P, settings.minP)
            putInt(KEY_OUTPUT_TOKENS, settings.maxOutputTokens)
            putFloat(KEY_REPETITION_PENALTY, settings.repetitionPenalty)
            putFloat(KEY_PRESENCE_PENALTY, settings.presencePenalty)
            putFloat(KEY_FREQUENCY_PENALTY, settings.frequencyPenalty)
            putInt(KEY_PENALTY_WINDOW, settings.penaltyWindow)
            putInt(KEY_NO_REPEAT_NGRAM, settings.noRepeatNgramSize)
            putInt(KEY_NO_REPEAT_WINDOW, settings.noRepeatNgramWindow)
            // All sampling params are saved unconditionally; no bitmask filtering needed.
        }
    }

    fun defaultGeneration(target: GenerationTarget): GenerationSettings = when (target) {
        GenerationTarget.LITERT -> GenerationSettings()
        GenerationTarget.LLAMA -> LLAMA_GENERATION_DEFAULT
        GenerationTarget.MNN -> MNN_GENERATION_DEFAULT
        GenerationTarget.CLOUD -> error("Cloud generation settings use their provider contract")
    }

    fun memory(context: Context, target: GenerationTarget): MemorySettings {
        val selected = context.getSharedPreferences("${generationPreferencesName(target)}_memory", Context.MODE_PRIVATE)
            .getInt(KEY_HISTORY_LIMIT, DEFAULT_HISTORY_LIMIT)
        return MemorySettings(selected.takeIf(historyLadder::contains) ?: DEFAULT_HISTORY_LIMIT)
    }

    fun saveMemory(context: Context, target: GenerationTarget, settings: MemorySettings) {
        context.getSharedPreferences("${generationPreferencesName(target)}_memory", Context.MODE_PRIVATE).edit {
            putInt(
                KEY_HISTORY_LIMIT,
                settings.historyLimit.takeIf(historyLadder::contains) ?: DEFAULT_HISTORY_LIMIT,
            )
        }
    }

    fun liteRtDirectory(context: Context): File {
        val modelRoot = context.getExternalFilesDir("models") ?: File(context.filesDir, "models")
        return File(modelRoot, "litert")
    }

    fun llamaDirectory(context: Context): File {
        val modelRoot = context.getExternalFilesDir("models") ?: File(context.filesDir, "models")
        return File(modelRoot, "llama")
    }

    fun localModels(context: Context) = LocalLlmModels(
        LiteRtModels(liteRtDirectory(context)),
        GgufModels(llamaDirectory(context)),
        MnnModels(File(context.getExternalFilesDir("models") ?: File(context.filesDir, "models"), "mnn")),
    )

    fun generationPreferencesName(target: GenerationTarget): String = when (target) {
        GenerationTarget.LITERT -> GENERATION_PREFERENCES
        GenerationTarget.LLAMA -> ADVANCED_GENERATION_PREFERENCES
        GenerationTarget.MNN -> "generation_mnn"
        GenerationTarget.CLOUD -> CLOUD_GENERATION_PREFERENCES
    }

    private fun generationTarget(engine: EngineChoice): GenerationTarget = when (engine) {
        is SelectedCloudEngine -> GenerationTarget.CLOUD
        is SelectedEngine -> when (engine.model.runtime) {
            LocalLlmRuntime.LITERT -> GenerationTarget.LITERT
            LocalLlmRuntime.LLAMA -> GenerationTarget.LLAMA
            LocalLlmRuntime.MNN -> GenerationTarget.MNN
        }
    }

    private fun modelKey(context: Context, key: String): String =
        selectedModelPath(context)?.let { modelKey(key, it) } ?: key

    private fun modelKey(key: String, path: String): String = "$key@$path"
}
