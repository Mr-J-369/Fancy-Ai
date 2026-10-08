package com.mrj.fancyai.ui.chat

import android.content.Context
import android.net.Uri
import androidx.core.content.edit
import androidx.core.util.AtomicFile
import com.mrj.fancyai.service.llm.ImagePrompt
import com.mrj.fancyai.service.llm.LlmInput
import com.mrj.fancyai.service.llm.SocialPostJson
import com.mrj.fancyai.util.decodeImage
import com.mrj.fancyai.util.writeAtomicFile
import kotlinx.coroutines.sync.Mutex
import kotlinx.serialization.Required
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

internal const val CHAT_TITLE_LIMIT = 60

internal fun appendSpeechToDraft(draft: String, speech: String): String {
    val spoken = speech.trim()
    if (spoken.isEmpty()) return draft
    return when {
        (draft.isEmpty() || draft.last().isWhitespace()) -> draft + spoken
        else -> "$draft $spoken"
    }
}

@Serializable
internal data class ChatTurn(
    val user: String = "",
    val assistant: String = "",
    val channels: Map<String, String> = emptyMap(),
    val imagePath: String? = null,
    val userImagePath: String? = null,
    val userImageDescription: String? = null,
    val modelInput: LlmInput? = null,
    val imageRequested: Boolean = false,
    val commandOutput: String? = null,
    val commandExitCode: Int? = null,
)

internal object SudoCommand {
    private val SUDO_TAG_BLOCK = Regex("""<\s*sudo\s*>(.*?)(<\s*/\s*sudo\s*>|$)""", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
    private val FENCED_CODE_BLOCK = Regex("""```(?:bash|sh|sudo)?\s*\n?(sudo\s+[\s\S]*?)```""", RegexOption.IGNORE_CASE)
    private val INLINE_CODE_BLOCK = Regex("""`\s*(sudo\s+[^`\n]+)\s*`""", RegexOption.IGNORE_CASE)

    fun extract(output: String): String? {
        val tagMatch = SUDO_TAG_BLOCK.find(output)?.groupValues?.get(1)?.trim()?.takeIf(String::isNotEmpty)
        val fencedMatch = tagMatch ?: FENCED_CODE_BLOCK.find(output)?.groupValues?.get(1)?.trim()?.takeIf(String::isNotEmpty)
        return fencedMatch ?: INLINE_CODE_BLOCK.find(output)?.groupValues?.get(1)?.trim()?.takeIf(String::isNotEmpty)
    }

    fun strip(output: String): String =
        SUDO_TAG_BLOCK.replace(output, "").trim()
}

internal val ChatTurn.sudoCommand: String?
    get() = SudoCommand.extract(assistant)

internal val ChatTurn.replyText: String
    get() = if (imageRequested) "" else SocialPostJson.field(assistant, "reply")?.takeIf(String::isNotBlank)
        ?: SudoCommand.strip(ImagePrompt.split(assistant).first)

private val IMAGE_TOOL_NAMES = setOf("generate_picture", "image_generation", "generate_image")

internal fun extractToolPrompt(toolCallJson: String?): String? {
    if (toolCallJson.isNullOrBlank()) return null
    return runCatching {
        val array = JSONArray(toolCallJson)
        for (i in 0 until array.length()) {
            val call = array.optJSONObject(i) ?: continue
            val name = call.optString("name")
            if (name in IMAGE_TOOL_NAMES) {
                val argsRaw = call.optString("arguments")
                if (argsRaw.isNotBlank()) {
                    val args = if (argsRaw.startsWith("{")) JSONObject(argsRaw) else call.optJSONObject("arguments")
                    val prompt = args?.optString("prompt")?.takeIf(String::isNotBlank)
                        ?: args?.optString("description")?.takeIf(String::isNotBlank)
                        ?: args?.optString("query")?.takeIf(String::isNotBlank)
                        ?: argsRaw.takeUnless { it.startsWith("{") }?.takeIf(String::isNotBlank)
                    prompt?.let { return@runCatching it }
                }
            }
        }
        null
    }.getOrNull()
}

internal val ChatTurn.imagePromptText: String?
    get() = ImagePrompt.split(assistant, imageOnly = imageRequested).second
        ?: extractToolPrompt(channels["tool_call"])

internal fun ChatTurn.withReplyText(reply: String): ChatTurn = copy(
    assistant = reply +
        sudoCommand?.let { "\n\n<sudo>$it</sudo>" }.orEmpty() +
        imagePromptText?.let { "\n\n<scene_prompt>$it</scene_prompt>" }.orEmpty(),
)

internal data class SpeakingReply(
    val conversationId: String,
    val turnIndex: Int,
)

@Serializable
internal data class ChatConversation(
    @Required val id: String = "",
    val title: String = "",
    val updatedAt: Long = System.currentTimeMillis(),
    val turns: List<ChatTurn> = emptyList(),
)

internal val chatPersistenceLock = Mutex()

internal fun readConversations(
    context: Context,
    characterId: String,
): List<ChatConversation> {
    val files = File(File(context.filesDir, CHAT_DIRECTORY), characterId)
        .listFiles { file -> file.isFile && (file.extension == CHAT_FILE_EXTENSION) }.orEmpty()
    return files.asSequence().map { file ->
        conversationJson.decodeFromString<ChatConversation>(AtomicFile(file).openRead().bufferedReader(Charsets.UTF_8).use { it.readText() })
    }.sortedByDescending(ChatConversation::updatedAt).toList()
}

private fun readConversation(file: File): ChatConversation? = runCatching {
    conversationJson.decodeFromString<ChatConversation>(AtomicFile(file).openRead().bufferedReader(Charsets.UTF_8).use { it.readText() })
}.getOrNull()

private val conversationJson = Json { ignoreUnknownKeys = true; encodeDefaults = true; explicitNulls = false }

internal fun writeConversation(
    context: Context,
    characterId: String,
    conversation: ChatConversation,
    previousConversation: ChatConversation? = null,
) {
    val bytes = conversationJson.encodeToString(conversation).toByteArray(Charsets.UTF_8)
    val destination = conversationFile(context, characterId, conversation.id)
    val previous = (previousConversation ?: readConversation(destination))?.turns.orEmpty().flatMap { (_, _, _, imagePath, userImagePath) -> listOfNotNull(userImagePath, imagePath) }
    val retained = conversation.turns.asSequence().flatMap { (_, _, _, imagePath, userImagePath) -> listOfNotNull(userImagePath, imagePath) }.toSet() +
        listOfNotNull(readAttachmentDraft(context, characterId, conversation.id))
    previous.filterNot { it in retained }.forEach { deleteChatAttachment(context, it) }
    writeAtomicFile(destination, bytes)
}

internal fun removeConversation(context: Context, characterId: String, conversationId: String) {
    val file = conversationFile(context, characterId, conversationId)
    val paths = readConversation(file)?.turns.orEmpty().flatMap { (_, _, _, imagePath, userImagePath) -> listOfNotNull(userImagePath, imagePath) }
    paths.forEach { deleteChatAttachment(context, it) }
    saveAttachmentDraft(context, characterId, conversationId, null)
    AtomicFile(file).delete()
}

internal fun conversationFile(context: Context, characterId: String, conversationId: String): File =
    File(File(File(context.filesDir, CHAT_DIRECTORY), characterId), "$conversationId.$CHAT_FILE_EXTENSION")

internal fun readAttachmentDraft(context: Context, characterId: String, conversationId: String): String? =
    context.getSharedPreferences(CHAT_DRAFT_PREFERENCES, Context.MODE_PRIVATE)
        .getString("$characterId:$conversationId:image", null)

internal fun saveAttachmentDraft(context: Context, characterId: String, conversationId: String, path: String?) {
    val previous = readAttachmentDraft(context, characterId, conversationId)
    if ((previous != null) && (previous != path)) {
        val saved = readConversation(conversationFile(context, characterId, conversationId))
        if (saved?.turns.orEmpty().none { (_, _, _, _, userImagePath) -> userImagePath == previous }) deleteChatAttachment(context, previous)
    }
    context.getSharedPreferences(CHAT_DRAFT_PREFERENCES, Context.MODE_PRIVATE).edit(commit = true) {
        val key = "$characterId:$conversationId:image"
        putString(key, path)
    }
}

internal fun readChatDraft(context: Context, characterId: String, conversationId: String): String =
    context.getSharedPreferences(CHAT_DRAFT_PREFERENCES, Context.MODE_PRIVATE)
        .getString("$characterId:$conversationId", "")
        .orEmpty()

internal fun saveChatDraft(context: Context, characterId: String, conversationId: String, draft: String) {
    if (conversationId.isEmpty()) return
    context.getSharedPreferences(CHAT_DRAFT_PREFERENCES, Context.MODE_PRIVATE).edit {
        val key = "$characterId:$conversationId"
        if (draft.isEmpty()) remove(key) else putString(key, draft)
    }
}

internal fun removeChatDraft(context: Context, characterId: String, conversationId: String) {
    val preferences = context.getSharedPreferences(CHAT_DRAFT_PREFERENCES, Context.MODE_PRIVATE)
    val key = "$characterId:$conversationId"
    preferences.edit {
        preferences.all.keys
            .filter { (it == key) || it.startsWith("$key:edit:") }
            .forEach(::remove)
    }
}

internal fun readMessageEditDraft(
    context: Context,
    characterId: String,
    conversationId: String,
    turnIndex: Int,
    user: Boolean,
): String = context.getSharedPreferences(CHAT_DRAFT_PREFERENCES, Context.MODE_PRIVATE)
    .getString("$characterId:$conversationId:edit:${if (user) "user" else "reply"}:$turnIndex", "")
    .orEmpty()

internal fun saveMessageEditDraft(
    context: Context,
    characterId: String,
    conversationId: String,
    turnIndex: Int,
    user: Boolean,
    draft: String,
) {
    context.getSharedPreferences(CHAT_DRAFT_PREFERENCES, Context.MODE_PRIVATE).edit {
        val key = "$characterId:$conversationId:edit:${if (user) "user" else "reply"}:$turnIndex"
        if (draft.isEmpty()) remove(key) else putString(key, draft)
    }
}

internal fun readRenameDraft(context: Context, conversationId: String): String =
    context.getSharedPreferences(CHAT_RENAME_PREFERENCES, Context.MODE_PRIVATE)
        .getString(conversationId, "")
        .orEmpty()

internal fun saveRenameDraft(context: Context, conversationId: String, draft: String) {
    context.getSharedPreferences(CHAT_RENAME_PREFERENCES, Context.MODE_PRIVATE).edit {
        if (draft.isEmpty()) remove(conversationId) else putString(conversationId, draft)
    }
}

private const val CHAT_DRAFT_PREFERENCES = "chat_draft"
private const val CHAT_RENAME_PREFERENCES = "chat_rename_draft"
internal const val CHAT_SEARCH_PREFERENCES = "chat_search_draft"
internal const val KEY_CHAT_SEARCH = "search"
private const val CHAT_DIRECTORY = "chats"
private const val CHAT_FILE_EXTENSION = "json"

internal fun saveChatAttachment(context: Context, uri: Uri): String {
    val root = File(context.filesDir, "chat_attachments").apply { mkdirs() }
    val file = File(root, "${UUID.randomUUID()}.image")
    return try {
        checkNotNull(context.contentResolver.openInputStream(uri)).use { input ->
            file.outputStream().use { output -> input.copyTo(output) }
        }
        decodeImage(context, Uri.fromFile(file), 64).recycle()
        file.relativeTo(context.filesDir).path
    } catch (failure: Throwable) {
        file.delete()
        throw failure
    }
}

internal fun saveGeneratedChatImage(context: Context, image: File): String {
    val root = File(context.filesDir, "chat_attachments").apply { mkdirs() }
    val destination = File(root, "${UUID.randomUUID()}.image")
    val metadata = File(root, "${destination.nameWithoutExtension}.json")
    return try {
        image.copyTo(destination)
        File(image.parentFile, "${image.nameWithoutExtension}.json").copyTo(metadata)
        destination.relativeTo(context.filesDir).path
    } catch (failure: Throwable) {
        destination.delete()
        metadata.delete()
        throw failure
    }
}

internal fun deleteChatAttachment(context: Context, path: String) {
    val root = File(context.filesDir, "chat_attachments").canonicalFile
    val image = File(context.filesDir, path).canonicalFile
    image.delete()
    val sidecar = File(root, "${image.nameWithoutExtension}.json")
    sidecar.delete()
}
