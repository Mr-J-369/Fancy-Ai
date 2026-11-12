package com.mrj.fancyai.ui.agents

import android.content.Context
import android.content.pm.PackageManager
import android.os.Environment
import com.mrj.fancyai.R
import com.mrj.fancyai.terminal.LinuxDistribution
import com.mrj.fancyai.terminal.TerminalCommandRunner
import com.mrj.fancyai.terminal.TerminalWorkspace
import com.mrj.fancyai.util.PrivateHttp.decodeLocalRoute
import com.mrj.fancyai.util.PrivateHttp.routePrivateHttp
import com.mrj.fancyai.util.writeAtomicFile
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import okhttp3.Dns
import okhttp3.OkHttpClient
import okhttp3.Request
import rikka.shizuku.Shizuku
import rikka.shizuku.ShizukuRemoteProcess
import java.io.File
import java.io.FileInputStream
import java.io.IOException
import java.io.InputStreamReader
import java.util.concurrent.TimeUnit
import kotlin.time.Duration.Companion.milliseconds

enum class ShizukuStatus {
    NOT_INSTALLED,
    NOT_RUNNING,
    UNAUTHORIZED,
    READY;

    val isReady: Boolean get() = this == READY
}

/**
 * Executes agent tool calls. bash defaults to proot Ubuntu 24.04 ARM64 (when installed via
 * Terminal) with full Linux tools; Android's own shell is only reached via an explicit
 * mode ('su' / 'android') or when no Linux distribution is installed. Every bash output is
 * stamped with the world it ran in; file tools operate in the workspace and phone storage;
 * fetch_url performs HTTP GETs.
 */
internal class AgentTools(context: Context) {
    private val app = context.applicationContext
    private val workspace = TerminalWorkspace.get(app)
    val root: File = workspace.environments.workspace.apply { mkdirs() }
    val storageRoot: File = Environment.getExternalStorageDirectory()
    private val fetchClient = OkHttpClient.Builder()
        .dns { hostname -> decodeLocalRoute(hostname)?.let(::listOf) ?: Dns.SYSTEM.lookup(hostname) }
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(90, TimeUnit.SECONDS)
        .build()

    fun checkShizukuStatus(): ShizukuStatus {
        return try {
            if (!Shizuku.pingBinder()) {
                val isInstalled = try {
                    app.packageManager.getPackageInfo("moe.shizuku.privileged.api", 0)
                    true
                } catch (_: Exception) {
                    false
                }
                if (isInstalled) ShizukuStatus.NOT_RUNNING else ShizukuStatus.NOT_INSTALLED
            } else if (Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED) {
                ShizukuStatus.READY
            } else {
                ShizukuStatus.UNAUTHORIZED
            }
        } catch (_: Throwable) {
            ShizukuStatus.NOT_INSTALLED
        }
    }

    fun checkRootAvailable(): Boolean {
        val paths = arrayOf(
            "/system/bin/su",
            "/system/xbin/su",
            "/sbin/su",
            "/vendor/bin/su",
            "/data/local/xbin/su",
            "/data/local/bin/su"
        )
        return paths.any { path -> runCatching { File(path).canExecute() }.getOrDefault(false) } ||
            try {
                Runtime.getRuntime().exec(arrayOf("/system/bin/which", "su")).waitFor() == 0
            } catch (_: Throwable) {
                false
            }
    }

    /** Dispatches one tool call; the returned string is the tool message content the model sees. */
    suspend fun execute(name: String, arguments: String): String {
        val trimmed = arguments.trim()
        val parsed = runCatching { Json.parseToJsonElement(trimmed.ifBlank { "{}" }) as? JsonObject }
        val input = parsed.getOrNull() ?: when (name) {
            "bash", "su_bash", "shizuku_bash" -> buildJsonObject { put("command", trimmed) }
            "read_file", "list_files" -> buildJsonObject { put("path", trimmed) }
            "fetch_url" -> buildJsonObject { put("url", trimmed) }
            else -> return "Tool failed: arguments are not valid JSON: ${parsed.exceptionOrNull()?.message ?: "input is not a JSON object"} (hint: escape quotes and newlines)"
        }
        return try {
            when (name) {
                "bash" -> bash(
                    command = input.text("command", "cmd", "arg") ?: input.fallbackText() ?: missing("command"),
                    mode = input.text("mode"),
                    detach = input.text("detach")?.toBooleanStrictOrNull() == true,
                    timeoutMs = input.text("timeout_seconds", "timeout")?.toIntOrNull()?.let { it * 1000L },
                )
                "su_bash" -> bash(
                    command = input.text("command", "cmd", "arg") ?: input.fallbackText() ?: missing("command"),
                    mode = "su"
                )
                "shizuku_bash" -> bash(
                    command = input.text("command", "cmd", "arg") ?: input.fallbackText() ?: missing("command"),
                    mode = "shizuku"
                )
                "read_file" -> readFile(input.text("path", "file_path", "file", "arg") ?: input.fallbackText() ?: missing("path"))
                "write_file" -> writeFile(
                    path = input.text("path", "file_path", "file") ?: missing("path"),
                    content = input.text("content", "text", "file_text") ?: missing("content"),
                )
                "edit_file" -> editFile(
                    path = input.text("path", "file_path", "file") ?: missing("path"),
                    oldString = input.text("old_string", "old_text", "target") ?: missing("old_string"),
                    newString = input.text("new_string", "new_text", "replacement") ?: missing("new_string"),
                )
                "list_files" -> listFiles(input.text("path", "dir", "directory", "arg") ?: input.fallbackText() ?: "")
                "install_ubuntu" -> {
                    if (workspace.environments.installed(LinuxDistribution.UBUNTU)) {
                        "Ubuntu is already installed."
                    } else {
                        withContext(Dispatchers.Main) { workspace.install(LinuxDistribution.UBUNTU) }
                        while (workspace.state.value.download?.distribution == LinuxDistribution.UBUNTU) {
                            delay(1000.milliseconds)
                        }
                        if (workspace.state.value.error != null) {
                            "Installation failed: ${workspace.state.value.error}"
                        } else {
                            "Ubuntu installation completed successfully. Bash now runs inside it by default."
                        }
                    }
                }
                "fetch_url" -> fetch(input.text("url", "endpoint", "arg", "link") ?: input.fallbackText() ?: missing("url"))
                "web_search" -> webSearch(input.text("query", "q", "arg") ?: input.fallbackText() ?: missing("query"))
                else -> "Unknown tool: $name"
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            "Tool failed: ${failure.message ?: failure.javaClass.simpleName}"
        }
    }

    /**
     * proot Linux (Ubuntu/Debian) is the default world for every bash call. Only an explicit
     * native mode ("su" / "shizuku" / "android") or an unknown mode leaves the container, and
     * every output is stamped so the caller always knows which world executed.
     */
    private suspend fun bash(command: String, mode: String? = null, detach: Boolean = false, timeoutMs: Long? = null): String {
        root.mkdirs()
        val timeout = timeoutMs ?: BASH_TIMEOUT_MS
        return when (mode) {
            null, "", "proot", "ubuntu", "debian" -> withContext(Dispatchers.IO) {
                val distribution = when {
                    workspace.environments.installed(LinuxDistribution.UBUNTU) -> LinuxDistribution.UBUNTU
                    workspace.environments.installed(LinuxDistribution.DEBIAN) -> LinuxDistribution.DEBIAN
                    else -> null
                }
                when {
                    distribution != null -> prootShell(command, distribution, detach, timeout)
                    mode.isNullOrBlank() -> {
                        if (checkShizukuStatus() == ShizukuStatus.READY) {
                            shizukuShell(command, timeout)
                        } else {
                            nativeShell(
                                command = command,
                                executable = "/system/bin/sh",
                                tag = "android",
                                timeoutMs = timeout,
                                tipIfCommandMissing = true,
                            )
                        }
                    }
                    else -> "Tool failed: mode='$mode' requires an installed Linux distribution; call install_ubuntu() first (or pass mode='shizuku' or mode='android')."
                }
            }
            "shizuku" -> shizukuShell(
                command = command,
                timeoutMs = timeout,
            )
            "su" -> nativeShell(
                command = command,
                executable = "/system/bin/su",
                tag = "su",
                timeoutMs = timeout,
                tipIfCommandMissing = false,
            )
            "android" -> nativeShell(
                command = command,
                executable = "/system/bin/sh",
                tag = "android",
                timeoutMs = timeout,
                tipIfCommandMissing = true,
            )
            else -> "Tool failed: unknown mode '$mode' (expected 'proot'/'ubuntu', 'shizuku', 'android', or 'su')"
        }
    }

    private suspend fun prootShell(
        command: String,
        distribution: LinuxDistribution,
        detach: Boolean,
        timeoutMs: Long,
    ): String = withContext(Dispatchers.IO) {
        val result = TerminalCommandRunner.execute(
            environments = workspace.environments,
            distribution = distribution,
            command = command,
            timeoutMs = timeoutMs,
            detach = detach,
        )
        val body = if (result.exitCode == 0 && result.output == "(no output)") {
            "[exit:0, no output]"
        } else {
            buildString {
                append(result.output)
                if (result.exitCode != 0) {
                    if (isNotEmpty() && !endsWith("\n")) append('\n')
                    append(app.getString(R.string.chat_terminal_exit_code, result.exitCode))
                }
            }
        }
        truncate("[proot:/workspace]\n$body")
    }

    private fun isProcessAlive(process: Process): Boolean {
        if (process is ShizukuRemoteProcess) {
            return runCatching { process.alive() }.getOrDefault(false)
        }
        return try {
            process.exitValue()
            false
        } catch (_: IllegalThreadStateException) {
            true
        } catch (e: Exception) {
            e.message?.contains("hasn't exited", ignoreCase = true) == true
        }
    }

    private suspend fun nativeShell(
        command: String,
        executable: String,
        tag: String,
        timeoutMs: Long,
        tipIfCommandMissing: Boolean,
    ): String = withContext(Dispatchers.IO) {
        val builder = ProcessBuilder(executable, "-c", command)
            .directory(root)
            .redirectErrorStream(true)
        builder.environment().apply {
            put("HOME", root.path)
            put("TMPDIR", root.path)
            put("PATH", "/system/bin:/system/xbin:/vendor/bin:/apex/com.android.runtime/bin")
        }
        val process = builder.start()
        val output = StringBuilder()
        val reader = process.inputStream.bufferedReader()
        val collector = Thread {
            reader.useLines { lines -> lines.forEach { output.append(it).append('\n') } }
        }.apply { isDaemon = true; start() }

        var timedOut = false
        try {
            val deadline = System.currentTimeMillis() + timeoutMs
            while (isProcessAlive(process)) {
                currentCoroutineContext().ensureActive()
                if (System.currentTimeMillis() >= deadline) {
                    timedOut = true
                    process.destroy()
                    if (!process.waitFor(10, TimeUnit.SECONDS)) process.destroyForcibly()
                    break
                }
                delay(BASH_POLL_MS.milliseconds)
            }
            // Always settle the process and the collector before reading any output.
            runCatching { process.waitFor() }
            collector.join()
        } finally {
            if (isProcessAlive(process)) process.destroyForcibly()
            collector.join()
            runCatching { reader.close() }
        }

        val exitCode = if (timedOut) 143 else runCatching { process.exitValue() }.getOrElse { 0 }
        truncate(buildString {
            append("[$tag:${root.path}]")
            if (output.isEmpty()) {
                when {
                    timedOut -> {}
                    exitCode == 0 -> append("\n[exit:0, no output]")
                    else -> append('\n')
                }
            } else {
                append('\n')
                append(output)
                if (!endsWith("\n")) append('\n')
            }
            when {
                timedOut -> append("[TIMED OUT after ${timeoutMs / 1000}s, exit:SIGTERM]")
                exitCode != 0 -> {
                    append(app.getString(R.string.chat_terminal_exit_code, exitCode))
                    if (exitCode == 127 && tipIfCommandMissing) {
                        append("\n(Tip: Use install_ubuntu() to get full Python, pip, apt, and Linux tools)")
                    }
                }
            }
        })
    }

    private suspend fun shizukuShell(
        command: String,
        timeoutMs: Long,
    ): String = withContext(Dispatchers.IO) {
        when (checkShizukuStatus()) {
            ShizukuStatus.READY -> Unit
            ShizukuStatus.NOT_INSTALLED -> return@withContext "Tool failed: Shizuku is not installed on this device."
            ShizukuStatus.NOT_RUNNING -> return@withContext "Tool failed: Shizuku service is not running. Start Shizuku via wireless debugging or root, then try again."
            ShizukuStatus.UNAUTHORIZED -> return@withContext "Tool failed: Shizuku permission has not been granted to FancyAI. Authorize FancyAI in the Agents menu or Shizuku app."
        }
        val workingDir = "/data/local/tmp"
        val env = arrayOf(
            "PATH=/system/bin:/system/xbin:/vendor/bin:/apex/com.android.runtime/bin",
            "HOME=/data/local/tmp",
            "TMPDIR=/data/local/tmp",
        )
        val process = try {
            val method = Shizuku::class.java.getDeclaredMethod(
                "newProcess",
                Array<String>::class.java,
                Array<String>::class.java,
                String::class.java,
            )
            method.isAccessible = true
            method.invoke(null, arrayOf("/system/bin/sh", "-c", command), env, workingDir) as Process
        } catch (e: Throwable) {
            val cause = (e as? java.lang.reflect.InvocationTargetException)?.targetException ?: e
            return@withContext "Tool failed to spawn Shizuku process: ${cause.message ?: cause.javaClass.simpleName}"
        }

        val output = StringBuilder()
        val reader = process.inputStream.bufferedReader()
        val errorReader = process.errorStream.bufferedReader()
        val collector = Thread {
            reader.useLines { lines -> lines.forEach { output.append(it).append('\n') } }
        }.apply { isDaemon = true; start() }
        val errorCollector = Thread {
            errorReader.useLines { lines -> lines.forEach { output.append(it).append('\n') } }
        }.apply { isDaemon = true; start() }

        var timedOut = false
        try {
            val deadline = System.currentTimeMillis() + timeoutMs
            while (isProcessAlive(process)) {
                currentCoroutineContext().ensureActive()
                if (System.currentTimeMillis() >= deadline) {
                    timedOut = true
                    runCatching { process.destroy() }
                    break
                }
                delay(BASH_POLL_MS.milliseconds)
            }
            if (!timedOut) {
                runCatching { process.waitFor() }
            }
            collector.join()
            errorCollector.join()
        } finally {
            if (isProcessAlive(process)) {
                runCatching { process.destroy() }
            }
            collector.join()
            errorCollector.join()
            runCatching { reader.close() }
            runCatching { errorReader.close() }
        }

        val exitCode = if (timedOut) 143 else runCatching { process.exitValue() }.getOrElse { 0 }
        truncate(buildString {
            append("[shizuku:$workingDir]")
            if (output.isEmpty()) {
                when {
                    timedOut -> {}
                    exitCode == 0 -> append("\n[exit:0, no output]")
                    else -> append('\n')
                }
            } else {
                append('\n')
                append(output)
                if (!endsWith("\n")) append('\n')
            }
            when {
                timedOut -> append("[TIMED OUT after ${timeoutMs / 1000}s, exit:SIGTERM]")
                exitCode != 0 -> append(app.getString(R.string.chat_terminal_exit_code, exitCode))
            }
        })
    }

    private suspend fun readFile(path: String): String = withContext(Dispatchers.IO) {
        val file = resolve(path)
        if (!file.exists()) throw IOException("File not found: $path")
        if (file.isDirectory) throw IOException("Path is a directory: $path (use list_files to inspect directories)")
        val reader = InputStreamReader(FileInputStream(file), Charsets.UTF_8)
        val chunk = CharArray(OUTPUT_LIMIT + 1)
        val count = reader.use { it.read(chunk) }
        when {
            count < 0 || (count == 0 && file.length() == 0L) -> "(empty file)"
            count > OUTPUT_LIMIT ->
                String(chunk, 0, OUTPUT_LIMIT) + "\n(truncated at $OUTPUT_LIMIT characters)"
            else -> String(chunk, 0, count)
        }
    }

    private suspend fun writeFile(path: String, content: String): String = withContext(Dispatchers.IO) {
        val file = resolve(path)
        file.parentFile?.mkdirs()
        writeAtomicFile(file, content.toByteArray(Charsets.UTF_8))
        "Wrote ${content.length} characters to $path"
    }

    private suspend fun editFile(path: String, oldString: String, newString: String): String = withContext(Dispatchers.IO) {
        val file = resolve(path)
        if (!file.exists()) throw IOException("File not found: $path")
        val rawContent = file.readText(Charsets.UTF_8)
        val content = rawContent.replace("\r\n", "\n")
        val target = oldString.replace("\r\n", "\n")
        val replacement = newString.replace("\r\n", "\n")
        val first = content.indexOf(target)
        if (first == -1) throw IOException("old_string not found in $path")
        val occurrences = content.split(target).size - 1
        if (occurrences > 1) {
            throw IOException(
                "old_string occurs $occurrences times in $path; include more surrounding lines to make it unique",
            )
        }
        val edited = content.substring(0, first) + replacement + content.substring(first + target.length)
        writeAtomicFile(file, edited.toByteArray(Charsets.UTF_8))
        "Edited $path"
    }

    private suspend fun listFiles(path: String): String = withContext(Dispatchers.IO) {
        val directory = resolve(path.ifBlank { "." })
        if (!directory.exists()) throw IOException("Directory not found: $path")
        if (!directory.isDirectory) throw IOException("Path is a file: $path (use read_file to inspect files)")
        val listing = directory.listFiles().orEmpty()
            .sortedWith(compareBy({ !it.isDirectory }, { it.name.lowercase() }))
            .joinToString("\n") { entry -> if (entry.isDirectory) "${entry.name}/" else entry.name }
        truncate(listing.ifEmpty { "(empty directory)" })
    }

    /** Confines file operations to the workspace and phone storage. */
    private fun resolve(path: String): File {
        var raw = path.trim()
        if (raw.startsWith("~/")) raw = raw.removePrefix("~/")
        else if (raw == "~") raw = "."
        else if (raw.startsWith("/workspace/")) raw = raw.removePrefix("/workspace/")
        else if (raw == "/workspace") raw = "."
        val base = if (raw.startsWith('/')) File(raw) else File(root, raw)
        val canonical = base.canonicalFile
        if (canonical == root.canonicalFile || canonical.path.startsWith(root.canonicalPath + File.separator)) return canonical
        if (canonical == storageRoot.canonicalFile || canonical.path.startsWith(storageRoot.canonicalPath + File.separator)) return canonical
        throw IOException("Path must be inside the workspace (${root.path}) or phone storage (${storageRoot.path}): $path")
    }

    private suspend fun fetch(url: String): String = withContext(Dispatchers.IO) {
        val target = if (!url.startsWith("http://") && !url.startsWith("https://")) "https://$url" else url
        val endpoint = routePrivateHttp(target)
        val request = Request.Builder().url(endpoint.url).apply {
            endpoint.hostHeader?.let { header("Host", it) }
        }.build()
        fetchClient.newCall(request).execute().use { response ->
            val status = if (response.isSuccessful) "" else "HTTP ${response.code}\n"
            val source = response.body.source()
            source.request(FETCH_LIMIT.toLong() + 1)
            val buffered = source.buffer
            val body = when {
                buffered.size > FETCH_LIMIT.toLong() ->
                    buffered.readUtf8(FETCH_LIMIT.toLong()) + "\n(truncated at $FETCH_LIMIT characters)"
                buffered.size > 0L -> buildString {
                    append(buffered.readUtf8(buffered.size))
                    if (!source.exhausted()) append("\n(response body incomplete)")
                }
                else -> ""
            }
            (status + body).ifEmpty { "(empty response body)" }
        }
    }

    private suspend fun webSearch(query: String): String = withContext(Dispatchers.IO) {
        val endpoint = routePrivateHttp("https://lite.duckduckgo.com/lite/")
        val requestBody = okhttp3.FormBody.Builder()
            .add("q", query)
            .build()
        val request = Request.Builder().url(endpoint.url).apply {
            endpoint.hostHeader?.let { header("Host", it) }
            header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
        }.post(requestBody).build()
        fetchClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) return@use "Search failed: HTTP ${response.code}"
            val html = response.body.string()
            val results = mutableListOf<String>()
            val resultRegex = Regex("<td class='result-snippet'>([\\s\\S]*?)</td>", RegexOption.IGNORE_CASE)
            for (match in resultRegex.findAll(html).take(6)) {
                val text = match.groupValues[1]
                    .replace(Regex("<[^>]+>"), "")
                    .replace(Regex("&\\w+;"), " ")
                    .trim()
                if (text.isNotEmpty()) results.add("- $text")
            }
            if (results.isEmpty()) "No results found for '$query'. Try a different search."
            else "Search results for '$query':\n" + results.joinToString("\n")
        }
    }

    private fun missing(name: String): Nothing = throw IOException("Missing required tool input: $name")

    private fun JsonObject.text(vararg names: String): String? {
        for (name in names) {
            val primitive = this[name] as? JsonPrimitive
            if (primitive != null && primitive !is JsonNull) {
                primitive.contentOrNull?.let { return it }
            }
        }
        return null
    }

    private fun JsonObject.fallbackText(): String? =
        values.firstNotNullOfOrNull { (it as? JsonPrimitive)?.takeIf { p -> p !is JsonNull }?.contentOrNull }

    private fun truncate(text: String): String =
        if (text.length <= OUTPUT_LIMIT) text
        else text.take(OUTPUT_LIMIT) + "\n(truncated at $OUTPUT_LIMIT characters)"

    companion object {
        const val BASH_TIMEOUT_MS = 10L * 60_000
        const val BASH_POLL_MS = 150L
        const val OUTPUT_LIMIT = 48_000
        const val FETCH_LIMIT = 48_000

        internal fun toolDefinitionsJson(enableRoot: Boolean = false, enableShizuku: Boolean = false): String {
            val tools = mutableListOf(
                ToolDefinition(
                    type = "function",
                    function = ToolFunction(
                        name = "bash",
                        description = "Run a shell command. Defaults to proot Linux (Ubuntu/Debian) when installed, Shizuku or Android host shell otherwise. Output is stamped with the world it ran in, e.g. [proot:/workspace], [shizuku:...], or [android:...]. Background daemons (nohup ..., setsid ... &) are killed when the call returns unless detach=true.",
                        parameters = ToolParameters(
                            type = "object",
                            properties = mapOf(
                                "command" to ToolProperty("string", "The shell command to run"),
                                "mode" to ToolProperty("string", "Optional. 'proot' or 'ubuntu' (default when installed), 'shizuku' for ADB shell privileges, 'android' for unprivileged host shell, 'su' for root"),
                                "detach" to ToolProperty("boolean", "Optional. true launches the command without kill-on-exit and returns immediately, leaving backgrounded daemons running"),
                                "timeout_seconds" to ToolProperty("integer", "Optional. Kill the command after this many seconds and return the output collected so far"),
                            ),
                            required = listOf("command")
                        )
                    )
                ),
                ToolDefinition(
                    type = "function",
                    function = ToolFunction(
                        name = "install_ubuntu",
                        description = "Downloads and installs Ubuntu 24.04. Call this once if you need apt, python, pip, etc. Blocks until complete (takes ~1 minute).",
                        parameters = ToolParameters(
                            type = "object",
                            properties = emptyMap()
                        )
                    )
                ),
                ToolDefinition(
                    type = "function",
                    function = ToolFunction(
                        name = "read_file",
                        description = "Read a text file from workspace or phone storage (/sdcard)",
                        parameters = ToolParameters(
                            type = "object",
                            properties = mapOf("path" to ToolProperty("string", "File path")),
                            required = listOf("path")
                        )
                    )
                ),
                ToolDefinition(
                    type = "function",
                    function = ToolFunction(
                        name = "write_file",
                        description = "Create or overwrite a text file in workspace or phone storage",
                        parameters = ToolParameters(
                            type = "object",
                            properties = mapOf(
                                "path" to ToolProperty("string", "File path"),
                                "content" to ToolProperty("string", "Full file content to write")
                            ),
                            required = listOf("path", "content")
                        )
                    )
                ),
                ToolDefinition(
                    type = "function",
                    function = ToolFunction(
                        name = "edit_file",
                        description = "Replace an exact unique occurrence of old_string with new_string",
                        parameters = ToolParameters(
                            type = "object",
                            properties = mapOf(
                                "path" to ToolProperty("string", "File path"),
                                "old_string" to ToolProperty("string", "Exact existing snippet"),
                                "new_string" to ToolProperty("string", "Replacement snippet")
                            ),
                            required = listOf("path", "old_string", "new_string")
                        )
                    )
                ),
                ToolDefinition(
                    type = "function",
                    function = ToolFunction(
                        name = "list_files",
                        description = "List files in a directory in workspace or /sdcard",
                        parameters = ToolParameters(
                            type = "object",
                            properties = mapOf("path" to ToolProperty("string", "Directory path"))
                        )
                    )
                ),
                ToolDefinition(
                    type = "function",
                    function = ToolFunction(
                        name = "fetch_url",
                        description = "Fetch a URL via HTTP GET and return text body",
                        parameters = ToolParameters(
                            type = "object",
                            properties = mapOf("url" to ToolProperty("string", "URL to fetch")),
                            required = listOf("url")
                        )
                    )
                ),
                ToolDefinition(
                    type = "function",
                    function = ToolFunction(
                        name = "web_search",
                        description = "Search the internet for a query and return text snippets",
                        parameters = ToolParameters(
                            type = "object",
                            properties = mapOf("query" to ToolProperty("string", "The search query")),
                            required = listOf("query")
                        )
                    )
                )
            )
            if (enableShizuku) {
                tools.add(
                    ToolDefinition(
                        type = "function",
                        function = ToolFunction(
                            name = "shizuku_bash",
                            description = "Run a privileged shell command via Shizuku (ADB privileges, UID 2000). Use for package management (pm), activities (am), dumpsys, settings, and accessing device files without sandbox restrictions.",
                            parameters = ToolParameters(
                                type = "object",
                                properties = mapOf("command" to ToolProperty("string", "The shell command to run via Shizuku")),
                                required = listOf("command")
                            )
                        )
                    )
                )
            }
            if (enableRoot) {
                tools.add(
                    ToolDefinition(
                        type = "function",
                        function = ToolFunction(
                            name = "su_bash",
                            description = "Run a shell command as root using su -c",
                            parameters = ToolParameters(
                                type = "object",
                                properties = mapOf("command" to ToolProperty("string", "The shell command to run as root")),
                                required = listOf("command")
                            )
                        )
                    )
                )
            }
            return Json.encodeToString(tools)
        }

        internal fun agentSystemPrompt(workspace: File, storage: File): String = """
            You are an autonomous coding and task agent running on the user's Android device inside FancyAI.
            You solve tasks end-to-end by inspecting, executing commands, reading and editing files, and reporting results concisely.

            Workspace: relative paths and /workspace resolve inside $workspace.
            Storage: /sdcard and paths under $storage are phone storage.

            Tools:
            - bash(command, mode, detach, timeout_seconds): Runs shell commands. Defaults to proot Linux (Ubuntu) once install_ubuntu() has run; before that it uses Shizuku (if ready) or the Android host shell. mode="shizuku" runs with ADB shell privileges (UID 2000), mode="android" selects the unprivileged Android shell, and mode="su" runs as root. Every output starts with a stamp such as [proot:/workspace], [shizuku:...], or [android:...] identifying the environment. Each call is a fresh process: pass detach=true for background daemons (nohup ..., setsid ... &) so they survive the call, and timeout_seconds for long builds or decompiles.
            - read_file(path): Read a text file from the workspace or /sdcard.
            - write_file(path, content): Create or overwrite a text file; parent folders are created.
            - edit_file(path, old_string, new_string): Replace one exact unique occurrence of old_string with new_string.
            - list_files(path): List files and directories in a folder.
            - fetch_url(url): Fetch a web page via HTTP GET.
            - web_search(query): Search the internet for a query.

            TOOL CALLS:
            Invoke tools through the native function-calling interface provided with this request.
            Never write tool calls as plain text, markup, or code blocks - rendered call syntax
            gets truncated mid-stream and the call never executes. Call one or more tools,
            receive their outputs, then continue.

            Rules:
            1. Inspect before modifying: read files or list directory contents before making changes.
            2. Execute tool calls directly instead of talking about running them.
            3. Each round you can execute tools, wait for their outputs, and then continue.
            4. When finished, provide a concise summary of what was accomplished.
        """.trimIndent()
    }
}

@Serializable
internal data class ToolDefinition(
    val type: String,
    val function: ToolFunction
)

@Serializable
internal data class ToolFunction(
    val name: String,
    val description: String,
    val parameters: ToolParameters? = null
)

@Serializable
internal data class ToolParameters(
    val type: String,
    val properties: Map<String, ToolProperty>,
    val required: List<String> = emptyList()
)

@Serializable
internal data class ToolProperty(
    val type: String,
    val description: String
)
