package com.mrj.fancyai.ui.aura

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.graphics.BitmapFactory
import android.os.IBinder
import android.os.PowerManager
import android.os.SystemClock
import android.util.Base64
import android.util.Log
import com.mrj.fancyai.R
import com.mrj.fancyai.service.ImageService
import com.mrj.fancyai.service.llm.MacroBus
import com.mrj.fancyai.util.AppLog
import com.mrj.fancyai.util.PrivateHttp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.HttpUrl.Companion.toHttpUrl
import java.io.BufferedInputStream
import java.io.Closeable
import java.io.DataInputStream
import java.io.File
import java.io.IOException
import java.net.Inet4Address
import java.net.InetAddress
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.SynchronousQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.cancel as cancelScope

internal fun auraLanAddress(context: Context): String =
    context.getSharedPreferences(AURA_PREFERENCES, Context.MODE_PRIVATE).getString("lan_address", "").orEmpty()

internal fun auraLanBase(address: String): String {
    val url = PrivateHttp.requiredBaseUrl(if ("://" in address) address else "http://${address.trim()}").toHttpUrl()
    require(url.scheme == "http" && url.username.isEmpty() && url.password.isEmpty() && url.query == null && url.fragment == null && url.encodedPath == "/")
    return url.toString().trimEnd('/')
}

internal fun validAuraLanAddress(address: String): Boolean = address.isNotBlank() && runCatching { auraLanBase(address) }.isSuccess

internal data class AuraLanGenerationConfig(
    val address: String,
    val includePromptPrefix: Boolean,
    override val metadata: AuraImageMetadata,
) : AuraGenerationConfig {
    override val seed: Long get() = 0L
}

internal fun auraLanGenerationConfig(context: Context, prompt: String, includePromptPrefix: Boolean): AuraLanGenerationConfig {
    val address = auraLanAddress(context)
    return AuraLanGenerationConfig(address, includePromptPrefix, AuraImageMetadata(MacroBus(context).text(prompt), "", 0, 0, 0f, 0, 0, "", "", "", "", "LAN", "", "", 0f))
}

internal suspend fun checkAuraLan(address: String): String {
    val status = Json.parseToJsonElement(AuraHttp(AuraRemoteConnection(AuraEngine.LAN, auraLanBase(address))).request("aura/v1/status")).jsonObject
    if (((status["protocol"] as? JsonPrimitive)?.intOrNull ?: 0) != 2 || ((status["instance"] as? JsonPrimitive)?.contentOrNull ?: "") == AuraLanHost.instance) {
        throw AuraNetworkException(R.string.aura_lan_wrong_host)
    }
    return status["instance"]!!.jsonPrimitive.content
}

private val auraJson = Json { ignoreUnknownKeys = true; encodeDefaults = true; explicitNulls = false }

internal suspend fun generateLanImage(config: AuraLanGenerationConfig, output: File, progress: (Int) -> Unit): AuraRenderedImage = withContext(Dispatchers.IO) {
    val http = AuraHttp(AuraRemoteConnection(AuraEngine.LAN, auraLanBase(config.address)))
    val instance = checkAuraLan(config.address)
    val path = "aura/v1/jobs/$instance/${UUID.randomUUID()}"
    var completed = false
    try {
        withTimeout(20.minutes) {
            val payload = buildJsonObject {
                put("prompt", config.metadata.prompt)
                put("include_prompt_prefix", config.includePromptPrefix)
            }
            var status = lanRequest { http.request(path, payload) }
            while (((status["state"] as? JsonPrimitive)?.contentOrNull ?: "") !in setOf("complete", "failed", "cancelled")) {
                progress(((status["progress"] as? JsonPrimitive)?.intOrNull ?: 0).coerceIn(0, 99))
                delay(750.milliseconds)
                status = lanRequest { http.request(path) }
            }
            if (((status["state"] as? JsonPrimitive)?.contentOrNull ?: "") != "complete") throw AuraNetworkException(R.string.aura_lan_generation_failed)
            val result = lanRequest { http.request("$path/result") }
            val bytes = Base64.decode(result["image"]!!.jsonPrimitive.content, Base64.NO_WRAP)
            require(bytes.size in 1..12 * 1024 * 1024)
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
            require(bounds.outWidth in 1..4096 && bounds.outHeight in 1..4096)
            val metadata = auraJson.decodeFromJsonElement<AuraImageMetadata>(result.getValue("metadata"))
                .copy(width = bounds.outWidth, height = bounds.outHeight)
            output.writeBytes(bytes)
            completed = true
            AuraRenderedImage(output, metadata)
        }
    } finally {
        if (!completed) withContext(NonCancellable) {
            runCatching { withTimeout(3.seconds) { http.request(path, method = "DELETE") } }
        }
    }
}

private suspend fun lanRequest(request: suspend () -> String): JsonObject {
    var last: AuraNetworkException? = null
    repeat(3) { attempt ->
        val response = try { Json.parseToJsonElement(request()).jsonObject } catch (failure: AuraNetworkException) {
            if (failure.messageResource != R.string.aura_remote_connection_failed) throw failure
            last = failure
            if (attempt < 2) delay(1.seconds)
            return@repeat
        }
        if (response.containsKey("error")) throw AuraNetworkException(when (response["error"]!!.jsonPrimitive.content) {
            "busy" -> R.string.aura_lan_busy
            "settings" -> R.string.aura_lan_settings_missing
            "missing", "stopped" -> R.string.aura_lan_job_missing
            else -> R.string.aura_lan_generation_failed
        })
        return response
    }
    throw checkNotNull(last)
}

internal data class AuraLanHostState(val address: String = "", val jobs: Int = 0, val error: Int? = null)

internal object AuraLanHost {
    private val mutableState = MutableStateFlow(AuraLanHostState())
    val state = mutableState.asStateFlow()
    var instance: String = UUID.randomUUID().toString()
        private set
    private var binding: Pair<Context, ServiceConnection>? = null
    private var server: AuraLanHttpServer? = null
    private var scope: CoroutineScope? = null
    private val requests = linkedMapOf<String, Request>()

    private class Request(
        val prompt: String,
        val includePromptPrefix: Boolean,
        val config: AuraLocalGenerationConfig,
    ) {
        var status = "queued"
        var progress = 0
        var file: File? = null
        var metadata: AuraImageMetadata? = null
        var error: String? = null
        var finishedAt: Long? = null
        var job: Job? = null
    }

    @Synchronized
    fun start(context: Context) {
        if (server != null) return
        val app = context.applicationContext
        try {
            auraLocalGenerationConfig(app, "", rollSeed = false)
            val addresses = NetworkInterface.getNetworkInterfaces().toList()
                .filter { it.isUp && !it.isLoopback }
                .sortedBy { if (it.name.startsWith("wlan") || it.name.startsWith("ap")) 0 else 1 }
                .flatMap { it.inetAddresses.toList() }
            val address = addresses.firstOrNull { it is Inet4Address && it.isSiteLocalAddress }
                ?: throw IllegalStateException("No LAN address")
            val connection = object : ServiceConnection {
                override fun onServiceConnected(name: ComponentName?, binder: IBinder?) = Unit
                override fun onServiceDisconnected(name: ComponentName?) = Unit
            }
            check(app.bindService(Intent(app, ImageService::class.java), connection, Context.BIND_AUTO_CREATE))
            binding = app to connection
            instance = UUID.randomUUID().toString()
            scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
            val listener = AuraLanHttpServer(address, 7861) { method, path, payload -> route(app, method, path, payload) }
            server = listener
            mutableState.value = AuraLanHostState("http://${address.hostAddress}:${listener.port}")
        } catch (failure: Exception) {
            binding?.let { (context, connection) -> context.unbindService(connection) }
            binding = null
            scope?.cancelScope()
            scope = null
            AppLog.write(Log.ERROR, "Aura LAN", "Could not start sharing", failure)
            mutableState.value = AuraLanHostState(error = R.string.aura_lan_start_failed)
        }
    }

    @Synchronized
    fun stop() {
        server?.close()
        server = null
        scope?.cancelScope()
        scope = null
        binding?.let { (context, connection) -> context.unbindService(connection) }
        binding = null
        requests.clear()
        mutableState.value = AuraLanHostState()
    }

    private fun route(app: Context, method: String, path: String, payload: JsonObject?): JsonObject {
        if (method == "GET" && path == "/aura/v1/status") {
            return buildJsonObject {
                put("protocol", 2)
                put("instance", instance)
            }
        }
        val parts = path.split('/').filter(String::isNotEmpty)
        if (parts.size !in 5..6 || parts.take(3) != listOf("aura", "v1", "jobs")) return error("invalid_request")
        if (parts[3] != instance) return error("missing")
        val id = parts[4]
        if (!Regex("[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}").matches(id)) return error("invalid_request")
        synchronized(this) {
            if (scope == null) return error("stopped")
            val now = SystemClock.elapsedRealtime()
            requests.entries.removeAll { it.value.finishedAt?.let { time -> now - time > 30 * 60_000 } == true }
            if (method == "POST" && parts.size == 5) return submit(app, id, payload)
            val existing = requests[id] ?: return error("missing")
            when (method to parts.getOrNull(5)) {
                "DELETE" to null -> {
                    existing.job?.cancel()
                    if (existing.finishedAt == null) {
                        existing.status = "cancelled"
                        existing.finishedAt = now
                        updateCount()
                    }
                    return status(id)
                }
                "GET" to null -> return status(id)
                "GET" to "result" -> if (existing.status != "complete") return error("invalid_request")
                else -> return error("invalid_request")
            }
        }
        return status(id, includeImage = true)
    }

    private fun submit(app: Context, id: String, payload: JsonObject?): JsonObject {
        val prompt = (payload?.get("prompt") as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull ?: return error("invalid_request")
        val includePromptPrefix = (payload["include_prompt_prefix"] as? JsonPrimitive)?.takeUnless { it.isString }?.booleanOrNull ?: return error("invalid_request")
        val existing = requests[id]
        if (existing != null) {
            return if (existing.prompt == prompt && existing.includePromptPrefix == includePromptPrefix) {
                status(id)
            } else error("conflict")
        }
        if (requests.size >= 128 || requests.values.count { it.finishedAt == null } >= 4) return error("busy")
        val config = auraLocalGenerationConfig(app, prompt, includePromptPrefix)
        val request = Request(prompt, includePromptPrefix, config)
        requests[id] = request
        request.job = scope?.launch(start = CoroutineStart.LAZY) { generate(app, request) }
        updateCount()
        request.job?.start()
        return status(id)
    }

    private suspend fun generate(app: Context, request: Request) {
        val wakeLock = app.getSystemService(PowerManager::class.java)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "FancyAI:AuraLanJob")
        try {
            wakeLock.acquire(20 * 60_000L)
            val file = withTimeout(20.minutes) {
                AuraImages.execute(app, settings = request.config,
                    onMetadata = { synchronized(this@AuraLanHost) { request.metadata = it } },
                    onProgress = { percent -> synchronized(this@AuraLanHost) {
                        request.status = "running"
                        request.progress = percent
                    } },
                )
            }
            synchronized(this) {
                if (request.status != "cancelled") {
                    request.file = file
                    request.status = "complete"
                    request.progress = 100
                }
            }
        } catch (cancelled: CancellationException) {
            synchronized(this) { request.status = "cancelled" }
            throw cancelled
        } catch (failure: Exception) {
            AppLog.write(Log.ERROR, "Aura LAN", "Host generation failed", failure)
            synchronized(this) { request.status = "failed"; request.error = "generation" }
        } finally {
            if (wakeLock.isHeld) wakeLock.release()
            synchronized(this) {
                request.finishedAt = SystemClock.elapsedRealtime()
                updateCount()
            }
        }
    }

    private fun updateCount() {
        mutableState.value = mutableState.value.copy(jobs = requests.values.count { it.finishedAt == null })
    }

    private fun status(id: String, includeImage: Boolean = false): JsonObject {
        val result = synchronized(this) {
            val request = requests[id] ?: return error("missing")
            if (!includeImage) return buildJsonObject {
                put("state", request.status)
                put("progress", request.progress)
                request.error?.let { put("error", it) }
            }
            request.file to request.metadata
        }
        val file = result.first?.takeIf { it.isFile && it.length() in 1..12L * 1024 * 1024 } ?: return error("missing")
        return buildJsonObject {
            put("image", Base64.encodeToString(file.readBytes(), Base64.NO_WRAP))
            result.second?.let { put("metadata", it.toJson()) }
        }
    }
    private fun error(code: String): JsonObject = buildJsonObject {
        put("error", code)
    }
}

internal class AuraLanHttpServer(
    address: InetAddress,
    port: Int,
    private val handle: (String, String, JsonObject?) -> JsonObject,
) : Closeable {
    private val listener = ServerSocket(port, 4, address)
    val port: Int get() = listener.localPort
    private val sockets = ConcurrentHashMap.newKeySet<Socket>()
    private val workers = ThreadPoolExecutor(0, 4, 10, TimeUnit.SECONDS, SynchronousQueue())
    private val acceptor = Thread({
        while (!listener.isClosed) {
            val socket = try { listener.accept() } catch (_: IOException) { break }
            sockets.add(socket)
            try { workers.execute { serve(socket) } }
            catch (_: RejectedExecutionException) { sockets.remove(socket); socket.close() }
        }
    }, "Aura LAN listener").apply { isDaemon = true; start() }

    private fun serve(socket: Socket) {
        try {
            socket.use {
                socket.soTimeout = 5_000
                val peer = socket.inetAddress
                require(peer.isSiteLocalAddress || peer.isLoopbackAddress || peer.isLinkLocalAddress)
                val input = DataInputStream(BufferedInputStream(socket.getInputStream()))
                var headerBytes = 0
                fun line(): String {
                    val bytes = ArrayList<Byte>()
                    while (true) {
                        require(++headerBytes <= 8192)
                        val byte = input.read()
                        require(byte >= 0)
                        if (byte == 13) { require(input.read() == 10); headerBytes++; break }
                        require(byte in 32..126 || byte == 9)
                        bytes.add(byte.toByte())
                    }
                    return bytes.toByteArray().toString(Charsets.US_ASCII)
                }
                val request = line().split(' ')
                require(request.size == 3)
                val (method, path, version) = request
                require(version == "HTTP/1.1")
                require(method in setOf("GET", "POST", "DELETE"))
                val headers = mutableMapOf<String, String>()
                while (true) {
                    val line = line()
                    if (line.isEmpty()) break
                    val key = line.substringBefore(':').lowercase()
                    require(':' in line && headers.put(key, line.substringAfter(':').trim()) == null)
                }
                require(setOf("origin", "transfer-encoding", "expect").none { it in headers })
                val host = headers["host"].orEmpty().substringBefore(':')
                require(host == socket.localAddress.hostAddress || (socket.localAddress.isLoopbackAddress && host == "localhost"))
                val length = headers["content-length"]?.toIntOrNull() ?: 0
                require(length in 0..16_384)
                val payload = if (method == "POST") {
                    require(length > 0 && headers["content-type"].orEmpty().substringBefore(';') == "application/json")
                    val body = ByteArray(length)
                    input.readFully(body)
                    Json.parseToJsonElement(body.toString(Charsets.UTF_8)).jsonObject
                } else { require(length == 0); null }
                val response = handle(method, path, payload).toString().toByteArray(Charsets.UTF_8)
                val output = socket.getOutputStream()
                output.write(("HTTP/1.1 200 OK\r\nContent-Type: application/json; charset=utf-8\r\n" +
                    "Content-Length: ${response.size}\r\nCache-Control: no-store\r\nConnection: close\r\n\r\n").toByteArray(Charsets.US_ASCII))
                output.write(response)
            }
        } catch (_: Exception) {
        } finally {
            sockets.remove(socket)
            runCatching { socket.close() }
        }
    }

    override fun close() {
        listener.close()
        sockets.forEach { runCatching { it.close() } }
        workers.shutdownNow()
        acceptor.interrupt()
    }
}
