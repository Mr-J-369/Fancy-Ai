package com.mrj.fancyai.ui.social

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.mrj.fancyai.data.db.entity.CharacterEntity
import com.mrj.fancyai.data.db.entity.SocialCommentEntity
import com.mrj.fancyai.data.db.entity.SocialPostEntity
import com.mrj.fancyai.data.repository.MediaRepository
import com.mrj.fancyai.data.repository.SettingsRepository
import com.mrj.fancyai.data.repository.SocialRepository
import com.mrj.fancyai.service.AutoGenerationService
import com.mrj.fancyai.service.ImageService
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import java.io.File
import java.util.UUID

class SocialViewModel(
    private val platform: String,
    private val socialRepository: SocialRepository,
    private val autoGenService: AutoGenerationService,
    private val imageService: ImageService,
    private val mediaRepository: MediaRepository,
    private val settingsRepository: SettingsRepository
) : ViewModel() {

    val posts: Flow<List<SocialPostEntity>> = socialRepository.getPostsByPlatform(platform)

    var characters by mutableStateOf<List<CharacterEntity>>(emptyList())
        private set
    var isBusy by mutableStateOf(false)
        private set
    var statusText by mutableStateOf<String?>(null)
        private set

    init {
        viewModelScope.launch {
            socialRepository.getCharacters().collect { characters = it }
        }
    }

    fun comments(postId: String): Flow<List<SocialCommentEntity>> =
        socialRepository.getComments(postId)

    fun resolveImage(ref: String?): File? = ref?.let { mediaRepository.resolveToFile(it) }

    fun characterName(charId: String): String =
        characters.firstOrNull { it.id == charId }?.name ?: "Character"

    fun characterAvatar(charId: String): File? =
        characters.firstOrNull { it.id == charId }?.avatarRef?.let { mediaRepository.resolveToFile(it) }

    /** Composes a post for [charId]: AI-generated (structured, with a flux-prompt photo) or manual. */
    fun createPost(charId: String, useAi: Boolean, manualText: String, withImage: Boolean) {
        val char = characters.firstOrNull { it.id == charId } ?: return
        viewModelScope.launch {
            isBusy = true
            statusText = if (useAi) "Generating post…" else null
            try {
                // Build the post parts either from the model or from the manual text.
                val text: String
                val title: String?
                val subreddit: String?
                var imagePrompt: String?

                if (useAi) {
                    val subChoices = if (platform == "rebbit") settingsRepository.rebbitSubreddits.toList() else emptyList()
                    val content = autoGenService.generatePost(char.name, char.persona, platform, subChoices, char.bio)
                    text = content.text
                    title = content.title
                    subreddit = content.subreddit
                    imagePrompt = content.imagePrompt
                } else {
                    val clean = manualText.trim()
                    if (clean.isBlank()) return@launch
                    text = clean
                    title = if (platform == "rebbit") clean.take(100) else null
                    subreddit = if (platform == "rebbit") "r/${char.handle.ifBlank { char.name }}" else null
                    imagePrompt = if (withImage) clean else null
                }
                if (text.isBlank() && imagePrompt == null) return@launch

                var imageRef: String? = null
                if (imagePrompt != null) {
                    statusText = "Generating image…"
                    val params = mapOf(
                        "width" to settingsRepository.imgWidth,
                        "height" to settingsRepository.imgHeight,
                        "steps" to settingsRepository.imgSteps,
                        "cfg" to settingsRepository.imgCfg
                    )
                    imageService.generate(imagePrompt!!, params, null)
                        .onSuccess { imageRef = it.ref }
                }

                socialRepository.insertPost(
                    SocialPostEntity(
                        id = UUID.randomUUID().toString(),
                        charId = char.id,
                        platform = platform,
                        caption = if (platform == "ustagram") text else null,
                        imageRef = imageRef,
                        title = if (platform == "rebbit") title else null,
                        subreddit = if (platform == "rebbit") subreddit else null,
                        text = if (platform != "ustagram") text else null,
                        timestamp = System.currentTimeMillis()
                    )
                )
            } finally {
                isBusy = false
                statusText = null
            }
        }
    }

    /** Adds the user's own reply to a post. */
    fun addUserReply(postId: String, text: String) {
        if (text.isBlank()) return
        viewModelScope.launch {
            socialRepository.insertComment(
                SocialCommentEntity(
                    id = UUID.randomUUID().toString(),
                    postId = postId,
                    authorId = "user",
                    authorName = settingsRepository.userName,
                    text = text.trim()
                )
            )
        }
    }

    /** Has a (random) character reply in-character to a post. */
    fun requestCharacterReply(post: SocialPostEntity) {
        if (characters.isEmpty()) return
        val responder = characters.filter { it.id != post.charId }.randomOrNull()
            ?: characters.random()
        val postContent = post.caption ?: post.text ?: post.title ?: ""
        viewModelScope.launch {
            isBusy = true
            statusText = "${responder.name} is replying…"
            try {
                val reply = autoGenService.generateReply(responder.name, responder.persona, platform, postContent)
                socialRepository.insertComment(
                    SocialCommentEntity(
                        id = UUID.randomUUID().toString(),
                        postId = post.id,
                        authorId = responder.id,
                        authorName = responder.name,
                        text = reply
                    )
                )
            } finally {
                isBusy = false
                statusText = null
            }
        }
    }

    fun deletePost(postId: String) {
        viewModelScope.launch { socialRepository.deletePost(postId) }
    }

    fun deleteComment(id: String) {
        viewModelScope.launch { socialRepository.deleteComment(id) }
    }
}
