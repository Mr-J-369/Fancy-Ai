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
import com.mrj.fancyai.ui.settings.ExternalModelPicker
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
            installed = state.chromaComponentsInstalled,
            buttonText = stringResource(
                R.string.aura_download_chroma_components,
                Formatter.formatShortFileSize(context, CHROMA_COMPONENTS_TOTAL_BYTES),
            ),
            noteText = stringResource(R.string.aura_chroma_components_note),
            readyText = stringResource(R.string.aura_chroma_components_ready),
            enabled = actionEnabled,
            onDownload = ::installChromaComponents,
        )
        AuraDownloadRow(
            installed = state.mossComponentsInstalled,
            buttonText = stringResource(
                R.string.aura_download_moss_components,
                Formatter.formatShortFileSize(context, MOSS_COMPONENTS_TOTAL_BYTES),
            ),
            noteText = stringResource(R.string.aura_moss_components_note),
            readyText = stringResource(R.string.aura_moss_components_ready),
            enabled = actionEnabled,
            onDownload = ::installMossComponents,
        )
        AuraDownloadRow(
            installed = state.kreaComponentsInstalled,
            buttonText = stringResource(
                R.string.aura_download_krea_components,
                Formatter.formatShortFileSize(context, KREA2_COMPONENTS_TOTAL_BYTES),
            ),
            noteText = stringResource(R.string.aura_krea_components_note),
            readyText = stringResource(R.string.aura_krea_components_ready),
            enabled = actionEnabled,
            onDownload = ::installKreaComponents,
        )
        AuraDownloadRow(
            installed = state.animaComponentsInstalled,
            buttonText = stringResource(
                R.string.aura_download_anima_components,
                Formatter.formatShortFileSize(context, ANIMA_COMPONENTS_TOTAL_BYTES),
            ),
            noteText = stringResource(R.string.aura_anima_components_note),
            readyText = stringResource(R.string.aura_anima_components_ready),
            enabled = actionEnabled,
            onDownload = ::installAnimaComponents,
        )
        AuraDownloadRow(
            installed = state.upscalerInstalled,
            buttonText = stringResource(
                R.string.aura_download_upscaler,
                Formatter.formatShortFileSize(context, UPSCALER_MODEL_BYTES),
            ),
            noteText = stringResource(R.string.aura_upscaler_note),
            readyText = stringResource(R.string.aura_upscaler_ready),
            enabled = actionEnabled,
            onDownload = { installUpscalerSuite() },
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
        EnumSelector(
            title = stringResource(R.string.aura_import_family),
            selected = stringResource(importFamily.labelRes()),
            options = AuraImportFamily.entries.map { family ->
                stringResource(family.labelRes()) to { importFamily = family }
            },
        )
        Button(
            onClick = onImportModel,
            enabled = (state.operation == null) && !state.generating,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(stringResource(R.string.aura_import_model))
        }
        OutlinedButton(
            onClick = { showFilePicker = true },
            enabled = (state.operation == null) && !state.generating,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(stringResource(R.string.aura_browse_files))
        }
        if (showFilePicker) {
            ExternalModelPicker(
                onDismiss = { showFilePicker = false },
                onSelect = { path ->
                    showFilePicker = false
                    linkExternalModel(path)
                },
                extensions = setOf("gguf", "safetensors"),
            )
        }
        Text(
            stringResource(R.string.aura_import_model_note),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
