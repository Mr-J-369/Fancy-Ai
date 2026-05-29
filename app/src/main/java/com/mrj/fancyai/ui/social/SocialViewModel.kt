package com.mrj.fancyai.ui.social

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.mrj.fancyai.data.db.entity.SocialPostEntity
import com.mrj.fancyai.data.repository.ChatRepository
import com.mrj.fancyai.data.repository.SocialRepository
import com.mrj.fancyai.domain.inference.LlamaEngine
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import java.util.UUID

class SocialViewModel(
    private val platform: String,
    private val socialRepository: SocialRepository,
    private val chatRepository: ChatRepository,
    private val llamaEngine: LlamaEngine
) : ViewModel() {

    val posts: Flow<List<SocialPostEntity>> = socialRepository.getPostsByPlatform(platform)

    var generatingPostFor: String? by mutableStateOf(null)
        private set

    var streamingPostText by mutableStateOf("")
        private set

    fun createPost(
        charId: String,
        caption: String? = null,
        imageRef: String? = null
    ) {
        viewModelScope.launch {
            val postId = UUID.randomUUID().toString()

            val post = when (platform) {
                "ustagram" -> SocialPostEntity(
                    id = postId,
                    charId = charId,
                    platform = "ustagram",
                    caption = caption,
                    imageRef = imageRef,
                    timestamp = System.currentTimeMillis()
                )
                "rebbit" -> SocialPostEntity(
                    id = postId,
                    charId = charId,
                    platform = "rebbit",
                    title = caption?.take(100),
                    text = caption,
                    timestamp = System.currentTimeMillis()
                )
                "y" -> SocialPostEntity(
                    id = postId,
                    charId = charId,
                    platform = "y",
                    text = caption,
                    timestamp = System.currentTimeMillis()
                )
                else -> return@launch
            }

            socialRepository.insertPost(post)
        }
    }

    fun deletePost(postId: String) {
        viewModelScope.launch {
            socialRepository.deletePost(postId)
        }
    }

    fun generatePost(charId: String) {
        viewModelScope.launch {
            generatingPostFor = charId
            streamingPostText = ""

            try {
                val cbId = llamaEngine.getNextCbId()
                var accumulated = ""

                llamaEngine.tokenFlow.collect { (id, token) ->
                    if (id == cbId) {
                        accumulated += token
                        streamingPostText = accumulated
                    }
                }
            } finally {
                generatingPostFor = null
            }
        }
    }
}
