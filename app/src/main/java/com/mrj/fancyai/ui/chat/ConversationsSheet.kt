package com.mrj.fancyai.ui.chat

import android.text.format.DateUtils
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SheetState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.mrj.fancyai.R
import com.mrj.fancyai.ui.kit.AppDialog
import com.mrj.fancyai.ui.kit.CompactSwitchRow
import com.mrj.fancyai.ui.kit.PostInput
import com.mrj.fancyai.ui.kit.capitalizeFirstVisibleLetter
import com.mrj.fancyai.ui.theme.Accent
import com.mrj.fancyai.ui.theme.AccentSoft

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ConversationsSheet(
    sheetState: SheetState,
    conversations: List<ChatConversation>,
    activeChatId: String,
    busy: Boolean,
    onDismiss: () -> Unit,
    onSelect: (ChatConversation) -> Unit,
    onNew: () -> Unit,
    thinking: Boolean,
    onThinkingChange: (Boolean) -> Unit,
    onRename: (String, String) -> Unit,
    onDelete: (String) -> Unit,
) {
    val context = LocalContext.current
    var renaming by remember { mutableStateOf<ChatConversation?>(null) }
    var deleting by remember { mutableStateOf<ChatConversation?>(null) }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .windowInsetsPadding(WindowInsets.navigationBars)
                .padding(horizontal = 16.dp)
                .padding(bottom = 10.dp),
        ) {
            Row(
                Modifier.fillMaxWidth().heightIn(min = 48.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = stringResource(R.string.section_conversations),
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    text = stringResource(R.string.conversation_new),
                    style = MaterialTheme.typography.labelMedium,
                    color = if (busy) MaterialTheme.colorScheme.onSurfaceVariant else Accent,
                    modifier = Modifier
                        .heightIn(min = 48.dp)
                        .clickable(role = Role.Button, enabled = !busy, onClick = onNew)
                        .padding(horizontal = 8.dp, vertical = 16.dp),
                )
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            CompactSwitchRow(stringResource(R.string.chat_thinking), thinking, onThinkingChange)
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            LazyColumn(Modifier.fillMaxWidth().heightIn(max = 360.dp)) {
                items(conversations, key = ChatConversation::id) { conversation ->
                    ConversationRow(
                        conversation = conversation,
                        active = conversation.id == activeChatId,
                        enabled = !busy,
                        onSelect = { onSelect(conversation) },
                        onRename = { renaming = conversation },
                    ) { deleting = conversation }
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                }
            }
        }
    }

    renaming?.let { conversation ->
        ConversationActionDialog(context, conversation, true, onRename, onDelete) { renaming = null }
    }
    deleting?.let { conversation ->
        ConversationActionDialog(context, conversation, false, onRename, onDelete) { deleting = null }
    }
}

@Composable
private fun ConversationActionDialog(
    context: android.content.Context,
    conversation: ChatConversation,
    isRename: Boolean,
    onRename: (String, String) -> Unit,
    onDelete: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val title = stringResource(
        if (isRename) R.string.chat_conversations_rename_title else R.string.chat_conversations_delete_title,
    )
    val confirmLabel = stringResource(if (isRename) R.string.action_save else R.string.action_delete)
    var renameText by remember(conversation.id) {
        mutableStateOf(
            if (isRename) readRenameDraft(context, conversation.id).ifBlank { conversation.title } else "",
        )
    }
    AppDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            if (isRename) {
                PostInput(
                    value = renameText,
                    onValueChange = {
                        renameText = it
                        saveRenameDraft(context, conversation.id, it)
                    },
                    singleLine = true,
                    label = stringResource(R.string.field_name),
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words),
                )
            } else {
                Text(
                    stringResource(R.string.chat_conversations_delete_message, conversation.title.ifBlank {
                        stringResource(R.string.conversation_new)
                    }),
                )
            }
        },
        confirmButton = {
            TextButton(onClick = {
                if (isRename) onRename(conversation.id, renameText.capitalizeFirstVisibleLetter())
                else onDelete(conversation.id)
                onDismiss()
            }) {
                Text(confirmLabel, style = MaterialTheme.typography.labelMedium)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.action_cancel), style = MaterialTheme.typography.labelMedium)
            }
        },
    )
}

@Composable
private fun ConversationRow(
    conversation: ChatConversation,
    active: Boolean,
    enabled: Boolean,
    onSelect: () -> Unit,
    onRename: () -> Unit,
    onDelete: () -> Unit,
) {
    var menuOpen by remember { mutableStateOf(value = false) }
    val actionsDescription = stringResource(R.string.action_more_options)
    Row(
        Modifier.fillMaxWidth().heightIn(min = 56.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .size(width = 2.dp, height = 28.dp)
                .background(if (active) Accent else Color.Transparent),
        )
        Column(
            Modifier
                .weight(1f)
                .clickable(role = Role.Button, enabled = enabled && !active, onClick = onSelect)
                .padding(start = 12.dp, end = 8.dp, top = 8.dp, bottom = 8.dp),
        ) {
            Text(
                text = conversation.title.ifBlank { stringResource(R.string.conversation_new) },
                style = MaterialTheme.typography.titleSmall,
                color = if (active) AccentSoft else MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = DateUtils.getRelativeTimeSpanString(conversation.updatedAt).toString(),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 1.dp),
            )
        }
        Box(
            Modifier
                .size(48.dp)
                .semantics { contentDescription = actionsDescription }
                .clickable(role = Role.Button, enabled = enabled) { menuOpen = true },
            contentAlignment = Alignment.Center,
        ) {
            Icon(painter = painterResource(R.drawable.ic_more), contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(22.dp))
            DropdownMenu(
                expanded = menuOpen,
                onDismissRequest = { menuOpen = false },
                containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
            ) {
                listOf(true to onRename, false to onDelete).forEach { (isRename, action) ->
                    DropdownMenuItem(
                        text = {
                            Text(
                                stringResource(if (isRename) R.string.chat_conversations_rename else R.string.action_delete),
                                color = if (isRename) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.error,
                            )
                        },
                        onClick = {
                            menuOpen = false
                            action()
                        },
                    )
                }
            }
        }
    }
}
