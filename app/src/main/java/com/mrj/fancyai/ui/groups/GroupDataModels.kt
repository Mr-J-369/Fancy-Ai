package com.mrj.fancyai.ui.groups

import java.io.File

internal sealed interface GroupScreen {
    data object List : GroupScreen
    data object Create : GroupScreen
    data class Room(val group: GroupInfo) : GroupScreen
}

internal data class GroupInfo(
    val id: String,
    val name: String,
    val scenario: String,
    val memberIds: List<String>,
    val createdAt: Long,
)

internal data class GroupCreateDraft(
    val name: String = "",
    val scenario: String = "",
    val memberIds: Set<String> = emptySet(),
)

internal data class GroupMessage(
    val id: String,
    val authorId: String,
    val authorName: String,
    val authorHandle: String,
    val text: String,
    val createdAt: Long,
    val imagePath: String,
    val image: File,
    val thoughtProcess: String = "",
    val imagePrompt: String = "",
)

internal const val USER_ID = "user"
internal const val STREAMING_ID_PREFIX = "streaming:"
internal const val NAME_LIMIT = 80
internal const val SCENARIO_LIMIT = 1200
internal const val MESSAGE_LIMIT = 4000
internal const val MAXIMUM_RESPONDERS = 3
internal const val HANDLE_ID_LENGTH = 6
