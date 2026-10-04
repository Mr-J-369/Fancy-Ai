package com.mrj.fancyai.ui.music

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import com.mrj.fancyai.R
import com.mrj.fancyai.ui.kit.AppDialog
import com.mrj.fancyai.ui.kit.AppHeader
import com.mrj.fancyai.ui.kit.PostInput
import com.mrj.fancyai.ui.theme.AccentSoft
import com.mrj.fancyai.ui.theme.Ink

@Composable
internal fun RootProducerScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val app = context.applicationContext
    val scope = rememberCoroutineScope()
    val controller = remember(app, scope) { RootProducerController(app, scope) }
    with(controller) {
        var deleteTrack by remember { mutableStateOf<RootMusicTrack?>(null) }
        var showExit by remember { mutableStateOf(value = false) }

        fun requestBack() {
            if (drafting || generating) showExit = true else onBack()
        }

        LaunchedEffect(controller) {
            controller.loadLibrary()
        }

        DisposableEffect(controller) {
            onDispose { controller.close() }
        }

        BackHandler(onBack = ::requestBack)

        Box(
            Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        0f to MaterialTheme.colorScheme.surface,
                        0.38f to Ink,
                        1f to MaterialTheme.colorScheme.background,
                    ),
                )
                .windowInsetsPadding(WindowInsets.safeDrawing)
                .imePadding(),
        ) {
            Column(Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
                AppHeader(
                    title = stringResource(R.string.home_app_root_producer),
                    subtitle = stringResource(R.string.producer_subtitle),
                    onBack = ::requestBack,
                )
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(top = 16.dp, bottom = 24.dp),
                ) {
                    item { IdeaComposer() }
                    item { com.mrj.fancyai.ui.kit.ThoughtProcess(thoughtProcess) }
                    imagePath?.let { path ->
                        item { com.mrj.fancyai.ui.kit.Artwork(path, modifier = Modifier.fillMaxWidth().heightIn(min = 300.dp), contentDescription = stringResource(R.string.aura_result_description)) }
                    }

                    if (planReady) {
                        item { PlanReview() }
                    }

                    val message = notice
                    if (message != null) item { ProducerMessage(message, ::dismissNotice) }

                    library(controller, onDelete = { deleteTrack = it })
                }
            }
        }

        deleteTrack?.let { track ->
            AppDialog(
                onDismissRequest = { deleteTrack = null },
                title = { Text(stringResource(R.string.producer_delete_title)) },
                text = { Text(stringResource(R.string.producer_delete_text, track.title)) },
                confirmButton = {
                    TextButton(
                        onClick = {
                            deleteTrack = null
                            controller.remove(track)
                        },
                    ) { Text(stringResource(R.string.action_remove), style = MaterialTheme.typography.labelMedium) }
                },
                dismissButton = {
                    TextButton(onClick = { deleteTrack = null }) {
                        Text(stringResource(R.string.action_cancel), style = MaterialTheme.typography.labelMedium)
                    }
                },
            )
        }

        if (showExit) {
            AppDialog(
                onDismissRequest = { showExit = false },
                title = { Text(stringResource(R.string.producer_leave_title)) },
                text = { Text(stringResource(R.string.producer_leave_text)) },
                confirmButton = {
                    TextButton(
                        onClick = {
                            showExit = false
                            controller.stopDraft()
                            controller.cancelGeneration()
                            onBack()
                        },
                    ) { Text(stringResource(R.string.producer_leave)) }
                },
                dismissButton = {
                    TextButton(onClick = { showExit = false }) {
                        Text(stringResource(R.string.action_cancel), style = MaterialTheme.typography.labelMedium)
                    }
                },
            )
        }
    }
}

@Composable
private fun RootProducerController.IdeaComposer() {
    val focus = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    Text(text = stringResource(R.string.producer_eyebrow), style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold, color = AccentSoft)
    Text(
        text = stringResource(R.string.producer_prompt),
        style = MaterialTheme.typography.titleLarge,
        color = MaterialTheme.colorScheme.onSurface,
        modifier = Modifier.padding(top = 6.dp),
    )
    Text(
        text = stringResource(R.string.producer_description),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = 4.dp).fillMaxWidth(0.92f),
    )
    PostInput(
        label = stringResource(R.string.producer_idea_label),
        hint = stringResource(R.string.producer_idea_hint),
        value = idea,
        enabled = !drafting,
        minLines = 3,
        modifier = Modifier.padding(top = 16.dp).fillMaxWidth(),
        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
        onValueChange = ::updateIdea,
    )
    ProducerAction(
        text = stringResource(
            when {
                drafting -> R.string.root_creator_stop
                planReady -> R.string.producer_rewrite
                else -> R.string.producer_ask
            },
        ),
        enabled = idea.isNotBlank() && !generating,
        active = drafting,
        onClick = {
            focus.clearFocus()
            keyboard?.hide()
            writePlan()
        },
        modifier = Modifier.padding(top = 8.dp),
    )
    Text(
        text = stringResource(R.string.producer_engine_note),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = 6.dp),
    )
}

@Composable
private fun RootProducerController.PlanReview() {
    val focus = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    val tierLabel = stringResource(when (tier) { RootMusicTier.CLIP -> R.string.producer_tier_clip; RootMusicTier.PRO -> R.string.producer_tier_pro })
    Spacer(Modifier.height(24.dp))
    HorizontalDivider(color = MaterialTheme.colorScheme.outline)
    Text(text = stringResource(R.string.producer_review_label), modifier = Modifier.padding(top = 16.dp), style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold, color = AccentSoft)
    Text(
        text = stringResource(R.string.producer_review_summary),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = 4.dp),
    )
    PostInput(
        label = stringResource(R.string.instructions_prompt_title),
        hint = stringResource(R.string.producer_title_hint),
        value = title,
        enabled = !generating,
        modifier = Modifier.padding(top = 16.dp).fillMaxWidth(),
        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
        singleLine = true,
        onValueChange = ::updateTitle,
    )
    PostInput(
        label = stringResource(R.string.producer_brief_label),
        hint = stringResource(R.string.producer_brief_hint),
        value = brief,
        enabled = !generating,
        minLines = 2,
        modifier = Modifier.padding(top = 16.dp).fillMaxWidth(),
        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
        onValueChange = ::updateBrief,
    )
    PostInput(
        label = stringResource(R.string.producer_lyrics_label),
        hint = stringResource(R.string.producer_lyrics_hint),
        value = lyrics,
        enabled = !generating,
        minLines = 4,
        modifier = Modifier.padding(top = 16.dp).fillMaxWidth(),
        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
        onValueChange = ::updateLyrics,
    )
    Text(text = stringResource(R.string.producer_version_label), modifier = Modifier.padding(top = 16.dp), style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold, color = AccentSoft)
    TierSelector(
        selected = tier,
        enabled = !generating,
        onSelect = ::updateTier,
    )
    Text(
        text = stringResource(
            R.string.producer_pricing_note,
            stringResource(R.string.cloud_openrouter),
            stringResource(tier.priceLabel),
            stringResource(R.string.producer_lyria),
            stringResource(R.string.producer_synthid),
        ),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = 6.dp),
    )
    if (openRouterKey.isBlank()) {
        Text(
            text = stringResource(
                R.string.producer_openrouter_required,
                stringResource(R.string.cloud_openrouter),
            ),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error,
            modifier = Modifier.padding(top = 6.dp),
        )
    }
    ProducerAction(
        text = if (generating) {
            stringResource(
                R.string.producer_creating,
                tierLabel,
            )
        } else {
            stringResource(
                R.string.producer_generate_tier,
                tierLabel,
                stringResource(tier.priceLabel),
            )
        },
        enabled = !drafting && title.isNotBlank() && brief.isNotBlank() &&
            openRouterKey.isNotBlank(),
        active = generating,
        onClick = {
            if (generating) cancelGeneration() else {
                focus.clearFocus()
                keyboard?.hide()
                generate()
            }
        },
        modifier = Modifier.padding(top = 8.dp),
    )
}

private fun androidx.compose.foundation.lazy.LazyListScope.library(
    controller: RootProducerController,
    onDelete: (RootMusicTrack) -> Unit,
) = with(controller) {
    item {
        Spacer(Modifier.height(24.dp))
        HorizontalDivider(color = MaterialTheme.colorScheme.outline)
        Row(
            Modifier.fillMaxWidth().padding(top = 16.dp, bottom = 8.dp),
            verticalAlignment = Alignment.Bottom,
        ) {
            Text(text = stringResource(R.string.producer_tracks_label), modifier = Modifier.weight(1f), style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold, color = AccentSoft)
            Text(
                text = if (libraryLoading) {
                    stringResource(R.string.producer_library_loading)
                } else {
                    pluralStringResource(
                        R.plurals.producer_track_count,
                        tracks.size,
                        tracks.size,
                    )
                },
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (!libraryLoading && tracks.isEmpty()) {
            Text(
                text = stringResource(R.string.producer_tracks_empty),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 2.dp, bottom = 16.dp),
            )
        }
    }

    items(tracks, key = RootMusicTrack::id) { track ->
        val active = activeTrackId == track.id
        TrackRow(
            track = track,
            active = active,
            playing = active && playbackPlaying,
            loading = active && playbackLoading,
            playback = controller,
            onPlay = { controller.play(track) },
            onSeek = { controller.seek(track.id, it) },
            onExport = { controller.export(track) },
        ) { onDelete(track) }
        Spacer(Modifier.height(8.dp))
    }
}
