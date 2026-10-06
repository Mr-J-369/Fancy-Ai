package com.mrj.fancyai.ui.settings

import android.Manifest
import android.content.pm.PackageManager
import android.text.format.Formatter
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLocale
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.mrj.fancyai.R
import com.mrj.fancyai.service.voice.LocalVoicePack
import com.mrj.fancyai.service.voice.SavedVoice
import com.mrj.fancyai.service.voice.VoiceLibrary
import com.mrj.fancyai.service.voice.VoiceModelKind
import com.mrj.fancyai.ui.kit.AppDialog
import com.mrj.fancyai.ui.kit.PostInput
import com.mrj.fancyai.ui.theme.Accent
import java.io.File
import java.util.Locale

@Composable
internal fun LocalVoiceControls(
    kind: VoiceModelKind,
    model: String,
    voice: String,
    sample: String,
    onModel: (String) -> Unit,
    onVoice: (String) -> Unit,
    onChanged: () -> Unit,
    stopSpeech: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val controller = remember(context, scope) { LocalVoiceController(context, scope) }
    DisposableEffect(controller) { onDispose { controller.downloads.cancel() } }
    var deleteTarget by remember { mutableStateOf<LocalVoicePack?>(null) }
    val packs = if (kind == VoiceModelKind.STT) listOf(LocalVoicePack.WHISPER, LocalVoicePack.ZIPFORMER)
        else listOf(LocalVoicePack.POCKET, LocalVoicePack.KOKORO, LocalVoicePack.SUPERTONIC)
    Column(Modifier.padding(top = 12.dp)) {
        if (kind == VoiceModelKind.TTS) {
            Text(
                stringResource(R.string.voice_language, LocalLocale.current.platformLocale.displayName),
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        packs.forEach { pack ->
            VoicePackItem(
                controller = controller,
                pack = pack,
                kind = kind,
                model = model,
                voice = voice,
                sample = sample,
                onModel = onModel,
                onVoice = onVoice,
                onChanged = onChanged,
                stopSpeech = stopSpeech,
                onDeleteRequest = { deleteTarget = it },
            )
            if (deleteTarget == pack) {
                AppDialog(
                    onDismissRequest = { deleteTarget = null },
                    title = { Text(stringResource(R.string.delete_named_title, stringResource(pack.title))) },
                    text = { Text(stringResource(if (pack == LocalVoicePack.WHISPER) R.string.voice_pack_delete_whisper else R.string.voice_pack_delete_detail)) },
                    confirmButton = {
                        TextButton(
                            onClick = {
                                deleteTarget = null
                                controller.remove(pack, model, stopSpeech, onModel, onChanged)
                            },
                        ) {
                            Text(stringResource(R.string.voice_pack_delete), color = MaterialTheme.colorScheme.error)
                        }
                    },
                    dismissButton = {
                        TextButton(onClick = { deleteTarget = null }) {
                            Text(stringResource(android.R.string.cancel))
                        }
                    },
                )
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        }
        if (controller.error != 0) {
            Text(
                stringResource(controller.error),
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

@Composable
private fun VoicePackItem(
    controller: LocalVoiceController,
    pack: LocalVoicePack,
    kind: VoiceModelKind,
    model: String,
    voice: String,
    sample: String,
    onModel: (String) -> Unit,
    onVoice: (String) -> Unit,
    onChanged: () -> Unit,
    stopSpeech: () -> Unit,
    onDeleteRequest: (LocalVoicePack) -> Unit,
) {
    val context = LocalContext.current
    val installed = remember(controller.revision, pack) { LocalVoicePack.ready(context, pack.id) }
    val selected = (model == pack.id) && installed
    val present = remember(controller.revision, pack) { File(context.filesDir, "voice/models/${pack.directory}").exists() }
    val needed = (if (pack == LocalVoicePack.ZIPFORMER) listOf(LocalVoicePack.WHISPER, pack) else listOf(pack))
        .filterNot { it.installed(context) }
    Column(Modifier.fillMaxWidth().padding(vertical = 12.dp)) {
        Text(
            stringResource(pack.title),
            style = MaterialTheme.typography.titleMedium,
            color = if (model == pack.id) Accent else MaterialTheme.colorScheme.onSurface,
        )
        Text(
            stringResource(pack.detail),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 4.dp),
        )
        if (controller.downloading == pack) {
            Text(
                stringResource(
                    if (controller.extracting) R.string.voice_pack_preparing else R.string.voice_pack_downloading,
                    stringResource(controller.activeTitle),
                    (controller.progress * 100).toInt(),
                ),
                style = MaterialTheme.typography.labelMedium,
                color = Accent,
                modifier = Modifier.padding(top = 10.dp),
            )
            LinearProgressIndicator(progress = { controller.progress }, modifier = Modifier.fillMaxWidth().padding(top = 10.dp))
            TextButton(onClick = { controller.downloads.cancel(); controller.downloadJob?.cancel() }) { Text(stringResource(R.string.voice_pack_cancel)) }
        } else {
            val bytes = needed.sumOf { it.bytes }
            FlowRow {
                TextButton(
                    enabled = !controller.busy && !selected,
                    onClick = {
                        controller.select(pack, installed, needed, stopSpeech, onModel, onChanged)
                    },
                ) {
                    Text(
                        if (selected) stringResource(R.string.status_selected)
                        else if (installed) stringResource(R.string.voice_pack_select)
                        else stringResource(R.string.vision_download_recommended, Formatter.formatFileSize(context, bytes)),
                    )
                }
                if (present) {
                    TextButton(
                        enabled = !controller.busy,
                        onClick = { onDeleteRequest(pack) },
                    ) {
                        Text(stringResource(R.string.voice_pack_delete), color = MaterialTheme.colorScheme.error)
                    }
                }
            }
            if (controller.deleting == pack) {
                Text(stringResource(R.string.voice_pack_deleting), style = MaterialTheme.typography.labelMedium)
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            }
        }
        if ((kind == VoiceModelKind.TTS) && (model == pack.id)) {
            SavedVoiceControls(controller, model, voice, sample, onVoice, onChanged, stopSpeech)
        }
    }
}

@Composable
private fun SavedVoiceControls(
    controller: LocalVoiceController,
    model: String,
    voice: String,
    sample: String,
    onVoice: (String) -> Unit,
    onChanged: () -> Unit,
    stopSpeech: () -> Unit,
) {
    val context = LocalContext.current
    var choosing by remember { mutableStateOf(value = false) }
    val voices = remember(controller.revision) { VoiceLibrary.voices(context) }
    val selected = voices.firstOrNull { (m, i) -> (m == model) && (i == voice) }
    TextButton(onClick = { stopSpeech(); choosing = true }) {
        Text(selected?.name ?: stringResource(R.string.voice_library_choose))
    }
    if (choosing) {
        VoiceChooser(
            voices.filter { (m) -> m == model },
            "$model:$voice",
            onSelect = { onVoice(it.id); choosing = false },
        ) { choosing = false }
    }
    if (model == LocalVoicePack.SUPERTONIC.id) {
        SupertonicLanguageControl(onChanged, stopSpeech)
    }
    if (model == LocalVoicePack.POCKET.id) {
        PocketVoiceEditor(
            sample,
            selected,
            stopSpeech,
            onSaved = { saved ->
                controller.revision++
                onVoice(saved.id)
                onChanged()
            },
        ) {
            controller.revision++
            onVoice("")
            onChanged()
        }
    }
}

@Composable
private fun SupertonicLanguageControl(onChanged: () -> Unit, stopSpeech: () -> Unit) {
    val context = LocalContext.current
    val locale = LocalLocale.current.platformLocale
    var language by remember { mutableStateOf(VoiceSettingsStore.supertonicLanguage(context)) }
    var choosing by remember { mutableStateOf(value = false) }
    val languageCodes = LocalVoicePack.supertonicLanguageCodes
    val languages = remember(locale, languageCodes) {
        languageCodes.asSequence().map { it to Locale.forLanguageTag(it).getDisplayLanguage(locale) }.sortedBy { (_, name) -> name }.toList()
    }
    TextButton(onClick = { stopSpeech(); choosing = true }) {
        Text(
            if (language.isBlank()) stringResource(R.string.voice_choose_language)
            else stringResource(R.string.voice_speech_language, Locale.forLanguageTag(language).getDisplayLanguage(locale)),
        )
    }
    Text(
        stringResource(R.string.voice_supertonic_language_detail),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    if (choosing) {
        AppDialog(
            onDismissRequest = { choosing = false },
            title = { Text(stringResource(R.string.voice_choose_language)) },
            text = {
                LazyColumn(Modifier.heightIn(max = 400.dp)) {
                    items(languages, key = { (code) -> code }) { (code, name) ->
                        Row(
                            modifier = Modifier.fillMaxWidth().clickable(role = Role.RadioButton) {
                                VoiceSettingsStore.saveSupertonicLanguage(context, code)
                                language = code
                                choosing = false
                                onChanged()
                            }.padding(vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            RadioButton(selected = language == code, onClick = null)
                            Text(name, modifier = Modifier.padding(start = 8.dp))
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { choosing = false }) {
                    Text(stringResource(android.R.string.cancel))
                }
            },
        )
    }
}

@Composable
private fun PocketVoiceEditor(
    sample: String,
    selected: SavedVoice?,
    stopSpeech: () -> Unit,
    onSaved: (SavedVoice) -> Unit,
    onDeleted: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val controller = remember(context, scope) { PocketVoiceController(context.applicationContext, scope) }
    DisposableEffect(controller) { onDispose { controller.close() } }
    with(controller) {
        val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
            if (it) record(stopSpeech) else error = R.string.voice_permission_denied
        }
        val importer = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            uri?.let { importSample(it, stopSpeech) }
        }
        Column(Modifier.padding(top = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(R.string.voice_library_new), style = MaterialTheme.typography.titleMedium)
            Text(
                stringResource(R.string.voice_library_sample_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (working) LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            if (recording) {
                Text(
                    pluralStringResource(R.plurals.voice_recording_seconds, elapsed, elapsed),
                    color = Accent,
                    style = MaterialTheme.typography.labelMedium,
                )
            }
            FlowRow {
                TextButton(
                    enabled = editable,
                    onClick = {
                        if (recording) recorder.finishSample()
                        else if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) record(stopSpeech)
                        else permission.launch(Manifest.permission.RECORD_AUDIO)
                    },
                ) {
                    Text(stringResource(if (recording) R.string.voice_library_finish else R.string.voice_library_record))
                }
                TextButton(enabled = idle, onClick = { importer.launch(arrayOf("audio/wav", "audio/x-wav")) }) {
                    Text(stringResource(R.string.voice_library_import))
                }
            }
            if (hasSample) Text(stringResource(R.string.voice_library_sample_ready), color = Accent, style = MaterialTheme.typography.labelMedium)
            PostInput(
                value = name,
                onValueChange = { name = it },
                label = stringResource(R.string.voice_library_name),
                singleLine = true,
                keyboardOptions = KeyboardOptions.Default,
                modifier = Modifier.fillMaxWidth(),
            )
            FlowRow {
                TextButton(
                    enabled = (canPreview && sample.isNotBlank()),
                    onClick = { preview(sample, stopSpeech) },
                ) {
                    Text(stringResource(if (playing) R.string.voice_library_stop else R.string.voice_library_preview))
                }
                TextButton(
                    enabled = (hasSample && name.isNotBlank() && idle),
                    onClick = { save(onSaved) },
                ) {
                    Text(stringResource(R.string.voice_library_save))
                }
            }
            if (selected != null) TextButton(enabled = idle, onClick = { deleting = true }) {
                Text(stringResource(R.string.voice_library_delete), color = MaterialTheme.colorScheme.error)
            }
            if (error != 0) Text(stringResource(error), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
        }
        if (deleting && (selected != null)) {
            AppDialog(
                onDismissRequest = { deleting = false },
                text = { Text(stringResource(R.string.voice_library_delete_confirm, selected.name)) },
                confirmButton = {
                    TextButton(
                        onClick = {
                            delete(selected, stopSpeech, onDeleted)
                        },
                    ) {
                        Text(stringResource(R.string.voice_library_delete))
                    }
                },
                dismissButton = {
                    TextButton(onClick = { deleting = false }) {
                        Text(stringResource(android.R.string.cancel))
                    }
                },
            )
        }
    }
}
@Composable
private fun VoiceChooser(
    voices: List<SavedVoice>,
    selected: String,
    onSelect: (SavedVoice) -> Unit,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    AppDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.voice_library_choose)) },
        text = {
            LazyColumn(Modifier.heightIn(max = 400.dp)) {
                if (voices.isEmpty()) item { Text(stringResource(R.string.voice_library_empty)) }
                items(voices, key = { it.key }) { voice ->
                    val ready = LocalVoicePack.ready(context, voice.model)
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable(enabled = ready, role = Role.RadioButton) { onSelect(voice) }
                            .padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(selected = selected == voice.key, enabled = ready, onClick = null)
                        Text(
                            voice.name,
                            modifier = Modifier.padding(start = 8.dp),
                            color = if (ready) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(android.R.string.cancel))
            }
        },
    )
}
