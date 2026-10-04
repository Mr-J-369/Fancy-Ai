package com.mrj.fancyai.ui.games

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.mrj.fancyai.R
import com.mrj.fancyai.ui.characters.CharacterCard
import com.mrj.fancyai.ui.kit.AppHeader
import com.mrj.fancyai.ui.kit.Artwork
import com.mrj.fancyai.ui.kit.ImageGenerationProgress
import com.mrj.fancyai.ui.kit.ImagePromptSection
import com.mrj.fancyai.ui.kit.MessageImage
import com.mrj.fancyai.ui.kit.MessageMarkdown
import com.mrj.fancyai.ui.kit.PostInput
import com.mrj.fancyai.ui.theme.Accent
import com.mrj.fancyai.ui.theme.AccentDeep
import com.mrj.fancyai.ui.theme.AccentSoft
import com.mrj.fancyai.ui.theme.Hairline
import com.mrj.fancyai.ui.theme.Ink
import com.mrj.fancyai.ui.theme.Slate
import com.mrj.fancyai.util.contentImageFile
import kotlinx.coroutines.flow.first

@Composable
internal fun ActiveGame(
    game: GameDefinition,
    character: CharacterCard,
    messages: List<GameMessage>,
    draft: String,
    generating: Boolean,
    renderingImage: Boolean,
    imageProgress: Int = 0,
    onBack: () -> Unit,
    onStop: () -> Unit,
    onDraftChange: (String) -> Unit,
    onSend: () -> Unit,
) {
    val listState = rememberLazyListState()
    val imeBottom = WindowInsets.ime.getBottom(LocalDensity.current)

    var followLatest by remember(listState, game.id, character.id) { mutableStateOf(value = true) }
    LaunchedEffect(listState, game.id, character.id) {
        snapshotFlow { listState.isScrollInProgress to !listState.canScrollForward }.collect { (scrolling, atLatest) ->
            if (scrolling || atLatest) followLatest = atLatest
        }
    }
    LaunchedEffect(listState, game.id, character.id, messages, generating, renderingImage, imeBottom) {
        snapshotFlow { listState.layoutInfo.totalItemsCount }.first { it > 0 }
        withFrameNanos { }
        val lastIndex = listState.layoutInfo.totalItemsCount - 1
        if (followLatest && !listState.isScrollInProgress && lastIndex >= 0) {
            listState.scrollToItem(lastIndex, Int.MAX_VALUE)
        }
    }

    Column(
        Modifier.fillMaxSize().windowInsetsPadding(
            WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal),
        ),
    ) {
        GameHeader(game, character, generating, onBack, onStop)
        HorizontalDivider(color = Hairline)
        GameTranscript(
            state = listState,
            modifier = Modifier.weight(1f).fillMaxWidth(),
            messages = messages,
            generating = generating,
            renderingImage = renderingImage,
            imageProgress = imageProgress,
            characterName = character.name,
        )
        GameComposer(draft, game, generating, onDraftChange, onSend)
    }
}

@Composable
private fun GameHeader(
    game: GameDefinition,
    character: CharacterCard,
    generating: Boolean,
    onBack: () -> Unit,
    onStop: () -> Unit,
) {
    Text(
        stringResource(R.string.games_live),
        style = MaterialTheme.typography.labelSmall,
        color = Accent,
        modifier = Modifier.padding(start = 16.dp, top = 4.dp),
    )
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
        AppHeader(
            title = stringResource(game.title),
            subtitle = stringResource(R.string.games_playing_with, character.name),
            onBack = onBack,
            modifier = Modifier.weight(1f),
        )
        if (generating) {
            Text(
                stringResource(R.string.action_stop),
                style = MaterialTheme.typography.labelMedium,
                color = Accent,
                modifier = Modifier.heightIn(min = 48.dp).clickable(role = Role.Button, onClick = onStop)
                    .padding(top = 14.dp),
            )
        }
    }
    Row(
        Modifier.fillMaxWidth().background(Slate).padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier.size(36.dp).background(AccentDeep, RoundedCornerShape(12.dp)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(painter = painterResource(game.symbol), contentDescription = null, tint = AccentSoft, modifier = Modifier.size(32.dp))
        }
        Artwork(
            path = character.avatarPath,
            resource = character.avatarResource,
            contentDescription = character.name,
            modifier = Modifier.padding(start = 6.dp).size(36.dp).clip(RoundedCornerShape(12.dp)),
        )
        Column(Modifier.weight(1f).padding(start = 10.dp)) {
            Text(character.name, style = MaterialTheme.typography.titleSmall, maxLines = 1)
            Text(
                stringResource(game.tagline),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Box(Modifier.size(6.dp).background(Accent, CircleShape))
        Text(
            stringResource(R.string.games_live),
            style = MaterialTheme.typography.labelSmall,
            color = AccentSoft,
            modifier = Modifier.padding(start = 6.dp),
        )
    }
}

@Composable
private fun GameTranscript(
    state: androidx.compose.foundation.lazy.LazyListState,
    modifier: Modifier,
    messages: List<GameMessage>,
    generating: Boolean,
    renderingImage: Boolean,
    imageProgress: Int,
    characterName: String,
) {
    LazyColumn(
        state = state,
        modifier = modifier,
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        items(messages, key = GameMessage::id) { message ->
            GameMessageItem(message)
        }
        if (renderingImage) {
            item(key = "image-progress") {
                ImageGenerationProgress(imageProgress)
            }
        }
        if (generating && messages.lastOrNull()?.text.isNullOrBlank()) {
            item("thinking") {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(color = Accent, strokeWidth = 2.dp, modifier = Modifier.size(16.dp))
                    Text(
                        stringResource(R.string.games_character_thinking, characterName),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(start = 8.dp),
                    )
                }
            }
        }

    }
}

@Composable
private fun GameComposer(
    draft: String,
    game: GameDefinition,
    generating: Boolean,
    onDraftChange: (String) -> Unit,
    onSend: () -> Unit,
) {
    val sendDescription = stringResource(R.string.games_play)
    val canSend = !generating && draft.isNotBlank()
    Column(
        Modifier.fillMaxWidth().background(Slate.copy(alpha = 0.98f))
            .windowInsetsPadding(WindowInsets.ime)
            .windowInsetsPadding(WindowInsets.navigationBars)
            .padding(horizontal = 16.dp, vertical = 8.dp),
    ) {
        PostInput(
            value = draft,
            hint = stringResource(game.placeholder),
            onValueChange = onDraftChange,
            enabled = !generating,
            minLines = 1,
            maxLines = 5,
        )
        HorizontalDivider(color = Hairline)
        Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(
                stringResource(R.string.games_your_move),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.weight(1f))
            Box(
                Modifier.size(48.dp).background(
                    if (canSend) Accent else Hairline,
                    CircleShape,
                ).semantics { contentDescription = sendDescription }
                    .clickable(role = Role.Button, enabled = canSend, onClick = onSend),
                contentAlignment = Alignment.Center,
            ) {
                Icon(painter = painterResource(R.drawable.ic_up), contentDescription = null, tint = Ink, modifier = Modifier.size(22.dp))
            }
        }
    }
}

@Composable
internal fun GameMessageItem(message: GameMessage) {
    when (message.role) {
        GameRole.USER -> Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            Surface(
                color = AccentDeep,
                shape = RoundedCornerShape(topStart = 20.dp, topEnd = 4.dp, bottomEnd = 20.dp, bottomStart = 20.dp),
                modifier = Modifier.padding(start = 36.dp),
            ) {
                Text(
                    message.text,
                    style = MaterialTheme.typography.bodyMedium,
                    color = AccentSoft,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                )
            }
        }
        GameRole.ASSISTANT -> Column(Modifier.fillMaxWidth()) {
            com.mrj.fancyai.ui.kit.ThoughtProcess(message.thoughtProcess)
            if (message.text.isNotBlank()) {
                Row {
                    Box(Modifier.width(2.dp).heightIn(min = 34.dp).background(Accent))
                    MessageMarkdown(
                        content = message.text,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.weight(1f).padding(start = 10.dp),
                    )
                }
            }
        }
    }
    val gameImagePath = message.imagePath
    if (gameImagePath != null) {
        val context = LocalContext.current
        val file = remember(gameImagePath) { contentImageFile(context, gameImagePath) }
        MessageImage(gameImagePath, R.string.games_generated_scene, topPadding = 8.dp, cornerRadius = 12.dp)
        ImagePromptSection(file, savedPrompt = message.imagePrompt.takeIf(String::isNotBlank))
    } else {
        message.imagePrompt.takeIf(String::isNotBlank)?.let { ImagePromptSection(null, savedPrompt = it) }
    }
}
