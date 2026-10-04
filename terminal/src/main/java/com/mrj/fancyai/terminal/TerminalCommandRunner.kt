package com.mrj.fancyai.terminal

import android.os.ParcelFileDescriptor
import android.system.Os
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException

data class CommandResult(val exitCode: Int, val output: String)

object TerminalCommandRunner {
    private val ANSI_REGEX = Regex("\u001B\\[[;?0-9]*[a-zA-Z]")

    fun stripAnsi(input: String): String =
        ANSI_REGEX.replace(input, "").replace("\r\n", "\n").replace("\r", "\n")

    private fun ensureFancyHelper(rootfs: File) {
        val binDir = File(rootfs, "usr/local/bin")
        if (!binDir.exists()) binDir.mkdirs()
        val fancyFile = File(binDir, "fancy")
        if (!fancyFile.exists()) {
            fancyFile.writeText(
                "#!/bin/sh\n" +
                "case \"\$1\" in\n" +
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
            sudoFile.writeText("#!/bin/sh\nexport DEBIAN_FRONTEND=noninteractive\nexec \"\$@\"\n")
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
        onOutput: ((String) -> Unit)? = null,
    ): CommandResult = withContext(Dispatchers.IO) {
        if (!environments.installed(distribution)) {
            return@withContext CommandResult(-1, "Distribution ${distribution.id} is not installed.")
        }
        val rootfs = environments.root(distribution)
        ensureFancyHelper(rootfs)

        val (args, env) = environments.command(
            distribution = distribution,
            workingDir = "/workspace",
            entrypoint = listOf("/bin/bash", "-c", command),
        )

        val handle = NativePty.start(args, env)
        val pid = (handle ushr 32).toInt()
        val fd = handle.toInt()
        val descriptor = ParcelFileDescriptor.adoptFd(fd)
        val input = ParcelFileDescriptor.AutoCloseInputStream(descriptor)
        val buffer = ByteArray(4096)
        val outputStream = ByteArrayOutputStream()

        var exitCode = -1
        var lastEmitTime = 0L
        val minEmitIntervalMs = 50L

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
            withTimeout(timeoutMs) {
                while (true) {
                    val count = try {
                        input.read(buffer)
                    } catch (_: IOException) {
                        // Master PTY throws EIO upon child slave close on Linux.
                        -1
                    }
                    if (count < 0) break
                    outputStream.write(buffer, 0, count)
                    emitOutput(force = false)
                }
                emitOutput(force = true)
                exitCode = NativePty.waitFor(pid)
            }
        } catch (_: kotlinx.coroutines.TimeoutCancellationException) {
            NativePty.stop(pid)
            exitCode = 124
            outputStream.write("\n[Command timed out after ${timeoutMs / 1000}s]\n".toByteArray(Charsets.UTF_8))
            emitOutput(force = true)
        } catch (cancelled: CancellationException) {
            NativePty.stop(pid)
            outputStream.write("\n[Command cancelled]\n".toByteArray(Charsets.UTF_8))
            emitOutput(force = true)
            throw cancelled
        } catch (failure: Exception) {
            NativePty.stop(pid)
            exitCode = 1
            outputStream.write("\n[Execution error: ${failure.message}]\n".toByteArray(Charsets.UTF_8))
            emitOutput(force = true)
        } finally {
            runCatching { input.close() }
            NativePty.reap(pid)
        }

        val rawOutput = outputStream.toString(Charsets.UTF_8.name())
        val cleanOutput = stripAnsi(rawOutput).trim()
        CommandResult(exitCode, cleanOutput.ifEmpty { "(no output)" })
    }
}
