package com.mrj.fancyai.ui.music

import android.util.Base64
import android.util.Log
import com.mrj.fancyai.service.llm.AssistantTurn
import com.mrj.fancyai.util.AppLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.job
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import okhttp3.Call
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okio.BufferedSource
import java.io.IOException
import java.util.Locale
import java.util.concurrent.CancellationException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

private val producerJson = Json { ignoreUnknownKeys = true; encodeDefaults = true; explicitNulls = false }

internal class RootProducerRuntime {
    private val activeCall = AtomicReference<Call?>()

    suspend fun generate(
        apiKey: String,
        tier: RootMusicTier,
        turn: AssistantTurn,
    ): RootMusicPayload = withContext(Dispatchers.IO) {
        val payload = buildJsonObject {
            put("model", tier.model)
            putJsonArray("modalities") {
                add(JsonPrimitive("text"))
                add(JsonPrimitive("audio"))
            }
            putJsonObject("audio") { put("format", "mp3") }
            put("stream", true)
            putJsonArray("messages") {
                if (turn.systemInstruction.isNotBlank()) {
                    add(buildJsonObject {
                        put("role", "system")
                        put("content", turn.systemInstruction)
                    })
                }
                add(buildJsonObject {
                    put("role", "user")
                    put("content", turn.input.textWithContext())
                })
            }
        }.toString()
        val call = client.newCall(
            Request.Builder()
                .url(OPENROUTER_CHAT)
                .header("Authorization", "Bearer ${apiKey.trim()}")
                .header("Accept", "text/event-stream")
                .header("HTTP-Referer", OPENROUTER_REFERER)
                .header("X-OpenRouter-Title", OPENROUTER_TITLE)
                .post(payload.toRequestBody(JSON_MEDIA_TYPE))
                .build(),
        )
        check(activeCall.compareAndSet(null, call))
        val cancellation = currentCoroutineContext().job.invokeOnCompletion { cause ->
            if (cause != null) call.cancel()
        }
        AppLog.write(Log.INFO, "Music", "Generation started tier=$tier")
        try {
            call.execute().use { response ->
                AppLog.write(Log.INFO, "Music", "HTTP status=${response.code}")
                if (!response.isSuccessful) {
                    val detail = response.body.string().take(MAX_ERROR_CHARS)
                    throw httpFailure(response.code, detail)
                }
                readStream(response.body.source()).also { AppLog.write(Log.INFO, "Music", "Generation completed") }
            }
        } catch (failure: IOException) {
            if (call.isCanceled()) {
                AppLog.write(Log.INFO, "Music", "Generation cancelled")
                throw CancellationException("Root Producer request cancelled").also {
                    it.initCause(failure)
                }
            }
            throw RootProducerException(RootProducerFailure.NETWORK, failure)
        } finally {
            cancellation.dispose()
            activeCall.compareAndSet(call, null)
        }
    }

    fun cancel() {
        activeCall.getAndSet(null)?.cancel()
    }

    private suspend fun readStream(source: BufferedSource): RootMusicPayload {
        val audio = StringBuilder()
        val providerText = StringBuilder()
        while (!source.exhausted()) {
            currentCoroutineContext().ensureActive()
            val line = source.readUtf8Line() ?: break
            if (!line.startsWith(SSE_DATA_PREFIX)) continue
            val data = line.removePrefix(SSE_DATA_PREFIX).trim()
            if (data.isEmpty()) continue
            if (data == SSE_DONE) break
            consumeRootFrame(data, audio, providerText)
        }
        if (audio.isEmpty()) throw RootProducerException(RootProducerFailure.INVALID_RESPONSE)
        val bytes = runCatching { Base64.decode(audio.toString(), Base64.DEFAULT) }
            .getOrElse {
                throw RootProducerException(RootProducerFailure.INVALID_RESPONSE, it)
            }
        val format = detectRootAudio(bytes)
            ?: throw RootProducerException(RootProducerFailure.INVALID_RESPONSE)
        return RootMusicPayload(bytes, providerText.toString().trim(), format)
    }

    private fun consumeRootFrame(data: String, audio: StringBuilder, providerText: StringBuilder) {
        val frame = runCatching { producerJson.parseToJsonElement(data).jsonObject }
            .getOrElse {
                throw RootProducerException(RootProducerFailure.INVALID_RESPONSE, it)
            }
        if (frame["error"] != null) throw RootProducerException(RootProducerFailure.PROVIDER)
        val choices = frame["choices"] as? JsonArray ?: return
        val choice = choices.firstOrNull() as? JsonObject ?: return
        val delta = choice["delta"] as? JsonObject ?: return
        val audioBlock = delta["audio"] as? JsonObject
        audio.append((audioBlock?.get("data") as? JsonPrimitive)?.content.orEmpty())
        providerText.append((audioBlock?.get("transcript") as? JsonPrimitive)?.content.orEmpty())
        when (val content = delta["content"]) {
            is JsonPrimitive -> providerText.append(content.content)
            is JsonArray -> content.forEach { part -> providerText.append(((part as? JsonObject)?.get("text") as? JsonPrimitive)?.content.orEmpty()) }
            else -> Unit
        }
        if (audio.length > MAX_AUDIO_BASE64_CHARS) throw RootProducerException(RootProducerFailure.INVALID_RESPONSE)
    }

    private fun httpFailure(code: Int, detail: String): RootProducerException {
        val normalized = detail.lowercase(Locale.ROOT)
        val failure = when (code) {
            401, 403 -> RootProducerFailure.AUTHENTICATION
            402 -> RootProducerFailure.PAYMENT
            429 -> RootProducerFailure.RATE_LIMIT
            else -> if ("rate limit" in normalized) {
                RootProducerFailure.RATE_LIMIT
            } else {
                RootProducerFailure.PROVIDER
            }
        }
        return RootProducerException(failure)
    }

    private companion object {
        val client = OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .readTimeout(8, TimeUnit.MINUTES)
            .build()
        const val OPENROUTER_CHAT = "https://openrouter.ai/api/v1/chat/completions"
        const val OPENROUTER_REFERER = "https://fancyai-os.com"
        const val OPENROUTER_TITLE = "Fancy AI"
        const val SSE_DATA_PREFIX = "data:"
        const val SSE_DONE = "[DONE]"
        const val MAX_ERROR_CHARS = 600
        const val MAX_AUDIO_BASE64_CHARS = 48 * 1024 * 1024
        val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
    }
}
