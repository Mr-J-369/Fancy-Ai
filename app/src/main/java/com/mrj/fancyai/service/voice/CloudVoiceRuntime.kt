package com.mrj.fancyai.service.voice

import com.mrj.fancyai.service.llm.CloudProvider
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.job
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.Call
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

internal enum class VoiceModelKind(internal val key: String) {
    STT("stt"),
    TTS("tts"),
}

@Serializable
internal data class CloudVoiceModel(
    val id: String,
    val voices: List<String> = emptyList(),
)

internal enum class CloudVoiceFailure {
    AUTHENTICATION,
    RATE_LIMIT,
    NETWORK,
    PROVIDER,
    INVALID_RESPONSE,
}

internal class CloudVoiceException(
    val failure: CloudVoiceFailure,
    cause: Throwable? = null,
) : Exception(failure.name, cause)

internal class CloudVoiceRuntime {
    private val activeCall = AtomicReference<Call?>()
    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()

    suspend fun fetchModels(
        provider: CloudProvider,
        apiKey: String,
        kind: VoiceModelKind,
    ): List<CloudVoiceModel> = withContext(Dispatchers.IO) {
        val url = when (provider) {
            CloudProvider.DEEPINFRA -> DEEPINFRA_MODELS
            CloudProvider.OPENROUTER -> when (kind) {
                VoiceModelKind.STT -> "$OPENROUTER_MODELS?output_modalities=transcription"
                VoiceModelKind.TTS -> "$OPENROUTER_MODELS?output_modalities=speech"
            }
            CloudProvider.CUSTOM -> error("Custom voice providers are unsupported")
        }
        val body = execute(request(provider, apiKey, url).get().build()) { it.string() }
        parseModels(body, provider, kind)
    }

    suspend fun transcribe(
        provider: CloudProvider,
        apiKey: String,
        model: String,
        wav: ByteArray,
    ): String = withContext(Dispatchers.IO) {
        val payload = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart(
                "file",
                "speech.wav",
                wav.toRequestBody(WAV_MEDIA_TYPE),
            )
            .addFormDataPart("model", model.trim().removePrefix("models/"))
            .addFormDataPart("response_format", "json")
            .build()
        val url = when (provider) {
            CloudProvider.DEEPINFRA -> DEEPINFRA_TRANSCRIPTIONS
            CloudProvider.OPENROUTER -> OPENROUTER_TRANSCRIPTIONS
            CloudProvider.CUSTOM -> error("Custom voice providers are unsupported")
        }
        val body = execute(request(provider, apiKey, url).post(payload).build()) { it.string() }
        val root = parseObject(body)
        root.string("text")?.trim()
            ?: throw CloudVoiceException(CloudVoiceFailure.INVALID_RESPONSE)
    }

    suspend fun synthesize(
        provider: CloudProvider,
        apiKey: String,
        model: String,
        voice: String,
        text: String,
    ): ByteArray = withContext(Dispatchers.IO) {
        val payload = buildJsonObject {
            put("model", model.trim().removePrefix("models/"))
            put("input", text)
            voice.trim().takeIf(String::isNotEmpty)?.let { put("voice", it) }
            put("response_format", "mp3")
        }
        val url = when (provider) {
            CloudProvider.DEEPINFRA -> DEEPINFRA_SPEECH
            CloudProvider.OPENROUTER -> OPENROUTER_SPEECH
            CloudProvider.CUSTOM -> error("Custom voice providers are unsupported")
        }
        execute(
            request(provider, apiKey, url)
                .post(payload.toString().toRequestBody(JSON_MEDIA_TYPE))
                .build(),
        ) { body ->
            val length = body.contentLength()
            if (length > MAX_AUDIO_BYTES) {
                throw CloudVoiceException(CloudVoiceFailure.INVALID_RESPONSE)
            }
            body.bytes().also { bytes ->
                if (bytes.isEmpty() || (bytes.size > MAX_AUDIO_BYTES)) {
                    throw CloudVoiceException(CloudVoiceFailure.INVALID_RESPONSE)
                }
            }
        }
    }

    fun cancel() {
        activeCall.getAndSet(null)?.cancel()
    }

    private fun request(
        provider: CloudProvider,
        apiKey: String,
        url: String,
    ): Request.Builder = Request.Builder()
        .url(url)
        .header("Authorization", "Bearer ${apiKey.trim()}")
        .apply {
            if (provider == CloudProvider.OPENROUTER) {
                header("HTTP-Referer", OPENROUTER_REFERER)
                header("X-OpenRouter-Title", OPENROUTER_TITLE)
            }
        }

    private suspend fun <T> execute(request: Request, read: (okhttp3.ResponseBody) -> T): T {
        val call = client.newCall(request)
        check(activeCall.compareAndSet(null, call))
        val cancellation = currentCoroutineContext().job.invokeOnCompletion { cause ->
            if (cause != null) call.cancel()
        }
        try {
            return call.execute().use { response ->
                if (!response.isSuccessful) {
                    val detail = response.body.string().take(MAX_ERROR_CHARS)
                    throw httpFailure(response.code, detail)
                }
                read(response.body)
            }
        } catch (failure: IOException) {
            if (call.isCanceled()) {
                throw CancellationException("Cloud voice request cancelled").also {
                    it.initCause(failure)
                }
            }
            throw CloudVoiceException(CloudVoiceFailure.NETWORK, failure)
        } finally {
            cancellation.dispose()
            activeCall.compareAndSet(call, null)
        }
    }

    private val json = Json { ignoreUnknownKeys = true }

    private fun parseModels(
        body: String,
        provider: CloudProvider,
        kind: VoiceModelKind,
    ): List<CloudVoiceModel> {
        val models = (parseObject(body)["data"] as? JsonArray)
            ?: throw CloudVoiceException(CloudVoiceFailure.INVALID_RESPONSE)
        return models.asSequence()
            .mapNotNull { element ->
                val model = element as? JsonObject ?: return@mapNotNull null
                val id = model.string("id")?.trim()?.removePrefix("models/")
                    ?.takeIf(String::isNotEmpty) ?: return@mapNotNull null
                if (!supports(model, provider, kind)) return@mapNotNull null
                CloudVoiceModel(
                    id = id,
                    voices = (model["supported_voices"] as? JsonArray)
                        ?.asSequence()
                        ?.mapNotNull(::voiceId)
                        ?.distinct()
                        ?.sortedWith(String.CASE_INSENSITIVE_ORDER)
                        ?.toList()
                        .orEmpty(),
                )
            }
            .distinctBy(CloudVoiceModel::id)
            .sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER, CloudVoiceModel::id))
            .toList()
    }

    private fun supports(
        model: JsonObject,
        provider: CloudProvider,
        kind: VoiceModelKind,
    ): Boolean = when (provider) {
        CloudProvider.DEEPINFRA -> {
            val metadata = model["metadata"] as? JsonObject
            val tags = metadata?.get("tags") as? JsonArray
            tags?.any { element ->
                runCatching { element.jsonPrimitive.content }.getOrNull()
                    .equals(if (kind == VoiceModelKind.STT) "stt" else "tts", ignoreCase = true)
            } == true
        }
        CloudProvider.OPENROUTER -> true
        CloudProvider.CUSTOM -> false
    }

    private fun voiceId(element: JsonElement): String? = runCatching {
        if (element is JsonObject) {
            element.string("id") ?: element.string("name")
        } else {
            element.jsonPrimitive.content
        }
    }.getOrNull()?.trim()?.takeIf(String::isNotEmpty)

    private fun parseObject(body: String): JsonObject = runCatching {
        json.parseToJsonElement(body).jsonObject
    }.getOrElse {
        throw CloudVoiceException(CloudVoiceFailure.INVALID_RESPONSE, it)
    }

    private fun httpFailure(code: Int, detail: String): CloudVoiceException {
        val normalized = detail.lowercase()
        val failure = when (code) {
            401, 403 -> CloudVoiceFailure.AUTHENTICATION
            429 -> CloudVoiceFailure.RATE_LIMIT
            else -> if ("rate limit" in normalized) {
                CloudVoiceFailure.RATE_LIMIT
            } else {
                CloudVoiceFailure.PROVIDER
            }
        }
        return CloudVoiceException(failure)
    }

    private fun JsonObject.string(name: String): String? =
        this[name]?.let { runCatching { it.jsonPrimitive.content }.getOrNull() }

    companion object {
        private const val DEEPINFRA_MODELS = "https://api.deepinfra.com/v1/models"
        private const val DEEPINFRA_TRANSCRIPTIONS = "https://api.deepinfra.com/v1/audio/transcriptions"
        private const val DEEPINFRA_SPEECH = "https://api.deepinfra.com/v1/audio/speech"
        private const val OPENROUTER_MODELS = "https://openrouter.ai/api/v1/models"
        private const val OPENROUTER_TRANSCRIPTIONS = "https://openrouter.ai/api/v1/audio/transcriptions"
        private const val OPENROUTER_SPEECH = "https://openrouter.ai/api/v1/audio/speech"
        private const val OPENROUTER_REFERER = "https://fancyai-os.com"
        private const val OPENROUTER_TITLE = "Fancy AI"
        private const val MAX_ERROR_CHARS = 600
        private const val MAX_AUDIO_BYTES = 32 * 1024 * 1024
        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
        private val WAV_MEDIA_TYPE = "audio/wav".toMediaType()
    }
}
