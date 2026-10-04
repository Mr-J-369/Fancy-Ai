package com.mrj.fancyai.ui.settings

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
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
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.mrj.fancyai.R
import com.mrj.fancyai.engine.LocalLlmRuntime
import com.mrj.fancyai.service.llm.CloudProvider
import com.mrj.fancyai.ui.theme.Accent
import com.mrj.fancyai.ui.theme.AccentSoft
import com.mrj.fancyai.ui.theme.Ink

internal data class EngineStatusNames(
    val active: String?,
    val local: String?,
    val cloud: Map<CloudProvider, String?>,
    val activeCloud: CloudProvider?,
    val generationTarget: GenerationTarget?,
)

internal fun engineStatusNames(context: Context): EngineStatusNames {
    val local = LlmSettingsStore.selectedEngine(context)
    val cloud = CloudProvider.entries.associateWith { provider ->
        CloudSettingsStore.selectedCloudEngine(context, provider)?.name
    }
    val activeCloud = LlmSettingsStore.activeCloudProvider(context)
    val active = if (activeCloud == null) local?.name else cloud[activeCloud]
    val target = when {
        active == null -> null
        activeCloud != null -> GenerationTarget.CLOUD
        local?.model?.runtime == LocalLlmRuntime.LITERT -> GenerationTarget.LITERT
        local?.model?.runtime == LocalLlmRuntime.MNN -> GenerationTarget.MNN
        else -> GenerationTarget.LLAMA
    }
    return EngineStatusNames(active, local?.name, cloud, activeCloud, target)
}

@Composable
internal fun SettingsRow(
    title: String,
    summary: String,
    onClick: () -> Unit,
    status: String? = null,
    action: String? = null,
    active: Boolean? = null,
) {
    val activeDescription = stringResource(R.string.status_active).uppercase()
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 72.dp)
            .clickable(role = Role.Button, onClick = onClick)
            .semantics { if (active == true) stateDescription = activeDescription }
            .padding(vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .size(width = 2.dp, height = 32.dp)
                .background(
                    when (active) {
                        true -> Accent
                        false -> MaterialTheme.colorScheme.outline
                        null -> MaterialTheme.colorScheme.outline.copy(alpha = 0f)
                    },
                ),
        )
        Column(Modifier.weight(1f).padding(start = 12.dp, end = 10.dp)) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                color = if (active == true) AccentSoft else MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = summary,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 2.dp),
            )
            status?.let {
                Text(
                    text = it,
                    style = MaterialTheme.typography.labelSmall,
                    color = Accent,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
        }
        if (action == null) {
            Icon(
                painter = painterResource(R.drawable.ic_forward),
                contentDescription = null,
                tint = if (active == true) Accent else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(22.dp),
            )
        } else {
            Text(
                text = action,
                style = MaterialTheme.typography.labelMedium,
                color = if (active == true) Accent else MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
            )
        }
    }
}

@Composable
internal fun SettingsAction(
    text: String,
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .background(
                if (enabled) Accent else MaterialTheme.colorScheme.surfaceContainerHigh,
                MaterialTheme.shapes.medium,
            )
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelLarge,
            color = if (enabled) Ink else MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
internal fun SettingsModelRow(id: String, selected: Boolean, summary: String? = null, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .semantics { this.selected = selected }
            .clickable(role = Role.RadioButton, onClick = onClick)
            .padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = id,
                style = MaterialTheme.typography.bodyMedium,
                color = if (selected) AccentSoft else MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            if (!summary.isNullOrEmpty()) {
                Text(
                    text = summary,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 2.dp),
                )
            }
        }
        if (selected) {
            Text(
                text = stringResource(R.string.status_selected).uppercase(),
                style = MaterialTheme.typography.labelSmall,
                color = Accent,
                modifier = Modifier.padding(start = 12.dp),
            )
        }
    }
    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
}
