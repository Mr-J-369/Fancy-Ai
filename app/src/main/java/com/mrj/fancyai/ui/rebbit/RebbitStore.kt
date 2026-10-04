package com.mrj.fancyai.ui.rebbit

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

internal const val REBBIT_DIRECTORY = "rebbit"
internal const val KEY_REBBIT_PROMPT = "prompt"
internal const val KEY_THINKING = "thinking"
internal const val KEY_REBBIT_COMMUNITIES = "communities"
internal const val KEY_REBBIT_DISABLED_COMMUNITIES = "disabled_communities"
internal const val KEY_COMMENT_DRAFT_PREFIX = "comment_draft_"
private const val POST_FILE = "post.properties"
internal const val COMMENTS_DIRECTORY = "comments"
private const val IMAGE_FILE = "image.jpg"
internal const val REBBIT_FALLBACK_RESPONDERS = 2
internal const val REBBIT_REPLY_LIMIT = 4
internal const val REBBIT_COMMENT_LIMIT = 500

internal fun readRebbitPosts(context: Context): List<RebbitPost> {
    val root = File(context.filesDir, REBBIT_DIRECTORY)
    val folders = root.listFiles().orEmpty()
    return folders.asSequence()
        .filter(File::isDirectory)
        .map { folder ->
            val props = Properties().apply { File(folder, POST_FILE).inputStream().use(::load) }
            val path = props.getProperty("imagePath").orEmpty()
            RebbitPost(
                folder.name,
                props.getProperty("characterId").orEmpty(),
                props.getProperty("characterName").orEmpty(),
                props.getProperty("authorHandle").orEmpty(),
                props.getProperty("caption").orEmpty().ifBlank { props.getProperty("title").orEmpty() },
                props.getProperty("community").orEmpty(),
                props.getProperty("reported").toBoolean(),
                props.getProperty("createdAt").toLongOrNull() ?: folder.lastModified(),
                path,
                if (path.isNotEmpty()) File(context.filesDir, path) else File(folder, IMAGE_FILE),
                thoughtProcess = props.getProperty("thoughtProcess").orEmpty(),
                imagePrompt = props.getProperty("imagePrompt").orEmpty(),
            )
        }
        .sortedByDescending(RebbitPost::createdAt)
        .toList()
}

internal fun writeRebbitDraft(context: Context, draft: RebbitDraft, image: File? = null): RebbitPost {
    val id = draft.id
    val folder = File(File(context.filesDir, REBBIT_DIRECTORY), id)
    folder.mkdirs()
    val post = RebbitPost(id, draft.character.id, draft.character.name, draft.character.handle, draft.caption, draft.community, false, System.currentTimeMillis(), image?.relativeTo(context.filesDir)?.path.orEmpty(), image ?: File(folder, IMAGE_FILE), thoughtProcess = draft.thoughtProcess, imagePrompt = draft.imagePrompt.orEmpty())
    writeRebbitPostProperties(folder, post)
    return post
}

internal fun writeRebbitPostProperties(folder: File, post: RebbitPost) {
    val props = Properties().apply {
        setProperty("characterId", post.characterId)
        setProperty("characterName", post.characterName)
        setProperty("authorHandle", post.authorHandle)
        setProperty("caption", post.caption)
        setProperty("thoughtProcess", post.thoughtProcess)
        setProperty("community", post.community)
        setProperty("reported", post.reported.toString())
        setProperty("createdAt", post.createdAt.toString())
        setProperty("imagePath", post.imagePath)
        setProperty("imagePrompt", post.imagePrompt)
    }
    writeProperties(File(folder, POST_FILE), props)
}

internal fun replaceRebbitImage(context: Context, post: RebbitPost, source: File, retainSourceOnFailure: Boolean = false): RebbitPost {
    val folder = File(File(context.filesDir, REBBIT_DIRECTORY), post.id)
    val updated = post.copy(image = source, imagePath = source.relativeTo(context.filesDir).path)
    try {
        writeRebbitPostProperties(folder, updated)
    } catch (failure: Throwable) {
        if (!retainSourceOnFailure) deleteAuraResult(source)
        throw failure
    }
    if (post.image != source) deleteAuraResult(post.image)
    return updated
}

internal suspend fun generateRebbitDraft(
    app: Context, author: CharacterCard, active: LlmEngineClient, rebbitPrompt: String,
    community: String?, enabledCommunities: List<String>, disabledCommunities: Set<String>,
    onCommunity: (String?) -> Unit = {},
    thinking: Boolean = false,
): RebbitDraft {
    val startup = withContext(Dispatchers.IO) {
        val settings = LlmSettingsStore.snapshot(app) ?: return@withContext null
        val target = normalizedCommunity(community.orEmpty())?.takeUnless { name -> disabledCommunities.any { it.equals(name, true) } }
            ?: enabledCommunities.randomOrNull()
        val targetContext = target?.let { "Selected r/: $it. Write your post about this topic." }
            ?: "Write a post for an appropriate r/ topic of your choice."
        val bus = MacroBus(app, author, subreddit = target.orEmpty())

        val request = LlmRequest(
            config = settings.sessionConfig(systemInstruction = AssistantProtocol.systemInstruction(settings.runtime, bus, listOf(rebbitPrompt))),
            input = LlmInput(
                text = targetContext,
                context = CharacterMemory(app, author.id).recall(targetContext, newConversation = true),
            ),
            thinking = thinking,
        )
        Pair(request, target)
    }
    if (startup == null) throw MacroException(R.string.social_no_engine, "No engine selected")
    val (request, target) = startup
    onCommunity(target)
    val response = active.generate(request).complete()
    val (postText, scenePrompt) = ImagePrompt.split(MacroBus(app, author, subreddit = target.orEmpty()).text(response.text))
    val parsed = SocialPostJson.parse(postText)
    val extractedCommunity = (SocialPostJson.field(postText, "r/", parsed)
        ?: SocialPostJson.field(postText, "r/name", parsed)
        ?: SocialPostJson.field(postText, "r", parsed)
        ?: SocialPostJson.field(postText, "subreddit", parsed))
        ?.let(::normalizedCommunity)
        ?: Regex("""\br/([a-zA-Z0-9_]+)""", RegexOption.IGNORE_CASE).find(postText)?.value?.let(::normalizedCommunity)
    val selectedCommunity = target
        ?: extractedCommunity?.takeUnless { name -> disabledCommunities.any { it.equals(name, ignoreCase = true) } }
        ?: enabledCommunities.randomOrNull().orEmpty()
    onCommunity(selectedCommunity.takeIf(String::isNotBlank))
    val caption = SocialPostJson.text(postText, "post", parsed).trim().capitalizeFirstVisibleLetter()
    return RebbitDraft(
        character = author,
        imagePrompt = scenePrompt,
        caption = caption,
        thoughtProcess = response.channels.values.joinToString("\n\n"),
        community = selectedCommunity,
    )
}

internal suspend fun createAutomaticRebbitPost(
    app: Context, author: CharacterCard, client: LlmEngineClient,
    onSaved: () -> Unit,
    onProgress: (Int) -> Unit = {},
    onDraft: (RebbitDraft) -> Unit = {},
) {
    val options = withContext(Dispatchers.IO) {
        val disabled = app.getSharedPreferences("rebbit_settings", Context.MODE_PRIVATE).getString(KEY_REBBIT_DISABLED_COMMUNITIES, "").orEmpty().lineSequence().mapNotNull(::normalizedCommunity).toSet()
        val enabled = app.getSharedPreferences("rebbit_settings", Context.MODE_PRIVATE).getString(KEY_REBBIT_COMMUNITIES, "").orEmpty().lineSequence().mapNotNull(::normalizedCommunity)
            .distinct().filterNot { community -> disabled.any { it.equals(community, true) } }.toList()
        Pair(enabled, disabled)
    }
    val draft = generateRebbitDraft(app, author, client,
        app.getSharedPreferences("rebbit_settings", Context.MODE_PRIVATE).getString(KEY_REBBIT_PROMPT, app.getString(R.string.rebbit_default_prompt)).orEmpty(),
        null, options.first, options.second,
        thinking = app.getSharedPreferences("rebbit_settings", Context.MODE_PRIVATE).getBoolean(KEY_THINKING, false))
    onDraft(draft)
    completeRebbitPost(app, draft, onSaved = { onSaved() }, onProgress = onProgress)
}

internal suspend fun completeRebbitPost(
    app: Context,
    draft: RebbitDraft,
    onSaved: (RebbitPost) -> Unit = {},
    onProgress: (Int) -> Unit = {},
): RebbitPost {
    val post = withContext(NonCancellable) {
        withContext(Dispatchers.IO) { writeRebbitDraft(app, draft) }.also(onSaved)
    }
    if (post.caption.isNotBlank()) {
        CharacterMemory(app, draft.character.id).collectPost(
            sessionId = "rebbit:${post.id}",
            timestamp = post.createdAt,
            entry = "${draft.character.name} in r/${post.community}: ${post.caption}",
        )
    }
    val image = draft.imagePrompt?.takeIf(String::isNotBlank)?.let {
        generatePromptImage(app, it, characterId = draft.character.id, onProgress = onProgress)
    }
    return withContext(NonCancellable) {
        if (image != null) {
            withContext(Dispatchers.IO) { replaceRebbitImage(app, post, image, retainSourceOnFailure = true) }
        } else post
    }
}
