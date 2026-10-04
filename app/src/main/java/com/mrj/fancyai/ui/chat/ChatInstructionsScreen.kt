package com.mrj.fancyai.ui.chat

import android.content.Context
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.mrj.fancyai.R
import com.mrj.fancyai.ui.kit.AppDialog
import com.mrj.fancyai.ui.kit.AppHeader
import com.mrj.fancyai.ui.kit.PostInput
import com.mrj.fancyai.ui.kit.capitalizeFirstVisibleLetter
import com.mrj.fancyai.ui.settings.SystemPrompt
import com.mrj.fancyai.ui.settings.builtInSystemPrompts
import com.mrj.fancyai.ui.settings.clearPromptDraft
import com.mrj.fancyai.ui.settings.readPromptDraft
import com.mrj.fancyai.ui.settings.readPrompts
import com.mrj.fancyai.ui.settings.readSelectedPrompt
import com.mrj.fancyai.ui.settings.savePromptDraft
import com.mrj.fancyai.ui.settings.savePrompts
import com.mrj.fancyai.ui.settings.saveSelectedPrompt
import com.mrj.fancyai.ui.theme.AccentSoft
import com.mrj.fancyai.ui.theme.Ink

private class PromptLibrary(private val context: Context) {
    val defaults = builtInSystemPrompts(context)
    var savedPrompts by mutableStateOf(readPrompts(context))
    var prompts by mutableStateOf(readPromptDraft(context, savedPrompts))
    var selected by mutableIntStateOf(readSelectedPrompt(context, prompts.size))
    var confirmDelete by mutableStateOf(false)
    var confirmReset by mutableStateOf(false)
    val current get() = prompts[selected]

    init {
        // Keep template identities stable before writing index-based drafts.
        savePrompts(context, savedPrompts, selected)
    }

    fun select(index: Int) {
        selected = index
        saveSelectedPrompt(context, index)
    }

    fun add(prompt: SystemPrompt) {
        prompts = prompts + prompt
        savedPrompts = savedPrompts + prompt
        selected = prompts.lastIndex
        savePrompts(context, savedPrompts, selected)
    }

    fun edit(prompt: SystemPrompt) {
        prompts = prompts.toMutableList().also { it[selected] = prompt }
        savePromptDraft(context, selected, prompts[selected])
    }

    fun save() {
        val saved = current.copy(title = current.title.capitalizeFirstVisibleLetter())
        prompts = prompts.toMutableList().also { it[selected] = saved }
        savedPrompts = savedPrompts.toMutableList().also { it[selected] = saved }
        savePrompts(context, savedPrompts, selected)
        clearPromptDraft(context, selected)
    }

    fun reset() {
        val restored = defaults.firstOrNull { it.templateId == current.templateId } ?: savedPrompts[selected]
        prompts = prompts.toMutableList().also { it[selected] = restored }
        savedPrompts = savedPrompts.toMutableList().also { it[selected] = restored }
        savePrompts(context, savedPrompts, selected)
        clearPromptDraft(context, selected)
        confirmReset = false
    }

    fun delete() {
        prompts = prompts.toMutableList().also { it.removeAt(selected) }
        savedPrompts = savedPrompts.toMutableList().also { it.removeAt(selected) }
        selected = selected.coerceAtMost(prompts.lastIndex)
        savePrompts(context, savedPrompts, selected)
        clearPromptDraft(context)
        prompts.forEachIndexed { index, prompt -> savePromptDraft(context, index, prompt) }
        confirmDelete = false
    }
}

@Composable
internal fun ChatInstructionsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val library = remember(context) { PromptLibrary(context) }
    val goBack = {
        when {
            library.confirmDelete -> library.confirmDelete = false
            library.confirmReset -> library.confirmReset = false
            else -> onBack()
        }
    }
    BackHandler(onBack = goBack)
    Column(Modifier.fillMaxSize()
        .background(Brush.verticalGradient(0f to MaterialTheme.colorScheme.surfaceContainerLow, 0.28f to Ink, 1f to MaterialTheme.colorScheme.background))
        .windowInsetsPadding(WindowInsets.safeDrawing).windowInsetsPadding(WindowInsets.ime).padding(horizontal = 20.dp)) {
        AppHeader(title = stringResource(R.string.instructions_title), onBack = goBack, subtitle = stringResource(R.string.instructions_subtitle))
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(top = 24.dp, bottom = 24.dp),
        ) {
            item {
                library.PromptLibraryChooser()
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            }
            item { library.PromptEditorSection() }
        }
    }
    if (library.confirmDelete) {
        AppDialog(
            onDismissRequest = { library.confirmDelete = false },
            title = { Text(stringResource(R.string.delete_named_title, library.current.title)) },
            text = { Text(stringResource(R.string.instructions_delete_message)) },
            confirmButton = {
                TextButton(onClick = library::delete) {
                    Text(stringResource(R.string.instructions_delete_prompt), color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { library.confirmDelete = false }) {
                    Text(stringResource(R.string.action_cancel), style = MaterialTheme.typography.labelMedium)
                }
            },
        )
    }
    if (library.confirmReset) {
        val builtIn = library.current.templateId != null
        AppDialog(
            onDismissRequest = { library.confirmReset = false },
            title = { Text(stringResource(if (builtIn) R.string.instructions_reset_template else R.string.instructions_discard_edits)) },
            text = { Text(stringResource(if (builtIn) R.string.instructions_reset_message else R.string.instructions_discard_message, library.current.title)) },
            confirmButton = {
                TextButton(onClick = library::reset) {
                    Text(stringResource(if (builtIn) R.string.instructions_reset_template else R.string.instructions_discard_edits))
                }
            },
            dismissButton = {
                TextButton(onClick = { library.confirmReset = false }) { Text(stringResource(R.string.action_cancel)) }
            },
        )
    }
}

@Composable
private fun PromptLibrary.PromptLibraryChooser() {
    val newPromptTitle = stringResource(R.string.instructions_default_title, prompts.count { it.templateId == null } + 1)
    Text(text = stringResource(R.string.instructions_library), color = AccentSoft, style = MaterialTheme.typography.labelSmall)
    Column(Modifier.fillMaxWidth().selectableGroup().padding(top = 8.dp)) {
        prompts.withIndex().sortedBy { it.value.templateId == null }.forEach { (index, prompt) ->
            Row(
                Modifier.fillMaxWidth().heightIn(min = 64.dp)
                    .selectable(selected = index == selected, role = Role.RadioButton, onClick = { select(index) })
                    .padding(vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                RadioButton(selected = index == selected, onClick = null)
                Column(Modifier.weight(1f).padding(start = 12.dp)) {
                    Text(prompt.title, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurface)
                    val description = when (prompt.templateId) {
                        "image_reply" -> R.string.instructions_template_images_note
                        "text_reply" -> R.string.instructions_template_text_note
                        "terminal_reply" -> R.string.instructions_template_terminal_note
                        else -> null
                    }
                    if (description != null) Text(
                        stringResource(description), style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 4.dp),
                    )
                    if (prompt != savedPrompts[index]) Text(
                        stringResource(R.string.instructions_unsaved), style = MaterialTheme.typography.labelSmall,
                        color = AccentSoft, modifier = Modifier.padding(top = 4.dp),
                    )
                }
            }
        }
    }
    OutlinedButton(
        onClick = { add(SystemPrompt(newPromptTitle, current.instruction)) },
        modifier = Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 20.dp),
    ) {
        Icon(painterResource(R.drawable.ic_add), contentDescription = null, modifier = Modifier.size(20.dp))
        Text(stringResource(R.string.instructions_create_template), modifier = Modifier.padding(start = 8.dp))
    }
}

@Composable
private fun PromptLibrary.PromptEditorSection() {
    val current = this.current
    var expanded by rememberSaveable(selected) { mutableStateOf(current.templateId == null || current != savedPrompts[selected]) }
    val canSave = current != savedPrompts[selected] && current.title.isNotBlank()
    TextButton(onClick = { expanded = !expanded }, modifier = Modifier.fillMaxWidth().padding(top = 12.dp)) {
        Text(stringResource(R.string.instructions_edit_template), modifier = Modifier.weight(1f), style = MaterialTheme.typography.titleSmall)
        Icon(painterResource(R.drawable.ic_expand), contentDescription = null, modifier = Modifier.size(22.dp).rotate(if (expanded) 180f else 0f))
    }
    if (!expanded) return
    Text(stringResource(R.string.instructions_explanation), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 8.dp))
    if (current.templateId == null) PostInput(
        value = current.title,
        label = stringResource(R.string.instructions_prompt_title),
        hint = stringResource(R.string.instructions_title_hint),
        singleLine = true,
        isError = current.title.isBlank(),
        modifier = Modifier.padding(top = 16.dp),
        onValueChange = { edit(current.copy(title = it)) },
    )
    PostInput(
        value = current.instruction,
        label = stringResource(R.string.action_instructions),
        hint = stringResource(R.string.instructions_hint),
        minLines = 4,
        maxLines = 10,
        modifier = Modifier.padding(top = 12.dp),
        onValueChange = { edit(current.copy(instruction = it)) },
    )
    Button(onClick = ::save, enabled = canSave, modifier = Modifier.fillMaxWidth().padding(top = 16.dp)) {
        Text(stringResource(R.string.instructions_save_prompt))
    }
    TextButton(onClick = { confirmReset = true }, modifier = Modifier.fillMaxWidth()) {
        Text(stringResource(if (current.templateId != null) R.string.instructions_reset_template else R.string.instructions_discard_edits))
    }
    if (current.templateId == null) {
        TextButton(onClick = { confirmDelete = true }, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.instructions_delete_prompt), color = MaterialTheme.colorScheme.error)
        }
    }
}
