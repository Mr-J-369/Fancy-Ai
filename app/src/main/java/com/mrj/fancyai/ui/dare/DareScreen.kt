package com.mrj.fancyai.ui.dare

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
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
import androidx.core.content.edit
import com.mrj.fancyai.R
import com.mrj.fancyai.ui.kit.AppDialog
import com.mrj.fancyai.ui.kit.AppHeader
import com.mrj.fancyai.ui.kit.CompactSwitchRow
import com.mrj.fancyai.ui.kit.PostInput
import com.mrj.fancyai.ui.kit.rememberSessionExit
import com.mrj.fancyai.ui.social.AutomaticPostingAvailability
import com.mrj.fancyai.ui.social.AutomaticSocialPosts
import com.mrj.fancyai.ui.theme.Ink
import com.mrj.fancyai.ui.theme.Slate
import com.mrj.fancyai.ui.theme.SlateRaised

@Composable
internal fun DareScreen(onBack: () -> Unit) {
    val app = LocalContext.current.applicationContext
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    val feedState = rememberLazyListState()
    val controller = remember(app, scope, snackbar, feedState) { DareController(app, scope, snackbar, feedState) }

    with(controller) {
        var deletePostRequested by remember { mutableStateOf<DarePost?>(null) }
        AutomaticPostingAvailability(busy || settingsOpen || clearRequested || (deletePostRequested != null))
        val automaticRevision by AutomaticSocialPosts.revision.collectAsState()

        LaunchedEffect(app, automaticRevision, generation.busy, sending) {
            loadFeed(automaticRevision)
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

        val back = { navigateBack(requestExit) }
        BackHandler(onBack = back)
        val actionsEnabled = !busy

        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Brush.verticalGradient(listOf(SlateRaised, Ink, Ink))),
            contentAlignment = Alignment.TopCenter,
        ) {
            DareFeed(
                actionsEnabled = actionsEnabled,
                onBack = back,
            )

            if (settingsOpen) {
                DareSettingsScreen(onBack = back)
            }

            SnackbarHost(
                hostState = snackbar,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .windowInsetsPadding(WindowInsets.safeDrawing)
                    .imePadding(),
            )
        }

        deletePostRequested?.let { post ->
            AppDialog(
                onDismissRequest = { deletePostRequested = null },
                title = { Text(stringResource(R.string.dare_delete_post_title)) },
                text = { Text(stringResource(R.string.dare_delete_post_message)) },
                confirmButton = {
                    TextButton(
                        onClick = {
                            deletePostRequested = null
                            deletePosts(post)
                        },
                    ) {
                        Text(
                            text = stringResource(R.string.action_delete),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                },
                dismissButton = {
                    TextButton(onClick = { deletePostRequested = null }) {
                        Text(
                            text = stringResource(R.string.action_cancel),
                            style = MaterialTheme.typography.labelMedium,
                        )
                    }
                },
            )
        }
    }
}

@Composable
private fun DareController.DareHeader(visible: Boolean, canClear: Boolean, onBack: () -> Unit) {
    if (clearRequested) {
        AppDialog(
            onDismissRequest = { clearRequested = false },
            title = { Text(stringResource(R.string.dare_clear_feed_title)) },
            text = { Text(stringResource(R.string.dare_clear_feed_message)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        clearRequested = false
                        deletePosts()
                    },
                ) {
                    Text(
                        text = stringResource(R.string.action_clear_feed),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            },
            dismissButton = {
                TextButton(onClick = { clearRequested = false }) {
                    Text(
                        text = stringResource(R.string.action_cancel),
                        style = MaterialTheme.typography.labelMedium,
                    )
                }
            },
        )
    }

    if (!visible) return

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .windowInsetsPadding(
                WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal),
            )
            .padding(horizontal = 12.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AppHeader(
            title = stringResource(R.string.home_app_dare),
            subtitle = null,
            onBack = onBack,
            modifier = Modifier.weight(1f),
            titleMaxLines = 1,
        )
        Box {
            val menuLabel = stringResource(R.string.action_more_options)
            IconButton(
                onClick = { menuOpen = true },
                modifier = Modifier.semantics { contentDescription = menuLabel },
            ) {
                Icon(
                    painter = painterResource(R.drawable.ic_more),
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            DropdownMenu(
                expanded = menuOpen,
                onDismissRequest = { menuOpen = false },
                containerColor = SlateRaised,
            ) {
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.action_instructions), style = MaterialTheme.typography.labelMedium) },
                    onClick = {
                        menuOpen = false
                        settingsOpen = true
                    },
                )
                if (canClear) {
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.action_clear_feed), style = MaterialTheme.typography.labelMedium) },
                        onClick = {
                            menuOpen = false
                            clearRequested = true
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun DareController.DareFeed(
    actionsEnabled: Boolean,
    onBack: () -> Unit,
) {
    var deletePostRequested by remember { mutableStateOf<DarePost?>(null) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .windowInsetsPadding(WindowInsets.safeDrawing),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        DareHeader(
            visible = true,
            canClear = actionsEnabled && posts.isNotEmpty(),
            onBack = onBack,
        )

        DareGenerateBar(
            value = dareDraft,
            enabled = actionsEnabled && !loading,
            onValueChange = { updateDareDraft(it) },
            onClear = { clearDareDraft() },
            onGenerate = { startDare() },
        )

        LazyColumn(
            state = feedState,
            modifier = Modifier
                .widthIn(max = DARE_FEED_WIDTH)
                .fillMaxWidth()
                .weight(1f),
        ) {
            if (generation !is DareGeneration.Idle) {
                item(key = "generation") {
                    DareGenerationPanel(
                        generation = generation,
                        posts = posts,
                        onStop = ::stopGeneration,
                    )
                }
            }

            if (loading) {
                item(key = "loading") {
                    DareLoading()
                }
            } else if ((posts.isEmpty()) && (generation is DareGeneration.Idle)) {
                item(key = "empty") {
                    DareEmpty()
                }
            } else {
                items(posts, key = DarePost::id) { post ->
                    DarePostCard(
                        post = post,
                        character = characters.firstOrNull { it.id == post.characterId },
                        userProfile = profile,
                        actionsEnabled = actionsEnabled,
                        onDelete = { deletePostRequested = post },
                        onRegenerateImage = { regenerateImage(post) },
                    )
                }
            }
        }
    }

    deletePostRequested?.let { post ->
        AppDialog(
            onDismissRequest = { deletePostRequested = null },
            title = { Text(stringResource(R.string.dare_delete_post_title)) },
            text = { Text(stringResource(R.string.dare_delete_post_message)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        deletePostRequested = null
                        deletePosts(post)
                    },
                ) {
                    Text(
                        text = stringResource(R.string.action_delete),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            },
            dismissButton = {
                TextButton(onClick = { deletePostRequested = null }) {
                    Text(
                        text = stringResource(R.string.action_cancel),
                        style = MaterialTheme.typography.labelMedium,
                    )
                }
            },
        )
    }
}

@Composable
private fun DareController.DareSettingsScreen(onBack: () -> Unit) {
    val defaultPrompt = stringResource(R.string.dare_default_prompt)

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Slate)
            .windowInsetsPadding(WindowInsets.safeDrawing),
    ) {
        Column(
            modifier = Modifier.fillMaxSize(),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            AppHeader(
                title = stringResource(R.string.action_instructions),
                subtitle = null,
                onBack = onBack,
            )

            Column(
                modifier = Modifier
                    .widthIn(max = DARE_FEED_WIDTH)
                    .fillMaxWidth()
                    .padding(20.dp),
            ) {
                Text(
                    text = stringResource(R.string.dare_prompt_section),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 8.dp),
                )
                PostInput(
                    value = darePrompt,
                    hint = stringResource(R.string.dare_prompt_hint),
                    singleLine = false,
                    minLines = 4,
                    maxLines = 10,
                    onValueChange = {
                        darePrompt = it
                        preferences.edit { putString(KEY_DARE_PROMPT, it) }
                    },
                )
                TextButton(
                    onClick = {
                        darePrompt = defaultPrompt
                        preferences.edit { putString(KEY_DARE_PROMPT, defaultPrompt) }
                    },
                    modifier = Modifier.padding(top = 8.dp),
                ) {
                    Text(stringResource(R.string.action_reset_prompt))
                }
                CompactSwitchRow(
                    stringResource(R.string.chat_thinking),
                    thinking,
                    ::updateThinking,
                    Modifier.padding(top = 8.dp),
                )
            }
        }
    }
}
