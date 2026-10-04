package com.mrj.fancyai.ui.characters

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.mrj.fancyai.R
import com.mrj.fancyai.ui.kit.EditorField
import com.mrj.fancyai.ui.kit.ImageGenerationProgress
import com.mrj.fancyai.ui.settings.CharacterVoiceSetting
import com.mrj.fancyai.ui.theme.Accent
import com.mrj.fancyai.ui.theme.AccentSoft
import kotlinx.coroutines.launch

private enum class EditorTab { CARD, LOOK }

internal const val NEW_CHARACTER = "new"
internal const val BACKGROUND_MAX_EDGE = 2048

@Composable
internal fun CharacterEditorScreen(
    character: CharacterCard?,
    onSaved: (CharacterCard) -> Unit,
    editorKey: String = character?.id ?: NEW_CHARACTER,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var tab by remember { mutableStateOf(EditorTab.CARD) }
    val controller = remember(context, editorKey) {
        CharacterEditorController(context.applicationContext, editorKey, character)
    }
    DisposableEffect(controller) { onDispose { controller.avatarJob?.cancel() } }
    with(controller) {
        val avatar = remember(mediaRevision, editorKey) {
            previewImage(AVATAR_FILE, character?.avatarPath)
        }
        val background = remember(mediaRevision, editorKey) {
            previewImage(BACKGROUND_FILE, character?.backgroundPath)
        }
        val canSave = draft.name.isNotBlank() && !saving && !choosing && !generatingAvatar
        val avatarPicker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) {
            scope.launch { choose(it, AVATAR_FILE, AVATAR_MAX_EDGE) }
        }
        val backgroundPicker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) {
            scope.launch { choose(it, BACKGROUND_FILE, BACKGROUND_MAX_EDGE) }
        }

        LazyColumn(
            modifier = Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.ime),
            contentPadding = PaddingValues(top = 16.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            item { EditorTabs(selected = tab, onSelect = { tab = it }) }
            when (tab) {
                EditorTab.CARD -> item {
                    Text(text = stringResource(R.string.character_card_section).uppercase(), style = MaterialTheme.typography.labelSmall, color = AccentSoft)
                    EditorField(stringResource(R.string.field_name), draft.name, stringResource(R.string.character_name_hint), singleLine = true) { update(draft.copy(name = it)) }
                    EditorField(stringResource(R.string.field_handle), draft.handle, stringResource(R.string.character_handle_hint), singleLine = true) { update(draft.copy(handle = it)) }
                    EditorField(stringResource(R.string.field_personality), draft.personality, stringResource(R.string.character_personality_hint)) { update(draft.copy(personality = it)) }
                    EditorField(stringResource(R.string.field_description), draft.description, stringResource(R.string.character_description_hint)) { update(draft.copy(description = it)) }
                    EditorField(stringResource(R.string.character_scene), draft.scene, stringResource(R.string.character_scene_hint)) { update(draft.copy(scene = it)) }
                    EditorField(stringResource(R.string.character_first_message), draft.firstMessage, stringResource(R.string.character_first_message_hint)) { update(draft.copy(firstMessage = it)) }
                    CharacterVoiceSetting(editorKey)
                }

                EditorTab.LOOK -> {
                    lookItems(
                        draftAppearance = draft.appearance,
                        onAppearanceChange = { text -> update(draft.copy(appearance = text)) },
                        avatar = avatar,
                        background = background,
                        mediaRevision = mediaRevision,
                        generatingAvatar = generatingAvatar,
                        imageProgress = imageProgress,
                        avatarError = avatarError,
                        enabled = !saving && !choosing && !generatingAvatar,
                        onPickAvatar = { avatarPicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) },
                        onGenerateAvatar = { scope.launch { generateAvatar() } },
                        onRemoveAvatar = { removeImage(AVATAR_FILE) },
                        onPickBackground = { backgroundPicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) },
                        onRemoveBackground = { removeImage(BACKGROUND_FILE) },
                    )
                }
            }
            item {

                HorizontalDivider(color = MaterialTheme.colorScheme.outline)
                Text(
                    text = stringResource(if (saving) R.string.state_saving else R.string.character_save),
                    style = MaterialTheme.typography.labelMedium,
                    color = if (canSave) Accent else MaterialTheme.colorScheme.outline,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).clickable(enabled = canSave, role = Role.Button) {
                        scope.launch { save()?.let(onSaved) }
                    }.padding(vertical = 17.dp),
                )
                if (editorKey == ROOT_CHARACTER_ID) {
                    Text(
                        text = stringResource(R.string.character_reset_default),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).clickable(role = Role.Button) {
                            scope.launch { resetRoot() }
                        }.padding(vertical = 17.dp),
                    )
                }
            }
        }
    }
}

private fun LazyListScope.lookItems(
    draftAppearance: String,
    onAppearanceChange: (String) -> Unit,
    avatar: java.io.File?,
    background: java.io.File?,
    mediaRevision: Int,
    generatingAvatar: Boolean,
    imageProgress: Int,
    avatarError: Int,
    enabled: Boolean,
    onPickAvatar: () -> Unit,
    onGenerateAvatar: () -> Unit,
    onRemoveAvatar: () -> Unit,
    onPickBackground: () -> Unit,
    onRemoveBackground: () -> Unit,
) {
    item {
        EditorField(
            stringResource(R.string.section_appearance).uppercase(),
            draftAppearance,
            stringResource(R.string.character_appearance_hint),
            onValueChange = onAppearanceChange,
        )
    }
    item {
        Text(text = stringResource(R.string.character_images_section).uppercase(), style = MaterialTheme.typography.labelSmall, color = AccentSoft)
        ImageChoice(
            stringResource(R.string.field_avatar),
            stringResource(R.string.character_avatar_summary),
            avatar,
            mediaRevision,
            Modifier.padding(top = 8.dp),
            onPick = onPickAvatar,
            onGenerate = onGenerateAvatar,
            generating = generatingAvatar,
            enabled = enabled,
            onRemove = onRemoveAvatar,
        )
        if (generatingAvatar) ImageGenerationProgress(imageProgress)
        if (avatarError != 0) {
            Text(
                stringResource(avatarError),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }
        ImageChoice(
            stringResource(R.string.character_background),
            stringResource(R.string.character_background_summary),
            background,
            mediaRevision,
            Modifier.padding(top = 8.dp),
            onPick = onPickBackground,
            enabled = enabled,
            onRemove = onRemoveBackground,
        )
    }
}

@Composable
private fun EditorTabs(selected: EditorTab, onSelect: (EditorTab) -> Unit) {
    Row(Modifier.fillMaxWidth().selectableGroup()) {
        EditorTab.entries.forEach { tab ->
            val active = selected == tab
            Column(
                Modifier.weight(1f).heightIn(min = 48.dp).selectable(selected = active, role = Role.Tab) { onSelect(tab) },
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    text = stringResource(
                        when (tab) {
                            EditorTab.CARD -> R.string.character_tab_card
                            EditorTab.LOOK -> R.string.character_tab_look
                        },
                    ).uppercase(),
                    style = MaterialTheme.typography.labelMedium,
                    color = if (active) Accent else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(vertical = 13.dp),
                )
                Box(Modifier.fillMaxWidth().heightIn(min = 1.dp).background(if (active) Accent else MaterialTheme.colorScheme.outline))
            }
        }
    }
}
