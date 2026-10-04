package com.mrj.fancyai.ui.benchmark

import android.content.Context
import android.text.format.Formatter
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.ModalBottomSheetProperties
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogWindowProvider
import com.mrj.fancyai.R
import com.mrj.fancyai.engine.LocalLlmRuntime
import com.mrj.fancyai.ui.kit.UiSymbols
import com.mrj.fancyai.ui.theme.Accent
import com.mrj.fancyai.ui.theme.AccentSoft
import com.mrj.fancyai.ui.theme.Danger
import com.mrj.fancyai.ui.theme.Hairline
import com.mrj.fancyai.ui.theme.SlateRaised
import java.text.DateFormat
import java.util.Date
import kotlin.math.abs

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ComparisonSheet(first: BenchmarkRun, second: BenchmarkRun, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val differences = remember(first, second) { configurationDifferences(context, first.configuration, second.configuration) }
    val metrics = remember(first, second) { comparisonMetrics(context, first, second) }
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = SlateRaised,
        properties = ModalBottomSheetProperties(
            isAppearanceLightStatusBars = false,
            isAppearanceLightNavigationBars = false,
        ),
    ) {
        val window = (LocalView.current.parent as? DialogWindowProvider)?.window
        LaunchedEffect(window) {
            window?.isNavigationBarContrastEnforced = false
        }
        LazyColumn(
            Modifier.fillMaxWidth(),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 24.dp),
        ) {
            item(key = "comparison_header") {
                Text(stringResource(R.string.benchmark_compare_title), style = MaterialTheme.typography.titleMedium)
                Text(text = stringResource(R.string.benchmark_compare_summary), modifier = Modifier.padding(top = 3.dp, bottom = 12.dp), color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
                CompareIdentities(first, second)
                HorizontalDivider(color = Hairline, modifier = Modifier.padding(vertical = 8.dp))
            }
            items(metrics, key = ComparisonMetric::label) { metric -> CompareMetricRow(metric) }
            item(key = "comparison_config_diff") {
                HorizontalDivider(color = Hairline, modifier = Modifier.padding(vertical = 10.dp))
                Text(text = stringResource(R.string.benchmark_configuration_changes), color = AccentSoft, style = MaterialTheme.typography.labelSmall)
                if (differences.isEmpty()) {
                    Text(text = stringResource(R.string.benchmark_no_configuration_changes), modifier = Modifier.padding(top = 6.dp), color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
                } else {
                    differences.forEach { difference ->
                        Text(text = difference, modifier = Modifier.padding(top = 6.dp), color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }
    }
}

@Composable
private fun CompareIdentities(first: BenchmarkRun, second: BenchmarkRun) {
    val context = LocalContext.current
    Row(Modifier.fillMaxWidth()) {
        listOf(first to R.string.benchmark_compare_a, second to R.string.benchmark_compare_b)
            .forEach { (run, mark) ->
                Column(Modifier.weight(1f).padding(end = 8.dp)) {
                    Text(text = stringResource(mark), color = Accent, style = MaterialTheme.typography.labelSmall)
                    Text(
                        run.configuration.modelName,
                        style = MaterialTheme.typography.titleSmall,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(top = 2.dp),
                    )
                    Text(text = listOf(context.runtimeName(run.configuration.runtime),
                        context.backendName(run.configuration.backend), DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(run.createdAtEpochMilliseconds)))
                        .joinToString(UiSymbols.META_SEPARATOR), modifier = Modifier.padding(top = 2.dp), color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
                }
            }
    }
}

@Composable
private fun CompareMetricRow(metric: ComparisonMetric) {
    val deltaColor = when {
        (metric.delta == null) || (abs(metric.delta) < DELTA_EPSILON) -> MaterialTheme.colorScheme.onSurfaceVariant
        (metric.delta > 0.0) != metric.lowerIsBetter -> Accent
        else -> Danger
    }
    Column(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
        Text(text = metric.label, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.labelSmall)
        Row(Modifier.fillMaxWidth().padding(top = 3.dp)) {
            Text(
                metric.first,
                style = MaterialTheme.typography.titleSmall.copy(fontFeatureSettings = "tnum"),
                modifier = Modifier.weight(1f),
            )
            Text(
                metric.second,
                style = MaterialTheme.typography.titleSmall.copy(fontFeatureSettings = "tnum"),
                modifier = Modifier.weight(1f),
            )
            Text(
                metric.delta?.let { stringResource(R.string.benchmark_delta_b, it * 100.0) }
                    ?: UiSymbols.UNAVAILABLE,
                style = MaterialTheme.typography.labelSmall.copy(fontFeatureSettings = "tnum"),
                color = deltaColor,
            )
        }
    }
}

internal data class ComparisonMetric(
    val label: String,
    val first: String,
    val second: String,
    val delta: Double?,
    val lowerIsBetter: Boolean,
)

private fun configurationDifferences(
    context: Context,
    first: BenchmarkConfiguration,
    second: BenchmarkConfiguration,
): List<String> {
    val firstFacts = listOf(
        Fact(R.string.label_model, first.modelName),
        Fact(R.string.benchmark_model_size, Formatter.formatShortFileSize(context, first.modelSizeBytes)),
        Fact(R.string.benchmark_device, first.deviceModel),
    ) + configurationFacts(context, first).let { it.first + if (first.runtime == LocalLlmRuntime.LLAMA) emptyList() else it.second }
    val secondFacts = listOf(
        Fact(R.string.label_model, second.modelName),
        Fact(R.string.benchmark_model_size, Formatter.formatShortFileSize(context, second.modelSizeBytes)),
        Fact(R.string.benchmark_device, second.deviceModel),
    ) + configurationFacts(context, second).let { it.first + if (second.runtime == LocalLlmRuntime.LLAMA) emptyList() else it.second }
    val secondByLabel = secondFacts.associateBy(Fact::label)
    return firstFacts.mapNotNull { fact ->
        secondByLabel[fact.label]?.value?.takeIf { it != fact.value }?.let { changed ->
            context.getString(R.string.benchmark_config_change, context.getString(fact.label), fact.value, changed)
        }
    }
}

private fun comparisonMetrics(
    context: Context,
    first: BenchmarkRun,
    second: BenchmarkRun,
): List<ComparisonMetric> = listOf(
    comparisonMetric(
        context.getString(R.string.benchmark_decode),
        (first.passes.map { it.decodeTokensPerSecond }.average()),
        (second.passes.map { it.decodeTokensPerSecond }.average()),
        lowerIsBetter = false,
    ) { context.getString(R.string.benchmark_rate_value, it) },
    comparisonMetric(
        context.getString(R.string.benchmark_prefill),
        (first.passes.map { it.promptTokensPerSecond }.average()),
        (second.passes.map { it.promptTokensPerSecond }.average()),
        lowerIsBetter = false,
    ) { context.getString(R.string.benchmark_rate_value, it) },
    comparisonMetric(
        context.getString(R.string.benchmark_first_token),
        (first.passes.map { it.timeToFirstTokenMilliseconds }.average()),
        (second.passes.map { it.timeToFirstTokenMilliseconds }.average()),
        lowerIsBetter = true,
    ) { context.getString(R.string.benchmark_seconds_value, it / 1_000.0) },
    comparisonMetric(
        context.getString(R.string.benchmark_cold_ready),
        first.coldReadyMilliseconds,
        second.coldReadyMilliseconds,
        lowerIsBetter = true,
    ) { context.getString(R.string.benchmark_seconds_value, it / 1_000.0) },
    comparisonMetric(
        context.getString(R.string.benchmark_total_time),
        (first.passes.map { it.totalGenerationMilliseconds }.average()),
        (second.passes.map { it.totalGenerationMilliseconds }.average()),
        lowerIsBetter = true,
    ) { context.getString(R.string.benchmark_seconds_value, it / 1_000.0) },
    comparisonMetric(
        context.getString(R.string.benchmark_peak_memory),
        first.peakPssKilobytes.toDouble(),
        second.peakPssKilobytes.toDouble(),
        lowerIsBetter = true,
    ) {
        Formatter.formatShortFileSize(context, it.toLong() * BYTES_PER_KILOBYTE)
    },
    comparisonMetric(
        context.getString(R.string.benchmark_lowest_free_ram),
        first.minimumAvailableMemoryBytes.toDouble(),
        second.minimumAvailableMemoryBytes.toDouble(),
        lowerIsBetter = false,
    ) {
        Formatter.formatShortFileSize(context, it.toLong())
    },
) + ComparisonMetric(
    label = context.getString(R.string.benchmark_thermal_peak),
    first = context.thermalName(first.thermalPeak),
    second = context.thermalName(second.thermalPeak),
    delta = null,
    lowerIsBetter = true,
)

private fun comparisonMetric(
    label: String,
    first: Double,
    second: Double,
    lowerIsBetter: Boolean,
    format: (Double) -> String,
) = ComparisonMetric(
    label, if (first.isNaN()) UiSymbols.UNAVAILABLE else format(first), if (second.isNaN()) UiSymbols.UNAVAILABLE else format(second),
    ((second - first) / first).takeIf { (first > 0.0) && it.isFinite() }, lowerIsBetter,
)

private const val DELTA_EPSILON = 0.0005
