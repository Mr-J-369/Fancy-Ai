package com.mrj.fancyai.ui.ustagram

import com.mrj.fancyai.service.llm.AssistantProtocol
import android.content.Context
import com.mrj.fancyai.R
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
import java.util.Locale
import java.util.Properties

internal const val DIRECTORY = "ustagram"
internal const val KEY_PROMPT = "prompt"
internal const val KEY_THINKING = "thinking"
internal const val KEY_CUSTOM_THEMES = "custom_themes"
internal const val KEY_DISABLED_THEMES = "disabled_themes"
internal const val KEY_THEME_DRAFT = "theme_draft"
internal const val KEY_THEME_FILTER = "theme_filter"
internal const val KEY_COMMENT_DRAFT_PREFIX = "comment_draft_"
private const val POST_FILE = "post.properties"
private const val PHOTO_FILE = "photo.jpg"

internal fun readUstagramPosts(context: Context): List<UstagramPost> {
    val root = File(context.filesDir, DIRECTORY)
    val folders = root.listFiles()
    return folders
        .orEmpty()
        .asSequence()
        .filter(File::isDirectory)
        .map(::readUstagramPost)
        .sortedByDescending(UstagramPost::createdAt)
        .toList()
}

internal fun readUstagramPost(folder: File): UstagramPost {
    val values = Properties().apply { File(folder, POST_FILE).inputStream().use(::load) }
    val photoPath = values.getProperty("photoPath").orEmpty()
    val photo = if (photoPath.isNotEmpty()) {
        File(folder.parentFile?.parentFile, photoPath)
    } else {
        File(folder, PHOTO_FILE)
    }
    return UstagramPost(
        id = folder.name,
        characterId = values.getProperty("characterId").orEmpty(),
        characterName = values.getProperty("characterName").orEmpty(),
        authorHandle = values.getProperty("authorHandle").orEmpty(),
        theme = values.getProperty("theme").orEmpty(),
        caption = values.getProperty("caption").orEmpty(),
        thoughtProcess = values.getProperty("thoughtProcess").orEmpty(),
        reported = values.getProperty("reported").toBoolean(),
        createdAt = values.getProperty("createdAt").toLongOrNull() ?: folder.lastModified(),
        photoPath = photoPath,
        photo = photo,
        imagePrompt = values.getProperty("imagePrompt").orEmpty(),
    )
}

internal fun writeUstagramDraft(context: Context, draft: UstagramDraft, image: File? = null): UstagramPost {
    val id = draft.id
    val folder = File(File(context.filesDir, DIRECTORY), id)
    folder.mkdirs()
    return UstagramPost(
        id = id,
        characterId = draft.character.id,
        characterName = draft.character.name,
        authorHandle = draft.character.handle,
        theme = draft.theme,
        caption = draft.caption,
        thoughtProcess = draft.thoughtProcess,
        reported = false,
        createdAt = System.currentTimeMillis(),
        photoPath = image?.relativeTo(context.filesDir)?.path.orEmpty(),
        photo = image ?: File(folder, PHOTO_FILE),
        imagePrompt = draft.imagePrompt.orEmpty(),
    ).also { writeUstagramPostProperties(folder, it) }
}

internal fun writeUstagramPostProperties(folder: File, post: UstagramPost) {
    val values = Properties().apply {
        setProperty("characterId", post.characterId)
        setProperty("characterName", post.characterName)
        setProperty("authorHandle", post.authorHandle)
        setProperty("theme", post.theme)
        setProperty("caption", post.caption)
        setProperty("thoughtProcess", post.thoughtProcess)
        setProperty("reported", post.reported.toString())
        setProperty("createdAt", post.createdAt.toString())
        setProperty("photoPath", post.photoPath)
        setProperty("imagePrompt", post.imagePrompt)
    }
    writeProperties(File(folder, POST_FILE), values)
}

internal fun replaceUstagramImage(context: Context, post: UstagramPost, source: File, retainSourceOnFailure: Boolean = false): UstagramPost {
    val folder = File(File(context.filesDir, DIRECTORY), post.id)
    val updated = post.copy(photo = source, photoPath = source.relativeTo(context.filesDir).path)
    try {
        writeUstagramPostProperties(folder, updated)
    } catch (failure: Throwable) {
        if (!retainSourceOnFailure) deleteAuraResult(source)
        throw failure
    }
    if (post.photo != source) deleteAuraResult(post.photo)
    return updated
}

internal suspend fun generateUstagramDraft(
    app: Context, author: CharacterCard, active: LlmEngineClient, postPrompt: String,
    requestedTheme: String?, enabledThemes: List<String>, excludedThemes: Set<String>,
    thinking: Boolean = false,
): UstagramDraft {
    val startup = withContext(Dispatchers.IO) {
        val settings = LlmSettingsStore.snapshot(app) ?: return@withContext null
        val targetTheme = requestedTheme?.let { selected ->
            enabledThemes.firstOrNull { it.equals(selected, true) }
                ?: throw MacroException(R.string.ustagram_wrong_theme, "The selected hashtag is not enabled")
        } ?: enabledThemes.randomOrNull()
        val targetContext = targetTheme?.let { "Selected #: $it. Write your post about this topic." }
            ?: "Write a post with an appropriate # of your choice."
        val bus = MacroBus(app, author, hashtag = targetTheme.orEmpty())

        val request = LlmRequest(
            config = settings.sessionConfig(systemInstruction = AssistantProtocol.systemInstruction(settings.runtime, bus, listOf(postPrompt))),
            input = LlmInput(
                text = targetContext,
                context = CharacterMemory(app, author.id).recall(targetContext, newConversation = true),
            ),
            thinking = thinking,
        )
        Pair(request, targetTheme)
    }
    if (startup == null) {
        throw MacroException(R.string.ustagram_no_engine, "No engine selected")
    }
    val (request, targetTheme) = startup
    val response = active.generate(request).complete()
    val (postText, scenePrompt) = ImagePrompt.split(MacroBus(app, author, hashtag = targetTheme.orEmpty()).text(response.text))
    val parsed = SocialPostJson.parse(postText)
    val extractedTheme = (SocialPostJson.field(postText, "#", parsed) ?: SocialPostJson.field(postText, "hashtag", parsed))
        ?.let(::normalizeUstagramTheme)
        ?: Regex("""#([a-zA-Z0-9_]+)""").find(postText)?.value?.let(::normalizeUstagramTheme)
    val theme = targetTheme
        ?: extractedTheme?.takeUnless { name -> excludedThemes.any { it.equals(name, ignoreCase = true) } }
        ?: enabledThemes.randomOrNull().orEmpty()
    val caption = SocialPostJson.text(postText, "caption", parsed).trim().capitalizeFirstVisibleLetter()
    return UstagramDraft(
        character = author,
        imagePrompt = scenePrompt,
        theme = theme,
        caption = caption,
        thoughtProcess = response.channels.values.joinToString("\n\n"),
    )
}

internal suspend fun createAutomaticUstagramPost(
    app: Context, author: CharacterCard, client: LlmEngineClient,
    onSaved: () -> Unit,
    onProgress: (Int) -> Unit = {},
    onDraft: (UstagramDraft) -> Unit = {},
) {
    val disabled = app.getSharedPreferences("ustagram_settings", Context.MODE_PRIVATE).getString(KEY_DISABLED_THEMES, "").orEmpty().lineSequence().mapNotNull(::normalizeUstagramTheme).toSet()
    val enabled = app.getSharedPreferences("ustagram_settings", Context.MODE_PRIVATE).getString(KEY_CUSTOM_THEMES, "").orEmpty().lineSequence().mapNotNull(::normalizeUstagramTheme).distinctBy { it.lowercase(Locale.ROOT) }.filterNot { theme -> disabled.any { it.equals(theme, true) } }.toList()
    val thinking = app.getSharedPreferences("ustagram_settings", Context.MODE_PRIVATE).getBoolean(KEY_THINKING, false)
    val draft = generateUstagramDraft(app, author, client, app.getSharedPreferences("ustagram_settings", Context.MODE_PRIVATE).getString(KEY_PROMPT, app.getString(R.string.ustagram_default_prompt)).orEmpty(), null, enabled, disabled, thinking)
    onDraft(draft)
    completeUstagramPost(app, draft, onSaved = { onSaved() }, onProgress = onProgress)
}

internal suspend fun completeUstagramPost(
    app: Context,
    draft: UstagramDraft,
    onSaved: (UstagramPost) -> Unit = {},
    onProgress: (Int) -> Unit = {},
): UstagramPost {
    val post = withContext(NonCancellable) {
        withContext(Dispatchers.IO) { writeUstagramDraft(app, draft) }.also(onSaved)
    }
    if (post.caption.isNotBlank()) {
        CharacterMemory(app, draft.character.id).collectPost(
            sessionId = "ustagram:${post.id}",
            timestamp = post.createdAt,
            entry = "${draft.character.name} (#${post.theme}): ${post.caption}",
        )
    }
    val image = draft.imagePrompt?.takeIf(String::isNotBlank)?.let {
        generatePromptImage(app, it, characterId = draft.character.id, onProgress = onProgress)
    }
    return withContext(NonCancellable) {
        if (image != null) {
            withContext(Dispatchers.IO) { replaceUstagramImage(app, post, image, retainSourceOnFailure = true) }
        } else post
    }
}
