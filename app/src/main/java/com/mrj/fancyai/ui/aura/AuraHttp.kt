package com.mrj.fancyai.ui.aura

import androidx.annotation.StringRes
import com.mrj.fancyai.R
import com.mrj.fancyai.util.PrivateHttp
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.Call
import okhttp3.Callback
import okhttp3.Credentials
import okhttp3.Dns
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.IOException
import java.util.concurrent.TimeUnit

internal class AuraNetworkException(@param:StringRes val messageResource: Int, cause: Throwable? = null) : Exception(cause)

internal class AuraHttp(private val connection: AuraRemoteConnection) {
    suspend fun request(
        path: String,
        payload: JsonObject? = null,
        method: String = if (payload != null) "POST" else "GET",
        onEvent: ((JsonObject) -> Unit)? = null,
    ): String {
        val baseUrl = connection.url.trim().trimEnd('/')
        val requestUrl = if (path.startsWith("http://") || path.startsWith("https://")) path else "$baseUrl/${path.removePrefix("/")}"
        val endpoint = PrivateHttp.routePrivateHttp(requestUrl)
        val builder = Request.Builder().url(endpoint.url)
        if (onEvent != null) builder.header("Accept", "text/event-stream")
        endpoint.hostHeader?.let { builder.header("Host", it) }
        if (connection.username.isNotBlank() || connection.password.isNotBlank()) {
            builder.header("Authorization", Credentials.basic(connection.username, connection.password))
        }
        val body = payload?.toString()?.toRequestBody(JSON)
        when (method.uppercase()) {
            "POST" -> builder.post(body ?: "".toRequestBody(JSON))
            "DELETE" -> if (body != null) builder.delete(body) else builder.delete()
            else -> builder.get()
        }
        return suspendCancellableCoroutine { continuation ->
            val call = client.newCall(builder.build())
            continuation.invokeOnCancellation { call.cancel() }
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    continuation.resumeWith(Result.failure(AuraNetworkException(R.string.aura_remote_connection_failed, e)))
                }
                override fun onResponse(call: Call, response: Response) {
                    continuation.resumeWith(runCatching {
                        response.use {
                            if (!it.isSuccessful) throw AuraNetworkException(R.string.aura_remote_request_failed)
                            if (onEvent == null) it.body.string()
                            else {
                                val source = it.body.source()
                                var completed: String? = null
                                while (completed == null) {
                                    val line = source.readUtf8Line() ?: break
                                    if (!line.startsWith("data:")) continue
                                    val data = line.removePrefix("data:").trim()
                                    if (data == "[DONE]") break
                                    if (data.isEmpty()) continue
                                    val event = Json.parseToJsonElement(data).jsonObject
                                    when (event["type"]?.jsonPrimitive?.content) {
                                        "complete" -> completed = data
                                        "error" -> throw IOException(event["message"]?.jsonPrimitive?.content)
                                        else -> onEvent(event)
                                    }
                                }
                                completed ?: throw AuraNetworkException(R.string.aura_remote_request_failed)
                            }
                        }
                    })
                }
            })
        }
    }

    suspend fun discover(): WebUiCatalog {
        return WebUiCatalog(
            models = Json.parseToJsonElement(request("sdapi/v1/sd-models")).jsonArray.map { it.jsonObject.getValue("title").jsonPrimitive.content },
            samplers = Json.parseToJsonElement(request("sdapi/v1/samplers")).jsonArray.map { it.jsonObject.getValue("name").jsonPrimitive.content },
            schedulers = Json.parseToJsonElement(request("sdapi/v1/schedulers")).jsonArray.map {
                it.jsonObject.getValue("name").jsonPrimitive.content to it.jsonObject.getValue("label").jsonPrimitive.content
            },
            upscalers = Json.parseToJsonElement(request("sdapi/v1/upscalers")).jsonArray.map { it.jsonObject.getValue("name").jsonPrimitive.content },
            img2img = true,
            taskProgress = true,
        )
    }

    companion object {
        private val JSON = "application/json; charset=utf-8".toMediaType()
        private val client = OkHttpClient.Builder()
            .dns { hostname -> PrivateHttp.decodeLocalRoute(hostname)?.let(::listOf) ?: Dns.SYSTEM.lookup(hostname) }
            .connectTimeout(0, TimeUnit.MILLISECONDS)
            .readTimeout(0, TimeUnit.MILLISECONDS)
            .writeTimeout(0, TimeUnit.MILLISECONDS)
            .callTimeout(0, TimeUnit.MILLISECONDS)
            .retryOnConnectionFailure(false)
            .build()
    }
}
