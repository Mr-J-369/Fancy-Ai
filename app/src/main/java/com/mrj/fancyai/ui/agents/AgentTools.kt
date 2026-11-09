package com.mrj.fancyai.ui.agents

import android.content.Context
import android.os.Environment
import android.system.Os
import com.mrj.fancyai.R
import com.mrj.fancyai.util.PrivateHttp.decodeLocalRoute
import com.mrj.fancyai.util.PrivateHttp.routePrivateHttp
import com.mrj.fancyai.util.writeAtomicFile
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
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
import java.io.File
import java.io.FileInputStream
import java.io.IOException
import java.io.InputStreamReader
import java.nio.file.Files
import java.nio.file.Paths
import java.util.concurrent.TimeUnit
import java.util.zip.ZipInputStream
import kotlin.time.Duration.Companion.milliseconds

/**
 * Executes agent tool calls. bash runs in the agent's private workspace using the native
 * Termux userland environment (with bash, apt, and Linux tools via lightweight PRoot path virtualization);
 * file tools operate in the workspace and phone storage; fetch_url performs HTTP GETs.
 */
@android.annotation.SuppressLint("SdCardPath")
internal class AgentTools(context: Context) {
    private val app = context.applicationContext
    val root: File = File(app.filesDir, "agents/workspace").apply { mkdirs() }
    val storageRoot: File = Environment.getExternalStorageDirectory()
    val termuxDir: File = File(app.filesDir, "agents/termux")
    val termuxUsr: File = File(termuxDir, "usr")
    private val tmpDir: File = File(app.filesDir, "agents/tmp").apply { mkdirs() }
    private val termuxLock = Mutex()

    private val fetchClient = OkHttpClient.Builder()
        .dns { hostname -> decodeLocalRoute(hostname)?.let(::listOf) ?: Dns.SYSTEM.lookup(hostname) }
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(90, TimeUnit.SECONDS)
        .build()

    @Volatile var activeBashProcess: Process? = null

    fun isTermuxInstalled(): Boolean =
        File(termuxDir, ".installed").isFile && File(termuxUsr, "bin/bash").canExecute()

    suspend fun installTermux(): String = withContext(Dispatchers.IO) {
        termuxLock.withLock {
            if (isTermuxInstalled()) return@withContext "Termux environment is already installed."
            termuxDir.mkdirs()
            val tempZip = File(termuxDir, "bootstrap.tmp.zip")
            val tempUsr = File(termuxDir, "usr.tmp")
            deleteRecursivelySafe(tempUsr)
            tempZip.delete()

            val urls = listOf(
                "https://github.com/termux/termux-packages/releases/latest/download/bootstrap-aarch64.zip",
                "https://github.com/termux/termux-packages/releases/download/bootstrap-2025.03.28-r1%2Bapt-android-7/bootstrap-aarch64.zip",
            )

            var downloaded = false
            var lastError: Exception? = null
            for (url in urls) {
                try {
                    val request = Request.Builder().url(url).build()
                    fetchClient.newCall(request).execute().use { response ->
                        if (!response.isSuccessful) throw IOException("HTTP ${response.code}")
                        val body = response.body
                        tempZip.outputStream().buffered().use { out ->
                            body.byteStream().use { input -> input.copyTo(out) }
                        }
                    }
                    downloaded = true
                    break
                } catch (e: Exception) {
                    lastError = e
                    tempZip.delete()
                }
            }

            if (!downloaded) {
                return@withContext "Failed to download Termux bootstrap: ${lastError?.message ?: "Network error"}"
            }

            try {
                tempUsr.mkdirs()
                ZipInputStream(tempZip.inputStream().buffered()).use { zis ->
                    var entry = zis.nextEntry
                    while (entry != null) {
                        val file = File(tempUsr, entry.name)
                        if (entry.isDirectory) {
                            file.mkdirs()
                        } else {
                            file.parentFile?.mkdirs()
                            file.outputStream().buffered().use { out -> zis.copyTo(out) }
                            if (entry.name.startsWith("bin/") || entry.name.startsWith("lib/")) {
                                runCatching { Os.chmod(file.path, 0x1ed) }
                            }
                        }
                        zis.closeEntry()
                        entry = zis.nextEntry
                    }
                }

                val symlinks = File(tempUsr, "SYMLINKS.txt")
                if (symlinks.isFile) {
                    symlinks.useLines { lines ->
                        for (line in lines) {
                            val parts = line.split("←", limit = 2)
                            if (parts.size == 2) {
                                val target = parts[0]
                                val linkRel = parts[1].removePrefix("./")
                                val linkFile = File(tempUsr, linkRel)
                                linkFile.parentFile?.mkdirs()
                                linkFile.delete()
                                runCatching {
                                    Files.createSymbolicLink(linkFile.toPath(), Paths.get(target))
                                }
                            }
                        }
                    }
                    symlinks.delete()
                }

                val binDir = File(tempUsr, "bin")
                binDir.listFiles()?.forEach { file ->
                    runCatching { Os.chmod(file.path, 0x1ed) }
                }
                File(tempUsr, "tmp").mkdirs()
                File(tempUsr, "var/cache/apt/archives/partial").mkdirs()
                File(termuxDir, "cache/apt/archives/partial").mkdirs()
                val etcDir = File(tempUsr, "etc").apply { mkdirs() }
                File(etcDir, "resolv.conf").writeText("nameserver 8.8.8.8\nnameserver 1.1.1.1\n")

                deleteRecursivelySafe(termuxUsr)
                if (!tempUsr.renameTo(termuxUsr)) {
                    throw IOException("Failed to activate Termux directory")
                }
                File(termuxDir, ".installed").writeText("termux-aarch64")
                "Termux environment installed successfully. Full bash, apt, coreutils, and Linux packages are now ready."
            } catch (e: Exception) {
                deleteRecursivelySafe(tempUsr)
                "Failed to extract Termux bootstrap: ${e.message}"
            } finally {
                tempZip.delete()
            }
        }
    }

    private fun deleteRecursivelySafe(file: File) {
        if (!file.exists()) return
        try {
            file.walkBottomUp().forEach {
                it.setWritable(true)
                it.delete()
            }
        } catch (_: Exception) {}
    }

    /** Dispatches one tool call; the returned string is the tool message content the model sees. */
    suspend fun execute(name: String, arguments: String): String {
        val trimmed = arguments.trim()
        val input = parseArgumentsObject(trimmed)
            ?: when {
                trimmed.isNotEmpty() && trimmed != "{}" -> when (name) {
                    "bash", "su_bash" -> buildJsonObject { put("command", trimmed) }
                    "read_file", "list_files" -> buildJsonObject { put("path", trimmed) }
                    "fetch_url" -> buildJsonObject { put("url", trimmed) }
                    "web_search" -> buildJsonObject { put("query", trimmed) }
                    else -> buildJsonObject {}
                }
                else -> buildJsonObject {}
            }
        return try {
            when (name) {
                "bash" -> {
                    val cmd = input.text("command", "cmd", "arg", "script", "code") ?: input.fallbackText() ?: missing("command")
                    if (cmd.trim().isEmpty()) {
                        "Tool failed: Command cannot be empty"
                    } else {
                        bash(command = cmd, mode = input.text("mode"))
                    }
                }
                "su_bash" -> {
                    val cmd = input.text("command", "cmd", "arg", "script", "code") ?: input.fallbackText() ?: missing("command")
                    if (cmd.trim().isEmpty()) {
                        "Tool failed: Command cannot be empty"
                    } else {
                        bash(command = cmd, mode = "su")
                    }
                }
                "setup_termux", "install_termux" -> installTermux()
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
                "install_ubuntu" -> "Ubuntu is replaced by the Termux base environment with bash, apt, and Linux packages."
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

    private suspend fun bash(command: String, mode: String? = null): String = withContext(Dispatchers.IO) {
        root.mkdirs()
        tmpDir.mkdirs()

        val native = app.applicationInfo.nativeLibraryDir
        val prootBin = File(native, "libfancy_proot.so")
        val prootLoader = File(native, "libfancy_proot_loader.so")

        if (!isTermuxInstalled() && prootBin.canExecute() && mode != "su") {
            installTermux()
        }

        val builder: ProcessBuilder = if (mode != "su" && isTermuxInstalled() && prootBin.canExecute()) {
            val rootfs = File(termuxDir, "rootfs").apply {
                listOf(
                    "dev", "proc", "sys", "system", "apex", "linkerconfig",
                    "sdcard", "storage", "data", "data/data/com.termux/files",
                    "workspace", "usr", "tmp",
                ).forEach { File(this, it).mkdirs() }
            }
            val termuxCache = File(termuxDir, "cache").apply {
                File(this, "apt/archives/partial").mkdirs()
            }
            val termuxHome = File(root, ".termux_home").apply { mkdirs() }

            val prootArgs = mutableListOf(
                prootBin.path,
                "--kill-on-exit",
                "--link2symlink",
                "--sysvipc",
                "-i", "10000:10000",
                "-r", rootfs.path,
                "-w", "/workspace",
                "-b", "/dev",
                "-b", "/proc",
                "-b", "/sys",
                "-b", "/system",
                "-b", "/apex",
                "-b", "/linkerconfig",
                "-b", "${termuxUsr.path}:/data/data/com.termux/files/usr",
                "-b", "${termuxUsr.path}:/usr",
                "-b", "${termuxCache.path}:/data/data/com.termux/cache",
                "-b", "${termuxHome.path}:/data/data/com.termux/files/home",
                "-b", "${root.path}:/workspace",
            )
            val sdcard = File("/storage/emulated/0")
            if (sdcard.exists()) {
                prootArgs.addAll(listOf("-b", "${sdcard.path}:/sdcard", "-b", sdcard.path))
            }
            if (File("/data").isDirectory) {
                prootArgs.addAll(listOf("-b", "/data"))
            }
            if (File("/vendor").isDirectory) {
                prootArgs.addAll(listOf("-b", "/vendor"))
            }

            prootArgs.addAll(
                listOf(
                    "/data/data/com.termux/files/usr/bin/env", "-i",
                    "HOME=/workspace",
                    "USER=u0_a100",
                    "LOGNAME=u0_a100",
                    "TERM=xterm-256color",
                    "COLORTERM=truecolor",
                    "LANG=C.UTF-8",
                    "SHELL=/data/data/com.termux/files/usr/bin/bash",
                    "PREFIX=/data/data/com.termux/files/usr",
                    "TMPDIR=/data/data/com.termux/files/usr/tmp",
                    "PATH=/data/data/com.termux/files/usr/bin:/data/data/com.termux/files/usr/bin/applets:/system/bin:/system/xbin:/vendor/bin:/apex/com.android.runtime/bin",
                    "/data/data/com.termux/files/usr/bin/bash", "-c", command,
                )
            )

            ProcessBuilder(prootArgs)
                .directory(root)
                .redirectErrorStream(true)
                .also { pb ->
                    val env = pb.environment()
                    env["PROOT_LOADER"] = prootLoader.path
                    env["PROOT_TMP_DIR"] = tmpDir.path
                    env["HOME"] = root.path
                    env["TMPDIR"] = tmpDir.path
                    env["TERM"] = "xterm-256color"
                }
        } else {
            val executable = if (mode == "su") "/system/bin/su" else "/system/bin/sh"
            ProcessBuilder(executable, "-c", command)
                .directory(root)
                .redirectErrorStream(true)
                .also { pb ->
                    val env = pb.environment()
                    env["PATH"] = "/system/bin:/system/xbin:/vendor/bin:/apex/com.android.runtime/bin"
                    env["HOME"] = root.path
                    env["TMPDIR"] = tmpDir.path
                    env["WORKSPACE"] = root.path
                    env["TERM"] = "xterm-256color"
                    env["SHELL"] = executable
                }
        }

        val process = try {
            builder.start()
        } catch (e: Exception) {
            return@withContext "Failed to start process: ${e.message ?: e.javaClass.simpleName}"
        }
        activeBashProcess = process
        val output = StringBuilder()
        val reader = process.inputStream.bufferedReader()
        val started = System.currentTimeMillis()

        val collector = Thread {
            try {
                val buffer = CharArray(4096)
                var count: Int
                while (reader.read(buffer).also { count = it } != -1) {
                    synchronized(output) {
                        if (output.length < OUTPUT_LIMIT) {
                            output.appendRange(buffer, 0, minOf(count, OUTPUT_LIMIT - output.length))
                        }
                    }
                }
            } catch (_: Throwable) {
                // Expected when process is destroyed or streams closed on stop/timeout
            }
        }.apply {
            name = "agent-shell-collector"
            isDaemon = true
            start()
        }

        try {
            while (process.isAlive) {
                currentCoroutineContext().ensureActive()
                if (System.currentTimeMillis() - started > BASH_TIMEOUT_MS) {
                    process.destroyForcibly()
                    synchronized(output) {
                        output.append("\n(killed: command ran longer than 10 minutes)")
                    }
                    break
                }
                delay(BASH_POLL_MS.milliseconds)
            }
            process.waitFor(5, TimeUnit.SECONDS)
        } finally {
            process.destroyForcibly()
            activeBashProcess = null
            runCatching { reader.close() }
            runCatching { process.inputStream.close() }
            runCatching { process.errorStream.close() }
            runCatching { process.outputStream.close() }
            runCatching { collector.join(500) }
        }

        val collected = !process.isAlive
        val resultText = synchronized(output) { output.toString() }
        val finalOutput = buildString {
            append(resultText)
            if (collected && process.exitValue() != 0) {
                if (isNotEmpty() && !endsWith("\n")) append('\n')
                append(app.getString(R.string.chat_terminal_exit_code, process.exitValue()))
            }
        }
        truncate(finalOutput.ifBlank { "(empty output)" })
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

        internal fun parseArgumentsObject(raw: String): JsonObject? {
            if (raw.isBlank() || raw == "{}" || raw == "{\n}") return null
            val standard = runCatching { Json.parseToJsonElement(raw) as? JsonObject }.getOrNull()
            if (standard != null && standard.isNotEmpty()) {
                return buildJsonObject {
                    for ((k, v) in standard) {
                        val cleanKey = k.trim()
                            .removeSurrounding("\"")
                            .removeSurrounding("'")
                            .replace("\\\"", "")
                            .trim()
                        put(cleanKey, v)
                    }
                }
            }

            val content = if (raw.startsWith("{") && raw.endsWith("}")) {
                raw.substring(1, raw.length - 1).trim()
            } else {
                raw
            }
            val pairRegex = Regex("([a-zA-Z0-9_\"'\\\\]+)\\s*[:=]\\s*(?:\"([^\"]*)\"|'([^']*)'|([^,}\n]+))")
            val pairs = pairRegex.findAll(content).toList()
            if (pairs.isNotEmpty()) {
                return buildJsonObject {
                    for (match in pairs) {
                        val key = match.groupValues[1].trim()
                            .removeSurrounding("\"")
                            .removeSurrounding("'")
                            .replace("\\\"", "")
                            .trim()
                        val value = (match.groupValues[2].takeIf { it.isNotEmpty() }
                            ?: match.groupValues[3].takeIf { it.isNotEmpty() }
                            ?: match.groupValues[4]).trim()
                        put(key, value)
                    }
                }
            }
            return standard
        }

        internal fun toolDefinitionsJson(enableRoot: Boolean = false): String {
            val tools = mutableListOf(
                ToolDefinition(
                    type = "function",
                    function = ToolFunction(
                        name = "bash",
                        description = "Run a shell command in the agent workspace. Uses the Termux Linux environment (bash, apt, coreutils, python).",
                        parameters = ToolParameters(
                            type = "object",
                            properties = mapOf(
                                "command" to ToolProperty("string", "The shell command to run")
                            ),
                            required = listOf("command")
                        )
                    )
                ),
                ToolDefinition(
                    type = "function",
                    function = ToolFunction(
                        name = "setup_termux",
                        description = "Installs or updates the Termux base environment with bash, apt, and Linux tools.",
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

            Workspace: relative paths and files resolve inside the workspace ($workspace).
            Storage: /sdcard and paths under $storage are phone storage.

            Tools:
            - bash(command): Runs shell commands in the agent workspace using the Termux environment (with bash, apt, python, coreutils).
            - setup_termux(): Sets up or refreshes the Termux environment if needed.
            - read_file(path): Read a text file from the workspace or /sdcard.
            - write_file(path, content): Create or overwrite a text file; parent folders are created.
            - edit_file(path, old_string, new_string): Replace one exact unique occurrence of old_string with new_string.
            - list_files(path): List files and directories in a folder.
            - fetch_url(url): Fetch a web page via HTTP GET.
            - web_search(query): Search the internet for a query.

            TOOL CALL FORMAT:
            To execute a tool, invoke tools natively or output a tool call block:
            <tool_call>
            {"name": "tool_name", "arguments": {"parameter": "value"}}
            </tool_call>
            OR:
            <bash>shell command to run</bash>

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
