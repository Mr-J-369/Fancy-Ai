package com.mrj.fancyai.ui.music

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.mrj.fancyai.R
import com.mrj.fancyai.ui.theme.Accent
import com.mrj.fancyai.ui.theme.AccentSoft
import java.util.Locale

@Composable
internal fun TierSelector(
    selected: RootMusicTier,
    enabled: Boolean,
    onSelect: (RootMusicTier) -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().selectableGroup().padding(top = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        RootMusicTier.entries.forEach { tier ->
            val active = selected == tier
            Column(
                Modifier
                    .weight(1f)
                    .defaultMinSize(minHeight = 80.dp)
                    .background(
                        if (active) Accent.copy(alpha = 0.13f) else MaterialTheme.colorScheme.surface.copy(alpha = 0.3f),
                        RoundedCornerShape(6.dp),
                    )
                    .border(
                        1.dp,
                        if (active) Accent else MaterialTheme.colorScheme.outline,
                        RoundedCornerShape(6.dp),
                    )
                    .selectable(
                        selected = active,
                        enabled = enabled,
                        role = Role.RadioButton,
                    ) { onSelect(tier) }
                    .padding(horizontal = 12.dp, vertical = 10.dp),
            ) {
                Text(
                    text = stringResource(when (tier) { RootMusicTier.CLIP -> R.string.producer_tier_clip; RootMusicTier.PRO -> R.string.producer_tier_pro }),
                    style = MaterialTheme.typography.titleMedium,
                    color = if (active) AccentSoft else MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    text = stringResource(when (tier) { RootMusicTier.CLIP -> R.string.producer_duration_clip; RootMusicTier.PRO -> R.string.producer_duration_pro }),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 3.dp),
                )
                Text(
                    text = stringResource(tier.priceLabel),
                    style = MaterialTheme.typography.labelMedium,
                    color = if (active) Accent else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
        }
    }
}

@Composable
internal fun ProducerAction(
    text: String,
    enabled: Boolean,
    active: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val available = enabled || active
    val color = if (available) Accent else MaterialTheme.colorScheme.outline
    Row(
        modifier
            .fillMaxWidth()
            .defaultMinSize(minHeight = 48.dp)
            .background(
                if (active) Accent.copy(alpha = 0.1f) else MaterialTheme.colorScheme.surface.copy(alpha = 0.24f),
                RoundedCornerShape(topStart = 4.dp, topEnd = 14.dp, bottomEnd = 14.dp, bottomStart = 4.dp),
            )
            .border(
                1.dp,
                color,
                RoundedCornerShape(topStart = 4.dp, topEnd = 14.dp, bottomEnd = 14.dp, bottomStart = 4.dp),
            )
            .clickable(enabled = available, role = Role.Button, onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.width(3.dp).height(20.dp).background(color))
        Text(
            text = text,
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.Bold,
            color = color,
            modifier = Modifier.padding(start = 12.dp).weight(1f),
        )
        Icon(painter = painterResource(if (active) R.drawable.ic_stop else R.drawable.ic_forward), contentDescription = null, tint = color, modifier = Modifier.size(22.dp))
    }
}

@Composable
internal fun ProducerMessage(text: String, onDismiss: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(top = 12.dp)
            .border(
                1.dp,
                Accent,
                RoundedCornerShape(6.dp),
            )
            .padding(start = 12.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f),
        )
        TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_done), style = MaterialTheme.typography.labelMedium) }
    }
}

@Composable
internal fun TrackRow(
    track: RootMusicTrack,
    active: Boolean,
    playing: Boolean,
    loading: Boolean,
    playback: RootProducerController,
    onPlay: () -> Unit,
    onSeek: (Long) -> Unit,
    onExport: () -> Unit,
    onDelete: () -> Unit,
) {
    val position = if (active) playback.playbackPosition else 0L
    val duration = if (active) playback.playbackDuration else 0L
    val recordedPrice = track.priceUsd.takeIf(String::isNotBlank)
        ?: stringResource(track.tier.priceLabel)
    Column(
        Modifier
            .fillMaxWidth()
            .background(
                if (active) Accent.copy(alpha = 0.08f) else MaterialTheme.colorScheme.surface.copy(alpha = 0.3f),
                RoundedCornerShape(7.dp),
            )
            .border(
                1.dp,
                if (active) Accent else MaterialTheme.colorScheme.outline,
                RoundedCornerShape(7.dp),
            )
            .padding(horizontal = 12.dp, vertical = 10.dp),
    ) {
        Text(
            text = track.title,
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            text = stringResource(
                R.string.producer_track_details,
                stringResource(when (track.tier) { RootMusicTier.CLIP -> R.string.producer_tier_clip; RootMusicTier.PRO -> R.string.producer_tier_pro }),
                recordedPrice,
                stringResource(when (track.format) { RootAudioFormat.MP3 -> R.string.producer_format_mp3; RootAudioFormat.WAV -> R.string.producer_format_wav; RootAudioFormat.UNKNOWN -> R.string.producer_format_bin }),
            ),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 2.dp),
        )
        if (active) {
            PlaybackTimeline(
                position = position,
                duration = duration,
                loading = loading,
                onSeek = onSeek,
            )
        }
        Row(
            Modifier.fillMaxWidth().padding(top = if (active) 4.dp else 8.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            TrackAction(
                text = stringResource(
                    when {
                        loading -> R.string.producer_loading
                        playing -> R.string.producer_pause
                        active && (duration > 0) && (position >= (duration - 250)) ->
                            R.string.producer_replay
                        else -> R.string.producer_play
                    },
                ),
                enabled = !loading,
                onClick = onPlay,
                modifier = Modifier.weight(1f),
            )
            TrackAction(
                text = stringResource(R.string.producer_save),
                enabled = true,
                onClick = onExport,
                modifier = Modifier.weight(1f),
            )
            TrackAction(
                text = stringResource(R.string.action_remove),
                enabled = true,
                danger = true,
                onClick = onDelete,
                modifier = Modifier.weight(1f),
            )
        }
    }
}

@Composable
internal fun PlaybackTimeline(
    position: Long,
    duration: Long,
    loading: Boolean,
    onSeek: (Long) -> Unit,
) {
    var seeking by remember { mutableStateOf(value = false) }
    var sliderPosition by remember { mutableFloatStateOf(position.toFloat()) }
    LaunchedEffect(position, duration, seeking) {
        if (!seeking) sliderPosition = position.coerceIn(0, duration).toFloat()
    }
    Slider(
        value = sliderPosition.coerceIn(0f, duration.coerceAtLeast(1).toFloat()),
        onValueChange = {
            seeking = true
            sliderPosition = it
        },
        onValueChangeFinished = {
            onSeek(sliderPosition.toLong())
            seeking = false
        },
        enabled = !loading && (duration > 0),
        valueRange = 0f..duration.coerceAtLeast(1).toFloat(),
        modifier = Modifier.fillMaxWidth().height(38.dp).padding(top = 8.dp),
    )
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(
            text = formatPlaybackTime(if (seeking) sliderPosition.toLong() else position),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = if (loading) stringResource(R.string.producer_loading) else formatPlaybackTime(duration),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
internal fun TrackAction(
    text: String,
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    danger: Boolean = false,
) {
    Box(
        modifier
            .heightIn(min = 48.dp)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .padding(horizontal = 2.dp, vertical = 12.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelMedium,
            color = when {
                !enabled -> MaterialTheme.colorScheme.outline
                danger -> MaterialTheme.colorScheme.error
                else -> Accent
            },
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

internal fun formatPlaybackTime(milliseconds: Long): String {
    val seconds = milliseconds.coerceAtLeast(0) / 1_000
    return String.format(Locale.ROOT, "%d:%02d", seconds / 60, seconds % 60)
}
