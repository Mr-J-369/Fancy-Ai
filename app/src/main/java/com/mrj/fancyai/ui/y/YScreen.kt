package com.mrj.fancyai.ui.y

import android.text.format.DateUtils
import androidx.activity.compose.BackHandler
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.mrj.fancyai.R
import com.mrj.fancyai.ui.characters.CharacterCard
import com.mrj.fancyai.ui.kit.AppDialog
import com.mrj.fancyai.ui.kit.AppHeader
import com.mrj.fancyai.ui.kit.CompactSwitchRow
import com.mrj.fancyai.ui.kit.ImagePromptSection
import com.mrj.fancyai.ui.kit.MessageImage
import com.mrj.fancyai.ui.kit.MessageMarkdown
import com.mrj.fancyai.ui.kit.PromptEditor
import com.mrj.fancyai.ui.kit.ThoughtProcess
import com.mrj.fancyai.ui.kit.rememberSessionExit
import com.mrj.fancyai.ui.profile.UserProfile
import com.mrj.fancyai.ui.social.AutomaticPostingAvailability
import com.mrj.fancyai.ui.social.AutomaticSocialPosts
import com.mrj.fancyai.ui.social.SocialActiveGeneration
import com.mrj.fancyai.ui.social.SocialAvatar
import com.mrj.fancyai.ui.social.SocialComment
import com.mrj.fancyai.ui.social.SocialCommentRow
import com.mrj.fancyai.ui.social.SocialCommentsScreen
import com.mrj.fancyai.ui.theme.Accent
import com.mrj.fancyai.ui.theme.AccentSoft
import com.mrj.fancyai.ui.theme.Hairline
import com.mrj.fancyai.ui.theme.Ink
import com.mrj.fancyai.ui.theme.Slate
import com.mrj.fancyai.ui.theme.SlateRaised
import java.io.File

@Composable
internal fun YScreen(onBack: () -> Unit) {
    val app = LocalContext.current.applicationContext
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    val feedState = rememberLazyListState()
    val controller = remember(app, scope, snackbar, feedState) { YController(app, scope, snackbar, feedState) }

    var settingsOpen by remember { mutableStateOf(value = false) }
    var clearRequested by remember { mutableStateOf(value = false) }
    var deleteRequested by remember { mutableStateOf<YPost?>(null) }
    val busy = controller.generation.busy || controller.replying || controller.sending
    AutomaticPostingAvailability(busy || settingsOpen || clearRequested || (deleteRequested != null))
    val automaticRevision by AutomaticSocialPosts.revision.collectAsState()
    LaunchedEffect(app, automaticRevision, controller.generation.busy, controller.replying, controller.sending) {
        if (busy) return@LaunchedEffect
        controller.loadFeed()
    }

    LaunchedEffect(settingsOpen, controller.loading) {
        if (!settingsOpen && !controller.loading && controller.posts.isNotEmpty()) feedState.scrollToItem(0)
    }

    DisposableEffect(app) {
        onDispose {
            controller.generationRun++
            controller.close()
        }
    }

    val requestExit = rememberSessionExit { controller.stopGeneration(); onBack() }
    BackHandler {
        if (settingsOpen) settingsOpen = false else {
            requestExit()
        }
    }

    Box(
        Modifier.fillMaxSize().background(
            Brush.verticalGradient(0f to SlateRaised, 0.3f to Ink, 1f to MaterialTheme.colorScheme.background),
        ),
    ) {
        if (settingsOpen) {
            YSettingsScreen(
                prompt = controller.prompt,
                thinking = controller.thinking,
                onBack = { settingsOpen = false },
                onPromptChange = controller::updatePrompt,
                onThinkingChange = controller::updateThinking,
            ) { controller.updatePrompt(app.getString(R.string.y_default_prompt)) }
        } else if (controller.openThread != null) {
            controller.Comments()
        } else {
            controller.Feed(
                feedState = feedState,
                onBack = requestExit,
                onSettings = { settingsOpen = true },
                onClear = { clearRequested = true },
            ) { deleteRequested = it }
        }
        SnackbarHost(
            hostState = snackbar,
            modifier = Modifier.align(Alignment.BottomCenter).windowInsetsPadding(WindowInsets.safeDrawing),
        )
    }

    val pending = deleteRequested
    if (clearRequested || (pending != null)) {
        val clearing = clearRequested
        val dismiss = { if (clearing) clearRequested = false else deleteRequested = null }
        val (dialogTitle, dialogMessage) = if (clearing) R.string.y_clear_title to R.string.y_clear_message else R.string.social_delete_post_title to R.string.y_delete_post_message
        AppDialog(
            onDismissRequest = dismiss,
            title = { Text(stringResource(dialogTitle)) },
            text = { Text(stringResource(dialogMessage)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        dismiss()
                        if (clearing) controller.clearFeed() else pending?.let(controller::deletePost)
                    },
                ) { Text(stringResource(R.string.action_delete), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = dismiss) { Text(stringResource(R.string.action_cancel), style = MaterialTheme.typography.labelMedium) }
            },
        )
    }
}


@Composable
private fun YController.Feed(feedState: LazyListState, onBack: () -> Unit, onSettings: () -> Unit, onClear: () -> Unit, onDelete: (YPost) -> Unit) {
    Column(
        Modifier.fillMaxSize().windowInsetsPadding(
            WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal),
        ),
    ) {
        YHeader(
            canClear = posts.isNotEmpty() && !sending && !generation.busy && !replying,
            onBack = onBack,
            onSettings = onSettings,
        ) {
            stopGeneration()
            onClear()
        }
        HorizontalDivider(color = Hairline)
        YGenerateBar(
            enabled = !loading && !sending && !generation.busy && !replying,
            replying = replying,
            status = replyStatus, imageProgress = imageProgress,
            onGenerate = ::startPost,
        )
        LazyColumn(
            modifier = Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Bottom)),
            state = feedState,
            contentPadding = PaddingValues(bottom = 24.dp),
        ) {
            if (generation !is YGeneration.Idle) {
                item(key = "generation") {
                    YGenerationPanel(generation, ::stopGeneration)
                }
            }
            when {
                loading -> item(key = "loading") { YLoading() }
                (posts.isEmpty()) && (generation is YGeneration.Idle) -> item(key = "empty") { YEmpty() }
                else -> items(posts, key = YPost::id) { post ->
                    YPostCard(
                        post = post,
                        character = characters.firstOrNull { (id) -> id == post.characterId },
                        profile = profile,
                        commentCount = commentCounts[post.id] ?: 0,
                        actionsEnabled = !sending && !generation.busy && !replying,
                        onComments = { openComments(post) },
                        onDelete = { onDelete(post) },
                        onReport = { report(post) },
                    ) { regenerateImage(post) }
                }
            }
        }
    }
}

@Composable
private fun YController.Comments() {
    openThread?.let { post ->
        val authors = remember(characters) { characters.associateBy(CharacterCard::id) }
        SocialCommentsScreen(
            title = stringResource(R.string.social_comments_title, post.characterName),
            onStop = ::stopGeneration,
            empty = threadComments.isEmpty(),
            latestKey = threadComments.lastOrNull(),
            draft = commentDraft,
            hint = stringResource(R.string.y_comment_hint),
            emptyMessage = stringResource(R.string.y_comments_empty),
            status = replyStatus.takeIf { replying },
            imageProgress = imageProgress.takeIf { replying },
            sendEnabled = !sending && !replying && !generation.busy,
            containerColor = Ink,
            onDraftChange = { updateCommentDraft(post.id, it) },
            onSend = { submitComment(post) },
            onDismiss = { openThread = null; threadComments = emptyList() },
            comments = {
                items(threadComments.asReversed(), key = SocialComment::id) { comment ->
                    SocialCommentRow(comment, authors[comment.authorId], profile)
                }
            },
        ) {
            ThoughtProcess(post.thoughtProcess)
            MessageMarkdown(
                post.text,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 3.dp, bottom = 8.dp),
            )
            HorizontalDivider(color = Hairline)
        }
    }

}

@Composable
internal fun YHeader(
    canClear: Boolean,
    onBack: () -> Unit,
    onSettings: () -> Unit,
    onClear: () -> Unit,
) {
    var menuOpen by remember { mutableStateOf(value = false) }
    val menuLabel = stringResource(R.string.action_more_options)
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AppHeader(
            title = stringResource(R.string.home_app_y),
            subtitle = stringResource(R.string.y_eyebrow),
            onBack = onBack,
            modifier = Modifier.weight(1f),
            titleMaxLines = 1,
            subtitleMaxLines = 1,
        )
        Box {
            IconButton(
                onClick = { menuOpen = true },
                modifier = Modifier.semantics { contentDescription = menuLabel },
            ) {
                Icon(painter = painterResource(R.drawable.ic_more), contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(22.dp))
            }
            DropdownMenu(menuOpen, { menuOpen = false }, containerColor = SlateRaised) {
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.y_settings_title).lowercase().replaceFirstChar(Char::uppercase), style = MaterialTheme.typography.bodySmall) },
                    onClick = { menuOpen = false; onSettings() },
                )
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.action_clear_feed), style = MaterialTheme.typography.bodySmall) },
                    enabled = canClear,
                    onClick = { onClear(); menuOpen = false },
                )
            }
        }
    }
}

@Composable
internal fun YGenerateBar(
    enabled: Boolean,
    replying: Boolean,
    status: String?, imageProgress: Int? = null,
    onGenerate: () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().background(Slate.copy(alpha = 0.48f)).padding(horizontal = 16.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            if (imageProgress != null) com.mrj.fancyai.ui.kit.ImageGenerationProgress(imageProgress)
            else Text(
                status ?: stringResource(R.string.y_generate_summary),
                style = MaterialTheme.typography.bodySmall,
                color = if (replying) AccentSoft else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Text(
            stringResource(R.string.action_generate),
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.Bold,
            color = Accent.copy(alpha = if (enabled) 1f else 0.35f),
            modifier = Modifier.heightIn(min = 48.dp).clickable(enabled, role = Role.Button, onClick = onGenerate)
                .padding(start = 12.dp, top = 17.dp),
        )
    }
}

@Composable
internal fun YGenerationPanel(
    generation: YGeneration,
    onStop: () -> Unit,
) {
    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        Surface(
            color = Slate.copy(alpha = 0.92f),
            shape = MaterialTheme.shapes.medium,
            modifier = Modifier.widthIn(max = FEED_WIDTH).fillMaxWidth().padding(12.dp).animateContentSize(),
        ) {
            when (generation) {
                YGeneration.Idle -> Unit
                is YGeneration.Writing -> SocialActiveGeneration(
                    generation.character,
                    stringResource(R.string.y_writing, generation.character.name),
                    onStop,
                )
            }
        }
    }
}

@Composable
internal fun YLoading() {
    val description = stringResource(R.string.y_loading)
    Box(
        Modifier.fillMaxWidth().heightIn(min = 240.dp).semantics { contentDescription = description },
        contentAlignment = Alignment.Center,
    ) {
        CircularProgressIndicator(color = Accent, strokeWidth = 2.dp, modifier = Modifier.requiredSize(24.dp))
    }
}

@Composable
internal fun YEmpty() {
    Column(
        Modifier.fillMaxWidth().heightIn(min = 160.dp).padding(horizontal = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(painter = painterResource(R.drawable.ic_empty), contentDescription = null, tint = Accent, modifier = Modifier.size(32.dp))
        Text(
            stringResource(R.string.social_empty_title),
            style = MaterialTheme.typography.titleMedium,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = 8.dp),
        )
        Text(
            stringResource(R.string.y_empty_summary),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = 4.dp).widthIn(max = 300.dp),
        )
    }
}



internal val FEED_WIDTH = 680.dp

@Composable
internal fun YPostCard(
    post: YPost,
    character: CharacterCard?,
    profile: UserProfile,
    commentCount: Int,
    actionsEnabled: Boolean,
    onComments: () -> Unit,
    onDelete: () -> Unit,
    onReport: () -> Unit,
    onRegenerateImage: () -> Unit = {},
) {
    var menuOpen by remember(post.id) { mutableStateOf(value = false) }
    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        Column(Modifier.widthIn(max = FEED_WIDTH).fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp)) {
            Row(verticalAlignment = Alignment.Top) {
                SocialAvatar(character, profile, user = false, post.characterName, 40.dp)
                Column(Modifier.weight(1f).padding(start = 12.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(
                                post.characterName,
                                style = MaterialTheme.typography.titleSmall,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            if (post.authorHandle.isNotBlank()) {
                                Text(
                                    post.authorHandle,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                        }
                        Text(
                            DateUtils.getRelativeTimeSpanString(post.createdAt, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS, DateUtils.FORMAT_ABBREV_RELATIVE).toString(),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(start = 8.dp).align(Alignment.Top),
                        )
                        Box(Modifier.padding(start = 4.dp)) {
                            Icon(painter = painterResource(R.drawable.ic_more), contentDescription = stringResource(R.string.action_more_options), tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(48.dp).clickable(role = Role.Button) { menuOpen = true }.padding(13.dp))
                            DropdownMenu(menuOpen, { menuOpen = false }, containerColor = SlateRaised) {
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.image_regenerate), style = MaterialTheme.typography.bodySmall) },
                                    enabled = actionsEnabled,
                                    onClick = {
                                        menuOpen = false
                                        onRegenerateImage()
                                    },
                                )
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.action_delete), style = MaterialTheme.typography.bodySmall) },
                                    enabled = actionsEnabled,
                                    onClick = {
                                        menuOpen = false
                                        onDelete()
                                    },
                                )
                                DropdownMenuItem(
                                    text = { Text(stringResource(if (post.reported) R.string.status_reported else R.string.action_report), style = MaterialTheme.typography.bodySmall) },
                                    enabled = actionsEnabled && !post.reported,
                                    onClick = {
                                        menuOpen = false
                                        onReport()
                                    },
                                )
                            }
                        }
                    }
                    ThoughtProcess(post.thoughtProcess)
                    MessageMarkdown(
                        content = post.text,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.fillMaxWidth().padding(top = 2.dp),
                    )
                    if (post.imagePath.isNotBlank()) {
                        MessageImage(post.imagePath, R.string.vision_image, contentScale = ContentScale.FillWidth)
                    }
                    ImagePromptSection(
                        if (post.imagePath.isNotBlank()) File(LocalContext.current.filesDir, post.imagePath) else null,
                        savedPrompt = post.imagePrompt.takeIf(String::isNotBlank),
                    )
                    if (post.reported) {
                        Text(
                            stringResource(R.string.status_reported),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.error,
                            modifier = Modifier.padding(top = 4.dp),
                        )
                    }
                    TextButton(onClick = onComments, modifier = Modifier.padding(top = 6.dp)) {
                        Text(
                            pluralStringResource(R.plurals.rebbit_comments_action, commentCount, commentCount),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
    HorizontalDivider(color = Hairline)
}

@Composable
internal fun YSettingsScreen(
    prompt: String,
    thinking: Boolean,
    onBack: () -> Unit,
    onPromptChange: (String) -> Unit,
    onThinkingChange: (Boolean) -> Unit,
    onReset: () -> Unit,
) {
    Column(
        Modifier.fillMaxSize()
            .background(Brush.verticalGradient(0f to MaterialTheme.colorScheme.surfaceContainerLow, 0.28f to Ink, 1f to MaterialTheme.colorScheme.background))
            .windowInsetsPadding(WindowInsets.safeDrawing).padding(horizontal = 0.dp),
    ) {
        AppHeader(title = stringResource(R.string.y_settings_title), onBack = onBack, subtitle = stringResource(R.string.y_settings_summary))
        HorizontalDivider(color = Hairline)
        LazyColumn(
            Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.ime),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 14.dp),
        ) {
            item {
                PromptEditor(
                    value = prompt,
                    section = stringResource(R.string.y_prompt_section),
                    hint = stringResource(R.string.y_prompt_hint),
                    onValueChange = onPromptChange,
                ) { onReset() }
            }
            item {
                CompactSwitchRow(
                    stringResource(R.string.chat_thinking),
                    thinking,
                    onThinkingChange,
                )
            }
        }
    }
}
