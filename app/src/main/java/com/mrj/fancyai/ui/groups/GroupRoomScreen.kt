package com.mrj.fancyai.ui.groups

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
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
import com.mrj.fancyai.ui.profile.UserProfile
import com.mrj.fancyai.ui.theme.Accent
import java.io.File
import com.mrj.fancyai.ui.theme.AccentSoft
import com.mrj.fancyai.ui.theme.Hairline
import com.mrj.fancyai.ui.theme.Slate
import com.mrj.fancyai.ui.theme.SlateRaised
import kotlinx.coroutines.flow.first

@Composable
internal fun GroupRoomScreen(
    group: GroupInfo,
    members: List<CharacterCard>,
    profile: UserProfile,
    messages: List<GroupMessage>,
    streaming: GroupMessage?,
    loading: Boolean,
    draft: String,
    generating: Boolean,
    status: String?,
    imageProgress: Int? = null,
    onBack: () -> Unit,
    onDelete: () -> Unit,
    onDraftChange: (String) -> Unit,
    onMention: (CharacterCard) -> Unit,
    onSend: () -> Unit,
    onStop: () -> Unit,
) {
    val composerFocus = remember(group.id) { FocusRequester() }
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
                title = group.name,
                subtitle = pluralStringResource(R.plurals.groups_member_count, members.size, members.size),
                onBack = onBack,
                modifier = Modifier.weight(1f),
                titleMaxLines = 1,
            )
            Box {
                var menuOpen by remember(group.id) { mutableStateOf(false) }
                val moreDescription = stringResource(R.string.groups_more_options)
                Icon(painter = painterResource(R.drawable.ic_more), contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(48.dp).semantics { contentDescription = moreDescription }
                        .clickable(!generating, role = Role.Button) { menuOpen = true }
                        .padding(start = 17.dp, top = 9.dp).size(22.dp))
                DropdownMenu(menuOpen, { menuOpen = false }, containerColor = SlateRaised) {
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.groups_delete_action), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.error) },
                        onClick = {
                            menuOpen = false
                            onDelete()
                        },
                    )
                }
            }
        }
        HorizontalDivider(color = Hairline)
        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            members.forEach { member ->
                Column(
                    Modifier.widthIn(min = 56.dp).heightIn(min = 52.dp)
                        .clickable(enabled = !generating && !loading, role = Role.Button) {
                            onMention(member)
                            composerFocus.requestFocus()
                        },
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Artwork(
                        path = member.avatarPath,
                        resource = member.avatarResource,
                        modifier = Modifier.size(30.dp).clip(RoundedCornerShape(9.dp)),
                        contentDescription = member.name,
                    )
                    Text(
                        member.name,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(top = 2.dp),
                    )
                    Text(
                        groupHandle(member),
                        style = MaterialTheme.typography.labelSmall,
                        color = AccentSoft,
                        maxLines = 1,
                    )
                }
            }
        }
        HorizontalDivider(color = Hairline)
        GroupTranscript(group, members, profile, messages, streaming, loading, generating, Modifier.fillMaxWidth().weight(1f))
        GroupComposer(imageProgress, status, generating, loading, draft, composerFocus, onDraftChange, onSend, onStop)
    }
}

@Composable
private fun GroupTranscript(
    group: GroupInfo,
    members: List<CharacterCard>,
    profile: UserProfile,
    messages: List<GroupMessage>,
    streaming: GroupMessage?,
    loading: Boolean,
    generating: Boolean,
    modifier: Modifier,
) {
    val messageList = rememberLazyListState()
    val displayedMessages = if (streaming == null) messages else listOf(streaming) + messages
    var followLatest by remember(messageList, group.id) { mutableStateOf(value = true) }
    LaunchedEffect(messageList, group.id) {
        snapshotFlow { messageList.isScrollInProgress to !messageList.canScrollBackward }.collect { (scrolling, atLatest) ->
            if (scrolling || atLatest) followLatest = atLatest
        }
    }
    LaunchedEffect(messageList, group.id, displayedMessages) {
        snapshotFlow { messageList.layoutInfo.totalItemsCount }.first { it > 0 }
        withFrameNanos { }
        val lastIndex = messageList.layoutInfo.totalItemsCount - 1
        if (followLatest && !messageList.isScrollInProgress && lastIndex >= 0) {
            messageList.scrollToItem(0, 0)
        }
    }
    if (loading) {
        val description = stringResource(R.string.groups_loading_messages)
        Box(
            modifier.semantics { contentDescription = description },
            contentAlignment = Alignment.Center,
        ) {
            CircularProgressIndicator(color = Accent, strokeWidth = 2.dp, modifier = Modifier.requiredSize(24.dp))
        }
    } else if (messages.isEmpty() && !generating) {
        Box(modifier, contentAlignment = Alignment.Center) {
            Text(
                stringResource(R.string.groups_room_empty),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(24.dp),
            )
        }
    } else {
        LazyColumn(
            modifier,
            state = messageList,
            reverseLayout = true,
            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            items(displayedMessages, key = GroupMessage::id) { message ->
                GroupMessageRow(message, members.firstOrNull { it.id == message.authorId }, profile)
            }
        }
    }
}

@Composable
private fun GroupComposer(
    imageProgress: Int?,
    status: String?,
    generating: Boolean,
    loading: Boolean,
    draft: String,
    composerFocus: FocusRequester,
    onDraftChange: (String) -> Unit,
    onSend: () -> Unit,
    onStop: () -> Unit,
) {
    Column(
        Modifier.fillMaxWidth().background(SlateRaised).windowInsetsPadding(WindowInsets.ime)
            .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Bottom)),
    ) {
        if (imageProgress != null) ImageGenerationProgress(imageProgress, Modifier.padding(horizontal = 16.dp, vertical = 6.dp))
        else if (status != null) {
            Text(
                status,
                style = MaterialTheme.typography.labelSmall,
                color = AccentSoft,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
            )
        }
        val composerAction = stringResource(
            if (generating) R.string.groups_stop_generation else R.string.action_send_message,
        )
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.Bottom,
        ) {
            PostInput(
                value = draft,
                hint = stringResource(R.string.groups_message_hint),
                singleLine = false,
                minLines = 1,
                maxLines = 5,
                modifier = Modifier.weight(1f).focusRequester(composerFocus),
                onValueChange = onDraftChange,
            )
            Icon(painter = painterResource(if (generating) R.drawable.ic_stop else R.drawable.ic_up), contentDescription = null, tint = Accent.copy(alpha = if (generating || (!loading && draft.isNotBlank())) 1f else 0.35f), modifier = Modifier.size(48.dp).semantics { contentDescription = composerAction }
                    .clickable(
                        enabled = generating || (!loading && draft.isNotBlank()),
                        role = Role.Button,
                        onClick = if (generating) onStop else onSend,
                    ).padding(top = 12.dp).size(22.dp))
        }
    }
}

@Composable
internal fun GroupMessageRow(
    message: GroupMessage,
    character: CharacterCard?,
    profile: UserProfile,
) {
    val user = message.authorId == USER_ID
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = if (user) Arrangement.End else Arrangement.Start,
        verticalAlignment = Alignment.Top,
    ) {
        if (!user) {
            character?.let {
                Artwork(
                    path = it.avatarPath,
                    resource = it.avatarResource,
                    modifier = Modifier.size(30.dp).clip(RoundedCornerShape(9.dp)),
                    contentDescription = it.name,
                )
                Spacer(Modifier.size(8.dp))
            }
        }
        Column(
            Modifier.widthIn(max = 520.dp).background(
                if (user) Accent.copy(alpha = 0.13f) else Slate,
                RoundedCornerShape(10.dp),
            ).padding(horizontal = 12.dp, vertical = 8.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    message.authorName,
                    style = MaterialTheme.typography.labelMedium,
                    color = if (user) Accent else AccentSoft,
                )
                if (message.authorHandle.isNotBlank()) {
                    Text(
                        message.authorHandle,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(start = 4.dp),
                    )
                }
            }
            com.mrj.fancyai.ui.kit.ThoughtProcess(message.thoughtProcess)
            if (message.text.isNotBlank()) {
                MessageMarkdown(
                    message.text,
                    MaterialTheme.typography.bodyMedium,
                    MaterialTheme.colorScheme.onSurface,
                    Modifier.padding(top = 4.dp),
                )
            }
            if (message.id.startsWith(STREAMING_ID_PREFIX) && message.text.isBlank()) {
                CircularProgressIndicator(
                    modifier = Modifier.padding(top = 8.dp).size(20.dp),
                    color = Accent,
                    strokeWidth = 2.dp,
                )
            }
            if (message.image.isFile) {
                MessageImage(
                    message.image.absolutePath,
                    R.string.groups_message_image,
                    topPadding = 8.dp,
                    cornerRadius = 8.dp,
                )
            }
            if (message.image.isFile || message.imagePrompt.isNotBlank()) {
                ImagePromptSection(
                    message.image.takeIf(File::isFile),
                    savedPrompt = message.imagePrompt.takeIf(String::isNotBlank),
                )
            }
        }
        if (user && profile.avatarPath != null) {
            Spacer(Modifier.size(8.dp))
            Artwork(path = profile.avatarPath, resource = 0, contentDescription = profile.name,
                alignment = Alignment.Center, modifier = Modifier.size(30.dp).clip(RoundedCornerShape(9.dp)))
        }
    }
}
