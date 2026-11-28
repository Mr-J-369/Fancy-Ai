package com.mrj.fancyai.service.llm

import android.content.Context
import androidx.annotation.StringRes
import com.mrj.fancyai.ui.characters.CHARACTER_FILE
import com.mrj.fancyai.ui.characters.CharacterCard
import com.mrj.fancyai.ui.characters.readCharacter
import com.mrj.fancyai.ui.characters.rootCharacter
import com.mrj.fancyai.ui.profile.UserProfile
import com.mrj.fancyai.ui.profile.userProfile
import java.io.File

/** Expands shared placeholders. Callers own instructions, context and engine requests. */
internal class MacroBus(
    val context: Context,
    private val character: CharacterCard? = rootCharacter(context),
    private val profile: UserProfile? = userProfile(context),
    private val userName: String = profile?.name.orEmpty(),
    private val subreddit: String = "",
    private val hashtag: String = "",
    private val knownCharacters: List<CharacterCard>? = null,
) {

    /** Displayed speech and supplied context may contain literal, unfamiliar template syntax. */
    fun text(template: String): String {
        fun expand(source: String, path: Set<String>): String = FIELD.replace(source) { match ->
            val key = match.groupValues[1].trim().lowercase(java.util.Locale.ROOT)
            if (key in path) return@replace match.value
            val value = when (key) {
                "char" -> character?.name
                "char.handle" -> character?.handle
                "char.personality" -> character?.personality
                "char.description" -> character?.description
                "char.appearance" -> character?.appearance
                "char.scene" -> character?.scene
                "char.firstmessage" -> character?.firstMessage
                "char.relationships" -> relationshipsBlock()
                "user", "user.name" -> userName
                "user.handle" -> profile?.handle.orEmpty()
                "user.description" -> profile?.description.orEmpty()
                "user.appearance" -> profile?.appearance.orEmpty()
                "r/", "r", "subreddit" -> subreddit.ifBlank { "a matching r/" }
                "#" -> hashtag.ifBlank { "a matching #" }
                else -> return@replace match.value
            }
            expand(value ?: return@replace match.value, path + key)
        }
        return expand(template, emptySet())
    }

    internal fun relationshipsBlock(): String {
        val card = character ?: return ""
        val userLabel = userName.ifBlank { "User" }
        val knownById = knownCharacters?.associateBy(CharacterCard::id)
        val charactersDir = File(context.filesDir, "characters")
        val lines = buildList {
            card.userToCharacter.trim().takeIf(String::isNotEmpty)?.let {
                add("- $userLabel to ${card.name}: ${text(it)}")
            }
            card.characterToUser.trim().takeIf(String::isNotEmpty)?.let {
                add("- ${card.name} to $userLabel: ${text(it)}")
            }
            card.characterRelationships.forEach { rel ->
                val relationText = rel.relation.trim()
                if (relationText.isEmpty()) return@forEach
                val targetName = rel.targetCharacterId.takeIf(String::isNotBlank)?.let { targetId ->
                    knownById?.get(targetId)?.name
                        ?: File(charactersDir, targetId).takeIf { File(it, CHARACTER_FILE).isFile }?.let(::readCharacter)?.name
                }?.ifBlank { rel.targetCharacterName.trim() } ?: rel.targetCharacterName.trim()
                if (targetName.isNotEmpty()) {
                    add("- ${card.name} to $targetName: ${text(relationText)}")
                }
            }
        }
        if (lines.isEmpty()) return ""
        return "Relationships (strictly follow who is who; never confuse $userLabel with another character or swap roles):\n" +
            lines.joinToString("\n")
    }

    companion object {
        private val FIELD = Regex("\\{\\{([^{}]+)\\}\\}")
    }
}

internal class MacroException(@param:StringRes val resource: Int, detail: String) :
    IllegalArgumentException(detail)
