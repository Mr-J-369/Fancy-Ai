package com.mrj.fancyai.ui.dare

import androidx.compose.ui.unit.dp
import com.mrj.fancyai.ui.characters.CharacterCard
import java.io.File
import java.util.UUID

internal data class DarePost(
    val id: String,
    val characterId: String,
    val characterName: String,
    val authorHandle: String,
    val dareTitle: String,
    val caption: String,
    val createdAt: Long,
    val imagePath: String,
    val image: File,
    val thoughtProcess: String = "",
    val imagePrompt: String = "",
)

internal data class DareDraft(
    val character: CharacterCard,
    val dareTitle: String,
    val caption: String,
    val imagePrompt: String? = null,
    val thoughtProcess: String = "",
    val id: String = UUID.randomUUID().toString(),
)

internal sealed interface DareGeneration {
    data object Idle : DareGeneration
    data class Writing(val character: CharacterCard, val dareTitle: String? = null) : DareGeneration
    data class Rendering(val character: CharacterCard, val draft: DareDraft?, val progress: Int) : DareGeneration
    val busy: Boolean get() = (this is Writing) || (this is Rendering)
}

internal val DARE_FEED_WIDTH = 680.dp
