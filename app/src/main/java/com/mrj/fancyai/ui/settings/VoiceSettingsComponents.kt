package com.mrj.fancyai.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.mrj.fancyai.R
import com.mrj.fancyai.service.voice.CloudVoiceException
import com.mrj.fancyai.service.voice.CloudVoiceFailure
import com.mrj.fancyai.ui.kit.PostInput
import com.mrj.fancyai.ui.theme.Accent
import com.mrj.fancyai.ui.theme.AccentSoft

@Composable
internal fun voiceStatus(
    provider: VoiceProvider,
    providerLabel: String,
    apiKey: String,
    model: String,
    androidAvailable: Boolean,
): String = when {
    (provider == VoiceProvider.ANDROID) && androidAvailable ->
        stringResource(R.string.voice_service_available)
    provider == VoiceProvider.ANDROID -> stringResource(R.string.voice_service_unavailable)
    provider == VoiceProvider.LOCAL -> stringResource(R.string.voice_local_missing)
    apiKey.isBlank() -> stringResource(R.string.voice_cloud_key_missing, providerLabel)
    model.isBlank() -> stringResource(R.string.voice_cloud_model_missing, providerLabel)
    else -> stringResource(R.string.voice_cloud_ready, providerLabel)
}

@Composable
internal fun VoiceCapability(
    section: String,
    mark: String,
    title: String,
    summary: String,
    status: String,
    available: Boolean,
    feedback: String?,
    action: String,
    actionEnabled: Boolean,
    feedbackLabel: String? = null,
    onAction: () -> Unit,
    content: @Composable () -> Unit,
) {
    Text(
        text = section,
        style = MaterialTheme.typography.labelSmall,
        color = AccentSoft,
    )
    Spacer(Modifier.height(10.dp))
    Row(verticalAlignment = Alignment.Top) {
        Text(
            text = mark,
            style = MaterialTheme.typography.headlineSmall,
            color = Accent,
        )
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = summary,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp),
            )
        }
    }
    HorizontalDivider(
        modifier = Modifier.padding(top = 14.dp),
        color = MaterialTheme.colorScheme.outlineVariant,
    )
    Text(
        text = status,
        style = MaterialTheme.typography.labelSmall,
        color = if (available) Accent else MaterialTheme.colorScheme.error,
        modifier = Modifier.padding(top = 12.dp),
    )
    content()
    feedback?.let {
        feedbackLabel?.let { label ->
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                color = AccentSoft,
                modifier = Modifier.padding(top = 12.dp),
            )
        }
        Text(
            text = it,
            style = MaterialTheme.typography.bodySmall,
            color = if (available) {
                MaterialTheme.colorScheme.onSurface
            } else {
                MaterialTheme.colorScheme.error
            },
            modifier = Modifier.padding(top = if (feedbackLabel == null) 12.dp else 4.dp),
        )
    }
    SettingsAction(
        text = action,
        enabled = actionEnabled,
        onClick = onAction,
        modifier = Modifier.padding(top = 16.dp),
    )
}

@Composable
internal fun VoiceProviderSelector(
    selectedProvider: VoiceProvider,
    onSelect: (VoiceProvider) -> Unit,
) {
    Text(
        text = stringResource(R.string.voice_provider),
        style = MaterialTheme.typography.labelSmall,
        color = AccentSoft,
        modifier = Modifier.padding(top = 14.dp, bottom = 6.dp),
    )
    Row(
        Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(5.dp)),
    ) {
        VoiceProvider.entries.forEach { provider ->
            val selected = provider == selectedProvider
            Box(
                Modifier
                    .widthIn(min = 104.dp)
                    .heightIn(min = 48.dp)
                    .background(
                        if (selected) {
                            MaterialTheme.colorScheme.surfaceContainerHigh
                        } else {
                            MaterialTheme.colorScheme.surface.copy(alpha = 0.2f)
                        },
                    )
                    .semantics { this.selected = selected }
                    .clickable(role = Role.RadioButton) { onSelect(provider) }
                    .padding(horizontal = 6.dp, vertical = 14.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = stringResource(provider.label),
                    style = MaterialTheme.typography.labelSmall,
                    color = if (selected) Accent else MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
internal fun VoiceModelControl(model: String, onClick: () -> Unit) {
    Text(
        text = stringResource(R.string.label_model),
        style = MaterialTheme.typography.labelSmall,
        color = AccentSoft,
        modifier = Modifier.padding(top = 14.dp, bottom = 6.dp),
    )
    Row(
        Modifier
            .fillMaxWidth()
            .defaultMinSize(minHeight = 48.dp)
            .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(5.dp))
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = model.ifBlank { stringResource(R.string.voice_choose_model) },
            style = MaterialTheme.typography.bodyMedium,
            color = if (model.isBlank()) {
                MaterialTheme.colorScheme.onSurfaceVariant
            } else {
                MaterialTheme.colorScheme.onSurface
            },
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        Icon(painter = painterResource(R.drawable.ic_forward), contentDescription = null, tint = Accent, modifier = Modifier.padding(start = 12.dp).size(22.dp))
    }
}

@Composable
internal fun VoiceVoiceEditor(
    value: String,
    supported: List<String>,
    onValueChange: (String) -> Unit,
) {
    Text(
        stringResource(R.string.voice_voice_id),
        style = MaterialTheme.typography.labelSmall,
        color = AccentSoft,
        modifier = Modifier.padding(top = 14.dp),
    )
    PostInput(
        value = value,
        hint = stringResource(R.string.voice_voice_hint),
        singleLine = true,
        modifier = Modifier.padding(top = 6.dp),
        onValueChange = onValueChange,
    )
    Text(
        text = stringResource(R.string.voice_voice_summary),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = 6.dp),
    )
    if (supported.isNotEmpty()) {
        Text(
            text = stringResource(R.string.voice_supported_voices),
            style = MaterialTheme.typography.labelSmall,
            color = AccentSoft,
            modifier = Modifier.padding(top = 12.dp),
        )
        LazyRow(
            contentPadding = PaddingValues(top = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            items(supported, key = { it }) { voice ->
                val selected = voice == value
                Text(
                    text = voice,
                    style = MaterialTheme.typography.labelMedium,
                    color = if (selected) Accent else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier
                        .heightIn(min = 48.dp)
                        .border(
                            1.dp,
                            if (selected) Accent else MaterialTheme.colorScheme.outline,
                            RoundedCornerShape(5.dp),
                        )
                        .semantics { this.selected = selected }
                        .clickable(role = Role.RadioButton) { onValueChange(voice) }
                        .padding(horizontal = 12.dp, vertical = 14.dp),
                )
            }
        }
    }
}

internal fun voiceErrorResource(failure: Throwable, fallback: Int): Int =
    when ((failure as? CloudVoiceException)?.failure) {
        CloudVoiceFailure.AUTHENTICATION -> R.string.llm_error_authentication
        CloudVoiceFailure.RATE_LIMIT -> R.string.llm_error_rate_limit
        CloudVoiceFailure.NETWORK -> R.string.llm_error_network
        CloudVoiceFailure.PROVIDER -> R.string.voice_cloud_provider_failed
        CloudVoiceFailure.INVALID_RESPONSE -> R.string.voice_cloud_invalid_response
        null -> fallback
    }
