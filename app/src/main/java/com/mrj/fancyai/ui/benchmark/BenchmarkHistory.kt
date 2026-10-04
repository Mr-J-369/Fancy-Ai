package com.mrj.fancyai.ui.benchmark

import android.text.format.Formatter
import androidx.annotation.StringRes
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.mrj.fancyai.R
import com.mrj.fancyai.engine.LocalLlmRuntime
import com.mrj.fancyai.ui.kit.UiSymbols
import com.mrj.fancyai.ui.theme.Accent
import com.mrj.fancyai.ui.theme.AccentDeep
import com.mrj.fancyai.ui.theme.AccentSoft
import com.mrj.fancyai.ui.theme.Danger
import com.mrj.fancyai.ui.theme.Hairline
import com.mrj.fancyai.ui.theme.Ink
import java.text.DateFormat
import java.util.Date

@Composable
internal fun Header(onBack: () -> Unit) {
    val back = stringResource(R.string.settings_back)
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
        Box(
            Modifier.size(48.dp).clickable(role = Role.Button, onClick = onBack).semantics {
                contentDescription = back
            },
            contentAlignment = Alignment.CenterStart,
        ) {
            Icon(painter = painterResource(R.drawable.ic_back), contentDescription = null, tint = AccentSoft, modifier = Modifier.size(22.dp))
        }
        Column(Modifier.weight(1f).padding(top = 2.dp)) {
            Text(text = stringResource(R.string.benchmark_eyebrow), color = Accent, style = MaterialTheme.typography.labelSmall)
            Text(stringResource(R.string.benchmark_title), style = MaterialTheme.typography.titleLarge)
            Text(
                stringResource(R.string.benchmark_subtitle),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 2.dp, bottom = 12.dp),
            )
        }
    }
}

@Composable
internal fun SectionMark(@StringRes index: Int, @StringRes label: Int) {
    Row(Modifier.padding(top = 8.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(text = stringResource(index), color = Accent, style = MaterialTheme.typography.labelSmall)
        Box(Modifier.padding(horizontal = 8.dp).width(24.dp).height(1.dp).background(Hairline))
        Text(text = stringResource(label), color = AccentSoft, style = MaterialTheme.typography.labelSmall)
    }
}

@Composable
internal fun Disclosure(open: Boolean, closedLabel: String, openLabel: String, onClick: () -> Unit) {
    val state = stringResource(
        if (open) R.string.benchmark_collapse_evidence_description
        else R.string.benchmark_expand_evidence_description,
    )
    Row(
        Modifier.fillMaxWidth().heightIn(min = 48.dp).clickable(role = Role.Button, onClick = onClick)
            .semantics { stateDescription = state }
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(if (open) openLabel else closedLabel, style = MaterialTheme.typography.labelLarge,
            color = AccentSoft, modifier = Modifier.weight(1f))
        Icon(painter = painterResource(if (open) R.drawable.ic_collapse else R.drawable.ic_expand), contentDescription = null, tint = Accent, modifier = Modifier.size(22.dp))
    }
}

@Composable
internal fun TextAction(@StringRes label: Int, color: Color, onClick: () -> Unit) {
    Text(
        stringResource(label), style = MaterialTheme.typography.labelSmall, color = color,
        modifier = Modifier.heightIn(min = 48.dp).clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 16.dp),
    )
}

internal fun LazyListScope.benchmarkHistory(
    session: BenchmarkSession,
    groups: Map<String, List<BenchmarkRun>>,
    onCompare: () -> Unit,
    onClear: () -> Unit,
    onDelete: (BenchmarkRun) -> Unit,
) {
    with(session) {
        item(key = "benchmark_history_header") {
            SectionMark(R.string.benchmark_section_archive, R.string.benchmark_history_label)
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Bottom) {
                Column(Modifier.weight(1f)) {
                    Text(stringResource(R.string.benchmark_history_title), style = MaterialTheme.typography.titleMedium)
                    Text(text = pluralStringResource(
                            R.plurals.benchmark_history_run_count,
                            runs.size,
                            runs.size,
                        ), modifier = Modifier.padding(top = 1.dp), color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
                }
                if (runs.isNotEmpty()) TextAction(R.string.benchmark_clear_history, Danger, onClear)
            }
            if (comparison.isNotEmpty()) {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(top = 8.dp, bottom = 4.dp)
                        .background(AccentDeep.copy(alpha = 0.45f))
                        .border(1.dp, Accent.copy(alpha = 0.4f))
                        .padding(start = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(text = pluralStringResource(
                            R.plurals.benchmark_compare_selected_count,
                            comparison.size,
                            comparison.size,
                        ), modifier = Modifier.weight(1f), color = AccentSoft, style = MaterialTheme.typography.bodySmall)
                    if (comparison.size == 2) TextAction(R.string.benchmark_compare, Accent, onCompare)
                    else Text(text = stringResource(R.string.benchmark_select_one_more), modifier = Modifier.padding(12.dp), color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.labelSmall)
                }
            }
            Spacer(Modifier.height(8.dp))
        }
        if (loaded && runs.isEmpty()) {
            item {
                Column(Modifier.fillMaxWidth().border(1.dp, Hairline).padding(16.dp)) {
                    Text(stringResource(R.string.benchmark_history_empty_title), style = MaterialTheme.typography.titleMedium)
                    Text(text = stringResource(R.string.benchmark_history_empty_summary), modifier = Modifier.padding(top = 3.dp), color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
                }
            }
        }
        groups.forEach { (key, group) ->
            val open = key in openGroups.orEmpty()
            item(key = "group:$key") {
                ModelChapter(group, open) {
                    openGroups = if (open) openGroups.orEmpty() - key else openGroups.orEmpty() + key
                }
            }
            if (open) {
                items(group, key = BenchmarkRun::id) { run ->
                    val context = LocalContext.current
                    val slot = comparison.indexOf(run.id)
                    HistoryRun(
                        run = run,
                        expanded = expandedRun == run.id,
                        comparisonSlot = slot.takeIf { it >= 0 },
                        comparisonEnabled = (slot >= 0) || (comparison.size < 2),
                        onComparison = {
                            comparison = if (slot >= 0) comparison - run.id else comparison + run.id
                        },
                        onExpand = {
                            expandedRun = run.id.takeUnless { expandedRun == run.id }
                        },
                        onShare = { shareRun(context, run) },
                    ) { onDelete(run) }
                }
            }
        }
    }
}

@Composable
internal fun ModelChapter(runs: List<BenchmarkRun>, open: Boolean, onToggle: () -> Unit) {
    val context = LocalContext.current
    val newest = runs.first()
    val state = stringResource(
        if (open) R.string.benchmark_collapse_model_description
        else R.string.benchmark_expand_model_description,
    )
    Row(
        Modifier.fillMaxWidth().clickable(role = Role.Button, onClick = onToggle)
            .semantics { stateDescription = state }.padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(painter = painterResource(if (open) R.drawable.ic_collapse else R.drawable.ic_expand), contentDescription = null, tint = Accent, modifier = Modifier.width(24.dp).size(22.dp))
        Column(Modifier.weight(1f)) {
            Text(
                newest.configuration.modelName,
                style = MaterialTheme.typography.titleSmall,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Text(text = listOf(
                    context.runtimeName(newest.configuration.runtime),
                    Formatter.formatShortFileSize(context, newest.configuration.modelSizeBytes),
                    pluralStringResource(R.plurals.benchmark_history_run_count, runs.size, runs.size),
                ).joinToString(UiSymbols.META_SEPARATOR), modifier = Modifier.padding(top = 2.dp), color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
        }
        Column(horizontalAlignment = Alignment.End) {
            Text(
                runs.filter { it.passes.isNotEmpty() }.maxOfOrNull { run -> run.passes.map { it.decodeTokensPerSecond }.average() }?.let { stringResource(R.string.benchmark_rate_value, it) } ?: UiSymbols.UNAVAILABLE,
                style = MaterialTheme.typography.titleSmall.copy(fontFeatureSettings = "tnum"),
                color = AccentSoft,
            )
            Text(text = stringResource(R.string.benchmark_best_label), color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.labelSmall)
        }
    }
    HorizontalDivider(color = Hairline)
}

@Composable
internal fun HistoryRun(
    run: BenchmarkRun,
    expanded: Boolean,
    comparisonSlot: Int?,
    comparisonEnabled: Boolean,
    onComparison: () -> Unit,
    onExpand: () -> Unit,
    onShare: () -> Unit,
    onDelete: () -> Unit,
) {
    val context = LocalContext.current
    val compareDescription = when (comparisonSlot) {
        0 -> stringResource(R.string.benchmark_remove_run_a)
        1 -> stringResource(R.string.benchmark_remove_run_b)
        else -> stringResource(
            if (comparisonEnabled) R.string.benchmark_add_compare_description
            else R.string.benchmark_compare_full_description,
        )
    }
    val expandDescription = stringResource(
        if (expanded) R.string.benchmark_collapse_evidence_description
        else R.string.benchmark_expand_evidence_description,
    )
    Column(
        Modifier.fillMaxWidth()
            .background(if (comparisonSlot != null) AccentDeep.copy(alpha = 0.2f) else Color.Transparent),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier.size(48.dp)
                    .clickable(enabled = comparisonEnabled, role = Role.Checkbox, onClick = onComparison)
                    .semantics {
                        selected = comparisonSlot != null
                        contentDescription = compareDescription
                        if (!comparisonEnabled) disabled()
                    },
                contentAlignment = Alignment.Center,
            ) {
                Box(
                    Modifier.size(22.dp)
                        .border(1.dp, if (comparisonSlot != null) Accent else Hairline)
                        .background(if (comparisonSlot != null) Accent else Color.Transparent),
                    contentAlignment = Alignment.Center,
                ) {
                    if (comparisonSlot == null) Icon(painterResource(R.drawable.ic_add), contentDescription = null,
                        tint = AccentSoft, modifier = Modifier.size(16.dp))
                    else Text(stringResource(if (comparisonSlot == 0) R.string.benchmark_compare_a else R.string.benchmark_compare_b),
                        style = MaterialTheme.typography.labelSmall, color = Ink)
                }
            }
            Row(
                Modifier.weight(1f).clickable(role = Role.Button, onClick = onExpand)
                    .semantics { stateDescription = expandDescription }
                    .padding(vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(run.createdAtEpochMilliseconds)), style = MaterialTheme.typography.titleSmall)
                    Text(text = listOf(context.backendName(run.configuration.backend), stringResource(R.string.benchmark_test_value, run.promptTargetTokens, run.outputTokenLimit, run.repetitions))
                            .joinToString(UiSymbols.META_SEPARATOR), modifier = Modifier.padding(top = 2.dp), color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
                }
                Text(
                    if (run.passes.isEmpty()) UiSymbols.UNAVAILABLE else stringResource(R.string.benchmark_rate_value, run.passes.map { it.decodeTokensPerSecond }.average()),
                    style = MaterialTheme.typography.titleMedium.copy(fontFeatureSettings = "tnum"),
                    color = AccentSoft,
                )
                Icon(painter = painterResource(if (expanded) R.drawable.ic_collapse else R.drawable.ic_expand), contentDescription = null, tint = Accent, modifier = Modifier.padding(horizontal = 8.dp).size(22.dp))
            }
        }
        AnimatedVisibility(expanded) {
            Column(Modifier.padding(start = 48.dp).background(Ink.copy(alpha = 0.42f)).padding(12.dp)) {
                FactSection(R.string.benchmark_result_label, summaryFacts(context, run))
                RunEvidence(run)
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    TextAction(R.string.benchmark_share_run, Accent, onShare)
                    TextAction(R.string.benchmark_delete_run, Danger, onDelete)
                }
            }
        }
    }
    HorizontalDivider(color = Hairline)
}

@Composable
internal fun RunEvidence(run: BenchmarkRun) {
    val context = LocalContext.current
    val (engine, sampling) = configurationFacts(context, run.configuration)
    Column(Modifier.fillMaxWidth()) {
        FactSection(
            R.string.benchmark_run_evidence_label,
            listOf(
                Fact(R.string.label_model, run.configuration.modelName),
                Fact(
                    R.string.benchmark_model_size,
                    Formatter.formatShortFileSize(context, run.configuration.modelSizeBytes),
                ),
                Fact(R.string.benchmark_device, run.configuration.deviceModel),
                Fact(R.string.benchmark_test_label, stringResource(R.string.benchmark_test_value, run.promptTargetTokens, run.outputTokenLimit, run.repetitions)),
                Fact(
                    R.string.benchmark_prompt_tokens_by_pass,
                    run.passes.joinToString(UiSymbols.VALUE_SEPARATOR) {
                        it.promptTokenCount.toString()
                    },
                ),
                Fact(
                    R.string.benchmark_output_tokens_by_pass,
                    run.passes.joinToString(UiSymbols.VALUE_SEPARATOR) {
                        it.generatedTokenCount.toString()
                    },
                ),
            ),
        )
        FactSection(R.string.benchmark_engine_inputs_label, engine)
        if (run.configuration.runtime != LocalLlmRuntime.LLAMA) FactSection(R.string.benchmark_sampler_inputs_label, sampling)
        run.passes.forEach { pass ->
            FactSection(
                title = R.string.benchmark_pass_title,
                titleArgument = pass.number,
                facts = passFacts(context, pass),
            )
        }
    }
}

@Composable
internal fun ConfigurationEvidence(configuration: BenchmarkConfiguration) {
    val context = LocalContext.current
    val (engine, sampling) = configurationFacts(context, configuration)
    Column(Modifier.fillMaxWidth().background(Ink.copy(alpha = 0.42f)).padding(12.dp)) {
        FactSection(R.string.benchmark_engine_inputs_label, engine)
        if (configuration.runtime != LocalLlmRuntime.LLAMA) FactSection(R.string.benchmark_sampler_inputs_label, sampling)
    }
}

@Composable
internal fun FactSection(
    @StringRes title: Int,
    facts: List<Fact>,
    titleArgument: Int? = null,
) {
    val sectionTitle = titleArgument?.let { argument ->
        stringResource(title, argument)
    } ?: stringResource(title)
    Text(text = sectionTitle, modifier = Modifier.padding(top = 8.dp, bottom = 3.dp), color = AccentSoft, style = MaterialTheme.typography.labelSmall)
    facts.forEach { fact ->
        Row(Modifier.fillMaxWidth().padding(vertical = 3.dp), verticalAlignment = Alignment.Top) {
            Text(text = stringResource(fact.label), modifier = Modifier.weight(0.9f).padding(end = 10.dp), color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
            Text(
                fact.value,
                style = MaterialTheme.typography.bodySmall.copy(fontFeatureSettings = "tnum"),
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.weight(1.2f),
            )
        }
    }
}
