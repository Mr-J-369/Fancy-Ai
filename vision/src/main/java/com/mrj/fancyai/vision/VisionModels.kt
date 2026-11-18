package com.mrj.fancyai.vision

import android.content.Context
import android.net.wifi.WifiManager
import android.os.PowerManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.Dispatcher
import okhttp3.Dns
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.RandomAccessFile
import java.net.Inet4Address
import java.net.Inet6Address
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlin.coroutines.CoroutineContext
import kotlin.time.Duration.Companion.milliseconds

data class VisionModel(val name: String, val path: String, val sizeBytes: Long)

class VisionModels(context: Context) {
    private val app = context.applicationContext
    private val directory = directory(context)
    private val bulkClient: OkHttpClient by lazy {
        val ipv4FirstDns = Dns { hostname ->
            val resolved = Dns.SYSTEM.lookup(hostname)
            val ipv4 = resolved.filterIsInstance<Inet4Address>()
            val ipv6 = resolved.filterIsInstance<Inet6Address>()
            if (ipv4.isEmpty() || ipv6.isEmpty()) resolved else ipv4 + ipv6
        }
        OkHttpClient.Builder()
            .dns(ipv4FirstDns)
            .fastFallback(true)
            .retryOnConnectionFailure(true)
            .protocols(listOf(Protocol.HTTP_1_1))
            .dispatcher(Dispatcher().apply { maxRequests = 32; maxRequestsPerHost = 16 })
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(45, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .build()
    }
    private val recommendedUrl =
        if (
            context.getSharedPreferences("app", Context.MODE_PRIVATE)
                .getBoolean("hf_mirror", false)
        ) {
            RECOMMENDED_URL.replaceFirst("https://huggingface.co/", "https://hf-mirror.com/")
        } else {
            RECOMMENDED_URL
        }

    fun installed(): List<VisionModel> {
        return directory.listFiles()
            .orEmpty()
            .asSequence()
            .filter { it.isFile && it.extension.equals(EXTENSION, ignoreCase = true) }
            .sortedBy { it.name.lowercase() }
            .map { VisionModel(it.name, it.absolutePath, it.length()) }
            .toList()
    }

    suspend fun import(
        displayName: String,
        input: InputStream,
        sizeBytes: Long,
        onProgress: (Long, Long) -> Unit,
    ): VisionModel = withContext(Dispatchers.IO) {
        val name = File(displayName).name
        directory.mkdirs()
        val target = File(directory, name)
        val temporary = File(directory, ".${UUID.randomUUID()}.$EXTENSION")
        try {
            FileOutputStream(temporary).use { output ->
                input.use { source ->
                    copy(
                        source,
                        output,
                        sizeBytes,
                        currentCoroutineContext(),
                        onProgress,
                    )
                }
                output.fd.sync()
            }
            Files.move(temporary.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE)
            VisionModel(target.name, target.absolutePath, target.length())
        } finally {
            temporary.delete()
        }
    }

    suspend fun downloadRecommended(onProgress: (Long, Long) -> Unit): VisionModel =
        withContext(Dispatchers.IO) {
            directory.mkdirs()
            val target = File(directory, RECOMMENDED_FILE_NAME)
            download(target, onProgress)
            VisionModel(target.name, target.absolutePath, target.length())
        }

    private suspend fun download(target: File, onProgress: (Long, Long) -> Unit) =
        withContext(Dispatchers.IO) {
            val partial = File(target.parentFile, ".${target.name}.download")
            val stateFile = File(target.parentFile, ".${target.name}.download.state")
            val cancelled = AtomicBoolean(false)
            val activeCalls = ConcurrentHashMap.newKeySet<Call>()
            val registration = currentCoroutineContext()[Job]?.invokeOnCompletion { cause ->
                if (cause != null) {
                    cancelled.set(true)
                    activeCalls.forEach { it.cancel() }
                }
            }
            val powerManager = app.getSystemService(PowerManager::class.java)
            val wakeLock = runCatching {
                powerManager?.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "FancyAI:VisionDownload")
                    ?.apply { acquire(WAKELOCK_TIMEOUT_MS) }
            }.getOrNull()
            val wifiManager = app.getSystemService(WifiManager::class.java)
            val wifiLock = runCatching {
                wifiManager?.createWifiLock(WifiManager.WIFI_MODE_FULL_LOW_LATENCY, "FancyAI:VisionDownload")
                    ?.apply { setReferenceCounted(false); acquire() }
            }.getOrNull()

            try {
                var lastFailure: Exception? = null
                for (attempt in 0 until MAX_ATTEMPTS) {
                    currentCoroutineContext().ensureActive()
                    if (cancelled.get()) throw CancellationException("Download cancelled")
                    try {
                        executeDownloadAttempt(target, partial, stateFile, cancelled, activeCalls, onProgress)
                        return@withContext
                    } catch (c: CancellationException) {
                        throw c
                    } catch (error: Exception) {
                        if (cancelled.get() || !currentCoroutineContext().isActive) {
                            throw CancellationException("Download cancelled").also { it.initCause(error) }
                        }
                        lastFailure = error
                        if (attempt + 1 < MAX_ATTEMPTS) {
                            delay((1_500L * (attempt + 1)).coerceAtMost(8_000L).milliseconds)
                        }
                    }
                }
                throw checkNotNull(lastFailure)
            } finally {
                registration?.dispose()
                runCatching { if (wifiLock?.isHeld == true) wifiLock.release() }
                runCatching { if (wakeLock?.isHeld == true) wakeLock.release() }
            }
        }

    private suspend fun executeDownloadAttempt(
        target: File,
        partial: File,
        stateFile: File,
        cancelled: AtomicBoolean,
        activeCalls: MutableSet<Call>,
        onProgress: (Long, Long) -> Unit,
    ) {
        val probe = buildRequest(rangeHeader = "bytes=0-0")
        val probeCall = bulkClient.newCall(probe)
        activeCalls.add(probeCall)
        var remoteTotal = 0L
        var useParallel = false
        var handledInline = false

        try {
            probeCall.execute().use { response ->
                when (response.code) {
                    206 -> {
                        remoteTotal = parseTotal(response.header("Content-Range"))
                        if (remoteTotal >= MIN_PARALLEL_BYTES) useParallel = true
                    }
                    200 -> {
                        stateFile.delete()
                        val body = response.body
                        val total = body.contentLength().takeIf { it > 0L } ?: RECOMMENDED_SIZE_BYTES
                        streamToPartial(body.byteStream(), partial, 0L, total, cancelled, onProgress)
                        moveIntoPlace(partial, target)
                        handledInline = true
                    }
                    else -> throw IOException("Download failed with HTTP ${response.code}")
                }
            }
        } finally {
            activeCalls.remove(probeCall)
        }

        if (handledInline) return

        if (useParallel) {
            downloadParallel(target, partial, stateFile, remoteTotal, cancelled, activeCalls, onProgress)
        } else {
            downloadSingleResuming(
                target,
                partial,
                stateFile,
                if (remoteTotal > 0L) remoteTotal else RECOMMENDED_SIZE_BYTES,
                cancelled,
                activeCalls,
                onProgress,
            )
        }
    }

    private suspend fun downloadParallel(
        target: File,
        partial: File,
        stateFile: File,
        totalBytes: Long,
        cancelled: AtomicBoolean,
        activeCalls: MutableSet<Call>,
        onProgress: (Long, Long) -> Unit,
    ) {
        val chunkCount = ((totalBytes + CHUNK_BYTES - 1L) / CHUNK_BYTES).toInt()
        val workers = WORKERS.coerceAtMost(chunkCount)
        val completed = loadOrInitState(partial, stateFile, totalBytes, chunkCount)
        val initialBytes = (0 until chunkCount).sumOf { idx ->
            if (completed[idx]) chunkLen(idx, chunkCount, totalBytes) else 0L
        }
        val downloadedBytes = AtomicLong(initialBytes)
        val lastReportMs = AtomicLong(0L)
        val nextChunk = AtomicInteger(0)
        val stateLock = Any()

        onProgress(initialBytes, totalBytes)

        withContext(Dispatchers.IO) {
            RandomAccessFile(partial, "rw").use { dataRaf ->
                dataRaf.channel.use { channel ->
                    RandomAccessFile(stateFile, "rw").use { stateRaf ->
                        coroutineScope {
                            repeat(workers) {
                                launch(Dispatchers.IO) {
                                    val buffer = ByteArray(STREAM_BUFFER_BYTES)
                                    val byteBuffer = ByteBuffer.wrap(buffer)
                                    while (true) {
                                        currentCoroutineContext().ensureActive()
                                        if (cancelled.get()) throw CancellationException("Download cancelled")
                                        var idx = nextChunk.getAndIncrement()
                                        while (idx < chunkCount && completed[idx]) {
                                            idx = nextChunk.getAndIncrement()
                                        }
                                        if (idx >= chunkCount) break
                                        val start = idx.toLong() * CHUNK_BYTES
                                        val expectedLen = chunkLen(idx, chunkCount, totalBytes)
                                        val endInclusive = start + expectedLen - 1L
                                        downloadChunk(
                                            start,
                                            endInclusive,
                                            expectedLen,
                                            channel,
                                            buffer,
                                            byteBuffer,
                                            totalBytes,
                                            downloadedBytes,
                                            lastReportMs,
                                            cancelled,
                                            activeCalls,
                                            onProgress,
                                        )
                                        synchronized(stateLock) {
                                            completed[idx] = true
                                            stateRaf.seek(STATE_HEADER_BYTES + idx)
                                            stateRaf.writeByte(1)
                                        }
                                    }
                                }
                            }
                        }
                        channel.force(true)
                    }
                }
            }
        }
        onProgress(totalBytes, totalBytes)
        stateFile.delete()
        moveIntoPlace(partial, target)
    }

    private suspend fun downloadChunk(
        start: Long,
        endInclusive: Long,
        expectedLen: Long,
        channel: FileChannel,
        buffer: ByteArray,
        byteBuffer: ByteBuffer,
        totalBytes: Long,
        downloadedBytes: AtomicLong,
        lastReportMs: AtomicLong,
        cancelled: AtomicBoolean,
        activeCalls: MutableSet<Call>,
        onProgress: (Long, Long) -> Unit,
    ) {
        var chunkFailure: Exception? = null
        for (attempt in 0 until 3) {
            currentCoroutineContext().ensureActive()
            if (cancelled.get()) throw CancellationException("Download cancelled")
            var written = 0L
            val call = bulkClient.newCall(buildRequest("bytes=$start-$endInclusive"))
            activeCalls.add(call)
            try {
                call.execute().use { response ->
                    if (response.code != 206 && !(response.code == 200 && start == 0L && expectedLen == totalBytes)) {
                        throw IOException("Chunk HTTP ${response.code}")
                    }
                    response.body.byteStream().use { input ->
                        var writePos = start
                        while (written < expectedLen) {
                            if (cancelled.get()) throw CancellationException("Download cancelled")
                            val maxRead = (expectedLen - written).coerceAtMost(buffer.size.toLong()).toInt()
                            val count = input.read(buffer, 0, maxRead)
                            if (count < 0) break
                            if (count == 0) continue
                            byteBuffer.limit(count)
                            byteBuffer.position(0)
                            while (byteBuffer.hasRemaining()) {
                                writePos += channel.write(byteBuffer, writePos)
                            }
                            written += count
                            val totalNow = downloadedBytes.addAndGet(count.toLong())
                            val now = System.currentTimeMillis()
                            val prev = lastReportMs.get()
                            if (now - prev >= PROGRESS_INTERVAL_MS && lastReportMs.compareAndSet(prev, now)) {
                                onProgress(totalNow.coerceAtMost(totalBytes), totalBytes)
                            }
                        }
                    }
                }
                if (written != expectedLen) throw IOException("Truncated chunk: $written/$expectedLen")
                return
            } catch (c: CancellationException) {
                downloadedBytes.addAndGet(-written)
                throw c
            } catch (error: Exception) {
                downloadedBytes.addAndGet(-written)
                if (cancelled.get()) throw CancellationException("Download cancelled").also { it.initCause(error) }
                chunkFailure = error
                if (attempt + 1 < 3) delay((1_000L * (attempt + 1)).milliseconds)
            } finally {
                activeCalls.remove(call)
            }
        }
        throw checkNotNull(chunkFailure)
    }

    private fun downloadSingleResuming(
        target: File,
        partial: File,
        stateFile: File,
        expectedSize: Long,
        cancelled: AtomicBoolean,
        activeCalls: MutableSet<Call>,
        onProgress: (Long, Long) -> Unit,
    ) {
        if (stateFile.exists()) {
            stateFile.delete()
            partial.delete()
        }
        val existing = partial.length().takeIf { it in 1L..<maxOf(2L, expectedSize) } ?: 0L
        val call = bulkClient.newCall(buildRequest(if (existing > 0L) "bytes=$existing-" else null))
        activeCalls.add(call)
        try {
            call.execute().use { response ->
                when (response.code) {
                    206 -> {
                        val contentLen = response.body.contentLength()
                        val total = if (contentLen > 0L) existing + contentLen else expectedSize
                        streamToPartial(response.body.byteStream(), partial, existing, total, cancelled, onProgress)
                        moveIntoPlace(partial, target)
                    }
                    200 -> {
                        val total = response.body.contentLength().takeIf { it > 0L } ?: expectedSize
                        streamToPartial(response.body.byteStream(), partial, 0L, total, cancelled, onProgress)
                        moveIntoPlace(partial, target)
                    }
                    416 -> {
                        val rangeTotal = parseTotal(response.header("Content-Range"))
                        if (rangeTotal > 0L && partial.isFile && partial.length() == rangeTotal) {
                            onProgress(rangeTotal, rangeTotal)
                            moveIntoPlace(partial, target)
                        } else {
                            partial.delete()
                            throw IOException("Range mismatch (HTTP 416)")
                        }
                    }
                    else -> throw IOException("Download failed with HTTP ${response.code}")
                }
            }
        } finally {
            activeCalls.remove(call)
        }
    }

    private fun streamToPartial(
        input: InputStream,
        partial: File,
        startOffset: Long,
        totalBytes: Long,
        cancelled: AtomicBoolean,
        onProgress: (Long, Long) -> Unit,
    ) {
        RandomAccessFile(partial, "rw").use { output ->
            if (startOffset == 0L) output.setLength(0L)
            output.seek(startOffset)
            var copied = startOffset
            var lastReported = 0L
            onProgress(copied, totalBytes)
            val buffer = ByteArray(STREAM_BUFFER_BYTES)
            input.use { stream ->
                while (true) {
                    if (cancelled.get()) throw CancellationException("Download cancelled")
                    val count = stream.read(buffer)
                    if (count < 0) break
                    output.write(buffer, 0, count)
                    copied += count
                    val now = System.currentTimeMillis()
                    if (now - lastReported >= PROGRESS_INTERVAL_MS) {
                        lastReported = now
                        onProgress(copied, totalBytes)
                    }
                }
            }
            output.fd.sync()
            onProgress(copied, totalBytes)
        }
    }

    private fun loadOrInitState(
        partial: File,
        stateFile: File,
        totalBytes: Long,
        chunkCount: Int,
    ): BooleanArray {
        if (partial.isFile && partial.length() == totalBytes && stateFile.isFile &&
            stateFile.length() == STATE_HEADER_BYTES + chunkCount
        ) {
            val loaded = runCatching {
                RandomAccessFile(stateFile, "r").use { raf ->
                    if (raf.readInt() != STATE_MAGIC) return@use null
                    if (raf.readLong() != totalBytes) return@use null
                    if (raf.readLong() != CHUNK_BYTES) return@use null
                    val flags = ByteArray(chunkCount)
                    raf.readFully(flags)
                    BooleanArray(chunkCount) { flags[it].toInt() != 0 }
                }
            }.getOrNull()
            if (loaded != null) return loaded
        }
        RandomAccessFile(partial, "rw").use { it.setLength(totalBytes) }
        RandomAccessFile(stateFile, "rw").use { raf ->
            raf.setLength(0L)
            raf.writeInt(STATE_MAGIC)
            raf.writeLong(totalBytes)
            raf.writeLong(CHUNK_BYTES)
            raf.write(ByteArray(chunkCount))
            raf.fd.sync()
        }
        return BooleanArray(chunkCount)
    }

    private fun chunkLen(index: Int, chunkCount: Int, totalBytes: Long): Long {
        val start = index.toLong() * CHUNK_BYTES
        return if (index == chunkCount - 1) totalBytes - start else CHUNK_BYTES
    }

    private fun buildRequest(rangeHeader: String?): Request = Request.Builder()
        .url(recommendedUrl)
        .header("User-Agent", "FancyAI/1.0 (Android)")
        .header("Accept-Encoding", "identity")
        .apply { if (rangeHeader != null) header("Range", rangeHeader) }
        .get()
        .build()

    private fun parseTotal(header: String?): Long =
        header?.let { CONTENT_RANGE_TOTAL.find(it)?.groupValues?.getOrNull(1)?.toLongOrNull() } ?: 0L

    private fun moveIntoPlace(partial: File, target: File) {
        Files.move(
            partial.toPath(),
            target.toPath(),
            StandardCopyOption.ATOMIC_MOVE,
            StandardCopyOption.REPLACE_EXISTING,
        )
    }

    private fun copy(
        input: InputStream,
        output: FileOutputStream,
        total: Long,
        cancellation: CoroutineContext,
        onProgress: (Long, Long) -> Unit,
    ) {
        val buffer = ByteArray(COPY_BUFFER_BYTES)
        var copied = 0L
        onProgress(copied, total)
        while (true) {
            cancellation.ensureActive()
            val count = input.read(buffer)
            if (count < 0) break
            output.write(buffer, 0, count)
            copied += count
            onProgress(copied, total)
        }
    }

    companion object {
        const val RECOMMENDED_FILE_NAME = "InternVL3.5-1B.litertlm"
        const val RECOMMENDED_SIZE_BYTES = 817_517_936L
        private const val RECOMMENDED_REVISION = "0b5d2401e3f2ee38a7c5aaa9395c1987462b9657"
        private const val RECOMMENDED_URL =
            "https://huggingface.co/litert-community/InternVL3_5-1B/resolve/" +
                RECOMMENDED_REVISION + "/model.litertlm"

        fun directory(context: Context): File {
            val models = context.getExternalFilesDir("models") ?: File(context.filesDir, "models")
            return File(models, "vision")
        }

        private const val EXTENSION = "litertlm"
        private const val COPY_BUFFER_BYTES = 1024 * 1024
        private const val STREAM_BUFFER_BYTES = 256 * 1024
        private const val MIN_PARALLEL_BYTES = 2L * 1024 * 1024
        private const val CHUNK_BYTES = 8L * 1024 * 1024
        private const val WORKERS = 6
        private const val MAX_ATTEMPTS = 5
        private const val PROGRESS_INTERVAL_MS = 150L
        private const val STATE_MAGIC = 0x46444C31
        private const val STATE_HEADER_BYTES = 20L
        private const val WAKELOCK_TIMEOUT_MS = 60 * 60_000L
        private val CONTENT_RANGE_TOTAL = Regex("""bytes\s+(?:\d+-\d+|\*)/(\d+)""", RegexOption.IGNORE_CASE)
    }
}
