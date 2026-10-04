package com.mrj.fancyai.ui.phone

import androidx.annotation.StringRes
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.mrj.fancyai.R
import com.mrj.fancyai.ui.characters.CharacterCard
import com.mrj.fancyai.ui.kit.AppHeader
import com.mrj.fancyai.ui.kit.Artwork
import com.mrj.fancyai.ui.theme.Accent
import com.mrj.fancyai.ui.theme.Ink

@Composable
internal fun PhoneCallPipSurface(character: CharacterCard, phase: PhonePhase) {
    Box(Modifier.fillMaxSize().background(Ink)) {
        PhoneCallBackdrop(
            character,
            Brush.verticalGradient(
                0f to Ink.copy(alpha = 0.12f),
                0.48f to Color.Transparent,
                1f to Ink.copy(alpha = 0.94f),
            ),
        )
        Column(
            Modifier.align(Alignment.BottomStart).fillMaxWidth().padding(12.dp),
        ) {
            Text(
                text = character.name,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = stringResource(phase.label),
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.Bold,
                color = Accent,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 3.dp),
            )
        }
    }
}

@Composable
internal fun PhoneCallSurface(
    character: CharacterCard,
    phase: PhonePhase,
    muted: Boolean,
    heard: String,
    thoughtProcess: String,
    @StringRes error: Int,
    onBack: () -> Unit,
    onStart: () -> Unit,
    onMute: () -> Unit,
    onStop: () -> Unit,
    onEnd: () -> Unit,
) {
    Box(Modifier.fillMaxSize().background(Ink)) {
        PhoneCallBackdrop(
            character,
            Brush.verticalGradient(
                0f to Ink.copy(alpha = 0.38f),
                0.38f to Color.Transparent,
                0.72f to Ink.copy(alpha = 0.5f),
                1f to Ink.copy(alpha = 0.94f),
            ),
        )
        Column(
            Modifier
                .fillMaxSize()
                .windowInsetsPadding(WindowInsets.safeDrawing)
                .padding(horizontal = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            AppHeader(character.name, stringResource(R.string.phone_private_call), onBack)
            Spacer(Modifier.weight(1f))
            Text(
                text = stringResource(phase.label),
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.Bold,
                color = if (phase == PhonePhase.Error) MaterialTheme.colorScheme.error else Accent,
            )
            if (error != 0) {
                Text(
                    text = stringResource(error),
                    style = MaterialTheme.typography.bodyMedium,
                    textAlign = TextAlign.Center,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.padding(top = 8.dp).fillMaxWidth(0.9f),
                )
            } else if (heard.isNotBlank()) {
                Text(
                    text = heard,
                    style = MaterialTheme.typography.bodyMedium,
                    textAlign = TextAlign.Center,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 8.dp).fillMaxWidth(0.9f),
                )
            }
            Spacer(Modifier.heightIn(min = 20.dp))
            com.mrj.fancyai.ui.kit.ThoughtProcess(thoughtProcess, Modifier.heightIn(max = 240.dp).verticalScroll(rememberScrollState()))
            if ((phase == PhonePhase.Ready) || (phase == PhonePhase.Error)) {
                PhoneStartButton(
                    label = stringResource(
                        if (phase == PhonePhase.Error) R.string.action_try_again else R.string.phone_start_call,
                    ),
                    onClick = onStart,
                )
            } else {
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceEvenly,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    PhoneControl(
                        mark = if (muted) R.drawable.ic_mic_off else R.drawable.ic_mic,
                        label = stringResource(if (muted) R.string.phone_unmute else R.string.phone_mute),
                        active = muted,
                        onClick = onMute,
                    )
                    PhoneControl(
                        mark = R.drawable.ic_stop,
                        label = stringResource(R.string.phone_stop_voice),
                        enabled = phase == PhonePhase.Speaking,
                        onClick = onStop,
                    )
                    PhoneControl(
                        mark = R.drawable.ic_close,
                        label = stringResource(R.string.phone_end_call),
                        destructive = true,
                        onClick = onEnd,
                    )
                }
            }
            Spacer(Modifier.heightIn(min = 12.dp))
        }
    }
}

@Composable
private fun PhoneCallBackdrop(character: CharacterCard, gradient: Brush) {
    val hasBackground = (character.backgroundResource != 0) || (character.backgroundPath != null)
    Artwork(
        path = if (hasBackground) character.backgroundPath else character.avatarPath,
        resource = if (hasBackground) character.backgroundResource else character.avatarResource,
        contentDescription = null,
        modifier = Modifier.fillMaxSize(),
    )
    Box(Modifier.fillMaxSize().background(gradient))
}

@Composable
private fun PhoneStartButton(label: String, onClick: () -> Unit) {
    Text(
        text = label,
        style = MaterialTheme.typography.labelLarge,
        fontWeight = FontWeight.Bold,
        textAlign = TextAlign.Center,
        color = Ink,
        modifier = Modifier
            .fillMaxWidth(0.68f)
            .heightIn(min = 48.dp)
            .background(Accent, RoundedCornerShape(24.dp))
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = 20.dp, vertical = 15.dp),
    )
}

@Composable
private fun PhoneControl(
    mark: Int,
    label: String,
    enabled: Boolean = true,
    active: Boolean = false,
    destructive: Boolean = false,
    onClick: () -> Unit,
) {
    val background = when {
        destructive -> MaterialTheme.colorScheme.error
        active -> Accent
        else -> MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.86f)
    }
    val foreground = if (destructive || active) Ink else MaterialTheme.colorScheme.onSurface
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            Modifier
                .size(52.dp)
                .background(background.copy(alpha = if (enabled) 1f else 0.32f), CircleShape)
                .clickable(enabled = enabled, role = Role.Button, onClick = onClick),
            contentAlignment = Alignment.Center,
        ) {
            Icon(painter = painterResource(mark), contentDescription = null, tint = foreground.copy(alpha = if (enabled) 1f else 0.45f), modifier = Modifier.size(22.dp))
        }
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.padding(top = 6.dp),
        )
    }
}
