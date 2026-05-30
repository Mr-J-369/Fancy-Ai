package com.mrj.fancyai.service

import android.util.Log
import com.google.gson.Gson
import com.google.gson.JsonObject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

/**
 * A single OpenAI-style chat message. [images] holds optional base64 data URLs
 * ("data:image/jpeg;base64,…"); when present the content is sent as a multimodal
 * parts array (text + image_url) for vision-capable models.
 */
data class ChatMessage(val role: String, val content: String, val images: List<String> = emptyList())

/**
 * Cloud / remote-HTTP LLM client. Talks to any OpenAI-compatible Chat Completions API:
 *  - deepinfra  → https://api.deepinfra.com/v1/openai/...
 *  - openrouter → https://openrouter.ai/api/v1/...
 *  - localllm   → a local OpenAI-compatible HTTP server (e.g. llama-server / LM Studio)
 *  - custom     → any user-supplied OpenAI-compatible endpoint
 *
 * This is the cloud counterpart to the on-device [com.mrj.fancyai.domain.inference.LlamaEngine];
 * a faithful port of the provider dispatch in the old js/core/api.js.
 */
class CloudLlmService {

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .build()
    private val gson = Gson()
    private val jsonMedia = "application/json".toMediaType()

    /** Fetches the available model ids for a cloud provider (DeepInfra / OpenRouter). */
    suspend fun fetchModels(provider: String, apiKey: String): Result<List<String>> =
        withContext(Dispatchers.IO) {
            val url = when (provider) {
                "deepinfra" -> "https://api.deepinfra.com/v1/openai/models"
                "openrouter" -> "https://openrouter.ai/api/v1/models"
                else -> return@withContext Result.failure(
                    Exception("Model listing isn't supported for '$provider'")
                )
            }
            runCatching {
                val builder = Request.Builder().url(url).get()
                if (apiKey.isNotBlank()) builder.header("Authorization", "Bearer $apiKey")
                client.newCall(builder.build()).execute().use { resp ->
                    if (!resp.isSuccessful) {
                        val detail = runCatching { resp.body?.string()?.take(200) }.getOrNull()
                        throw Exception("HTTP ${resp.code}${detail?.let { " — $it" } ?: ""}")
                    }
                    val body = resp.body?.string() ?: throw Exception("Empty response")
                    val data = gson.fromJson(body, JsonObject::class.java).getAsJsonArray("data")
                        ?: throw Exception("Unexpected response shape (no 'data')")
                    data.mapNotNull { it.asJsonObject.get("id")?.asString }.sorted()
                }
            }
        }

    /**
     * Streams a chat completion token-by-token. Errors are surfaced as a thrown exception
     * in the flow so callers can show them.
     */
    fun chatStream(
        provider: String,
        apiKey: String,
        baseUrl: String,
        model: String,
        messages: List<ChatMessage>,
        temperature: Float,
        maxTokens: Int
    ): Flow<String> = flow {
        val endpoint = endpointFor(provider, baseUrl)
        val payload = mapOf(
            "model" to model.ifBlank { "meta-llama/Llama-3-70b-chat" },
            "messages" to messages.map { msg ->
                val content: Any = if (msg.images.isEmpty()) {
                    // Plain-text content keeps compatibility with non-vision servers.
                    msg.content
                } else {
                    // Multimodal content array: text part (if any) + one image_url part each.
                    buildList {
                        if (msg.content.isNotBlank()) add(mapOf("type" to "text", "text" to msg.content))
                        msg.images.forEach { url ->
                            add(mapOf("type" to "image_url", "image_url" to mapOf("url" to url)))
                        }
                    }
                }
                mapOf("role" to msg.role, "content" to content)
            },
            "temperature" to temperature,
            "max_tokens" to maxTokens,
            "stream" to true
        )
        val body = gson.toJson(payload).toRequestBody(jsonMedia)
        val builder = Request.Builder().url(endpoint).post(body)
        if (apiKey.isNotBlank()) builder.header("Authorization", "Bearer $apiKey")
        if (provider == "openrouter") {
            builder.header("HTTP-Referer", "https://fancy-ai.os")
            builder.header("X-Title", "Fancy AI")
        }

        client.newCall(builder.build()).execute().use { resp ->
            if (!resp.isSuccessful) {
                val detail = runCatching { resp.body?.string()?.take(300) }.getOrNull()
                throw Exception("LLM error: HTTP ${resp.code}${detail?.let { " — $it" } ?: ""}")
            }
            val source = resp.body?.source() ?: throw Exception("Empty LLM response")
            while (!source.exhausted()) {
                val line = source.readUtf8Line() ?: break
                if (!line.startsWith("data:")) continue
                val data = line.removePrefix("data:").trim()
                if (data.isEmpty()) continue
                if (data == "[DONE]") break
                val token = runCatching {
                    val delta = gson.fromJson(data, JsonObject::class.java)
                        .getAsJsonArray("choices")?.firstOrNull()?.asJsonObject
                        ?.getAsJsonObject("delta")?.get("content")
                    if (delta != null && !delta.isJsonNull) delta.asString else null
                }.onFailure { Log.w("CloudLlmService", "Bad SSE chunk: $data", it) }.getOrNull()
                if (!token.isNullOrEmpty()) emit(token)
            }
        }
    }.flowOn(Dispatchers.IO)

    private fun endpointFor(provider: String, baseUrl: String): String = when (provider) {
        "deepinfra" -> "https://api.deepinfra.com/v1/openai/chat/completions"
        "openrouter" -> "https://openrouter.ai/api/v1/chat/completions"
        "localllm" -> normalize(baseUrl.ifBlank { "http://127.0.0.1:8082" })
        "custom" -> normalize(baseUrl.ifBlank { "http://10.0.2.2:5000" })
        else -> throw Exception("Unknown provider: $provider")
    }

    /** Appends `/v1/chat/completions` unless the URL already targets a chat/generate path. */
    private fun normalize(url: String): String {
        val trimmed = url.trim().trimEnd('/')
        return if (trimmed.endsWith("/chat/completions") || trimmed.endsWith("/generate")) {
            trimmed
        } else {
            "$trimmed/v1/chat/completions"
        }
    }
}
