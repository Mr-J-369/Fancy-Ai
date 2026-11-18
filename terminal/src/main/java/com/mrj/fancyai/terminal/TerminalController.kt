package com.mrj.fancyai.terminal

import android.content.Context
import android.net.ConnectivityManager
import android.net.Uri
import android.net.wifi.WifiManager
import android.os.ParcelFileDescriptor
import android.os.PowerManager
import android.system.Os
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.Call
import okhttp3.Dispatcher
import okhttp3.Dns
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.RandomAccessFile
import java.net.Inet4Address
import java.net.Inet6Address
import java.nio.ByteBuffer
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.attribute.BasicFileAttributes
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import java.util.zip.GZIPInputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.time.Duration.Companion.milliseconds


enum class LinuxDistribution(val id: String, val sha256: String, val bytes: Long, val url: String) {
    DEBIAN(
        "debian",
        "bf7af0229701decd1b9f42143504fc8f69e5664c37e57001d198e731e4f86c2e", 30_159_582,
        "https://registry-1.docker.io/v2/arm64v8/debian/blobs/sha256:bf7af0229701decd1b9f42143504fc8f69e5664c37e57001d198e731e4f86c2e",
    ),
    UBUNTU(
        "ubuntu",
        "04207713ece899c3740823d33690441ad3a7f0ded1101aca744e2b0f37ac7ff2", 29_870_567,
        "https://cdimage.ubuntu.com/ubuntu-base/releases/24.04/release/ubuntu-base-24.04.4-base-arm64.tar.gz",
    );
}

/** A rootfs, not an Android security sandbox. Commands have the hosting app's permissions. */
class LinuxEnvironments(context: Context) {
    private val app = context.applicationContext
    val directory = File(app.filesDir, "terminal")
    val workspace = File(directory, "workspace")
    private val activeCancel = AtomicReference<(() -> Unit)?>()
    private val lock = Mutex()
    private val ipv4FirstDns = Dns { hostname ->
        val resolved = Dns.SYSTEM.lookup(hostname)
        val ipv4 = resolved.filterIsInstance<Inet4Address>()
        val ipv6 = resolved.filterIsInstance<Inet6Address>()
        if (ipv4.isEmpty() || ipv6.isEmpty()) resolved else ipv4 + ipv6
    }
    private val apiClient = OkHttpClient.Builder()
        .dns(ipv4FirstDns)
        .fastFallback(true)
        .retryOnConnectionFailure(true)
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(45, TimeUnit.SECONDS)
        .build()
    private val bulkClient = OkHttpClient.Builder()
        .dns(ipv4FirstDns)
        .fastFallback(true)
        .retryOnConnectionFailure(true)
        .protocols(listOf(Protocol.HTTP_1_1))
        .dispatcher(Dispatcher().apply { maxRequests = 32; maxRequestsPerHost = 16 })
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(45, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()

    fun root(distribution: LinuxDistribution) = File(directory, "environments/${distribution.id}/rootfs")
    fun installed(distribution: LinuxDistribution) = File(root(distribution).parentFile, ".installed").isFile
    fun cancelDownload() { activeCancel.get()?.invoke() }

    suspend fun install(distribution: LinuxDistribution, progress: (Float, Boolean) -> Unit) = lock.withLock {
        withContext(Dispatchers.IO) {
            if (installed(distribution)) return@withContext
            val parent = File(directory, "environments").apply { mkdirs() }
            parent.listFiles { file -> file.name.startsWith(".install-") }?.forEach(::deleteTree)
            val staging = File(parent, ".install-${UUID.randomUUID()}" ).apply { mkdir() }
            val archive = File(staging, "rootfs.tar.gz")
            val rootfs = File(staging, "rootfs").apply { mkdir() }
            val jobContext = coroutineContext
            var lastReport = 0L
            fun report(value: Float, extracting: Boolean, force: Boolean = false) {
                val now = System.nanoTime()
                if (force || now - lastReport > 100_000_000L) {
                    lastReport = now
                    progress(value.coerceIn(0f, 1f), extracting)
                }
            }
            try {
                report(0f, extracting = false, force = true)
                download(distribution, archive, jobContext) { report(it, extracting = false) }
                report(0f, extracting = true, force = true)
                extract(archive, rootfs, distribution, jobContext, ::report)
                check(File(rootfs, "usr/bin/bash").isFile)
                File(rootfs, "etc/apt/apt.conf.d/99-fancy-terminal").writeText("APT::Sandbox::User \"root\";\n")
                File(rootfs, "usr/sbin/policy-rc.d").apply {
                    writeText("#!/bin/sh\nexit 101\n") // No init system in PRoot.
                    Os.chmod(path, 0x1ed)
                }
                listOf("root", "workspace", "tmp", "dev", "proc", "sys", "system", "apex", "linkerconfig", "sdcard", "storage", "fancy_data", "vendor", "data", "mnt", "mnt/data", "mnt/system", "mnt/sdcard")
                    .forEach { File(rootfs, it).mkdirs() }
                File(staging, ".installed").writeText(distribution.sha256)
                archive.delete()
                jobContext.ensureActive()
                check(staging.renameTo(checkNotNull(root(distribution).parentFile)))
                report(1f, extracting = true, force = true)
            } finally {
                activeCancel.getAndSet(null)?.invoke()
                deleteTree(staging)
            }
        }
    }

    private fun extract(
        archive: File,
        rootfs: File,
        distribution: LinuxDistribution,
        jobContext: kotlin.coroutines.CoroutineContext,
        report: (Float, Boolean, Boolean) -> Unit,
    ) {
        // Defer links until all regular entries are written: extraction never follows a link.
        val symlinks = mutableListOf<Pair<File, String>>()
        val hardlinks = mutableListOf<Pair<File, String>>()
        val rootPath = rootfs.canonicalFile.toPath()
        fun entryPath(name: String): File {
            val path = rootPath.resolve(name.removePrefix("/")).normalize()
            require(path.startsWith(rootPath))
            return path.toFile()
        }
        archive.inputStream().buffered().use { compressed ->
            TarArchiveInputStream(GZIPInputStream(compressed, 64 * 1024)).use { tar ->
                while (true) {
                    jobContext.ensureActive()
                    val entry = tar.nextEntry ?: break
                    val file = entryPath(entry.name)
                    when {
                        entry.isDirectory -> file.mkdirs()
                        entry.isSymbolicLink -> symlinks += file to entry.linkName
                        entry.isLink -> hardlinks += file to entry.linkName
                        entry.isFile -> {
                            file.parentFile?.mkdirs()
                            copyChunks(tar, file, jobContext, afterWrite = { _, _ ->
                                report((distribution.bytes - compressed.available()).toFloat() / distribution.bytes, true, false)
                            })
                            Os.chmod(file.path, entry.mode.and(0x1ff).or(0x180))
                        }
                        // Android supplies /dev through PRoot; never create host device nodes.
                    }
                }
            }
        }
        for ((file, target) in symlinks) {
            val destination = if (target.startsWith("/")) entryPath(target).toPath()
                else file.parentFile!!.toPath().resolve(target).normalize()
            require(destination.startsWith(rootPath))
            file.parentFile?.mkdirs()
            Files.createSymbolicLink(file.toPath(), file.parentFile!!.toPath().relativize(destination))
        }
        for ((file, target) in hardlinks) {
            val source = entryPath(target).canonicalFile
            require(source.toPath().startsWith(rootPath))
            file.parentFile?.mkdirs()
            // Android app SELinux policy forbids hard links, even within app-private storage.
            Files.copy(source.toPath(), file.toPath())
            Os.chmod(file.path, Os.stat(source.path).st_mode.and(0x1ff))
        }
    }

    private fun copyChunks(
        input: InputStream,
        output: File,
        jobContext: kotlin.coroutines.CoroutineContext,
        afterWrite: (ByteArray, Int) -> Unit = { _, _ -> },
        beforeWrite: (ByteArray, Int) -> Unit = { _, _ -> },
    ) {
        output.outputStream().buffered().use { destination ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                jobContext.ensureActive()
                val count = input.read(buffer)
                if (count < 0) break
                beforeWrite(buffer, count)
                destination.write(buffer, 0, count)
                afterWrite(buffer, count)
            }
        }
    }

    private suspend fun download(
        distribution: LinuxDistribution,
        archive: File,
        jobContext: kotlin.coroutines.CoroutineContext,
        progress: (Float) -> Unit,
    ) {
        val cancelled = AtomicBoolean(false)
        val activeCalls = ConcurrentHashMap.newKeySet<Call>()
        val cancelAll: () -> Unit = {
            cancelled.set(true)
            activeCalls.forEach { it.cancel() }
        }
        activeCancel.set(cancelAll)
        val registration = jobContext[Job]?.invokeOnCompletion { cause ->
            if (cause != null) cancelAll()
        }
        val powerManager = app.getSystemService(PowerManager::class.java)
        val wakeLock = runCatching {
            powerManager?.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "FancyAI:TerminalDownload")
                ?.apply { acquire(60 * 60_000L) }
        }.getOrNull()
        val wifiManager = app.getSystemService(WifiManager::class.java)
        val wifiLock = runCatching {
            wifiManager?.createWifiLock(WifiManager.WIFI_MODE_FULL_LOW_LATENCY, "FancyAI:TerminalDownload")
                ?.apply { setReferenceCounted(false); acquire() }
        }.getOrNull()

        try {
            var lastFailure: Exception? = null
            for (attempt in 0 until 5) {
                jobContext.ensureActive()
                if (cancelled.get()) throw CancellationException("Download cancelled")
                try {
                    downloadAttempt(distribution, archive, jobContext, cancelled, activeCalls, progress)
                    return
                } catch (c: CancellationException) {
                    throw c
                } catch (error: Exception) {
                    if (cancelled.get() || (jobContext[Job]?.isActive == false)) {
                        throw CancellationException("Download cancelled").also { it.initCause(error) }
                    }
                    lastFailure = error
                    if (attempt + 1 < 5) delay((1_500L * (attempt + 1)).coerceAtMost(8_000L).milliseconds)
                }
            }
            throw checkNotNull(lastFailure)
        } finally {
            registration?.dispose()
            activeCancel.compareAndSet(cancelAll, null)
            runCatching { if (wifiLock?.isHeld == true) wifiLock.release() }
            runCatching { if (wakeLock?.isHeld == true) wakeLock.release() }
        }
    }

    private suspend fun downloadAttempt(
        distribution: LinuxDistribution,
        archive: File,
        jobContext: kotlin.coroutines.CoroutineContext,
        cancelled: AtomicBoolean,
        activeCalls: MutableSet<Call>,
        progress: (Float) -> Unit,
    ) {
        val authHeader = if (distribution == LinuxDistribution.DEBIAN) {
            val tokenCall = apiClient.newCall(
                Request.Builder()
                    .url("https://auth.docker.io/token?service=registry.docker.io&scope=repository:arm64v8/debian:pull")
                    .header("User-Agent", "FancyAI/1.0 (Android)")
                    .build(),
            )
            activeCalls.add(tokenCall)
            try {
                tokenCall.execute().use { response ->
                    if (!response.isSuccessful) throw IOException("HTTP ${response.code}")
                    "Bearer ${Json.parseToJsonElement(response.body.string()).jsonObject.getValue("token").jsonPrimitive.content}"
                }
            } finally {
                activeCalls.remove(tokenCall)
            }
        } else null
        jobContext.ensureActive()
        if (cancelled.get()) throw CancellationException("Download cancelled")

        fun buildRequest(rangeHeader: String?): Request = Request.Builder()
            .url(distribution.url)
            .header("User-Agent", "FancyAI/1.0 (Android)")
            .header("Accept-Encoding", "identity")
            .apply {
                if (authHeader != null) header("Authorization", authHeader)
                if (rangeHeader != null) header("Range", rangeHeader)
            }
            .get()
            .build()

        val probeCall = bulkClient.newCall(buildRequest("bytes=0-0"))
        activeCalls.add(probeCall)
        var remoteTotal = 0L
        var useParallel = false
        var handledInline = false
        try {
            probeCall.execute().use { response ->
                when (response.code) {
                    206 -> {
                        remoteTotal = parseContentRangeTotal(response.header("Content-Range"))
                        if (remoteTotal >= 2L * 1024 * 1024) useParallel = true
                    }
                    200 -> {
                        val totalBytes = response.body.contentLength().takeIf { it > 0L } ?: distribution.bytes
                        var received = 0L
                        copyChunks(response.body.byteStream(), archive, jobContext, afterWrite = { _, _ ->
                            progress((received.toFloat() / totalBytes).coerceIn(0f, 1f))
                        }, beforeWrite = { _, count ->
                            received += count
                        })
                        handledInline = true
                    }
                    else -> throw IOException("HTTP ${response.code}")
                }
            }
        } finally {
            activeCalls.remove(probeCall)
        }
        if (handledInline) return

        if (useParallel) {
            val chunkSize = 4L * 1024 * 1024
            val chunkCount = ((remoteTotal + chunkSize - 1L) / chunkSize).toInt()
            val workers = 4.coerceAtMost(chunkCount)
            val completed = BooleanArray(chunkCount)
            val downloadedBytes = AtomicLong(0L)
            val nextChunk = AtomicInteger(0)
            withContext(Dispatchers.IO) {
                RandomAccessFile(archive, "rw").use { raf ->
                    raf.setLength(remoteTotal)
                    raf.channel.use { channel ->
                        coroutineScope {
                            repeat(workers) {
                                launch(Dispatchers.IO) {
                                    val buffer = ByteArray(256 * 1024)
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
                                        val expectedLen = if (idx == chunkCount - 1) remoteTotal - start else chunkSize
                                        val endInclusive = start + expectedLen - 1L
                                        var written = 0L
                                        val chunkCall = bulkClient.newCall(buildRequest("bytes=$start-$endInclusive"))
                                        activeCalls.add(chunkCall)
                                        try {
                                            chunkCall.execute().use { resp ->
                                                if (resp.code != 206 && !(resp.code == 200 && start == 0L && expectedLen == remoteTotal)) {
                                                    throw IOException("Chunk HTTP ${resp.code}")
                                                }
                                                resp.body.byteStream().use { input ->
                                                    var pos = start
                                                    while (written < expectedLen) {
                                                        if (cancelled.get()) throw CancellationException("Download cancelled")
                                                        val maxRead = (expectedLen - written).coerceAtMost(buffer.size.toLong()).toInt()
                                                        val count = input.read(buffer, 0, maxRead)
                                                        if (count < 0) break
                                                        if (count == 0) continue
                                                        byteBuffer.limit(count)
                                                        byteBuffer.position(0)
                                                        while (byteBuffer.hasRemaining()) {
                                                            pos += channel.write(byteBuffer, pos)
                                                        }
                                                        written += count
                                                        val totalNow = downloadedBytes.addAndGet(count.toLong())
                                                        progress((totalNow.toFloat() / remoteTotal).coerceIn(0f, 1f))
                                                    }
                                                }
                                            }
                                            if (written != expectedLen) throw IOException("Truncated chunk $idx")
                                            completed[idx] = true
                                        } catch (t: Throwable) {
                                            downloadedBytes.addAndGet(-written)
                                            throw t
                                        } finally {
                                            activeCalls.remove(chunkCall)
                                        }
                                    }
                                }
                            }
                        }
                        channel.force(true)
                    }
                }
            }
        } else {
            val singleCall = bulkClient.newCall(buildRequest(null))
            activeCalls.add(singleCall)
            try {
                singleCall.execute().use { response ->
                    if (!response.isSuccessful) throw IOException("HTTP ${response.code}")
                    val totalBytes = response.body.contentLength().takeIf { it > 0 } ?: distribution.bytes
                    var received = 0L
                    copyChunks(response.body.byteStream(), archive, jobContext, afterWrite = { _, _ ->
                        progress((received.toFloat() / totalBytes).coerceIn(0f, 1f))
                    }, beforeWrite = { _, count ->
                        received += count
                    })
                }
            } finally {
                activeCalls.remove(singleCall)
            }
        }
    }

    private fun parseContentRangeTotal(header: String?): Long =
        header?.let { Regex("""bytes\s+(?:\d+-\d+|\*)/(\d+)""", RegexOption.IGNORE_CASE).find(it)?.groupValues?.getOrNull(1)?.toLongOrNull() } ?: 0L

    suspend fun delete(distribution: LinuxDistribution) = lock.withLock {
        withContext(Dispatchers.IO) { deleteTree(root(distribution).parentFile!!) }
    }

    fun command(
        distribution: LinuxDistribution,
        workingDir: String = "/root",
        entrypoint: List<String> = listOf("/bin/bash", "--login"),
        killOnExit: Boolean = true,
    ): Pair<Array<String>, Array<String>> {
        val rootfs = root(distribution)
        // PRoot keeps Android's supplementary groups; Linux needs names for `groups` at login.
        val groupFile = File(rootfs, "etc/group")
        val groupText = groupFile.readText()
        val groups = groupText.lineSequence().map { it.split(':') }.toList()
        val groupIds = groups.mapNotNull { it.getOrNull(2)?.toIntOrNull() }.toSet()
        val groupNames = groups.map { it.first() }.toMutableSet()
        val androidGroups = File("/proc/self/status").useLines { lines ->
            lines.first { it.startsWith("Groups:") }.substringAfter(':')
                .trim().split(Regex("\\s+")).mapNotNull(String::toIntOrNull).distinct()
        }
        val missingGroups = androidGroups.filterNot { it in groupIds }.map { gid ->
            var name = "android_$gid"
            while (!groupNames.add(name)) name += "_"
            "$name:x:$gid:\n"
        }
        if (missingGroups.isNotEmpty()) {
            val separator = if (groupText.isEmpty() || groupText.endsWith('\n')) "" else "\n"
            groupFile.appendText(separator + missingGroups.joinToString(""))
        }
        val temporary = File(directory, "tmp").apply { mkdirs() }
        workspace.mkdirs()
        val network = app.getSystemService(ConnectivityManager::class.java)
        val dns = network.getLinkProperties(network.activeNetwork)?.dnsServers.orEmpty()
        if (dns.isNotEmpty()) {
            val resolver = File(rootfs, "etc/resolv.conf")
            Files.deleteIfExists(resolver.toPath())
            resolver.writeText(dns.joinToString("\n", postfix = "\n") { "nameserver ${it.hostAddress}" })
        }
        val native = app.applicationInfo.nativeLibraryDir
        listOf("sdcard", "storage", "fancy_data", "vendor", "data", "mnt", "mnt/data", "mnt/system", "mnt/sdcard").forEach { File(rootfs, it).mkdirs() }
        val binds = mutableListOf(
            "-b", "/dev", "-b", "/proc", "-b", "/sys", "-b", "/system", "-b", "/system:/mnt/system",
            "-b", "/apex", "-b", "/linkerconfig", "-b", "${workspace.path}:/workspace",
            "-b", "${app.filesDir.path}:/fancy_data",
        )
        if (File("/data").isDirectory) {
            binds.addAll(listOf("-b", "/data", "-b", "/data:/mnt/data"))
        }
        if (File("/vendor").isDirectory) binds.addAll(listOf("-b", "/vendor"))
        val primaryStorage = File("/storage/emulated/0")
        if (primaryStorage.exists()) {
            binds.addAll(
                listOf(
                    "-b", "${primaryStorage.path}:/sdcard",
                    "-b", "/storage/emulated/0",
                    "-b", "${primaryStorage.path}:/mnt/sdcard",
                )
            )
        }
        val killArg = if (killOnExit) arrayOf("--kill-on-exit") else emptyArray<String>()
        val command = arrayOf(
            "$native/libfancy_proot.so",
        ) + killArg + arrayOf(
            "--link2symlink", "--sysvipc", "-0",
            "-r", rootfs.path, "-w", workingDir,
        ) + binds.toTypedArray() + arrayOf(
            "/usr/bin/env", "-i", "HOME=/root", "USER=root", "LOGNAME=root", "TERM=xterm-256color",
            "LANG=C.UTF-8", "COLORTERM=truecolor",
            "PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin:/system/bin:/system/xbin:/vendor/bin",
        ) + entrypoint
        val environment = arrayOf(
            "PROOT_LOADER=$native/libfancy_proot_loader.so", "PROOT_TMP_DIR=${temporary.path}",
            "HOME=${rootfs.path}/root", "TMPDIR=${temporary.path}", "PATH=/system/bin", "TERM=xterm-256color",
        )
        return command to environment
    }

    private fun deleteTree(file: File) {
        if (!Files.exists(file.toPath(), LinkOption.NOFOLLOW_LINKS)) return
        try {
            Files.walkFileTree(file.toPath(), object : SimpleFileVisitor<Path>() {
                override fun preVisitDirectory(dir: Path, attrs: BasicFileAttributes): FileVisitResult {
                    dir.toFile().setWritable(true)
                    return FileVisitResult.CONTINUE
                }
                override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                    file.toFile().setWritable(true)
                    try { Files.delete(file) } catch (_: Exception) {}
                    return FileVisitResult.CONTINUE
                }
                override fun postVisitDirectory(dir: Path, exc: IOException?): FileVisitResult {
                    dir.toFile().setWritable(true)
                    try { Files.delete(dir) } catch (_: Exception) {}
                    return FileVisitResult.CONTINUE
                }
            })
        } catch (_: Exception) {}
        file.deleteRecursively()
    }
}


internal object NativePty {
    init { System.loadLibrary("fancy_terminal") }
    external fun start(arguments: Array<String>, environment: Array<String>): Long
    external fun resize(fd: Int, columns: Int, rows: Int)
    external fun waitFor(pid: Int): Int
    external fun stop(pid: Int)
    external fun forceStop(pid: Int)
    external fun tryReap(pid: Int): Boolean
    external fun reap(pid: Int)
}

/** Sessions outlive screens; a process restart never replays commands. */
class TerminalSession private constructor(val distribution: LinuxDistribution, handle: Long) {
    val id: String = UUID.randomUUID().toString()
    private val pid = (handle ushr 32).toInt()
    private val descriptor = ParcelFileDescriptor.adoptFd(handle.toInt())
    private val input = ParcelFileDescriptor.AutoCloseInputStream(descriptor)
    private val output = ParcelFileDescriptor.AutoCloseOutputStream(ParcelFileDescriptor.dup(descriptor.fileDescriptor))
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val ended = CompletableDeferred<Unit>()
    private val mutableExit = MutableStateFlow<Int?>(null)
    val exitCode = mutableExit.asStateFlow()
    private val transcript = ArrayDeque<ByteArray>()
    private var transcriptBytes = 0
    private var receiver: ((ByteArray) -> Unit)? = null
    var controlNext: Boolean = false
    var onControlConsumed: () -> Unit = {}
    private val writes = kotlinx.coroutines.channels.Channel<ByteArray>(kotlinx.coroutines.channels.Channel.UNLIMITED)

    init {
        val reading = scope.launch(Dispatchers.IO) {
            try {
                val buffer = ByteArray(8192)
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    val bytes = buffer.copyOf(count)
                    withContext(Dispatchers.Main) {
                        transcript.addLast(bytes)
                        transcriptBytes += bytes.size
                        while (transcriptBytes > 256 * 1024) transcriptBytes -= transcript.removeFirst().size
                        receiver?.invoke(bytes)
                    }
                }
            } catch (_: IOException) {
                // Linux PTYs report EIO when the final slave closes. waitpid owns the exit status.
            } finally { input.close() }
        }
        scope.launch(Dispatchers.IO) {
            try {
                for (bytes in writes) output.write(bytes)
            } catch (_: IOException) {
                // The child may exit while a keyboard event is in flight.
            } finally { output.close() }
        }
        scope.launch {
            val status = withContext(Dispatchers.IO) { NativePty.waitFor(pid) }
            reading.join()
            writes.close()
            NativePty.reap(pid)
            mutableExit.value = status
            ended.complete(Unit)
        }
    }

    fun attach(onOutput: (ByteArray) -> Unit) {
        receiver = onOutput
        transcript.forEach(onOutput)
    }

    fun detach() { receiver = null }
    fun write(text: String) {
        var input = text
        if (controlNext && text.isNotEmpty()) {
            val first = text.first().uppercaseChar()
            if (first in '@'..'_') input = (first.code and 31).toChar() + text.drop(1)
            controlNext = false
            onControlConsumed()
        }
        if (!ended.isCompleted) writes.trySend(input.toByteArray(Charsets.UTF_8))
    }

    fun resize(columns: Int, rows: Int) {
        if (!ended.isCompleted && columns in 2..1000 && rows in 1..1000) {
            // A close can race a final layout callback.
            runCatching { NativePty.resize(descriptor.fd, columns, rows) }
        }
    }

    suspend fun close() {
        if (!ended.isCompleted) {
            runCatching { NativePty.stop(pid) }
            if (kotlinx.coroutines.withTimeoutOrNull(500.milliseconds) { ended.await() } == null) {
                runCatching { NativePty.forceStop(pid) }
                ended.await()
            }
        }
        detach()
    }

    companion object {
        suspend fun open(
            environments: LinuxEnvironments,
            distribution: LinuxDistribution,
            workingDir: String = "/root",
            entrypoint: List<String> = listOf("/bin/bash", "--login"),
        ): TerminalSession = withContext(Dispatchers.IO) {
            val (command, environment) = environments.command(distribution, workingDir, entrypoint)
            val handle = NativePty.start(command, environment)
            withContext(Dispatchers.Main) { TerminalSession(distribution, handle) }
        }
    }
}


data class EnvironmentDownload(val distribution: LinuxDistribution, val fraction: Float = 0f, val extracting: Boolean = false)
data class TerminalWorkspaceState(
    val installed: Set<LinuxDistribution> = emptySet(),
    val sessions: List<TerminalSession> = emptyList(),
    val download: EnvironmentDownload? = null,
    val busy: Boolean = false,
    val error: String? = null,
)

/** Shared lifetime follows FancyAI's existing foreground app session, not a particular screen. */
class TerminalWorkspace private constructor(context: Context) {
    private val preferences = context.getSharedPreferences("terminal_settings", Context.MODE_PRIVATE)
    @set:android.annotation.SuppressLint("UseKtx") // This module does not depend on AndroidX Core.
    var fontScale: Float
        get() = preferences.getFloat("font_scale", 1f)
        set(value) { preferences.edit().putFloat("font_scale", value).apply() }
    val environments = LinuxEnvironments(context)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val mutableState = MutableStateFlow(TerminalWorkspaceState())
    val state = mutableState.asStateFlow()
    private var installation: Job? = null

    init { perform { refresh() } }

    private suspend fun refresh() {
        val installed = withContext(Dispatchers.IO) {
            environments.workspace.mkdirs()
            LinuxDistribution.entries.filter(environments::installed).toSet()
        }
        mutableState.value = mutableState.value.copy(installed = installed)
    }

    fun clearError() { mutableState.value = mutableState.value.copy(error = null) }
    fun reportError(message: String) { mutableState.value = mutableState.value.copy(error = message) }

    fun install(distribution: LinuxDistribution) {
        if (installation?.isActive == true) return
        installation = scope.launch {
            mutableState.value = mutableState.value.copy(download = EnvironmentDownload(distribution), error = null)
            try {
                environments.install(distribution) { fraction, extracting ->
                    scope.launch {
                        if (mutableState.value.download?.distribution == distribution) {
                            mutableState.value = mutableState.value.copy(download = EnvironmentDownload(distribution, fraction, extracting))
                        }
                    }
                }
                refresh()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                mutableState.value = mutableState.value.copy(error = failure.message ?: failure.javaClass.simpleName)
            } finally {
                mutableState.value = mutableState.value.copy(download = null)
            }
        }
    }

    fun cancelInstall() { installation?.cancel(); environments.cancelDownload() }

    fun open(
        distribution: LinuxDistribution,
        workingDir: String = "/root",
        entrypoint: List<String> = listOf("/bin/bash", "--login"),
        onSessionCreated: ((TerminalSession) -> Unit)? = null,
    ) = perform {
        val session = TerminalSession.open(environments, distribution, workingDir, entrypoint)
        mutableState.value = mutableState.value.copy(sessions = mutableState.value.sessions + session)
        onSessionCreated?.invoke(session)
    }

    fun close(session: TerminalSession) = perform {
        session.close()
        mutableState.value = mutableState.value.copy(sessions = mutableState.value.sessions - session)
    }

    fun delete(distribution: LinuxDistribution) = perform {
        val sessions = mutableState.value.sessions.filter { it.distribution == distribution }
        for (session in sessions) session.close()
        mutableState.value = mutableState.value.copy(sessions = mutableState.value.sessions - sessions.toSet())
        environments.delete(distribution)
        refresh()
    }

    private fun perform(action: suspend () -> Unit) {
        if (mutableState.value.busy) return
        scope.launch {
            mutableState.value = mutableState.value.copy(busy = true, error = null)
            try { action() }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) {
                mutableState.value = mutableState.value.copy(error = failure.message ?: failure.javaClass.simpleName)
            } finally { mutableState.value = mutableState.value.copy(busy = false) }
        }
    }

    companion object {
        @Volatile private var instance: TerminalWorkspace? = null
        fun get(context: Context): TerminalWorkspace = instance ?: synchronized(this) {
            instance ?: TerminalWorkspace(context.applicationContext).also { instance = it }
        }
    }
}


fun exportTerminalSources(context: Context, destination: Uri) {
    val names = checkNotNull(context.assets.list("terminal/legal"))
    ZipOutputStream(checkNotNull(context.contentResolver.openOutputStream(destination))).use { zip ->
        for (name in names) {
            zip.putNextEntry(ZipEntry(name))
            context.assets.open("terminal/legal/$name").use { it.copyTo(zip) }
            zip.closeEntry()
        }
    }
}
