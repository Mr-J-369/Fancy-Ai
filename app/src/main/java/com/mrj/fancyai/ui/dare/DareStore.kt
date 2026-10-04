package com.mrj.fancyai.ui.dare

import android.content.Context
import com.mrj.fancyai.R
import com.mrj.fancyai.service.llm.AssistantProtocol
import com.mrj.fancyai.service.llm.ImagePrompt
import com.mrj.fancyai.service.llm.LlmEngineClient
import com.mrj.fancyai.service.llm.LlmInput
import com.mrj.fancyai.service.llm.LlmRequest
import com.mrj.fancyai.service.llm.MacroBus
import com.mrj.fancyai.service.llm.MacroException
import com.mrj.fancyai.service.llm.SocialPostJson
import com.mrj.fancyai.service.llm.complete
import com.mrj.fancyai.service.llm.generatePromptImage
import com.mrj.fancyai.service.memory.CharacterMemory
import com.mrj.fancyai.ui.aura.deleteAuraResult
import com.mrj.fancyai.ui.characters.CharacterCard
import com.mrj.fancyai.ui.kit.capitalizeFirstVisibleLetter
import com.mrj.fancyai.ui.settings.LlmSettingsStore
import com.mrj.fancyai.ui.settings.runtime
import com.mrj.fancyai.ui.settings.sessionConfig
import com.mrj.fancyai.util.writeProperties
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Properties

internal const val DARE_DIRECTORY = "dare"
internal const val KEY_DARE_PROMPT = "prompt"
internal const val KEY_THINKING = "thinking"
private const val POST_FILE = "post.properties"
private const val IMAGE_FILE = "image.jpg"

internal fun readDarePosts(context: Context): List<DarePost> {
    val root = File(context.filesDir, DARE_DIRECTORY)
    val folders = root.listFiles().orEmpty()
    return folders.asSequence()
        .filter(File::isDirectory)
        .map { folder ->
            val props = Properties().apply { File(folder, POST_FILE).inputStream().use(::load) }
            val path = props.getProperty("imagePath").orEmpty()
            DarePost(
                id = folder.name,
                characterId = props.getProperty("characterId").orEmpty(),
                characterName = props.getProperty("characterName").orEmpty(),
                authorHandle = props.getProperty("authorHandle").orEmpty(),
                dareTitle = props.getProperty("dareTitle").orEmpty(),
                caption = props.getProperty("caption").orEmpty(),
                createdAt = props.getProperty("createdAt")?.toLongOrNull() ?: folder.lastModified(),
                imagePath = path,
                image = if (path.isNotEmpty()) File(context.filesDir, path) else File(folder, IMAGE_FILE),
                thoughtProcess = props.getProperty("thoughtProcess").orEmpty(),
                imagePrompt = props.getProperty("imagePrompt").orEmpty(),
            )
        }
        .sortedByDescending(DarePost::createdAt)
        .toList()
}

internal fun writeDareDraft(context: Context, draft: DareDraft, image: File? = null): DarePost {
    val id = draft.id
    val folder = File(File(context.filesDir, DARE_DIRECTORY), id)
    folder.mkdirs()
    val post = DarePost(
        id = id,
        characterId = draft.character.id,
        characterName = draft.character.name,
        authorHandle = draft.character.handle,
        dareTitle = draft.dareTitle,
        caption = draft.caption,
        createdAt = System.currentTimeMillis(),
        imagePath = image?.relativeTo(context.filesDir)?.path.orEmpty(),
        image = image ?: File(folder, IMAGE_FILE),
        thoughtProcess = draft.thoughtProcess,
        imagePrompt = draft.imagePrompt.orEmpty(),
    )
    writeDarePostProperties(folder, post)
    return post
}

internal fun writeDarePostProperties(folder: File, post: DarePost) {
    val props = Properties().apply {
        setProperty("characterId", post.characterId)
        setProperty("characterName", post.characterName)
        setProperty("authorHandle", post.authorHandle)
        setProperty("dareTitle", post.dareTitle)
        setProperty("caption", post.caption)
        setProperty("thoughtProcess", post.thoughtProcess)
        setProperty("createdAt", post.createdAt.toString())
        setProperty("imagePath", post.imagePath)
        setProperty("imagePrompt", post.imagePrompt)
    }
    writeProperties(File(folder, POST_FILE), props)
}

internal fun replaceDareImage(context: Context, post: DarePost, source: File, retainSourceOnFailure: Boolean = false): DarePost {
    val folder = File(File(context.filesDir, DARE_DIRECTORY), post.id)
    val updated = post.copy(image = source, imagePath = source.relativeTo(context.filesDir).path)
    try {
        writeDarePostProperties(folder, updated)
    } catch (failure: Throwable) {
        if (!retainSourceOnFailure) deleteAuraResult(source)
        throw failure
    }
    if (post.image != source) deleteAuraResult(post.image)
    return updated
}

internal suspend fun generateDareDraft(
    app: Context,
    author: CharacterCard,
    active: LlmEngineClient,
    dareInstruction: String,
    dareTitle: String,
    thinking: Boolean = false,
): DareDraft {
    val startup = withContext(Dispatchers.IO) {
        val settings = LlmSettingsStore.snapshot(app) ?: return@withContext null
        val targetContext = "Dare Challenge: $dareTitle. Respond in-character performing this dare."
        val profile = com.mrj.fancyai.ui.profile.userProfile(app)
        val bus = MacroBus(app, author, profile)

        val request = LlmRequest(
            config = settings.sessionConfig(
                systemInstruction = AssistantProtocol.systemInstruction(settings.runtime, bus, listOf(dareInstruction)),
            ),
            input = LlmInput(
                text = targetContext,
                context = CharacterMemory(app, author.id).recall(targetContext, newConversation = true),
            ),
            thinking = thinking,
        )
        Pair(request, profile)
    }
    if (startup == null) throw MacroException(R.string.social_no_engine, "No engine selected")
    val (request, profile) = startup
    val response = active.generate(request).complete()
    val (postText, scenePrompt) = ImagePrompt.split(MacroBus(app, author, profile).text(response.text))
    val parsed = SocialPostJson.parse(postText)
    val extractedDare = SocialPostJson.field(postText, "dare", parsed)?.trim()
    val finalTitle = if (dareTitle != app.getString(R.string.dare_fallback_title)) {
        dareTitle
    } else {
        extractedDare?.takeIf { it.length <= 60 } ?: dareTitle
    }
    val caption = SocialPostJson.text(postText, "post", parsed).ifBlank { postText }.trim().capitalizeFirstVisibleLetter()
    return DareDraft(
        character = author,
        dareTitle = finalTitle,
        imagePrompt = scenePrompt,
        caption = caption,
        thoughtProcess = response.channels.values.joinToString("\n\n"),
    )
}

internal suspend fun createAutomaticDarePost(
    app: Context,
    author: CharacterCard,
    client: LlmEngineClient,
    onSaved: () -> Unit,
    onProgress: (Int) -> Unit = {},
    onDraft: (DareDraft) -> Unit = {},
) {
    val prompt = app.getSharedPreferences("dare_settings", Context.MODE_PRIVATE)
        .getString(KEY_DARE_PROMPT, app.getString(R.string.dare_default_prompt)).orEmpty()
    val thinking = app.getSharedPreferences("dare_settings", Context.MODE_PRIVATE).getBoolean(KEY_THINKING, false)
    val challenge = app.getString(R.string.dare_fallback_title)
    val draft = generateDareDraft(app, author, client, prompt, challenge, thinking)
    onDraft(draft)
    completeDarePost(app, draft, onSaved = { onSaved() }, onProgress = onProgress)
}

internal suspend fun completeDarePost(
    app: Context,
    draft: DareDraft,
    onSaved: (DarePost) -> Unit = {},
    onProgress: (Int) -> Unit = {},
): DarePost {
    val post = withContext(NonCancellable) {
        withContext(Dispatchers.IO) { writeDareDraft(app, draft) }.also(onSaved)
    }
    if (post.caption.isNotBlank()) {
        CharacterMemory(app, draft.character.id).collectPost(
            sessionId = "dare:${post.id}",
            timestamp = post.createdAt,
            entry = "${draft.character.name} completed dare '${draft.dareTitle}': ${post.caption}",
        )
    }
    val image = draft.imagePrompt?.takeIf(String::isNotBlank)?.let {
        generatePromptImage(app, it, characterId = draft.character.id, onProgress = onProgress)
    }
    return withContext(NonCancellable) {
        if (image != null) {
            withContext(Dispatchers.IO) { replaceDareImage(app, post, image, retainSourceOnFailure = true) }
        } else post
    }
}
