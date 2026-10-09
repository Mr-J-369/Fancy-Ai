package com.mrj.fancyai.ui.settings

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.mrj.fancyai.R
import com.mrj.fancyai.service.llm.CloudProvider
import com.mrj.fancyai.ui.kit.AppHeader
import com.mrj.fancyai.ui.kit.PostInput
import com.mrj.fancyai.ui.theme.Accent
import com.mrj.fancyai.ui.theme.AccentSoft
import com.mrj.fancyai.ui.theme.Ink
import kotlin.math.roundToInt

private class SamplingControlSpec(
    val key: String,
    val section: Int,
    val title: Int,
    val summary: Int,
    val value: Number?,
    val defaultValue: Number,
    val offValue: Number?,
    val range: ClosedFloatingPointRange<Float>,
    val steps: Int,
    val onChange: (Float) -> Unit,
)

@Composable
internal fun GenerationScreen(target: GenerationTarget, onBack: () -> Unit) {
    BackHandler(onBack = onBack)
    val context = LocalContext.current
    val cloudEngine = remember(context, target) {
        (LlmSettingsStore.activeEngine(context) as? SelectedCloudEngine).takeIf { target == GenerationTarget.CLOUD }
    }
    val runtimeName = cloudEngine?.let { cloudProviderName(it.provider) } ?: generationRuntimeName(target)

    Column(Modifier.fillMaxSize()
        .background(Brush.verticalGradient(0f to MaterialTheme.colorScheme.surfaceContainerLow, 0.28f to Ink, 1f to MaterialTheme.colorScheme.background))
        .windowInsetsPadding(WindowInsets.safeDrawing).padding(horizontal = 20.dp)) {
        AppHeader(title = stringResource(R.string.generation_runtime_title, stringResource(runtimeName)), onBack = onBack, subtitle = cloudEngine?.let {
            stringResource(R.string.generation_cloud_model_subtitle, stringResource(cloudProviderName(it.provider)), it.model)
        } ?: stringResource(generationRuntimeSubtitle(target)))
        when (target) {
            GenerationTarget.LITERT -> LocalGenerationEditor(target)
            GenerationTarget.LLAMA -> LlamaGenerationEditor()
            GenerationTarget.CLOUD -> CloudGenerationEditor(checkNotNull(cloudEngine))
        }
    }
}

@Composable
private fun LocalGenerationEditor(target: GenerationTarget) {
    val context = LocalContext.current
    var settings by remember(context, target) {
        mutableStateOf(LlmSettingsStore.generation(context, target))
    }
    val defaults = remember(target) { LlmSettingsStore.defaultGeneration(target) }
    fun save(next: GenerationSettings) {
        settings = next
        LlmSettingsStore.saveGeneration(context, target, next)
    }
    val penaltyMinimum = -2f
    val penaltySteps = 79
    val localSamplingControls = listOf(
        SamplingControlSpec(
            key = "temperature", section = R.string.section_sampling, title = R.string.sampling_temperature,
            summary = R.string.generation_temperature_summary, value = settings.temperature, defaultValue = settings.temperature, offValue = null,
            range = 0f..2f, steps = 39,
        ) { save(settings.copy(temperature = it)) },
        SamplingControlSpec(
            key = "top_k", section = R.string.section_sampling, title = R.string.generation_top_k,
            summary = R.string.generation_top_k_summary, value = settings.topK, defaultValue = settings.topK, offValue = null,
            range = 1f..100f, steps = 98,
        ) { save(settings.copy(topK = it.roundToInt())) },
        SamplingControlSpec(
            key = "top_p", section = R.string.section_sampling, title = R.string.generation_top_p,
            summary = R.string.generation_top_p_summary, value = settings.topP, defaultValue = settings.topP, offValue = null,
            range = 0f..1f, steps = 99,
        ) { save(settings.copy(topP = it)) },
        SamplingControlSpec(
            key = "min_p", section = R.string.section_sampling, title = R.string.generation_min_p,
            summary = R.string.generation_min_p_summary, value = settings.minP, defaultValue = settings.minP, offValue = null,
            range = 0f..1f, steps = 99,
        ) { save(settings.copy(minP = it)) },
        SamplingControlSpec(
            key = "repetition_penalty", section = R.string.generation_repetition, title = R.string.sampling_repetition_penalty,
            summary = R.string.generation_repetition_penalty_summary, value = settings.repetitionPenalty, defaultValue = settings.repetitionPenalty, offValue = 1f,
            range = 1f..1.5f, steps = 49,
        ) { save(settings.copy(repetitionPenalty = it)) },
        SamplingControlSpec(
            key = "presence_penalty", section = R.string.generation_repetition, title = R.string.sampling_presence_penalty,
            summary = R.string.generation_presence_penalty_summary, value = settings.presencePenalty, defaultValue = settings.presencePenalty, offValue = 0f,
            range = penaltyMinimum..2f, steps = penaltySteps,
        ) { save(settings.copy(presencePenalty = it)) },
        SamplingControlSpec(
            key = "frequency_penalty", section = R.string.generation_repetition, title = R.string.sampling_frequency_penalty,
            summary = R.string.generation_frequency_penalty_summary, value = settings.frequencyPenalty, defaultValue = settings.frequencyPenalty, offValue = 0f,
            range = penaltyMinimum..2f, steps = penaltySteps,
        ) { save(settings.copy(frequencyPenalty = it)) },
    )
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(top = 24.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp),
    ) {
        generationReset(settings == defaults) { save(defaults) }
        item { GenerationSection(R.string.generation_replies, settings == defaults) }
        generationOutput(settings.maxOutputTokens) { save(settings.copy(maxOutputTokens = it)) }
        samplingControls(localSamplingControls.filter { target != GenerationTarget.LITERT || it.key != "min_p" })
        item {
            SettingsStepper(
                title = stringResource(R.string.sampling_penalty_window),
                value = if (settings.penaltyWindow == 0) stringResource(R.string.generation_whole_conversation) else pluralStringResource(R.plurals.settings_tokens, settings.penaltyWindow, settings.penaltyWindow),
                summary = stringResource(if (target == GenerationTarget.LITERT) R.string.generation_litert_penalty_window_summary else R.string.generation_penalty_window_summary),
                onLower = LlmSettingsStore.windowLadder.below(settings.penaltyWindow)?.let { next -> { save(settings.copy(penaltyWindow = next)) } },
                onHigher = LlmSettingsStore.windowLadder.above(settings.penaltyWindow)?.let { next -> { save(settings.copy(penaltyWindow = next)) } },
            )
        }
        if (target == GenerationTarget.LITERT) {
            item {
                SettingsStepper(
                    title = stringResource(R.string.generation_no_repeat_ngram),
                    value = if (settings.noRepeatNgramSize == 0) stringResource(R.string.settings_off) else pluralStringResource(R.plurals.settings_tokens, settings.noRepeatNgramSize, settings.noRepeatNgramSize),
                    summary = stringResource(R.string.generation_no_repeat_ngram_summary),
                    onLower = LlmSettingsStore.ngramLadder.below(settings.noRepeatNgramSize)?.let { next -> { save(settings.copy(noRepeatNgramSize = next)) } },
                    onHigher = LlmSettingsStore.ngramLadder.above(settings.noRepeatNgramSize)?.let { next -> { save(settings.copy(noRepeatNgramSize = next)) } },
                )
            }
            if (settings.noRepeatNgramSize > 0) {
                item {
                    SettingsStepper(
                        title = stringResource(R.string.sampling_no_repeat_window),
                        value = if (settings.noRepeatNgramWindow == 0) stringResource(R.string.generation_whole_reply) else pluralStringResource(R.plurals.settings_tokens, settings.noRepeatNgramWindow, settings.noRepeatNgramWindow),
                        summary = stringResource(R.string.generation_no_repeat_window_summary),
                        onLower = LlmSettingsStore.windowLadder.below(settings.noRepeatNgramWindow)?.let { next -> { save(settings.copy(noRepeatNgramWindow = next)) } },
                        onHigher = LlmSettingsStore.windowLadder.above(settings.noRepeatNgramWindow)?.let { next -> { save(settings.copy(noRepeatNgramWindow = next)) } },
                    )
                }
            }
        }
    }
}

@Composable
private fun CloudGenerationEditor(engine: SelectedCloudEngine) {
    val context = LocalContext.current
    var settings by remember(context, engine.provider, engine.model) {
        mutableStateOf(CloudSettingsStore.cloudGeneration(context, engine))
    }
    val defaults = remember(engine) { CloudSettingsStore.defaultCloudGeneration(engine) }
    fun save(next: CloudGenerationSettings) {
        settings = next
        CloudSettingsStore.saveCloudGeneration(context, engine, next)
    }
    val supported = engine.supportedGenerationParameters
    val outputLimit = ("max_tokens" in supported) || ("max_completion_tokens" in supported)
    val samplingControls = listOf(
        SamplingControlSpec(
            key = "temperature", section = R.string.section_sampling, title = R.string.sampling_temperature,
            summary = R.string.generation_temperature_summary, value = settings.temperature, defaultValue = 1f, offValue = null,
            range = 0f..2f, steps = 39,
        ) { save(settings.copy(temperature = it)) },
        SamplingControlSpec(
                        key = "top_k", section = R.string.section_sampling, title = R.string.generation_top_k,
                        summary = R.string.generation_top_k_summary, value = settings.topK, defaultValue = 0, offValue = 0,
            range = 0f..100f, steps = 100,
        ) { save(settings.copy(topK = it.roundToInt())) },
        SamplingControlSpec(
                        key = "top_p", section = R.string.section_sampling, title = R.string.generation_top_p,
                        summary = R.string.generation_top_p_summary, value = settings.topP, defaultValue = 1f, offValue = null,
            range = 0f..1f, steps = 99,
        ) { save(settings.copy(topP = it)) },
        SamplingControlSpec(
                        key = "min_p", section = R.string.section_sampling, title = R.string.generation_min_p,
                        summary = R.string.generation_min_p_summary, value = settings.minP, defaultValue = 0f, offValue = null,
            range = 0f..1f, steps = 99,
        ) { save(settings.copy(minP = it)) },
        SamplingControlSpec(
                        key = "repetition_penalty", section = R.string.generation_repetition, title = R.string.sampling_repetition_penalty,
                        summary = R.string.generation_repetition_penalty_summary, value = settings.repetitionPenalty, defaultValue = 1f, offValue = 1f,
            range = 0.01f..5f, steps = 499,
        ) { save(settings.copy(repetitionPenalty = it)) },
        SamplingControlSpec(
                        key = "presence_penalty", section = R.string.generation_repetition, title = R.string.sampling_presence_penalty,
                        summary = R.string.generation_presence_penalty_summary, value = settings.presencePenalty, defaultValue = 0f, offValue = 0f,
            range = -2f..2f, steps = 79,
        ) { save(settings.copy(presencePenalty = it)) },
        SamplingControlSpec(
                        key = "frequency_penalty", section = R.string.generation_repetition, title = R.string.sampling_frequency_penalty,
                        summary = R.string.generation_frequency_penalty_summary, value = settings.frequencyPenalty, defaultValue = 0f, offValue = 0f,
            range = -2f..2f, steps = 79,
        ) { save(settings.copy(frequencyPenalty = it)) },
    )
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(top = 24.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp),
    ) {
        generationReset(settings == defaults) { save(defaults) }
        if ((engine.provider == CloudProvider.OPENROUTER) && supported.isEmpty()) {
            item {
                Text(
                    text = stringResource(R.string.generation_openrouter_parameters_unavailable),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        if (outputLimit) {
            item { GenerationSection(R.string.generation_replies, settings == defaults) }
            generationOutput(settings.maxOutputTokens) { save(settings.copy(maxOutputTokens = it)) }
        }
        samplingControls(samplingControls.filter { it.key in supported })
    }
}

@Composable
private fun LlamaGenerationEditor() {
    val context = LocalContext.current
    var settings by remember(context) { mutableStateOf(LlmSettingsStore.generation(context, GenerationTarget.LLAMA)) }
    val defaults = remember { LlmSettingsStore.defaultGeneration(GenerationTarget.LLAMA) }
    fun save(next: GenerationSettings) {
        settings = next
        LlmSettingsStore.saveGeneration(context, GenerationTarget.LLAMA, next)
    }
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(top = 24.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp),
    ) {
        generationReset(settings == defaults) { save(defaults) }
        item { Text(stringResource(R.string.llama_sampling_values), style = MaterialTheme.typography.bodySmall) }
        val fields: List<Triple<Int, String, (String) -> Unit>> = listOf(
            Triple(R.string.generation_max_output, settings.maxOutputTokens.toString()) { text -> text.toIntOrNull()?.let { save(settings.copy(maxOutputTokens = it)) } },
            Triple(R.string.sampling_temperature, settings.temperature.toString()) { text -> text.toFloatOrNull()?.let { save(settings.copy(temperature = it)) } },
            Triple(R.string.sampling_dynamic_temperature, settings.dynamicTemperature.toString()) { text -> text.toFloatOrNull()?.let { save(settings.copy(dynamicTemperature = it)) } },
            Triple(R.string.generation_top_k, settings.topK.toString()) { text -> text.toIntOrNull()?.let { save(settings.copy(topK = it)) } },
            Triple(R.string.generation_top_p, settings.topP.toString()) { text -> text.toFloatOrNull()?.let { save(settings.copy(topP = it)) } },
            Triple(R.string.generation_min_p, settings.minP.toString()) { text -> text.toFloatOrNull()?.let { save(settings.copy(minP = it)) } },
            Triple(R.string.sampling_repetition_penalty, settings.repetitionPenalty.toString()) { text -> text.toFloatOrNull()?.let { save(settings.copy(repetitionPenalty = it)) } },
            Triple(R.string.sampling_presence_penalty, settings.presencePenalty.toString()) { text -> text.toFloatOrNull()?.let { save(settings.copy(presencePenalty = it)) } },
            Triple(R.string.sampling_frequency_penalty, settings.frequencyPenalty.toString()) { text -> text.toFloatOrNull()?.let { save(settings.copy(frequencyPenalty = it)) } },
            Triple(R.string.sampling_penalty_window, settings.penaltyWindow.toString()) { text -> text.toIntOrNull()?.let { save(settings.copy(penaltyWindow = it)) } },
        )
        fields.forEach { (label, stored, update) ->
            item(key = label) {
                var text by remember { mutableStateOf(stored) }
                LaunchedEffect(stored) {
                    if (text.toDoubleOrNull() != stored.toDoubleOrNull()) text = stored
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(label), style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                    PostInput(value = text, onValueChange = {
                        text = it
                        update(it)
                    }, hint = stringResource(R.string.engines_runtime_default), singleLine = true, modifier = Modifier.width(100.dp))
                }
            }
        }
    }
}

private fun androidx.compose.foundation.lazy.LazyListScope.generationReset(isDefault: Boolean, onReset: () -> Unit) {
    item {
        Text(stringResource(R.string.generation_reset), style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = if (isDefault) 0.42f else 1f),
            modifier = Modifier.heightIn(min = 48.dp).clickable(role = Role.Button, enabled = !isDefault, onClick = onReset).padding(vertical = 15.dp))
    }
}

private fun androidx.compose.foundation.lazy.LazyListScope.generationOutput(
    maxOutputTokens: Int,
    onChange: (Int) -> Unit,
) {
    item {
        val ladder = LlmSettingsStore.outputLadder
        SettingsStepper(
            title = stringResource(R.string.generation_max_output),
            value = pluralStringResource(R.plurals.settings_tokens, maxOutputTokens, maxOutputTokens),
            summary = stringResource(R.string.generation_max_output_summary),
            onLower = ladder.below(maxOutputTokens)?.let { value -> { onChange(value) } },
            onHigher = ladder.above(maxOutputTokens)?.let { value -> { onChange(value) } },
        )
    }
}

private fun androidx.compose.foundation.lazy.LazyListScope.samplingControls(
    controls: List<SamplingControlSpec>,
) {
    controls.groupBy(SamplingControlSpec::section).forEach { (section, grouped) ->
        item { GenerationSection(section) }
        grouped.forEach { control ->
            item {
                val value = control.value ?: control.defaultValue
                val display = when {
                    control.value == null -> stringResource(R.string.generation_provider_default)
                    control.offValue != null && value.toDouble() == control.offValue.toDouble() -> stringResource(R.string.settings_off)
                    value is Int -> value.toString()
                    else -> String.format(LocalConfiguration.current.locales[0], "%.2f", value.toFloat())
                }
                SamplingControl(
                    title = stringResource(control.title),
                    summary = stringResource(control.summary),
                    value = value.toFloat(),
                    display = display,
                    range = control.range,
                    steps = control.steps,
                    onChange = control.onChange,
                )
            }
        }
    }
}

internal fun generationRuntimeName(target: GenerationTarget): Int = when (target) {
    GenerationTarget.LITERT -> R.string.engines_litert
    GenerationTarget.LLAMA -> R.string.engines_llama
    GenerationTarget.CLOUD -> R.string.generation_cloud
}

internal fun generationRuntimeSubtitle(target: GenerationTarget): Int = when (target) {
    GenerationTarget.LITERT -> R.string.generation_litert_subtitle
    GenerationTarget.LLAMA -> R.string.generation_llama_subtitle
    GenerationTarget.CLOUD -> R.string.generation_cloud_subtitle
}

@Composable
private fun GenerationSection(title: Int, recommended: Boolean = false) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(
            text = stringResource(title).uppercase(),
            style = MaterialTheme.typography.labelSmall,
            color = AccentSoft,
        )
        Spacer(Modifier.weight(1f))
        if (recommended) {
            Text(
                text = stringResource(R.string.generation_recommended),
                style = MaterialTheme.typography.labelSmall,
                color = Accent,
            )
        }
    }
    HorizontalDivider(
        color = MaterialTheme.colorScheme.outline,
        modifier = Modifier.padding(top = 8.dp),
    )
}

@Composable
private fun SamplingControl(
    title: String,
    summary: String,
    value: Float,
    display: String,
    range: ClosedFloatingPointRange<Float>,
    steps: Int,
    onChange: (Float) -> Unit,
) {
    Column(Modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Spacer(Modifier.weight(1f))
            Text(
                text = display,
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold,
                color = Accent,
            )
        }
        Text(
            text = summary,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.fillMaxWidth().padding(top = 2.dp),
        )
        Slider(
            value = value,
            onValueChange = onChange,
            valueRange = range,
            steps = steps,
            colors = SliderDefaults.colors(
                thumbColor = Accent,
                activeTrackColor = Accent,
                inactiveTrackColor = MaterialTheme.colorScheme.outline,
            ),
            modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
        )
    }
}

@Composable
internal fun MemoryScreen(target: GenerationTarget, onBack: () -> Unit) {
    BackHandler(onBack = onBack)
    val context = LocalContext.current
    var settings by remember(context, target) { mutableStateOf(LlmSettingsStore.memory(context, target)) }
    val ladder = LlmSettingsStore.historyLadder

    fun save(historyLimit: Int) {
        settings = settings.copy(historyLimit = historyLimit)
        LlmSettingsStore.saveMemory(context, target, settings)
    }

    Column(Modifier.fillMaxSize()
        .background(Brush.verticalGradient(0f to MaterialTheme.colorScheme.surfaceContainerLow, 0.28f to Ink, 1f to MaterialTheme.colorScheme.background))
        .windowInsetsPadding(WindowInsets.safeDrawing).padding(horizontal = 20.dp)) {
        AppHeader(title = stringResource(R.string.memory_runtime_title, stringResource(generationRuntimeName(target))), onBack = onBack, subtitle = stringResource(R.string.memory_subtitle))
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(top = 24.dp, bottom = 24.dp),
        ) {
            item {
                SettingsStepper(
                    title = stringResource(R.string.memory_recent_turns),
                    value = pluralStringResource(R.plurals.memory_turns, settings.historyLimit, settings.historyLimit),
                    summary = stringResource(R.string.memory_recent_turns_summary),
                    onLower = ladder.below(settings.historyLimit)?.let { { save(it) } },
                    onHigher = ladder.above(settings.historyLimit)?.let { { save(it) } },
                )
            }
            item {
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant, modifier = Modifier.padding(top = 24.dp, bottom = 20.dp))
                Text(stringResource(R.string.memory_protection), style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurface)
                Text(stringResource(R.string.memory_protection_status), style = MaterialTheme.typography.labelMedium, color = Accent, modifier = Modifier.padding(top = 4.dp))
                Text(stringResource(R.string.memory_protection_summary), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 4.dp))
            }
        }
    }
}
