package com.mrj.fancyai.ui.settings

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.edit
import com.mrj.fancyai.R
import com.mrj.fancyai.service.llm.CloudLlmRuntime
import com.mrj.fancyai.service.llm.CloudModelInfo
import com.mrj.fancyai.service.llm.CloudProvider
import com.mrj.fancyai.service.llm.llmErrorResource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import java.util.concurrent.CancellationException

private const val ENGINE_PREFERENCES = "engine"
private const val CLOUD_GENERATION_PREFERENCES = "cloud_generation"
private const val KEY_ACTIVE_PROVIDER = "active_provider"
private const val KEY_CLOUD_API_KEY = "cloud_api_key"
private const val KEY_CLOUD_BASE_URL = "cloud_base_url"
private const val KEY_CLOUD_MODEL = "cloud_model"
private const val KEY_CLOUD_QUERY = "cloud_query"
private const val KEY_CLOUD_SUPPORTED_PARAMETERS = "cloud_supported_parameters"
private const val KEY_CLOUD_CONTEXT_LENGTH = "cloud_context_length"
private const val KEY_CLOUD_PRICE_INPUT = "cloud_price_input"
private const val KEY_CLOUD_PRICE_OUTPUT = "cloud_price_output"
private const val KEY_TEMPERATURE = "temperature"
private const val KEY_TOP_K = "top_k"
private const val KEY_TOP_P = "top_p"
private const val KEY_MIN_P = "min_p"
private const val KEY_OUTPUT_TOKENS = "max_output_tokens"
private const val KEY_REPETITION_PENALTY = "repetition_penalty"
private const val KEY_PRESENCE_PENALTY = "presence_penalty"
private const val KEY_FREQUENCY_PENALTY = "frequency_penalty"

internal fun CloudGenerationSettings.requestParameters(supported: Set<String>): Map<String, String> = buildMap {
    when {
        "max_tokens" in supported -> put("max_tokens", maxOutputTokens.toString())
        "max_completion_tokens" in supported -> put("max_completion_tokens", maxOutputTokens.toString())
    }
    temperature?.takeIf { "temperature" in supported }?.let { put("temperature", it.toString()) }
    topK?.takeIf { "top_k" in supported }?.let { put("top_k", it.toString()) }
    topP?.takeIf { "top_p" in supported }?.let { put("top_p", it.toString()) }
    minP?.takeIf { "min_p" in supported }?.let { put("min_p", it.toString()) }
    repetitionPenalty?.takeIf { "repetition_penalty" in supported }
        ?.let { put("repetition_penalty", it.toString()) }
    presencePenalty?.takeIf { "presence_penalty" in supported }
        ?.let { put("presence_penalty", it.toString()) }
    frequencyPenalty?.takeIf { "frequency_penalty" in supported }
        ?.let { put("frequency_penalty", it.toString()) }
}

internal object CloudSettingsStore {
    fun cloudApiKey(context: Context, provider: CloudProvider): String =
        context.getSharedPreferences(ENGINE_PREFERENCES, Context.MODE_PRIVATE).getString(cloudKey(provider, KEY_CLOUD_API_KEY), "").orEmpty()

    fun saveCloudApiKey(context: Context, provider: CloudProvider, value: String) =
        context.getSharedPreferences(ENGINE_PREFERENCES, Context.MODE_PRIVATE).edit { putString(cloudKey(provider, KEY_CLOUD_API_KEY), value) }

    fun cloudBaseUrl(context: Context): String =
        context.getSharedPreferences(ENGINE_PREFERENCES, Context.MODE_PRIVATE).getString(KEY_CLOUD_BASE_URL, "").orEmpty()

    fun saveCloudBaseUrl(context: Context, value: String) = context.getSharedPreferences(ENGINE_PREFERENCES, Context.MODE_PRIVATE).edit {
        putString(KEY_CLOUD_BASE_URL, value)
    }

    fun cloudModel(context: Context, provider: CloudProvider): String =
        context.getSharedPreferences(ENGINE_PREFERENCES, Context.MODE_PRIVATE).getString(cloudKey(provider, KEY_CLOUD_MODEL), "").orEmpty()

    fun saveCloudModel(context: Context, provider: CloudProvider, value: String) =
        context.getSharedPreferences(ENGINE_PREFERENCES, Context.MODE_PRIVATE).edit { putString(cloudKey(provider, KEY_CLOUD_MODEL), value) }

    fun saveCloudModel(context: Context, provider: CloudProvider, model: CloudModelInfo) =
        context.getSharedPreferences(ENGINE_PREFERENCES, Context.MODE_PRIVATE).edit {
            putString(cloudKey(provider, KEY_CLOUD_MODEL), model.id)
            putStringSet(
                cloudModelKey(provider, model.id, KEY_CLOUD_SUPPORTED_PARAMETERS),
                model.supportedParameters,
            )
            model.contextLength?.takeIf { it > 0 }?.let {
                putInt(cloudModelKey(provider, model.id, KEY_CLOUD_CONTEXT_LENGTH), it)
            } ?: remove(cloudModelKey(provider, model.id, KEY_CLOUD_CONTEXT_LENGTH))
            model.priceInput?.takeIf { it >= 0 }?.let {
                putString(cloudModelKey(provider, model.id, KEY_CLOUD_PRICE_INPUT), it.toString())
            } ?: remove(cloudModelKey(provider, model.id, KEY_CLOUD_PRICE_INPUT))
            model.priceOutput?.takeIf { it >= 0 }?.let {
                putString(cloudModelKey(provider, model.id, KEY_CLOUD_PRICE_OUTPUT), it.toString())
            } ?: remove(cloudModelKey(provider, model.id, KEY_CLOUD_PRICE_OUTPUT))
        }

    fun cloudModelQuery(context: Context, provider: CloudProvider): String =
        context.getSharedPreferences(ENGINE_PREFERENCES, Context.MODE_PRIVATE).getString(cloudKey(provider, KEY_CLOUD_QUERY), "").orEmpty()

    fun saveCloudModelQuery(context: Context, provider: CloudProvider, value: String) =
        context.getSharedPreferences(ENGINE_PREFERENCES, Context.MODE_PRIVATE).edit { putString(cloudKey(provider, KEY_CLOUD_QUERY), value) }

    fun selectedCloudEngine(context: Context, provider: CloudProvider): SelectedCloudEngine? {
        val model = cloudModel(context, provider).trim().removePrefix("models/")
        if (model.isEmpty()) return null
        val apiKey = cloudApiKey(context, provider).trim()
        if ((provider != CloudProvider.CUSTOM) && apiKey.isEmpty()) return null
        val baseUrl = cloudBaseUrl(context).trim()
        if ((provider == CloudProvider.CUSTOM) && baseUrl.isEmpty()) return null
        if ((provider == CloudProvider.CUSTOM) && runCatching {
            CloudLlmRuntime.customChatUrl(baseUrl)
        }.isFailure) return null
        return SelectedCloudEngine(
            provider = provider,
            apiKey = apiKey,
            baseUrl = baseUrl,
            model = model,
            structuredOutput = "structured_outputs" in context.getSharedPreferences(ENGINE_PREFERENCES, Context.MODE_PRIVATE).getStringSet(
                cloudModelKey(provider, model, KEY_CLOUD_SUPPORTED_PARAMETERS), emptySet(),
            ).orEmpty(),
            supportedGenerationParameters = CloudLlmRuntime.generationParameters(
                provider = provider,
                reported = context.getSharedPreferences(ENGINE_PREFERENCES, Context.MODE_PRIVATE).getStringSet(
                    cloudModelKey(provider, model, KEY_CLOUD_SUPPORTED_PARAMETERS),
                    null,
                )?.toSet(),
            ),
            contextLength = context.getSharedPreferences(ENGINE_PREFERENCES, Context.MODE_PRIVATE).let { preferences ->
                preferences.getInt(cloudModelKey(provider, model, KEY_CLOUD_CONTEXT_LENGTH), 0)
                    .takeIf { preferences.contains(cloudModelKey(provider, model, KEY_CLOUD_CONTEXT_LENGTH)) && it > 0 }
            },
            priceInput = context.getSharedPreferences(ENGINE_PREFERENCES, Context.MODE_PRIVATE)
                .getString(cloudModelKey(provider, model, KEY_CLOUD_PRICE_INPUT), null)?.toDoubleOrNull()
                ?.takeIf { it >= 0 },
            priceOutput = context.getSharedPreferences(ENGINE_PREFERENCES, Context.MODE_PRIVATE)
                .getString(cloudModelKey(provider, model, KEY_CLOUD_PRICE_OUTPUT), null)?.toDoubleOrNull()
                ?.takeIf { it >= 0 },
        )
    }

    fun activateCloud(context: Context, provider: CloudProvider): Boolean {
        if (selectedCloudEngine(context, provider) == null) return false
        context.getSharedPreferences(ENGINE_PREFERENCES, Context.MODE_PRIVATE).edit { putString(KEY_ACTIVE_PROVIDER, provider.name) }
        return true
    }

    fun cloudGeneration(context: Context, engine: SelectedCloudEngine): CloudGenerationSettings {
        val preferences = context.getSharedPreferences(CLOUD_GENERATION_PREFERENCES, Context.MODE_PRIVATE)
        if (!preferences.contains(cloudGenerationKey(engine, KEY_OUTPUT_TOKENS))) {
            return defaultCloudGeneration(engine)
        }
        fun float(key: String, range: ClosedFloatingPointRange<Float>): Float? {
            val storedKey = cloudGenerationKey(engine, key)
            return preferences.getFloat(storedKey, 0f)
                .takeIf { preferences.contains(storedKey) }
                ?.coerceIn(range)
        }
        return CloudGenerationSettings(
            maxOutputTokens = preferences.getInt(
                cloudGenerationKey(engine, KEY_OUTPUT_TOKENS),
                DEFAULT_OUTPUT_TOKENS,
            ).takeIf(LlmSettingsStore.outputLadder::contains) ?: DEFAULT_OUTPUT_TOKENS,
            temperature = float(KEY_TEMPERATURE, 0f..2f).takeIf { "temperature" in engine.supportedGenerationParameters },
            topK = cloudGenerationKey(engine, KEY_TOP_K).let { key ->
                preferences.getInt(key, 0).takeIf { preferences.contains(key) }?.coerceAtLeast(0)
            }.takeIf { "top_k" in engine.supportedGenerationParameters },
            topP = float(KEY_TOP_P, 0f..1f).takeIf { "top_p" in engine.supportedGenerationParameters },
            minP = float(KEY_MIN_P, 0f..1f).takeIf { "min_p" in engine.supportedGenerationParameters },
            repetitionPenalty = float(KEY_REPETITION_PENALTY, 0.01f..5f)
                .takeIf { "repetition_penalty" in engine.supportedGenerationParameters },
            presencePenalty = float(KEY_PRESENCE_PENALTY, -2f..2f)
                .takeIf { "presence_penalty" in engine.supportedGenerationParameters },
            frequencyPenalty = float(KEY_FREQUENCY_PENALTY, -2f..2f)
                .takeIf { "frequency_penalty" in engine.supportedGenerationParameters },
        )
    }

    fun defaultCloudGeneration(engine: SelectedCloudEngine): CloudGenerationSettings {
        val supported = engine.supportedGenerationParameters
        return CloudGenerationSettings(
            temperature = DEFAULT_TEMPERATURE.takeIf { "temperature" in supported },
            topP = DEFAULT_TOP_P.takeIf { "top_p" in supported },
            repetitionPenalty = DEFAULT_REPETITION_PENALTY.takeIf { "repetition_penalty" in supported },
        )
    }

    fun saveCloudGeneration(
        context: Context,
        engine: SelectedCloudEngine,
        settings: CloudGenerationSettings,
    ) {
        context.getSharedPreferences(CLOUD_GENERATION_PREFERENCES, Context.MODE_PRIVATE).edit {
            putInt(cloudGenerationKey(engine, KEY_OUTPUT_TOKENS), settings.maxOutputTokens)
            settings.temperature?.let { putFloat(cloudGenerationKey(engine, KEY_TEMPERATURE), it) }
                ?: remove(cloudGenerationKey(engine, KEY_TEMPERATURE))
            settings.topK?.let { putInt(cloudGenerationKey(engine, KEY_TOP_K), it) }
                ?: remove(cloudGenerationKey(engine, KEY_TOP_K))
            settings.topP?.let { putFloat(cloudGenerationKey(engine, KEY_TOP_P), it) }
                ?: remove(cloudGenerationKey(engine, KEY_TOP_P))
            settings.minP?.let { putFloat(cloudGenerationKey(engine, KEY_MIN_P), it) }
                ?: remove(cloudGenerationKey(engine, KEY_MIN_P))
            settings.repetitionPenalty?.let {
                putFloat(cloudGenerationKey(engine, KEY_REPETITION_PENALTY), it)
            } ?: remove(cloudGenerationKey(engine, KEY_REPETITION_PENALTY))
            settings.presencePenalty?.let {
                putFloat(cloudGenerationKey(engine, KEY_PRESENCE_PENALTY), it)
            } ?: remove(cloudGenerationKey(engine, KEY_PRESENCE_PENALTY))
            settings.frequencyPenalty?.let {
                putFloat(cloudGenerationKey(engine, KEY_FREQUENCY_PENALTY), it)
            } ?: remove(cloudGenerationKey(engine, KEY_FREQUENCY_PENALTY))
        }
    }

    private fun cloudKey(provider: CloudProvider, key: String): String = "$key@${provider.name}"

    private fun cloudModelKey(provider: CloudProvider, model: String, key: String): String =
        "$key@${provider.name}@${model.trim().removePrefix("models/")}"

    private fun cloudGenerationKey(engine: SelectedCloudEngine, key: String): String =
        cloudModelKey(engine.provider, engine.model, key)
}

internal class CloudSettingsController(
    val context: Context,
    private val scope: CoroutineScope,
    val provider: CloudProvider,
) {
    val runtime = CloudLlmRuntime()
    var apiKey by mutableStateOf(CloudSettingsStore.cloudApiKey(context, provider))
    var baseUrl by mutableStateOf(CloudSettingsStore.cloudBaseUrl(context))
    var model by mutableStateOf(CloudSettingsStore.cloudModel(context, provider))
    var query by mutableStateOf(CloudSettingsStore.cloudModelQuery(context, provider))
    var models by mutableStateOf(emptyList<CloudModelInfo>())
    var loading by mutableStateOf(value = false)
    var activeProvider by mutableStateOf(LlmSettingsStore.activeCloudProvider(context))
    var errorMessage by mutableIntStateOf(0)

    val configured get() = CloudSettingsStore.selectedCloudEngine(context, provider) != null
    val active get() = activeProvider == provider && configured
    val matches: List<CloudModelInfo>
        get() {
            val terms = query.trim().lowercase().split(' ').filter(String::isNotBlank)
            return if (terms.isEmpty()) models else models.filter { candidate ->
                val id = candidate.id.lowercase()
                terms.all(id::contains)
            }
        }

    fun fetchModels() {
        if (provider != CloudProvider.CUSTOM && apiKey.isBlank()) {
            errorMessage = R.string.cloud_key_required
            return
        }
        if (provider == CloudProvider.CUSTOM && baseUrl.isBlank()) {
            errorMessage = R.string.cloud_endpoint_required
            return
        }
        loading = true
        errorMessage = 0
        val requestedApiKey = apiKey.trim()
        val requestedBaseUrl = baseUrl.trim()
        scope.launch {
            try {
                val fetched = runtime.fetchModels(provider, requestedApiKey, requestedBaseUrl)
                models = fetched
                fetched.firstOrNull { it.id == model.trim().removePrefix("models/") }?.let { selected ->
                    CloudSettingsStore.saveCloudModel(context, provider, selected)
                }
                errorMessage = if (fetched.isEmpty()) R.string.cloud_models_empty else 0
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                models = emptyList()
                errorMessage = llmErrorResource(failure, R.string.cloud_models_failed)
            } finally {
                loading = false
            }
        }
    }

    fun activate(): Boolean {
        errorMessage = when {
            provider != CloudProvider.CUSTOM && apiKey.isBlank() -> R.string.cloud_key_required
            provider == CloudProvider.CUSTOM && baseUrl.isBlank() -> R.string.cloud_endpoint_required
            provider == CloudProvider.CUSTOM && runCatching { CloudLlmRuntime.customChatUrl(baseUrl) }.isFailure -> R.string.cloud_endpoint_invalid
            model.isBlank() -> R.string.cloud_model_required
            !CloudSettingsStore.activateCloud(context, provider) -> R.string.cloud_configuration_incomplete
            else -> 0
        }
        if (errorMessage == 0) activeProvider = provider
        return errorMessage == 0
    }
}
