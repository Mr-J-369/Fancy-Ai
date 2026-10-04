package com.mrj.fancyai.ui.aura

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
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
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.mrj.fancyai.R
import com.mrj.fancyai.sd.hd.UpscaleStyle
import com.mrj.fancyai.ui.theme.AccentSoft

@Composable
internal fun AuraController.EnhancePage() {
    if (state.engine == AuraEngine.LAN || state.engine == AuraEngine.LOCAL_DREAM) {
        Text(stringResource(state.enhancementAvailability.message), modifier = Modifier.padding(16.dp))
        return
    }
    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        EnhanceResultSection()
        EnhanceUpscalerSection()
        EnhanceRedrawSection()
        Spacer(Modifier.height(8.dp))
    }
}

@Composable
private fun AuraController.EnhanceResultSection() {
    AuraSection(stringResource(R.string.action_enhance)) {
        val result = state.result
        if (result == null) {
            Text(
                stringResource(R.string.aura_enhance_requires_image),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            Image(
                bitmap = result.asImageBitmap(),
                contentDescription = stringResource(R.string.aura_result_description),
                contentScale = ContentScale.Fit,
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(result.width.toFloat() / result.height)
                    .clip(MaterialTheme.shapes.medium)
                    .background(Color.Black),
            )
        }
        Text(
            stringResource(state.enhancementAvailability.message),
            style = MaterialTheme.typography.bodySmall,
            color = if (state.enhancementAvailability == EnhancementAvailability.READY) {
                MaterialTheme.colorScheme.onSurfaceVariant
            } else {
                AccentSoft
            },
        )
    }
}

@Composable
private fun AuraController.EnhanceUpscalerSection() {
    AuraSection(stringResource(R.string.aura_upscaler_style)) {
        if (state.engine != AuraEngine.LOCAL) {
            val upscalers = state.remoteCatalog?.upscalers.orEmpty()
            if (upscalers.isEmpty()) Text(stringResource(R.string.aura_remote_upscalers_empty))
            else EnumSelector(stringResource(R.string.aura_upscaler_style),
                state.remote.upscaler.ifBlank { stringResource(R.string.aura_remote_choose_upscaler) },
                upscalers.map { it to { updateRemoteSettings(state.remote.copy(upscaler = it)) } })
        } else {
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                UpscaleStyle.entries.forEach { style ->
                    FilterChip(
                        selected = state.upscalerStyle == style,
                        onClick = {
                            saveText(KEY_UPSCALER_STYLE, style.name)
                            state = state.copy(upscalerStyle = style)
                        },
                        label = { Text(stringResource(style.label())) },
                    )
                }
            }
            val installed = auraUpscalerFile(LocalContext.current, state.upscalerStyle).isFile
            Text(
                stringResource(
                    if (installed) R.string.aura_upscaler_installed
                    else R.string.aura_upscaler_not_installed,
                ),
                style = MaterialTheme.typography.bodySmall,
                color = if (installed) AccentSoft else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun AuraController.EnhanceRedrawSection() {
    AuraSection(stringResource(R.string.aura_redraw)) {
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            listOf(
                0f to R.string.aura_redraw_none,
                0.2f to R.string.aura_redraw_light,
                0.35f to R.string.aura_redraw_medium,
                0.5f to R.string.aura_redraw_strong,
            ).forEach { (strength, label) ->
                FilterChip(
                    selected = state.redrawStrength == strength,
                    onClick = {
                        saveFloat(KEY_REDRAW, strength)
                        state = state.copy(redrawStrength = strength)
                    },
                    label = { Text(stringResource(label)) },
                )
            }
        }
        Text(
            stringResource(
                if (state.redrawStrength == 0f) R.string.aura_redraw_none_note
                else R.string.aura_redraw_note,
            ),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
