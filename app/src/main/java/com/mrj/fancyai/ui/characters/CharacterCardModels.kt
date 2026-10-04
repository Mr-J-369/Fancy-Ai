package com.mrj.fancyai.ui.characters

import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import com.mrj.fancyai.R
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

internal data class CharacterCard(
    val id: String,
    val name: String,
    val handle: String,
    val personality: String,
    val description: String,
    val scene: String,
    val firstMessage: String,
    val appearance: String,
    val rebbitEnabled: Boolean = true,
    val ustagramEnabled: Boolean = true,
    val yEnabled: Boolean = true,
    val dareEnabled: Boolean = true,
    @param:DrawableRes val avatarResource: Int = 0,
    val avatarPath: String? = null,
    @param:DrawableRes val backgroundResource: Int = 0,
    val backgroundPath: String? = null,
)

internal enum class CharacterSocialApp(
    @param:StringRes val title: Int,
    @param:StringRes val summary: Int,
    val property: String,
) {
    REBBIT(
        R.string.home_app_rebbit,
        R.string.character_rebbit_summary,
        "rebbitEnabled",
    ),
    USTAGRAM(
        R.string.home_app_ustagram,
        R.string.character_ustagram_summary,
        "ustagramEnabled",
    ),
    Y(
        R.string.home_app_y,
        R.string.character_y_summary,
        "yEnabled",
    ),
    DARE(
        R.string.home_app_dare,
        R.string.character_dare_summary,
        "dareEnabled",
    ),
}

@Serializable
internal data class CharacterDraft(
    val name: String = "",
    val handle: String = "",
    val personality: String = "",
    val description: String = "",
    val scene: String = "",
    @SerialName("first_message") val firstMessage: String = "",
    val appearance: String = "",
    val rebbitEnabled: Boolean = true,
    val ustagramEnabled: Boolean = true,
    val yEnabled: Boolean = true,
    val dareEnabled: Boolean = true,
)

internal fun CharacterDraft.toCard(id: String) = CharacterCard(
    id = id,
    name = name,
    handle = handle,
    personality = personality,
    description = description,
    scene = scene,
    firstMessage = firstMessage,
    appearance = appearance,
    rebbitEnabled = rebbitEnabled,
    ustagramEnabled = ustagramEnabled,
    yEnabled = yEnabled,
    dareEnabled = dareEnabled,
)

@Serializable
internal data class FancyCharacterExtension(
    val handle: String = "",
    val appearance: String = "",
    val rebbitEnabled: Boolean = true,
    val ustagramEnabled: Boolean = true,
    val yEnabled: Boolean = true,
    val dareEnabled: Boolean = true,
)

@Serializable
internal data class CharacterCardData(
    val name: String = "",
    val description: String = "",
    val personality: String = "",
    val scenario: String = "",
    @SerialName("first_mes") val firstMessage: String = "",
    @SerialName("mes_example") val exampleMessages: String = "",
    @SerialName("creator_notes") val creatorNotes: String = "",
    @SerialName("system_prompt") val systemPrompt: String = "",
    @SerialName("post_history_instructions") val postHistoryInstructions: String = "",
    @SerialName("alternate_greetings") val alternateGreetings: List<String> = emptyList(),
    val tags: List<String> = emptyList(),
    val creator: String = "Fancy AI",
    @SerialName("character_version") val characterVersion: String = "1",
    val extensions: Map<String, FancyCharacterExtension> = emptyMap(),
)

@Serializable
internal data class CharacterCardSpec(
    val spec: String = "chara_card_v2",
    @SerialName("spec_version") val specVersion: String = "2.0",
    val data: CharacterCardData = CharacterCardData(),
)

internal const val FANCY_EXTENSION = "fancy_ai"
