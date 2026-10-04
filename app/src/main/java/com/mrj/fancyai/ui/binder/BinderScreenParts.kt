package com.mrj.fancyai.ui.binder

import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.mrj.fancyai.R
import com.mrj.fancyai.ui.kit.AppDialog
import com.mrj.fancyai.ui.kit.AppHeader
import com.mrj.fancyai.ui.theme.Accent
import com.mrj.fancyai.ui.theme.AccentSoft
import com.mrj.fancyai.ui.theme.Hairline
import com.mrj.fancyai.ui.theme.Ink
import com.mrj.fancyai.ui.theme.SlateRaised

@Composable
internal fun BinderScreenContent(
    controller: BinderController,
    requestBack: () -> Unit,
    snackbar: SnackbarHostState,
) {
    with(controller) {
        Box(
            Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        0f to SlateRaised,
                        0.32f to Ink,
                        1f to MaterialTheme.colorScheme.background,
                    ),
                ),
        ) {
            Column(
                Modifier.fillMaxSize().windowInsetsPadding(
                    WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal),
                ),
            ) {
                Text(
                    stringResource(R.string.binder_eyebrow),
                    style = MaterialTheme.typography.labelSmall,
                    color = AccentSoft,
                    modifier = Modifier.padding(start = 16.dp, top = 4.dp),
                )
                Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                    AppHeader(
                        title = stringResource(R.string.home_app_binder),
                        subtitle = if (sessionMatches > 0) {
                            pluralStringResource(R.plurals.binder_session_matches, sessionMatches, sessionMatches)
                        } else null,
                        onBack = requestBack,
                        modifier = Modifier.weight(1f),
                    )
                    if (preferences.configured && !generation.busy && !savingMatch && match == null && !editing) {
                        Text(
                            stringResource(R.string.binder_edit_preferences),
                            style = MaterialTheme.typography.labelSmall,
                            color = Accent,
                            modifier = Modifier
                                .heightIn(min = 48.dp)
                                .clickable(role = Role.Button) { editing = true }
                                .padding(start = 12.dp, top = 17.dp),
                        )
                    }
                }
                HorizontalDivider(color = Hairline)
                com.mrj.fancyai.ui.kit.ThoughtProcess(thoughtProcess, Modifier.heightIn(max = 240.dp).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp))
                val visibleMatch = match
                if (visibleMatch != null && !editing) {
                    BinderProfileCard(
                        visibleMatch,
                        saving = savingMatch,
                        generating = generation.busy,
                        onPass = ::pass,
                        onMatch = { saveMatch(visibleMatch) },
                    ) {
                        if (generation == BinderGeneration.Rendering) {
                            BinderWorking(
                                stringResource(R.string.chat_image_generating),
                                imageProgress,
                                ::stopGeneration,
                            )
                        }
                    }
                } else when (generation) {
                    BinderGeneration.Idle -> {
                        if (editing || !preferences.configured) {
                            BinderQuestionnaire(
                                value = preferences,
                                onChange = ::updatePreferences,
                                onFind = ::generateMatch,
                            )
                        } else {
                            BinderEmptyDeck(sessionMatches, ::generateMatch)
                        }
                    }
                    BinderGeneration.Rendering -> BinderWorking(
                        stringResource(R.string.chat_image_generating),
                        progress = imageProgress,
                        onStop = ::stopGeneration,
                    )
                    BinderGeneration.Writing -> BinderWorking(
                        stringResource(R.string.binder_writing_profile),
                        progress = null,
                        onStop = ::stopGeneration,
                    )
                }
            }
            SnackbarHost(
                snackbar,
                Modifier
                    .align(Alignment.BottomCenter)
                    .windowInsetsPadding(WindowInsets.safeDrawing),
            )
        }
    }
}

@Composable
internal fun BinderExitDialog(
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    AppDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.binder_exit_title), style = MaterialTheme.typography.titleLarge) },
        text = { Text(stringResource(R.string.binder_exit_message)) },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text(
                    stringResource(R.string.binder_exit_confirm),
                    color = MaterialTheme.colorScheme.error,
                )
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.action_cancel), style = MaterialTheme.typography.labelMedium)
            }
        },
    )
}
