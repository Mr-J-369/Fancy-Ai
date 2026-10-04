package com.mrj.fancyai.ui.aura

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.mrj.fancyai.R
import com.mrj.fancyai.sd.MnnBackend
import com.mrj.fancyai.sd.MnnMemoryPolicy
import com.mrj.fancyai.sd.SamplerType
import com.mrj.fancyai.sd.Schedule
import com.mrj.fancyai.sd.SdRuntimeType
import com.mrj.fancyai.sd.SeedPolicy
import com.mrj.fancyai.ui.kit.PostInput
import com.mrj.fancyai.ui.kit.CompactSwitchRow
import com.mrj.fancyai.ui.theme.AccentSoft

@Composable
internal fun AuraController.AdvancedPage() {
    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        SamplingAndRuntimeSettings()


        AuraSection(stringResource(R.string.field_seed)) {
            val updateSeed: (String) -> Unit = { value ->
                saveText(KEY_SEED, value)
                state = state.copy(seed = value)
            }
            NumericField(
                label = stringResource(R.string.field_seed),
                value = state.seed,
                decimal = false,
                onValueChange = updateSeed,
                modifier = Modifier.fillMaxWidth(),
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(
                    onClick = {
                        saveModelBoolean(KEY_SEED_LOCKED, !state.seedLocked)
                        state = state.copy(seedLocked = !state.seedLocked)
                    },
                    modifier = Modifier.weight(1f),
                ) {
                    Text(
                        stringResource(
                            if (state.seedLocked) R.string.aura_unlock_seed else R.string.aura_lock_seed,
                        ),
                    )
                }
                OutlinedButton(onClick = {
                    updateSeed(SeedPolicy.roll().toString())
                }, modifier = Modifier.weight(1f)) {
                    Text(stringResource(R.string.aura_random_seed))
                }
            }
        }

        AuraSection(stringResource(R.string.aura_model_options)) {
            CompactSwitchRow(
                title = stringResource(R.string.aura_v_prediction),
                summary = stringResource(R.string.aura_v_prediction_note),
                checked = state.vPred,
                onCheckedChange = { value ->
                    saveModelBoolean(KEY_V_PRED, value)
                    state = state.copy(vPred = value)
                },
            )
            CompactSwitchRow(
                title = stringResource(R.string.aura_image_refine),
                summary = stringResource(R.string.aura_image_refine_note),
                checked = state.imageRefine,
                onCheckedChange = { value ->
                    saveModelBoolean(KEY_IMAGE_REFINE, value)
                    state = state.copy(imageRefine = value)
                },
            )
            if (state.imageRefine) {
                PostInput(
                    label = stringResource(R.string.aura_image_refine_prompt),
                    value = state.imageRefinePrompt,
                    onValueChange = { value ->
                        saveText(KEY_IMAGE_REFINE_PROMPT, value)
                        state = state.copy(imageRefinePrompt = value)
                    },
                    minLines = 3,
                    maxLines = 6,
                )
                ValueSlider(
                    label = stringResource(R.string.aura_image_refine_strength),
                    value = state.imageRefineStrength,
                    valueText = stringResource(R.string.format_decimal_two_places, state.imageRefineStrength),
                    range = 0.2f..0.7f,
                    onValueChange = { value ->
                        saveFloat(KEY_IMAGE_REFINE_STRENGTH, value)
                        state = state.copy(imageRefineStrength = value)
                    },
                )
            }
        }
        Spacer(Modifier.height(8.dp))
    }
}

@Composable
private fun AuraController.SamplingAndRuntimeSettings() {
    AuraSection(stringResource(R.string.section_sampling)) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(
                Triple(R.string.aura_steps, state.steps) { value: String ->
                    saveText(KEY_STEPS, value)
                    state = state.copy(steps = value)
                },
                Triple(R.string.aura_guidance, state.cfg) { value: String ->
                    saveText(KEY_CFG, value)
                    state = state.copy(cfg = value)
                },
            ).forEach { (label, value, update) ->
                NumericField(
                    label = stringResource(label),
                    value = value,
                    decimal = label == R.string.aura_guidance,
                    onValueChange = update,
                    modifier = Modifier.weight(1f),
                )
            }
        }
        Text(
            stringResource(R.string.aura_steps_cfg_note),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        EnumSelector(
            title = stringResource(R.string.aura_sampler),
            selected = stringResource(SamplerType.fromName(state.sampler).label()),
            options = SamplerType.entries.map { option ->
                stringResource(option.label()) to {
                    saveText(KEY_SAMPLER, option.name)
                    state = state.copy(sampler = option.name)
                }
            },
        )
        EnumSelector(
            title = stringResource(R.string.aura_schedule),
            selected = stringResource(
                runCatching { Schedule.valueOf(state.schedule) }.getOrDefault(Schedule.KARRAS).label(),
            ),
            options = Schedule.entries.map { option ->
                stringResource(option.label()) to {
                    saveText(KEY_SCHEDULE, option.name)
                    state = state.copy(schedule = option.name)
                }
            },
        )
        Text(
            stringResource(R.string.aura_lcm_note),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }

    if (state.selectedModel?.runtime == SdRuntimeType.MNN) {
        RuntimeChoices(
            title = stringResource(R.string.aura_processor),
            selected = state.backend,
            options = MnnBackend.entries.map { it.name to stringResource(it.label()) },
            note = stringResource(R.string.aura_processor_note),
        ) { backend ->
            saveText(KEY_BACKEND, backend)
            state = state.copy(backend = backend)
            unloadModel()
        }
        RuntimeChoices(
            title = stringResource(R.string.aura_memory),
            selected = state.memoryPolicy,
            options = MnnMemoryPolicy.entries.map { it.name to stringResource(it.label()) },
            note = stringResource(MnnMemoryPolicy.fromName(state.memoryPolicy).note()),
        ) { policy ->
            saveText(KEY_MEMORY_POLICY, policy)
            state = state.copy(memoryPolicy = policy)
        }
    }

}

@Composable
private fun RuntimeChoices(title: String, selected: String, options: List<Pair<String, String>>, note: String, onSelect: (String) -> Unit) {
    AuraSection(title) {
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            options.forEach { (value, label) ->
                FilterChip(
                    selected = selected == value,
                    onClick = { onSelect(value) },
                    label = { Text(label) },
                )
            }
        }
        Text(
            note,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
internal fun AuraBackendsPage(state: AuraState, onOpen: (AuraEngine) -> Unit) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)) {
        Text(stringResource(R.string.aura_backends_title), style = MaterialTheme.typography.headlineSmall)
        Text(stringResource(R.string.aura_backends_note), style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 8.dp, bottom = 24.dp))
        AuraEngine.entries.forEach { engine ->
            HorizontalDivider()
            Column(Modifier.fillMaxWidth().clickable(role = Role.Button) { onOpen(engine) }.padding(vertical = 20.dp)) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(stringResource(engine.label), style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                    if (state.engine == engine) Text(stringResource(R.string.cloud_active),
                        style = MaterialTheme.typography.labelMedium, color = AccentSoft)
                }
                Text(stringResource(when (engine) { AuraEngine.LOCAL -> R.string.aura_local_backend_note; AuraEngine.WEBUI -> R.string.aura_remote_backend_note; AuraEngine.LOCAL_DREAM -> R.string.aura_local_dream_note; AuraEngine.LAN -> R.string.aura_lan_note }),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 6.dp))
            }
        }
        HorizontalDivider()
    }
}
