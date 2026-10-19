package com.mrj.fancyai.ui.aura

import android.text.format.Formatter
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.mrj.fancyai.R
import com.mrj.fancyai.ui.theme.AccentSoft

@Composable
internal fun AuraController.ModelsPage(
    onRemove: (AuraModel) -> Unit,
    onExport: (AuraModel) -> Unit,
    onGetModels: () -> Unit,
) {
    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        InstalledModelsSection(
            onRemove = onRemove,
            onExport = onExport,
            onGetModels = onGetModels,
        )
        Spacer(Modifier.height(8.dp))
    }
}

@Composable
private fun AuraController.InstalledModelsSection(
    onRemove: (AuraModel) -> Unit,
    onExport: (AuraModel) -> Unit,
    onGetModels: () -> Unit,
) {
    AuraSection(stringResource(R.string.section_installed_models)) {
        if (state.models.isEmpty()) {
            Text(
                stringResource(R.string.aura_no_model),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            OutlinedButton(
                onClick = onGetModels,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(stringResource(R.string.aura_tab_get_models))
            }
        } else {
            state.models.forEach { model ->
                ModelRow(
                    model = model,
                    selected = model.file.path == state.selectedModelPath,
                    enabled = (state.operation == null) && !state.generating,
                    onSelect = { selectModel(model) },
                    onExport = { onExport(model) },
                    onRemove = { onRemove(model) },
                )
            }
        }
        if (state.selectedModel != null) {
            TextButton(
                onClick = ::unloadModel,
                enabled = !state.generating && (state.operation == null),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(stringResource(R.string.aura_unload_model), color = AccentSoft)
            }
        }
    }
}

@Composable
internal fun AuraController.GetModelsPage(
    onImportModel: () -> Unit,
) {
    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        DownloadModelsSection()
        ImportModelsSection(onImportModel = onImportModel)
        Spacer(Modifier.height(8.dp))
    }
}

@Composable
private fun AuraController.DownloadModelsSection() {
    val uriHandler = LocalUriHandler.current
    AuraSection(stringResource(R.string.aura_tab_get_models)) {
        OutlinedButton(
            onClick = { uriHandler.openUri("https://huggingface.co/Mr-J-369") },
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(stringResource(R.string.aura_browse_models))
        }
        val actionEnabled = (state.operation == null) && !state.generating
        val context = LocalContext.current
        AuraDownloadRow(
            installed = state.models.any { it.file.name == STARTER_IMAGE_MODEL_ID },
            buttonText = stringResource(
                R.string.aura_download_starter,
                stringResource(R.string.aura_starter_model_name),
                Formatter.formatShortFileSize(context, STARTER_IMAGE_MODEL_BYTES),
            ),
            noteText = stringResource(R.string.aura_starter_note),
            readyText = stringResource(R.string.aura_starter_ready),
            enabled = actionEnabled,
            onDownload = ::installStarterModel,
        )
        AuraDownloadRow(
            installed = state.ditBaseInstalled,
            buttonText = stringResource(
                R.string.aura_download_dit_base,
                Formatter.formatShortFileSize(context, DIT_BASE_TOTAL_BYTES),
            ),
            noteText = stringResource(R.string.aura_dit_base_note),
            readyText = stringResource(R.string.aura_dit_base_ready),
            enabled = actionEnabled,
            onDownload = ::installDitBaseSuite,
        )
        AuraDownloadRow(
            installed = state.qwenComponentsInstalled,
            buttonText = stringResource(
                R.string.aura_download_qwen_components,
                Formatter.formatShortFileSize(context, QWEN_COMPONENTS_TOTAL_BYTES),
            ),
            noteText = stringResource(R.string.aura_qwen_components_note),
            readyText = stringResource(R.string.aura_qwen_components_ready),
            enabled = actionEnabled,
            onDownload = ::installQwenComponents,
        )
        AuraDownloadRow(
            installed = state.qwen3EncoderInstalled,
            buttonText = stringResource(
                R.string.aura_download_qwen3_encoder,
                Formatter.formatShortFileSize(context, QWEN3_8B_BYTES),
            ),
            noteText = stringResource(R.string.aura_qwen3_encoder_note),
            readyText = stringResource(R.string.aura_qwen3_encoder_ready),
            enabled = actionEnabled,
            onDownload = ::installQwen3Encoder,
        )
        AuraDownloadRow(
            installed = state.models.any { it.file.name == "flux2_klein_4b" },
            buttonText = stringResource(
                R.string.aura_download_flux2_klein,
                Formatter.formatShortFileSize(context, FLUX2_KLEIN_4B_BYTES),
            ),
            noteText = stringResource(R.string.aura_flux2_klein_note),
            readyText = stringResource(R.string.aura_flux2_klein_ready),
            enabled = actionEnabled,
            onDownload = ::installFlux2Klein,
        )
        AuraDownloadRow(
            installed = state.models.any { it.file.name == "flux2_klein_9b" },
            buttonText = stringResource(
                R.string.aura_download_klein9b,
                Formatter.formatShortFileSize(context, KLEIN9B_BYTES),
            ),
            noteText = stringResource(R.string.aura_klein9b_note),
            readyText = stringResource(R.string.aura_klein9b_ready),
            enabled = actionEnabled,
            onDownload = ::installKlein9b,
        )
        AuraDownloadRow(
            installed = state.models.any { it.file.name == "z_image_turbo_q4_0" },
            buttonText = stringResource(
                R.string.aura_download_zit_q4,
                Formatter.formatShortFileSize(context, ZIT_BYTES),
            ),
            noteText = stringResource(R.string.aura_zit_q4_note),
            readyText = stringResource(R.string.aura_zit_q4_ready),
            enabled = actionEnabled,
            onDownload = ::installZitKhv,
        )
        AuraDownloadRow(
            installed = state.models.any { it.file.name.contains("qwen", ignoreCase = true) },
            buttonText = stringResource(
                R.string.aura_download_qwen_image_2_1,
                Formatter.formatShortFileSize(context, QWEN_IMAGE_2_1_BYTES),
            ),
            noteText = stringResource(R.string.aura_qwen_image_2_1_note),
            readyText = stringResource(R.string.aura_qwen_image_2_1_ready),
            enabled = actionEnabled,
            onDownload = ::installQwenImage21,
        )
    }
}

@Composable
private fun AuraDownloadRow(
    installed: Boolean,
    buttonText: String,
    noteText: String,
    readyText: String,
    enabled: Boolean,
    onDownload: () -> Unit,
) {
    if (!installed) {
        Button(
            onClick = onDownload,
            enabled = enabled,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(buttonText)
        }
        Text(
            noteText,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    } else {
        Text(
            readyText,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.primary,
        )
    }
}

@Composable
private fun AuraController.ImportModelsSection(
    onImportModel: () -> Unit,
) {
    AuraSection(stringResource(R.string.aura_import_model)) {
        Text(
            stringResource(R.string.aura_import_family),
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            ImportFamilyRow(AuraImportFamily.KLEIN_4B, R.string.aura_import_family_klein4b)
            ImportFamilyRow(AuraImportFamily.KLEIN_9B, R.string.aura_import_family_klein9b)
            ImportFamilyRow(AuraImportFamily.ZIT, R.string.aura_import_family_zit)
            ImportFamilyRow(AuraImportFamily.QWEN_21, R.string.aura_import_family_qwen21)
            ImportFamilyRow(AuraImportFamily.SDXL_MNN, R.string.aura_import_family_sdxl_mnn)
            ImportFamilyRow(AuraImportFamily.SDXL_QNN, R.string.aura_import_family_sdxl_qnn)
            ImportFamilyRow(AuraImportFamily.SD15_QNN, R.string.aura_import_family_sd15_qnn)
        }
        Button(
            onClick = onImportModel,
            enabled = (state.operation == null) && !state.generating,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(stringResource(R.string.aura_import_model))
        }
        Text(
            stringResource(R.string.aura_import_model_note),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun AuraController.ImportFamilyRow(family: AuraImportFamily, label: Int) {
    FilterChip(
        selected = importFamily == family,
        onClick = { importFamily = family },
        enabled = (state.operation == null) && !state.generating,
        label = { Text(stringResource(label)) },
        modifier = Modifier.fillMaxWidth(),
    )
}
