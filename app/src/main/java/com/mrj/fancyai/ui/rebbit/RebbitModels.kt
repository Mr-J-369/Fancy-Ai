package com.mrj.fancyai.ui.rebbit

import androidx.compose.ui.unit.dp
import com.mrj.fancyai.ui.characters.CharacterCard
import java.io.File
import java.util.Locale

internal data class RebbitPost(
    val id: String,
    val characterId: String,
    val characterName: String,
    val authorHandle: String,
    val caption: String,
    val community: String,
    val reported: Boolean,
    val createdAt: Long,
    val imagePath: String,
    val image: File,
    val thoughtProcess: String = "",
    val imagePrompt: String = "",
)

internal data class RebbitDraft(
    val character: CharacterCard,
    val caption: String,
    val community: String,
    val imagePrompt: String? = null,
    val thoughtProcess: String = "",
    val id: String = java.util.UUID.randomUUID().toString(),
)

internal sealed interface RebbitGeneration {
    data object Idle : RebbitGeneration
    data class Writing(val character: CharacterCard, val community: String? = null) : RebbitGeneration
    data class Rendering(val character: CharacterCard, val draft: RebbitDraft?, val progress: Int) : RebbitGeneration
    val busy: Boolean get() = this is Writing || this is Rendering
}

internal val FEED_WIDTH = 680.dp
internal const val REBBIT_USER_ID = "user"

internal fun normalizedCommunity(raw: String): String? {
    val clean = raw.trim().removePrefix("c/").removePrefix("C/").removePrefix("r/").removePrefix("R/").lowercase(Locale.ROOT)
        .filter { it in 'a'..'z' || it in '0'..'9' || it == '_' }
    return clean.takeIf(String::isNotBlank)?.let { "r/$it" }
}
