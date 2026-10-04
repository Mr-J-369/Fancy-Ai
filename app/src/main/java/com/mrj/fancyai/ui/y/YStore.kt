package com.mrj.fancyai.ui.y

import android.content.Context
import com.mrj.fancyai.service.llm.generatePromptImage
import com.mrj.fancyai.service.memory.CharacterMemory
import com.mrj.fancyai.ui.aura.deleteAuraResult
import com.mrj.fancyai.ui.characters.CharacterCard
import com.mrj.fancyai.util.writeProperties
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Properties
import java.util.UUID

internal data class YPost(
    val id: String,
    val characterId: String,
    val characterName: String,
    val authorHandle: String,
    val text: String,
    val reported: Boolean,
    val imagePath: String = "",
    val createdAt: Long,
    val thoughtProcess: String = "",
    val imagePrompt: String = "",
)

internal data class YDraft(
    val character: CharacterCard,
    val text: String,
    val imagePath: String = "",
    val thoughtProcess: String = "",
    val imagePrompt: String = "",
    val id: String = UUID.randomUUID().toString(),
)

internal sealed interface YGeneration {
    data object Idle : YGeneration
    data class Writing(val character: CharacterCard) : YGeneration

    val busy: Boolean get() = this is Writing
}

internal const val POST_LIMIT = 280
internal const val KEY_COMMENT_DRAFT_PREFIX = "comment_draft_"
internal const val DIRECTORY = "y"
private const val POST_FILE = "post.properties"

internal fun readYPosts(context: Context): List<YPost> {
    val root = File(context.filesDir, DIRECTORY)
    val folders = root.listFiles().orEmpty()
    return folders.asSequence()
        .filter(File::isDirectory)
        .map(::readYPost)
        .sortedByDescending(YPost::createdAt)
        .toList()
}

internal fun readYPost(folder: File): YPost {
    val values = Properties().apply { File(folder, POST_FILE).inputStream().use(::load) }
    return YPost(
        id = folder.name,
        characterId = values.getProperty("characterId").orEmpty(),
        characterName = values.getProperty("characterName").orEmpty(),
        authorHandle = values.getProperty("authorHandle").orEmpty(),
        text = values.getProperty("text").orEmpty(),
        thoughtProcess = values.getProperty("thoughtProcess").orEmpty(),
        reported = values.getProperty("reported").toBoolean(),
        imagePath = values.getProperty("imagePath").orEmpty(),
        createdAt = values.getProperty("createdAt").toLongOrNull() ?: folder.lastModified(),
        imagePrompt = values.getProperty("imagePrompt").orEmpty(),
    )
}

internal fun writeYDraft(context: Context, draft: YDraft, image: File? = null): YPost {
    val id = draft.id
    val folder = File(File(context.filesDir, DIRECTORY), id)
    folder.mkdirs()
    return try {
        YPost(
            id = id,
            characterId = draft.character.id,
            characterName = draft.character.name,
            authorHandle = draft.character.handle,
            text = draft.text,
            thoughtProcess = draft.thoughtProcess,
            reported = false,
            imagePath = image?.relativeTo(context.filesDir)?.path ?: draft.imagePath,
            createdAt = System.currentTimeMillis(),
            imagePrompt = draft.imagePrompt,
        ).also { writeYPostProperties(folder, it) }
    } catch (failure: Throwable) {
        folder.deleteRecursively()
        throw failure
    }
}

internal fun writeYPostProperties(folder: File, post: YPost) {
    val values = Properties().apply {
        setProperty("characterId", post.characterId)
        setProperty("characterName", post.characterName)
        setProperty("authorHandle", post.authorHandle)
        setProperty("text", post.text)
        setProperty("thoughtProcess", post.thoughtProcess)
        setProperty("reported", post.reported.toString())
        setProperty("imagePath", post.imagePath)
        setProperty("imagePrompt", post.imagePrompt)
        setProperty("createdAt", post.createdAt.toString())
    }
    writeProperties(File(folder, POST_FILE), values)
}

internal fun replaceYImage(context: Context, post: YPost, source: File, retainSourceOnFailure: Boolean = false): YPost {
    val folder = File(File(context.filesDir, DIRECTORY), post.id)
    val oldImage = post.imagePath.takeIf(String::isNotBlank)?.let { File(context.filesDir, it) }
    val updated = post.copy(imagePath = source.relativeTo(context.filesDir).path)
    try {
        writeYPostProperties(folder, updated)
    } catch (failure: Throwable) {
        if (!retainSourceOnFailure) deleteAuraResult(source)
        throw failure
    }
    if (oldImage != null && oldImage != source) deleteAuraResult(oldImage)
    return updated
}

internal suspend fun completeYPost(
    app: Context,
    draft: YDraft,
    onSaved: (YPost) -> Unit = {},
    onProgress: (Int) -> Unit = {},
): YPost {
    val post = withContext(NonCancellable) {
        withContext(Dispatchers.IO) { writeYDraft(app, draft) }.also(onSaved)
    }
    if (post.text.isNotBlank()) {
        CharacterMemory(app, draft.character.id).collectPost(
            sessionId = "y:${post.id}",
            timestamp = post.createdAt,
            entry = "${draft.character.name}: ${post.text}",
        )
    }
    val image = draft.imagePrompt.takeIf(String::isNotBlank)?.let {
        generatePromptImage(app, it, characterId = draft.character.id, onProgress = onProgress)
    }
    return withContext(NonCancellable) {
        if (image != null) {
            withContext(Dispatchers.IO) { replaceYImage(app, post, image, retainSourceOnFailure = true) }
        } else post
    }
}
