package com.mrj.fancyai.ui.groups

import android.content.Context
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.edit
import com.mrj.fancyai.R
import com.mrj.fancyai.service.llm.AssistantProtocol
import com.mrj.fancyai.service.llm.ImagePrompt
import com.mrj.fancyai.service.llm.LlmEngineClient
import com.mrj.fancyai.service.llm.LlmExchange
import com.mrj.fancyai.service.llm.LlmInput
import com.mrj.fancyai.service.llm.LlmRequest
import com.mrj.fancyai.service.llm.MacroBus
import com.mrj.fancyai.service.llm.SocialPostJson
import com.mrj.fancyai.service.llm.complete
import com.mrj.fancyai.service.llm.generatePromptImage
import com.mrj.fancyai.service.memory.CharacterMemory
import com.mrj.fancyai.ui.characters.CharacterCard
import com.mrj.fancyai.ui.characters.availableCharacters
import com.mrj.fancyai.ui.kit.capitalizeFirstVisibleLetter
import com.mrj.fancyai.ui.lorebook.lorebookContext
import com.mrj.fancyai.ui.profile.userProfile
import com.mrj.fancyai.ui.settings.LlmSettings
import com.mrj.fancyai.ui.settings.LlmSettingsStore
import com.mrj.fancyai.ui.settings.runtime
import com.mrj.fancyai.ui.settings.sessionConfig
import com.mrj.fancyai.util.deleteReferencedImages
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.ArrayDeque
import java.util.Locale
import java.util.UUID
import java.util.concurrent.atomic.AtomicReference

internal class GroupController(
    private val app: Context,
    private val scope: kotlinx.coroutines.CoroutineScope,
    private val snackbar: SnackbarHostState,
) {
    private val preferences = app.getSharedPreferences("group_settings", Context.MODE_PRIVATE)
    var instruction by mutableStateOf(preferences.getString("instruction", app.getString(R.string.groups_default_prompt)).orEmpty())
        private set
    var thinking by mutableStateOf(preferences.getBoolean("thinking", false))
        private set

    fun updateInstruction(value: String) {
        instruction = value
        preferences.edit { putString("instruction", value) }
    }

    fun updateThinking(value: Boolean) {
        thinking = value
        preferences.edit { putBoolean("thinking", value) }
    }

    val runtime = AtomicReference<LlmEngineClient?>()
    var screen by mutableStateOf<GroupScreen>(GroupScreen.List)
    var groups by mutableStateOf<List<GroupInfo>>(emptyList())
    var previews by mutableStateOf<Map<String, GroupMessage>>(emptyMap())
    var characters by mutableStateOf<List<CharacterCard>>(emptyList())
    var profile by mutableStateOf(userProfile(app))
    var loading by mutableStateOf(true)
    var createDraft by mutableStateOf(readGroupCreateDraft(app))
    var messages by mutableStateOf<List<GroupMessage>>(emptyList())
    var roomLoading by mutableStateOf(false)
    var composer by mutableStateOf("")
    var imageProgress by mutableStateOf<Int?>(null)
    var generating by mutableStateOf(false)
    var generationStatus by mutableStateOf<String?>(null)
    var streamingMessage by mutableStateOf<GroupMessage?>(null)
    var streamingGroupId by mutableStateOf<String?>(null)
    var generationJob: Job? = null
    var deleteRequested by mutableStateOf<GroupInfo?>(null)

    fun load() {
        scope.launch {
            try {
                val savedGroups = withContext(Dispatchers.IO) { readGroups(app) }
                groups = savedGroups
                previews = withContext(Dispatchers.IO) {
                    savedGroups.mapNotNull { group ->
                        readGroupMessages(app, group.id).firstOrNull()?.let { group.id to it }
                    }.toMap()
                }
                characters = withContext(Dispatchers.IO) { availableCharacters(app) }
                profile = withContext(Dispatchers.IO) { userProfile(app) }
            } finally {
                loading = false
            }
        }
    }

    fun updateCreateDraft(value: GroupCreateDraft) {
        createDraft = value
        saveGroupCreateDraft(app, value)
    }

    fun stopGeneration() {
        if (!generating) return
        generationJob?.cancel()
        generationJob = null
        runtime.get()?.cancel()
        generating = false
        generationStatus = null
        imageProgress = null
    }

    private suspend fun saveReply(group: GroupInfo, message: GroupMessage): GroupMessage = withContext(NonCancellable) {
        val saved = withContext(Dispatchers.IO) {
            writeGroupMessage(app, group.id, message)
        }
        if (streamingGroupId == group.id) {
            streamingGroupId = null
            streamingMessage = null
        }
        if ((screen as? GroupScreen.Room)?.group?.id == group.id) {
            messages = listOf(saved) + messages.filterNot { it.id == saved.id }
        }
        previews += group.id to saved
        saved
    }

    private suspend fun runReplies(
        group: GroupInfo,
        members: List<CharacterCard>,
        latestUserText: String,
        settings: LlmSettings,
    ) {
        val mentions = mentionedGroupCharacters(latestUserText, members)
        val initial = mentions.ifEmpty {
            members.shuffled().take(MAXIMUM_RESPONDERS)
        }
        val queue = ArrayDeque(initial)
        val handled = linkedSetOf<String>()
        var active = runtime.get()
        if (active == null) {
            active = LlmEngineClient(app)
            runtime.set(active)
        }
        while (queue.isNotEmpty() && handled.size < MAXIMUM_RESPONDERS) {
            val speaker = queue.removeFirst()
            if (!handled.add(speaker.id)) continue
            val saved = generateSpeakerReply(
                group = group,
                members = members,
                latestUserText = latestUserText,
                settings = settings,
                speaker = speaker,
                client = active,
            )

            queue.addAll(mentionedGroupCharacters(saved.text, members)
                .filterNot { it.id in handled }
                .take((MAXIMUM_RESPONDERS - handled.size - queue.size).coerceAtLeast(0)))
        }
    }

    private suspend fun generateSpeakerReply(
        group: GroupInfo,
        members: List<CharacterCard>,
        latestUserText: String,
        settings: LlmSettings,
        speaker: CharacterCard,
        client: LlmEngineClient,
    ): GroupMessage {
        val macros = MacroBus(app, speaker, profile)
        imageProgress = null
        generationStatus = app.getString(R.string.social_character_replying, speaker.name)
        val transcript = withContext(Dispatchers.IO) {
            readGroupMessages(app, group.id)
        }
        val memory = CharacterMemory(app, speaker.id)
        val memories = memory.recall(latestUserText, newConversation = transcript.none { it.authorId == speaker.id })
        val (request, streamed, imageRequested) = prepareGroupReply(
            group = group,
            members = members,
            latestUserText = latestUserText,
            settings = settings,
            speaker = speaker,
            macros = macros,
            transcript = transcript,
            memories = memories,
        )
        val text = StringBuilder()
        val thoughts = StringBuilder()
        val reply = client.generate(
            request,
        ).onEach { chunk ->
            text.append(chunk.text)
            chunk.channels.values.forEach(thoughts::append)
            if (streamingGroupId == group.id && (screen as? GroupScreen.Room)?.group?.id == group.id) {
                streamingMessage = streamed.copy(
                    text = groupReplyText(ImagePrompt.split(macros.text(text.toString()), imageOnly = imageRequested).first),
                    thoughtProcess = thoughts.toString(),
                )
            }
        }.complete()
        val (replyBody, scenePrompt) = ImagePrompt.split(if (imageRequested) reply.text else macros.text(reply.text), imageOnly = imageRequested)
        var saved = streamed.copy(
            id = UUID.randomUUID().toString(),
            text = groupReplyText(replyBody),
            thoughtProcess = reply.channels.values.joinToString("\n\n"),
            imagePrompt = scenePrompt.orEmpty(),
        )
        if (saved.text.isNotBlank() || saved.imagePrompt.isNotBlank()) {
            saved = saveReply(group, saved)
            memory.collect(
                sessionId = "groups:${group.id}",
                turnIndex = transcript.count { it.authorId == speaker.id },
                timestamp = saved.createdAt,
                messages = (transcript.filter { it.text.isNotBlank() }.take(2).asReversed() + saved)
                    .map { "${it.authorName}: ${it.text}" },
            )
        }
        if (scenePrompt != null) {
            generationStatus = app.getString(R.string.chat_image_generating)
            val image = generatePromptImage(app, scenePrompt, characterId = speaker.id) { imageProgress = it }
            saved = saveReply(
                group,
                saved.copy(
                    imagePath = image.relativeTo(app.filesDir).path,
                    image = image,
                    imagePrompt = scenePrompt,
                ),
            )
        }
        if (streamingGroupId == group.id) {
            streamingGroupId = null
            streamingMessage = null
        }
        return saved
    }

    private suspend fun prepareGroupReply(
        group: GroupInfo,
        members: List<CharacterCard>,
        latestUserText: String,
        settings: LlmSettings,
        speaker: CharacterCard,
        macros: MacroBus,
        transcript: List<GroupMessage>,
        memories: List<String>,
    ): Triple<LlmRequest, GroupMessage, Boolean> {
        val history = mutableListOf<LlmExchange>()
        var pendingUser = StringBuilder()
        transcript.asReversed().forEach { message ->
            val content = macros.text(message.text).trim()
            if (message.authorId == speaker.id) {
                if (content.isNotBlank() && pendingUser.isNotBlank()) {
                    history += LlmExchange(LlmInput(pendingUser.toString()), content)
                    pendingUser = StringBuilder()
                } else if (content.isNotBlank() && history.isNotEmpty()) {
                    val previous = history.removeAt(history.lastIndex)
                    history += previous.copy(assistant = "${previous.assistant}\n$content")
                }
            } else {
                if (pendingUser.isNotEmpty()) pendingUser.append('\n')
                pendingUser.append(macros.text(message.authorName)).append(": ").append(content)
            }
        }
        val roomContext = "Room: ${group.name}\nMembers and accepted handles: " +
            members.joinToString(", ") { "${it.name} (${groupHandle(it)})" } +
            "\nScenario: ${group.scenario}"
        val contextBlocks = withContext(Dispatchers.IO) {
            lorebookContext(app, speaker, "$latestUserText\n$roomContext")
        }
        val streamed = GroupMessage(
            id = "$STREAMING_ID_PREFIX${speaker.id}",
            authorId = speaker.id,
            authorName = speaker.name,
            authorHandle = groupHandle(speaker),
            text = "",
            createdAt = System.currentTimeMillis(),
            imagePath = "",
            image = File(""),
        )
        if ((screen as? GroupScreen.Room)?.group?.id == group.id) {
            streamingGroupId = group.id
            streamingMessage = streamed
        }
        val turn = AssistantProtocol.compile(
            settings.runtime,
            macros = macros,
            instructions = listOf(instruction),
            message = pendingUser.toString(),
            triggerMessage = latestUserText,
            context = listOf(roomContext),
            optionalContext = listOf(macros.text(contextBlocks)) + memories,
        )
        return Triple(LlmRequest(
            config = settings.sessionConfig(turn.systemInstruction, history = if (turn.imageRequested) emptyList() else history),
            input = turn.input,
            thinking = thinking,
        ), streamed, turn.imageRequested)
    }

    fun sendMessage() {
        val group = (screen as? GroupScreen.Room)?.group ?: return
        if (generating || roomLoading) return
        val submittedDraft = composer
        val input = submittedDraft.trim()
        val text = input.take(MESSAGE_LIMIT).capitalizeFirstVisibleLetter()
        if (text.isBlank()) return
        val members = characters.filter { it.id in group.memberIds }
        if (members.isEmpty()) {
            scope.launch { snackbar.showSnackbar(app.getString(R.string.groups_no_members_available)) }
            return
        }
        generating = true
        generationJob = scope.launch {
            try {
                val settings = withContext(Dispatchers.IO) { LlmSettingsStore.snapshot(app) }
                if (settings == null) {
                    generating = false
                    generationStatus = null
                    imageProgress = null
                    snackbar.showSnackbar(app.getString(R.string.groups_no_engine))
                    return@launch
                }
                withContext(NonCancellable) {
                    val userMessage = withContext(Dispatchers.IO) {
                        writeGroupMessage(
                            app,
                            group.id,
                            GroupMessage(
                                id = UUID.randomUUID().toString(),
                                authorId = USER_ID,
                                authorName = profile.name.ifBlank { app.getString(R.string.label_you) },
                                authorHandle = profile.handle,
                                text = text,
                                createdAt = System.currentTimeMillis(),
                                imagePath = "",
                                image = File(""),
                            ),
                        )
                    }
                    if ((screen as? GroupScreen.Room)?.group?.id == group.id) {
                        messages = listOf(userMessage) + messages
                        if (composer == submittedDraft) {
                            composer = ""
                            app.getSharedPreferences(DRAFT_PREFERENCES, Context.MODE_PRIVATE).edit { remove(group.id) }
                        }
                    }
                    previews += group.id to userMessage
                }
                runReplies(group, members, text, settings)
            } catch (failure: Throwable) {
                if (streamingGroupId == group.id) {
                    streamingMessage?.takeIf { it.text.isNotBlank() }?.let { partial ->
                        saveReply(group, partial.copy(id = UUID.randomUUID().toString()))
                    }
                }
                throw failure
            } finally {
                if (streamingGroupId == group.id) {
                    streamingGroupId = null
                    streamingMessage = null
                }
                generationJob = null
                generating = false
                generationStatus = null
                imageProgress = null
            }
        }
    }

    fun openRoom(group: GroupInfo) {
        stopGeneration()
        screen = GroupScreen.Room(group)
        composer = app.getSharedPreferences(DRAFT_PREFERENCES, Context.MODE_PRIVATE).getString(group.id, "").orEmpty()
        messages = emptyList()
        roomLoading = true
        scope.launch {
            try {
                val loaded = withContext(Dispatchers.IO) {
                    readGroupMessages(app, group.id)
                }
                if ((screen as? GroupScreen.Room)?.group?.id == group.id) messages = loaded
            } finally {
                if ((screen as? GroupScreen.Room)?.group?.id == group.id) {
                    roomLoading = false
                }
            }
        }
    }

    fun leaveRoom() {
        stopGeneration()
        screen = GroupScreen.List
        messages = emptyList()
        roomLoading = false
        composer = ""
    }

    fun createGroup() {
        val availableIds = characters.mapTo(mutableSetOf(), CharacterCard::id)
        val normalized = createDraft.copy(memberIds = createDraft.memberIds.intersect(availableIds)).normalized()
        if (normalized.name.isBlank() || normalized.memberIds.isEmpty()) return
        scope.launch {
            val group = withContext(Dispatchers.IO) { writeGroup(app, normalized) }
            groups = (groups + group).sortedByDescending(GroupInfo::createdAt)
            createDraft = GroupCreateDraft()
            app.getSharedPreferences(CREATE_PREFERENCES, Context.MODE_PRIVATE).edit { clear() }
            openRoom(group)
        }
    }

    fun deleteGroup(group: GroupInfo) {
        scope.launch {
            withContext(Dispatchers.IO) {
                val folder = File(File(app.filesDir, "groups"), group.id)
                deleteReferencedImages(app, folder)
                folder.deleteRecursively()
                group.memberIds.forEach { memberId ->
                    CharacterMemory(app, memberId).deleteSession("groups:${group.id}")
                }
                app.getSharedPreferences(DRAFT_PREFERENCES, Context.MODE_PRIVATE).edit { remove(group.id) }
            }
            groups = groups.filterNot { it.id == group.id }
            previews -= group.id
            deleteRequested = null
            if ((screen as? GroupScreen.Room)?.group?.id == group.id) leaveRoom()
            snackbar.showSnackbar(app.getString(R.string.groups_deleted))
        }
    }

}

internal fun groupReplyText(raw: String): String = SocialPostJson.text(raw, "message")
    .capitalizeFirstVisibleLetter()

internal fun mentionedGroupCharacters(text: String, members: List<CharacterCard>): List<CharacterCard> {
    val mentions = Regex("(?<![A-Za-z0-9_])@([A-Za-z0-9_]+)", RegexOption.IGNORE_CASE)
        .findAll(text).map { it.groupValues[1].lowercase(Locale.ROOT) }.distinct().toList()
    if (mentions.isEmpty()) return emptyList()
    val byHandle = members.associateBy { groupHandle(it).removePrefix("@").lowercase(Locale.ROOT) }
    return mentions.mapNotNull(byHandle::get)
}

internal fun groupHandle(character: CharacterCard): String {
    val explicit = character.handle.trim().removePrefix("@").lowercase(Locale.ROOT)
        .filter { it in 'a'..'z' || it in '0'..'9' || it == '_' }
    val fallback = character.name.lowercase(Locale.ROOT)
        .filter { it in 'a'..'z' || it in '0'..'9' }
    val stable = character.id.lowercase(Locale.ROOT)
        .filter { it in 'a'..'z' || it in '0'..'9' }.take(HANDLE_ID_LENGTH)
    return "@${explicit.ifBlank { fallback.ifBlank { "member$stable" } }}"
}

internal fun insertGroupMention(draft: String, handle: String): String {
    val prefix = draft.takeIf(String::isBlank)?.let { "" } ?: (draft.trimEnd() + " ")
    return "$prefix$handle ".take(MESSAGE_LIMIT)
}
