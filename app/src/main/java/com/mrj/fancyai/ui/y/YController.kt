package com.mrj.fancyai.ui.y
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
import com.mrj.fancyai.service.llm.LlmInput
import com.mrj.fancyai.service.llm.LlmRequest
import com.mrj.fancyai.service.llm.MacroBus
import com.mrj.fancyai.service.llm.MacroException
import com.mrj.fancyai.service.llm.SocialPostJson
import com.mrj.fancyai.service.llm.complete
import com.mrj.fancyai.service.llm.generatePromptImage
import com.mrj.fancyai.service.llm.llmErrorResource
import com.mrj.fancyai.service.memory.CharacterMemory
import com.mrj.fancyai.ui.characters.CharacterCard
import com.mrj.fancyai.ui.characters.availableCharacters
import com.mrj.fancyai.ui.kit.capitalizeFirstVisibleLetter
import com.mrj.fancyai.ui.profile.userProfile
import com.mrj.fancyai.ui.settings.LlmSettingsStore
import com.mrj.fancyai.ui.settings.runtime
import com.mrj.fancyai.ui.settings.sessionConfig
import com.mrj.fancyai.ui.social.AutomaticSocialPosts
import com.mrj.fancyai.ui.social.SocialComment
import com.mrj.fancyai.ui.social.deleteSocialPostMemories
import com.mrj.fancyai.ui.social.mentionedSocialCharacters
import com.mrj.fancyai.ui.social.persistUserSocialComment
import com.mrj.fancyai.ui.social.readSocialComments
import com.mrj.fancyai.ui.social.runSocialCharacterReplies
import com.mrj.fancyai.ui.social.selectSocialReplyTargets
import com.mrj.fancyai.util.deleteReferencedImages
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.atomic.AtomicReference

internal class YController(
    private val app: Context,
    private val scope: kotlinx.coroutines.CoroutineScope,
    private val snackbar: SnackbarHostState,
    private val feedState: androidx.compose.foundation.lazy.LazyListState,
) {
    val runtime = AtomicReference<LlmEngineClient?>()
    var characters by mutableStateOf<List<CharacterCard>>(emptyList())
    var posts by mutableStateOf<List<YPost>>(emptyList())
    var commentCounts by mutableStateOf<Map<String, Int>>(emptyMap())
    var loading by mutableStateOf(true)
    var generation by mutableStateOf<YGeneration>(YGeneration.Idle)
    var generationJob: Job? = null
    var generationRun = 0
    var replyJob: Job? = null
    var replying by mutableStateOf(false)
    var sending by mutableStateOf(false)
    var imageProgress by mutableStateOf<Int?>(null)
    var replyStatus by mutableStateOf<String?>(null)
    var openThread by mutableStateOf<YPost?>(null)
    var threadComments by mutableStateOf<List<SocialComment>>(emptyList())
    var commentDraft by mutableStateOf("")
    var prompt by mutableStateOf(app.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE).getString(KEY_PROMPT, app.getString(R.string.y_default_prompt)).orEmpty())
    var thinking by mutableStateOf(app.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE).getBoolean(KEY_THINKING, false))
        private set

    val profile = userProfile(app)

    suspend fun loadFeed(): Unit = withContext(Dispatchers.IO) {
        try {
            val loadedPosts = readYPosts(app)
            val loadedCounts = loadedPosts.associateBy(YPost::id) {
                readSocialComments(app.filesDir, DIRECTORY, it.id).size
            }
            characters = availableCharacters(app)
            posts = loadedPosts
            commentCounts = loadedCounts
            openThread?.id?.let { openId ->
                if (loadedPosts.none { it.id == openId }) {
                    openThread = null
                    threadComments = emptyList()
                }
            }
        } finally {
            loading = false
        }
    }

    fun updatePrompt(value: String) {
        prompt = value
        app.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE).edit { putString(KEY_PROMPT, value) }
    }

    fun updateThinking(value: Boolean) {
        thinking = value
        app.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE).edit { putBoolean(KEY_THINKING, value) }
    }

    fun updateCommentDraft(postId: String, value: String) {
        commentDraft = value
        app.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE).edit { if (value.isBlank()) remove(KEY_COMMENT_DRAFT_PREFIX + postId) else putString(KEY_COMMENT_DRAFT_PREFIX + postId, value) }
    }

    fun report(post: YPost) {
        if (sending || generation.busy || replying || post.reported) return
        sending = true
        scope.launch {
            try {
                AutomaticSocialPosts.awaitManualTurn()
                withContext(kotlinx.coroutines.NonCancellable) {
                    val reported = withContext(Dispatchers.IO) {
                        val folder = File(File(app.filesDir, DIRECTORY), post.id)
                        val current = readYPost(folder)
                        current.copy(reported = true).also { writeYPostProperties(folder, it) }
                    }
                    posts = posts.map { if (it.id == post.id) reported else it }
                    if (openThread?.id == post.id) openThread = reported
                }
                scope.launch { snackbar.showSnackbar(app.getString(R.string.social_post_reported)) }
            } finally {
                sending = false
            }
        }
    }

    fun clearFeed() {
        if (sending || generation.busy || replying) return
        sending = true
        val removed = posts
        scope.launch {
            try {
                AutomaticSocialPosts.awaitManualTurn()
                generationJob?.join()
                replyJob?.join()
                withContext(Dispatchers.IO) {
                    val folder = File(app.filesDir, DIRECTORY)
                    deleteReferencedImages(app, folder)
                    folder.deleteRecursively()
                    val preferences = app.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
                    preferences.edit { preferences.all.keys.filter { it.startsWith(KEY_COMMENT_DRAFT_PREFIX) }.forEach(::remove) }
                }
                removed.forEach { deleteSocialPostMemories(app, DIRECTORY, it.id, it.characterId) }
                posts = emptyList()
                commentCounts = emptyMap()
                openThread = null
                threadComments = emptyList()
            } finally {
                sending = false
            }
        }
    }

    fun deletePost(post: YPost) {
        if (sending || generation.busy || replying) return
        sending = true
        scope.launch {
            try {
                AutomaticSocialPosts.awaitManualTurn()
                generationJob?.join()
                replyJob?.join()
                withContext(Dispatchers.IO) {
                    val folder = File(File(app.filesDir, DIRECTORY), post.id)
                    deleteReferencedImages(app, folder)
                    folder.deleteRecursively()
                    app.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE).edit { remove(KEY_COMMENT_DRAFT_PREFIX + post.id) }
                }
                deleteSocialPostMemories(app, DIRECTORY, post.id, post.characterId)
                posts = posts.filterNot { it.id == post.id }
                commentCounts -= post.id
                if (openThread?.id == post.id) {
                    openThread = null
                    threadComments = emptyList()
                }
                scope.launch { snackbar.showSnackbar(app.getString(R.string.social_post_deleted)) }
            } finally {
                sending = false
            }
        }
    }

    fun close() {
        generationRun++
        generationJob?.cancel()
        replyJob?.cancel()
        runtime.get()?.cancel()
        runtime.getAndSet(null)?.close()
    }

    fun startPost(character: CharacterCard? = null) {
        if (generation.busy || replying || sending || loading) return
        val eligible = characters.filter(CharacterCard::yEnabled)
        val author = character?.takeIf(CharacterCard::yEnabled) ?: eligible.randomOrNull()
        if (author == null) {
            scope.launch { snackbar.showSnackbar(app.getString(R.string.y_no_posters)) }
            return
        }
        val postPrompt = prompt
        val run = generationRun + 1
        generationRun = run
        generation = YGeneration.Writing(author)
        generationJob = scope.launch {
            try {
                AutomaticSocialPosts.awaitManualTurn()
                val active = runtime.get() ?: LlmEngineClient(app).also(runtime::set)
                val draft = generateYDraft(app, author, active, postPrompt, thinking)
                val post = completeYPost(app, draft, onSaved = { post ->
                    posts = listOf(post) + posts.filterNot { it.id == post.id }
                    if (post.id !in commentCounts) commentCounts += post.id to 0
                }, onProgress = { percent ->
                    if (run == generationRun) imageProgress = percent
                })
                posts = posts.map { if (it.id == post.id) post else it }
                if (run == generationRun) feedState.requestScrollToItem(0)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Throwable) {
                if (run == generationRun) snackbar.showSnackbar(app.getString(llmErrorResource(failure, R.string.chat_engine_failed)))
            } finally {
                if (run == generationRun) {
                    generationJob = null
                    generation = YGeneration.Idle
                    imageProgress = null
                }
            }
        }
    }

    fun regenerateImage(post: YPost) {
        if (generation.busy || loading || sending || replying) return
        val run = generationRun + 1
        generationRun = run
        generation = YGeneration.Writing(characters.firstOrNull { it.id == post.characterId }
            ?: CharacterCard(post.characterId, post.characterName, post.authorHandle, "", "", "", "", ""))
        generationJob = scope.launch {
            try {
                AutomaticSocialPosts.awaitManualTurn()
                val prompt = post.imagePrompt.ifBlank { post.text }
                val image = generatePromptImage(app, prompt, freshSeed = true, characterId = post.characterId) { percent ->
                    if (run == generationRun) imageProgress = percent
                }
                val updated = withContext(kotlinx.coroutines.NonCancellable + Dispatchers.IO) {
                    replaceYImage(app, post, image)
                }
                if (run == generationRun) {
                    posts = posts.map { if (it.id == post.id) updated else it }
                    if (openThread?.id == post.id) openThread = updated
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Throwable) {
                if (run == generationRun) scope.launch { snackbar.showSnackbar(app.getString(llmErrorResource(failure, R.string.chat_engine_failed))) }
            } finally {
                if (run == generationRun) {
                    generation = YGeneration.Idle
                    generationJob = null
                    imageProgress = null
                }
            }
        }
    }

    fun stopGeneration() {
        if (!generation.busy && !replying) return
        generationRun++
        generationJob?.cancel()
        generationJob = null
        replyJob?.cancel()
        replyJob = null
        runtime.get()?.cancel()
        generation = YGeneration.Idle
        replying = false
        replyStatus = null
        imageProgress = null
    }

    fun openComments(post: YPost) {
        openThread = post
        commentDraft = app.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE).getString(KEY_COMMENT_DRAFT_PREFIX + post.id, "").orEmpty()
        threadComments = emptyList()
        scope.launch {
            val comments = withContext(Dispatchers.IO) { readSocialComments(app.filesDir, DIRECTORY, post.id) }
            if (openThread?.id == post.id) {
                threadComments = (comments + threadComments).associateBy(SocialComment::id).values.sortedBy(SocialComment::createdAt)
            }
        }
    }

    private fun startCharacterReplies(post: YPost, latestUserText: String, userComment: SocialComment) {
        if (replying || generation.busy) return
        val eligible = characters.filter(CharacterCard::yEnabled)
        val targets = selectSocialReplyTargets(
            mentionedSocialCharacters(latestUserText, characters), eligible, listOf(post.characterId), FALLBACK_RESPONDERS, REPLY_LIMIT,
        )
        if (targets.isEmpty()) return
        replying = true
        replyJob = scope.launch {
            try {
                AutomaticSocialPosts.awaitManualTurn()
                val settings = withContext(Dispatchers.IO) { LlmSettingsStore.snapshot(app) }
                if (settings == null) {
                    scope.launch { snackbar.showSnackbar(app.getString(R.string.social_no_engine)) }
                    return@launch
                }
                val active = runtime.get() ?: LlmEngineClient(app).also(runtime::set)
                // Freeze earlier context once; new character replies never enter another responder's request.
                val savedComments = withContext(Dispatchers.IO) {
                    readSocialComments(app.filesDir, DIRECTORY, post.id)
                }
                runSocialCharacterReplies(
                    DIRECTORY, targets, savedComments, userComment, latestUserText, settings, active,
                    macros = { character -> MacroBus(app, character, profile) },
                    post = "Y post by ${post.characterName} (${post.authorHandle}):\n${post.text}",
                    thinking = thinking,
                    onCharacter = { character ->
                        imageProgress = null
                        replyStatus = app.getString(R.string.social_character_replying, character.name)
                    },
                    onImage = { replyStatus = app.getString(R.string.chat_image_generating) },
                    onImageProgress = { imageProgress = it },
                ) { comment, isNew ->
                    if (openThread?.id == post.id) {
                        threadComments = (threadComments.filterNot { it.id == comment.id } + comment)
                            .sortedBy(SocialComment::createdAt)
                    }
                    if (isNew) commentCounts += post.id to ((commentCounts[post.id] ?: 0) + 1)
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Throwable) {
                snackbar.showSnackbar(app.getString(llmErrorResource(failure, R.string.chat_engine_failed)))
            } finally {
                replying = false
                replyStatus = null
                imageProgress = null
                replyJob = null
            }
        }
    }

    fun submitComment(post: YPost) {
        val submittedDraft = commentDraft
        val input = submittedDraft.trim()
        val text = input.take(POST_LIMIT)
        if ((text.isBlank()) || sending || replying || generation.busy) return
        sending = true
        val authorName = profile.name.ifBlank { app.getString(R.string.label_you) }
        scope.launch {
            try {
                val comment = persistUserSocialComment(app.filesDir, DIRECTORY, post.id, authorName, profile.handle, text)
                if (openThread?.id == post.id && threadComments.none { it.id == comment.id }) {
                    threadComments += comment
                }
                commentCounts += post.id to ((commentCounts[post.id] ?: 0) + 1)
                if (app.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE).getString(KEY_COMMENT_DRAFT_PREFIX + post.id, "").orEmpty() == submittedDraft) {
                    app.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE).edit { remove(KEY_COMMENT_DRAFT_PREFIX + post.id) }
                    if (openThread?.id == post.id) commentDraft = ""
                }
                startCharacterReplies(post, text, comment)
            } finally {
                sending = false
            }
        }
    }

}

internal const val FALLBACK_RESPONDERS = 2
internal const val REPLY_LIMIT = 4
internal suspend fun generateYDraft(
    app: Context, author: CharacterCard, active: LlmEngineClient, postPrompt: String,
    thinking: Boolean = false,
): YDraft {
    val request = withContext(Dispatchers.IO) {
        val settings = LlmSettingsStore.snapshot(app)
            ?: throw MacroException(R.string.social_no_engine, "No engine selected")
        val bus = MacroBus(app, author)

        LlmRequest(
            config = settings.sessionConfig(systemInstruction = AssistantProtocol.systemInstruction(settings.runtime, bus, listOf(postPrompt))),
            input = LlmInput(
                text = bus.text("{{char}}"),
                context = CharacterMemory(app, author.id).recall(postPrompt, newConversation = true),
            ),
            thinking = thinking,
        )
    }
    val response = active.generate(request).complete()
    val (postText, scenePrompt) = ImagePrompt.split(MacroBus(app, author).text(response.text))
    val caption = SocialPostJson.text(postText, "post").capitalizeFirstVisibleLetter()
    return YDraft(
        character = author,
        text = caption,
        thoughtProcess = response.channels.values.joinToString("\n\n"),
        imagePrompt = scenePrompt.orEmpty(),
    )
}

internal suspend fun createAutomaticYPost(
    app: Context, author: CharacterCard, client: LlmEngineClient,
    onSaved: () -> Unit,
    onProgress: (Int) -> Unit = {},
) {
    val draft = generateYDraft(app, author, client,
        app.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE).getString(KEY_PROMPT, app.getString(R.string.y_default_prompt)).orEmpty(),
        app.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE).getBoolean(KEY_THINKING, false))
    completeYPost(app, draft, onSaved = { onSaved() }, onProgress = onProgress)
}

private const val PREFERENCES = "y_settings"
private const val KEY_PROMPT = "prompt"
private const val KEY_THINKING = "thinking"
