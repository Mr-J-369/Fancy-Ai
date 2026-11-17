package com.mrj.fancyai.terminal

import android.os.ParcelFileDescriptor
import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import android.system.StructPollfd
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.time.Duration.Companion.milliseconds

data class CommandResult(val exitCode: Int, val output: String)

object TerminalCommandRunner {
    private val ANSI_REGEX = Regex("\\u001B\\[[;?0-9]*[a-zA-Z]")
    private val cleanupScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    fun stripAnsi(input: String): String =
        ANSI_REGEX.replace(input, "").replace("\r\n", "\n").replace("\r", "\n")

    private fun ensureFancyHelper(rootfs: File) {
        val binDir = File(rootfs, "usr/local/bin")
        if (!binDir.exists()) binDir.mkdirs()
        val fancyFile = File(binDir, "fancy")
        if (!fancyFile.exists()) {
            fancyFile.writeText(
                "#!/bin/sh\n" +
                $$"case \"$1\" in\n" +
                "  status)\n" +
                "    echo \"FancyAI Terminal OS\"\n" +
                "    echo \"Environment: Ubuntu 24.04 ARM64\"\n" +
                "    echo \"Workspace: /workspace\"\n" +
                "    ;;\n" +
                "  workspace|files)\n" +
                "    ls -la /workspace\n" +
                "    ;;\n" +
                "  *)\n" +
                "    echo \"FancyAI CLI Helper\"\n" +
                "    echo \"Usage: fancy [status|workspace]\"\n" +
                "    ;;\n" +
                "esac\n",
            )
            runCatching { Os.chmod(fancyFile.path, 0x1ed) }
        }
        val sudoFile = File(binDir, "sudo")
        if (!sudoFile.exists() || !sudoFile.readText().contains("DEBIAN_FRONTEND")) {
            sudoFile.writeText("#!/bin/sh\nexport DEBIAN_FRONTEND=noninteractive\nexec \"$@\"\n")
            runCatching { Os.chmod(sudoFile.path, 0x1ed) }
        }
        val aptConfDir = File(rootfs, "etc/apt/apt.conf.d")
        if (aptConfDir.isDirectory) {
            val aptConf = File(aptConfDir, "99fancy")
            if (!aptConf.exists()) {
                aptConf.writeText(
                    "APT::Get::Assume-Yes \"true\";\n" +
                    "APT::Get::AutomaticRemove \"true\";\n" +
                    "DPkg::Options { \"--force-confdef\"; \"--force-confold\"; };\n",
                )
            }
        }
    }

    suspend fun execute(
        environments: LinuxEnvironments,
        distribution: LinuxDistribution,
        command: String,
        timeoutMs: Long = 60_000L,
        detach: Boolean = false,
        onHandleReady: ((ActiveCommandHandle) -> Unit)? = null,
        onOutput: ((String) -> Unit)? = null,
    ): CommandResult = withContext(Dispatchers.IO) {
        if (!environments.installed(distribution)) {
            return@withContext CommandResult(-1, "Distribution ${distribution.id} is not installed.")
        }
        val rootfs = environments.root(distribution)
        ensureFancyHelper(rootfs)

        // Detached calls must not hold the slave PTY: backgrounded daemons inherit the terminal,
        // which would stall the read loop until the timeout. Redirect stdin/stdout/stderr away.
        val entrypoint = if (detach) {
            listOf("/bin/bash", "-c", "{ $command ; } < /dev/null > /dev/null 2>&1 &")
        } else {
            listOf("/bin/bash", "-c", command)
        }
        val (args, env) = environments.command(
            distribution = distribution,
            workingDir = "/workspace",
            entrypoint = entrypoint,
            killOnExit = !detach,
        )

        val handle = NativePty.start(args, env)
        val pid = (handle ushr 32).toInt()
        val fd = handle.toInt()
        val inDescriptor = ParcelFileDescriptor.adoptFd(fd)
        val outDescriptor = runCatching { ParcelFileDescriptor.dup(inDescriptor.fileDescriptor) }.getOrNull()
        val input = ParcelFileDescriptor.AutoCloseInputStream(inDescriptor)
        val output = outDescriptor?.let { ParcelFileDescriptor.AutoCloseOutputStream(it) }
        val activeHandle = ActiveCommandHandle(output)
        onHandleReady?.invoke(activeHandle)

        val wakeupPipe = runCatching { Os.pipe() }.getOrNull()
        val pipeLock = Any()
        var pipeClosed = wakeupPipe == null
        val woken = AtomicBoolean(false)
        val stopped = AtomicBoolean(false)
        val reaped = AtomicBoolean(false)

        fun signalWakeup() {
            if (!woken.compareAndSet(false, true)) return
            synchronized(pipeLock) {
                if (!pipeClosed && wakeupPipe != null) {
                    runCatching { Os.write(wakeupPipe[1], byteArrayOf(1), 0, 1) }
                }
            }
        }

        fun closeWakeupPipe() {
            synchronized(pipeLock) {
                if (!pipeClosed && wakeupPipe != null) {
                    pipeClosed = true
                    runCatching { Os.close(wakeupPipe[0]) }
                    runCatching { Os.close(wakeupPipe[1]) }
                }
            }
        }

        fun stopAsync() {
            if (stopped.compareAndSet(false, true)) {
                runCatching { NativePty.stop(pid) }
                signalWakeup()
            }
        }

        suspend fun stopAndReapSync() = withContext(NonCancellable) {
            if (stopped.compareAndSet(false, true)) {
                runCatching { NativePty.stop(pid) }
            }
            if (reaped.get()) return@withContext
            repeat(10) {
                if (NativePty.tryReap(pid)) {
                    reaped.set(true)
                    return@withContext
                }
                delay(25.milliseconds)
            }
            runCatching { NativePty.forceStop(pid) }
            if (reaped.compareAndSet(false, true)) {
                NativePty.reap(pid)
            }
        }

        fun scheduleBackgroundStopAndReap() {
            stopAsync()
            cleanupScope.launch {
                repeat(10) {
                    if (NativePty.tryReap(pid)) {
                        reaped.set(true)
                        return@launch
                    }
                    delay(25.milliseconds)
                }
                runCatching { NativePty.forceStop(pid) }
                if (reaped.compareAndSet(false, true)) {
                    NativePty.reap(pid)
                }
            }
        }

        val job = currentCoroutineContext()[Job]
        val completionHandle = job?.invokeOnCompletion { cause ->
            if (cause != null) {
                stopAsync()
            }
        }

        val buffer = ByteArray(4096)
        val outputStream = ByteArrayOutputStream()

        var exitCode: Int
        var lastEmitTime = 0L
        val minEmitIntervalMs = 50L
        var reapInBackground = false

        fun emitOutput(force: Boolean = false) {
            val now = System.currentTimeMillis()
            if (force || (now - lastEmitTime >= minEmitIntervalMs)) {
                lastEmitTime = now
                val raw = outputStream.toString(Charsets.UTF_8.name())
                val clean = stripAnsi(raw).trim()
                if (clean.isNotEmpty()) {
                    onOutput?.invoke(clean)
                }
            }
        }

        try {
            currentCoroutineContext().ensureActive()
            val deadline = System.currentTimeMillis() + timeoutMs
            val ptyPoll = StructPollfd().apply {
                this.fd = inDescriptor.fileDescriptor
                this.events = OsConstants.POLLIN.toShort()
            }
            val wakePoll = wakeupPipe?.let { (readFd, _) ->
                StructPollfd().apply {
                    this.fd = readFd
                    this.events = OsConstants.POLLIN.toShort()
                }
            }
            val pollFds = if (wakePoll != null) arrayOf(ptyPoll, wakePoll) else arrayOf(ptyPoll)
            var timedOut = false

            while (true) {
                currentCoroutineContext().ensureActive()
                val remainingMs = deadline - System.currentTimeMillis()
                if (remainingMs <= 0L) {
                    timedOut = true
                    break
                }
                ptyPoll.revents = 0
                wakePoll?.revents = 0
                val ready = try {
                    Os.poll(pollFds, remainingMs.coerceAtMost(Int.MAX_VALUE.toLong()).toInt())
                } catch (e: ErrnoException) {
                    if (e.errno == OsConstants.EINTR) continue
                    throw e
                }
                currentCoroutineContext().ensureActive()
                if (ready == 0) {
                    timedOut = true
                    break
                }
                if (wakePoll != null && wakePoll.revents.toInt() != 0) {
                    currentCoroutineContext().ensureActive()
                    break
                }
                val revents = ptyPoll.revents.toInt()
                if ((revents and OsConstants.POLLIN) != 0) {
                    val count = try {
                        input.read(buffer)
                    } catch (_: IOException) {
                        // Master PTY throws EIO upon child slave close on Linux.
                        -1
                    }
                    if (count < 0) break
                    if (count > 0) {
                        outputStream.write(buffer, 0, count)
                        emitOutput(force = false)
                    }
                } else if ((revents and (OsConstants.POLLHUP or OsConstants.POLLERR or OsConstants.POLLNVAL)) != 0) {
                    break
                }
            }

            if (timedOut) {
                stopAndReapSync()
                exitCode = 124
                outputStream.write("\n[TIMED OUT after ${timeoutMs / 1000}s, exit:SIGTERM]\n".toByteArray(Charsets.UTF_8))
                emitOutput(force = true)
            } else {
                currentCoroutineContext().ensureActive()
                emitOutput(force = true)
                exitCode = NativePty.waitFor(pid)
            }
        } catch (cancelled: CancellationException) {
            reapInBackground = true
            outputStream.write("\n[Command cancelled]\n".toByteArray(Charsets.UTF_8))
            emitOutput(force = true)
            scheduleBackgroundStopAndReap()
            throw cancelled
        } catch (failure: Exception) {
            stopAndReapSync()
            exitCode = 1
            outputStream.write("\n[Execution error: ${failure.message}]\n".toByteArray(Charsets.UTF_8))
            emitOutput(force = true)
        } finally {
            completionHandle?.dispose()
            closeWakeupPipe()
            runCatching { output?.close() }
            runCatching { input.close() }
            if (!reapInBackground && reaped.compareAndSet(false, true)) {
                NativePty.reap(pid)
            }
        }

        val rawOutput = outputStream.toString(Charsets.UTF_8.name())
        val cleanOutput = stripAnsi(rawOutput).trim()
        CommandResult(exitCode, cleanOutput.ifEmpty { "(no output)" })
    }
}
