package com.mrj.fancyai.ui.ustagram

import android.content.Context
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
import androidx.compose.foundation.text.KeyboardOptions
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import androidx.core.content.edit
import com.mrj.fancyai.R
import com.mrj.fancyai.ui.characters.CharacterCard
import com.mrj.fancyai.ui.kit.AppDialog
import com.mrj.fancyai.ui.kit.AppHeader
import com.mrj.fancyai.ui.kit.CompactSwitch
import com.mrj.fancyai.ui.kit.CompactSwitchRow
import com.mrj.fancyai.ui.kit.ImageGenerationProgress
import com.mrj.fancyai.ui.kit.MessageMarkdown
import com.mrj.fancyai.ui.kit.PostInput
import com.mrj.fancyai.ui.kit.PromptEditor
import com.mrj.fancyai.ui.kit.rememberSessionExit
import com.mrj.fancyai.ui.profile.UserProfile
import com.mrj.fancyai.ui.social.AutomaticPostingAvailability
import com.mrj.fancyai.ui.social.AutomaticSocialPosts
import com.mrj.fancyai.ui.social.SocialActiveGeneration
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
internal fun UstagramScreen(onBack: () -> Unit) {
    val app = LocalContext.current.applicationContext
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    val feedState = rememberLazyListState()
    val controller = remember(app, scope, snackbar, feedState) { UstagramController(app, scope, snackbar, feedState) }
    with(controller) {

        var deleteRequested by remember { mutableStateOf<UstagramPost?>(null) }
        var clearRequested by remember { mutableStateOf(value = false) }
        var settingsOpen by remember { mutableStateOf(value = false) }
        var themesOnly by remember { mutableStateOf(value = false) }

        val actionsEnabled = !busy
        val deletionPending = clearRequested || deleteRequested != null
        AutomaticPostingAvailability(!actionsEnabled || settingsOpen || deletionPending)
        val automaticRevision by AutomaticSocialPosts.revision.collectAsState()

        LaunchedEffect(app, automaticRevision, generation.busy, replying, sending) {
            loadFeed()
        }

        LaunchedEffect(settingsOpen, loading) {
            if (!settingsOpen && !loading && posts.isNotEmpty()) feedState.scrollToItem(0)
        }

        DisposableEffect(app) {
            onDispose {
                stopGeneration()
                runtime.getAndSet(null)?.close()
            }
        }

        val requestExit = rememberSessionExit {
            stopGeneration()
            onBack()
        }
        BackHandler {
            if (settingsOpen) {
                settingsOpen = false
            } else {
                requestExit()
            }
        }

        Box(
            Modifier.fillMaxSize().background(
                Brush.verticalGradient(0f to SlateRaised, 0.28f to Ink, 1f to MaterialTheme.colorScheme.background),
            ),
        ) {
            if (settingsOpen) {
                UstagramSettingsScreen(themesOnly) { settingsOpen = false }
            } else if (openThread != null) {
                Comments(actionsEnabled)
            } else {
                Feed(
                    feedState, actionsEnabled,
                    onBack = requestExit,
                    onSettings = { themes -> themesOnly = themes; settingsOpen = true },
                    onClear = { clearRequested = true },
                    onDelete = { deleteRequested = it },
                )
            }
            SnackbarHost(
                hostState = snackbar,
                modifier = Modifier.align(Alignment.BottomCenter).windowInsetsPadding(WindowInsets.safeDrawing),
            )
        }

        if (deletionPending) {
            val post = deleteRequested
            val (title, message, action) = if (clearRequested) {
                Triple(R.string.ustagram_clear_title, R.string.ustagram_clear_message, R.string.action_clear_feed)
            } else {
                Triple(R.string.social_delete_post_title, R.string.ustagram_delete_post_message, R.string.action_delete)
            }
            val dismiss = {
                clearRequested = false
                deleteRequested = null
            }
            AppDialog(
                onDismissRequest = dismiss,
                title = { Text(stringResource(title)) },
                text = { Text(stringResource(message)) },
                confirmButton = {
                    TextButton(
                        onClick = {
                            dismiss()
                            deletePosts(post)
                        },
                    ) { Text(stringResource(action), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.error) }
                },
                dismissButton = {
                    TextButton(onClick = dismiss) { Text(stringResource(R.string.action_cancel), style = MaterialTheme.typography.labelMedium) }
                },
            )
        }
    }
}

@Composable
private fun UstagramController.Feed(
    feedState: LazyListState,
    actionsEnabled: Boolean,
    onBack: () -> Unit,
    onSettings: (Boolean) -> Unit,
    onClear: () -> Unit,
    onDelete: (UstagramPost) -> Unit,
) {
    val allLabel = stringResource(R.string.ustagram_theme_all)
    val automaticState by AutomaticSocialPosts.state.collectAsState()
    val feedGeneration = if (generation is UstagramGeneration.Idle) {
        automaticState.ustagramDraft?.let { UstagramGeneration.Rendering(it.character, it, automaticState.imageProgress) } ?: generation
    } else generation
    val targetTheme = enabledThemes.firstOrNull { it.equals(themeFilter, true) }.orEmpty()
    Column(
        Modifier.fillMaxSize().windowInsetsPadding(
            WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal),
        ),
    ) {
        UstagramHeader(
            canClear = posts.isNotEmpty() && actionsEnabled,
            theme = themeFilter.takeIf { it != allLabel },
            onBack = onBack,
            onSettings = onSettings,
            onClear = {
                stopGeneration()
                onClear()
            },
        )
        HorizontalDivider(color = Hairline)
        UstagramGenerateBar(
            enabled = !loading && actionsEnabled,
            theme = targetTheme,
            replying = replying,
            replyStatus = replyStatus, imageProgress = replyImageProgress,
        ) { startPost(theme = targetTheme) }
        val displayedPosts = remember(posts, themeFilter) {
            val filter = themeFilter.takeIf { it != allLabel }.orEmpty()
            if (filter.isBlank()) posts
            else posts.filter { it.theme.equals(filter, ignoreCase = true) }
        }
        LazyColumn(
            state = feedState,
            modifier = Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Bottom)),
            contentPadding = PaddingValues(bottom = 24.dp),
        ) {
            if (feedGeneration !is UstagramGeneration.Idle) {
                item(key = "generation") {
                    UstagramGenerationPanel(
                        generation = feedGeneration,
                        posts = posts,
                        onStop = { if (generation.busy) stopGeneration() else AutomaticSocialPosts.cancelPost() },
                    )
                }
            }
            when {
                loading -> item(key = "loading") { UstagramLoading() }
                displayedPosts.isEmpty() && feedGeneration is UstagramGeneration.Idle -> item(key = "empty") { UstagramEmpty() }
                else -> items(displayedPosts, key = UstagramPost::id) { post ->
                    UstagramPostCard(
                        post = post,
                        character = characters.firstOrNull { it.id == post.characterId },
                        profile = profile,
                        actionsEnabled = actionsEnabled,
                        onRegenerateImage = { regenerateImage(post) },
                        onComments = { openComments(post) },
                        onDelete = { onDelete(post) },
                        onReport = { report(post) },
                    )
                }
            }
        }
    }
}

@Composable
private fun UstagramController.Comments(actionsEnabled: Boolean) {
    val app = LocalContext.current.applicationContext
    openThread?.let { post ->
        val authors = remember(characters) { characters.associateBy(CharacterCard::id) }
        SocialCommentsScreen(
            title = stringResource(R.string.social_comments_title, post.characterName),
            onStop = ::stopGeneration,
            empty = threadComments.isEmpty(),
            latestKey = threadComments.lastOrNull(),
            draft = commentDraft,
            hint = stringResource(R.string.social_comment_hint),
            emptyMessage = stringResource(R.string.social_comments_empty),
            status = replyStatus.takeIf { replying },
            imageProgress = replyImageProgress.takeIf { replying },
            sendEnabled = actionsEnabled,
            containerColor = Ink,
            onDraftChange = { commentDraft = it; app.getSharedPreferences("ustagram_settings", Context.MODE_PRIVATE).edit { if (it.isBlank()) remove(KEY_COMMENT_DRAFT_PREFIX + post.id) else putString(KEY_COMMENT_DRAFT_PREFIX + post.id, it) } },
            onSend = { submitComment(post) },
            onDismiss = { openThread = null; threadComments = emptyList() },
            comments = {
                items(threadComments.asReversed(), key = SocialComment::id) { comment ->
                    SocialCommentRow(comment, authors[comment.authorId], profile)
                }
            },
        ) {
            MessageMarkdown(post.caption.ifBlank { stringResource(R.string.ustagram_photo_only_post) },
                MaterialTheme.typography.bodySmall, MaterialTheme.colorScheme.onSurfaceVariant,
                Modifier.padding(top = 3.dp, bottom = 8.dp))
            HorizontalDivider(color = Hairline)
        }
    }
}

@Composable
internal fun UstagramHeader(
    canClear: Boolean,
    theme: String?,
    onBack: () -> Unit,
    onSettings: (themesOnly: Boolean) -> Unit,
    onClear: () -> Unit,
) {
    var menuOpen by remember { mutableStateOf(false) }
    val menuLabel = stringResource(R.string.action_more_options)
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AppHeader(
            title = stringResource(R.string.home_app_ustagram),
            subtitle = theme ?: stringResource(R.string.ustagram_eyebrow),
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
            DropdownMenu(
                expanded = menuOpen,
                onDismissRequest = { menuOpen = false },
                containerColor = SlateRaised,
            ) {
                listOf(
                    R.string.ustagram_themes_action to { onSettings(true) },
                    R.string.action_instructions to { onSettings(false) },
                    R.string.action_clear_feed to onClear,
                ).forEach { (label, action) ->
                    DropdownMenuItem(
                        text = { Text(stringResource(label), style = MaterialTheme.typography.bodySmall) },
                        enabled = label != R.string.action_clear_feed || canClear,
                        onClick = {
                            menuOpen = false
                            action()
                        },
                    )
                }
            }
        }
    }
}

@Composable
internal fun UstagramGenerateBar(
    enabled: Boolean,
    theme: String?,
    replying: Boolean,
    replyStatus: String?, imageProgress: Int? = null,
    onGenerate: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val summary = if (theme.isNullOrBlank()) {
            stringResource(R.string.ustagram_generate_summary)
        } else {
            stringResource(R.string.ustagram_generate_theme_summary)
        }
        if (imageProgress != null) ImageGenerationProgress(imageProgress, Modifier.weight(1f).padding(end = 12.dp))
        else Text(
            replyStatus ?: summary,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 2,
            modifier = Modifier.weight(1f).padding(end = 12.dp),
        )
        if (replying) {
            if (imageProgress == null) CircularProgressIndicator(color = Accent, strokeWidth = 2.dp, modifier = Modifier.requiredSize(20.dp))
        } else {
            Text(
                stringResource(R.string.action_generate),
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.Bold,
                color = Accent.copy(alpha = if (enabled) 1f else 0.35f),
                modifier = Modifier.heightIn(min = 48.dp)
                    .clickable(enabled = enabled, role = Role.Button, onClick = onGenerate)
                    .padding(horizontal = 12.dp, vertical = 17.dp),
            )
        }
    }
    HorizontalDivider(color = Hairline)
}

@Composable
internal fun UstagramGenerationPanel(
    generation: UstagramGeneration,
    posts: List<UstagramPost>,
    onStop: () -> Unit,
) {
    val draft = (generation as? UstagramGeneration.Rendering)?.draft
    if (draft != null && posts.none { it.id == draft.id }) {
        val post = remember(draft) {
            UstagramPost("pending", draft.character.id, draft.character.name, draft.character.handle, draft.theme, draft.caption, false, System.currentTimeMillis(), "", File(""), thoughtProcess = draft.thoughtProcess, imagePrompt = draft.imagePrompt.orEmpty())
        }
        UstagramPostCard(
            post = post, character = draft.character, profile = UserProfile(),
            actionsEnabled = false,
            onComments = {}, onDelete = {}, onReport = {},
            pending = true,
            content = { UstagramGenerationStatus(generation, onStop) },
        )
        return
    }
    UstagramGenerationStatus(generation, onStop)
}

@Composable
private fun UstagramGenerationStatus(
    generation: UstagramGeneration,
    onStop: () -> Unit,
) {
    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        Surface(
            color = Slate.copy(alpha = 0.92f),
            shape = MaterialTheme.shapes.medium,
            modifier = Modifier.widthIn(max = 720.dp).fillMaxWidth().padding(12.dp).animateContentSize(),
        ) {
            when (generation) {
                UstagramGeneration.Idle -> Unit
                is UstagramGeneration.Writing -> SocialActiveGeneration(
                    character = generation.character,
                    title = stringResource(R.string.ustagram_writing, generation.character.name),
                    onStop = onStop,
                )
                is UstagramGeneration.Rendering -> SocialActiveGeneration(
                    character = generation.character,
                    title = generation.character.name,
                    onStop = onStop,
                    progress = generation.progress,
                )
            }
        }
    }
}

@Composable
internal fun UstagramController.UstagramSettingsScreen(themesOnly: Boolean, onBack: () -> Unit) {
    Column(Modifier.fillMaxSize()
        .background(Brush.verticalGradient(0f to MaterialTheme.colorScheme.surfaceContainerLow, 0.28f to Ink, 1f to MaterialTheme.colorScheme.background))
        .windowInsetsPadding(WindowInsets.safeDrawing).padding(horizontal = 0.dp)) {
        AppHeader(title = stringResource(R.string.ustagram_settings_title), onBack = onBack, subtitle = stringResource(R.string.ustagram_settings_summary))
        HorizontalDivider(color = Hairline)
        LazyColumn(
            modifier = Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.ime),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 14.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            if (!themesOnly) {
                item {
                    PromptEditor(
                        value = prompt,
                        section = stringResource(R.string.ustagram_prompt_section),
                        hint = stringResource(R.string.ustagram_prompt_hint),
                        onValueChange = ::updatePrompt,
                        onReset = { updatePrompt() },
                    )
                }
                item {
                    CompactSwitchRow(
                        stringResource(R.string.chat_thinking),
                        thinking,
                        ::updateThinking,
                    )
                }

            }
            else {
                item {
                    UstagramThemeEditor()
                }
            }
        }
    }
}

@Composable
private fun UstagramController.UstagramThemeEditor() {
    val normalizedDraft = normalizeUstagramTheme(themeDraft)
    val addEnabled = (normalizedDraft != null) && customThemes.none { it.equals(normalizedDraft, ignoreCase = true) }
    val allEnabled = customThemes.isNotEmpty() && customThemes.none(disabledThemes::contains)
    val shown = remember(customThemes, themeFilter) { customThemes.filter { it.contains(themeFilter.trim(), ignoreCase = true) } }
    Column(Modifier.fillMaxWidth()) {
        Text(stringResource(R.string.ustagram_themes_section), style = MaterialTheme.typography.labelSmall, color = AccentSoft)
        Text(stringResource(R.string.ustagram_themes_summary), style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 4.dp))
    }
    Row(
        modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        PostInput(
            value = themeDraft,
            hint = stringResource(R.string.ustagram_theme_hint),
            onValueChange = ::updateThemeDraft,
            singleLine = true,
            modifier = Modifier.weight(1f),
            keyboardOptions = KeyboardOptions(
                capitalization = KeyboardCapitalization.Words,
                imeAction = ImeAction.Done,
            ),
        )
        Text(
            stringResource(R.string.action_add),
            style = MaterialTheme.typography.labelMedium,
            color = Accent.copy(alpha = if (addEnabled) 1f else 0.35f),
            modifier = Modifier.heightIn(min = 48.dp)
                .clickable(enabled = addEnabled, role = Role.Button, onClick = ::addTheme)
                .padding(horizontal = 14.dp, vertical = 17.dp),
        )
    }
    if (customThemes.isEmpty()) {
        Text(
            stringResource(R.string.ustagram_theme_empty),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 10.dp),
        )
    } else {
        PostInput(
            value = themeFilter,
            hint = stringResource(R.string.ustagram_theme_filter_hint),
            singleLine = true,
            onValueChange = ::updateThemeFilter,
            modifier = Modifier.padding(top = 10.dp),
        )
        CompactSwitchRow(
            title = stringResource(R.string.ustagram_theme_all),
            summary = stringResource(R.string.ustagram_theme_all_summary),
            checked = allEnabled,
            onCheckedChange = { updateDisabledThemes(if (it) emptySet() else customThemes.toSet()) },
            modifier = Modifier.heightIn(min = 64.dp),
        )
        HorizontalDivider(color = Hairline)
        shown.forEach { theme ->
            val enabled = theme !in disabledThemes
            Row(
                modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    theme,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = if (enabled) 1f else 0.45f),
                    modifier = Modifier.weight(1f).padding(end = 10.dp),
                )
                CompactSwitch(
                    label = theme,
                    checked = enabled,
                    onCheckedChange = { updateDisabledThemes(if (it) disabledThemes - theme else disabledThemes + theme) },
                )
                Text(
                    stringResource(R.string.action_remove),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.heightIn(min = 48.dp)
                        .clickable(role = Role.Button) { removeTheme(theme) }
                        .padding(start = 12.dp, top = 17.dp),
                )
            }
            HorizontalDivider(color = Hairline)
        }
    }
}
