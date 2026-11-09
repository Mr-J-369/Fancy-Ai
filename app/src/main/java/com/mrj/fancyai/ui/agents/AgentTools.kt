package com.mrj.fancyai.ui.agents

import android.content.Context
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
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import kotlinx.serialization.Serializable
import okhttp3.Dns
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.BufferedReader
import java.io.File
import java.io.FileInputStream
import java.io.IOException
import java.io.InputStreamReader
import java.util.concurrent.TimeUnit
import kotlin.time.Duration.Companion.milliseconds

/**
 * Executes agent tool calls. bash runs in the shared workspace, executing inside Ubuntu 24.04 ARM64
 * (if installed via Terminal) with full Linux tools, or falling back to Android's shell;
 * file tools operate in the workspace and phone storage; fetch_url performs HTTP GETs.
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

    /** Dispatches one tool call; the returned string is the tool message content the model sees. */
    suspend fun execute(name: String, arguments: String): String {
        val trimmed = arguments.trim()
        val input = runCatching { Json.parseToJsonElement(trimmed.ifBlank { "{}" }) as? JsonObject }.getOrNull()
            ?: when (name) {
                "bash" -> buildJsonObject { put("command", trimmed) }
                "read_file", "list_files" -> buildJsonObject { put("path", trimmed) }
                "fetch_url" -> buildJsonObject { put("url", trimmed) }
                else -> return "Tool failed: the tool input must be a JSON object"
            }
        return try {
            when (name) {
                "bash" -> bash(input.text("command", "cmd", "arg") ?: input.fallbackText() ?: missing("command"))
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

    private suspend fun bash(command: String): String = withContext(Dispatchers.IO) {
        root.mkdirs()
        val distribution = when {
            workspace.environments.installed(LinuxDistribution.UBUNTU) -> LinuxDistribution.UBUNTU
            workspace.environments.installed(LinuxDistribution.DEBIAN) -> LinuxDistribution.DEBIAN
            else -> null
        }
        if (distribution != null) {
            val result = TerminalCommandRunner.execute(
                environments = workspace.environments,
                distribution = distribution,
                command = command,
                timeoutMs = BASH_TIMEOUT_MS,
            )
            val output = buildString {
                append(result.output)
                if (result.exitCode != 0) {
                    if (isNotEmpty() && !endsWith("\n")) append('\n')
                    append(app.getString(R.string.chat_terminal_exit_code, result.exitCode))
                }
            }
            return@withContext truncate(output.ifBlank { "(empty output)" })
        }

        val builder = ProcessBuilder("/system/bin/sh", "-c", command)
            .directory(root)
            .redirectErrorStream(true)
        builder.environment().apply {
            put("HOME", root.path)
            put("TMPDIR", root.path)
            put("PATH", "/system/bin:/system/xbin:/apex/com.android.runtime/bin")
        }
        val process = builder.start()
        val output = StringBuilder()
        val reader = process.inputStream.bufferedReader()
        val started = System.currentTimeMillis()
        try {
            while (process.isAlive) {
                currentCoroutineContext().ensureActive()
                drain(reader, output)
                if (process.isAlive && System.currentTimeMillis() - started > BASH_TIMEOUT_MS) {
                    process.destroyForcibly()
                    output.append("\n(killed: command ran longer than 10 minutes)")
                    break
                }
                delay(BASH_POLL_MS.milliseconds)
            }
        } finally {
            process.destroyForcibly()
        }
        drain(reader, output)
        val collected = process.waitFor(5, TimeUnit.SECONDS)
        if (collected && process.exitValue() != 0) {
            if (output.isNotEmpty() && !output.endsWith("\n")) output.append('\n')
            output.append(app.getString(R.string.chat_terminal_exit_code, process.exitValue()))
            if (process.exitValue() == 127) {
                output.append("\n(Tip: Install Ubuntu in the Terminal mini-app for full Python, pip, apt, and Linux tools)")
            }
        }
        truncate(output.toString().ifBlank { "(empty output)" })
    }

    private fun drain(reader: BufferedReader, output: StringBuilder) {
        while (reader.ready()) {
            reader.readLine()?.let { line -> output.append(line).append('\n') }
        }
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
        const val OUTPUT_LIMIT = 24_000
        const val FETCH_LIMIT = 48_000

        internal fun toolDefinitionsJson(): String = Json.encodeToString(
            listOf(
                ToolDefinition(
                    type = "function",
                    function = ToolFunction(
                        name = "bash",
                        description = "Run a shell command in the workspace (/workspace)",
                        parameters = ToolParameters(
                            type = "object",
                            properties = mapOf("command" to ToolProperty("string", "The shell command to run")),
                            required = listOf("command")
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
        )

        internal fun agentSystemPrompt(workspace: File, storage: File): String = """
            You are an autonomous coding and task agent running on the user's Android device inside FancyAI.
            You solve tasks end-to-end by inspecting, executing commands, reading and editing files, and reporting results concisely.

            Workspace: relative paths and /workspace resolve inside $workspace.
            Storage: /sdcard and paths under $storage are phone storage.

            Tools:
            - bash(command): Runs shell commands in the workspace (/workspace). When Ubuntu 24.04 is installed via Terminal, commands run in full Ubuntu ARM64 with python3, pip, apt, git, curl, etc. Otherwise commands run via Android sh.
            - read_file(path): Read a text file from the workspace or /sdcard.
            - write_file(path, content): Create or overwrite a text file; parent folders are created.
            - edit_file(path, old_string, new_string): Replace one exact unique occurrence of old_string with new_string.
            - list_files(path): List files and directories in a folder.
            - fetch_url(url): Fetch a web page via HTTP GET.
            - web_search(query): Search the internet for a query.

            TOOL CALL FORMAT:
            To execute a tool, output a tool call block:
            <|tool_call>call:tool_name {"command": "value"}
            OR:
            <tool_call>
            {"name": "tool_name", "arguments": {"command": "value"}}
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
