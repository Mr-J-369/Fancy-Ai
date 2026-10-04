package com.mrj.fancyai.service.llm

import android.graphics.BitmapFactory
import android.os.SystemClock
import android.util.Base64
import com.mrj.fancyai.util.AppLog
import com.mrj.fancyai.util.PrivateHttp.decodeLocalRoute
import com.mrj.fancyai.util.PrivateHttp.requiredBaseUrl
import com.mrj.fancyai.util.PrivateHttp.routePrivateHttp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.job
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import okhttp3.Call
import okhttp3.Dns
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

internal data class CloudLlmDelta(
    val text: String = "",
    val reasoning: String = "",
)

internal data class CloudModelInfo(
    val id: String,
    val supportedParameters: Set<String> = emptySet(),
    val supportsVision: Boolean = false,
    /** Reported context window in tokens. Null when the provider does not report one. */
    val contextLength: Int? = null,
    /** USD per 1M input/output tokens, normalized across providers. Null when unreported. */
    val priceInput: Double? = null,
    val priceOutput: Double? = null,
)

/** One OpenAI-compatible HTTP path for DeepInfra, OpenRouter, and user-supplied endpoints. */
internal class CloudLlmRuntime {
    private val activeCall = AtomicReference<Call?>()
    private val client = OkHttpClient.Builder()
        .dns { hostname ->
            decodeLocalRoute(hostname)?.let(::listOf) ?: Dns.SYSTEM.lookup(hostname)
        }
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()

    fun stream(
        config: LlmSessionConfig,
        input: LlmInput,
        thinking: Boolean,
        filesDir: File? = null,
    ): Flow<CloudLlmDelta> = flow {
        val payload = requestPayload(config, input, thinking, filesDir)
        val request = requestBuilder(config, chatUrl(config))
            .post(payload.toString().toRequestBody(JSON_MEDIA_TYPE))
            .build()
        val call = client.newCall(request)
        check(activeCall.compareAndSet(null, call)) { "A cloud generation is already active" }
        currentCoroutineContext().job.invokeOnCompletion { cause ->
            if (cause != null) call.cancel()
        }
        val started = SystemClock.elapsedRealtime()
        AppLog.write(android.util.Log.INFO, "CloudLLM", "Generation started provider=${config.cloudProvider} history=${config.history.size} thinking=$thinking")
        try {
            call.execute().use { response ->
                AppLog.write(android.util.Log.INFO, "CloudLLM", "HTTP status=${response.code}")
                if (!response.isSuccessful) {
                    val detail = response.body.string().take(MAX_ERROR_CHARS)
                    throw httpFailure(response.code, detail)
                }
                val source = response.body.source()
                val decoded = StreamResponse(started)
                while (!source.exhausted()) {
                    currentCoroutineContext().ensureActive()
                    val line = source.readUtf8Line() ?: break
                    if (!line.startsWith(SSE_DATA_PREFIX)) continue
                    val data = line.removePrefix(SSE_DATA_PREFIX).trim()
                    if (data.isEmpty()) continue
                    if (data == SSE_DONE) break
                    decoded.accept(data)?.let { emit(it) }
                }
                emit(decoded.finish())
            }
        } catch (failure: IOException) {
            if (call.isCanceled()) {
                AppLog.write(android.util.Log.INFO, "CloudLLM", "Generation cancelled")
                throw CancellationException("Cloud generation cancelled").also { it.initCause(failure) }
            }
            AppLog.write(android.util.Log.ERROR, "CloudLLM", "Network failure", failure)
            throw LlmEngineException(
                LlmError.NETWORK,
                "The cloud provider could not be reached",
                failure,
            )
        } catch (cancelled: CancellationException) {
            AppLog.write(android.util.Log.INFO, "CloudLLM", "Generation cancelled")
            throw cancelled
        } catch (failure: Exception) {
            AppLog.write(android.util.Log.ERROR, "CloudLLM", "Generation failed error=${(failure as? LlmEngineException)?.error}", failure)
            throw failure
        } finally {
            activeCall.compareAndSet(call, null)
        }
    }.flowOn(Dispatchers.IO)

    /** Decodes one response and owns its emitted-character counts and terminal status. */
    private class StreamResponse(private val started: Long) {
        private var textCharacters = 0
        private var reasoningCharacters = 0
        private val pendingText = StringBuilder()
        private var inlineReasoning = false

        fun accept(data: String): CloudLlmDelta? {
            val frame = runCatching { Json.parseToJsonElement(data).jsonObject }
                .getOrElse {
                    throw LlmEngineException(
                        LlmError.NETWORK,
                        "The provider returned an invalid streaming frame",
                        it,
                    )
                }
            providerError(frame)?.let { detail ->
                throw LlmEngineException(
                    error = classifyProviderFailure(0, detail),
                    message = detail.ifBlank { "The provider rejected the request" },
                )
            }
            val choice = (frame["choices"] as? JsonArray)?.firstOrNull() as? JsonObject ?: return null
            val delta = (choice["delta"] as? JsonObject) ?: return null
            pendingText.append(contentText(delta["content"]) + contentText(delta["refusal"]))
            val text = StringBuilder()
            val reasoning = StringBuilder(reasoningText(delta))
            while (pendingText.isNotEmpty()) {
                val marker = if (inlineReasoning) "</think>" else "<think>"
                if (pendingText.startsWith(marker, ignoreCase = true)) {
                    pendingText.delete(0, marker.length)
                    inlineReasoning = !inlineReasoning
                } else if (marker.startsWith(pendingText.toString(), ignoreCase = true)) {
                    break
                } else {
                    (if (inlineReasoning) reasoning else text).append(pendingText[0])
                    pendingText.deleteCharAt(0)
                }
            }
            textCharacters += text.length
            reasoningCharacters += reasoning.length
            return if (text.isEmpty() && reasoning.isEmpty()) null else CloudLlmDelta(text = text.toString(), reasoning = reasoning.toString())
        }

        fun finish(): CloudLlmDelta {
            AppLog.write(android.util.Log.INFO, "CloudLLM", "Stream ended textChars=$textCharacters reasoningChars=$reasoningCharacters elapsedMs=${SystemClock.elapsedRealtime() - started}")
            return if (inlineReasoning) CloudLlmDelta(reasoning = pendingText.toString())
            else CloudLlmDelta(text = pendingText.toString())
        }
    }

    suspend fun fetchModels(
        provider: CloudProvider,
        apiKey: String,
        baseUrl: String,
    ): List<CloudModelInfo> = withContext(Dispatchers.IO) {
        val config = LlmSessionConfig(
            modelPath = "",
            cpuThreads = 0,
            contextTokens = 0,
            speculativeDecoding = false,
            systemInstruction = "",
            openingMessage = "",
            history = emptyList(),
            historyLimit = 0,
            temperature = 1f,
            topK = 64,
            topP = 0.95f,
            maxOutputTokens = 128,
            repetitionPenalty = 1f,
            presencePenalty = 0f,
            frequencyPenalty = 0f,
            penaltyWindow = 0,
            noRepeatNgramSize = 0,
            noRepeatNgramWindow = 0,
            runtime = LlmRuntime.CLOUD,
            cloudProvider = provider,
            cloudApiKey = apiKey,
            cloudBaseUrl = baseUrl,
        )
        val request = requestBuilder(config, modelsUrl(config)).get().build()
        val call = client.newCall(request)
        currentCoroutineContext().job.invokeOnCompletion { cause ->
            if (cause != null) call.cancel()
        }
        try {
            call.execute().use { response ->
                val body = response.body.string()
                if (!response.isSuccessful) throw httpFailure(response.code, body.take(MAX_ERROR_CHARS))
                modelInfos(body, provider)
            }
        } catch (failure: IOException) {
            if (call.isCanceled()) {
                throw CancellationException("Model request cancelled").also { it.initCause(failure) }
            }
            throw LlmEngineException(
                LlmError.NETWORK,
                "The provider model list could not be reached",
                failure,
            )
        }
    }

    fun cancel() {
        activeCall.get()?.cancel()
    }

    companion object {
        private const val DEEPINFRA_CHAT = "https://api.deepinfra.com/v1/openai/chat/completions"
        private const val DEEPINFRA_MODELS = "https://api.deepinfra.com/v1/models"
        private const val OPENROUTER_CHAT = "https://openrouter.ai/api/v1/chat/completions"
        private const val OPENROUTER_MODELS = "https://openrouter.ai/api/v1/models"
        private const val OPENROUTER_REFERER = "https://fancyai-os.com"
        private const val OPENROUTER_TITLE = "Fancy AI"
        private const val SSE_DATA_PREFIX = "data:"
        private const val SSE_DONE = "[DONE]"
        private const val MAX_ERROR_CHARS = 600
        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
        private val PORTABLE_GENERATION_FIELDS = setOf(
            "temperature",
            "top_p",
            "max_tokens",
            "presence_penalty",
            "frequency_penalty",
        )
        private val DEEPINFRA_GENERATION_FIELDS = PORTABLE_GENERATION_FIELDS + setOf(
            "top_k",
            "min_p",
            "repetition_penalty",
        )
        private val GENERATION_FIELDS = DEEPINFRA_GENERATION_FIELDS + "max_completion_tokens"
        internal fun generationParameters(
            provider: CloudProvider,
            reported: Set<String>? = null,
        ): Set<String> = when (provider) {
            CloudProvider.DEEPINFRA -> DEEPINFRA_GENERATION_FIELDS
            CloudProvider.OPENROUTER -> reported.orEmpty().intersect(GENERATION_FIELDS)
            CloudProvider.CUSTOM -> PORTABLE_GENERATION_FIELDS
        }

        internal fun modelInfos(body: String, provider: CloudProvider): List<CloudModelInfo> {
            val root = runCatching { Json.parseToJsonElement(body) }
                .getOrElse { throw LlmEngineException(LlmError.NETWORK, "Invalid model list", it) }
            val models = when (root) {
                is JsonArray -> root.jsonArray
                is JsonObject -> (root.jsonObject["data"] ?: root.jsonObject["models"])
                    ?.takeIf { it is JsonArray }?.jsonArray
                else -> null
            } ?: throw LlmEngineException(LlmError.NETWORK, "Invalid model list")
            return models.asSequence()
                .mapNotNull { item ->
                    (item as? JsonObject)?.let { model ->
                        if (!isChatModel(model, provider)) return@mapNotNull null
                        val id = model.string("id") ?: model.string("model_id") ?: model.string("model_name")
                        id?.trim()?.removePrefix("models/")?.takeIf(String::isNotEmpty)?.let { cleanId ->
                            val metadata = model["metadata"]?.takeIf { it is JsonObject }?.jsonObject
                            val (inputPrice, outputPrice) = parsePricing(model, metadata, provider)
                            CloudModelInfo(
                                id = cleanId,
                                supportsVision = parseVisionSupport(model, metadata, provider),
                                supportedParameters = (model["supported_parameters"] as? JsonArray)
                                    ?.asSequence()
                                    ?.mapNotNull { parameter ->
                                        (parameter as? JsonPrimitive)?.contentOrNull
                                    }
                                    ?.toSet()
                                    .orEmpty(),
                                contextLength = when (provider) {
                                    CloudProvider.OPENROUTER -> model.intFieldOrNull("context_length")
                                    CloudProvider.DEEPINFRA -> metadata?.intFieldOrNull("context_length")
                                    CloudProvider.CUSTOM -> null
                                },
                                priceInput = inputPrice,
                                priceOutput = outputPrice,
                            )
                        }
                    }
                }
                .distinctBy(CloudModelInfo::id)
                .sortedBy(CloudModelInfo::id)
                .toList()
        }

        private fun parseVisionSupport(model: JsonObject, metadata: JsonObject?, provider: CloudProvider): Boolean =
            when (provider) {
                CloudProvider.OPENROUTER -> model["architecture"]?.takeIf { it is JsonObject }
                    ?.jsonObject?.get("input_modalities")?.takeIf { it is JsonArray }
                    ?.jsonArray?.contains(JsonPrimitive("image")) == true
                CloudProvider.DEEPINFRA -> metadata
                    ?.get("tags")?.takeIf { it is JsonArray }
                    ?.jsonArray?.any { it == JsonPrimitive("vision") || it == JsonPrimitive("vlm") } == true
                CloudProvider.CUSTOM -> false
            }

        private fun parsePricing(model: JsonObject, metadata: JsonObject?, provider: CloudProvider): Pair<Double?, Double?> =
            when (provider) {
                CloudProvider.OPENROUTER -> {
                    val pricing = model["pricing"]?.takeIf { it is JsonObject }?.jsonObject
                    (pricing?.doubleFieldOrNull("prompt")?.let { it * 1_000_000 }) to
                        (pricing?.doubleFieldOrNull("completion")?.let { it * 1_000_000 })
                }
                CloudProvider.DEEPINFRA -> {
                    val pricing = metadata?.get("pricing")?.takeIf { it is JsonObject }?.jsonObject
                    pricing?.doubleFieldOrNull("input_tokens") to pricing?.doubleFieldOrNull("output_tokens")
                }
                CloudProvider.CUSTOM -> null to null
            }

        private fun isChatModel(model: JsonObject, provider: CloudProvider): Boolean = when (provider) {
            CloudProvider.OPENROUTER -> {
                val outputs = model["architecture"]?.takeIf { it is JsonObject }
                    ?.jsonObject?.get("output_modalities")?.takeIf { it is JsonArray }?.jsonArray
                outputs?.size == 1 && outputs[0] == JsonPrimitive("text")
            }
            CloudProvider.DEEPINFRA -> model["metadata"]?.takeIf { it is JsonObject }
                ?.jsonObject?.get("tags")?.takeIf { it is JsonArray }
                ?.jsonArray?.contains(JsonPrimitive("chat")) == true
            CloudProvider.CUSTOM -> true
        }

        internal fun customChatUrl(baseUrl: String): String {
            val base = requiredBaseUrl(baseUrl).trimEnd('/')
            return when {
                base.endsWith("/chat/completions") || base.endsWith("/generate") -> base
                base.endsWith("/v1") -> "$base/chat/completions"
                else -> "$base/v1/chat/completions"
            }
        }

        internal fun customModelsUrl(baseUrl: String): String {
            val base = requiredBaseUrl(baseUrl)
                .removeSuffix("/chat/completions")
                .removeSuffix("/generate")
                .trimEnd('/')
            return if (base.endsWith("/v1")) "$base/models" else "$base/v1/models"
        }

        internal fun requestPayload(
            config: LlmSessionConfig,
            input: LlmInput,
            thinking: Boolean,
            filesDir: File? = null,
        ): JsonObject {
            val messages = CloudTranscript.messagePayloads(config, input) { path -> encodeImage(path, filesDir) }
            val payload = buildJsonObject {
                put("model", config.cloudModel.trim().removePrefix("models/"))
                put("messages", messages)
                put("stream", true)
                if (config.cloudStructuredOutput && input.responseSchema.isNotBlank()) {
                    put("response_format", buildJsonObject {
                        put("type", "json_schema")
                        put("json_schema", buildJsonObject {
                            put("name", "character_output")
                            put("strict", true)
                            put("schema", Json.parseToJsonElement(input.responseSchema))
                        })
                    })
                }
                config.cloudGenerationParameters.forEach { (name, value) ->
                    if ((name == "top_k") || (name == "max_tokens") || (name == "max_completion_tokens")) {
                        put(name, value.toInt())
                    } else {
                        put(name, value.toFloat())
                    }
                }
                when (config.cloudProvider) {
                    CloudProvider.DEEPINFRA ->
                        put("reasoning_effort", if (thinking) "medium" else "none")
                    CloudProvider.OPENROUTER -> {
                        put(
                            "provider",
                            buildJsonObject { put("require_parameters", true) },
                        )
                        put(
                            "reasoning",
                            buildJsonObject { put("effort", if (thinking) "medium" else "none") },
                        )
                    }
                    CloudProvider.CUSTOM -> {
                        put("reasoning_effort", if (thinking) "medium" else "none")
                        put("chat_template_kwargs", buildJsonObject { put("enable_thinking", thinking) })
                    }
                }
            }
            return payload
        }

        private fun encodeImage(path: String, baseDir: File?): String {
            val file = File(path).let { if (it.isAbsolute || (baseDir == null)) it else File(baseDir, path) }
            val bytes = file.readBytes()
            val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
            val mimeType = options.outMimeType
                ?: if (bytes.size >= 12 && bytes[0] == 'R'.code.toByte() && bytes[1] == 'I'.code.toByte() && bytes[2] == 'F'.code.toByte() && bytes[3] == 'F'.code.toByte() && bytes[8] == 'W'.code.toByte() && bytes[9] == 'E'.code.toByte() && bytes[10] == 'B'.code.toByte() && bytes[11] == 'P'.code.toByte()) "image/webp"
                else throw IOException("Unrecognized image format")
            val base64 = Base64.encodeToString(bytes, Base64.NO_WRAP)
            return "data:$mimeType;base64,$base64"
        }

        internal fun requestBuilder(config: LlmSessionConfig, url: String): Request.Builder {
            val endpoint = routePrivateHttp(url)
            return Request.Builder().url(endpoint.url).apply {
                endpoint.hostHeader?.let { header("Host", it) }
                if (config.cloudApiKey.isNotBlank()) {
                    header("Authorization", "Bearer ${config.cloudApiKey.trim()}")
                }
                if (config.cloudProvider == CloudProvider.OPENROUTER) {
                    header("HTTP-Referer", OPENROUTER_REFERER)
                    header("X-OpenRouter-Title", OPENROUTER_TITLE)
                }
            }
        }

        internal fun chatUrl(config: LlmSessionConfig): String = when (config.cloudProvider) {
            CloudProvider.DEEPINFRA -> DEEPINFRA_CHAT
            CloudProvider.OPENROUTER -> OPENROUTER_CHAT
            CloudProvider.CUSTOM -> customChatUrl(config.cloudBaseUrl)
        }

        internal fun modelsUrl(config: LlmSessionConfig): String = when (config.cloudProvider) {
            CloudProvider.DEEPINFRA -> DEEPINFRA_MODELS
            CloudProvider.OPENROUTER -> OPENROUTER_MODELS
            CloudProvider.CUSTOM -> customModelsUrl(config.cloudBaseUrl)
        }

        internal fun contentText(content: JsonElement?, fallbackKey: String = "content"): String = when (content) {
            null, JsonNull -> ""
            is JsonPrimitive -> content.content
            is JsonArray -> content.asSequence().mapNotNull { part ->
                (part as? JsonObject)?.let { item ->
                    item.string("text") ?: item.string(fallbackKey)
                }
            }.joinToString(separator = "")
            else -> ""
        }

        internal fun reasoningText(delta: JsonObject): String =
            (delta.string("reasoning_content") ?: delta.string("reasoning"))
                ?.takeIf(String::isNotEmpty)
                ?: contentText(delta["reasoning_details"] as? JsonArray, "summary")

        internal fun providerError(frame: JsonObject): String? {
            val error = frame["error"]?.takeUnless { it == JsonNull } ?: return null
            return when (error) {
                is JsonPrimitive -> error.content
                is JsonObject -> error.string("message") ?: error.toString()
                else -> error.toString()
            }.take(MAX_ERROR_CHARS)
        }

        internal fun httpFailure(code: Int, detail: String): LlmEngineException {
            val message = providerError(
                runCatching { Json.parseToJsonElement(detail).jsonObject }.getOrDefault(JsonObject(emptyMap())),
            ) ?: detail.takeIf(String::isNotBlank) ?: "Cloud request failed with HTTP $code"
            return LlmEngineException(classifyProviderFailure(code, message), message)
        }

        internal fun classifyProviderFailure(code: Int, detail: String): LlmError {
            val lower = detail.lowercase()
            return when (code) {
                401, 403 -> LlmError.AUTHENTICATION
                429 -> LlmError.RATE_LIMIT
                else -> if (
                    ("context length" in lower) || ("context_length" in lower) ||
                    ("maximum context" in lower) || ("too many tokens" in lower)
                ) {
                    LlmError.CONTEXT_EXHAUSTED
                } else if (
                    ("rate limit" in lower) || ("rate_limit" in lower) ||
                    ("too many requests" in lower) || ("rate_limit_error" in lower)
                ) {
                    LlmError.RATE_LIMIT
                } else {
                    LlmError.NETWORK
                }
            }
        }

        internal fun JsonObject.string(name: String): String? = (this[name] as? JsonPrimitive)?.contentOrNull

        internal fun JsonObject.intFieldOrNull(name: String): Int? = (this[name] as? JsonPrimitive)?.let {
            if (it is JsonNull) null
            else it.contentOrNull?.toDoubleOrNull()?.toInt()
        }

        internal fun JsonObject.doubleFieldOrNull(name: String): Double? = (this[name] as? JsonPrimitive)?.let {
            if (it is JsonNull) null
            else it.contentOrNull?.toDoubleOrNull()
        }
    }
}
