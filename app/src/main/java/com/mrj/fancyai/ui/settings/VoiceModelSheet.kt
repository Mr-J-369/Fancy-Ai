package com.mrj.fancyai.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SheetState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.mrj.fancyai.R
import com.mrj.fancyai.service.voice.CloudVoiceModel
import com.mrj.fancyai.service.voice.VoiceModelKind
import com.mrj.fancyai.ui.kit.PostInput
import com.mrj.fancyai.ui.theme.Accent
import com.mrj.fancyai.ui.theme.AccentSoft

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun VoiceModelSheet(
    controller: VoiceSettingsController,
    kind: VoiceModelKind,
    sheetState: SheetState,
) {
    val selection = if (kind == VoiceModelKind.STT) controller.stt else controller.tts
    val provider = selection.provider
    val model = selection.model
    ModalBottomSheet(
        onDismissRequest = controller::dismissCatalog,
        sheetState = sheetState,
        dragHandle = null,
        containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .fillMaxHeight(0.82f)
                .navigationBarsPadding()
                .imePadding()
                .padding(horizontal = 20.dp, vertical = 18.dp),
        ) {
            Text(
                text = stringResource(
                    if (kind == VoiceModelKind.STT) {
                        R.string.voice_stt_models
                    } else {
                        R.string.voice_tts_models
                    },
                ),
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = stringResource(R.string.voice_models_provider, stringResource(provider.label)),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 3.dp),
            )
            PostInput(
                value = model,
                onValueChange = { controller.setModel(kind, it) },
                label = stringResource(R.string.cloud_model_id),
                singleLine = true,
                keyboardOptions = KeyboardOptions.Default,
                modifier = Modifier.fillMaxWidth().padding(top = 14.dp),
            )
            Text(
                stringResource(R.string.cloud_model_id_summary),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
            )
            VoiceModelCatalog(controller, kind)
            SettingsAction(
                text = stringResource(R.string.action_done),
                enabled = model.isNotBlank(),
                onClick = controller::dismissCatalog,
            )
        }
    }
}

@Composable
private fun ColumnScope.VoiceModelCatalog(controller: VoiceSettingsController, kind: VoiceModelKind) {
    val context = LocalContext.current
    val selection = if (kind == VoiceModelKind.STT) controller.stt else controller.tts
    val provider = selection.provider
    val model = selection.model
    val query = selection.query
    val models = selection.models
    val loading = controller.catalogLoading == kind
    val error = controller.catalogError
    val terms = query.trim().lowercase().split(' ').filter(String::isNotBlank)
    val matches = if (terms.isEmpty()) models else models.filter { candidate ->
        val id = candidate.id.lowercase()
        terms.all(id::contains)
    }
    Row(
        Modifier.fillMaxWidth().padding(top = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = stringResource(R.string.voice_compatible_models),
            style = MaterialTheme.typography.labelSmall,
            color = AccentSoft,
            modifier = Modifier.weight(1f),
        )
        Text(
            text = stringResource(
                if (loading) R.string.cloud_loading_models else R.string.cloud_refresh_models,
            ),
            style = MaterialTheme.typography.labelMedium,
            color = if (loading) MaterialTheme.colorScheme.outline else Accent,
            modifier = Modifier
                .heightIn(min = 48.dp)
                .clickable(enabled = !loading, role = Role.Button, onClick = { controller.fetchModels(kind) })
                .padding(start = 16.dp, top = 15.dp),
        )
    }
    if (models.isNotEmpty()) {
        PostInput(
            value = query,
            onValueChange = {
                VoiceSettingsStore.saveModelQuery(context, kind, provider, it)
                selection.query = it
            },
            label = stringResource(R.string.cloud_search_models),
            singleLine = true,
            keyboardOptions = KeyboardOptions.Default,
            modifier = Modifier.fillMaxWidth(),
        )
        Text(
            pluralStringResource(R.plurals.voice_models_shown, matches.size, matches.size),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
        )
    }
    LazyColumn(
        modifier = Modifier.fillMaxWidth().weight(1f),
        contentPadding = PaddingValues(top = 8.dp, bottom = 16.dp),
    ) {
        if (error != 0 || (!loading && models.isEmpty())) {
            item {
                Text(
                    text = stringResource(if (error != 0) error else R.string.voice_refresh_catalog),
                    style = MaterialTheme.typography.bodySmall,
                    color = if (error != 0) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(vertical = 12.dp),
                )
            }
        }
        items(matches, key = CloudVoiceModel::id) { candidate ->
            SettingsModelRow(
                id = candidate.id,
                selected = model.trim().removePrefix("models/") == candidate.id,
            ) {
                controller.setModel(kind, candidate.id)
                controller.dismissCatalog()
            }
        }
    }
}
