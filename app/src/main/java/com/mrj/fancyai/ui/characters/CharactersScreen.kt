package com.mrj.fancyai.ui.characters

import android.content.Context
import android.content.Intent
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
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
import androidx.core.net.toUri
import com.mrj.fancyai.R
import com.mrj.fancyai.ui.kit.AppHeader
import com.mrj.fancyai.ui.kit.Artwork
import com.mrj.fancyai.ui.kit.PostInput
import com.mrj.fancyai.ui.profile.UserProfile
import com.mrj.fancyai.ui.profile.UserProfileScreen
import com.mrj.fancyai.ui.theme.Accent
import com.mrj.fancyai.ui.theme.AccentSoft
import com.mrj.fancyai.ui.theme.Ink
import kotlinx.coroutines.launch
import java.io.File

@Composable
internal fun CharactersScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val controller = remember(context) { CharactersController(context.applicationContext) }
    LaunchedEffect(context, controller.revision) { controller.load() }
    with(controller) {
        val importCard = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri != null) {
                scope.launch {
                    importCharacter(uri)?.let { imported ->
                        revision++
                        openCharacter = imported
                    }
                }
            }
        }
        val exportJson = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
            val character = controller.pendingExport
            if ((uri != null) && (character != null)) {
                scope.launch { exportCharacter(character, uri, png = false) }
            }
            controller.pendingExport = null
        }
        val exportPng = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("image/png")) { uri ->
            val character = controller.pendingExport
            if ((uri != null) && (character != null)) {
                scope.launch { exportCharacter(character, uri, png = true) }
            }
            controller.pendingExport = null
        }
        if (editingUserProfile) {
            UserProfileScreen {
                editingUserProfile = false
                controller.revision++
            }
            return
        }

        BackHandler(onBack = if ((openCharacter == null) && !creating) onBack else ::leaveWorkspace)

        CharacterWorkspaceContent(
            context = context,
            scope = scope,
            onBack = onBack,
            importCard = importCard,
            exportJson = exportJson,
            exportPng = exportPng,
        )
    }
}

@Composable
private fun CharactersController.CharacterWorkspaceContent(
    context: Context,
    scope: kotlinx.coroutines.CoroutineScope,
    onBack: () -> Unit,
    importCard: androidx.activity.result.ActivityResultLauncher<Array<String>>,
    exportJson: androidx.activity.result.ActivityResultLauncher<String>,
    exportPng: androidx.activity.result.ActivityResultLauncher<String>,
) {
    Box(
        Modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    0f to MaterialTheme.colorScheme.surface,
                    0.45f to Ink,
                    1f to MaterialTheme.colorScheme.background,
                ),
            )
            .windowInsetsPadding(WindowInsets.safeDrawing),
    ) {
        Column(Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
            AppHeader(
                title = when {
                    creating -> stringResource(
                        if (editSource == null) R.string.character_create_title else R.string.character_edit_title,
                    )
                    openCharacter != null -> openCharacter!!.name
                    else -> stringResource(R.string.characters_title)
                },
                subtitle = if ((openCharacter == null) && !creating) stringResource(R.string.characters_subtitle) else null,
                onBack = if ((openCharacter == null) && !creating) onBack else this@CharacterWorkspaceContent::leaveWorkspace,
            )
            if (transferMessage != 0) {
                Text(
                    text = stringResource(transferMessage),
                    style = MaterialTheme.typography.labelSmall,
                    color = if (transferFailed) MaterialTheme.colorScheme.error else Accent,
                    modifier = Modifier.padding(start = 52.dp, top = 4.dp),
                )
            }
            val character = openCharacter
            if (creating || character != null) {
                CharacterDetailPane(
                    character = character,
                    scope = scope,
                    onExportJson = { exportJson.launch("${characterExportName(context, it)}.json") },
                    onExportPng = { exportPng.launch("${characterExportName(context, it)}.png") },
                )
            } else {
                CharacterRoster(
                    characters = characters,
                    profile = profile,
                    search = search,
                    loading = loading,
                    onCreate = {
                        editSource = null
                        creating = true
                    },
                    onImport = { importCard.launch(arrayOf("image/png", "application/json")) },
                    onExplore = {
                        context.startActivity(Intent(Intent.ACTION_VIEW, "https://chat.layla-cloud.com".toUri()))
                    },
                    onOpenProfile = { editingUserProfile = true },
                    onOpen = { openCharacter = it },
                    onSearch = {
                        search = it
                        context.getSharedPreferences(CHARACTER_SEARCH_PREFERENCES, Context.MODE_PRIVATE).edit {
                            if (it.isEmpty()) remove(KEY_CHARACTER_SEARCH) else putString(KEY_CHARACTER_SEARCH, it)
                        }
                    },
                )
            }
        }
    }
}

@Composable
private fun CharactersController.CharacterDetailPane(
    character: CharacterCard?,
    scope: kotlinx.coroutines.CoroutineScope,
    onExportJson: (CharacterCard) -> Unit,
    onExportPng: (CharacterCard) -> Unit,
) {
    if (creating || character == null) {
        CharacterEditorScreen(
            character = editSource,
            onSaved = { saved ->
                revision++
                creating = false
                editSource = null
                openCharacter = saved
            },
        )
        return
    }
    val exportable = character.id != ROOT_CHARACTER_ID
    CharacterProfile(
        character = character,
        onEdit = {
            editSource = character
            creating = true
        },
        onDelete = if (exportable) {
            {
                openCharacter = null
                scope.launch {
                    try {
                        delete(character)
                    } finally {
                        revision++
                    }
                }
            }
        } else {
            null
        },
        onExportJson = if (exportable) {
            {
                pendingExport = character
                onExportJson(character)
            }
        } else {
            null
        },
        onExportPng = if (exportable && (character.avatarPath?.let(::File)?.isFile == true)) {
            {
                pendingExport = character
                onExportPng(character)
            }
        } else {
            null
        },
    )
}

@Composable
internal fun CharacterRoster(
    characters: List<CharacterCard>,
    profile: UserProfile,
    search: String,
    loading: Boolean,
    onCreate: () -> Unit,
    onImport: () -> Unit,
    onExplore: () -> Unit,
    onOpenProfile: () -> Unit,
    onOpen: (CharacterCard) -> Unit,
    onSearch: (String) -> Unit,
) {
    val visibleCharacters = characters.filter { character ->
        character.name.contains(search, ignoreCase = true)
    }
    val profileTitle = profile.name.ifBlank { stringResource(R.string.label_you) }
    val profileVisible = search.isBlank() || profileTitle.contains(search, ignoreCase = true) ||
        profile.handle.contains(search, ignoreCase = true)
    val root = visibleCharacters.firstOrNull { it.id == ROOT_CHARACTER_ID }
    val remainingCharacters = visibleCharacters.filterNot { it.id == ROOT_CHARACTER_ID }
    val rosterCharacters = listOfNotNull(root) + (if (profileVisible) listOf(null) else emptyList()) + remainingCharacters
    LazyVerticalGrid(
        columns = GridCells.Fixed(2),
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(top = 16.dp, bottom = 24.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        item(span = { GridItemSpan(maxLineSpan) }) {
            Column {
                PostInput(
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words, imeAction = ImeAction.Search),
                    value = search,
                    hint = stringResource(R.string.characters_search_hint),
                    onValueChange = onSearch,
                )
                Row(
                    Modifier.fillMaxWidth().padding(top = 16.dp, bottom = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = stringResource(R.string.characters_roster),
                        style = MaterialTheme.typography.labelSmall,
                        color = AccentSoft,
                        modifier = Modifier.weight(1f),
                    )
                    listOf(
                        R.string.character_explore_action to onExplore,
                        R.string.character_import_action to onImport,
                        R.string.action_new to onCreate,
                    ).forEachIndexed { index, (label, action) ->
                        Text(
                            text = stringResource(label),
                            style = MaterialTheme.typography.labelMedium,
                            color = Accent,
                            modifier = Modifier
                                .heightIn(min = 48.dp)
                                .clickable(role = Role.Button, onClick = action)
                                .padding(
                                    start = 16.dp,
                                    top = 15.dp,
                                    end = if (index == 2) 0.dp else 16.dp,
                                    bottom = 15.dp,
                                ),
                        )
                    }
                }
            }
        }
        if (loading || (!profileVisible && root == null && remainingCharacters.isEmpty())) {
            item(span = { GridItemSpan(maxLineSpan) }) {
                Text(
                    text = stringResource(
                        if (loading) R.string.character_loading else R.string.characters_no_results,
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        items(rosterCharacters, key = { it?.id ?: "user-profile" }) { character ->
            val title = character?.name ?: profileTitle
            val subtitle = character?.handle ?: profile.handle.ifBlank { stringResource(R.string.profile_roster_hint) }
            val avatarPath = if (character != null) character.avatarPath else profile.avatarPath
            PeopleTile(title = title, subtitle = subtitle, onOpen = {
                if (character != null) onOpen(character) else onOpenProfile()
            }) {
                if (character != null || avatarPath != null) {
                    Artwork(
                        path = avatarPath,
                        resource = character?.avatarResource ?: 0,
                        modifier = Modifier.fillMaxSize(),
                        contentDescription = title,
                    )
                } else {
                    Icon(
                        painter = painterResource(R.drawable.home_icon_profile),
                        contentDescription = null,
                        tint = AccentSoft,
                        modifier = Modifier
                            .fillMaxSize()
                            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.72f))
                            .wrapContentSize(Alignment.Center),
                    )
                }
            }
        }
    }
}
