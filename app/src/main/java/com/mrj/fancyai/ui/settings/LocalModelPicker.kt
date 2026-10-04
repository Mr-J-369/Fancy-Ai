package com.mrj.fancyai.ui.settings

import android.text.format.Formatter
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import com.mrj.fancyai.ui.kit.AppDialog
import com.mrj.fancyai.ui.theme.Ink
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.mrj.fancyai.R
import com.mrj.fancyai.engine.LocalLlmModel
import com.mrj.fancyai.engine.LocalLlmRuntime
import com.mrj.fancyai.engine.MnnModels
import com.mrj.fancyai.ui.theme.Accent
import com.mrj.fancyai.ui.theme.AccentSoft
import java.io.File

@Composable
internal fun CpuThreadSetting(
    threads: Int,
    coreCount: Int,
    llama: Boolean,
    mnn: Boolean = false,
    onChanged: (Int) -> Unit,
) {
    SettingsStepper(
        title = stringResource(
            if (llama) R.string.engine_decode_threads else R.string.engine_cpu_threads,
        ),
        value = if (threads == 0) {
            "0"
        } else {
            pluralStringResource(R.plurals.settings_threads, threads, threads)
        },
        summary = stringResource(
            if (mnn) R.string.engines_mnn_threads_summary else if (llama) R.string.engines_decode_threads_summary else R.string.engines_cpu_threads_summary,
        ),
        onLower = (threads - 1).takeIf { threads > 0 }?.let { { onChanged(it) } },
        onHigher = (threads + 1).takeIf { llama || threads < coreCount }?.let { { onChanged(it) } },
        modifier = Modifier.padding(top = 16.dp),
    )
}

@Composable
internal fun ImportModelRow(enabled: Boolean, onClick: () -> Unit) {
    Surface(
        modifier = Modifier.fillMaxWidth().clickable(enabled = enabled, role = Role.Button, onClick = onClick),
        color = MaterialTheme.colorScheme.surfaceContainer,
        shape = MaterialTheme.shapes.medium,
        border = BorderStroke(1.dp, Accent.copy(alpha = 0.38f)),
    ) {
        Row(
            Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(painter = painterResource(R.drawable.ic_add), contentDescription = null, tint = Accent, modifier = Modifier.size(22.dp))
            Column(Modifier.weight(1f).padding(start = 12.dp)) {
                Text(
                    text = stringResource(R.string.engines_import_model),
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    text = stringResource(R.string.engines_import_model_summary),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 2.dp),
                )
            }
        }
    }
}

@Composable
internal fun BenchmarkShortcutRow(enabled: Boolean = true, onClick: () -> Unit) {
    Surface(
        modifier = Modifier.fillMaxWidth().clickable(enabled = enabled, role = Role.Button, onClick = onClick),
        color = MaterialTheme.colorScheme.surfaceContainer,
        shape = MaterialTheme.shapes.medium,
        border = BorderStroke(1.dp, Accent.copy(alpha = 0.38f)),
    ) {
        Row(
            Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                painter = painterResource(R.drawable.ic_benchmark),
                contentDescription = null,
                tint = Accent,
                modifier = Modifier.size(22.dp),
            )
            Column(Modifier.weight(1f).padding(start = 12.dp, end = 8.dp)) {
                Text(
                    text = stringResource(R.string.benchmark_title),
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    text = stringResource(R.string.benchmark_subtitle),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 2.dp),
                )
            }
            Icon(
                painter = painterResource(R.drawable.ic_forward),
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(18.dp),
            )
        }
    }
}

@Composable
internal fun BrowseModelsDialog(
    models: List<LocalLlmModel>,
    importingName: String?,
    importProgress: Float?,
    onDismiss: () -> Unit,
    onDownload: (StarterLlmModel) -> Unit,
    onStop: () -> Unit,
) {
    AppDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.engines_browse_models)) },
        dismissButton = null,
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.action_done), style = MaterialTheme.typography.labelMedium)
            }
        },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                LlmSettingsStore.starterModels.forEach { starter ->
                    val installed = models.any { it.name.equals(starter.fileName, ignoreCase = true) || it.path.endsWith(starter.fileName) }
                    val downloading = importingName == starter.name
                    BrowseModelCard(
                        model = starter,
                        installed = installed,
                        downloading = downloading,
                        downloadProgress = if (downloading) importProgress else null,
                        busy = importingName != null,
                        onDownload = { onDownload(starter) },
                        onStop = onStop,
                    )
                }
            }
        },
    )
}

@Composable
private fun BrowseModelCard(
    model: StarterLlmModel,
    installed: Boolean,
    downloading: Boolean,
    downloadProgress: Float?,
    busy: Boolean,
    onDownload: () -> Unit,
    onStop: () -> Unit,
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surfaceContainer,
        shape = MaterialTheme.shapes.medium,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
    ) {
        Column(
            Modifier.fillMaxWidth().padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Column {
                Text(
                    text = model.name,
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    text = stringResource(
                        R.string.two_part_meta,
                        stringResource(
                            when (model.runtime) {
                                LocalLlmRuntime.LITERT -> R.string.engines_litert
                                LocalLlmRuntime.LLAMA -> R.string.engines_gguf
                                LocalLlmRuntime.MNN -> R.string.engines_mnn
                            },
                        ),
                        Formatter.formatShortFileSize(LocalContext.current, model.sizeBytes),
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 2.dp),
                )
            }
            if (downloading) {
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = downloadProgress?.let { stringResource(R.string.aura_progress, (it * 100).toInt()) }
                            ?: stringResource(R.string.engines_downloading, model.name),
                        style = MaterialTheme.typography.labelMedium,
                        color = Accent,
                    )
                    TextButton(onClick = onStop) {
                        Text(
                            text = stringResource(R.string.action_stop),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                }
                if (downloadProgress == null) {
                    LinearProgressIndicator(
                        color = Accent,
                        trackColor = MaterialTheme.colorScheme.outline,
                        modifier = Modifier.fillMaxWidth().height(2.dp),
                    )
                } else {
                    LinearProgressIndicator(
                        progress = { downloadProgress.coerceIn(0f, 1f) },
                        color = Accent,
                        trackColor = MaterialTheme.colorScheme.outline,
                        modifier = Modifier.fillMaxWidth().height(2.dp),
                    )
                }
            } else if (installed) {
                Text(
                    text = stringResource(R.string.engines_installed),
                    style = MaterialTheme.typography.labelMedium,
                    color = AccentSoft,
                )
            } else {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    Button(
                        onClick = onDownload,
                        enabled = !busy,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = Accent,
                            contentColor = Ink,
                        ),
                    ) {
                        Icon(
                            painter = painterResource(R.drawable.ic_down),
                            contentDescription = null,
                            modifier = Modifier.size(18.dp),
                        )
                        Spacer(Modifier.width(6.dp))
                        Text(stringResource(R.string.engines_download))
                    }
                }
            }
        }
    }
}

@Composable
internal fun ImportProgressBanner(name: String, progress: Float?, onStop: (() -> Unit)? = null) {
    Column(
        Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceContainer)
            .padding(horizontal = 14.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                stringResource(
                    if (name.endsWith(".zip", ignoreCase = true)) R.string.model_extracting
                    else if (progress != null) R.string.engines_downloading
                    else R.string.model_copying,
                    name,
                ),
                style = MaterialTheme.typography.labelMedium,
                color = AccentSoft,
                modifier = Modifier.weight(1f),
            )
            progress?.let {
                Text(
                    stringResource(R.string.aura_progress, (it * 100).toInt()),
                    style = MaterialTheme.typography.labelMedium,
                    color = Accent,
                    modifier = Modifier.padding(end = 8.dp),
                )
            }
            if (onStop != null) {
                Text(
                    text = stringResource(R.string.action_stop),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier
                        .heightIn(min = 40.dp)
                        .clickable(role = Role.Button, onClick = onStop)
                        .padding(horizontal = 8.dp, vertical = 10.dp),
                )
            }
        }
        if (progress == null) {
            LinearProgressIndicator(
                color = Accent,
                trackColor = MaterialTheme.colorScheme.outline,
                modifier = Modifier.fillMaxWidth().height(2.dp),
            )
        } else {
            LinearProgressIndicator(
                progress = { progress.coerceIn(0f, 1f) },
                color = Accent,
                trackColor = MaterialTheme.colorScheme.outline,
                modifier = Modifier.fillMaxWidth().height(2.dp),
            )
        }
    }
}

@Composable
internal fun EmptyModels() {
    Column(
        Modifier
            .fillMaxWidth()
            .border(1.dp, MaterialTheme.colorScheme.outline, MaterialTheme.shapes.medium)
            .padding(horizontal = 14.dp, vertical = 14.dp),
    ) {
        Text(
            text = stringResource(R.string.engines_no_models),
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Text(
            text = stringResource(R.string.engines_no_models_summary),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 3.dp),
        )
    }
}

@Composable
internal fun ModelPicker(
    models: List<LocalLlmModel>,
    selectedPath: String?,
    active: Boolean,
    expanded: Boolean,
    onToggle: () -> Unit,
    onSelect: (LocalLlmModel) -> Unit,
    onRemove: (LocalLlmModel) -> Unit,
) {
    val selected = models.firstOrNull { it.path == selectedPath } ?: models.first()
    val stateLabel = stringResource(if (active) R.string.status_active else R.string.status_selected).uppercase()
    Column(Modifier.fillMaxWidth()) {
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(role = Role.Button, onClick = onToggle)
                .semantics { stateDescription = stateLabel },
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            shape = MaterialTheme.shapes.medium,
            border = BorderStroke(
                1.dp,
                if (active) Accent else MaterialTheme.colorScheme.outline,
            ),
        ) {
            Row(
                Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                ModelDescription(selected, Modifier.weight(1f))
                Text(
                    text = stateLabel,
                    style = MaterialTheme.typography.labelSmall,
                    color = if (active) Accent else MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Icon(painter = painterResource(if (expanded) R.drawable.ic_collapse else R.drawable.ic_expand), contentDescription = null, tint = if (active) Accent else MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(start = 10.dp).size(22.dp))
            }
        }
        if (expanded) {
            Column(Modifier.fillMaxWidth().padding(top = 4.dp).selectableGroup()) {
                models.forEachIndexed { index, model ->
                    val selectedModel = model.path == selected.path
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .selectable(
                                selected = selectedModel,
                                role = Role.RadioButton,
                            ) { onSelect(model) }
                            .padding(start = 12.dp, top = 6.dp, bottom = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        ModelDescription(model, Modifier.weight(1f))
                        if (selectedModel) {
                            Text(
                                text = stringResource(R.string.status_selected).uppercase(),
                                style = MaterialTheme.typography.labelSmall,
                                color = Accent,
                            )
                        }
                        Text(
                            text = stringResource(R.string.action_remove),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.error,
                            modifier = Modifier
                                .heightIn(min = 48.dp)
                                .clickable(role = Role.Button) { onRemove(model) }
                                .padding(horizontal = 10.dp, vertical = 16.dp),
                        )
                    }
                    if (index < models.lastIndex) {
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    }
                }
            }
        }
    }
}

@Composable
internal fun ModelDescription(model: LocalLlmModel, modifier: Modifier = Modifier) {
    Column(modifier) {
        Text(
            text = model.name,
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            text = stringResource(
                R.string.two_part_meta,
                stringResource(
                    when (model.runtime) {
                        LocalLlmRuntime.LITERT -> R.string.engines_litert
                        LocalLlmRuntime.LLAMA -> R.string.engines_gguf
                        LocalLlmRuntime.MNN -> R.string.engines_mnn
                    },
                ),
                Formatter.formatShortFileSize(LocalContext.current, if (model.runtime == LocalLlmRuntime.MNN) MnnModels.sizeBytes(model.path) else File(model.path).length()),
            ),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 2.dp),
        )
    }
}

@Composable
internal fun <T> RadioChoiceGroup(
    sectionTitleRes: Int? = null,
    sectionTitleText: String? = null,
    options: List<Triple<T, String, String>>,
    selected: T,
    onSelect: (T) -> Unit,
) {
    Column(Modifier.fillMaxWidth().selectableGroup().padding(top = 16.dp)) {
        if (sectionTitleRes != null) {
            Text(text = stringResource(sectionTitleRes), modifier = Modifier.padding(bottom = 6.dp), color = AccentSoft, style = MaterialTheme.typography.labelSmall)
        } else if (!sectionTitleText.isNullOrBlank()) {
            Text(
                text = sectionTitleText,
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurface,
            )
        }
        options.forEach { (key, title, summary) ->
            SettingsRow(
                title = title,
                summary = summary,
                onClick = { onSelect(key) },
                active = selected == key,
            )
        }
    }
}
