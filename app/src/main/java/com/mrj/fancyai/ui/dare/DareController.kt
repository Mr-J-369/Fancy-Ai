package com.mrj.fancyai.ui.dare

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.edit
import com.mrj.fancyai.R
import com.mrj.fancyai.service.llm.LlmEngineClient
import com.mrj.fancyai.service.llm.generatePromptImage
import com.mrj.fancyai.service.llm.llmErrorResource
import com.mrj.fancyai.ui.characters.CharacterCard
import com.mrj.fancyai.ui.characters.availableCharacters
import com.mrj.fancyai.ui.profile.userProfile
import com.mrj.fancyai.ui.social.AutomaticSocialPosts
import com.mrj.fancyai.util.deleteReferencedImages
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.atomic.AtomicReference

internal class DareController(
    private val app: Context,
    private val scope: CoroutineScope,
    private val snackbar: SnackbarHostState,
    val feedState: LazyListState,
) {
    val preferences: SharedPreferences = app.getSharedPreferences("dare_settings", Context.MODE_PRIVATE)
    val runtime = AtomicReference<LlmEngineClient?>()
    val profile = userProfile(app)
    var loadedRevision: Long? = null
    var characters by mutableStateOf<List<CharacterCard>>(emptyList())
    var posts by mutableStateOf<List<DarePost>>(emptyList())
    var loading by mutableStateOf(value = true)
    var generation by mutableStateOf<DareGeneration>(DareGeneration.Idle)
    var generationJob by mutableStateOf<Job?>(null)
    var generationRun by mutableIntStateOf(0)
    var sending by mutableStateOf(value = false)
    var menuOpen by mutableStateOf(value = false)
    var clearRequested by mutableStateOf(value = false)
    var settingsOpen by mutableStateOf(value = false)
    var dareDraft by mutableStateOf(preferences.getString("draft_input", "").orEmpty())
    val busy get() = sending || generation.busy
    var darePrompt by mutableStateOf(
        preferences.getString(KEY_DARE_PROMPT, app.getString(R.string.dare_default_prompt)).orEmpty(),
    )
    var thinking by mutableStateOf(preferences.getBoolean(KEY_THINKING, false))
        private set

    fun updateThinking(value: Boolean) {
        thinking = value
        preferences.edit { putBoolean(KEY_THINKING, value) }
    }

    fun updateDareDraft(text: String) {
        dareDraft = text
        preferences.edit { putString("draft_input", text) }
    }

    fun clearDareDraft() {
        dareDraft = ""
        preferences.edit { putString("draft_input", "") }
    }

    fun navigateBack(onExit: () -> Unit) {
        if (settingsOpen) {
            settingsOpen = false
        } else {
            onExit()
        }
    }

    fun deletePosts(post: DarePost? = null) {
        if (busy) return
        sending = true
        val targetPosts = post?.let(::listOf) ?: posts
        scope.launch {
            val remaining = try {
                AutomaticSocialPosts.awaitManualTurn()
                generationJob?.join()
                withContext(Dispatchers.IO) {
                    targetPosts.forEach { target ->
                        com.mrj.fancyai.service.memory.CharacterMemory(app, target.characterId).deleteSession("dare:${target.id}")
                    }
                    val folder = File(File(app.filesDir, DARE_DIRECTORY), post?.id.orEmpty())
                    deleteReferencedImages(app, folder)
                    folder.deleteRecursively()
                }
                posts.filterNot { (post == null) || (it.id == post.id) }
            } finally {
                loadedRevision = null
                sending = false
            }
            posts = remaining
        }
    }

    fun persistPost(draft: DareDraft) {
        val run = generationRun + 1
        generationRun = run
        generation = DareGeneration.Rendering(draft.character, draft, 0)
        scope.launch { feedState.scrollToItem(0) }
        generationJob = scope.launch {
            try {
                AutomaticSocialPosts.awaitManualTurn()
                val post = completeDarePost(
                    app = app,
                    draft = draft,
                    onSaved = { saved ->
                        posts = listOf(saved) + posts.filterNot { it.id == saved.id }
                    },
                ) { percent ->
                    if (run == generationRun) {
                        (generation as? DareGeneration.Rendering)?.let {
                            generation = it.copy(progress = percent)
                        }
                    }
                }
                posts = posts.map { if (it.id == post.id) post else it }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Throwable) {
                if (run == generationRun) {
                    snackbar.showSnackbar(app.getString(llmErrorResource(failure, R.string.chat_engine_failed)))
                }
            } finally {
                if (run == generationRun) generation = DareGeneration.Idle
            }
        }
    }

    fun regenerateImage(post: DarePost) {
        if (busy || loading) return
        val author = characters.firstOrNull { it.id == post.characterId }
            ?: CharacterCard(post.characterId, post.characterName, post.authorHandle, "", "", "", "", "")
        val run = generationRun + 1
        generationRun = run
        generation = DareGeneration.Rendering(author, null, 0)
        generationJob = scope.launch {
            try {
                AutomaticSocialPosts.awaitManualTurn()
                val prompt = post.imagePrompt.ifBlank { post.caption }
                val image = generatePromptImage(
                    app, prompt, freshSeed = true, characterId = post.characterId,
                ) { percent ->
                    if (run == generationRun) {
                        (generation as? DareGeneration.Rendering)?.let {
                            generation = it.copy(progress = percent)
                        }
                    }
                }
                val updated = withContext(kotlinx.coroutines.NonCancellable + Dispatchers.IO) {
                    replaceDareImage(app, post, image)
                }
                if (run == generationRun) {
                    posts = posts.map { if (it.id == post.id) updated else it }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Throwable) {
                if (run == generationRun) {
                    snackbar.showSnackbar(app.getString(llmErrorResource(failure, R.string.chat_engine_failed)))
                }
            } finally {
                if (run == generationRun) {
                    generation = DareGeneration.Idle
                    generationJob = null
                }
            }
        }
    }

    fun startDare(character: CharacterCard? = null, customChallenge: String? = null) {
        if (busy || loading) return
        val eligible = characters.filter(CharacterCard::dareEnabled)
        val author = character?.takeIf(CharacterCard::dareEnabled) ?: eligible.randomOrNull()
        if (author == null) {
            scope.launch { snackbar.showSnackbar(app.getString(R.string.dare_no_posters)) }
            return
        }
        val challenge = customChallenge?.trim()?.ifBlank { null }
            ?: dareDraft.trim().ifBlank { null }
            ?: app.getString(R.string.dare_fallback_title)

        val run = generationRun + 1
        generationRun = run
        generation = DareGeneration.Writing(author, challenge)
        generationJob = scope.launch {
            try {
                AutomaticSocialPosts.awaitManualTurn()
                val active = runtime.get() ?: LlmEngineClient(app).also(runtime::set)
                val draft = generateDareDraft(app, author, active, darePrompt, challenge, thinking)
                persistPost(draft)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Throwable) {
                if (run == generationRun) {
                    snackbar.showSnackbar(app.getString(llmErrorResource(failure, R.string.chat_engine_failed)))
                }
            } finally {
                if (run == generationRun) generation = DareGeneration.Idle
            }
        }
    }

    fun stopGeneration() {
        if (!generation.busy) return
        generationRun++
        generationJob?.cancel()
        generationJob = null
        runtime.get()?.cancel()
        generation = DareGeneration.Idle
    }

    suspend fun loadFeed(automaticRevision: Long) {
        if (busy || (loadedRevision == automaticRevision)) return
        try {
            val loaded = withContext(Dispatchers.IO) {
                val feed = readDarePosts(app)
                Pair(feed, availableCharacters(app))
            }
            posts = loaded.first
            characters = loaded.second
            loadedRevision = automaticRevision
        } finally {
            loading = false
        }
    }
}
