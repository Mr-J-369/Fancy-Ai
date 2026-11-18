package com.mrj.fancyai.util

import android.content.Context
import android.net.wifi.WifiManager
import android.os.PowerManager
import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.Request
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import kotlin.time.Duration.Companion.milliseconds

private const val DOWNLOAD_TAG = "AppDownloader"
private const val USER_AGENT = "FancyAI/1.0 (Android)"
private const val MIN_PARALLEL_BYTES = 2L * 1024 * 1024
private const val SMALL_CHUNK_BYTES = 2L * 1024 * 1024
private const val LARGE_CHUNK_BYTES = 8L * 1024 * 1024
private const val LARGE_FILE_THRESHOLD = 16L * 1024 * 1024
private const val VERY_LARGE_FILE_THRESHOLD = 32L * 1024 * 1024
private const val STREAM_BUFFER_BYTES = 256 * 1024
private const val PROGRESS_INTERVAL_MS = 150L
private const val STATE_MAGIC = 0x46444C31 // "FDL1"
private const val STATE_HEADER_BYTES = 20L
private const val WAKELOCK_TIMEOUT_MS = 60 * 60_000L

private val NON_RETRYABLE_STATUS = setOf(400, 401, 403, 404, 410)
private val CONTENT_RANGE_TOTAL = Regex("""bytes\s+(?:\d+-\d+|\*)/(\d+)""", RegexOption.IGNORE_CASE)

private class NonRetryableHttpException(code: Int) : IOException("Download failed with HTTP $code")

/**
 * Downloads [url] to [target] using parallel HTTP Range streams when supported,
 * 5G-safe IPv4-preferred DNS, automatic retries with resume, and wake/Wi-Fi locks.
 */
internal suspend fun downloadFile(
    context: Context,
    url: String,
    target: File,
    expectedSize: Long = 0L,
    headers: Map<String, String> = emptyMap(),
    applyHfMirror: Boolean = true,
    attempts: Int = 5,
    activeCancel: AtomicReference<(() -> Unit)?>? = null,
    onProgress: (Long, Long) -> Unit = { _, _ -> },
) {
    val resolvedUrl = if (applyHfMirror) PrivateHttp.hfDownloadUrl(context, url) else url
    val parent = target.parentFile?.apply { mkdirs() }
    val partial = File(parent, ".${target.name}.download")
    val stateFile = File(parent, ".${target.name}.download.state")

    val cancelled = AtomicBoolean(false)
    val activeCalls = ConcurrentHashMap.newKeySet<Call>()
    val cancelAll: () -> Unit = {
        cancelled.set(true)
        activeCalls.forEach { it.cancel() }
    }
    activeCancel?.set(cancelAll)
    val registration = currentCoroutineContext()[Job]?.invokeOnCompletion { cause ->
        if (cause != null) cancelAll()
    }

    val powerManager = context.applicationContext.getSystemService(PowerManager::class.java)
    val wakeLock = runCatching {
        powerManager?.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "FancyAI:Download")
            ?.apply { acquire(WAKELOCK_TIMEOUT_MS) }
    }.getOrNull()
    val wifiManager = context.applicationContext.getSystemService(WifiManager::class.java)
    val wifiLock = runCatching {
        wifiManager?.createWifiLock(WifiManager.WIFI_MODE_FULL_LOW_LATENCY, "FancyAI:Download")
            ?.apply { setReferenceCounted(false); acquire() }
    }.getOrNull()

    try {
        withContext(Dispatchers.IO) {
            var lastFailure: Exception? = null
            for (attempt in 0 until attempts) {
                currentCoroutineContext().ensureActive()
                if (cancelled.get()) throw CancellationException("Download cancelled")
                try {
                    executeDownloadAttempt(
                        url = resolvedUrl,
                        target = target,
                        partial = partial,
                        stateFile = stateFile,
                        expectedSize = expectedSize,
                        headers = headers,
                        cancelled = cancelled,
                        activeCalls = activeCalls,
                        onProgress = onProgress,
                    )
                    return@withContext
                } catch (c: CancellationException) {
                    throw c
                } catch (fatal: NonRetryableHttpException) {
                    throw fatal
                } catch (error: Exception) {
                    if (cancelled.get() || (currentCoroutineContext()[Job]?.isActive == false)) {
                        throw CancellationException("Download cancelled").also { it.initCause(error) }
                    }
                    lastFailure = error
                    AppLog.write(
                        Log.WARN,
                        DOWNLOAD_TAG,
                        "Download failed for ${target.name} (attempt ${attempt + 1}/$attempts)",
                        error,
                    )
                    if (attempt + 1 < attempts) {
                        delay((1_500L * (attempt + 1)).coerceAtMost(8_000L).milliseconds)
                    }
                }
            }
            throw checkNotNull(lastFailure) { "Download failed for ${target.name}" }
        }
    } finally {
        registration?.dispose()
        activeCancel?.compareAndSet(cancelAll, null)
        runCatching { if (wifiLock?.isHeld == true) wifiLock.release() }
        runCatching { if (wakeLock?.isHeld == true) wakeLock.release() }
    }
}

private suspend fun executeDownloadAttempt(
    url: String,
    target: File,
    partial: File,
    stateFile: File,
    expectedSize: Long,
    headers: Map<String, String>,
    cancelled: AtomicBoolean,
    activeCalls: MutableSet<Call>,
    onProgress: (Long, Long) -> Unit,
) {
    val probeRequest = buildDownloadRequest(url, headers, rangeHeader = "bytes=0-0")
    val probeCall = PrivateHttp.bulkClient.newCall(probeRequest)
    activeCalls.add(probeCall)
    var remoteTotal = 0L
    var useParallel = false
    var handledInline = false

    try {
        probeCall.execute().use { response ->
            when (response.code) {
                206 -> {
                    remoteTotal = parseTotalFromContentRange(response.header("Content-Range"))
                    if (remoteTotal >= MIN_PARALLEL_BYTES) {
                        useParallel = true
                    }
                }
                200 -> {
                    stateFile.delete()
                    val body = response.body
                    val total = body.contentLength().takeIf { it > 0L } ?: expectedSize
                    streamBodyToFile(
                        input = body.byteStream(),
                        partial = partial,
                        startOffset = 0L,
                        totalBytes = total,
                        cancelled = cancelled,
                        onProgress = onProgress,
                    )
                    movePartialToTarget(partial, target)
                    handledInline = true
                }
                416 -> {
                    val rangeTotal = parseTotalFromContentRange(response.header("Content-Range"))
                    if (rangeTotal > 0L && partial.isFile && partial.length() == rangeTotal && !stateFile.exists()) {
                        onProgress(rangeTotal, rangeTotal)
                        movePartialToTarget(partial, target)
                        handledInline = true
                    } else {
                        partial.delete()
                        stateFile.delete()
                        throw IOException("Range mismatch (HTTP 416)")
                    }
                }
                in NON_RETRYABLE_STATUS -> throw NonRetryableHttpException(response.code)
                else -> throw IOException("Download failed with HTTP ${response.code}")
            }
        }
    } finally {
        activeCalls.remove(probeCall)
    }

    if (handledInline) return

    if (useParallel) {
        downloadParallelRanges(
            url = url,
            target = target,
            partial = partial,
            stateFile = stateFile,
            totalBytes = remoteTotal,
            headers = headers,
            cancelled = cancelled,
            activeCalls = activeCalls,
            onProgress = onProgress,
        )
    } else {
        downloadSingleResuming(
            url = url,
            target = target,
            partial = partial,
            stateFile = stateFile,
            expectedSize = if (remoteTotal > 0L) remoteTotal else expectedSize,
            headers = headers,
            cancelled = cancelled,
            activeCalls = activeCalls,
            onProgress = onProgress,
        )
    }
}

private suspend fun downloadParallelRanges(
    url: String,
    target: File,
    partial: File,
    stateFile: File,
    totalBytes: Long,
    headers: Map<String, String>,
    cancelled: AtomicBoolean,
    activeCalls: MutableSet<Call>,
    onProgress: (Long, Long) -> Unit,
) {
    stateFile.parentFile?.mkdirs()
    val chunkSize = if (totalBytes >= LARGE_FILE_THRESHOLD) LARGE_CHUNK_BYTES else SMALL_CHUNK_BYTES
    val chunkCount = ((totalBytes + chunkSize - 1L) / chunkSize).toInt()
    val workers = (if (totalBytes >= VERY_LARGE_FILE_THRESHOLD) 6 else 4).coerceAtMost(chunkCount)

    val completed = loadOrInitChunkState(partial, stateFile, totalBytes, chunkSize, chunkCount)
    val completedBytesInit = (0 until chunkCount).sumOf { idx ->
        if (completed[idx]) chunkLength(idx, chunkSize, chunkCount, totalBytes) else 0L
    }
    val downloadedBytes = AtomicLong(completedBytesInit)
    val lastReportMs = AtomicLong(0L)
    val nextChunk = AtomicInteger(0)
    val stateLock = Any()

    onProgress(completedBytesInit, totalBytes)

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

                                    val start = idx.toLong() * chunkSize
                                    val expectedLen = chunkLength(idx, chunkSize, chunkCount, totalBytes)
                                    val endInclusive = start + expectedLen - 1L

                                    downloadChunkWithRetry(
                                        url = url,
                                        headers = headers,
                                        start = start,
                                        endInclusive = endInclusive,
                                        expectedLen = expectedLen,
                                        channel = channel,
                                        buffer = buffer,
                                        byteBuffer = byteBuffer,
                                        totalBytes = totalBytes,
                                        downloadedBytes = downloadedBytes,
                                        lastReportMs = lastReportMs,
                                        cancelled = cancelled,
                                        activeCalls = activeCalls,
                                        onProgress = onProgress,
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
    movePartialToTarget(partial, target)
}

private suspend fun downloadChunkWithRetry(
    url: String,
    headers: Map<String, String>,
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
        var writtenInAttempt = 0L
        val request = buildDownloadRequest(url, headers, rangeHeader = "bytes=$start-$endInclusive")
        val call = PrivateHttp.bulkClient.newCall(request)
        activeCalls.add(call)
        try {
            call.execute().use { response ->
                when (response.code) {
                    206 -> Unit
                    200 -> {
                        if (start != 0L || expectedLen != totalBytes) {
                            throw IOException("Server ignored Range header for chunk at $start")
                        }
                    }
                    in NON_RETRYABLE_STATUS -> throw NonRetryableHttpException(response.code)
                    else -> throw IOException("Chunk HTTP ${response.code}")
                }
                response.body.byteStream().use { input ->
                    var writePos = start
                    while (writtenInAttempt < expectedLen) {
                        if (cancelled.get()) throw CancellationException("Download cancelled")
                        val remaining = (expectedLen - writtenInAttempt).coerceAtMost(buffer.size.toLong()).toInt()
                        val count = input.read(buffer, 0, remaining)
                        if (count < 0) break
                        if (count == 0) continue
                        byteBuffer.limit(count)
                        byteBuffer.position(0)
                        while (byteBuffer.hasRemaining()) {
                            writePos += channel.write(byteBuffer, writePos)
                        }
                        writtenInAttempt += count
                        val currentTotal = downloadedBytes.addAndGet(count.toLong())
                        val now = System.currentTimeMillis()
                        val prev = lastReportMs.get()
                        if (now - prev >= PROGRESS_INTERVAL_MS && lastReportMs.compareAndSet(prev, now)) {
                            onProgress(currentTotal.coerceAtMost(totalBytes), totalBytes)
                        }
                    }
                }
            }
            if (writtenInAttempt != expectedLen) {
                throw IOException("Truncated chunk at $start: $writtenInAttempt/$expectedLen")
            }
            return
        } catch (c: CancellationException) {
            downloadedBytes.addAndGet(-writtenInAttempt)
            throw c
        } catch (fatal: NonRetryableHttpException) {
            downloadedBytes.addAndGet(-writtenInAttempt)
            throw fatal
        } catch (error: Exception) {
            downloadedBytes.addAndGet(-writtenInAttempt)
            if (cancelled.get()) {
                throw CancellationException("Download cancelled").also { it.initCause(error) }
            }
            chunkFailure = error
            if (attempt + 1 < 3) delay((1_000L * (attempt + 1)).milliseconds)
        } finally {
            activeCalls.remove(call)
        }
    }
    throw checkNotNull(chunkFailure)
}

private fun downloadSingleResuming(
    url: String,
    target: File,
    partial: File,
    stateFile: File,
    expectedSize: Long,
    headers: Map<String, String>,
    cancelled: AtomicBoolean,
    activeCalls: MutableSet<Call>,
    onProgress: (Long, Long) -> Unit,
) {
    if (stateFile.exists()) {
        stateFile.delete()
        partial.delete()
    }
    val existing = partial.length().takeIf { length ->
        if (expectedSize <= 0L) length > 0L else length in 1L..<maxOf(2L, expectedSize)
    } ?: 0L
    val request = buildDownloadRequest(
        url = url,
        headers = headers,
        rangeHeader = if (existing > 0L) "bytes=$existing-" else null,
    )
    val call = PrivateHttp.bulkClient.newCall(request)
    activeCalls.add(call)
    try {
        call.execute().use { response ->
            when (response.code) {
                206 -> {
                    val body = response.body
                    val contentLen = body.contentLength()
                    val total = when {
                        contentLen > 0L -> existing + contentLen
                        expectedSize > 0L -> expectedSize
                        else -> 0L
                    }
                    streamBodyToFile(
                        input = body.byteStream(),
                        partial = partial,
                        startOffset = existing,
                        totalBytes = total,
                        cancelled = cancelled,
                        onProgress = onProgress,
                    )
                    movePartialToTarget(partial, target)
                }
                200 -> {
                    val body = response.body
                    val total = body.contentLength().takeIf { it > 0L } ?: expectedSize
                    streamBodyToFile(
                        input = body.byteStream(),
                        partial = partial,
                        startOffset = 0L,
                        totalBytes = total,
                        cancelled = cancelled,
                        onProgress = onProgress,
                    )
                    movePartialToTarget(partial, target)
                }
                416 -> {
                    val rangeTotal = parseTotalFromContentRange(response.header("Content-Range"))
                    if (rangeTotal > 0L && partial.isFile && partial.length() == rangeTotal) {
                        onProgress(rangeTotal, rangeTotal)
                        movePartialToTarget(partial, target)
                    } else {
                        partial.delete()
                        throw IOException("Range mismatch (HTTP 416)")
                    }
                }
                in NON_RETRYABLE_STATUS -> throw NonRetryableHttpException(response.code)
                else -> throw IOException("Download failed with HTTP ${response.code}")
            }
        }
    } finally {
        activeCalls.remove(call)
    }
}

private fun streamBodyToFile(
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
        var lastReportedTime = 0L
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
                if (now - lastReportedTime >= PROGRESS_INTERVAL_MS) {
                    lastReportedTime = now
                    onProgress(copied, totalBytes)
                }
            }
        }
        output.fd.sync()
        onProgress(copied, totalBytes)
    }
}

private fun loadOrInitChunkState(
    partial: File,
    stateFile: File,
    totalBytes: Long,
    chunkSize: Long,
    chunkCount: Int,
): BooleanArray {
    if (partial.isFile && partial.length() == totalBytes && stateFile.isFile &&
        stateFile.length() == STATE_HEADER_BYTES + chunkCount
    ) {
        val loaded = runCatching {
            RandomAccessFile(stateFile, "r").use { raf ->
                if (raf.readInt() != STATE_MAGIC) return@use null
                if (raf.readLong() != totalBytes) return@use null
                if (raf.readLong() != chunkSize) return@use null
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
        raf.writeLong(chunkSize)
        raf.write(ByteArray(chunkCount))
        raf.fd.sync()
    }
    return BooleanArray(chunkCount)
}

private fun chunkLength(index: Int, chunkSize: Long, chunkCount: Int, totalBytes: Long): Long {
    val start = index.toLong() * chunkSize
    return if (index == chunkCount - 1) totalBytes - start else chunkSize
}

private fun buildDownloadRequest(
    url: String,
    headers: Map<String, String>,
    rangeHeader: String?,
): Request = Request.Builder()
    .url(url)
    .apply {
        var hasUserAgent = false
        for ((key, value) in headers) {
            if (key.equals("User-Agent", ignoreCase = true)) hasUserAgent = true
            header(key, value)
        }
        if (!hasUserAgent) header("User-Agent", USER_AGENT)
        header("Accept-Encoding", "identity")
        if (rangeHeader != null) header("Range", rangeHeader)
    }
    .get()
    .build()

private fun parseTotalFromContentRange(header: String?): Long =
    header?.let { CONTENT_RANGE_TOTAL.find(it)?.groupValues?.getOrNull(1)?.toLongOrNull() } ?: 0L

private fun movePartialToTarget(partial: File, target: File) {
    runCatching {
        Files.move(
            partial.toPath(),
            target.toPath(),
            StandardCopyOption.ATOMIC_MOVE,
            StandardCopyOption.REPLACE_EXISTING,
        )
        return
    }
    if (target.exists()) target.delete()
    check(partial.renameTo(target)) { "Could not install ${target.name}" }
}
