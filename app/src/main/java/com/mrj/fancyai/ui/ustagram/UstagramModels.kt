package com.mrj.fancyai.ui.ustagram

import com.mrj.fancyai.ui.characters.CharacterCard
import java.io.File
import java.util.Locale

internal data class UstagramPost(
    val id: String,
    val characterId: String,
    val characterName: String,
    val authorHandle: String,
    val theme: String,
    val caption: String,
    val reported: Boolean,
    val createdAt: Long,
    val photoPath: String,
    val photo: File,
    val thoughtProcess: String = "",
    val imagePrompt: String = "",
)

internal data class UstagramDraft(
    val character: CharacterCard,
    val theme: String,
    val caption: String,
    val imagePrompt: String? = null,
    val thoughtProcess: String = "",
    val id: String = java.util.UUID.randomUUID().toString(),
)

internal sealed interface UstagramGeneration {
    data object Idle : UstagramGeneration
    data class Writing(val character: CharacterCard) : UstagramGeneration
    data class Rendering(val character: CharacterCard, val draft: UstagramDraft? = null, val progress: Int = 0) : UstagramGeneration
    val busy: Boolean get() = this is Writing || this is Rendering
}

internal const val THEME_LIMIT = 50
internal const val COMMENT_LIMIT = 500
internal const val FALLBACK_RESPONDERS = 2
internal const val REPLY_LIMIT = 4

internal fun normalizeUstagramTheme(raw: String): String? {
    val clean = raw.trim().removePrefix("#").lowercase(Locale.ROOT)
        .filter { it in 'a'..'z' || it in '0'..'9' || it == '_' }
    return clean.takeIf(String::isNotBlank)?.let { "#$it" }
}
