package com.mrj.fancyai.ui.chat

import android.content.Context
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import androidx.core.content.edit
import com.mrj.fancyai.R
import com.mrj.fancyai.ui.characters.CharacterCard
import com.mrj.fancyai.ui.characters.availableCharacters
import com.mrj.fancyai.ui.kit.AppHeader
import com.mrj.fancyai.ui.kit.PostInput
import com.mrj.fancyai.ui.theme.Accent
import com.mrj.fancyai.ui.theme.AccentSoft
import com.mrj.fancyai.ui.theme.Ink
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
internal fun ChatDirectory(onBack: () -> Unit, onOpen: (CharacterCard) -> Unit) {
    var instructionsOpen by rememberSaveable { mutableStateOf(false) }
    var menuOpen by remember { mutableStateOf(false) }
    if (instructionsOpen) {
        ChatInstructionsScreen { instructionsOpen = false }
        return
    }
    val context = LocalContext.current
    var search by remember(context) { mutableStateOf(context.getSharedPreferences(CHAT_SEARCH_PREFERENCES, Context.MODE_PRIVATE).getString(KEY_CHAT_SEARCH, "").orEmpty()) }
    var directory by remember(context) { mutableStateOf<List<CharacterCard>?>(null) }
    LaunchedEffect(context) {
        directory = withContext(Dispatchers.IO) {
            availableCharacters(context)
                .map { character ->
                    character to (readConversations(context, character.id).firstOrNull()?.updatedAt ?: 0L)
                }
                .sortedByDescending { it.second }
                .map { it.first }
        }
    }
    val characters = remember(directory, search) {
        directory.orEmpty().filter { it.name.contains(search, ignoreCase = true) }
    }

    BackHandler(onBack = onBack)
    Column(Modifier.fillMaxSize()
        .background(Brush.verticalGradient(0f to MaterialTheme.colorScheme.surfaceContainerLow, 0.28f to Ink, 1f to MaterialTheme.colorScheme.background))
        .windowInsetsPadding(WindowInsets.safeDrawing).padding(horizontal = 20.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            AppHeader(title = stringResource(R.string.chats_title), onBack = onBack, subtitle = stringResource(R.string.chats_subtitle), modifier = Modifier.weight(1f))
            Box {
                IconButton(onClick = { menuOpen = true }) {
                    Icon(painterResource(R.drawable.ic_more), contentDescription = stringResource(R.string.action_more_options), tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(22.dp))
                }
                DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                    DropdownMenuItem(text = { Text(stringResource(R.string.instructions_title)) }, onClick = { menuOpen = false; instructionsOpen = true })
                }
            }
        }
        PostInput(
            singleLine = true,
            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words, imeAction = ImeAction.Search),
            value = search,
            hint = stringResource(R.string.chats_search_hint),
            onValueChange = {
                search = it
                context.getSharedPreferences(CHAT_SEARCH_PREFERENCES, Context.MODE_PRIVATE).edit { if (it.isEmpty()) remove(KEY_CHAT_SEARCH) else putString(KEY_CHAT_SEARCH, it) }
            },
            modifier = Modifier.padding(top = 24.dp),
        )
        Text(
            text = stringResource(R.string.section_conversations),
            style = MaterialTheme.typography.labelSmall,
            color = AccentSoft,
            modifier = Modifier.padding(top = 20.dp, bottom = 6.dp),
        )
        LazyColumn(Modifier.fillMaxSize()) {
            items(characters, key = { it.id }) { character ->
                Row(
                    Modifier
                        .fillMaxWidth()
                        .heightIn(min = 56.dp)
                        .clickable(role = Role.Button) { onOpen(character) },
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = character.name,
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.weight(1f),
                    )
                    Icon(painter = painterResource(R.drawable.ic_forward), contentDescription = null, tint = Accent, modifier = Modifier.size(22.dp))
                }
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            }
            if (directory == null) {
                item { CircularProgressIndicator(modifier = Modifier.padding(top = 20.dp), color = Accent) }
            } else if (characters.isEmpty()) {
                item {
                    Text(
                        text = stringResource(R.string.chats_no_results),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 20.dp),
                    )
                }
            }
        }
    }
}
