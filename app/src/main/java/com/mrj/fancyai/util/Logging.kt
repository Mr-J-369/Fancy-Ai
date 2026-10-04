package com.mrj.fancyai.util

import android.app.Application
import android.content.Context
import android.os.FileObserver
import android.os.Process
import android.util.Log
import com.mrj.fancyai.BuildConfig
import java.io.File
import java.time.Instant

/** Small operation-level logs. Callers must never pass user content, credentials, or HTTP bodies. */
object AppLog {
    private val lock = Any()
    private var file: File? = null
    private var crashFile: File? = null
    private var process = "main"
    @Volatile var enabled = false
        private set
    private var observer: FileObserver? = null
    private const val MAX_BYTES = 512 * 1024L

    fun initialize(context: Context) {
        synchronized(lock) {
            if (file != null) return
            process = Application.getProcessName().substringAfter(':', "main")
                .replace(Regex("[^a-zA-Z0-9_-]"), "_")
            val directory = File(context.filesDir, "diagnostics")
            directory.mkdirs()
            val enabledFile = File(directory, "logging-enabled")
            enabled = enabledFile.exists()
            observer = object : FileObserver(directory, CREATE or DELETE or MOVED_TO or MOVED_FROM) {
                override fun onEvent(event: Int, path: String?) {
                    if (path == enabledFile.name) enabled = enabledFile.exists()
                }
            }.also { it.startWatching() }
            file = File(directory, "App-$process.log")
            crashFile = File(directory, "Last-crash-$process.txt")
        }
        write(Log.INFO, "App", "Process started version=${BuildConfig.VERSION_NAME} sdk=${android.os.Build.VERSION.SDK_INT}")
        val previous = Thread.getDefaultUncaughtExceptionHandler() ?: return
        Thread.setDefaultUncaughtExceptionHandler { thread, failure ->
            try {
                write(Log.ERROR, "Crash", "Uncaught exception", failure)
                synchronized(lock) {
                    crashFile?.let { target ->
                        runCatching {
                            target.parentFile?.mkdirs()
                            writeAtomicFile(
                                target,
                                format("E", "Crash", "Uncaught exception version=${BuildConfig.VERSION_NAME}", failure).toByteArray(),
                            )
                        }
                    }
                }
            } finally {
                previous.uncaughtException(thread, failure)
            }
        }
    }

    fun setEnabled(context: Context, value: Boolean) {
        synchronized(lock) {
            val enabledFile = File(context.filesDir, "diagnostics/logging-enabled")
            if (value) {
                enabledFile.parentFile?.mkdirs()
                enabledFile.writeText("")
            } else {
                enabledFile.delete()
            }
            enabled = value
        }
    }

    fun deleteReports(context: Context, crashes: Boolean) {
        synchronized(lock) {
            File(context.filesDir, "diagnostics").listFiles().orEmpty().filter {
                if (crashes) it.name.startsWith("Last-crash-") else it.extension == "log"
            }.forEach { it.delete() }
        }
    }

    fun write(priority: Int, tag: String, event: String, failure: Throwable? = null) {
        if (!enabled) return
        // Diagnostics must never replace an operation's result with a logging failure.
        runCatching {
            val line = format(if (priority == Log.ERROR) "E" else if (priority == Log.WARN) "W" else "I", tag, event, failure)
            Log.println(priority, tag, line)
            synchronized(lock) {
                if (!enabled) return
                file?.let { target ->
                    target.parentFile?.mkdirs()
                    if (target.length() + line.toByteArray().size > MAX_BYTES) {
                        val previous = File(target.parentFile, "${target.nameWithoutExtension}-previous.log")
                        check(!previous.exists() || previous.delete())
                        check(target.renameTo(previous))
                    }
                    target.appendText(line)
                }
            }
        }
    }

    private fun format(level: String, tag: String, event: String, failure: Throwable?): String = buildString {
        append(Instant.now()).append(' ').append(level).append(' ').append(process)
            .append(" pid=").append(Process.myPid()).append(" tid=").append(Process.myTid())
            .append(' ').append(tag).append(": ").append(event.take(2048)).append('\n')
        // Exception messages can contain prompts, server responses, URLs, and keys.
        // Keep exception types and stack frames, with bounded cause traversal.
        var cause = failure
        repeat(8) {
            val current = cause ?: return@buildString
            append(current.javaClass.name).append('\n')
            current.stackTrace.take(24).forEach { append("  at ").append(it).append('\n') }
            cause = current.cause.takeUnless { it === current }
        }
    }
}
