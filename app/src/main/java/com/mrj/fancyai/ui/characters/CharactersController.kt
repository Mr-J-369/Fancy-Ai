package com.mrj.fancyai.ui.characters

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.edit
import com.mrj.fancyai.R
import com.mrj.fancyai.service.llm.llmErrorResource
import com.mrj.fancyai.service.voice.VoiceLibrary
import com.mrj.fancyai.ui.kit.capitalizeFirstVisibleLetter
import com.mrj.fancyai.ui.lorebook.deleteLorebookForCharacter
import com.mrj.fancyai.ui.profile.UserProfile
import com.mrj.fancyai.ui.profile.userProfile
import com.mrj.fancyai.ui.settings.ProAccess
import com.mrj.fancyai.util.decodeImage
import com.mrj.fancyai.util.writeProperties
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import java.io.File
import java.util.Properties
import java.util.UUID

internal class CharactersController(private val context: Context) {
    var characters by mutableStateOf<List<CharacterCard>>(emptyList())
        private set
    var profile by mutableStateOf(UserProfile())
        private set
    var loading by mutableStateOf(true)
        private set
    var transferMessage by mutableIntStateOf(0)
    var transferFailed by mutableStateOf(false)
    var openCharacter by mutableStateOf<CharacterCard?>(null)
    var creating by mutableStateOf(false)
    var editingUserProfile by mutableStateOf(false)
    var editSource by mutableStateOf<CharacterCard?>(null)
    var pendingExport by mutableStateOf<CharacterCard?>(null)
    var revision by mutableIntStateOf(0)
    var search by mutableStateOf(
        context.getSharedPreferences(CHARACTER_SEARCH_PREFERENCES, Context.MODE_PRIVATE)
            .getString(KEY_CHARACTER_SEARCH, "").orEmpty(),
    )

    suspend fun load() {
        loading = true
        try {
            val loaded = withContext(Dispatchers.IO) { availableCharacters(context) to userProfile(context) }
            characters = loaded.first
            profile = loaded.second
        } finally {
            loading = false
        }
    }

    suspend fun importCharacter(uri: Uri): CharacterCard? {
        transferMessage = R.string.character_importing
        transferFailed = false
        return try {
            withContext(Dispatchers.IO) { importCharacterCard(context, uri) }.also {
                transferMessage = R.string.character_imported
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            transferFailed = true
            transferMessage = llmErrorResource(failure, R.string.character_import_failed)
            null
        }
    }

    fun leaveWorkspace() {
        creating = false
        editingUserProfile = false
        editSource = null
        openCharacter = null
    }

    suspend fun exportCharacter(character: CharacterCard, uri: Uri, png: Boolean) {
        withContext(Dispatchers.IO) {
            if (png) exportCharacterPng(context, character, uri)
            else exportCharacterJson(context, character, uri)
        }
        transferFailed = false
        transferMessage = R.string.character_exported
    }

    suspend fun delete(character: CharacterCard) {
        loading = true
        withContext(Dispatchers.IO) {
            VoiceLibrary.assign(context, character.id, "")
            deleteLorebookForCharacter(context, character.id)
            val chats = File(context.filesDir, "chats/${character.id}")
            val chatFiles = chats.listFiles { file -> file.isFile && (file.extension == "json") }.orEmpty()
            val folders = listOf(
                File(context.filesDir, "chat_memory/${character.id}"),
                File(File(context.filesDir, "characters"), character.id),
                File(context.filesDir, "media/characters/${character.id}"),
                File(context.filesDir, "character_draft/${character.id}"),
                chats,
            )
            folders.forEach { it.deleteRecursively() }
            context.getSharedPreferences("chat_memory", Context.MODE_PRIVATE).edit(commit = true) {
                remove("${character.id}.enabled")
                remove("${character.id}.autoCollect")
            }
            val memoryDrafts = context.getSharedPreferences("chat_memory_drafts", Context.MODE_PRIVATE)
            memoryDrafts.edit(commit = true) {
                memoryDrafts.all.keys.filter { it.startsWith("${character.id}:") }.forEach(::remove)
            }
            context.getSharedPreferences("$DRAFT_PREFERENCES.${character.id}", Context.MODE_PRIVATE)
                .edit(commit = true) { clear() }
            val drafts = context.getSharedPreferences("chat_draft", Context.MODE_PRIVATE)
            drafts.edit(commit = true) {
                for (key in drafts.all.keys) {
                    if (key.startsWith("${character.id}:")) remove(key)
                }
            }
            context.getSharedPreferences("chat_rename_draft", Context.MODE_PRIVATE).edit {
                for (file in chatFiles) remove(file.nameWithoutExtension)
            }
        }
    }
}

private val draftJsonFormat = Json { ignoreUnknownKeys = true }

internal fun readDraft(context: Context, editorKey: String, character: CharacterCard?): CharacterDraft {
    return context.getSharedPreferences("$DRAFT_PREFERENCES.$editorKey", Context.MODE_PRIVATE).getString("draft_json", null)
        ?.let { runCatching { draftJsonFormat.decodeFromString<CharacterDraft>(it) }.getOrNull() }
        ?: character?.toDraft() ?: CharacterDraft()
}

internal fun saveCharacterDraft(context: Context, editorKey: String, draft: CharacterDraft) {
    context.getSharedPreferences("$DRAFT_PREFERENCES.$editorKey", Context.MODE_PRIVATE).edit {
        putString("draft_json", draftJsonFormat.encodeToString(draft))
    }
}

internal fun hasCharacterDraft(context: Context, editorKey: String): Boolean =
    context.getSharedPreferences("$DRAFT_PREFERENCES.$editorKey", Context.MODE_PRIVATE).contains("draft_json")

internal fun copyResizedDraftImage(
    context: Context,
    editorKey: String,
    uri: Uri,
    name: String,
    maxEdge: Int,
) {
    val target = draftImage(context, editorKey, name)
    val partial = File(target.parentFile, ".${target.name}.part")
    try {
        copyResizedImage(context, uri, partial, maxEdge)
        partial.renameTo(target)
        context.getSharedPreferences("$DRAFT_PREFERENCES.$editorKey", Context.MODE_PRIVATE).edit { putBoolean("remove.$name", false) }
    } finally { partial.delete() }
}

internal fun copyResizedImage(
    context: Context,
    uri: Uri,
    target: File,
    maxEdge: Int?,
) {
    val bitmap = decodeImage(context, uri, maxEdge)
    try {
        target.parentFile?.mkdirs()
        target.outputStream().use { bitmap.compress(Bitmap.CompressFormat.WEBP_LOSSY, IMAGE_QUALITY, it) }
    } finally {
        bitmap.recycle()
    }
}

internal const val IMAGE_QUALITY = 90

internal fun draftImage(context: Context, editorKey: String, name: String): File =
    File(context.filesDir, "character_draft/$editorKey/$name")

internal const val DRAFT_PREFERENCES = "character_creator_draft"

internal fun draftCharacterId(context: Context, editorKey: String): String {
    val preferences = context.getSharedPreferences("$DRAFT_PREFERENCES.$editorKey", Context.MODE_PRIVATE)
    val saved = preferences.getString("character_id", null)
    val existing = saved?.let { runCatching { UUID.fromString(it).toString() }.getOrNull() }
    if (existing != null) return existing
    val id = UUID.randomUUID().toString()
    preferences.edit(commit = true) { putString("character_id", id) }
    return id
}

internal fun rootCharacter(context: Context): CharacterCard {
    val saved = readCharacter(File(File(context.filesDir, "characters"), ROOT_CHARACTER_ID))
    val defaultDesc = context.getString(R.string.character_root_description)
    if (saved != null) {
        if (saved.description.contains("<sudo>")) {
            val updated = saved.copy(description = defaultDesc)
            val file = File(File(context.filesDir, "characters"), ROOT_CHARACTER_ID)
            runCatching { writeProperties(File(file, CHARACTER_FILE), updated.toDraft().toProperties()) }
            return updated
        }
        return saved
    }
    return CharacterCard(
        id = ROOT_CHARACTER_ID,
        name = context.getString(R.string.character_root_name),
        handle = context.getString(R.string.character_root_handle),
        personality = context.getString(R.string.character_root_personality),
        description = defaultDesc,
        scene = "",
        firstMessage = context.getString(R.string.chat_empty_message),
        appearance = context.getString(R.string.character_root_appearance),
        avatarResource = R.drawable.root_avatar,
        backgroundResource = R.drawable.root_chat_background,
    )
}

internal fun resetRootCharacter(context: Context): CharacterCard = synchronized(ProAccess.charactersLock) {
    VoiceLibrary.assign(context, ROOT_CHARACTER_ID, "")
    File(File(context.filesDir, "characters"), ROOT_CHARACTER_ID).deleteRecursively()
    File(context.filesDir, "character_draft/$ROOT_CHARACTER_ID").deleteRecursively()
    context.getSharedPreferences("$DRAFT_PREFERENCES.$ROOT_CHARACTER_ID", Context.MODE_PRIVATE)
        .edit(commit = true) { clear() }
    rootCharacter(context)
}

internal fun availableCharacters(context: Context): List<CharacterCard> {
    val folders = File(context.filesDir, "characters").listFiles().orEmpty()
    return listOf(rootCharacter(context)) + folders
        .asSequence()
        .filter { it.isDirectory && (it.name != ROOT_CHARACTER_ID) }
        .mapNotNull(::readCharacter)
        .sortedBy(CharacterCard::name)
        .toList()
}


internal fun saveCharacterSocialEnabled(
    context: Context,
    characterId: String,
    app: CharacterSocialApp,
    enabled: Boolean,
) {
    val file = File(File(File(context.filesDir, "characters"), characterId), CHARACTER_FILE)
    val values = if (!file.exists() && characterId == ROOT_CHARACTER_ID) {
        rootCharacter(context).toDraft().toProperties()
    } else {
        Properties().apply { file.inputStream().use(::load) }
    }
    values.setProperty(app.property, enabled.toString())
    writeProperties(file, values)
}

internal fun readCharacter(folder: File): CharacterCard? = runCatching {
    val values = Properties().apply {
        File(folder, CHARACTER_FILE).inputStream().use(::load)
    }
    CharacterCard(
        id = folder.name,
        name = values.getProperty("name").orEmpty(),
        handle = values.getProperty("handle").orEmpty(),
        personality = values.getProperty("personality").orEmpty(),
        description = values.getProperty("description").orEmpty(),
        scene = values.getProperty("scene").orEmpty(),
        firstMessage = values.getProperty("firstMessage").orEmpty(),
        appearance = values.getProperty("appearance").orEmpty(),
        rebbitEnabled = values.getProperty("rebbitEnabled")?.toBooleanStrictOrNull() ?: true,
        ustagramEnabled = values.getProperty("ustagramEnabled")?.toBooleanStrictOrNull() ?: true,
        yEnabled = values.getProperty("yEnabled")?.toBooleanStrictOrNull() ?: true,
        dareEnabled = values.getProperty("dareEnabled")?.toBooleanStrictOrNull() ?: true,
        avatarResource = if (folder.name == ROOT_CHARACTER_ID) R.drawable.root_avatar else 0,
        avatarPath = File(folder, AVATAR_FILE).takeIf(File::isFile)?.absolutePath,
        backgroundResource = if (folder.name == ROOT_CHARACTER_ID) R.drawable.root_chat_background else 0,
        backgroundPath = File(folder, BACKGROUND_FILE).takeIf(File::isFile)?.absolutePath,
    ).takeIf { it.name.isNotBlank() }
}.getOrNull()

internal fun saveCharacter(
    context: Context,
    editorKey: String,
    source: CharacterDraft,
    character: CharacterCard?,
): CharacterCard = synchronized(ProAccess.charactersLock) {
    val current = character?.let { readCharacter(File(File(context.filesDir, "characters"), it.id)) }
    val draft = source.normalized().let {
        if (current == null) it else it.copy(
            rebbitEnabled = current.rebbitEnabled,
            ustagramEnabled = current.ustagramEnabled,
            yEnabled = current.yEnabled,
            dareEnabled = current.dareEnabled,
        )
    }
    val folder = File(File(context.filesDir, "characters"), character?.id ?: draftCharacterId(context, editorKey)).apply { mkdirs() }
    writeProperties(File(folder, CHARACTER_FILE), draft.toProperties())
    listOf(AVATAR_FILE, BACKGROUND_FILE).forEach { name ->
        val savedImage = File(folder, name)
        if (context.getSharedPreferences("$DRAFT_PREFERENCES.$editorKey", Context.MODE_PRIVATE).getBoolean("remove.$name", false)) {
            savedImage.delete()
        } else {
            draftImage(context, editorKey, name).takeIf(File::isFile)?.copyTo(savedImage, overwrite = true)
        }
    }
    File(context.filesDir, "character_draft/$editorKey").deleteRecursively()
    context.getSharedPreferences("$DRAFT_PREFERENCES.$editorKey", Context.MODE_PRIVATE).edit { clear() }
    draft.toCard(folder.name).copy(
        avatarResource = if (folder.name == ROOT_CHARACTER_ID) R.drawable.root_avatar else 0,
        avatarPath = File(folder, AVATAR_FILE).takeIf(File::isFile)?.absolutePath,
        backgroundResource = if (folder.name == ROOT_CHARACTER_ID) R.drawable.root_chat_background else 0,
        backgroundPath = File(folder, BACKGROUND_FILE).takeIf(File::isFile)?.absolutePath,
    )
}

internal fun saveGeneratedCharacter(
    context: Context,
    source: CharacterDraft,
    preparedAvatar: File?,
    characterId: String,
): CharacterCard = synchronized(ProAccess.charactersLock) {
    val draft = source.normalized()
    val folder = File(File(context.filesDir, "characters"), characterId).apply(File::mkdirs)
    writeProperties(File(folder, CHARACTER_FILE), draft.toProperties())
    preparedAvatar?.let { image ->
        copyResizedImage(context, Uri.fromFile(image), File(folder, AVATAR_FILE), maxEdge = null)
        val album = File(context.filesDir, "media/characters/$characterId").apply(File::mkdirs)
        image.copyTo(File(album, image.name), overwrite = true)
        val sidecar = File(image.parentFile, "${image.nameWithoutExtension}.json")
        sidecar.copyTo(File(album, sidecar.name), overwrite = true)
    }
    draft.toCard(folder.name).copy(
        avatarResource = if (folder.name == ROOT_CHARACTER_ID) R.drawable.root_avatar else 0,
        avatarPath = File(folder, AVATAR_FILE).takeIf(File::isFile)?.absolutePath,
        backgroundResource = if (folder.name == ROOT_CHARACTER_ID) R.drawable.root_chat_background else 0,
        backgroundPath = File(folder, BACKGROUND_FILE).takeIf(File::isFile)?.absolutePath,
    )
}

internal fun CharacterDraft.toProperties() = Properties().apply {
    setProperty("name", name)
    setProperty("handle", handle)
    setProperty("personality", personality)
    setProperty("description", description)
    setProperty("scene", scene)
    setProperty("firstMessage", firstMessage)
    setProperty("appearance", appearance)
    setProperty("rebbitEnabled", rebbitEnabled.toString())
    setProperty("ustagramEnabled", ustagramEnabled.toString())
    setProperty("yEnabled", yEnabled.toString())
    setProperty("dareEnabled", dareEnabled.toString())
}

internal fun CharacterCard.toDraft() = CharacterDraft(
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

private fun CharacterDraft.normalized() = copy(
    name = name.trim().capitalizeFirstVisibleLetter(), handle = handle.trim(), personality = personality.trim().capitalizeFirstVisibleLetter(),
    description = description.trim().capitalizeFirstVisibleLetter(), scene = scene.trim().capitalizeFirstVisibleLetter(),
    firstMessage = firstMessage.trim().capitalizeFirstVisibleLetter(), appearance = appearance.trim().capitalizeFirstVisibleLetter(),
)

internal const val CHARACTER_SEARCH_PREFERENCES = "character_search_draft"
internal const val KEY_CHARACTER_SEARCH = "search"
internal const val ROOT_CHARACTER_ID = "root"
internal const val CHARACTER_FILE = "character.properties"
internal const val AVATAR_FILE = "avatar.webp"
internal const val BACKGROUND_FILE = "background.webp"
internal const val AVATAR_MAX_EDGE = 1024
