package com.mrj.fancyai.ui.benchmark

import android.text.format.Formatter
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.mrj.fancyai.R
import com.mrj.fancyai.engine.LocalLlmModel
import com.mrj.fancyai.ui.kit.UiSymbols
import com.mrj.fancyai.ui.theme.Accent
import com.mrj.fancyai.ui.theme.AccentSoft
import com.mrj.fancyai.ui.theme.Danger
import com.mrj.fancyai.ui.theme.Hairline
import com.mrj.fancyai.ui.theme.SlateRaised

@Composable
internal fun SetupPanel(
    model: LocalLlmModel?,
    configuration: BenchmarkConfiguration?,
    repetitions: Int,
    loaded: Boolean,
    running: Boolean,
    onModels: () -> Unit,
    onRepetitions: (Int) -> Unit,
    onRun: () -> Unit,
    onStop: () -> Unit,
) {
    var inputsOpen by rememberSaveable { mutableStateOf(value = false) }
    Surface(
        color = SlateRaised.copy(alpha = 0.95f),
        shape = RoundedCornerShape(topEnd = 24.dp, bottomStart = 24.dp),
        border = BorderStroke(1.dp, Hairline),
    ) {
        Column {
            ModelPlate(model, configuration, loaded, !running, onModels)
            if (configuration != null) {
                HorizontalDivider(color = Hairline)
                Disclosure(
                    open = inputsOpen,
                    closedLabel = stringResource(R.string.benchmark_show_inputs),
                    openLabel = stringResource(R.string.benchmark_hide_inputs),
                ) { inputsOpen = !inputsOpen }
                AnimatedVisibility(inputsOpen) { ConfigurationEvidence(configuration) }
            }
            HorizontalDivider(color = Hairline)
            Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
                Text(text = stringResource(R.string.benchmark_repeat_label), color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.labelSmall)
                RepetitionSelector(repetitions, !running, onRepetitions)
                Text(text = stringResource(
                        R.string.benchmark_protocol_summary,
                        BENCHMARK_PROMPT_TARGET_TOKENS,
                        BENCHMARK_OUTPUT_TOKENS,
                    ), modifier = Modifier.padding(top = 8.dp), color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
            }
            RunAction(running, model != null, onRun, onStop)
        }
    }
}

@Composable
private fun ModelPlate(
    model: LocalLlmModel?,
    configuration: BenchmarkConfiguration?,
    loaded: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    val description = stringResource(R.string.benchmark_choose_model_description)
    Row(
        Modifier.fillMaxWidth().clickable(enabled = enabled && loaded, role = Role.Button, onClick = onClick)
            .semantics { contentDescription = description }.padding(16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.width(3.dp).height(44.dp).background(if (model == null) Hairline else Accent))
        Column(Modifier.weight(1f).padding(start = 12.dp)) {
            Text(text = stringResource(R.string.benchmark_model_label), color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.labelSmall)
            Text(
                when {
                    !loaded -> stringResource(R.string.benchmark_models_loading)
                    model == null -> stringResource(R.string.engines_no_models)
                    else -> model.name
                },
                style = MaterialTheme.typography.titleMedium,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 3.dp),
            )
            if (model != null) {
                val context = LocalContext.current
                Text(text = listOfNotNull(
                        context.runtimeName(model.runtime),
                        configuration?.backend?.let(context::backendName),
                        Formatter.formatShortFileSize(context, model.sizeBytes),
                    ).joinToString(UiSymbols.META_SEPARATOR), modifier = Modifier.padding(top = 2.dp), color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
            }
        }
        if (model != null) {
            Text(text = stringResource(R.string.benchmark_change_model), modifier = Modifier.padding(start = 8.dp), color = Accent, style = MaterialTheme.typography.labelSmall)
        }
    }
}

@Composable
private fun RepetitionSelector(selected: Int, enabled: Boolean, onSelect: (Int) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(top = 8.dp).selectableGroup()) {
        BENCHMARK_REPETITION_CHOICES.forEach { count ->
            val active = count == selected
            Box(
                Modifier.weight(1f).heightIn(min = 48.dp)
                    .selectable(active, enabled, Role.RadioButton) { onSelect(count) }
                    .border(1.dp, if (active) Accent else Hairline),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    if (count == 1) stringResource(R.string.benchmark_once)
                    else pluralStringResource(R.plurals.benchmark_times, count, count),
                    style = MaterialTheme.typography.labelLarge,
                    color = if (active) AccentSoft else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun RunAction(
    running: Boolean,
    enabled: Boolean,
    onRun: () -> Unit,
    onStop: () -> Unit,
) {
    val description = stringResource(
        if (running) R.string.benchmark_stop_description else R.string.benchmark_run_description,
    )
    Row(
        Modifier.fillMaxWidth().heightIn(min = 56.dp)
            .background(if (running) Danger.copy(alpha = 0.1f) else Accent.copy(alpha = 0.12f))
            .clickable(
                enabled = running || enabled,
                role = Role.Button,
                onClick = if (running) onStop else onRun,
            )
            .semantics { contentDescription = description }
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            stringResource(if (running) R.string.benchmark_stop else R.string.benchmark_run),
            style = MaterialTheme.typography.labelLarge,
            color = if (running) Danger else if (enabled) AccentSoft else Hairline,
            modifier = Modifier.weight(1f),
        )
        Icon(painter = painterResource(if (running) R.drawable.ic_close else R.drawable.ic_right), contentDescription = null, tint = if (running) Danger else Accent, modifier = Modifier.size(22.dp))
    }
}

@Composable
internal fun ProgressPanel(progress: BenchmarkProgress) {
    val total = (progress.repetitions + 2).coerceAtLeast(1)
    val stage = when (progress.phase) {
        BenchmarkPhase.RESETTING -> 0
        BenchmarkPhase.LOADING -> 1
        BenchmarkPhase.WARMING -> 2
        BenchmarkPhase.PASS -> 2 + progress.completedPasses
        BenchmarkPhase.IDLE -> 0
    }
    Row(
        Modifier.fillMaxWidth().padding(top = 8.dp).border(1.dp, Hairline).padding(12.dp),
        verticalAlignment = Alignment.Bottom,
    ) {
        Column(Modifier.weight(1f)) {
            Text(progressTitle(progress), style = MaterialTheme.typography.titleSmall)
            Text(text = progressSubtitle(progress), modifier = Modifier.padding(top = 3.dp), color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
            LinearProgressIndicator(
                progress = { (stage.toFloat() / total).coerceIn(0f, 1f) },
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp).height(2.dp),
                color = Accent,
                trackColor = Hairline,
            )
        }
        progress.latestDecodeTokensPerSecond?.let { rate ->
            Text(
                stringResource(R.string.benchmark_rate_value, rate),
                style = MaterialTheme.typography.titleSmall,
                color = AccentSoft,
                modifier = Modifier.padding(start = 12.dp),
            )
        }
    }
}

@Composable
private fun progressTitle(progress: BenchmarkProgress): String = when (progress.phase) {
    BenchmarkPhase.RESETTING -> stringResource(R.string.benchmark_resetting)
    BenchmarkPhase.LOADING -> stringResource(R.string.benchmark_loading)
    BenchmarkPhase.WARMING -> stringResource(R.string.benchmark_warming)
    BenchmarkPhase.PASS -> stringResource(R.string.benchmark_running_pass, progress.pass, progress.repetitions)
    BenchmarkPhase.IDLE -> stringResource(R.string.status_ready)
}

@Composable
private fun progressSubtitle(progress: BenchmarkProgress): String = when (progress.phase) {
    BenchmarkPhase.RESETTING -> stringResource(R.string.benchmark_resetting_summary)
    BenchmarkPhase.LOADING -> stringResource(R.string.benchmark_loading_summary)
    BenchmarkPhase.WARMING -> stringResource(R.string.benchmark_warming_summary)
    BenchmarkPhase.PASS -> pluralStringResource(
        R.plurals.benchmark_pass_progress,
        progress.repetitions,
        progress.completedPasses,
        progress.repetitions,
    )
    BenchmarkPhase.IDLE -> stringResource(R.string.benchmark_ready_summary)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ModelPicker(
    models: List<LocalLlmModel>,
    selectedPath: String?,
    onSelect: (LocalLlmModel) -> Unit,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = SlateRaised,
    ) {
        Column(Modifier.fillMaxWidth().navigationBarsPadding()) {
            Text(stringResource(R.string.benchmark_model_sheet_title), style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(horizontal = 16.dp))
            Text(text = stringResource(R.string.benchmark_model_sheet_summary), modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp), color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
            if (models.isEmpty()) {
                Text(stringResource(R.string.benchmark_no_models_summary), style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(16.dp))
            } else {
                LazyColumn(
                    Modifier.fillMaxWidth().heightIn(max = 620.dp).selectableGroup(),
                    contentPadding = PaddingValues(bottom = 16.dp),
                ) {
                    items(models, key = LocalLlmModel::path) { model ->
                        val selected = model.path == selectedPath
                        val context = LocalContext.current
                        Row(
                            Modifier.fillMaxWidth()
                                .selectable(selected, role = Role.RadioButton) { onSelect(model) }
                                .padding(horizontal = 16.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Box(
                                Modifier.width(3.dp).height(40.dp)
                                    .background(if (selected) Accent else Hairline),
                            )
                            Column(Modifier.weight(1f).padding(start = 12.dp)) {
                                Text(
                                    model.name,
                                    style = MaterialTheme.typography.titleSmall,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                Text(text = listOf(context.runtimeName(model.runtime),
                                    Formatter.formatShortFileSize(context, model.sizeBytes))
                                    .joinToString(UiSymbols.META_SEPARATOR), modifier = Modifier.padding(top = 2.dp), color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
                            }
                            if (selected) Text(text = stringResource(R.string.status_selected).uppercase(), color = Accent, style = MaterialTheme.typography.labelSmall)
                        }
                        HorizontalDivider(color = Hairline)
                    }
                }
            }
        }
    }
}
