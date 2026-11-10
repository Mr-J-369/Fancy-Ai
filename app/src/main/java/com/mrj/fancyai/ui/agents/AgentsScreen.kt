package com.mrj.fancyai.ui.agents

import android.content.ClipData
import android.content.ClipboardManager
import android.text.format.DateUtils
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
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
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
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
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mrj.fancyai.R
import com.mrj.fancyai.ui.kit.AppDialog
import com.mrj.fancyai.ui.kit.AppHeader
import com.mrj.fancyai.ui.kit.MessageMarkdown
import com.mrj.fancyai.ui.kit.PostInput
import com.mrj.fancyai.ui.kit.rememberSessionExit
import com.mrj.fancyai.ui.theme.Accent
import com.mrj.fancyai.ui.theme.AccentDeep
import com.mrj.fancyai.ui.theme.AccentSoft
import com.mrj.fancyai.ui.theme.Danger
import com.mrj.fancyai.ui.theme.Hairline
import com.mrj.fancyai.ui.theme.Ink
import com.mrj.fancyai.ui.theme.SlateRaised
import com.mrj.fancyai.ui.theme.TextMuted
import com.mrj.fancyai.ui.theme.TextPrimary
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlin.time.Duration.Companion.seconds

internal data class AgentSkill(val command: String, val description: String, val template: String)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun AgentsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val controller = remember(context) { AgentsController.get(context) }
    val requestExit = rememberSessionExit {
        controller.stop()
        onBack()
    }
    val selectedId by controller.selectedConversationId.collectAsState()
    val runningId by controller.running.collectAsState()
    val input by controller.input.collectAsState()
    val conversations by controller.conversations.collectAsState()
    val modelName by controller.modelName.collectAsState()
    val enableRoot by controller.enableRoot.collectAsState()

    LaunchedEffect(Unit) { controller.refreshModel() }

    val conversation = selectedId?.let { conversations[it] }
    val messages = conversation?.messages.orEmpty()
    val sessionList = remember(conversations) { controller.allConversations() }

    val listState = rememberLazyListState()
    val imeBottom = WindowInsets.ime.getBottom(LocalDensity.current)
    var followLatest by remember(listState, selectedId) { mutableStateOf(value = true) }

    LaunchedEffect(listState, selectedId) {
        snapshotFlow { listState.isScrollInProgress to !listState.canScrollForward }.collect { (scrolling, atLatest) ->
            if (scrolling || atLatest) followLatest = atLatest
        }
    }

    val lastMessageTextLength = messages.lastOrNull()?.text?.length ?: 0
    LaunchedEffect(listState, selectedId, messages.size, lastMessageTextLength, runningId, imeBottom) {
        if (messages.isEmpty()) return@LaunchedEffect
        snapshotFlow { listState.layoutInfo.totalItemsCount }.first { it > 0 }
        withFrameNanos { }
        val lastIndex = listState.layoutInfo.totalItemsCount - 1
        if (followLatest && !listState.isScrollInProgress && (lastIndex >= 0)) {
            listState.scrollToItem(lastIndex, Int.MAX_VALUE)
        }
    }

    var menu by remember { mutableStateOf(value = false) }
    var sessions by remember { mutableStateOf(value = false) }
    var settings by remember { mutableStateOf(value = false) }
    var deleting by remember { mutableStateOf<AgentConversation?>(null) }

    BackHandler(enabled = deleting != null) { deleting = null }
    BackHandler(enabled = (deleting == null) && sessions) { sessions = false }
    BackHandler(enabled = (deleting == null) && !sessions && settings) { settings = false }
    BackHandler(enabled = (deleting == null) && !sessions && !settings && menu) { menu = false }
    BackHandler(enabled = (deleting == null) && !sessions && !settings && !menu) { requestExit() }

    if (settings) {
        AgentsSettingsScreen(controller = controller, onBack = { settings = false })
        return
    }

    Column(
        Modifier
            .fillMaxSize()
            .background(Ink)
            .windowInsetsPadding(WindowInsets.safeDrawing)
            .imePadding(),
    ) {
        Box {
            AppHeader(
                title = stringResource(R.string.agents_title),
                subtitle = modelName,
                onBack = requestExit,
            )
            Box(
                Modifier
                    .align(Alignment.TopEnd)
                    .padding(end = 8.dp, top = 8.dp),
            ) {
                val more = stringResource(R.string.action_more_options)
                Icon(
                    painter = painterResource(R.drawable.ic_more_horizontal),
                    contentDescription = more,
                    tint = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier
                        .clickable(role = Role.Button) { menu = true }
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                )
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.agents_new_session)) },
                        onClick = {
                            menu = false
                            controller.startNewConversation()
                        },
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.settings_title)) },
                        onClick = {
                            menu = false
                            settings = true
                        },
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.agents_sessions)) },
                        onClick = {
                            menu = false
                            sessions = true
                        },
                    )
                    DropdownMenuItem(
                        text = { Text(if (enableRoot) "Disable Root (su_bash)" else "Enable Root (su_bash)") },
                        onClick = {
                            menu = false
                            controller.toggleRoot()
                        },
                    )
                }
            }
        }

        if (messages.isEmpty()) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 24.dp, vertical = 32.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
            ) {
                Text(
                    text = stringResource(R.string.agents_hint),
                    style = MaterialTheme.typography.bodyLarge,
                    color = TextMuted,
                )
                if (runningId != null) RunningRow(stop = controller::stop)
            }
        } else {
        LazyColumn(
            state = listState,
            verticalArrangement = Arrangement.spacedBy(12.dp),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 10.dp),
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
        ) {
            items(messages, key = { (id) -> id }) {
                AgentMessageRow(it)
            }
        }
    }

        val skills = listOf(
            AgentSkill("/read", "Read a specific file", "Read the file at path: "),
            AgentSkill("/list", "List directory contents", "List files in directory: "),
            AgentSkill("/web", "Search or fetch a website", "Fetch and summarize the URL: "),
            AgentSkill("/fix", "Fix code errors", "Review the code and fix errors in: ")
        )
        val query = if (input.startsWith("/")) input.substring(1).lowercase() else null
        val matchedSkills = if (query != null) skills.filter { it.command.substring(1).startsWith(query) } else emptyList()

        if (matchedSkills.isNotEmpty()) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(SlateRaised)
                    .border(1.dp, Hairline, RoundedCornerShape(8.dp))
            ) {
                matchedSkills.forEach { skill ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { controller.input.value = skill.template }
                            .padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = skill.command,
                            style = MaterialTheme.typography.labelMedium.copy(fontFamily = FontFamily.Monospace),
                            color = Accent,
                            modifier = Modifier.width(64.dp)
                        )
                        Text(
                            text = skill.description,
                            style = MaterialTheme.typography.bodySmall,
                            color = TextPrimary
                        )
                    }
                }
            }
        }

        AgentInputBar(
            running = runningId != null,
            input = input,
            contextUsage = controller.contextUsage.collectAsState().value,
            onInput = { controller.input.value = it },
            onSend = { controller.start() },
            onStop = controller::stop,
        )
    }

    if (sessions) {
        ModalBottomSheet(
            onDismissRequest = { sessions = false },
            containerColor = Ink,
        ) {
            Column(Modifier.padding(horizontal = 20.dp, vertical = 8.dp)) {
                Text(
                    text = stringResource(R.string.agents_sessions),
                    style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                if (sessionList.isEmpty()) {
                    Text(
                        text = stringResource(R.string.agents_no_sessions),
                        style = MaterialTheme.typography.bodySmall,
                        color = TextMuted,
                        modifier = Modifier.padding(vertical = 24.dp),
                    )
                }
                Column(
                    Modifier
                        .fillMaxWidth()
                        .heightIn(max = 420.dp)
                        .verticalScroll(rememberScrollState())
                        .padding(vertical = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    for (item in sessionList) {
                        SessionRow(
                            conversation = item,
                            selected = item.id == selectedId,
                            running = runningId == item.id,
                            onSelect = { controller.selectConversation(item.id); sessions = false },
                        ) { deleting = item }
                    }
                }
            }
        }
    }

    deleting?.let { (id, _, title) ->
        AppDialog(
            onDismissRequest = { deleting = null },
            title = { Text(stringResource(R.string.agents_delete_session)) },
            text = { Text(title) },
            confirmButton = {
                TextButton(onClick = { controller.deleteConversation(id); deleting = null }) {
                    Text(stringResource(R.string.action_delete), color = Danger)
                }
            },
            dismissButton = {
                TextButton(onClick = { deleting = null }) { Text(stringResource(R.string.action_cancel)) }
            },
        )
    }
}

@Composable
private fun RunningRow(stop: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 2.dp, color = Accent)
        Spacer(Modifier.width(8.dp))
        Text(
            text = stringResource(R.string.chat_terminal_running),
            style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace, letterSpacing = 1.sp),
            color = AccentSoft,
        )
        Spacer(Modifier.weight(1f))
        Text(
            text = stringResource(R.string.agents_stop),
            style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold),
            color = Danger,
            modifier = Modifier
                .clip(RoundedCornerShape(6.dp))
                .clickable(role = Role.Button, onClick = stop)
                .padding(horizontal = 10.dp, vertical = 6.dp),
        )
    }
}

@Suppress("UsePropertyAccessSyntax")
@Composable
private fun AgentMessageRow(message: AgentMessage) {
    val context = LocalContext.current
    var copied by remember(message.id) { mutableStateOf(value = false) }
    LaunchedEffect(copied) {
        if (copied) {
            delay(2.seconds)
            copied = false
        }
    }

    when (message.role) {
        AgentRole.USER -> Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.End) {
            SelectionContainer {
                Box(
                    Modifier
                        .clip(RoundedCornerShape(14.dp))
                        .background(AccentDeep)
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                ) {
                    Text(
                        text = message.text,
                        style = MaterialTheme.typography.bodyLarge,
                        color = AccentSoft,
                    )
                }
            }
            Box(
                Modifier
                    .padding(top = 2.dp, end = 2.dp)
                    .clip(RoundedCornerShape(4.dp))
                    .clickable(role = Role.Button) {
                        context.getSystemService(ClipboardManager::class.java)?.setPrimaryClip(
                            ClipData.newPlainText("user_message", message.text),
                        )
                        copied = true
                    }
                    .padding(horizontal = 4.dp, vertical = 2.dp),
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    if (copied) {
                        Text(
                            text = stringResource(R.string.chat_terminal_copied),
                            style = MaterialTheme.typography.labelSmall.copy(
                                fontFamily = FontFamily.Monospace,
                                fontSize = 10.sp,
                            ),
                            color = Accent,
                        )
                    }
                    Icon(
                        painter = painterResource(R.drawable.ic_copy),
                        contentDescription = stringResource(
                            if (copied) R.string.chat_terminal_copied else R.string.chat_message_copy,
                        ),
                        tint = if (copied) Accent else TextMuted,
                        modifier = Modifier.size(13.dp),
                    )
                }
            }
        }

        AgentRole.ASSISTANT -> Column(Modifier.fillMaxWidth()) {
            val text = message.text
            val thinkStart = text.indexOf("<think>")
            val contentToRender = if (thinkStart != -1) {
                val thinkEnd = text.indexOf("</think>", thinkStart)
                val hasEnd = thinkEnd != -1
                val thinkContent = if (hasEnd) text.substring(thinkStart + 7, thinkEnd).trim() else text.substring(thinkStart + 7).trim()
                val afterThink = if (hasEnd) text.substring(thinkEnd + 8).trim() else ""
                
                if (thinkContent.isNotEmpty()) {
                    var thinkExpanded by remember { mutableStateOf(false) }
                    Column(
                        Modifier
                            .fillMaxWidth()
                            .padding(bottom = 8.dp)
                            .clip(RoundedCornerShape(8.dp))
                            .background(SlateRaised)
                            .border(1.dp, Hairline, RoundedCornerShape(8.dp))
                    ) {
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .clickable { thinkExpanded = !thinkExpanded }
                                .padding(horizontal = 12.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "Reasoning Process",
                                style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                                color = TextMuted,
                                modifier = Modifier.weight(1f)
                            )
                            Icon(
                                painter = painterResource(if (thinkExpanded) R.drawable.ic_expand else R.drawable.ic_forward),
                                contentDescription = null,
                                tint = TextMuted,
                                modifier = Modifier.size(14.dp)
                            )
                        }
                        AnimatedVisibility(
                            visible = thinkExpanded,
                            enter = expandVertically(animationSpec = tween(150)),
                            exit = shrinkVertically(animationSpec = tween(150))
                        ) {
                            Text(
                                text = thinkContent,
                                style = MaterialTheme.typography.bodySmall.copy(
                                    fontFamily = FontFamily.Monospace,
                                    fontSize = 11.sp,
                                    lineHeight = 16.sp,
                                ),
                                color = TextMuted,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(start = 12.dp, end = 12.dp, bottom = 12.dp)
                            )
                        }
                    }
                }
                afterThink
            } else text

            SelectionContainer {
                MessageMarkdown(
                    content = contentToRender,
                    style = MaterialTheme.typography.bodyLarge,
                    color = TextPrimary,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            if (message.text.isNotEmpty()) {
                Box(
                    Modifier
                        .padding(top = 2.dp, start = 2.dp)
                        .clip(RoundedCornerShape(4.dp))
                        .clickable(role = Role.Button) {
                            context.getSystemService(ClipboardManager::class.java)?.setPrimaryClip(
                                ClipData.newPlainText("agent_message", message.text),
                            )
                            copied = true
                        }
                        .padding(horizontal = 4.dp, vertical = 2.dp),
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        Icon(
                            painter = painterResource(R.drawable.ic_copy),
                            contentDescription = stringResource(
                                if (copied) R.string.chat_terminal_copied else R.string.chat_message_copy,
                            ),
                            tint = if (copied) Accent else TextMuted,
                            modifier = Modifier.size(13.dp),
                        )
                        if (copied) {
                            Text(
                                text = stringResource(R.string.chat_terminal_copied),
                                style = MaterialTheme.typography.labelSmall.copy(
                                    fontFamily = FontFamily.Monospace,
                                    fontSize = 10.sp,
                                ),
                                color = Accent,
                            )
                        }
                    }
                }
            }
        }

        AgentRole.TOOL -> ToolCard(message)
        AgentRole.ERROR -> Text(
            text = message.text,
            style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace, fontSize = 12.sp),
            color = Danger,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Suppress("UsePropertyAccessSyntax")
@Composable
private fun ToolCard(message: AgentMessage) {
    val context = LocalContext.current
    var expanded by remember(message.id) { mutableStateOf(value = false) }
    var copied by remember(message.id) { mutableStateOf(value = false) }
    LaunchedEffect(copied) {
        if (copied) {
            delay(2.seconds)
            copied = false
        }
    }

    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(SlateRaised)
            .border(1.dp, Hairline, RoundedCornerShape(10.dp))
            .padding(horizontal = 10.dp, vertical = 8.dp),
    ) {
        Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Icon(
                painter = painterResource(R.drawable.ic_spark),
                contentDescription = null,
                tint = AccentSoft,
                modifier = Modifier.size(12.dp),
            )
            Text(
                text = message.label ?: stringResource(R.string.agents_title),
                style = MaterialTheme.typography.labelSmall.copy(
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 1.sp,
                ),
                color = AccentSoft,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.weight(1f))
            Box(
                Modifier
                    .clip(RoundedCornerShape(4.dp))
                    .clickable(role = Role.Button) {
                        context.getSystemService(ClipboardManager::class.java)?.setPrimaryClip(
                            ClipData.newPlainText("tool_output", message.text),
                        )
                        copied = true
                    }
                    .padding(horizontal = 4.dp),
            ) {
                Icon(
                    painter = painterResource(R.drawable.ic_copy),
                    contentDescription = stringResource(
                        if (copied) R.string.chat_terminal_copied else R.string.chat_terminal_copy_output,
                    ),
                    tint = if (copied) Accent else AccentSoft,
                    modifier = Modifier.size(13.dp),
                )
            }
            Box(
                Modifier
                    .clip(RoundedCornerShape(4.dp))
                    .clickable(role = Role.Button) { expanded = !expanded }
                    .padding(horizontal = 4.dp, vertical = 2.dp),
            ) {
                Icon(
                    painter = painterResource(if (expanded) R.drawable.ic_expand else R.drawable.ic_forward),
                    contentDescription = null,
                    tint = TextMuted,
                    modifier = Modifier.size(14.dp),
                )
            }
        }
        if (message.text.isNotEmpty()) {
            if (expanded) {
                val scrollState = rememberScrollState()
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 6.dp)
                        .clip(RoundedCornerShape(6.dp))
                        .background(Ink)
                        .border(1.dp, Hairline, RoundedCornerShape(6.dp))
                        .padding(8.dp),
                ) {
                    Text(
                        text = message.text,
                        style = MaterialTheme.typography.bodySmall.copy(
                            fontFamily = FontFamily.Monospace,
                            fontSize = 11.sp,
                            lineHeight = 16.sp,
                        ),
                        color = TextPrimary,
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 260.dp)
                            .verticalScroll(scrollState),
                    )
                }
            } else {
                Text(
                    text = message.text,
                    style = MaterialTheme.typography.bodySmall.copy(
                        fontFamily = FontFamily.Monospace,
                        fontSize = 11.sp,
                        lineHeight = 16.sp,
                    ),
                    color = TextPrimary,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier
                        .padding(top = 6.dp)
                        .fillMaxWidth(),
                )
            }
        }
    }
}

@Composable
private fun AgentInputBar(
    running: Boolean,
    input: String,
    contextUsage: Pair<Int, Int>,
    onInput: (String) -> Unit,
    onSend: () -> Unit,
    onStop: () -> Unit,
) {
    Column(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        val (percent, turns) = contextUsage
        if (percent > 0 || turns > 0) {
            val turnsText = pluralStringResource(R.plurals.memory_turns, turns, turns)
            Text(
                text = if (percent > 0) "ctx ~$percent% · $turnsText" else turnsText,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 4.dp)
            )
        }
        
        Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            PostInput(
                value = input,
                onValueChange = onInput,
                hint = stringResource(R.string.agents_input_hint),
                minLines = 1,
                maxLines = 6,
                modifier = Modifier.weight(1f),
            )
            Box(
                Modifier
                    .clip(RoundedCornerShape(10.dp))
                    .background(if (running) Danger else Accent)
                    .clickable(role = Role.Button) { if (running) onStop() else onSend() }
                    .padding(horizontal = 14.dp, vertical = 12.dp),
            ) {
                Text(
                    text = stringResource(if (running) R.string.agents_stop else R.string.action_send),
                    style = MaterialTheme.typography.labelSmall.copy(
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 1.sp,
                    ),
                    color = Ink,
                )
            }
        }
    }
}

@Composable
private fun SessionRow(
    conversation: AgentConversation,
    selected: Boolean,
    running: Boolean,
    onSelect: () -> Unit,
    onDelete: () -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(if (selected) AccentDeep else Ink)
            .border(1.dp, if (selected) Accent else Hairline, RoundedCornerShape(10.dp))
            .clickable(role = Role.Button, onClick = onSelect)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                text = conversation.title.ifBlank { stringResource(R.string.agents_title) },
                style = MaterialTheme.typography.bodyMedium,
                color = TextPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = DateUtils.getRelativeTimeSpanString(conversation.updatedAt).toString(),
                style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace),
                color = TextMuted,
            )
        }
        if (running) {
            CircularProgressIndicator(Modifier.size(12.dp), strokeWidth = 2.dp, color = Accent)
        }
        Icon(
            painter = painterResource(R.drawable.ic_delete),
            contentDescription = stringResource(R.string.agents_delete_session),
            tint = TextMuted,
            modifier = Modifier
                .clickable(role = Role.Button, onClick = onDelete)
                .padding(6.dp)
                .size(16.dp),
        )
    }
}
