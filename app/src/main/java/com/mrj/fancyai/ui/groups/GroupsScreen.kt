package com.mrj.fancyai.ui.groups

import android.content.Context
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import com.mrj.fancyai.ui.kit.PromptEditor
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.edit
import com.mrj.fancyai.R
import com.mrj.fancyai.ui.characters.CharacterCard
import com.mrj.fancyai.ui.kit.AppDialog
import com.mrj.fancyai.ui.kit.AppHeader
import com.mrj.fancyai.ui.kit.Artwork
import com.mrj.fancyai.ui.kit.CompactSwitch
import com.mrj.fancyai.ui.kit.CompactSwitchRow
import com.mrj.fancyai.ui.kit.MessageImage
import com.mrj.fancyai.ui.kit.PostInput
import com.mrj.fancyai.ui.theme.Accent
import com.mrj.fancyai.ui.theme.AccentSoft
import com.mrj.fancyai.ui.theme.Hairline
import com.mrj.fancyai.ui.theme.Ink
import com.mrj.fancyai.ui.theme.Slate

@Composable
internal fun GroupsScreen(onBack: () -> Unit) {
    val app = LocalContext.current.applicationContext
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    val controller = remember(app, scope, snackbar) { GroupController(app, scope, snackbar) }
    with(controller) {
        LaunchedEffect(app) {
            load()
        }

        DisposableEffect(app) {
            onDispose {
                generationJob?.cancel()
                runtime.get()?.cancel()
                runtime.getAndSet(null)?.close()
            }
        }

        BackHandler {
            when (screen) {
                GroupScreen.List -> onBack()
                GroupScreen.Create -> screen = GroupScreen.List
                is GroupScreen.Room -> leaveRoom()
            }
        }

        Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
            when (val current = screen) {
                GroupScreen.List -> GroupListScreen(
                    instruction = instruction,
                    onInstructionChange = ::updateInstruction,
                    thinking = thinking,
                    onThinkingChange = ::updateThinking,
                    groups = groups,
                    previews = previews,
                    characters = characters,
                    loading = loading,
                    onBack = onBack,
                    onCreate = { screen = GroupScreen.Create },
                    onOpen = ::openRoom,
                )
                GroupScreen.Create -> GroupCreateScreen(
                    draft = createDraft,
                    characters = characters,
                    onBack = { screen = GroupScreen.List },
                    onChange = ::updateCreateDraft,
                    onCreate = ::createGroup,
                )
                is GroupScreen.Room -> GroupRoomScreen(
                    group = current.group,
                    members = characters.filter { (id) -> id in current.group.memberIds },
                    profile = profile,
                    messages = messages,
                    streaming = streamingMessage.takeIf { streamingGroupId == current.group.id },
                    loading = roomLoading,
                    draft = composer,
                    generating = generating,
                    status = generationStatus,
                    imageProgress = imageProgress,
                    onBack = ::leaveRoom,
                    onDelete = { deleteRequested = current.group },
                    onDraftChange = {
                        composer = it
                        app.getSharedPreferences(DRAFT_PREFERENCES, Context.MODE_PRIVATE).edit { if (composer.isBlank()) remove(current.group.id) else putString(current.group.id, composer) }
                    },
                    onMention = { member ->
                        composer = insertGroupMention(composer, groupHandle(member))
                        app.getSharedPreferences(DRAFT_PREFERENCES, Context.MODE_PRIVATE).edit { if (composer.isBlank()) remove(current.group.id) else putString(current.group.id, composer) }
                    },
                    onSend = ::sendMessage,
                    onStop = ::stopGeneration,
                )
            }
            SnackbarHost(
                snackbar,
                Modifier.align(Alignment.BottomCenter).windowInsetsPadding(WindowInsets.safeDrawing),
            )
        }

        deleteRequested?.let { group ->
            AppDialog(
                onDismissRequest = { deleteRequested = null },
                title = { Text(stringResource(R.string.delete_named_title, group.name), style = MaterialTheme.typography.titleLarge) },
                text = { Text(stringResource(R.string.groups_delete_message), style = MaterialTheme.typography.bodySmall) },
                confirmButton = {
                    TextButton(onClick = { deleteGroup(group) }) {
                        Text(stringResource(R.string.action_delete), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.error)
                    }
                },
                dismissButton = {
                    TextButton(onClick = { deleteRequested = null }) { Text(stringResource(R.string.action_cancel), style = MaterialTheme.typography.labelMedium) }
                },
            )
        }
    }
}


@Composable
internal fun GroupListScreen(
    instruction: String,
    onInstructionChange: (String) -> Unit,
    thinking: Boolean,
    onThinkingChange: (Boolean) -> Unit,
    groups: List<GroupInfo>,
    previews: Map<String, GroupMessage>,
    characters: List<CharacterCard>,
    loading: Boolean,
    onBack: () -> Unit,
    onCreate: () -> Unit,
    onOpen: (GroupInfo) -> Unit,
) {
    var editingInstructions by remember { mutableStateOf(value = false) }
    val defaultInstruction = stringResource(R.string.groups_default_prompt)
    if (editingInstructions) {
        AppDialog(
            onDismissRequest = { editingInstructions = false },
            title = { Text(stringResource(R.string.action_instructions)) },
            text = {
                Column {
                    PromptEditor(
                        value = instruction,
                        section = stringResource(R.string.home_app_groups),
                        hint = stringResource(R.string.instructions_hint),
                        onValueChange = onInstructionChange,
                    ) { onInstructionChange(defaultInstruction) }
                }
            },
            confirmButton = {
                TextButton(onClick = { editingInstructions = false }) { Text(stringResource(R.string.action_done)) }
            },
        )
    }
    Column(
        Modifier.fillMaxSize().windowInsetsPadding(
            WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal),
        ),
    ) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            AppHeader(
                title = stringResource(R.string.home_app_groups),
                subtitle = stringResource(R.string.groups_subtitle),
                onBack = onBack,
                modifier = Modifier.weight(1f),
                titleMaxLines = 1,
            )
            TextButton(onClick = { editingInstructions = true }) {
                Text(stringResource(R.string.action_instructions), style = MaterialTheme.typography.labelSmall)
            }
            Text(
                stringResource(R.string.groups_new),
                style = MaterialTheme.typography.labelSmall,
                color = Accent,
                modifier = Modifier.heightIn(min = 48.dp).clickable(role = Role.Button, onClick = onCreate)
                    .padding(start = 14.dp, top = 17.dp),
            )
        }
        HorizontalDivider(color = Hairline)
        CompactSwitchRow(
            stringResource(R.string.chat_thinking),
            thinking,
            onThinkingChange,
            Modifier.padding(horizontal = 16.dp),
        )
        HorizontalDivider(color = Hairline)
        if (loading) {
            val loadingDescription = stringResource(R.string.groups_loading)
            CircularProgressIndicator(
                color = Accent,
                strokeWidth = 2.dp,
                modifier = Modifier.fillMaxSize().semantics { contentDescription = loadingDescription }
                    .wrapContentSize(Alignment.Center).requiredSize(24.dp),
            )
        } else if (groups.isEmpty()) {
            GroupEmpty()
        } else {
            LazyColumn(
                Modifier.fillMaxSize().windowInsetsPadding(
                    WindowInsets.safeDrawing.only(WindowInsetsSides.Bottom),
                ),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 14.dp),
            ) {
                items(groups, key = GroupInfo::id) { group ->
                    GroupPreviewRow(group, previews[group.id], characters, onOpen)
                }
            }
        }
    }
}

@Composable
private fun GroupPreviewRow(
    group: GroupInfo,
    preview: GroupMessage?,
    characters: List<CharacterCard>,
    onOpen: (GroupInfo) -> Unit,
) {
    val members = characters.asSequence().filter { (id) -> id in group.memberIds }.take(GROUP_LIST_AVATARS).toList()
    Row(
        Modifier.fillMaxWidth().clickable(role = Role.Button) { onOpen(group) }
            .padding(vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                group.name,
                style = MaterialTheme.typography.titleMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Row(
                Modifier.padding(top = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                members.forEach { MemberAvatar(it) }
                Text(
                    pluralStringResource(R.plurals.groups_member_count, group.memberIds.size, group.memberIds.size),
                    style = MaterialTheme.typography.labelSmall,
                    color = AccentSoft,
                )
            }
            val summary = preview?.text?.takeIf(String::isNotBlank) ?: group.scenario
            if (summary.isNotBlank()) {
                Text(
                    summary,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
        }
        if (preview?.image?.isFile == true) {
            MessageImage(
                preview.image.absolutePath,
                R.string.groups_message_image,
                topPadding = 0.dp,
                modifier = Modifier.padding(start = 10.dp).size(48.dp),
                contentScale = ContentScale.Crop,
            )
        }
        Icon(painter = painterResource(R.drawable.ic_forward), contentDescription = null, tint = Accent, modifier = Modifier.padding(start = 8.dp).size(22.dp))
    }
    HorizontalDivider(color = Hairline)
}

@Composable
private fun GroupEmpty() {
    Column(
        Modifier.fillMaxSize().padding(horizontal = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(painter = painterResource(R.drawable.ic_empty), contentDescription = null, tint = Accent, modifier = Modifier.size(32.dp))
        Text(
            stringResource(R.string.groups_empty_title),
            style = MaterialTheme.typography.titleMedium,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = 8.dp),
        )
        Text(
            stringResource(R.string.groups_empty_summary),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = 4.dp).widthIn(max = 300.dp),
        )
    }
}

private const val GROUP_LIST_AVATARS = 4

@Composable
internal fun GroupCreateScreen(
    draft: GroupCreateDraft,
    characters: List<CharacterCard>,
    onBack: () -> Unit,
    onChange: (GroupCreateDraft) -> Unit,
    onCreate: () -> Unit,
) {
    Column(
        Modifier.fillMaxSize().windowInsetsPadding(
            WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal),
        ),
    ) {
        AppHeader(
            title = stringResource(R.string.groups_create_title),
            subtitle = stringResource(R.string.groups_create_summary),
            onBack = onBack,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
            titleMaxLines = 1,
        )
        HorizontalDivider(color = Hairline)
        LazyColumn(
            Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.ime)
                .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Bottom)),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 16.dp),
        ) {
            item {
                Text(stringResource(R.string.groups_name_label), style = MaterialTheme.typography.labelSmall, color = AccentSoft)
                PostInput(
                    value = draft.name,
                    hint = stringResource(R.string.groups_name_hint),
                    singleLine = true,
                    modifier = Modifier.padding(top = 6.dp),
                    onValueChange = { onChange(draft.copy(name = it.take(NAME_LIMIT))) },
                )
            }
            item {
                Text(stringResource(R.string.groups_scenario_label), style = MaterialTheme.typography.labelSmall, color = AccentSoft, modifier = Modifier.padding(top = 20.dp))
                PostInput(
                    value = draft.scenario,
                    hint = stringResource(R.string.groups_scenario_hint),
                    singleLine = false,
                    modifier = Modifier.padding(top = 6.dp).heightIn(min = 96.dp, max = 220.dp),
                    onValueChange = { onChange(draft.copy(scenario = it.take(SCENARIO_LIMIT))) },
                )
            }
            item {
                Text(stringResource(R.string.groups_members_label), style = MaterialTheme.typography.labelSmall, color = AccentSoft, modifier = Modifier.padding(top = 20.dp))
                Text(
                    stringResource(R.string.groups_members_summary),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp, bottom = 4.dp),
                )
            }
            items(characters, key = CharacterCard::id) { character ->
                GroupMemberRow(character, onChange, draft)
            }
            item {
                val enabled = draft.name.isNotBlank() && draft.memberIds.isNotEmpty()
                Text(
                    stringResource(R.string.groups_create_title).uppercase(),
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.Bold,
                    textAlign = TextAlign.Center,
                    color = Ink.copy(alpha = if (enabled) 1f else 0.42f),
                    modifier = Modifier.fillMaxWidth().padding(top = 20.dp).heightIn(min = 48.dp)
                        .background(Accent.copy(alpha = if (enabled) 1f else 0.35f), RoundedCornerShape(10.dp))
                        .clickable(enabled, role = Role.Button, onClick = onCreate).padding(vertical = 17.dp),
                )
            }
        }
    }
}

@Composable
private fun GroupMemberRow(
    character: CharacterCard,
    onChange: (GroupCreateDraft) -> Unit,
    draft: GroupCreateDraft,
) {
    val selected = character.id in draft.memberIds
    val onCheckedChange: (Boolean) -> Unit = { checked ->
        onChange(
            draft.copy(
                memberIds = if (checked) draft.memberIds + character.id else draft.memberIds - character.id,
            ),
        )
    }
    Row(
        Modifier.fillMaxWidth().heightIn(min = 56.dp)
            .clickable(role = Role.Checkbox) {
                onCheckedChange(!selected)
            },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Artwork(
            path = character.avatarPath,
            resource = character.avatarResource,
            modifier = Modifier.size(36.dp).clip(RoundedCornerShape(10.dp)),
            contentDescription = character.name,
        )
        Column(Modifier.weight(1f).padding(horizontal = 10.dp)) {
            Text(character.name, style = MaterialTheme.typography.titleSmall)
            Text(
                groupHandle(character),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        CompactSwitch(
            selected,
            label = character.name,
            onCheckedChange = onCheckedChange,
            colors = SwitchDefaults.colors(
                checkedThumbColor = Ink,
                checkedTrackColor = Accent,
                uncheckedThumbColor = MaterialTheme.colorScheme.onSurfaceVariant,
                uncheckedTrackColor = Slate,
            ),
        )
    }
    HorizontalDivider(color = Hairline)
}

@Composable
private fun MemberAvatar(member: CharacterCard) {
    Artwork(
        path = member.avatarPath,
        resource = member.avatarResource,
        modifier = Modifier.size(24.dp).clip(RoundedCornerShape(8.dp)),
        contentDescription = member.name,
    )
}
