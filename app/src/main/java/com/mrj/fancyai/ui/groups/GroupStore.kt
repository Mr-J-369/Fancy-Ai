package com.mrj.fancyai.ui.groups

import android.content.Context
import androidx.core.content.edit
import com.mrj.fancyai.ui.kit.capitalizeFirstVisibleLetter
import com.mrj.fancyai.util.writeProperties
import java.io.File
import java.util.Properties
import java.util.UUID

internal fun readGroups(context: Context): List<GroupInfo> {
    val folders = File(context.filesDir, GROUPS_DIRECTORY).listFiles().orEmpty()
    return folders.asSequence()
        .filter(File::isDirectory).map(::readGroup)
        .sortedByDescending(GroupInfo::createdAt).toList()
}

private fun readGroup(folder: File): GroupInfo {
    val values = Properties().apply { File(folder, GROUP_FILE).inputStream().use(::load) }
    return GroupInfo(
        id = folder.name,
        name = values.getProperty("name").orEmpty(),
        scenario = values.getProperty("scenario").orEmpty(),
        memberIds = values.getProperty("memberIds").orEmpty().lineSequence().filter(String::isNotBlank).toList(),
        createdAt = values.getProperty("createdAt").toLongOrNull() ?: folder.lastModified(),
    )
}

internal fun writeGroup(context: Context, draft: GroupCreateDraft): GroupInfo {
    val group = GroupInfo(
        id = UUID.randomUUID().toString(),
        name = draft.name,
        scenario = draft.scenario,
        memberIds = draft.memberIds.toList(),
        createdAt = System.currentTimeMillis(),
    )
    val folder = File(File(context.filesDir, GROUPS_DIRECTORY), group.id)
    folder.mkdirs()
    return try {
        val values = Properties().apply {
            setProperty("name", group.name)
            setProperty("scenario", group.scenario)
            setProperty("memberIds", group.memberIds.joinToString("\n"))
            setProperty("createdAt", group.createdAt.toString())
        }
        writeProperties(File(folder, GROUP_FILE), values)
        group
    } catch (failure: Throwable) {
        folder.deleteRecursively()
        throw failure
    }
}

internal fun readGroupMessages(context: Context, groupId: String): List<GroupMessage> {
    val messagesFolder = File(File(File(context.filesDir, GROUPS_DIRECTORY), groupId), MESSAGES_DIRECTORY)
    val files = messagesFolder.listFiles().orEmpty()
    return files.asSequence().filter { it.isFile && it.extension == "properties" }.map { file ->
        val values = Properties().apply { file.inputStream().use(::load) }
        val id = values.getProperty("id").orEmpty()
        val imagePath = values.getProperty("imagePath").orEmpty()
        val image = if (imagePath.isNotEmpty()) {
            File(context.filesDir, imagePath)
        } else {
            File(file.parentFile, "${file.nameWithoutExtension}.jpg")
        }
        GroupMessage(
            id = id,
            authorId = values.getProperty("authorId").orEmpty(),
            authorName = values.getProperty("authorName").orEmpty(),
            authorHandle = values.getProperty("authorHandle").orEmpty(),
            text = values.getProperty("text").orEmpty(),
            thoughtProcess = values.getProperty("thoughtProcess").orEmpty(),
            createdAt = values.getProperty("createdAt").toLongOrNull() ?: file.lastModified(),
            imagePath = imagePath,
            image = image,
            imagePrompt = values.getProperty("imagePrompt").orEmpty(),
        )
    }.sortedByDescending(GroupMessage::createdAt).toList()
}

internal fun writeGroupMessage(context: Context, groupId: String, source: GroupMessage): GroupMessage {
    val folder = File(File(File(context.filesDir, GROUPS_DIRECTORY), groupId), MESSAGES_DIRECTORY).apply(File::mkdirs)
    val message = source.copy(image = if (source.imagePath.isNotEmpty()) source.image else File(folder, "${source.id}.jpg"))
    val values = Properties().apply {
        setProperty("id", message.id)
        setProperty("authorId", message.authorId)
        setProperty("authorName", message.authorName)
        setProperty("authorHandle", message.authorHandle)
        setProperty("text", message.text)
        setProperty("thoughtProcess", message.thoughtProcess)
        setProperty("createdAt", message.createdAt.toString())
        setProperty("imagePath", message.imagePath)
        setProperty("imagePrompt", message.imagePrompt)
    }
    writeProperties(File(folder, "${message.id}.properties"), values)
    return message
}

private const val GROUPS_DIRECTORY = "groups"
private const val GROUP_FILE = "group.properties"
private const val MESSAGES_DIRECTORY = "messages"

internal fun readGroupCreateDraft(context: Context): GroupCreateDraft {
    val preferences = context.getSharedPreferences(CREATE_PREFERENCES, Context.MODE_PRIVATE)
    return GroupCreateDraft(
        name = preferences.getString(KEY_CREATE_NAME, "").orEmpty(),
        scenario = preferences.getString(KEY_CREATE_SCENARIO, "").orEmpty(),
        memberIds = preferences.getString(KEY_CREATE_MEMBERS, "").orEmpty()
            .lineSequence().filter(String::isNotBlank).toSet(),
    )
}

internal fun saveGroupCreateDraft(context: Context, draft: GroupCreateDraft) {
    context.getSharedPreferences(CREATE_PREFERENCES, Context.MODE_PRIVATE).edit {
        putString(KEY_CREATE_NAME, draft.name)
        putString(KEY_CREATE_SCENARIO, draft.scenario)
        putString(KEY_CREATE_MEMBERS, draft.memberIds.joinToString("\n"))
    }
}

internal fun GroupCreateDraft.normalized() = copy(
    name = name.trim().replace(Regex("\\s+"), " ").take(NAME_LIMIT).capitalizeFirstVisibleLetter(),
    scenario = scenario.trim().take(SCENARIO_LIMIT).capitalizeFirstVisibleLetter(),
)

internal const val CREATE_PREFERENCES = "group_create_draft"
internal const val DRAFT_PREFERENCES = "group_message_drafts"
private const val KEY_CREATE_NAME = "name"
private const val KEY_CREATE_SCENARIO = "scenario"
private const val KEY_CREATE_MEMBERS = "members"
