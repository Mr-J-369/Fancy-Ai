package com.mrj.fancyai.ui.social

import com.mrj.fancyai.util.writeProperties
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Properties
import java.util.UUID

internal data class SocialComment(
    val id: String = "",
    val postId: String = "",
    val authorId: String = "",
    val authorName: String = "",
    val authorHandle: String = "",
    val text: String = "",
    val createdAt: Long = 0,
    val imagePath: String = "",
    val image: File = File(""),
    val parentId: String = "",
    val thoughtProcess: String = "",
    val imagePrompt: String = "",
)

internal fun readSocialComments(
    filesDir: File,
    directory: String,
    postId: String,
): List<SocialComment> {
    val folder = File(File(File(filesDir, directory), postId), "comments")
    val comments = folder.listFiles().orEmpty().asSequence()
        .filter { it.isFile && it.extension == "properties" }
        .map { file ->
            val values = Properties().apply { file.inputStream().use(::load) }
            val id = values.getProperty("id").orEmpty()
            val imagePath = values.getProperty("imagePath").orEmpty()
            SocialComment(
                id = id,
                postId = postId,
                authorId = values.getProperty("authorId").orEmpty(),
                authorName = values.getProperty("authorName").orEmpty(),
                authorHandle = values.getProperty("authorHandle").orEmpty(),
                text = values.getProperty("text").orEmpty(),
                thoughtProcess = values.getProperty("thoughtProcess").orEmpty(),
                createdAt = values.getProperty("createdAt").toLongOrNull() ?: file.lastModified(),
                imagePath = imagePath,
                image = if (imagePath.isNotEmpty()) File(filesDir, imagePath) else File(file.parentFile, "$id.jpg"),
                parentId = values.getProperty("parentId").orEmpty(),
                imagePrompt = values.getProperty("imagePrompt").orEmpty(),
            )
        }.sortedBy(SocialComment::createdAt).toList()
    return comments
}

internal suspend fun writeSocialComment(filesDir: File, directory: String, comment: SocialComment): SocialComment =
    withContext(NonCancellable) {
        withContext(Dispatchers.IO) {
            val folder = File(File(File(filesDir, directory), comment.postId), "comments").apply { mkdirs() }
            val saved = comment.copy(image = if (comment.imagePath.isNotEmpty()) comment.image else File(folder, "${comment.id}.jpg"))
            val values = Properties().apply {
                setProperty("id", comment.id)
                setProperty("authorId", comment.authorId)
                setProperty("authorName", comment.authorName)
                setProperty("authorHandle", comment.authorHandle)
                setProperty("text", comment.text)
                setProperty("thoughtProcess", comment.thoughtProcess)
                setProperty("createdAt", comment.createdAt.toString())
                setProperty("imagePath", comment.imagePath)
                setProperty("parentId", comment.parentId)
                setProperty("imagePrompt", comment.imagePrompt)
            }
            writeProperties(File(folder, "${comment.id}.properties"), values)
            saved
        }
    }

internal suspend fun persistUserSocialComment(
    filesDir: File,
    directory: String,
    postId: String,
    authorName: String,
    authorHandle: String,
    text: String,
): SocialComment {
    AutomaticSocialPosts.awaitManualTurn()
    return writeSocialComment(
        filesDir, directory,
        SocialComment(
            id = UUID.randomUUID().toString(), postId = postId, authorId = "user",
            authorName = authorName, authorHandle = authorHandle, text = text,
            createdAt = System.currentTimeMillis(),
        ),
    )
}
