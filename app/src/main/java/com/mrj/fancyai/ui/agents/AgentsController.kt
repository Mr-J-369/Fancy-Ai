package com.mrj.fancyai.ui.agents

import android.content.Context
import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import androidx.core.content.edit
import com.mrj.fancyai.R
import com.mrj.fancyai.service.InferenceForegroundService
import com.mrj.fancyai.service.llm.LlmEngineClient
import com.mrj.fancyai.service.llm.LlmExchange
import com.mrj.fancyai.service.llm.LlmInput
import com.mrj.fancyai.service.llm.LlmRequest
import com.mrj.fancyai.service.llm.AssistantProtocol
import com.mrj.fancyai.service.llm.MacroBus
import com.mrj.fancyai.ui.settings.LlmSettings
import com.mrj.fancyai.ui.settings.LlmSettingsStore
import com.mrj.fancyai.ui.settings.SelectedEngine
import com.mrj.fancyai.ui.settings.appSettings
import com.mrj.fancyai.ui.settings.sessionConfig
import com.mrj.fancyai.ui.settings.runtime
import com.mrj.fancyai.util.AppLog
import com.mrj.fancyai.util.writeAtomicFile
import java.io.File
import java.util.UUID

private const val TAG = "agents"
/** Bounds one user turn to 24 assistant tool rounds; the stop button cancels earlier. */
private const val MAX_TOOL_ROUNDS = 24

@Serializable
internal enum class AgentRole {
    @SerialName("user") USER,
    @SerialName("assistant") ASSISTANT,
    @SerialName("tool") TOOL,
    @SerialName("error") ERROR,
}

/** One rendered item of an agent conversation; persisted exactly as shown. */
@Serializable
internal data class AgentMessage(
    val id: String,
    val role: AgentRole,
    val label: String? = null,
    val text: String = "",
    val streaming: Boolean = true,
)

/**
 * A task conversation. State lives in [File]-backed conversations; the model
 * loop runs through [LlmEngineClient] following the active Settings runtime,
 * and tools are executed by [AgentTools].
 */
@Serializable
internal data class AgentConversation(
    val id: String,
    val agentId: String = "",
    val title: String,
    val updatedAt: Long = 0L,
    val messages: List<AgentMessage> = emptyList(),
    val apiHistory: List<JsonElement> = emptyList(),
)

internal data class ToolInvocation(
    val name: String,
    val arguments: String,
    val rawTag: String,
)

internal class AgentsController private constructor(context: Context) {
    private val app = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val store = File(app.filesDir, "agents")
    private val persistence = Mutex()
    private val conversationLock = Any()
    private val json = Json { ignoreUnknownKeys = true }
    private val tools = AgentTools(app)
    private var runtime: LlmEngineClient? = null
    private var runJob: Job? = null

    private val preferences = context.getSharedPreferences("agents_settings", Context.MODE_PRIVATE)

    val conversations = MutableStateFlow<Map<String, AgentConversation>>(emptyMap())
    val selectedConversationId = MutableStateFlow<String?>(null)
    val input = MutableStateFlow("")
    val running = MutableStateFlow<String?>(null)
    val modelName = MutableStateFlow(resolveModelName())
    val enableRoot = MutableStateFlow(false)
    val agentPrompt = MutableStateFlow(
        preferences.getString("agent_prompt", null) ?: AgentTools.agentSystemPrompt(tools.root, tools.storageRoot)
    )
    val thinking = MutableStateFlow(preferences.getBoolean("thinking", false))

    init {
        scope.launch { loadSavedConversations() }
    }

    fun refreshModel() {
        modelName.value = resolveModelName()
    }

    private fun resolveModelName(): String =
        LlmSettingsStore.snapshot(app)?.engine?.name
            ?: app.getString(R.string.agents_model_missing)

    private suspend fun loadSavedConversations() {
        withContext(Dispatchers.IO) {
            val files = store.listFiles { file -> file.isFile && file.name.endsWith(".json") }.orEmpty()
            val loaded = files.asSequence().mapNotNull { file ->
                val saved = runCatching { json.decodeFromString<AgentConversation>(file.readText()) }.getOrNull()
                    ?: return@mapNotNull null
                saved.copy(messages = saved.messages.map { message -> message.copy(streaming = false) })
            }.associateBy { it.id }
            conversations.value += loaded
        }
    }

    fun selectConversation(id: String?) {
        selectedConversationId.value = id
    }

    fun startNewConversation() {
        selectedConversationId.value = null
        input.value = ""
        running.value = null
    }

    fun deleteConversation(id: String) {
        if (running.value == id) return
        scope.launch {
            conversations.value -= id
            if (selectedConversationId.value == id) selectedConversationId.value = null
            withContext(Dispatchers.IO) { File(store, "$id.json").delete() }
        }
    }

    fun allConversations(): List<AgentConversation> =
        conversations.value.values.sortedByDescending { it.updatedAt }

    fun start() {
        val prompt = input.value.trim()
        if ((prompt.isEmpty()) || (running.value != null)) return

        val existing = conversations.value[selectedConversationId.value]
        val userMsg = AgentMessage(
            id = UUID.randomUUID().toString(),
            role = AgentRole.USER,
            text = prompt,
            streaming = false,
        )
        val conversation = existing?.copy(
            updatedAt = System.currentTimeMillis(),
            messages = existing.messages + userMsg,
        ) ?: AgentConversation(
            id = UUID.randomUUID().toString(),
            title = prompt.lineSequence().first().take(48),
            updatedAt = System.currentTimeMillis(),
            messages = listOf(userMsg),
        )
        conversations.value += (conversation.id to conversation)
        selectedConversationId.value = conversation.id
        input.value = ""
        persist(conversation.id)
        run(conversation)
    }

    private fun run(conversation: AgentConversation) {
        val conversationId = conversation.id
        running.value = conversationId
        InferenceForegroundService.start(app)
        AppLog.write(Log.INFO, TAG, "agent turn start rounds<=$MAX_TOOL_ROUNDS")
        runJob = scope.launch {
            try {
                val settings = withContext(Dispatchers.IO) { LlmSettingsStore.snapshot(app) }
                if (settings == null) {
                    appendError(conversationId, app.getString(R.string.agents_model_missing))
                    return@launch
                }
                val client = runtime ?: LlmEngineClient(app).also { runtime = it }
                agentLoop(conversationId, settings, client)
                
                val conv = conversations.value[conversationId]
                if (conv != null && conv.messages.count { it.role == AgentRole.USER } == 1) {
                    generateTitle(conversationId, settings, client)
                }
            } catch (cancelled: CancellationException) {
                withContext(NonCancellable) {
                    finalizeStreaming(conversationId)
                    persist(conversationId)
                }
                throw cancelled
            } catch (failure: Exception) {
                appendError(conversationId, failure.message ?: failure.javaClass.simpleName)
                finalizeStreaming(conversationId)
                persist(conversationId)
            } finally {
                if (running.value == conversationId) running.value = null
            }
        }
    }

    private suspend fun generateTitle(
        conversationId: String,
        settings: LlmSettings,
        client: LlmEngineClient,
    ) {
        val conv = conversations.value[conversationId] ?: return
        val firstUserMsg = conv.messages.firstOrNull { it.role == AgentRole.USER }?.text ?: return
        val prompt = LlmInput(
            text = "Summarize the following prompt into a short, 3-4 word title. Do not use quotes or formatting. Prompt: $firstUserMsg",
        )
        val config = settings.appSettings().sessionConfig("You are a helpful assistant.", history = emptyList())
        val request = LlmRequest(config = config, input = prompt, thinking = false)
        
        val titleBuilder = java.lang.StringBuilder()
        try {
            client.generate(request).collect { chunk ->
                titleBuilder.append(chunk.text)
            }
            val newTitle = titleBuilder.toString().trim().removeSurrounding("\"")
            if (newTitle.isNotBlank()) {
                mutate(conversationId) { it.copy(title = newTitle) }
                persist(conversationId)
            }
        } catch (_: Exception) {
            // Ignore failure, we just keep the default title
        }
    }

    private suspend fun agentLoop(
        conversationId: String,
        settings: LlmSettings,
        client: LlmEngineClient,
    ) {
        val currentMessages = conversations.value[conversationId]?.messages.orEmpty()
        var (history, currentInput) = buildExchangesAndInput(currentMessages)
        
        val macros = MacroBus(app)
        val systemInstructions = listOf(agentPrompt.value)
            
        val systemInstruction = AssistantProtocol.systemInstruction(settings.runtime, macros, systemInstructions, null)
        
        var round = 0

        while (true) {
            round++
            if (round > MAX_TOOL_ROUNDS) {
                appendError(conversationId, app.resources.getQuantityString(R.plurals.agents_round_limit, MAX_TOOL_ROUNDS, MAX_TOOL_ROUNDS))
                break
            }

            val config = settings.appSettings().sessionConfig(systemInstruction, history = history)
            val request = LlmRequest(config = config, input = currentInput, thinking = thinking.value)

            // Token Trimming Logic for Context Size
            val maxOutput = (settings.generation as? com.mrj.fancyai.ui.settings.GenerationSettings)?.maxOutputTokens?.takeIf { it > 0 } ?: 4096
            val budget = (settings.engine as? SelectedEngine)?.contextTokens?.minus(maxOutput + 512) ?: 4096
            if (history.isNotEmpty()) {
                val textsToCount = listOf(systemInstruction, currentInput.textWithContext()) + history.flatMap { listOf(it.input.textWithContext(), it.assistant) }
                val counts = client.countTokens(textsToCount)
                if (counts != null) {
                    var total = counts[0] + counts[1]
                    var keepHistory = 0
                    for (i in history.indices.reversed()) {
                        val exchangeTokens = counts[2 + i * 2] + counts[2 + i * 2 + 1]
                        if (total + exchangeTokens > budget) break
                        total += exchangeTokens
                        keepHistory++
                    }
                    if (keepHistory < history.size) {
                        history = history.takeLast(keepHistory)
                    }
                }
            }

            val streamedText = StringBuilder()
            val channelToolCall = StringBuilder()

            client.generate(request).collect { chunk ->
                if (chunk.text.isNotEmpty()) {
                    streamedText.append(chunk.text)
                    appendStreamingText(conversationId, chunk.text)
                }
                chunk.channels["tool_call"]?.let { channelToolCall.append(it) }
            }

            finalizeStreaming(conversationId)
            val fullResponse = streamedText.toString()
            val invocations = parseToolInvocations(fullResponse, channelToolCall.toString())

            if (invocations.isEmpty()) {
                persist(conversationId)
                break
            }

            cleanAssistantMessage(conversationId, invocations.map { it.rawTag })

            val toolOutputs = StringBuilder()
            for ((name, arguments) in invocations) {
                val cardId = toolCard(conversationId, name, arguments)
                val output = tools.execute(name, arguments)
                appendToolOutput(conversationId, cardId, output)
                if (toolOutputs.isNotEmpty()) toolOutputs.append("\n\n")
                toolOutputs.append("Tool ($name) output:\n$output")
            }

            history += LlmExchange(input = currentInput, assistant = fullResponse)
            currentInput = LlmInput(
                text = "$toolOutputs\n\nProceed with the next step or final answer.",
                toolsJson = AgentTools.toolDefinitionsJson(enableRoot.value),
            )

            persist(conversationId)
            AppLog.write(Log.INFO, TAG, "agent round $round executed ${invocations.size} tool calls")
        }
    }

    fun toggleRoot() {
        enableRoot.value = !enableRoot.value
    }
    
    fun updateAgentPrompt(prompt: String) {
        agentPrompt.value = prompt
        preferences.edit { putString("agent_prompt", prompt) }
    }

    fun updateThinking(value: Boolean) {
        thinking.value = value
        preferences.edit { putBoolean("thinking", value) }
    }
    
    fun resetAgentPrompt() {
        val defaultPrompt = AgentTools.agentSystemPrompt(tools.root, tools.storageRoot)
        updateAgentPrompt(defaultPrompt)
    }

    private fun buildExchangesAndInput(messages: List<AgentMessage>): Pair<List<LlmExchange>, LlmInput> {
        val exchanges = mutableListOf<LlmExchange>()
        var lastInput: LlmInput? = null
        val lastAssistant = StringBuilder()

        for (i in 0 until (messages.size - 1).coerceAtLeast(0)) {
            val msg = messages[i]
            when (msg.role) {
                AgentRole.USER -> {
                    if (lastInput != null) {
                        exchanges.add(LlmExchange(lastInput, lastAssistant.toString()))
                        lastAssistant.clear()
                    }
                    lastInput = LlmInput(text = msg.text, toolsJson = AgentTools.toolDefinitionsJson(enableRoot.value))
                }
                AgentRole.TOOL -> {
                    if (lastInput != null) {
                        exchanges.add(LlmExchange(lastInput, lastAssistant.toString()))
                        lastAssistant.clear()
                    }
                    lastInput = LlmInput(
                        text = "Tool result (${msg.label ?: "tool"}):\n${msg.text}",
                        toolsJson = AgentTools.toolDefinitionsJson(enableRoot.value),
                    )
                }
                AgentRole.ASSISTANT -> {
                    if (lastAssistant.isNotEmpty()) lastAssistant.append("\n\n")
                    lastAssistant.append(msg.text)
                }
                AgentRole.ERROR -> Unit
            }
        }

        if (lastInput != null && lastAssistant.isNotEmpty() && messages.size > 1 && messages.last().role == AgentRole.USER) {
            exchanges.add(LlmExchange(lastInput, lastAssistant.toString()))
        }

        val latestUser = messages.lastOrNull { it.role == AgentRole.USER }?.text.orEmpty()
        val initialInput = LlmInput(text = latestUser, toolsJson = AgentTools.toolDefinitionsJson(enableRoot.value))
        return exchanges to initialInput
    }

    private fun parseToolInvocations(text: String, channelToolCall: String): List<ToolInvocation> {
        val results = mutableListOf<ToolInvocation>()
        if (channelToolCall.isNotBlank()) {
            runCatching {
                fun addCall(element: JsonObject) {
                    val name = element["name"]?.jsonPrimitive?.contentOrNull
                        ?: (element["function"] as? JsonObject)?.get("name")?.jsonPrimitive?.contentOrNull
                    val rawArgs = element["arguments"]
                        ?: (element["function"] as? JsonObject)?.get("arguments")
                    val args = when (rawArgs) {
                        is JsonObject -> rawArgs.toString()
                        is JsonPrimitive -> rawArgs.contentOrNull ?: "{}"
                        else -> rawArgs?.toString() ?: "{}"
                    }
                    if (!name.isNullOrBlank()) results.add(ToolInvocation(name, args, ""))
                }
                when (val element = Json.parseToJsonElement(channelToolCall)) {
                    is JsonObject -> addCall(element)
                    is JsonArray -> element.filterIsInstance<JsonObject>().forEach(::addCall)
                    else -> Unit
                }
            }
        }
        for (match in bashRegex.findAll(text)) {
            val command = match.groupValues[1].trim()
            val args = buildJsonObject { put("command", command) }.toString()
            results.add(ToolInvocation("bash", args, match.value))
        }
        for (match in toolCallRegex.findAll(text)) {
            var body = match.groupValues[1].trim().removeSuffix("</tool_call>").trim()
            if (body.startsWith("```")) {
                body = body.substringAfter("\n").substringBeforeLast("```").trim()
            }
            if (body.startsWith("call:", ignoreCase = true)) {
                val afterCall = body.substring(5).trim()
                val name = afterCall.substringBefore(' ').substringBefore('{').trim()
                val rawArgs = afterCall.substring(name.length).trim().removeSuffix("</tool_call>").trim()
                val args = if (name == "bash" && !rawArgs.startsWith("{")) {
                    buildJsonObject { put("command", rawArgs) }.toString()
                } else rawArgs
                results.add(ToolInvocation(name, args, match.value))
                continue
            }
            val parsed = runCatching { Json.parseToJsonElement(body).jsonObject }.getOrNull()
            if (parsed != null) {
                val name = (parsed["name"] as? JsonPrimitive)?.contentOrNull
                    ?: (parsed["function"] as? JsonObject)?.get("name")?.jsonPrimitive?.contentOrNull
                    ?: "unknown"
                val rawArgs = parsed["arguments"]
                    ?: (parsed["function"] as? JsonObject)?.get("arguments")
                val args = when (rawArgs) {
                    is JsonObject -> rawArgs.toString()
                    is JsonPrimitive -> rawArgs.contentOrNull ?: "{}"
                    else -> body
                }
                results.add(ToolInvocation(name, args, match.value))
            }
        }
        for (match in gemmaToolCallRegex.findAll(text)) {
            val body = match.groupValues[1].trim().removeSuffix("</tool_call>").trim()
            if (body.startsWith("call:", ignoreCase = true)) {
                val afterCall = body.substring(5).trim()
                val name = afterCall.substringBefore(' ').substringBefore('{').trim()
                val rawArgs = afterCall.substring(name.length).trim().removeSuffix("</tool_call>").trim()
                val args = if (name == "bash" && !rawArgs.startsWith("{")) {
                    buildJsonObject { put("command", rawArgs) }.toString()
                } else rawArgs
                results.add(ToolInvocation(name, args, match.value))
            } else {
                val parsed = runCatching { Json.parseToJsonElement(body).jsonObject }.getOrNull()
                if (parsed != null) {
                    val name = (parsed["name"] as? JsonPrimitive)?.contentOrNull
                        ?: (parsed["function"] as? JsonObject)?.get("name")?.jsonPrimitive?.contentOrNull
                        ?: "unknown"
                    val rawArgs = parsed["arguments"]
                        ?: (parsed["function"] as? JsonObject)?.get("arguments")
                    val args = when (rawArgs) {
                        is JsonObject -> rawArgs.toString()
                        is JsonPrimitive -> rawArgs.contentOrNull ?: "{}"
                        else -> body
                    }
                    results.add(ToolInvocation(name, args, match.value))
                }
            }
        }
        if (results.isEmpty()) {
            for (match in callPrefixRegex.findAll(text)) {
                val name = match.groupValues[1].trim()
                val rawArgs = match.groupValues[2].trim()
                    .removeSuffix("</tool_call>")
                    .removeSuffix("<|im_end|>")
                    .trim()
                val args = if (name == "bash" && !rawArgs.startsWith("{")) {
                    buildJsonObject { put("command", rawArgs) }.toString()
                } else rawArgs
                results.add(ToolInvocation(name, args, match.value))
            }
        }
        return results
    }

    fun stop() {
        runtime?.cancel()
        runJob?.cancel()
    }

    private fun toolCard(conversationId: String, name: String, arguments: String): String {
        val id = UUID.randomUUID().toString()
        val displayText = when (name) {
            "bash" -> runCatching {
                val obj = Json.parseToJsonElement(arguments) as? JsonObject
                obj?.get("command")?.jsonPrimitive?.contentOrNull ?: obj?.get("cmd")?.jsonPrimitive?.contentOrNull ?: obj?.get("arg")?.jsonPrimitive?.contentOrNull
            }.getOrNull() ?: arguments
            "read_file", "write_file", "edit_file" -> runCatching {
                val obj = Json.parseToJsonElement(arguments) as? JsonObject
                obj?.get("path")?.jsonPrimitive?.contentOrNull ?: obj?.get("arg")?.jsonPrimitive?.contentOrNull
            }.getOrNull() ?: arguments
            "list_files" -> runCatching {
                val obj = Json.parseToJsonElement(arguments) as? JsonObject
                obj?.get("path")?.jsonPrimitive?.contentOrNull ?: obj?.get("dir")?.jsonPrimitive?.contentOrNull ?: obj?.get("arg")?.jsonPrimitive?.contentOrNull
            }.getOrNull()?.ifEmpty { "." } ?: arguments
            "fetch_url" -> runCatching {
                val obj = Json.parseToJsonElement(arguments) as? JsonObject
                obj?.get("url")?.jsonPrimitive?.contentOrNull ?: obj?.get("arg")?.jsonPrimitive?.contentOrNull
            }.getOrNull() ?: arguments
            "web_search" -> runCatching {
                val obj = Json.parseToJsonElement(arguments) as? JsonObject
                obj?.get("query")?.jsonPrimitive?.contentOrNull ?: obj?.get("q")?.jsonPrimitive?.contentOrNull ?: obj?.get("arg")?.jsonPrimitive?.contentOrNull
            }.getOrNull() ?: arguments
            else -> arguments
        }

        mutateMessages(conversationId) { messages ->
            messages + AgentMessage(
                id = id,
                role = AgentRole.TOOL,
                label = name,
                text = "$displayText\n\n…",
                streaming = false,
            )
        }
        return id
    }

    private fun appendToolOutput(conversationId: String, messageId: String, output: String) {
        mutateMessages(conversationId) { messages ->
            messages.map { message ->
                if (message.id == messageId) {
                    val inputPart = message.text.removeSuffix("\n\n…").trim()
                    val combined = if (inputPart.isBlank()) output else "$inputPart\n\n$output"
                    message.copy(text = combined)
                } else message
            }
        }
    }

    private fun cleanAssistantMessage(conversationId: String, rawTags: List<String>) {
        mutateMessages(conversationId) { messages ->
            val index = messages.indexOfLast { it.role == AgentRole.ASSISTANT }
            if (index == -1) messages
            else {
                var text = messages[index].text
                for (tag in rawTags) {
                    if (tag.isNotEmpty()) text = text.replace(tag, "")
                }
                text = text.trim()
                if (text.isEmpty()) {
                    messages.filterIndexed { i, _ -> i != index }
                } else {
                    messages.mapIndexed { i, msg ->
                        if (i == index) msg.copy(text = text, streaming = false) else msg
                    }
                }
            }
        }
    }

    private fun appendStreamingText(conversationId: String, delta: String) {
        mutateMessages(conversationId) { messages ->
            val index = messages.indexOfLast { it.role == AgentRole.ASSISTANT && it.streaming }
            if (index == -1) {
                messages + AgentMessage(
                    id = UUID.randomUUID().toString(),
                    role = AgentRole.ASSISTANT,
                    text = delta,
                )
            } else messages.mapIndexed { position, message ->
                if (position == index) message.copy(text = message.text + delta) else message
            }
        }
    }

    private fun finalizeStreaming(conversationId: String) {
        mutateMessages(conversationId) { messages ->
            messages.map { message ->
                if (message.streaming && message.role != AgentRole.TOOL) message.copy(streaming = false) else message
            }
        }
    }

    private fun appendError(conversationId: String, text: String) {
        finalizeStreaming(conversationId)
        mutateMessages(conversationId) { messages ->
            messages + AgentMessage(
                id = UUID.randomUUID().toString(),
                role = AgentRole.ERROR,
                text = text,
                streaming = false,
            )
        }
    }

    private fun mutate(conversationId: String, transform: (AgentConversation) -> AgentConversation) {
        synchronized(conversationLock) {
            val map = conversations.value
            val conversation = map[conversationId] ?: return
            conversations.value = map + (conversationId to transform(conversation))
        }
    }

    private fun mutateMessages(
        conversationId: String,
        transform: (List<AgentMessage>) -> List<AgentMessage>,
    ) {
        mutate(conversationId) { it.copy(messages = transform(it.messages)) }
    }

    private fun persist(conversationId: String) = scope.launch {
        val conversation = conversations.value[conversationId] ?: return@launch
        persistence.withLock {
            withContext(Dispatchers.IO) {
                store.mkdirs()
                writeAtomicFile(File(store, "$conversationId.json"), json.encodeToString(conversation).toByteArray())
            }
        }
    }

    companion object {
        private val bashRegex = Regex("<bash>([\\s\\S]*?)</bash>", RegexOption.IGNORE_CASE)
        private val toolCallRegex = Regex("<tool_call>([\\s\\S]*?)</tool_call>", RegexOption.IGNORE_CASE)
        private val gemmaToolCallRegex = Regex("<\\|tool_call>([\\s\\S]*?)(?:<\\|tool_response>|<\\|tool_call>|<\\|im_end>|$)", RegexOption.IGNORE_CASE)
        private val callPrefixRegex = Regex("(?:^|\\n)call:\\s*([a-zA-Z0-9_]+)[\\s{]([\\s\\S]*?)(?:\\ncall:|$)", RegexOption.IGNORE_CASE)

        @android.annotation.SuppressLint("StaticFieldLeak")
        @Volatile private var instance: AgentsController? = null

        fun get(context: Context): AgentsController = instance ?: synchronized(this) {
            instance ?: AgentsController(context).also { instance = it }
        }
    }
}
