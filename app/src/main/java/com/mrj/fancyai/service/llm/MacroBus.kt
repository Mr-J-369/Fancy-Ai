package com.mrj.fancyai.service.llm

import android.content.Context
import androidx.annotation.StringRes
import com.mrj.fancyai.ui.characters.CharacterCard
import com.mrj.fancyai.ui.characters.rootCharacter
import com.mrj.fancyai.ui.profile.UserProfile
import com.mrj.fancyai.ui.profile.userProfile

/** Expands shared placeholders. Callers own instructions, context and engine requests. */
internal class MacroBus(
    val context: Context,
    private val character: CharacterCard? = rootCharacter(context),
    private val profile: UserProfile? = userProfile(context),
    private val userName: String = profile?.name.orEmpty(),
    private val subreddit: String = "",
    private val hashtag: String = "",
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

    companion object {
        private val FIELD = Regex("\\{\\{([^{}]+)\\}\\}")
    }
}

internal class MacroException(@param:StringRes val resource: Int, detail: String) :
    IllegalArgumentException(detail)
