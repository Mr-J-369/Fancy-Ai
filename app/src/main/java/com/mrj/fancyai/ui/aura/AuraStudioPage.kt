package com.mrj.fancyai.ui.aura

import android.text.format.DateUtils
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import com.mrj.fancyai.R
import com.mrj.fancyai.sd.MnnBackend
import com.mrj.fancyai.sd.SamplerType
import com.mrj.fancyai.sd.Schedule
import com.mrj.fancyai.sd.SdModelType
import com.mrj.fancyai.sd.SdRuntimeType
import com.mrj.fancyai.ui.kit.PostInput
import com.mrj.fancyai.ui.theme.AccentSoft
import com.mrj.fancyai.ui.theme.Danger
import kotlin.math.ceil

internal const val TOKENS_PER_CHUNK = 77

@Composable
internal fun AuraController.StudioPage(
    onChooseSource: () -> Unit,
    onOpenResult: () -> Unit,
) {
    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        StudioResultSection(onOpenResult)
        StudioModelSection()
        StudioPromptSection()
        StudioSourceSection(onChooseSource)
        Spacer(Modifier.height(8.dp))
    }
}

@Composable
private fun AuraController.StudioResultSection(onOpenResult: () -> Unit) {
    val result = state.result ?: return
    AuraSection(stringResource(R.string.aura_result_section)) {
        Image(
            bitmap = result.asImageBitmap(),
            contentDescription = stringResource(R.string.aura_result_description),
            contentScale = ContentScale.Fit,
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(result.width.toFloat() / result.height)
                .clip(MaterialTheme.shapes.medium)
                .background(Color.Black)
                .clickable(role = Role.Image, onClick = onOpenResult),
        )
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            OutlinedButton(onClick = { saveResult(false) }) { Text(stringResource(R.string.action_save)) }
            OutlinedButton(onClick = { saveResult(true) }) { Text(stringResource(R.string.action_share)) }
        }
        state.resultMetadata?.let { metadata ->
            Text(
                stringResource(R.string.aura_image_details),
                style = MaterialTheme.typography.labelMedium,
                color = AccentSoft,
            )
            Text(
                stringResource(
                    R.string.aura_image_details_summary,
                    metadata.model,
                    metadata.runtime,
                    metadata.width,
                    metadata.height,
                    metadata.steps,
                    stringResource(R.string.format_decimal_two_places, metadata.cfg),
                    if (metadata.runtime == AuraEngine.LOCAL_DREAM.name) metadata.schedule else if (metadata.isRemote) metadata.sampler else stringResource(SamplerType.fromName(metadata.sampler).label()),
                    if (metadata.isRemote) {
                        stringResource(if (metadata.runtime == AuraEngine.LOCAL_DREAM.name) R.string.aura_local_dream else R.string.aura_webui)
                    } else {
                        stringResource(
                            runCatching { Schedule.valueOf(metadata.schedule) }.getOrDefault(Schedule.KARRAS).label(),
                        )
                    },
                    metadata.seed,
                    if (metadata.isRemote) {
                        stringResource(if (metadata.runtime == AuraEngine.LOCAL_DREAM.name) R.string.aura_local_dream else R.string.aura_webui)
                    } else if (metadata.runtime == SdRuntimeType.MNN.name) {
                        stringResource(MnnBackend.fromName(metadata.backend).label())
                    } else {
                        stringResource(R.string.aura_runtime_qnn)
                    },
                ),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            metadata.generationDurationMs?.let { durationMs ->
                Text(
                    stringResource(
                        R.string.aura_generation_duration,
                        DateUtils.formatElapsedTime((durationMs + 500L) / 1000L),
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = AccentSoft,
                )
            }
            TextButton(onClick = ::copyResultDetails) {
                Text(stringResource(R.string.aura_copy_details), color = AccentSoft)
            }
        }
    }
}

@Composable
private fun AuraController.StudioModelSection() {
    AuraSection(stringResource(R.string.aura_model_section)) {
        val model = state.selectedModel
        if (state.engine == AuraEngine.LAN) {
            Selector(title = stringResource(R.string.aura_lan), detail = state.lanAddress) { page = AuraPage.MODELS }
        } else if (state.engine != AuraEngine.LOCAL) {
            Selector(
                title = state.remote.model.ifBlank { stringResource(if (state.engine == AuraEngine.LOCAL_DREAM) R.string.aura_local_dream else R.string.aura_remote_choose_model) },
                detail = stringResource(state.engine.label),
                onClick = { page = AuraPage.MODELS },
            )
        } else if (model == null) {
            Text(
                stringResource(R.string.aura_no_model),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            OutlinedButton(onClick = { page = AuraPage.MODELS }, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.aura_open_models))
            }
        } else {
            Selector(
                title = model.file.name,
                detail = model.description(),
                onClick = { page = AuraPage.MODELS },
            )
            if (model.orientations.size > 1) {
                Text(
                    stringResource(R.string.aura_orientation),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    model.orientations.forEach { orientation ->
                        FilterChip(
                            selected = state.orientation == orientation,
                            onClick = {
                                saveText(KEY_ORIENTATION, orientation.name)
                                state = state.copy(orientation = orientation)
                            },
                            label = { Text(stringResource(orientation.label())) },
                        )
                    }
                }
            }
            if (model.type == SdModelType.DIT) {
                Text(
                    stringResource(R.string.aura_dimensions_section),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    NumericField(
                        label = stringResource(R.string.aura_width),
                        value = state.customWidth,
                        decimal = false,
                        onValueChange = { value ->
                            saveText(KEY_CUSTOM_WIDTH, value)
                            state = state.copy(customWidth = value)
                        },
                        modifier = Modifier.weight(1f),
                    )
                    NumericField(
                        label = stringResource(R.string.aura_height),
                        value = state.customHeight,
                        decimal = false,
                        onValueChange = { value ->
                            saveText(KEY_CUSTOM_HEIGHT, value)
                            state = state.copy(customHeight = value)
                        },
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }
    }
}

@Composable
private fun AuraController.StudioPromptSection() {
    AuraSection(stringResource(R.string.aura_prompt_section)) {
        if (state.engine != AuraEngine.LAN) PostInput(
            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences, autoCorrectEnabled = true),
            label = stringResource(R.string.aura_prompt_prefix),
            value = state.promptPrefix,
            onValueChange = { value ->
                saveText(KEY_PROMPT_PREFIX, value)
                state = state.copy(promptPrefix = value, error = null)
            },
            minLines = 1,
            maxLines = 4,
        )
        PostInput(
            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences, autoCorrectEnabled = true),
            label = stringResource(R.string.aura_prompt),
            value = state.prompt,
            onValueChange = { value ->
                saveText(KEY_PROMPT, value)
                state = state.copy(prompt = value, error = null)
            },
            minLines = 3,
            maxLines = 9,
        )
        if (state.engine != AuraEngine.LAN) PostInput(
            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences, autoCorrectEnabled = true),
            label = stringResource(R.string.aura_negative_prompt),
            value = state.negativePrompt,
            onValueChange = { value ->
                saveText(KEY_NEGATIVE_PROMPT, value)
                state = state.copy(negativePrompt = value, error = null)
            },
            minLines = 1,
            maxLines = 5,
        )
        val tokens = state.tokenCount
        if (tokens != null) {
            val limit = state.selectedModel?.chunkLimit
            val chunks = ceil(tokens.coerceAtLeast(1) / TOKENS_PER_CHUNK.toDouble()).toInt()
            Text(
                text = if (limit == null) {
                    stringResource(R.string.aura_token_count_unlimited, tokens, chunks)
                } else {
                    stringResource(R.string.aura_token_count_limited, tokens, chunks, limit)
                },
                style = MaterialTheme.typography.bodySmall,
                color = if ((limit != null) && (chunks > limit)) Danger
                else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun AuraController.StudioSourceSection(onChooseSource: () -> Unit) {
    if (state.engine == AuraEngine.LAN) return
    AuraSection(stringResource(R.string.aura_source_image)) {
        state.sourcePreview?.let { source ->
            Image(
                bitmap = source.asImageBitmap(),
                contentDescription = stringResource(R.string.aura_source_image),
                contentScale = ContentScale.Fit,
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(source.width.toFloat() / source.height)
                    .clip(MaterialTheme.shapes.medium)
                    .background(Color.Black),
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(
                onClick = onChooseSource,
                enabled = state.hasGenerationModel,
                modifier = Modifier.weight(1f),
            ) {
                Text(stringResource(R.string.action_choose_image), style = MaterialTheme.typography.labelMedium)
            }
            if (state.sourcePath != null) {
                TextButton(onClick = ::removeSource, modifier = Modifier.weight(1f)) {
                    Text(stringResource(R.string.aura_remove_source), color = Danger)
                }
            }
        }
        if (state.sourcePath != null) {
            ValueSlider(
                label = stringResource(R.string.aura_denoising),
                value = state.denoising,
                valueText = stringResource(R.string.format_decimal_two_places, state.denoising),
                range = 0.05f..1f,
                onValueChange = { value ->
                    saveFloat(KEY_DENOISING, value)
                    state = state.copy(denoising = value)
                },
            )
            Text(
                stringResource(R.string.aura_denoising_note),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            Text(
                stringResource(R.string.aura_source_note),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
