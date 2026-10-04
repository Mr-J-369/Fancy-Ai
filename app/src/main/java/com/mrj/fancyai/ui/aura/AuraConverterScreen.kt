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
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.mrj.fancyai.R
import com.mrj.fancyai.ui.kit.AppDialog
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
    var npu by rememberSaveable { mutableStateOf(true) }
    var pendingRemoval by remember { mutableStateOf<AuraLora?>(null) }
    val working = converter.operation != null
    val checkpointPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument(), converter::chooseCheckpoint)
    val loraPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument(), converter::importLora)

    LaunchedEffect(converter) { converter.loadLoras() }
    AuraImageServiceBinding(app, enabled = true) { converter.service = it }
    DisposableEffect(converter) { onDispose { converter.job?.cancel() } }
    BackHandler {
        if (pendingRemoval != null) pendingRemoval = null else onBack()
    }
    pendingRemoval?.let { lora ->
        AppDialog(
            onDismissRequest = { pendingRemoval = null },
            title = { Text(stringResource(R.string.remove_item_title, lora.file.name)) },
            text = { Text(stringResource(R.string.aura_remove_lora_message)) },
            confirmButton = {
                TextButton(onClick = {
                    pendingRemoval = null
                    converter.removeLora(lora)
                }) { Text(stringResource(R.string.action_remove), color = Danger) }
            },
            dismissButton = {
                TextButton(onClick = { pendingRemoval = null }) { Text(stringResource(R.string.action_cancel)) }
            },
        )
    }
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
                    npu = npu,
                    working = working,
                    onNpuChange = { npu = it },
                    onPickCheckpoint = { checkpointPicker.launch(arrayOf("*/*")) },
                    onPickLora = { loraPicker.launch(arrayOf("*/*")) },
                    onRequestRemoveLora = { pendingRemoval = it },
                )
            }
            if (converter.converting || converter.conversionLog.isNotEmpty()) {
                ConverterLogSection(converter = converter, npu = npu)
            }
        }
    }
}

@Composable
private fun ConverterSetupSection(
    converter: AuraConverterController,
    npu: Boolean,
    working: Boolean,
    onNpuChange: (Boolean) -> Unit,
    onPickCheckpoint: () -> Unit,
    onPickLora: () -> Unit,
    onRequestRemoveLora: (AuraLora) -> Unit,
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
    ConverterTargetSection(npu = npu, working = working, onNpuChange = onNpuChange)
    ConverterLoraSection(
        loras = converter.loras,
        working = working,
        onRemoveLora = onRequestRemoveLora,
        onStrengthChange = converter::setStrength,
        onPickLora = onPickLora,
    )
    Button(
        onClick = { converter.convert(npu) },
        enabled = !working && converter.checkpoint != null,
        modifier = Modifier.fillMaxWidth(),
    ) { Text(stringResource(R.string.aura_converter_start)) }
}

@Composable
private fun ConverterTargetSection(
    npu: Boolean,
    working: Boolean,
    onNpuChange: (Boolean) -> Unit,
) {
    AuraSection(stringResource(R.string.aura_converter_target)) {
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            FilterChip(
                selected = npu,
                onClick = { onNpuChange(true) },
                enabled = !working,
                label = { Text(stringResource(R.string.aura_converter_npu)) },
                modifier = Modifier.fillMaxWidth(),
            )
            FilterChip(
                selected = !npu,
                onClick = { onNpuChange(false) },
                enabled = !working,
                label = { Text(stringResource(R.string.aura_converter_mnn)) },
                modifier = Modifier.fillMaxWidth(),
            )
        }
        Text(
            stringResource(if (npu) R.string.aura_import_qnn_note else R.string.aura_import_mnn_note),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun ConverterLoraSection(
    loras: List<AuraLora>,
    working: Boolean,
    onRemoveLora: (AuraLora) -> Unit,
    onStrengthChange: (AuraLora, Float) -> Unit,
    onPickLora: () -> Unit,
) {
    AuraSection(stringResource(R.string.aura_lora_title)) {
        loras.forEach { lora ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    lora.file.nameWithoutExtension,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.weight(1f),
                )
                TextButton(
                    onClick = { onRemoveLora(lora) },
                    enabled = !working,
                    colors = ButtonDefaults.textButtonColors(contentColor = Danger),
                ) { Text(stringResource(R.string.action_remove)) }
            }
            ValueSlider(
                label = stringResource(R.string.aura_lora_strength),
                value = lora.strength,
                valueText = stringResource(R.string.aura_lora_multiplier, lora.strength),
                range = 0f..1.5f,
                enabled = !working,
                onValueChange = { onStrengthChange(lora, it) },
            )
            HorizontalDivider()
        }
        OutlinedButton(
            onClick = onPickLora,
            enabled = !working,
            modifier = Modifier.fillMaxWidth(),
        ) { Text(stringResource(R.string.aura_import_lora)) }
        Text(
            stringResource(R.string.aura_lora_note),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun ConverterLogSection(
    converter: AuraConverterController,
    npu: Boolean,
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
        if (converter.converting && npu) {
            TextButton(onClick = { converter.job?.cancel() }) {
                Text(stringResource(R.string.aura_cancel_conversion), color = Danger)
            }
        }
    }
}
