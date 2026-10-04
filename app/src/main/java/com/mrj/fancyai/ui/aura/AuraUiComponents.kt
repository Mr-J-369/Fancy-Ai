package com.mrj.fancyai.ui.aura

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.mrj.fancyai.R
import com.mrj.fancyai.sd.AuraOrientation
import com.mrj.fancyai.sd.MnnBackend
import com.mrj.fancyai.sd.MnnMemoryPolicy
import com.mrj.fancyai.sd.SamplerType
import com.mrj.fancyai.sd.Schedule
import com.mrj.fancyai.sd.SdModelType
import com.mrj.fancyai.sd.SdRuntimeType
import com.mrj.fancyai.sd.hd.UpscaleStyle
import com.mrj.fancyai.ui.kit.ImageGenerationProgress
import com.mrj.fancyai.ui.kit.PostInput
import com.mrj.fancyai.ui.theme.Accent
import com.mrj.fancyai.ui.theme.AccentSoft
import com.mrj.fancyai.ui.theme.Danger
import com.mrj.fancyai.ui.theme.Ink
import com.mrj.fancyai.ui.theme.SlateRaised

@Composable
internal fun AuraSection(title: String, content: @Composable () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(
            title,
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.SemiBold,
            color = AccentSoft,
        )
        content()
    }
}

@Composable
internal fun Selector(title: String, detail: String, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.medium)
            .background(SlateRaised)
            .border(1.dp, MaterialTheme.colorScheme.outline, MaterialTheme.shapes.medium)
            .clickable(onClick = onClick)
            .padding(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.width(3.dp).height(32.dp).background(Accent))
        Column(Modifier.weight(1f).padding(start = 10.dp)) {
            Text(title, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                detail,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 2.dp),
            )
        }
        Icon(painter = painterResource(R.drawable.ic_expand), contentDescription = null, tint = Accent, modifier = Modifier.size(22.dp))
    }
}

@Composable
internal fun ModelRow(
    model: AuraModel,
    selected: Boolean,
    enabled: Boolean,
    onSelect: () -> Unit,
    onExport: () -> Unit,
    onRemove: () -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(enabled = enabled, onClick = onSelect)
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .size(8.dp)
                .background(if (selected) Accent else MaterialTheme.colorScheme.outline, RoundedCornerShape(4.dp)),
        )
        Column(Modifier.weight(1f).padding(horizontal = 10.dp)) {
            Text(model.file.name, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                model.description(),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        TextButton(
            onClick = onExport,
            enabled = enabled,
        ) {
            Text(stringResource(R.string.action_export), style = MaterialTheme.typography.labelMedium)
        }
        TextButton(
            onClick = onRemove,
            enabled = enabled,
            colors = ButtonDefaults.textButtonColors(contentColor = Danger),
        ) {
            Text(stringResource(R.string.action_remove), style = MaterialTheme.typography.labelMedium)
        }
    }
}

@Composable
internal fun NumericField(
    label: String,
    value: String,
    decimal: Boolean,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    PostInput(
        value = value,
        onValueChange = { candidate ->
            if (candidate.all { it.isDigit() || (decimal && (it == '.')) || (it == '-') }) {
                onValueChange(candidate)
            }
        },
        label = label,
        singleLine = true,
        keyboardOptions = KeyboardOptions(
            keyboardType = if (decimal) KeyboardType.Decimal else KeyboardType.Number,
        ),
        modifier = modifier,
    )
}

@Composable
internal fun ValueSlider(
    label: String,
    value: Float,
    valueText: String,
    range: ClosedFloatingPointRange<Float>,
    onValueChange: (Float) -> Unit,
    enabled: Boolean = true,
) {
    Column {
        Row {
            Text(label, style = MaterialTheme.typography.labelMedium)
            Spacer(Modifier.weight(1f))
            Text(valueText, style = MaterialTheme.typography.labelMedium, color = Accent)
        }
        Slider(value = value, onValueChange = onValueChange, valueRange = range, enabled = enabled)
    }
}

@Composable
internal fun EnumSelector(
    title: String,
    selected: String,
    options: List<Pair<String, () -> Unit>>,
) {
    var expanded by remember { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(title, style = MaterialTheme.typography.labelMedium)
        Box {
            Selector(title = selected, detail = title) { expanded = true }
            DropdownMenu(
                expanded = expanded,
                onDismissRequest = { expanded = false },
                containerColor = SlateRaised,
            ) {
                options.forEach { (label, select) ->
                    DropdownMenuItem(
                        text = { Text(label, style = MaterialTheme.typography.bodyMedium) },
                        onClick = {
                            expanded = false
                            select()
                        },
                    )
                }
            }
        }
    }
}

@Composable
internal fun AuraModel.description(): String {
    val modelName = stringResource(
        when (type) {
            SdModelType.DIT -> R.string.aura_model_dit
            SdModelType.SDXL -> R.string.aura_model_sdxl
            else -> R.string.aura_model_sd15
        },
    )
    val runtimeName = stringResource(
        when (runtime) {
            SdRuntimeType.DIT -> R.string.aura_runtime_dit
            SdRuntimeType.MNN -> R.string.engines_mnn
            else -> R.string.aura_runtime_qnn
        },
    )
    val size = size(orientations.firstOrNull() ?: AuraOrientation.SQUARE)
    return stringResource(R.string.aura_model_details, modelName, runtimeName, size.first, size.second)
}

internal fun SamplerType.label(): Int = when (this) {
    SamplerType.EULER -> R.string.aura_sampler_euler
    SamplerType.EULER_A -> R.string.aura_sampler_euler_a
    SamplerType.HEUN -> R.string.aura_sampler_heun
    SamplerType.DPM2 -> R.string.aura_sampler_dpm2
    SamplerType.DPM2_A -> R.string.aura_sampler_dpm2_a
    SamplerType.LMS -> R.string.aura_sampler_lms
    SamplerType.DPMPP_2S_A -> R.string.aura_sampler_dpmpp_2s_a
    SamplerType.DPMPP_2M -> R.string.aura_sampler_dpmpp_2m
    SamplerType.DPMPP_SDE -> R.string.aura_sampler_dpmpp_sde
    SamplerType.DPMPP_2M_SDE -> R.string.aura_sampler_dpmpp_2m_sde
    SamplerType.DPMPP_3M_SDE -> R.string.aura_sampler_dpmpp_3m_sde
    SamplerType.DDIM -> R.string.aura_sampler_ddim
    SamplerType.LCM -> R.string.aura_sampler_lcm
}

internal fun Schedule.label(): Int = when (this) {
    Schedule.NORMAL -> R.string.aura_schedule_normal
    Schedule.KARRAS -> R.string.aura_schedule_karras
    Schedule.EXPONENTIAL -> R.string.aura_schedule_exponential
    Schedule.SGM_UNIFORM -> R.string.aura_schedule_sgm_uniform
    Schedule.SIMPLE -> R.string.aura_schedule_simple
    Schedule.DDIM_UNIFORM -> R.string.aura_schedule_ddim_uniform
    Schedule.BETA -> R.string.aura_schedule_beta
}

internal fun AuraOrientation.label(): Int = when (this) {
    AuraOrientation.SQUARE -> R.string.aura_square
    AuraOrientation.LANDSCAPE -> R.string.aura_landscape
    AuraOrientation.PORTRAIT -> R.string.aura_portrait
}

internal fun UpscaleStyle.label(): Int = when (this) {
    UpscaleStyle.PHOTO -> R.string.aura_style_photo
    UpscaleStyle.ANIME -> R.string.aura_style_drawing
}

internal fun MnnMemoryPolicy.label(): Int = when (this) {
    MnnMemoryPolicy.LOW_MEMORY -> R.string.aura_memory_low
    MnnMemoryPolicy.BALANCED -> R.string.aura_memory_balanced
    MnnMemoryPolicy.SPEED_FIRST -> R.string.aura_memory_speed
}

internal fun MnnBackend.label(): Int = when (this) {
    MnnBackend.AUTOMATIC -> R.string.aura_processor_auto
    MnnBackend.CPU -> R.string.engines_cpu
    MnnBackend.OPENCL -> R.string.engines_opencl
}

internal fun MnnMemoryPolicy.note(): Int = when (this) {
    MnnMemoryPolicy.LOW_MEMORY -> R.string.aura_memory_low_note
    MnnMemoryPolicy.BALANCED -> R.string.aura_memory_balanced_note
    MnnMemoryPolicy.SPEED_FIRST -> R.string.aura_memory_speed_note
}

@Composable
internal fun AuraNavigation(page: AuraPage, onPage: (AuraPage) -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(18.dp),
    ) {
        AuraPage.entries.filter { it.parent == null }.forEach { item ->
            Text(
                text = stringResource(item.label),
                style = MaterialTheme.typography.labelMedium,
                color = if (item == page.section) Accent else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier
                    .heightIn(min = 48.dp)
                    .clickable { onPage(item) }
                    .padding(vertical = 15.dp),
            )
        }
    }
    HorizontalDivider(color = MaterialTheme.colorScheme.outline)
}

@Composable
internal fun OperationBanner(status: String, progress: Float?) {
    Column(
        Modifier
            .fillMaxWidth()
            .background(SlateRaised)
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(status, style = MaterialTheme.typography.labelMedium, color = AccentSoft)
            Spacer(Modifier.weight(1f))
            progress?.let {
                Text(
                    stringResource(R.string.aura_progress, (it * 100).toInt()),
                    style = MaterialTheme.typography.labelMedium,
                    color = Accent,
                )
            }
        }
        progress?.let {
            LinearProgressIndicator(
                progress = { it.coerceIn(0f, 1f) },
                color = Accent,
                trackColor = MaterialTheme.colorScheme.outline,
                modifier = Modifier.fillMaxWidth().height(2.dp),
            )
        }
    }
}

@Composable
internal fun AuraController.AuraDock() {
    val action = dockAction() ?: return
    val label = when (action) {
        AuraDockAction.STOP -> R.string.action_stop
        AuraDockAction.CREATE -> R.string.aura_create
        AuraDockAction.INSTALL_UPSCALER -> R.string.aura_install_upscaler
        AuraDockAction.ENHANCE -> R.string.action_enhance
    }
    Column(
        Modifier
            .fillMaxWidth()
            .background(SlateRaised)
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        if (state.generating) {
            ImageGenerationProgress(state.progress, label = stringResource(R.string.aura_working))
        }
        Button(
            onClick = {
                when {
                    state.generating -> stop()
                    page == AuraPage.STUDIO -> generate()
                    action == AuraDockAction.INSTALL_UPSCALER -> installUpscaler(state.upscalerStyle)
                    else -> generate(enhance = true)
                }
            },
            enabled = action.enabled(state),
            colors = ButtonDefaults.buttonColors(
                containerColor = if (state.generating) MaterialTheme.colorScheme.surface else Accent,
                contentColor = if (state.generating) Accent else Ink,
            ),
            modifier = Modifier.fillMaxWidth().height(48.dp),
        ) {
            Text(stringResource(label), style = MaterialTheme.typography.labelMedium)
        }
    }
}
