package com.mrj.fancyai.ui.ustagram

import androidx.compose.runtime.setValue
import androidx.compose.runtime.getValue
import android.content.Context
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.core.content.edit
import com.mrj.fancyai.R
import com.mrj.fancyai.service.llm.LlmEngineClient
import com.mrj.fancyai.service.llm.MacroBus
import com.mrj.fancyai.service.llm.generatePromptImage
import com.mrj.fancyai.service.llm.llmErrorResource
import com.mrj.fancyai.ui.characters.CharacterCard
import com.mrj.fancyai.ui.characters.availableCharacters
import com.mrj.fancyai.ui.profile.userProfile
import com.mrj.fancyai.ui.settings.LlmSettingsStore
import com.mrj.fancyai.ui.social.AutomaticSocialPosts
import com.mrj.fancyai.ui.social.SocialComment
import com.mrj.fancyai.ui.social.deleteSocialPostMemories
import com.mrj.fancyai.ui.social.mentionedSocialCharacters
import com.mrj.fancyai.ui.social.persistUserSocialComment
import com.mrj.fancyai.ui.social.readSocialComments
import com.mrj.fancyai.ui.social.runSocialCharacterReplies
import com.mrj.fancyai.ui.social.selectSocialReplyTargets
import com.mrj.fancyai.util.deleteReferencedImages
import java.io.File
import java.util.Locale
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal class UstagramController(
    private val app: Context,
    private val scope: CoroutineScope,
    private val snackbar: SnackbarHostState,
    private val feedState: LazyListState,
) {
    val runtime = AtomicReference<LlmEngineClient?>()
    val profile = userProfile(app)
    var characters by mutableStateOf<List<CharacterCard>>(emptyList())
    var posts by mutableStateOf<List<UstagramPost>>(emptyList())
    var commentCounts by mutableStateOf<Map<String, Int>>(emptyMap())
    var loading by mutableStateOf(value = true)
    var generation by mutableStateOf<UstagramGeneration>(UstagramGeneration.Idle)
    var generationJob by mutableStateOf<Job?>(null)
    var replyJob by mutableStateOf<Job?>(null)
    var replying by mutableStateOf(value = false)
    var sending by mutableStateOf(false)
    val busy get() = sending || generation.busy || replying
    var replyImageProgress by mutableStateOf<Int?>(null)
    var replyStatus by mutableStateOf<String?>(null)
    var generationRun by mutableIntStateOf(0)
    var openThread by mutableStateOf<UstagramPost?>(null)
    var threadComments by mutableStateOf<List<SocialComment>>(emptyList())
    var commentDraft by mutableStateOf("")
    var themeDraft by mutableStateOf(app.getSharedPreferences("ustagram_settings", Context.MODE_PRIVATE).getString(KEY_THEME_DRAFT, "").orEmpty())
    var themeFilter by mutableStateOf(app.getSharedPreferences("ustagram_settings", Context.MODE_PRIVATE).getString(KEY_THEME_FILTER, "").orEmpty())
    var prompt by mutableStateOf(app.getSharedPreferences("ustagram_settings", Context.MODE_PRIVATE).getString(KEY_PROMPT, app.getString(R.string.ustagram_default_prompt)).orEmpty())
    var thinking by mutableStateOf(app.getSharedPreferences("ustagram_settings", Context.MODE_PRIVATE).getBoolean(KEY_THINKING, false))
        private set

    fun updateThinking(value: Boolean) {
        thinking = value
        app.getSharedPreferences("ustagram_settings", Context.MODE_PRIVATE).edit { putBoolean(KEY_THINKING, value) }
    }

    var customThemes by mutableStateOf(app.getSharedPreferences("ustagram_settings", Context.MODE_PRIVATE).getString(KEY_CUSTOM_THEMES, "").orEmpty().lineSequence().mapNotNull(::normalizeUstagramTheme).distinctBy { it.lowercase(Locale.ROOT) }.toList())
    var disabledThemes by mutableStateOf(app.getSharedPreferences("ustagram_settings", Context.MODE_PRIVATE).getString(KEY_DISABLED_THEMES, "").orEmpty().lineSequence().mapNotNull(::normalizeUstagramTheme).toSet())
    val enabledThemes get() = customThemes.filterNot { theme -> disabledThemes.any { it.equals(theme, true) } }

    fun updatePrompt(value: String = app.getString(R.string.ustagram_default_prompt)) {
        prompt = value
        app.getSharedPreferences("ustagram_settings", Context.MODE_PRIVATE).edit { putString(KEY_PROMPT, value) }
    }

    fun updateThemeDraft(value: String) {
        themeDraft = value.take(THEME_LIMIT)
        app.getSharedPreferences("ustagram_settings", Context.MODE_PRIVATE).edit { putString(KEY_THEME_DRAFT, themeDraft) }
    }

    fun updateThemeFilter(value: String) {
        themeFilter = value.take(THEME_LIMIT)
        app.getSharedPreferences("ustagram_settings", Context.MODE_PRIVATE).edit { putString(KEY_THEME_FILTER, themeFilter) }
    }

    private fun updateCustomThemes(themes: List<String>) {
        customThemes = themes
        app.getSharedPreferences("ustagram_settings", Context.MODE_PRIVATE).edit { putString(KEY_CUSTOM_THEMES, customThemes.joinToString("\n")) }
    }

    fun updateDisabledThemes(themes: Set<String>) {
        disabledThemes = themes
        app.getSharedPreferences("ustagram_settings", Context.MODE_PRIVATE).edit { putString(KEY_DISABLED_THEMES, disabledThemes.sorted().joinToString("\n")) }
    }

    fun addTheme() {
        val theme = normalizeUstagramTheme(themeDraft)
        if ((theme != null) && customThemes.none { it.equals(theme, ignoreCase = true) }) {
            updateCustomThemes(customThemes + theme)
            updateDisabledThemes(disabledThemes - theme)
            updateThemeDraft("")
        }
    }

    fun removeTheme(theme: String) {
        updateCustomThemes(customThemes - theme)
        updateDisabledThemes(disabledThemes - theme)
    }

    fun report(post: UstagramPost) {
        if (busy || post.reported) return
        sending = true
        scope.launch {
            try {
                AutomaticSocialPosts.awaitManualTurn()
                withContext(kotlinx.coroutines.NonCancellable) {
                    val reported = withContext(Dispatchers.IO) {
                        val folder = File(File(app.filesDir, DIRECTORY), post.id)
                        val current = readUstagramPost(folder)
                        current.copy(reported = true).also { writeUstagramPostProperties(folder, it) }
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

    fun deletePosts(post: UstagramPost? = null) {
        if (busy) return
        sending = true
        val removed = if (post == null) posts else listOf(post)
        scope.launch {
            try {
                AutomaticSocialPosts.awaitManualTurn()
                generationJob?.join()
                withContext(Dispatchers.IO) {
                    val folder = File(File(app.filesDir, DIRECTORY), post?.id.orEmpty())
                    deleteReferencedImages(app, folder)
                    folder.deleteRecursively()
                    val preferences = app.getSharedPreferences("ustagram_settings", Context.MODE_PRIVATE)
                    val draftKeys = if (post == null) {
                        preferences.all.keys.filter { it.startsWith(KEY_COMMENT_DRAFT_PREFIX) }
                    } else {
                        listOf(KEY_COMMENT_DRAFT_PREFIX + post.id)
                    }
                    preferences.edit { draftKeys.forEach(::remove) }
                }
                removed.forEach { deleteSocialPostMemories(app, DIRECTORY, it.id, it.characterId) }
                posts = posts.filterNot { post == null || it.id == post.id }
                commentCounts = commentCounts.filterKeys { post != null && it != post.id }
                if (post == null || openThread?.id == post.id) {
                    openThread = null
                    threadComments = emptyList()
                }
                if (post != null) scope.launch { snackbar.showSnackbar(app.getString(R.string.social_post_deleted)) }
            } finally {
                sending = false
            }
        }
    }

    fun persistPost(draft: UstagramDraft) {
        val run = generationRun + 1
        generationRun = run
        generation = UstagramGeneration.Rendering(draft.character, draft)
        feedState.requestScrollToItem(0)
        generationJob = scope.launch {
            try {
                AutomaticSocialPosts.awaitManualTurn()
                val post = completeUstagramPost(app, draft, onSaved = { post ->
                    posts = listOf(post) + posts.filterNot { it.id == post.id }
                    if (post.id !in commentCounts) commentCounts += post.id to 0
                }, onProgress = { percent -> if (run == generationRun) { (generation as? UstagramGeneration.Rendering)?.let { generation = it.copy(progress = percent) } } })
                posts = posts.map { if (it.id == post.id) post else it }
                if (openThread?.id == post.id) openThread = post
                if (run == generationRun) {
                    feedState.requestScrollToItem(0)
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Throwable) {
                if (run == generationRun) snackbar.showSnackbar(app.getString(llmErrorResource(failure, R.string.chat_engine_failed)))
            } finally {
                if (run == generationRun) generation = UstagramGeneration.Idle
            }
        }
    }

    fun regenerateImage(post: UstagramPost) {
        if (busy || loading) return
        val author = characters.firstOrNull { it.id == post.characterId }
            ?: CharacterCard(post.characterId, post.characterName, post.authorHandle, "", "", "", "", "")
        val run = generationRun + 1
        generationRun = run
        generation = UstagramGeneration.Rendering(author)
        generationJob = scope.launch {
            try {
                AutomaticSocialPosts.awaitManualTurn()
                val prompt = post.imagePrompt.ifBlank { post.caption }
                val image = generatePromptImage(app, prompt, freshSeed = true, characterId = post.characterId) { percent ->
                    if (run == generationRun) (generation as? UstagramGeneration.Rendering)?.let { generation = it.copy(progress = percent) }
                }
                val updated = withContext(kotlinx.coroutines.NonCancellable + Dispatchers.IO) {
                    replaceUstagramImage(app, post, image)
                }
                if (run == generationRun) {
                    posts = posts.map { if (it.id == post.id) updated else it }
                    if (openThread?.id == post.id) openThread = updated
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Throwable) {
                if (run == generationRun) snackbar.showSnackbar(app.getString(llmErrorResource(failure, R.string.chat_engine_failed)))
            } finally {
                if (run == generationRun) {
                    generation = UstagramGeneration.Idle
                    generationJob = null
                }
            }
        }
    }

    fun startPost(character: CharacterCard? = null, theme: String? = null) {
        if (busy || loading) return
        val eligible = characters.filter(CharacterCard::ustagramEnabled)
        val author = character?.takeIf(CharacterCard::ustagramEnabled) ?: eligible.randomOrNull()
        if (author == null) {
            scope.launch { snackbar.showSnackbar(app.getString(R.string.ustagram_no_posters)) }
            return
        }
        val postPrompt = prompt
        val excludedThemes = disabledThemes
        val allLabel = app.getString(R.string.ustagram_theme_all)
        val filterTheme = themeFilter.takeIf { it != allLabel }.orEmpty()
        val requestedTheme = normalizeUstagramTheme(theme ?: enabledThemes.firstOrNull { it.equals(filterTheme, true) }.orEmpty())
        val run = generationRun + 1
        generationRun = run
        generation = UstagramGeneration.Writing(author)
        generationJob = scope.launch {
            try {
                AutomaticSocialPosts.awaitManualTurn()
                val active = runtime.get() ?: LlmEngineClient(app).also(runtime::set)
                val draft = generateUstagramDraft(app, author, active, postPrompt, requestedTheme,
                    enabledThemes, excludedThemes, thinking)
                persistPost(draft)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Throwable) {
                if (run == generationRun) snackbar.showSnackbar(app.getString(llmErrorResource(failure, R.string.chat_engine_failed)))
            } finally {
                if (run == generationRun) generation = UstagramGeneration.Idle
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
        generation = UstagramGeneration.Idle
        replying = false
        replyStatus = null
        replyImageProgress = null
    }

    fun openComments(post: UstagramPost) {
        openThread = post
        commentDraft = app.getSharedPreferences("ustagram_settings", Context.MODE_PRIVATE).getString(KEY_COMMENT_DRAFT_PREFIX + post.id, "").orEmpty()
        threadComments = emptyList()
        scope.launch {
            val comments = withContext(Dispatchers.IO) { readSocialComments(app.filesDir, DIRECTORY, post.id) }
            if (openThread?.id == post.id) {
                threadComments = (comments + threadComments).associateBy(SocialComment::id).values.sortedBy(SocialComment::createdAt)
            }
        }
    }

    fun startCharacterReplies(post: UstagramPost, latestUserText: String, userComment: SocialComment) {
        if (replying || generation.busy) return
        val eligible = characters.filter(CharacterCard::ustagramEnabled)
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
                    snackbar.showSnackbar(app.getString(R.string.ustagram_no_engine))
                    return@launch
                }
                val active = runtime.get() ?: LlmEngineClient(app).also(runtime::set)
                // The latest user comment is already supplied below; freeze earlier context for every responder.
                val savedComments = withContext(Dispatchers.IO) {
                    readSocialComments(app.filesDir, DIRECTORY, post.id)
                }
                runSocialCharacterReplies(
                    DIRECTORY, targets, savedComments, userComment, latestUserText, settings, active,
                    macros = { character -> MacroBus(app, character, profile) },
                    post = "Ustagram post by ${post.characterName} (${post.authorHandle}), hashtag ${post.theme}:\n${post.caption}",
                    thinking = thinking,
                    onCharacter = { character ->
                        replyImageProgress = null
                        replyStatus = app.getString(R.string.social_character_replying, character.name)
                    },
                    onImage = { replyStatus = app.getString(R.string.chat_image_generating) },
                    onImageProgress = { replyImageProgress = it },
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
                replyImageProgress = null
                replyJob = null
            }
        }
    }

    fun submitComment(post: UstagramPost) {
        val submittedDraft = commentDraft
        val input = submittedDraft.trim()
        val text = input.take(COMMENT_LIMIT)
        if ((text.isBlank()) || busy) return
        sending = true
        val authorName = profile.name.ifBlank { app.getString(R.string.label_you) }
        scope.launch {
            try {
                val comment = persistUserSocialComment(app.filesDir, DIRECTORY, post.id, authorName, profile.handle, text)
                if (openThread?.id == post.id && threadComments.none { it.id == comment.id }) {
                    threadComments += comment
                }
                commentCounts += post.id to ((commentCounts[post.id] ?: 0) + 1)
                if (app.getSharedPreferences("ustagram_settings", Context.MODE_PRIVATE).getString(KEY_COMMENT_DRAFT_PREFIX + post.id, "").orEmpty() == submittedDraft) {
                    app.getSharedPreferences("ustagram_settings", Context.MODE_PRIVATE).edit { remove(KEY_COMMENT_DRAFT_PREFIX + post.id) }
                    if (openThread?.id == post.id) commentDraft = ""
                }
                startCharacterReplies(post, text, comment)
            } finally {
                sending = false
            }
        }
    }

    suspend fun loadFeed() {
        if (busy) return
        try {
            val loaded = withContext(Dispatchers.IO) {
                val loadedPosts = readUstagramPosts(app)
                Triple(
                    availableCharacters(app),
                    loadedPosts,
                    loadedPosts.associateBy(UstagramPost::id) {
                        readSocialComments(app.filesDir, DIRECTORY, it.id).size
                    },
                )
            }
            characters = loaded.first
            posts = loaded.second
            commentCounts = loaded.third
            openThread?.id?.let { openId ->
                if (loaded.second.none { it.id == openId }) {
                    openThread = null
                    threadComments = emptyList()
                }
            }
        } finally {
            loading = false
        }
    }
}
