package com.mrj.fancyai.ui.aura

import android.app.ActivityManager
import android.os.SystemClock
import android.text.format.Formatter
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.mrj.fancyai.R
import com.mrj.fancyai.ui.kit.AppHeader
import com.mrj.fancyai.ui.theme.Danger
import com.mrj.fancyai.ui.theme.Ink
import kotlinx.coroutines.delay
import kotlin.time.Duration.Companion.seconds

@Composable
internal fun AuraConverterScreen(onBack: () -> Unit, onOpenAura: () -> Unit) {
    val app = LocalContext.current.applicationContext
    val scope = rememberCoroutineScope()
    val converter = remember(app, scope) { AuraConverterController(app, scope) }
    val working = converter.operation != null
    val checkpointPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument(), converter::chooseCheckpoint)

    DisposableEffect(converter) { onDispose { converter.job?.cancel() } }
    BackHandler { onBack() }
    Column(
        Modifier.fillMaxSize()
            .background(Brush.verticalGradient(0f to MaterialTheme.colorScheme.surfaceContainerLow, 0.28f to Ink, 1f to MaterialTheme.colorScheme.background))
            .windowInsetsPadding(WindowInsets.safeDrawing),
    ) {
        AppHeader(
            title = stringResource(R.string.home_app_aura_converter),
            subtitle = stringResource(R.string.aura_converter_subtitle),
            onBack = onBack,
        )
        converter.operation?.let { OperationBanner(it, converter.progress) }
        Column(
            Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            converter.installedModel?.let { model ->
                Text(stringResource(R.string.aura_converter_done, model.name), style = MaterialTheme.typography.titleMedium)
                Button(onClick = onOpenAura, enabled = !working, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.aura_converter_open))
                }
            }
            if (converter.cancelled) Text(stringResource(R.string.aura_converter_cancelled))
            converter.error?.let { Text(it, color = Danger) }
            if (!converter.converting) {
                ConverterSetupSection(
                    converter = converter,
                    working = working,
                    onPickCheckpoint = { checkpointPicker.launch(arrayOf("*/*")) },
                )
            }
            if (converter.converting || converter.conversionLog.isNotEmpty()) {
                ConverterLogSection(converter = converter)
            }
        }
    }
}

@Composable
private fun ConverterSetupSection(
    converter: AuraConverterController,
    working: Boolean,
    onPickCheckpoint: () -> Unit,
) {
    val context = LocalContext.current
    Text(
        stringResource(R.string.aura_converter_note),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    OutlinedButton(
        onClick = onPickCheckpoint,
        enabled = !working,
        modifier = Modifier.fillMaxWidth(),
    ) { Text(stringResource(R.string.aura_converter_checkpoint)) }
    converter.checkpoint?.let { document ->
        Text(document.name, style = MaterialTheme.typography.titleSmall)
        if (document.size >= 0L) {
            Text(Formatter.formatShortFileSize(context, document.size), style = MaterialTheme.typography.bodySmall)
        }
    }
    ConverterQuantSection(
        ggufQuant = converter.ggufQuant,
        working = working,
        onQuantChange = { converter.ggufQuant = it },
    )
    Button(
        onClick = { converter.convert() },
        enabled = !working && converter.checkpoint != null,
        modifier = Modifier.fillMaxWidth(),
    ) { Text(stringResource(R.string.aura_converter_start)) }
}

@Composable
private fun ConverterQuantSection(
    ggufQuant: GgufQuant,
    working: Boolean,
    onQuantChange: (GgufQuant) -> Unit,
) {
    AuraSection(stringResource(R.string.aura_converter_quant)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            GgufQuant.entries.forEach { quant ->
                FilterChip(
                    selected = ggufQuant == quant,
                    onClick = { onQuantChange(quant) },
                    enabled = !working,
                    label = { Text(quant.label) },
                    modifier = Modifier.weight(1f),
                )
            }
        }
        Text(
            stringResource(
                when (ggufQuant) {
                    GgufQuant.Q8_0 -> R.string.aura_converter_quant_q8
                    GgufQuant.Q4_0 -> R.string.aura_converter_quant_q4
                    GgufQuant.F16 -> R.string.aura_converter_quant_f16
                }
            ),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            stringResource(R.string.aura_converter_gguf_note),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun ConverterLogSection(
    converter: AuraConverterController,
) {
    AuraSection(stringResource(R.string.aura_conversion_log)) {
        if (converter.converting) {
            val app = LocalContext.current.applicationContext
            val manager = remember { app.getSystemService(ActivityManager::class.java) }
            var memory by remember { mutableStateOf(ActivityManager.MemoryInfo().also(manager::getMemoryInfo)) }
            var elapsed by remember { mutableLongStateOf(0L) }
            LaunchedEffect(converter.startedAt) {
                while (true) {
                    memory = ActivityManager.MemoryInfo().also(manager::getMemoryInfo)
                    elapsed = (SystemClock.elapsedRealtime() - converter.startedAt) / 1000
                    delay(1.seconds)
                }
            }
            converter.checkpoint?.let { Text(it.name, style = MaterialTheme.typography.titleSmall) }
            Text(
                stringResource(
                    R.string.aura_conversion_memory,
                    (100 * (memory.totalMem - memory.availMem) / memory.totalMem).toInt(),
                    memory.availMem / 1073741824.0,
                    elapsed / 60.0,
                ),
                style = MaterialTheme.typography.bodySmall,
            )
        }
        if (converter.conversionLog.isNotEmpty()) {
            val logScroll = rememberScrollState()
            var followLog by remember { mutableStateOf(true) }
            LaunchedEffect(logScroll.maxValue, followLog) {
                if (followLog) logScroll.scrollTo(logScroll.maxValue)
            }
            TextButton(onClick = { followLog = !followLog }) {
                Text(stringResource(if (followLog) R.string.aura_pause_log else R.string.aura_follow_log))
            }
            SelectionContainer {
                Text(
                    converter.conversionLog.joinToString("\n"),
                    modifier = Modifier.fillMaxWidth().height(220.dp).verticalScroll(logScroll),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
        if (converter.converting) {
            TextButton(onClick = { converter.job?.cancel() }) {
                Text(stringResource(R.string.aura_cancel_conversion), color = Danger)
            }
        }
    }
}
