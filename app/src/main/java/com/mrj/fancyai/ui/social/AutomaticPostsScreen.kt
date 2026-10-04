package com.mrj.fancyai.ui.social

import android.content.Context
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.core.content.edit
import com.mrj.fancyai.R
import com.mrj.fancyai.ui.characters.CharacterCard
import com.mrj.fancyai.ui.characters.CharacterSocialApp
import com.mrj.fancyai.ui.characters.availableCharacters
import com.mrj.fancyai.ui.characters.saveCharacterSocialEnabled
import com.mrj.fancyai.ui.kit.AppHeader
import com.mrj.fancyai.ui.kit.Artwork
import com.mrj.fancyai.ui.kit.CompactSwitchRow
import com.mrj.fancyai.ui.kit.EditorField
import com.mrj.fancyai.ui.kit.ImageGenerationProgress
import com.mrj.fancyai.ui.kit.PostInput
import com.mrj.fancyai.ui.profile.UserProfile
import com.mrj.fancyai.ui.theme.Accent
import com.mrj.fancyai.ui.theme.AccentSoft
import com.mrj.fancyai.ui.theme.Hairline
import com.mrj.fancyai.ui.theme.Ink
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

@Composable
internal fun AutomaticPostsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val preferences = remember(context) { context.getSharedPreferences("automatic_social_posts", Context.MODE_PRIVATE) }
    var enabled by remember(preferences) { mutableStateOf(preferences.getBoolean("enabled", false)) }
    var intervalDraft by remember(context) {
        mutableStateOf(preferences.getString("minutes_draft", null) ?: preferences.getInt("minutes", 30).toString())
    }
    var characters by remember { mutableStateOf<List<CharacterCard>>(emptyList()) }
    var loaded by remember { mutableStateOf(false) }
    var search by remember { mutableStateOf(preferences.getString("search", "").orEmpty()) }
    var expanded by rememberSaveable { mutableStateOf<String?>(null) }
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    val status by AutomaticSocialPosts.state.collectAsState()
    LaunchedEffect(context) {
        try {
            characters = withContext(Dispatchers.IO) { availableCharacters(context) }
        } finally {
            loaded = true
        }
    }
    LaunchedEffect(enabled) {
        while (enabled) { now = System.currentTimeMillis(); delay(15.seconds) }
    }
    val filtered = remember(characters, search) {
        characters.filter { search.isBlank() || it.name.contains(search, true) || it.handle.contains(search, true) }
    }
    val hasParticipants = remember(characters) { characters.any { character -> CharacterSocialApp.entries.any(character::postsTo) } }
    BackHandler(onBack = onBack)
    Column(Modifier.fillMaxSize()
        .background(Brush.verticalGradient(0f to MaterialTheme.colorScheme.surfaceContainerLow, 0.28f to Ink, 1f to MaterialTheme.colorScheme.background))
        .windowInsetsPadding(WindowInsets.safeDrawing).imePadding().padding(horizontal = 20.dp)) {
        AppHeader(title = stringResource(R.string.automatic_posts_title), onBack = onBack, subtitle = stringResource(R.string.label_social))
        LazyColumn(
            modifier = Modifier.weight(1f),
            contentPadding = PaddingValues(top = 24.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            item(key = "controls") {
                AutomaticPostsControls(
                    context, preferences, enabled, intervalDraft, now, status, hasParticipants, loaded,
                    onEnabledChange = { enabled = it },
                    onIntervalChange = {
                        intervalDraft = it
                        AutomaticPostSettings.setMinutes(context, it)
                        now = System.currentTimeMillis()
                    },
                )
            }
            automaticPostsCharacters(
                context, preferences, loaded, characters, filtered, expanded, search,
                onSearchChange = { search = it },
                onExpandedChange = { expanded = it },
                onCharactersChange = { characters = it },
            )
        }
    }
}

@Composable
private fun AutomaticPostsControls(
    context: Context,
    preferences: android.content.SharedPreferences,
    enabled: Boolean,
    intervalDraft: String,
    now: Long,
    status: AutomaticSocialPosts.State,
    hasParticipants: Boolean,
    loaded: Boolean,
    onEnabledChange: (Boolean) -> Unit,
    onIntervalChange: (String) -> Unit,
) {
    Text(text = stringResource(R.string.automatic_posts_session).uppercase(), style = MaterialTheme.typography.labelSmall, color = AccentSoft)
    CompactSwitchRow(
        title = stringResource(R.string.automatic_posts_title),
        summary = stringResource(R.string.automatic_posts_summary),
        checked = enabled,
        onCheckedChange = { onEnabledChange(it); AutomaticPostSettings.setEnabled(context, it) },
    )
    Column(Modifier.fillMaxWidth().padding(top = 14.dp, bottom = 12.dp)) {
        val intervalLabel = stringResource(R.string.automatic_posts_frequency)
        Text(intervalLabel.uppercase(), style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        PostInput(
            value = intervalDraft,
            hint = intervalLabel,
            onValueChange = { value ->
                if (value.all(Char::isDigit)) {
                    onIntervalChange(value)
                }
            },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            modifier = Modifier.padding(top = 6.dp),
        )

    }
    val nextAt = preferences.getLong("next_at", 0)
    val remaining = ((nextAt - now + 59_999) / 60_000).coerceAtLeast(1).toInt()
    val statusText = when {
        !enabled -> stringResource(R.string.automatic_posts_paused)
        status.running -> stringResource(status.message, status.character, stringResource(checkNotNull(status.app).title))
        !hasParticipants && loaded -> stringResource(R.string.automatic_posts_no_participants)
        status.message != R.string.automatic_posts_waiting -> stringResource(status.message)
        nextAt > now -> pluralStringResource(R.plurals.automatic_posts_next, remaining, remaining)
        else -> stringResource(R.string.automatic_posts_waiting)
    }
    Text(statusText, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    Text(stringResource(R.string.automatic_posts_rotation), style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 6.dp))
}

private fun LazyListScope.automaticPostsCharacters(
    context: Context,
    preferences: android.content.SharedPreferences,
    loaded: Boolean,
    characters: List<CharacterCard>,
    filtered: List<CharacterCard>,
    expanded: String?,
    search: String,
    onSearchChange: (String) -> Unit,
    onExpandedChange: (String?) -> Unit,
    onCharactersChange: (List<CharacterCard>) -> Unit,
) {
    item(key = "heading") {
        HorizontalDivider(color = MaterialTheme.colorScheme.outline, modifier = Modifier.padding(bottom = 20.dp))
        Text(text = stringResource(R.string.characters_title).uppercase(), style = MaterialTheme.typography.labelSmall, color = AccentSoft)
        EditorField(stringResource(R.string.automatic_posts_search), search, stringResource(R.string.automatic_posts_search_hint), singleLine = true) {
            onSearchChange(it)
            preferences.edit { putString("search", it) }
        }
    }
    if (!loaded) item { LinearProgressIndicator(Modifier.fillMaxWidth(), color = Accent) }
    else if (filtered.isEmpty()) item { Text(stringResource(R.string.characters_no_results), style = MaterialTheme.typography.bodyMedium) }
    items(filtered, key = CharacterCard::id) { character ->
        val open = expanded == character.id
        val apps = CharacterSocialApp.entries.count(character::postsTo)
        val expansion = stringResource(if (open) R.string.automatic_posts_expanded else R.string.automatic_posts_collapsed)
        Column {
            Row(Modifier.fillMaxWidth().heightIn(min = 64.dp)
                .semantics { stateDescription = expansion }
                .clickable(role = Role.Button) { onExpandedChange(if (open) null else character.id) },
                verticalAlignment = Alignment.CenterVertically) {
                Artwork(path = character.avatarPath, resource = character.avatarResource, modifier = Modifier.size(40.dp).clip(RoundedCornerShape(12.dp)))
                Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                    Text(character.name, style = MaterialTheme.typography.titleMedium)
                    Text(pluralStringResource(R.plurals.automatic_posts_apps, apps, apps),
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Icon(painter = painterResource(if (open) R.drawable.ic_collapse else R.drawable.ic_expand), contentDescription = null, tint = Accent, modifier = Modifier.size(22.dp))
            }
            AnimatedVisibility(open) {
                Column(Modifier.padding(bottom = 16.dp)) {
                    CharacterSocialApp.entries.forEach { app ->
                        CompactSwitchRow(title = stringResource(app.title), summary = stringResource(app.summary), checked = character.postsTo(app), onCheckedChange = { checked ->
                            saveCharacterSocialEnabled(context, character.id, app, checked)
                            val updated = when (app) {
                                CharacterSocialApp.REBBIT -> character.copy(rebbitEnabled = checked)
                                CharacterSocialApp.USTAGRAM -> character.copy(ustagramEnabled = checked)
                                CharacterSocialApp.Y -> character.copy(yEnabled = checked)
                                CharacterSocialApp.DARE -> character.copy(dareEnabled = checked)
                            }
                            onCharactersChange(characters.map { if (it.id == updated.id) updated else it })
                            AutomaticSocialPosts.settingsChanged()
                        })
                    }
                }
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outline)
        }
    }
}

/** Covers the gaps between a social screen's writing, saving, and image callback jobs. */
@Composable
internal fun AutomaticPostingAvailability(busy: Boolean) {
    val owner = remember { Any() }
    DisposableEffect(busy) {
        AutomaticSocialPosts.block(owner, busy)
        onDispose { AutomaticSocialPosts.block(owner, false) }
    }
}


@Composable
internal fun SocialAvatar(
    character: CharacterCard?,
    profile: UserProfile,
    user: Boolean,
    fallbackName: String,
    size: Dp,
) {
    val modifier = Modifier.size(size).clip(RoundedCornerShape(size / 3))
    if (character != null) {
        Artwork(path = character.avatarPath, resource = character.avatarResource, contentDescription = character.name, modifier = modifier)
    } else {
        Artwork(
            path = profile.avatarPath.takeIf { user },
            resource = 0,
            modifier = modifier,
            contentDescription = if (user) profile.name else fallbackName,
            alignment = Alignment.Center,
        ) {
            Text(fallbackName.firstOrNull()?.uppercase().orEmpty(),
                style = MaterialTheme.typography.titleMedium, color = AccentSoft)
        }
    }
}

@Composable
internal fun SocialActiveGeneration(
    character: CharacterCard,
    title: String,
    onStop: () -> Unit,
    progress: Int? = null,
) {
    Column(Modifier.padding(horizontal = 14.dp, vertical = 12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Artwork(
                path = character.avatarPath,
                resource = character.avatarResource,
                contentDescription = character.name,
                modifier = Modifier.size(36.dp).clip(RoundedCornerShape(10.dp)),
            )
            Text(
                title,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f).padding(horizontal = 10.dp),
            )
            Text(
                stringResource(R.string.action_stop),
                style = MaterialTheme.typography.labelSmall,
                color = Accent,
                modifier = Modifier.heightIn(min = 48.dp).clickable(role = Role.Button, onClick = onStop)
                    .padding(horizontal = 6.dp, vertical = 17.dp),
            )
        }
        if (progress != null) ImageGenerationProgress(progress, Modifier.padding(top = 10.dp))
        else LinearProgressIndicator(
            color = Accent,
            trackColor = Hairline,
            modifier = Modifier.fillMaxWidth().padding(top = 10.dp).height(2.dp),
        )
    }
}

@Composable
internal fun SocialCommentsScreen(
    title: String,
    empty: Boolean,
    latestKey: Any?,
    draft: String,
    hint: String,
    emptyMessage: String,
    status: String?,
    imageProgress: Int?,
    sendEnabled: Boolean,
    containerColor: Color,
    onDraftChange: (String) -> Unit,
    onSend: () -> Unit,
    onDismiss: () -> Unit,
    onStop: () -> Unit,
    replyTarget: SocialComment? = null,
    onClearReply: () -> Unit = {},
    comments: LazyListScope.() -> Unit,
    content: @Composable () -> Unit,
) {
    BackHandler(onBack = onDismiss)
    val list = rememberLazyListState()
    val focus = remember { androidx.compose.ui.focus.FocusRequester() }
    val keyboard = androidx.compose.ui.platform.LocalSoftwareKeyboardController.current
    val imeBottom = WindowInsets.ime.getBottom(androidx.compose.ui.platform.LocalDensity.current)
    var followLatest by remember(list) { mutableStateOf(true) }
    LaunchedEffect(list) {
        list.interactionSource.interactions.collect { interaction ->
            when (interaction) {
                is androidx.compose.foundation.interaction.DragInteraction.Start -> followLatest = false
                is androidx.compose.foundation.interaction.DragInteraction.Stop,
                is androidx.compose.foundation.interaction.DragInteraction.Cancel -> followLatest = !list.canScrollBackward
            }
        }
    }
    LaunchedEffect(latestKey, imeBottom) {
        if (followLatest) list.requestScrollToItem(0)
    }
    LaunchedEffect(list.isScrollInProgress) {
        if (!list.isScrollInProgress) followLatest = !list.canScrollBackward
    }
    LaunchedEffect(replyTarget?.id) {
        if (replyTarget != null) { focus.requestFocus(); keyboard?.show() }
    }
    Column(Modifier.fillMaxSize().background(containerColor).windowInsetsPadding(WindowInsets.safeDrawing).imePadding()) {
        AppHeader(title, null, onDismiss, Modifier.padding(horizontal = 16.dp))
        HorizontalDivider()
        LazyColumn(Modifier.fillMaxWidth().weight(1f), state = list, reverseLayout = true,
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp)) {
            comments()
            if (empty) item("empty") {
                Text(emptyMessage, style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(vertical = 20.dp))
            }
            item("post_context") { content() }
        }
        Column(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceContainerLow).padding(horizontal = 16.dp, vertical = 8.dp)) {
            if (status != null) Row(verticalAlignment = Alignment.CenterVertically) {
                if (imageProgress != null) ImageGenerationProgress(imageProgress, Modifier.weight(1f))
                else Text(status, style = MaterialTheme.typography.labelSmall, color = AccentSoft, modifier = Modifier.weight(1f))
                TextButton(onClick = onStop) { Text(stringResource(R.string.action_stop)) }
            }
            if (replyTarget != null) Row(verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.rebbit_replying_to, replyTarget.authorName), style = MaterialTheme.typography.labelMedium, modifier = Modifier.weight(1f))
                TextButton(onClick = onClearReply) { Text(stringResource(R.string.action_cancel)) }
            }
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Bottom) {
                PostInput(value = draft, onValueChange = onDraftChange, hint = hint,
                    modifier = Modifier.weight(1f).focusRequester(focus), maxLines = 5)
                TextButton(enabled = sendEnabled && draft.isNotBlank(), onClick = {
                    followLatest = true
                    list.requestScrollToItem(0)
                    onSend()
                }) { Text(stringResource(R.string.action_send)) }
            }
        }
    }
}

@Composable
internal fun SocialCommentRow(
    comment: SocialComment,
    character: CharacterCard?,
    profile: UserProfile,
    selected: Boolean = false,
    onReply: (() -> Unit)? = null,
) {
    val user = comment.authorId == "user"
    Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), horizontalArrangement = if (user) Arrangement.End else Arrangement.Start) {
        Column(Modifier.fillMaxWidth(0.92f)
            .background(if (selected || user) MaterialTheme.colorScheme.surfaceContainerHigh else MaterialTheme.colorScheme.surfaceContainerLow, RoundedCornerShape(16.dp))
            .padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                SocialAvatar(character, profile, user, comment.authorName, 28.dp)
                Text(comment.authorName, style = MaterialTheme.typography.labelLarge, modifier = Modifier.weight(1f).padding(start = 8.dp))
                Text(android.text.format.DateUtils.getRelativeTimeSpanString(comment.createdAt, System.currentTimeMillis(), android.text.format.DateUtils.MINUTE_IN_MILLIS).toString(),
                    style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            com.mrj.fancyai.ui.kit.ThoughtProcess(comment.thoughtProcess)
            com.mrj.fancyai.ui.kit.MessageMarkdown(comment.text, MaterialTheme.typography.bodyMedium,
                MaterialTheme.colorScheme.onSurface, Modifier.padding(top = 8.dp))
            if (comment.image.isFile) {
                com.mrj.fancyai.ui.kit.MessageImage(comment.image.absolutePath, R.string.vision_image, topPadding = 8.dp, cornerRadius = 8.dp)
            }
            if (comment.image.isFile || comment.imagePrompt.isNotBlank()) {
                com.mrj.fancyai.ui.kit.ImagePromptSection(
                    comment.image.takeIf(java.io.File::isFile),
                    savedPrompt = comment.imagePrompt.takeIf(String::isNotBlank),
                )
            }
            if (onReply != null) TextButton(onClick = onReply, modifier = Modifier.align(Alignment.End)) {
                Text(stringResource(R.string.chat_message_reply))
            }
        }
    }
}
