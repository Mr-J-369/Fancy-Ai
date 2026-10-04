package com.mrj.fancyai.ui.rebbit

import androidx.compose.runtime.setValue
import androidx.compose.runtime.getValue
import android.content.Context
import android.content.SharedPreferences
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
import com.mrj.fancyai.ui.social.readSocialComments
import com.mrj.fancyai.ui.social.runSocialCharacterReplies
import com.mrj.fancyai.ui.social.selectSocialReplyTargets
import com.mrj.fancyai.ui.social.writeSocialComment
import com.mrj.fancyai.util.deleteReferencedImages
import java.io.File
import java.util.Locale
import java.util.UUID
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal const val KEY_REBBIT_COMMUNITY_DRAFT = "community_draft"
internal const val KEY_REBBIT_COMMUNITY_FILTER = "community_filter"
internal const val REBBIT_COMMUNITY_INPUT_LIMIT = 42
internal const val KEY_COMMUNITY_SEARCH = "community_search"

internal class RebbitController(
    private val app: Context,
    private val scope: CoroutineScope,
    private val snackbar: SnackbarHostState,
    val feedState: LazyListState,
) {
    val preferences: SharedPreferences = app.getSharedPreferences("rebbit_settings", Context.MODE_PRIVATE)
    val runtime = AtomicReference<LlmEngineClient?>()
    val profile = userProfile(app)
    var loadedRevision: Long? = null
    var characters by mutableStateOf<List<CharacterCard>>(emptyList())
    var posts by mutableStateOf<List<RebbitPost>>(emptyList())
    var commentCounts by mutableStateOf<Map<String, Int>>(emptyMap())
    var loading by mutableStateOf(value = true)
    var generation by mutableStateOf<RebbitGeneration>(RebbitGeneration.Idle)
    var generationJob by mutableStateOf<Job?>(null)
    var replyJob by mutableStateOf<Job?>(null)
    var replying by mutableStateOf(value = false)
    var replyImageProgress by mutableStateOf<Int?>(null)
    var replyStatus by mutableStateOf<String?>(null)
    var generationRun by mutableIntStateOf(0)
    var openThread by mutableStateOf<RebbitPost?>(null)
    var threadComments by mutableStateOf<List<SocialComment>>(emptyList())
    var commentDraft by mutableStateOf("")
    var replyToId by mutableStateOf("")
    var threadLoading by mutableStateOf(false)
    var sending by mutableStateOf(false)
    var menuOpen by mutableStateOf(false)
    var clearRequested by mutableStateOf(false)
    var settingsOpen by mutableStateOf(false)
    var communitiesOnly by mutableStateOf(false)
    val busy get() = sending || generation.busy || replying
    var rebbitPrompt by mutableStateOf(preferences.getString(KEY_REBBIT_PROMPT, app.getString(R.string.rebbit_default_prompt)).orEmpty())
    var thinking by mutableStateOf(preferences.getBoolean(KEY_THINKING, false))
        private set

    fun updateThinking(value: Boolean) {
        thinking = value
        preferences.edit { putBoolean(KEY_THINKING, value) }
    }


    private fun saveCommunityState() {
        preferences.edit {
            putString(KEY_REBBIT_COMMUNITIES, customCommunities.joinToString("\n"))
            putString(KEY_REBBIT_DISABLED_COMMUNITIES, disabledCommunities.joinToString("\n"))
        }
    }
    var customCommunities by mutableStateOf(preferences.getString(KEY_REBBIT_COMMUNITIES, "").orEmpty().lineSequence().mapNotNull(::normalizedCommunity).distinct().toList())
    var disabledCommunities by mutableStateOf(preferences.getString(KEY_REBBIT_DISABLED_COMMUNITIES, "").orEmpty().lineSequence().mapNotNull(::normalizedCommunity).toSet())
    val allCommunities by androidx.compose.runtime.derivedStateOf {
        (customCommunities + posts.map(RebbitPost::community).filterNot { community -> disabledCommunities.any { it.equals(community, true) } })
            .filter(String::isNotBlank).distinctBy { it.lowercase(Locale.ROOT) }.sortedBy { it.lowercase(Locale.ROOT) }
    }
    val enabledCommunities get() = customCommunities.filterNot { community -> disabledCommunities.any { it.equals(community, true) } }
    var communityDraft by mutableStateOf(preferences.getString(KEY_REBBIT_COMMUNITY_DRAFT, "").orEmpty())
    var communitySearch by mutableStateOf(preferences.getString(KEY_COMMUNITY_SEARCH, "").orEmpty())
    var communityFilter by mutableStateOf(preferences.getString(KEY_REBBIT_COMMUNITY_FILTER, "").orEmpty())

    fun navigateBack(onExit: () -> Unit) {
        when {
            settingsOpen -> settingsOpen = false
            openThread != null -> openThread = null
            communityFilter.isNotBlank() -> selectCommunity("")
            else -> onExit()
        }
    }

    fun selectCommunity(community: String) {
        communityFilter = community
        preferences.edit { putString(KEY_REBBIT_COMMUNITY_FILTER, community) }
        openThread = null
    }

    fun addCommunity() {
        normalizedCommunity(communityDraft.filterNot(Char::isWhitespace))?.let { community ->
            if (customCommunities.none { it.equals(community, true) }) customCommunities += community
            disabledCommunities = disabledCommunities.filterNot { it.equals(community, true) }.toSet()
            saveCommunityState()
            communityDraft = ""
            preferences.edit { putString(KEY_REBBIT_COMMUNITY_DRAFT, "") }
        }
    }

    fun setAllCommunitiesEnabled(enabled: Boolean, communities: List<String>) {
        customCommunities = communities
        disabledCommunities = if (enabled) disabledCommunities.filterNot { disabled -> communities.any { it.equals(disabled, true) } }.toSet() else disabledCommunities + communities
        saveCommunityState()
    }

    fun setCommunityEnabled(community: String, enabled: Boolean) {
        if (customCommunities.none { it.equals(community, true) }) customCommunities += community
        disabledCommunities = if (enabled) disabledCommunities.filterNot { it.equals(community, true) }.toSet() else disabledCommunities + community
        saveCommunityState()
    }

    fun removeCommunity(community: String) {
        customCommunities = customCommunities.filterNot { it.equals(community, true) }
        disabledCommunities += community
        saveCommunityState()
    }

    fun addComment(comment: SocialComment) {
        if (openThread?.id == comment.postId) {
            threadComments = threadComments + comment
        }
        commentCounts += comment.postId to ((commentCounts[comment.postId] ?: 0) + 1)
    }

    fun report(post: RebbitPost) {
        if (busy || post.reported) return
        sending = true
        scope.launch {
            try {
                AutomaticSocialPosts.awaitManualTurn()
                withContext(kotlinx.coroutines.NonCancellable) {
                    val reported = withContext(Dispatchers.IO) {
                        val folder = File(File(app.filesDir, REBBIT_DIRECTORY), post.id)
                        val current = readRebbitPosts(app).firstOrNull { it.id == post.id }
                            ?: error("Saved post could not be read")
                        current.copy(reported = true).also { writeRebbitPostProperties(folder, it) }
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

    fun deletePosts(post: RebbitPost? = null) {
        if (busy) return
        sending = true
        val removed = if (post == null) posts else listOf(post)
        scope.launch {
            try {
                AutomaticSocialPosts.awaitManualTurn()
                generationJob?.join()
                withContext(Dispatchers.IO) {
                    val folder = File(File(app.filesDir, REBBIT_DIRECTORY), post?.id.orEmpty())
                    deleteReferencedImages(app, folder)
                    folder.deleteRecursively()
                    val preferences = app.getSharedPreferences("rebbit_settings", Context.MODE_PRIVATE)
                    val draftKeys = if (post == null) {
                        preferences.all.keys.filter { it.startsWith(KEY_COMMENT_DRAFT_PREFIX) }
                    } else {
                        listOf(KEY_COMMENT_DRAFT_PREFIX + post.id, KEY_COMMENT_DRAFT_PREFIX + post.id + "_parent")
                    }
                    preferences.edit { draftKeys.forEach(::remove) }
                }
                removed.forEach { deleteSocialPostMemories(app, REBBIT_DIRECTORY, it.id, it.characterId) }
                posts = posts.filterNot { post == null || it.id == post.id }
                commentCounts = commentCounts.filterKeys { post != null && it != post.id }
                if (post == null || openThread?.id == post.id) {
                    openThread = null
                    threadComments = emptyList()
                }
            } finally {
                loadedRevision = null
                sending = false
            }
        }
    }

    fun persistPost(draft: RebbitDraft) {
        val run = generationRun + 1
        generationRun = run
        generation = RebbitGeneration.Rendering(draft.character, draft, 0)
        feedState.requestScrollToItem(0)
        generationJob = scope.launch {
            try {
                AutomaticSocialPosts.awaitManualTurn()
                val post = completeRebbitPost(app, draft, onSaved = { post ->
                    posts = listOf(post) + posts.filterNot { it.id == post.id }
                    if (post.id !in commentCounts) commentCounts += post.id to 0
                }, onProgress = { percent -> if (run == generationRun) { (generation as? RebbitGeneration.Rendering)?.let { generation = it.copy(progress = percent) } } })
                posts = posts.map { if (it.id == post.id) post else it }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Throwable) {
                if (run == generationRun) snackbar.showSnackbar(app.getString(llmErrorResource(failure, R.string.chat_engine_failed)))
            } finally {
                if (run == generationRun) generation = RebbitGeneration.Idle
            }
        }
    }

    fun regenerateImage(post: RebbitPost) {
        if (busy || loading) return
        val author = characters.firstOrNull { it.id == post.characterId }
            ?: CharacterCard(post.characterId, post.characterName, post.authorHandle, "", "", "", "", "")
        val run = generationRun + 1
        generationRun = run
        generation = RebbitGeneration.Rendering(author, null, 0)
        generationJob = scope.launch {
            try {
                AutomaticSocialPosts.awaitManualTurn()
                val prompt = post.imagePrompt.ifBlank { post.caption }
                val image = generatePromptImage(app, prompt, freshSeed = true, characterId = post.characterId, onProgress = { percent -> if (run == generationRun) { (generation as? RebbitGeneration.Rendering)?.let { generation = it.copy(progress = percent) } } })
                val updated = withContext(kotlinx.coroutines.NonCancellable + Dispatchers.IO) {
                    replaceRebbitImage(app, post, image)
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
                    generation = RebbitGeneration.Idle
                    generationJob = null
                }
            }
        }
    }

    fun startPost(character: CharacterCard? = null, community: String? = null) {
        if (busy || loading) return
        val eligible = characters.filter(CharacterCard::rebbitEnabled)
        val author = character?.takeIf(CharacterCard::rebbitEnabled) ?: eligible.randomOrNull()
        if (author == null) {
            scope.launch { snackbar.showSnackbar(app.getString(R.string.rebbit_no_posters)) }
            return
        }
        val run = generationRun + 1
        generationRun = run
        generation = RebbitGeneration.Writing(author)
        generationJob = scope.launch {
            try {
                AutomaticSocialPosts.awaitManualTurn()
                val active = runtime.get() ?: LlmEngineClient(app).also(runtime::set)
                val draft = generateRebbitDraft(app, author, active, rebbitPrompt, community, enabledCommunities,
                    disabledCommunities, onCommunity = { target -> generation = RebbitGeneration.Writing(author, target) },
                    thinking = thinking)
                persistPost(draft)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Throwable) {
                if (run == generationRun) snackbar.showSnackbar(app.getString(llmErrorResource(failure, R.string.chat_engine_failed)))
            } finally {
                if (run == generationRun) generation = RebbitGeneration.Idle
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
        generation = RebbitGeneration.Idle
        replying = false
        replyStatus = null
        replyImageProgress = null
    }

    fun startCharacterReplies(post: RebbitPost, latestUserText: String, userComment: SocialComment) {
        if (replying || generation.busy) return
        val eligible = characters.filter(CharacterCard::rebbitEnabled)
        val mentioned = mentionedSocialCharacters(latestUserText, characters)
        if (eligible.isEmpty() && mentioned.isEmpty()) return
        replying = true
        replyJob = scope.launch {
            try {
                AutomaticSocialPosts.awaitManualTurn()
                val settings = withContext(Dispatchers.IO) { LlmSettingsStore.snapshot(app) }
                if (settings == null) {
                    snackbar.showSnackbar(app.getString(R.string.social_no_engine))
                    return@launch
                }
                val savedComments = withContext(Dispatchers.IO) { readSocialComments(app.filesDir, REBBIT_DIRECTORY, post.id) }
                val parentComment = savedComments.firstOrNull { it.id == userComment.parentId }
                val targets = selectSocialReplyTargets(
                    mentioned,
                    eligible,
                    listOfNotNull(parentComment?.authorId, post.characterId),
                    REBBIT_FALLBACK_RESPONDERS,
                    REBBIT_REPLY_LIMIT,
                )
                val active = runtime.get() ?: LlmEngineClient(app).also(runtime::set)
                runSocialCharacterReplies(
                    REBBIT_DIRECTORY, targets, savedComments, userComment, latestUserText, settings, active,
                    macros = { character -> MacroBus(app, character, profile) },
                    post = "Rebbit post by ${post.characterName} (${post.authorHandle}) in ${post.community}:\n${post.caption}",
                    additionalContext = listOfNotNull(parentComment?.let { "Replying to ${it.authorName}: ${it.text}" }),
                    parentId = userComment.id,
                    thinking = thinking,
                    onCharacter = { character ->
                        replyImageProgress = null
                        replyStatus = app.getString(R.string.social_character_replying, character.name)
                    },
                    onImage = { replyStatus = app.getString(R.string.chat_image_generating) },
                    onImageProgress = { replyImageProgress = it },
                ) { comment, isNew ->
                    if (isNew) addComment(comment)
                    else if (openThread?.id == post.id) {
                        threadComments = threadComments.map { if (it.id == comment.id) comment else it }
                    }
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

    fun openPost(post: RebbitPost) {
        openThread = post
        commentDraft = app.getSharedPreferences("rebbit_settings", Context.MODE_PRIVATE).getString(KEY_COMMENT_DRAFT_PREFIX + post.id, "").orEmpty()
        replyToId = app.getSharedPreferences("rebbit_settings", Context.MODE_PRIVATE).getString(KEY_COMMENT_DRAFT_PREFIX + post.id + "_parent", "").orEmpty()
        threadComments = emptyList()
        threadLoading = true
        scope.launch {
            try {
                val comments = withContext(Dispatchers.IO) { readSocialComments(app.filesDir, REBBIT_DIRECTORY, post.id) }
                if (openThread?.id == post.id) {
                    threadComments = (comments + threadComments).associateBy(SocialComment::id).values.sortedBy(SocialComment::createdAt)
                    commentCounts = commentCounts + (post.id to threadComments.size)
                }
            } finally {
                if (openThread?.id == post.id) threadLoading = false
            }
        }
    }

    fun sendComment(post: RebbitPost) {
        val submittedDraft = commentDraft
        val input = submittedDraft.trim()
        val text = input.take(REBBIT_COMMENT_LIMIT)
        if ((text.isBlank()) || sending || replying || generation.busy || threadLoading) return
        sending = true
        val parent = replyToId.takeIf { id -> threadComments.any { it.id == id } }.orEmpty()
        scope.launch {
            try {
                AutomaticSocialPosts.awaitManualTurn()
                val saved = writeSocialComment(app.filesDir, REBBIT_DIRECTORY, SocialComment(UUID.randomUUID().toString(), post.id, REBBIT_USER_ID,
                    profile.name.ifBlank { app.getString(R.string.label_you) }, profile.handle, text,
                    System.currentTimeMillis(), "", File(""), parentId = parent))
                addComment(saved)
                if (app.getSharedPreferences("rebbit_settings", Context.MODE_PRIVATE).getString(KEY_COMMENT_DRAFT_PREFIX + post.id, "").orEmpty() == submittedDraft) {
                    app.getSharedPreferences("rebbit_settings", Context.MODE_PRIVATE).edit { putString(KEY_COMMENT_DRAFT_PREFIX + post.id, "") }
                    app.getSharedPreferences("rebbit_settings", Context.MODE_PRIVATE).edit { putString(KEY_COMMENT_DRAFT_PREFIX + post.id + "_parent", "") }
                    if (openThread?.id == post.id) {
                        commentDraft = ""
                        replyToId = ""
                    }
                }
                startCharacterReplies(post, text, saved)
            } finally { sending = false }
        }
    }

    suspend fun loadFeed(automaticRevision: Long) {
        if (busy || loadedRevision == automaticRevision) return
        try {
            val loaded = withContext(Dispatchers.IO) {
                val feed = readRebbitPosts(app)
                val counts = feed.associate { post ->
                    val folder = File(File(File(app.filesDir, REBBIT_DIRECTORY), post.id), COMMENTS_DIRECTORY)
                    val files = folder.listFiles()
                    post.id to files.orEmpty().count { it.isFile && it.extension == "properties" }
                }
                Triple(feed, availableCharacters(app), counts)
            }
            posts = loaded.first
            characters = loaded.second
            commentCounts = loaded.third
            openThread?.id?.let { openId ->
                if (loaded.first.none { it.id == openId }) {
                    openThread = null
                    threadComments = emptyList()
                }
            }
            loadedRevision = automaticRevision
        } finally {
            loading = false
        }
    }
}
