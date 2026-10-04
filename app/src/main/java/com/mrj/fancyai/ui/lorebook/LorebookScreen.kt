package com.mrj.fancyai.ui.lorebook

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.mrj.fancyai.R
import com.mrj.fancyai.ui.characters.CharacterCard
import com.mrj.fancyai.ui.characters.availableCharacters
import com.mrj.fancyai.ui.kit.AppDialog
import com.mrj.fancyai.ui.kit.AppHeader
import com.mrj.fancyai.ui.kit.CompactSwitchRow
import com.mrj.fancyai.ui.kit.EditorField
import com.mrj.fancyai.ui.theme.Accent
import com.mrj.fancyai.ui.theme.AccentSoft
import com.mrj.fancyai.ui.theme.Hairline
import com.mrj.fancyai.ui.theme.Ink
import com.mrj.fancyai.ui.theme.Slate
import com.mrj.fancyai.ui.theme.SlateRaised
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale

@Composable
internal fun LorebookScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    var page by remember { mutableStateOf<LorebookPage>(LorebookPage.Index) }
    var revision by remember { mutableIntStateOf(0) }
    var enabled by remember { mutableStateOf(lorebookEnabled(context)) }
    var entries by remember { mutableStateOf<List<LorebookEntry>?>(null) }
    var characters by remember { mutableStateOf<List<CharacterCard>?>(null) }
    LaunchedEffect(revision) {
        entries = withContext(Dispatchers.IO) { lorebookEntries(context) }
        characters = withContext(Dispatchers.IO) { availableCharacters(context) }
    }

    fun goBack() {
        page = when (val current = page) {
            LorebookPage.Index -> {
                onBack()
                return
            }
            is LorebookPage.Book -> LorebookPage.Index
            is LorebookPage.Editor -> LorebookPage.Book(current.book)
        }
    }

    BackHandler(onBack = ::goBack)
    Box(
        Modifier
            .fillMaxSize()
            .background(Ink)
            .windowInsetsPadding(WindowInsets.safeDrawing),
    ) {
        when (val current = page) {
            LorebookPage.Index -> LorebookIndex(
                enabled = enabled,
                entries = entries,
                characters = characters,
                onBack = ::goBack,
                onEnabledChange = { next ->
                    enabled = next
                    setLorebookEnabled(context, next)
                },
            ) { page = LorebookPage.Book(it) }
            is LorebookPage.Book -> LorebookVolume(
                book = current.book,
                entries = entries.orEmpty().filter { it.characterId == current.book.characterId },
                onBack = ::goBack,
            ) { entry, isNew -> page = LorebookPage.Editor(current.book, entry, isNew) }
            is LorebookPage.Editor -> LorebookEditor(
                book = current.book,
                entry = current.entry,
                isNew = current.isNew,
                onBack = ::goBack,
            ) { revision++ }
        }
    }
}

@Composable
private fun LorebookIndex(
    enabled: Boolean,
    entries: List<LorebookEntry>?,
    characters: List<CharacterCard>?,
    onBack: () -> Unit,
    onEnabledChange: (Boolean) -> Unit,
    onOpen: (LorebookVolume) -> Unit,
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(bottom = 32.dp),
    ) {
        item {
            AppHeader(
                title = "",
                subtitle = null,
                onBack = onBack,
                modifier = Modifier.heightIn(min = 64.dp).padding(horizontal = 16.dp),
            )
            Column(Modifier.padding(horizontal = 20.dp)) {
                Text(
                    stringResource(R.string.lorebook_eyebrow),
                    style = MaterialTheme.typography.labelSmall,
                    color = Accent,
                )
                Text(
                    stringResource(R.string.home_app_lorebook),
                    style = MaterialTheme.typography.titleLarge,
                    color = AccentSoft,
                    modifier = Modifier.padding(top = 8.dp),
                )
                Text(
                    stringResource(R.string.lorebook_summary),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 8.dp, end = 24.dp),
                )
                CompactSwitchRow(
                    title = stringResource(R.string.lorebook_use_knowledge),
                    summary = stringResource(R.string.lorebook_use_knowledge_summary),
                    checked = enabled,
                    onCheckedChange = onEnabledChange,
                    modifier = Modifier.padding(top = 12.dp),
                )
                SharedVolume(
                    count = entries?.count { it.characterId == null } ?: 0,
                ) { onOpen(LorebookVolume()) }
                Text(
                    stringResource(R.string.lorebook_character_books),
                    style = MaterialTheme.typography.labelSmall,
                    color = AccentSoft,
                    modifier = Modifier.padding(top = 16.dp),
                )
                Text(
                    stringResource(R.string.lorebook_character_books_summary),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 6.dp, bottom = 12.dp),
                )
            }
        }
        items(characters.orEmpty(), key = CharacterCard::id) { character ->
            val count = entries?.count { it.characterId == character.id } ?: 0
            VolumeRow(
                title = character.name,
                count = count,
                modifier = Modifier.padding(horizontal = 20.dp),
            ) { onOpen(LorebookVolume(character.id, character.name)) }
        }
    }
}

@Composable
private fun SharedVolume(count: Int, onClick: () -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .padding(top = 12.dp)
            .background(
                SlateRaised,
                RoundedCornerShape(8.dp),
            )
            .border(1.dp, Hairline, RoundedCornerShape(8.dp))
            .clickable(role = Role.Button, onClick = onClick)
            .padding(12.dp),
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(
                stringResource(R.string.lorebook_shared),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f),
            )
            Text(
                pluralStringResource(R.plurals.lorebook_entry_count, count, count),
                style = MaterialTheme.typography.labelMedium,
                color = AccentSoft,
            )
        }
        Text(
            stringResource(R.string.lorebook_shared_summary),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 8.dp),
        )
    }
}

@Composable
private fun VolumeRow(title: String, count: Int, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Column(modifier.fillMaxWidth().clickable(role = Role.Button, onClick = onClick)) {
        Row(
            Modifier.fillMaxWidth().heightIn(min = 48.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                Modifier.size(28.dp).border(1.dp, Hairline, RoundedCornerShape(9.dp)),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    title.firstOrNull()?.uppercase().orEmpty(),
                    style = MaterialTheme.typography.labelMedium,
                    color = AccentSoft,
                )
            }
            Text(
                title,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f).padding(horizontal = 10.dp),
            )
            Text(
                pluralStringResource(R.plurals.lorebook_entry_count, count, count),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        HorizontalDivider(color = Hairline)
    }
}

@Composable
private fun LorebookHeader(title: String, action: String, onBack: () -> Unit, onAction: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 64.dp).padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AppHeader(
            title = title,
            subtitle = null,
            onBack = onBack,
            titleMaxLines = 1,
            modifier = Modifier.weight(1f),
        )
        Text(
            action,
            style = MaterialTheme.typography.labelMedium,
            color = Accent,
            modifier = Modifier.heightIn(min = 48.dp)
                .clickable(role = Role.Button, onClick = onAction)
                .padding(top = 16.dp, start = 12.dp, end = 8.dp),
        )
    }
}

@Composable
private fun LorebookVolume(
    book: LorebookVolume,
    entries: List<LorebookEntry>,
    onBack: () -> Unit,
    onOpen: (LorebookEntry, Boolean) -> Unit,
) {
    Column(Modifier.fillMaxSize()) {
        LorebookHeader(
            title = book.name ?: stringResource(R.string.lorebook_shared),
            action = stringResource(R.string.lorebook_add_entry),
            onBack = onBack,
        ) { onOpen(LorebookEntry(characterId = book.characterId), true) }
        LazyColumn(
            modifier = Modifier.weight(1f),
            contentPadding = PaddingValues(start = 24.dp, top = 12.dp, end = 24.dp, bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (entries.isEmpty()) {
                item {
                    Column(Modifier.padding(vertical = 36.dp)) {
                        Text(
                            stringResource(R.string.lorebook_empty_title),
                            style = MaterialTheme.typography.titleMedium,
                            color = AccentSoft,
                        )
                        Text(
                            stringResource(R.string.lorebook_empty_summary),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 10.dp),
                        )
                    }
                }
            }
            items(entries, key = LorebookEntry::id) { entry ->
                EntryRow(entry) { onOpen(entry, false) }
            }
        }
    }
}

@Composable
private fun EntryRow(entry: LorebookEntry, onClick: () -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .background(Slate.copy(alpha = 0.72f), RoundedCornerShape(14.dp))
            .border(1.dp, if (entry.enabled) Hairline else Hairline.copy(alpha = 0.5f), RoundedCornerShape(14.dp))
            .clickable(role = Role.Button, onClick = onClick)
            .padding(16.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                if (entry.constant) {
                    stringResource(R.string.lorebook_always_on).uppercase()
                } else {
                    stringResource(R.string.lorebook_entry_keywords, entry.keys.joinToString(", "))
                },
                style = MaterialTheme.typography.labelSmall,
                color = if (entry.enabled) Accent else MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            if (!entry.enabled) {
                Text(
                    stringResource(R.string.settings_off).uppercase(),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 12.dp),
                )
            }
        }
        Text(
            entry.content,
            style = MaterialTheme.typography.bodyMedium,
            color = if (entry.enabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 3,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 9.dp),
        )
    }
}

@Composable
private fun LorebookEditor(
    book: LorebookVolume,
    entry: LorebookEntry,
    isNew: Boolean,
    onBack: () -> Unit,
    onChanged: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val draftKey = "${book.characterId ?: SHARED_DRAFT_KEY}:${if (isNew) NEW_DRAFT_KEY else entry.id}"
    var draft by remember(draftKey) {
        mutableStateOf(
            lorebookDraft(
                context,
                draftKey,
                LorebookDraft(entry.keys.joinToString(", "), entry.content, entry.constant, entry.enabled),
            ),
        )
    }
    var error by remember { mutableStateOf<Int?>(null) }
    var saving by remember { mutableStateOf(value = false) }
    var confirmDelete by remember { mutableStateOf(value = false) }

    fun update(next: LorebookDraft) {
        draft = next
        error = null
        saveLorebookDraft(context, draftKey, next)
    }

    Column(Modifier.fillMaxSize().imePadding()) {
        LorebookHeader(
            title = stringResource(if (isNew) R.string.lorebook_new_entry else R.string.lorebook_edit_entry),
            action = stringResource(R.string.action_save).uppercase(),
            onBack = onBack,
        ) {
            val keys = draft.keys.split(',').asSequence().map(String::trim).filter(String::isNotEmpty)
                .distinctBy { it.lowercase(Locale.ROOT) }
                .toList()
            error = when {
                draft.content.isBlank() -> R.string.lorebook_content_required
                !draft.constant && keys.isEmpty() -> R.string.lorebook_keywords_required
                else -> null
            }
            if ((error == null) && !saving) {
                saving = true
                scope.launch {
                    try { withContext(Dispatchers.IO) {
                        saveLorebookEntry(
                            context,
                            entry.copy(
                                characterId = book.characterId,
                                keys = keys,
                                content = draft.content.trim(),
                                constant = draft.constant,
                                enabled = draft.enabled,
                            ),
                        )
                    } } finally { saving = false }
                    saveLorebookDraft(context, draftKey, null)
                    onChanged()
                    onBack()
                }
            }
        }
        LorebookFields(book, draft, error, isNew, ::update, onDelete = { confirmDelete = true })
    }
    if (confirmDelete) {
        AppDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text(stringResource(R.string.lorebook_delete_title)) },
            text = { Text(stringResource(R.string.lorebook_delete_message)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirmDelete = false
                        scope.launch {
                            withContext(Dispatchers.IO) {
                                deleteLorebookEntry(context, entry.id)
                            }
                            onChanged()
                            onBack()
                        }
                    },
                ) { Text(stringResource(R.string.action_delete), style = MaterialTheme.typography.labelMedium) }
            },
            dismissButton = {
                TextButton(onClick = { confirmDelete = false }) {
                    Text(stringResource(R.string.action_cancel), style = MaterialTheme.typography.labelMedium)
                }
            },
        )
    }
}


@Composable
private fun LorebookFields(
    book: LorebookVolume,
    draft: LorebookDraft,
    error: Int?,
    isNew: Boolean,
    onChange: (LorebookDraft) -> Unit,
    onDelete: () -> Unit,
) {
    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(start = 24.dp, end = 24.dp, bottom = 32.dp),
    ) {
        Text(
            stringResource(R.string.lorebook_entry_scope, book.name ?: stringResource(R.string.lorebook_shared)),
            style = MaterialTheme.typography.labelSmall,
            color = Accent,
            modifier = Modifier.padding(top = 10.dp),
        )
        EditorField(
            label = stringResource(R.string.lorebook_keywords),
            value = draft.keys,
            hint = stringResource(R.string.lorebook_keywords_hint),
            singleLine = true,
        ) { onChange(draft.copy(keys = it)) }
        Text(
            stringResource(R.string.lorebook_keywords_summary),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 7.dp),
        )
        EditorField(
            label = stringResource(R.string.lorebook_content),
            value = draft.content,
            hint = stringResource(R.string.lorebook_content_hint),
        ) { onChange(draft.copy(content = it)) }
        CompactSwitchRow(
            title = stringResource(R.string.lorebook_always_on),
            summary = stringResource(R.string.lorebook_always_on_summary),
            checked = draft.constant,
            onCheckedChange = { onChange(draft.copy(constant = it)) },
            modifier = Modifier.padding(top = 18.dp),
        )
        HorizontalDivider(color = Hairline)
        CompactSwitchRow(
            title = stringResource(R.string.label_enabled),
            summary = stringResource(R.string.lorebook_enabled_summary),
            checked = draft.enabled,
            onCheckedChange = { onChange(draft.copy(enabled = it)) },
        )
        error?.let {
            Text(
                stringResource(it),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(top = 18.dp),
            )
        }
        if (!isNew) {
            Text(
                stringResource(R.string.action_delete).uppercase(),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier
                    .padding(top = 28.dp)
                    .heightIn(min = 48.dp)
                    .clickable(role = Role.Button, onClick = onDelete)
                    .padding(top = 14.dp),
            )
        }
    }
}

private sealed interface LorebookPage {
    data object Index : LorebookPage
    data class Book(val book: LorebookVolume) : LorebookPage
    data class Editor(val book: LorebookVolume, val entry: LorebookEntry, val isNew: Boolean) : LorebookPage
}

private data class LorebookVolume(
    val characterId: String? = null,
    val name: String? = null,
)

private const val SHARED_DRAFT_KEY = "shared"
private const val NEW_DRAFT_KEY = "new"
