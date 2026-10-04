package com.mrj.fancyai.ui.games

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.mrj.fancyai.R
import com.mrj.fancyai.ui.characters.CharacterCard
import com.mrj.fancyai.ui.characters.availableCharacters
import com.mrj.fancyai.ui.kit.AppDialog
import com.mrj.fancyai.ui.kit.AppHeader
import com.mrj.fancyai.ui.kit.Artwork
import com.mrj.fancyai.ui.kit.EditorField
import com.mrj.fancyai.ui.profile.userProfile
import com.mrj.fancyai.ui.theme.Accent
import com.mrj.fancyai.ui.theme.AccentDeep
import com.mrj.fancyai.ui.theme.AccentSoft
import com.mrj.fancyai.ui.theme.Hairline
import com.mrj.fancyai.ui.theme.Ink
import com.mrj.fancyai.ui.theme.Slate
import com.mrj.fancyai.ui.theme.SlateRaised
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
internal fun GamesScreen(onBack: () -> Unit) {
    val app = LocalContext.current.applicationContext
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    val controller = remember(app, scope, snackbar) {
        GameController(app, scope) { resource ->
            scope.launch { snackbar.showSnackbar(app.getString(resource)) }
        }
    }
    with(controller) {
        fun leave() {
            if (active || preparing) showExit = true else if (selectedGame != null) resetGame() else onBack()
        }

        LaunchedEffect(app) {
            try {
                val loaded = withContext(Dispatchers.IO) {
                    availableCharacters(app) to userProfile(app)
                }
                characters = loaded.first
                profile = loaded.second
                selectedCharacter = characters.firstOrNull()
            } finally {
                rosterLoading = false
            }
        }
        DisposableEffect(app) {
            onDispose {
                generationJob?.cancel()
                runtime?.cancel()
                runtime?.close()
                runtime = null
            }
        }

        BackHandler(onBack = ::leave)

        Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
            val game = selectedGame
            val character = selectedCharacter
            when {
                game == null -> GamesHub(onBack = onBack, onGame = ::selectGame)
                !active || character == null -> GameLobby(
                    instruction = instruction,
                    openingInstruction = openingInstruction,
                    replyInstruction = replyInstruction,
                    onInstructionsChange = ::updateInstructions,
                    game = game,
                    characters = characters,
                    selectedCharacter = character,
                    loading = rosterLoading,
                    preparing = preparing,
                    onBack = ::leave,
                    onCharacter = { if (!preparing) selectedCharacter = it },
                    onPlay = { send(opening = true) },
                )
                else -> ActiveGame(
                    game = game,
                    character = character,
                    messages = messages,
                    draft = draft,
                    generating = generating,
                    renderingImage = renderingImage,
                    imageProgress = imageProgress,
                    onBack = ::leave,
                    onStop = { generationJob?.cancel(); runtime?.cancel() },
                    onDraftChange = ::updateDraft,
                    onSend = { send() },
                )
            }
            SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter).windowInsetsPadding(WindowInsets.safeDrawing))
        }

        if (showExit) {
            AppDialog(
                onDismissRequest = { showExit = false },
                title = { Text(stringResource(R.string.games_leave_title)) },
                text = { Text(stringResource(R.string.games_leave_message)) },
                confirmButton = {
                    TextButton(
                        onClick = {
                            showExit = false
                            resetGame()
                        },
                    ) { Text(stringResource(R.string.games_leave)) }
                },
                dismissButton = {
                    TextButton(onClick = { showExit = false }) { Text(stringResource(R.string.action_cancel), style = MaterialTheme.typography.labelMedium) }
                },
            )
        }
    }
}
@Composable
internal fun GameCard(index: Int, game: GameDefinition, onClick: () -> Unit) {
    Surface(
        color = SlateRaised.copy(alpha = 0.9f),
        shape = RoundedCornerShape(topStart = 4.dp, topEnd = 18.dp, bottomEnd = 4.dp, bottomStart = 18.dp),
        border = BorderStroke(1.dp, Hairline),
        modifier = Modifier.fillMaxWidth().heightIn(min = 152.dp).clickable(role = Role.Button, onClick = onClick),
    ) {
        Column(Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    stringResource(R.string.games_index_format, index + 1),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.weight(1f))
                Icon(painter = painterResource(game.symbol), contentDescription = null, tint = AccentSoft, modifier = Modifier.size(32.dp))
            }
            Spacer(Modifier.weight(1f))
            Text(
                stringResource(game.title),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                stringResource(game.tagline),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                minLines = 2,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 3.dp),
            )
            Row(
                Modifier.fillMaxWidth().padding(top = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(Modifier.width(20.dp).height(1.dp).background(Accent))
                Spacer(Modifier.weight(1f))
                Text(
                    stringResource(R.string.games_enter),
                    style = MaterialTheme.typography.labelSmall,
                    color = Accent,
                )
            }
        }
    }
}

@Composable
internal fun GameLobby(
    instruction: String,
    openingInstruction: String,
    replyInstruction: String,
    onInstructionsChange: (String, String, String) -> Unit,
    game: GameDefinition,
    characters: List<CharacterCard>,
    selectedCharacter: CharacterCard?,
    loading: Boolean,
    preparing: Boolean,
    onBack: () -> Unit,
    onCharacter: (CharacterCard) -> Unit,
    onPlay: () -> Unit,
) {
    Column(
        Modifier.fillMaxSize().windowInsetsPadding(
            WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal),
        ),
    ) {
        Text(
            stringResource(R.string.games_lobby_eyebrow),
            style = MaterialTheme.typography.labelSmall,
            color = Accent,
            modifier = Modifier.padding(start = 16.dp, top = 4.dp),
        )
        AppHeader(
            title = stringResource(game.title),
            subtitle = stringResource(game.tagline),
            onBack = onBack,
            modifier = Modifier.padding(horizontal = 16.dp),
        )
        LazyColumn(
            Modifier.weight(1f).fillMaxWidth(),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
        ) {
            item {
                EditorField(
                    label = stringResource(R.string.games_rules), value = instruction,
                    hint = stringResource(R.string.instructions_hint),
                    onValueChange = { onInstructionsChange(it, openingInstruction, replyInstruction) },
                )
                EditorField(
                    label = stringResource(R.string.games_opening_instruction), value = openingInstruction,
                    hint = stringResource(R.string.instructions_hint),
                    onValueChange = { onInstructionsChange(instruction, it, replyInstruction) },
                )
                EditorField(
                    label = stringResource(R.string.games_reply_instruction), value = replyInstruction,
                    hint = stringResource(R.string.instructions_hint),
                    onValueChange = { onInstructionsChange(instruction, openingInstruction, it) },
                )
                val defaultRules = stringResource(game.rules)
                TextButton(onClick = { onInstructionsChange(defaultRules, game.opening, game.replyFormat) }) {
                    Text(stringResource(R.string.action_reset_prompt))
                }
                Text(
                    stringResource(R.string.games_choose_character),
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(top = 16.dp),
                )
                Text(
                    stringResource(R.string.games_choose_summary),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 2.dp, bottom = 8.dp),
                )
            }
            item {
                GameLobbyRoster(loading, characters, selectedCharacter, onCharacter)
            }
        }
        Surface(color = Slate.copy(alpha = 0.98f)) {
            Button(
                onClick = onPlay,
                enabled = (selectedCharacter != null) && !preparing,
                colors = ButtonDefaults.buttonColors(containerColor = Accent, contentColor = Ink),
                shape = RoundedCornerShape(14.dp),
                modifier = Modifier.fillMaxWidth().windowInsetsPadding(WindowInsets.navigationBars)
                    .padding(horizontal = 16.dp, vertical = 8.dp).height(48.dp),
            ) {
                if (preparing) {
                    CircularProgressIndicator(
                        color = Ink,
                        strokeWidth = 2.dp,
                        modifier = Modifier.size(20.dp),
                    )
                    Text(stringResource(R.string.games_preparing), modifier = Modifier.padding(start = 10.dp))
                } else {
                    Text(
                        stringResource(
                            R.string.games_play_with,
                            selectedCharacter?.name ?: stringResource(R.string.label_character).uppercase(),
                        ),
                        style = MaterialTheme.typography.labelLarge,
                    )
                }
            }
        }
    }
}

@Composable
private fun GameLobbyRoster(
    loading: Boolean,
    characters: List<CharacterCard>,
    selectedCharacter: CharacterCard?,
    onCharacter: (CharacterCard) -> Unit,
) {
    when {
        loading -> Box(Modifier.fillMaxWidth().height(80.dp), contentAlignment = Alignment.Center) {
            CircularProgressIndicator(color = Accent, strokeWidth = 2.dp, modifier = Modifier.size(20.dp))
        }
        characters.isEmpty() -> EmptyRoster()
        else -> LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            items(characters, key = CharacterCard::id) { character ->
                CharacterGameCard(
                    character = character,
                    selected = character.id == selectedCharacter?.id,
                ) { onCharacter(character) }
            }
        }
    }
}

@Composable
internal fun CharacterGameCard(character: CharacterCard, selected: Boolean, onClick: () -> Unit) {
    val shape = RoundedCornerShape(topStart = 14.dp, topEnd = 4.dp, bottomEnd = 14.dp, bottomStart = 4.dp)
    Surface(
        color = if (selected) AccentDeep else SlateRaised,
        shape = shape,
        border = BorderStroke(if (selected) 2.dp else 1.dp, if (selected) Accent else Hairline),
        modifier = Modifier.width(92.dp).selectable(selected = selected, role = Role.RadioButton, onClick = onClick),
    ) {
        Column(Modifier.padding(6.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Artwork(
                path = character.avatarPath,
                resource = character.avatarResource,
                contentDescription = character.name,
                modifier = Modifier.fillMaxWidth().aspectRatio(1f).clip(RoundedCornerShape(10.dp)),
            )
            Text(
                character.name,
                style = MaterialTheme.typography.titleSmall,
                color = if (selected) AccentSoft else MaterialTheme.colorScheme.onSurface,
                textAlign = TextAlign.Center,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.fillMaxWidth().padding(top = 4.dp, bottom = 1.dp),
            )
        }
    }
}

@Composable
internal fun EmptyRoster() {
    Column(
        Modifier.fillMaxWidth().heightIn(min = 96.dp).border(1.dp, Hairline, RoundedCornerShape(14.dp))
            .padding(12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(painter = painterResource(R.drawable.ic_empty), contentDescription = null, tint = AccentSoft, modifier = Modifier.size(32.dp))
        Text(
            stringResource(R.string.games_empty_title),
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.padding(top = 4.dp),
        )
        Text(
            stringResource(R.string.games_empty_summary),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = 2.dp),
        )
    }
}

@Composable
internal fun GamesHub(onBack: () -> Unit, onGame: (GameDefinition) -> Unit) {
    Column(
        Modifier.fillMaxSize().background(
            Brush.verticalGradient(
                0f to Slate,
                0.38f to Ink,
                1f to MaterialTheme.colorScheme.background,
            ),
        ).windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal)),
    ) {
        Text(
            stringResource(R.string.games_eyebrow),
            style = MaterialTheme.typography.labelSmall,
            color = Accent,
            modifier = Modifier.padding(start = 16.dp, top = 4.dp),
        )
        AppHeader(
            title = stringResource(R.string.home_app_games),
            subtitle = stringResource(R.string.games_hub_summary),
            onBack = onBack,
            modifier = Modifier.padding(horizontal = 16.dp),
        )
        LazyVerticalGrid(
            columns = GridCells.Adaptive(144.dp),
            contentPadding = PaddingValues(start = 16.dp, top = 8.dp, end = 16.dp, bottom = 20.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.fillMaxSize().windowInsetsPadding(
                WindowInsets.safeDrawing.only(WindowInsetsSides.Bottom),
            ),
        ) {
            itemsIndexed(GAMES, key = { _, game -> game.id }) { index, game ->
                GameCard(index = index, game = game) { onGame(game) }
            }
            item(span = { GridItemSpan(maxLineSpan) }) {
                Row(
                    Modifier.fillMaxWidth().padding(top = 6.dp, bottom = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(Modifier.width(20.dp).height(1.dp).background(Accent))
                    Text(
                        stringResource(R.string.games_live_note),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(start = 8.dp),
                    )
                }
            }
        }
    }
}
