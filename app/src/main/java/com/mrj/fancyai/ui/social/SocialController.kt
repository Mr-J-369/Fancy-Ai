package com.mrj.fancyai.ui.social

import android.content.Context
import android.util.Log
import androidx.core.content.edit
import com.mrj.fancyai.R
import com.mrj.fancyai.service.llm.AssistantProtocol
import com.mrj.fancyai.service.llm.ImagePrompt
import com.mrj.fancyai.service.llm.LlmEngineClient
import com.mrj.fancyai.service.llm.LlmExchange
import com.mrj.fancyai.service.llm.LlmInput
import com.mrj.fancyai.service.llm.LlmRequest
import com.mrj.fancyai.service.llm.MacroBus
import com.mrj.fancyai.service.llm.complete
import com.mrj.fancyai.service.llm.generatePromptImage
import com.mrj.fancyai.service.memory.CharacterMemory
import com.mrj.fancyai.ui.characters.CharacterCard
import com.mrj.fancyai.ui.characters.CharacterSocialApp
import com.mrj.fancyai.ui.characters.availableCharacters
import com.mrj.fancyai.ui.rebbit.createAutomaticRebbitPost
import com.mrj.fancyai.ui.settings.LlmSettings
import com.mrj.fancyai.ui.settings.runtime
import com.mrj.fancyai.ui.settings.sessionConfig
import com.mrj.fancyai.ui.ustagram.createAutomaticUstagramPost
import com.mrj.fancyai.ui.y.createAutomaticYPost
import com.mrj.fancyai.util.AppLog
import java.io.File
import java.util.Locale
import java.util.UUID
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** One session-owned queue. It never starts Android services or schedules work after exit. */
internal object AutomaticSocialPosts {
    data class State(
        val rebbitDraft: com.mrj.fancyai.ui.rebbit.RebbitDraft? = null,
        val ustagramDraft: com.mrj.fancyai.ui.ustagram.UstagramDraft? = null,
        val dareDraft: com.mrj.fancyai.ui.dare.DareDraft? = null,
        val imageProgress: Int = 0,
        val running: Boolean = false,
        val character: String = "",
        val app: CharacterSocialApp? = null,
        val message: Int = R.string.automatic_posts_waiting,
    )

    private val mutableState = MutableStateFlow(State())
    val state = mutableState.asStateFlow()
    private val mutableRevision = MutableStateFlow(0L)
    val revision = mutableRevision.asStateFlow()
    private val blockers = mutableSetOf<Any>()
    private val manualJobs = mutableSetOf<Job>()
    private var sessionJob: Job? = null
    private var postJob: Job? = null
    private var settingsVersion = 0L
    private var lastAttempt: String? = null

    // All orchestration runs on Main; generation and file access suspend onto their own dispatchers.
    fun start(context: Context, scope: CoroutineScope) {
        if (sessionJob?.isActive == true) return
        val app = context.applicationContext
        val preferences = app.getSharedPreferences("automatic_social_posts", Context.MODE_PRIVATE)
        sessionJob = scope.launch {
            postJob?.join()
            postJob = null
            while (coroutineContext.job.isActive) {
                manualJobs.removeAll { !it.isActive }
                if ((preferences.getBoolean("enabled", false)) &&
                    (blockers.isEmpty()) && (manualJobs.isEmpty()) &&
                    (System.currentTimeMillis() >= preferences.getLong("next_at", 0))
                ) {
                    val version = settingsVersion
                    val pairs = withContext(Dispatchers.IO) {
                        availableCharacters(app).flatMap { character ->
                            CharacterSocialApp.entries.asSequence().filter(character::postsTo).map { character to it }.toList()
                        }.sortedBy { (character, target) -> "${character.id}/${target.name}" }
                    }
                    if (pairs.isEmpty()) {
                        mutableState.value = State(message = R.string.automatic_posts_no_participants)
                    } else if (version == settingsVersion && blockers.isEmpty() && manualJobs.none { it.isActive } &&
                        preferences.getBoolean("enabled", false) && System.currentTimeMillis() >= preferences.getLong("next_at", 0)
                    ) {
                        val last = lastAttempt ?: preferences.getString("last_pair", "")
                        val index = pairs.indexOfFirst { (character, target) -> "${character.id}/${target.name}" == last }
                        val (character, target) = pairs[(index + 1) % pairs.size]
                        lastAttempt = "${character.id}/${target.name}"
                        postJob = launch { executePost(app, character, target) }
                        postJob?.join()
                        postJob = null
                    }
                }
                delay(1.seconds)
            }
        }
    }

    private suspend fun executePost(app: Context, character: CharacterCard, target: CharacterSocialApp) {
        val client = LlmEngineClient(app)
        var saved = false
        mutableState.value = State(running = true, character = character.name, app = target, message = R.string.automatic_posts_writing)
        val onSaved: () -> Unit = {
            saved = true
            AutomaticPostSettings.posted(app, "${character.id}/${target.name}")
            mutableRevision.value++
        }

        try {
            when (target) {
                CharacterSocialApp.REBBIT -> createAutomaticRebbitPost(
                    app, character, client, onSaved,
                    onProgress = { mutableState.value = mutableState.value.copy(imageProgress = it) },
                ) { draft ->
                    mutableState.value = mutableState.value.copy(rebbitDraft = draft)
                }
                CharacterSocialApp.USTAGRAM -> createAutomaticUstagramPost(
                    app, character, client, onSaved,
                    onProgress = { mutableState.value = mutableState.value.copy(imageProgress = it) },
                ) { draft ->
                    mutableState.value = mutableState.value.copy(ustagramDraft = draft)
                }
                CharacterSocialApp.Y -> createAutomaticYPost(
                    app, character, client, onSaved,
                ) { mutableState.value = mutableState.value.copy(imageProgress = it) }
                CharacterSocialApp.DARE -> com.mrj.fancyai.ui.dare.createAutomaticDarePost(
                    app, character, client, onSaved,
                    onProgress = { mutableState.value = mutableState.value.copy(imageProgress = it) },
                ) { draft ->
                    mutableState.value = mutableState.value.copy(dareDraft = draft)
                }
            }
            mutableRevision.value++
            mutableState.value = mutableState.value.copy(
                message = R.string.automatic_posts_waiting,
            )
        } catch (cancelled: CancellationException) {
            mutableState.value = mutableState.value.copy(message = if (saved) R.string.automatic_posts_saved else R.string.automatic_posts_waiting)
            throw cancelled
        } catch (failure: Throwable) {
            AppLog.write(Log.ERROR, "AutomaticPosts", "Post generation failed", failure)
            mutableState.value = mutableState.value.copy(message = R.string.automatic_posts_waiting)
        } finally {
            client.closeAndAwait()
            mutableState.value = mutableState.value.copy(running = false, rebbitDraft = null, ustagramDraft = null)
        }
    }

    fun cancelPost() {
        postJob?.cancel()
        postJob = null
        mutableState.value = mutableState.value.copy(
            running = false,
            rebbitDraft = null,
            ustagramDraft = null,
            message = R.string.automatic_posts_waiting,
        )
    }

    fun stop() {
        sessionJob?.cancel()
        sessionJob = null
        postJob?.cancel()
        postJob = null
        mutableState.value = mutableState.value.copy(
            running = false,
            rebbitDraft = null,
            ustagramDraft = null,
            message = R.string.automatic_posts_waiting,
        )
    }

    fun block(owner: Any, blocked: Boolean) {
        if (blocked) {
            blockers += owner
            postJob?.cancel()
        } else blockers -= owner
    }

    fun settingsChanged() {
        settingsVersion++
        if (!mutableState.value.running) mutableState.value = State()
        postJob?.cancel()
    }

    /** Manual social actions own the queue for their complete coroutine, including image callbacks. */
    suspend fun awaitManualTurn() {
        val job = currentCoroutineContext().job
        manualJobs += job
        postJob?.cancelAndJoin()
    }

}

internal object AutomaticPostSettings {
    fun setEnabled(context: Context, enabled: Boolean) {
        context.getSharedPreferences("automatic_social_posts", Context.MODE_PRIVATE).edit {
            putBoolean("enabled", enabled)
            if (enabled) putLong("next_at", 0)
        }
        AutomaticSocialPosts.settingsChanged()
    }

    fun setMinutes(context: Context, draft: String) {
        val preferences = context.getSharedPreferences("automatic_social_posts", Context.MODE_PRIVATE)
        preferences.edit { putString("minutes_draft", draft) }
        val minutes = draft.toIntOrNull() ?: return
        if (minutes == preferences.getInt("minutes", 30)) return
        preferences.edit {
            putInt("minutes", minutes)
            if (preferences.getBoolean("enabled", false)) putLong("next_at", System.currentTimeMillis() + (minutes * 60_000L))
        }
    }

    fun posted(context: Context, pair: String) {
        val preferences = context.getSharedPreferences("automatic_social_posts", Context.MODE_PRIVATE)
        preferences.edit {
            putString("last_pair", pair)
            putLong("next_at", System.currentTimeMillis() + (preferences.getInt("minutes", 30) * 60_000L))
        }
    }
}

internal fun CharacterCard.postsTo(app: CharacterSocialApp): Boolean = when (app) {
    CharacterSocialApp.REBBIT -> rebbitEnabled
    CharacterSocialApp.USTAGRAM -> ustagramEnabled
    CharacterSocialApp.Y -> yEnabled
    CharacterSocialApp.DARE -> dareEnabled
}

internal suspend fun runSocialCharacterReplies(
    directory: String,
    targets: List<CharacterCard>,
    comments: List<SocialComment>,
    userComment: SocialComment,
    latestUserText: String,
    settings: LlmSettings,
    client: LlmEngineClient,
    macros: (CharacterCard) -> MacroBus,
    post: String,
    additionalContext: List<String> = emptyList(),
    parentId: String = "",
    thinking: Boolean = false,
    onCharacter: (CharacterCard) -> Unit,
    onImage: () -> Unit,
    onImageProgress: (Int) -> Unit,
    onReplySaved: (SocialComment, Boolean) -> Unit,
) {
    for (character in targets) {
        onCharacter(character)
        val bus = macros(character)
        val characterMemory = CharacterMemory(bus.context, character.id)
        val imageInstruction = ImagePrompt.requestedInstruction(bus, latestUserText)
        val history = mutableListOf<LlmExchange>()
        val pending = mutableListOf<String>()
        val earlier = comments.filterNot { (id) -> id == userComment.id }
        withContext(Dispatchers.IO) {
            for ((_, _, authorId, authorName, authorHandle, text) in if (imageInstruction == null) earlier else emptyList()) {
                val content = bus.text(text)
                if (authorId == character.id) {
                    if (pending.isNotEmpty()) {
                        history += LlmExchange(LlmInput(pending.joinToString("\n\n")), content)
                        pending.clear()
                    } else if (history.isNotEmpty()) {
                        val previous = history.removeAt(history.lastIndex)
                        history += previous.copy(assistant = "${previous.assistant}\n$content")
                    } else history += LlmExchange(LlmInput(bus.text(post)), content)
                } else pending += "${bus.text(authorName)} (${bus.text(authorHandle)}):\n$content"
            }
        }
        val memories = if (imageInstruction != null) emptyList() else characterMemory.recall(latestUserText, newConversation = earlier.none { it.authorId == character.id })
        val query = (earlier.takeLast(2).map { it.text } + latestUserText).joinToString("\n")
        val lore = if (imageInstruction != null) "" else com.mrj.fancyai.ui.lorebook.lorebookContext(bus.context, character, query)
        val defaultCommentPrompt = bus.context.getString(R.string.social_comments_default_prompt)
        val commentInstruction = bus.context.getSharedPreferences("automatic_social_posts", Context.MODE_PRIVATE)
            .getString("social_comments_prompt", defaultCommentPrompt)
            .orEmpty()
            .ifBlank { defaultCommentPrompt }
        val instructions = listOf(commentInstruction)
        val input = if (imageInstruction != null) LlmInput(text = latestUserText) else withContext(Dispatchers.IO) {
            LlmInput(
                text = bus.text(latestUserText),
                context = (listOf("Character: {{char}}\nDescription: {{char.description}}\nPersonality: {{char.personality}}\nAppearance: {{char.appearance}}", "Original post (background):\n$post", "User: {{user.name}} ({{user.handle}})\n{{user.description}}", lore) +
                    additionalContext + pending + memories).filter(String::isNotBlank).map(bus::text),
            )
        }
        val response = client.generate(
            LlmRequest(
                config = settings.sessionConfig(systemInstruction = AssistantProtocol.systemInstruction(settings.runtime, bus, instructions, imageInstruction), history = history),
                input = input,
                thinking = thinking,
            ),
        ).complete()
        val (rawReplyText, scenePrompt) = ImagePrompt.split(if (imageInstruction != null) response.text else bus.text(response.text), imageOnly = imageInstruction != null)
        val replyText = if (scenePrompt == null && imageInstruction == null) {
            rawReplyText.replace(Regex("""(?im)\n+[ \t]*(?:image\s*prompt|image\s*description)[ \t]*:[\s\S]*$"""), "").trim().ifBlank { rawReplyText }
        } else rawReplyText
        val thoughtProcess = response.channels.values.joinToString("\n\n")
        val reply = SocialComment(
            id = UUID.randomUUID().toString(), postId = userComment.postId, authorId = character.id,
            authorName = character.name, authorHandle = character.handle, text = replyText,
            thoughtProcess = thoughtProcess,
            createdAt = System.currentTimeMillis(), imagePath = "", image = File(""), parentId = parentId,
            imagePrompt = scenePrompt.orEmpty(),
        )
        val hasText = reply.text.isNotBlank()
        val hasPrompt = reply.imagePrompt.isNotBlank()
        val saved = if (hasText || hasPrompt) writeSocialComment(bus.context.filesDir, directory, reply) else reply
        if (hasText || hasPrompt) {
            onReplySaved(saved, true)
            characterMemory.collect(
                "$directory:${saved.postId}:${saved.id}", 0, saved.createdAt,
                (earlier.takeLast(2) + listOf(userComment, saved)).map { "${it.authorName}: ${it.text}" },
            )
        }
        if (scenePrompt != null) {
            onImage()
            val illustrated = withContext(NonCancellable) {
                val image = generatePromptImage(bus.context, scenePrompt, characterId = reply.authorId, onProgress = onImageProgress)
                writeSocialComment(bus.context.filesDir, directory, saved.copy(image = image, imagePath = image.relativeTo(bus.context.filesDir).path, imagePrompt = scenePrompt))
            }
            onReplySaved(illustrated, !hasText)
        }
    }
}

/** Deletes the post record and every reply record collected under it. */
internal suspend fun deleteSocialPostMemories(
    app: Context,
    directory: String,
    postId: String,
    authorId: String,
) {
    if (authorId.isNotBlank() && authorId != "user") {
        CharacterMemory(app, authorId).deleteSession("$directory:$postId")
    }
    withContext(Dispatchers.IO) { readSocialComments(app.filesDir, directory, postId) }.forEach { comment ->
        if (comment.authorId.isNotBlank() && comment.authorId != "user") {
            CharacterMemory(app, comment.authorId).deleteSession("$directory:$postId:${comment.id}")
        }
    }
}

internal fun mentionedSocialCharacters(
    text: String,
    characters: List<CharacterCard>,
): List<CharacterCard> {
    val mentions = Regex("(?<![A-Za-z0-9_])@[A-Za-z0-9_]+")
        .findAll(text)
        .map { it.value.lowercase(Locale.ROOT) }
        .toSet()
    if (mentions.isEmpty()) return emptyList()
    return characters.filter { character ->
        val handle = character.handle.trim().lowercase(Locale.ROOT).let {
            if (it.startsWith('@')) it else "@$it"
        }
        val name = "@" + character.name.lowercase(Locale.ROOT).filter(Char::isLetterOrDigit)
        (handle in mentions) || (name in mentions)
    }
}

internal fun selectSocialReplyTargets(
    mentioned: List<CharacterCard>,
    eligible: List<CharacterCard>,
    preferredAuthorIds: List<String>,
    fallbackLimit: Int,
    replyLimit: Int,
): List<CharacterCard> = mentioned.ifEmpty {
    preferredAuthorIds.firstNotNullOfOrNull { id -> eligible.firstOrNull { it.id == id } }
        ?.let(::listOf)
        ?: eligible.shuffled().take(fallbackLimit)
}.distinctBy(CharacterCard::id).take(replyLimit)
