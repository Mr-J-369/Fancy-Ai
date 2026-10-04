package com.mrj.fancyai.ui.rebbit

import androidx.activity.compose.BackHandler
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
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
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.mrj.fancyai.R
import com.mrj.fancyai.ui.kit.AppDialog
import com.mrj.fancyai.ui.kit.AppHeader
import com.mrj.fancyai.ui.kit.rememberSessionExit
import com.mrj.fancyai.ui.profile.UserProfile
import com.mrj.fancyai.ui.social.AutomaticPostingAvailability
import com.mrj.fancyai.ui.social.AutomaticSocialPosts
import com.mrj.fancyai.ui.social.SocialActiveGeneration
import com.mrj.fancyai.ui.theme.Hairline
import com.mrj.fancyai.ui.theme.Ink
import com.mrj.fancyai.ui.theme.Slate
import com.mrj.fancyai.ui.theme.SlateRaised
import java.io.File

@Composable
internal fun RebbitScreen(onBack: () -> Unit) {
    val app = LocalContext.current.applicationContext
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    val feedState = rememberLazyListState()
    val controller = remember(app, scope, snackbar, feedState) { RebbitController(app, scope, snackbar, feedState) }
    with(controller) {

        var deletePostRequested by remember { mutableStateOf<RebbitPost?>(null) }
        AutomaticPostingAvailability(busy || settingsOpen || clearRequested || deletePostRequested != null)
        val automaticRevision by AutomaticSocialPosts.revision.collectAsState()
        val automaticState by AutomaticSocialPosts.state.collectAsState()
        val feedGeneration = if (generation is RebbitGeneration.Idle) {
            automaticState.rebbitDraft?.let { RebbitGeneration.Rendering(it.character, it, automaticState.imageProgress) } ?: generation
        } else generation

        LaunchedEffect(app, automaticRevision, generation.busy, replying, sending) {
            loadFeed(automaticRevision)
        }
        LaunchedEffect(communityFilter) { feedState.scrollToItem(0) }

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

        val back = { navigateBack(requestExit) }
        BackHandler(onBack = back)
        val actionsEnabled = !busy
        val selectedCommunity = normalizedCommunity(communityFilter.takeUnless { it == app.getString(R.string.rebbit_community_all) }.orEmpty())
        val postContent: @Composable (RebbitPost, Boolean) -> Unit = { post, expanded ->
            RebbitPostCard(post = post, character = characters.firstOrNull { it.id == post.characterId }, userProfile = profile,
                commentCount = commentCounts[post.id] ?: 0, expanded = expanded,
                actionsEnabled = actionsEnabled,
                onRegenerateImage = { regenerateImage(post) },
                onComments = { openPost(post) },
                onCommunity = { selectCommunity(post.community) },
                onDelete = { deletePostRequested = post }, onReport = { report(post) })
        }

        Box(
            Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(SlateRaised, Ink, Ink))),
            contentAlignment = Alignment.TopCenter,
        ) {
            RebbitFeed(
                selectedCommunity = selectedCommunity,
                feedGeneration = feedGeneration,
                actionsEnabled = actionsEnabled,
                onBack = back,
                postContent = postContent,
            )
            when {
                settingsOpen -> RebbitSettingsScreen(
                    communitiesOnly = communitiesOnly,
                    communities = allCommunities,
                    onBack = back,
                    onOpenCommunity = { selectCommunity(it); navigateBack(requestExit) },
                )
                openThread != null -> {
                    val post = posts.firstOrNull { it.id == openThread?.id } ?: checkNotNull(openThread)
                    RebbitThreadPage(
                        post = post,
                        actionsEnabled = actionsEnabled,
                        onBack = back,
                    )
                }
                else -> Unit
            }
            SnackbarHost(
                hostState = snackbar,
                modifier = Modifier.align(Alignment.BottomCenter)
                    .windowInsetsPadding(WindowInsets.safeDrawing).imePadding(),
            )
        }

        deletePostRequested?.let { post -> AppDialog(onDismissRequest = { deletePostRequested = null }, title = { Text(stringResource(R.string.social_delete_post_title)) }, text = { Text(stringResource(R.string.rebbit_delete_post_message)) },
            confirmButton = { TextButton(onClick = {
                deletePostRequested = null
                deletePosts(post)
            }) { Text(stringResource(R.string.action_delete), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.error) } }, dismissButton = { TextButton(onClick = { deletePostRequested = null }) { Text(stringResource(R.string.action_cancel), style = MaterialTheme.typography.labelMedium) } }) }
    }
}

@Composable
internal fun RebbitController.RebbitHeader(visible: Boolean, canClear: Boolean, onBack: () -> Unit) {
    if (clearRequested) AppDialog(onDismissRequest = { clearRequested = false }, title = { Text(stringResource(R.string.rebbit_clear_title)) }, text = { Text(stringResource(R.string.rebbit_clear_message)) },
        confirmButton = { TextButton(onClick = {
            clearRequested = false
            deletePosts()
        }) { Text(stringResource(R.string.action_clear_feed), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.error) } }, dismissButton = { TextButton(onClick = { clearRequested = false }) { Text(stringResource(R.string.action_cancel), style = MaterialTheme.typography.labelMedium) } })
    if (!visible) return
    Row(
        Modifier.fillMaxWidth().windowInsetsPadding(
            WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal),
        ).padding(horizontal = 12.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AppHeader(
            title = stringResource(R.string.home_app_rebbit),
            subtitle = null,
            onBack = onBack,
            modifier = Modifier.weight(1f),
            titleMaxLines = 1,
        )
        Box {
            val menuLabel = stringResource(R.string.action_more_options)
            IconButton(onClick = { menuOpen = true }, modifier = Modifier.semantics { contentDescription = menuLabel }) {
                Icon(painter = painterResource(R.drawable.ic_more), contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(22.dp))
            }
            DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }, containerColor = SlateRaised) {
                DropdownMenuItem(text = { Text(stringResource(R.string.rebbit_communities_action)) }, onClick = {
                    menuOpen = false
                    communitiesOnly = true
                    settingsOpen = true
                })
                DropdownMenuItem(text = { Text(stringResource(R.string.action_instructions), style = MaterialTheme.typography.labelMedium) }, onClick = {
                    menuOpen = false
                    communitiesOnly = false
                    settingsOpen = true
                })
                DropdownMenuItem(text = { Text(stringResource(R.string.action_clear_feed), style = MaterialTheme.typography.labelMedium) }, enabled = canClear, onClick = {
                    menuOpen = false
                    clearRequested = true
                })
            }
        }
    }
}

@Composable
private fun RebbitController.RebbitFeed(
    selectedCommunity: String?,
    feedGeneration: RebbitGeneration,
    actionsEnabled: Boolean,
    onBack: () -> Unit,
    postContent: @Composable (RebbitPost, Boolean) -> Unit,
) {
    val visible = !settingsOpen && openThread == null
    Column(Modifier.fillMaxSize()) {
        RebbitHeader(visible, actionsEnabled && posts.isNotEmpty(), onBack)
        if (!visible) return@Column
        val displayed = remember(posts, selectedCommunity) {
            posts.filter { selectedCommunity == null || it.community.equals(selectedCommunity, ignoreCase = true) }
        }
        LazyColumn(
            state = feedState,
            contentPadding = PaddingValues(bottom = 24.dp),
            modifier = Modifier.weight(1f).windowInsetsPadding(
                WindowInsets.safeDrawing.only(WindowInsetsSides.Bottom + WindowInsetsSides.Horizontal),
            ),
        ) {
            if (selectedCommunity != null) item(key = "community") {
                Text(selectedCommunity, style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(20.dp))
            }
            item(key = "generate") {
                RebbitGenerateBar(
                    enabled = actionsEnabled && !loading,
                    community = selectedCommunity,
                    replying = replying,
                    replyStatus = replyStatus,
                    imageProgress = replyImageProgress,
                    onStop = ::stopGeneration,
                    onGenerate = { startPost(community = selectedCommunity) },
                )
            }
            if (feedGeneration !is RebbitGeneration.Idle) item(key = "generation") {
                RebbitGenerationPanel(
                    generation = feedGeneration,
                    posts = posts,
                    onStop = { if (generation.busy) stopGeneration() else AutomaticSocialPosts.cancelPost() },
                )
            }
            if (loading) item(key = "loading") {
                RebbitLoading()
            } else if (displayed.isEmpty() && feedGeneration is RebbitGeneration.Idle) item(key = "empty") {
                RebbitEmpty()
            } else items(displayed, key = RebbitPost::id) { post ->
                postContent(post, false)
            }
        }
    }
}

@Composable
internal fun RebbitGenerateBar(enabled: Boolean, community: String?, replying: Boolean, replyStatus: String?, imageProgress: Int? = null, onStop: () -> Unit, onGenerate: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        if (imageProgress != null) com.mrj.fancyai.ui.kit.ImageGenerationProgress(imageProgress, Modifier.weight(1f).padding(end = 12.dp))
        else Text(replyStatus ?: stringResource(if (community == null) R.string.rebbit_generate_summary else R.string.rebbit_generate_community_summary),
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f).padding(end = 12.dp))
        if (replying) TextButton(onClick = onStop) { Text(stringResource(R.string.action_stop), style = MaterialTheme.typography.labelMedium) }
        else TextButton(enabled = enabled, onClick = onGenerate) { Text(stringResource(R.string.rebbit_new_moment)) }
    }
    HorizontalDivider(color = Hairline)
}

@Composable
internal fun RebbitGenerationPanel(generation: RebbitGeneration, posts: List<RebbitPost>, onStop: () -> Unit) {
    val draft = (generation as? RebbitGeneration.Rendering)?.draft
    if (draft != null && posts.none { it.id == draft.id }) {
        val post = remember(draft) {
            RebbitPost("pending", draft.character.id, draft.character.name, draft.character.handle, draft.caption, draft.community, false, System.currentTimeMillis(), "", File(""), thoughtProcess = draft.thoughtProcess, imagePrompt = draft.imagePrompt.orEmpty())
        }
        RebbitPostCard(
            post = post, character = draft.character, userProfile = UserProfile(),
            commentCount = 0, expanded = true, actionsEnabled = false,
            onComments = {}, onCommunity = {}, onDelete = {}, onReport = {},
            pending = true,
            content = { RebbitGenerationStatus(generation, onStop) },
        )
        return
    }
    RebbitGenerationStatus(generation, onStop)
}

@Composable
private fun RebbitGenerationStatus(
    generation: RebbitGeneration,
    onStop: () -> Unit,
) {
    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        Surface(color = Slate.copy(alpha = 0.92f), shape = MaterialTheme.shapes.medium, modifier = Modifier.widthIn(max = FEED_WIDTH).fillMaxWidth().padding(12.dp).animateContentSize()) {
            when (generation) {
                RebbitGeneration.Idle -> Unit
                is RebbitGeneration.Writing -> SocialActiveGeneration(character = generation.character, title = stringResource(R.string.rebbit_writing, generation.character.name), onStop = onStop)
                is RebbitGeneration.Rendering -> SocialActiveGeneration(character = generation.character, title = generation.character.name, onStop = onStop, progress = generation.progress)
            }
        }
    }
}
