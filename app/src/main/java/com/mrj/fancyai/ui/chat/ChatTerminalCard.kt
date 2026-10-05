package com.mrj.fancyai.ui.chat

import android.content.ClipData
import android.content.ClipboardManager
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mrj.fancyai.R
import com.mrj.fancyai.terminal.LinuxDistribution
import com.mrj.fancyai.terminal.TerminalWorkspace
import com.mrj.fancyai.ui.theme.Accent
import com.mrj.fancyai.ui.theme.AccentSoft
import com.mrj.fancyai.ui.theme.Danger
import com.mrj.fancyai.ui.theme.Hairline
import com.mrj.fancyai.ui.theme.Ink
import com.mrj.fancyai.ui.theme.SlateRaised
import com.mrj.fancyai.ui.theme.TextMuted
import com.mrj.fancyai.ui.theme.TextPrimary

@Composable
internal fun ChatTerminalCard(
    turn: ChatTurn,
    index: Int,
    controller: ChatController,
    modifier: Modifier = Modifier,
) {
    val command = turn.sudoCommand ?: return
    val context = LocalContext.current
    val workspace = remember { TerminalWorkspace.get(context) }
    val workspaceState by workspace.state.collectAsState()
    val isUbuntuInstalled = workspaceState.installed.contains(LinuxDistribution.UBUNTU)
    val isRunning = controller.runningCommandTurnIndex == index
    val download = workspaceState.download
    val isDownloadingUbuntu = download?.distribution == LinuxDistribution.UBUNTU
    val exitCode = turn.commandExitCode
    val output = turn.commandOutput
    var outputExpanded by remember { mutableStateOf(true) }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(top = 10.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(SlateRaised)
            .border(1.dp, Hairline, RoundedCornerShape(12.dp))
            .padding(12.dp),
    ) {
        ChatTerminalHeader(
            isRunning = isRunning,
            exitCode = exitCode,
            onOpenFullTerminal = {
                workspace.open(LinuxDistribution.UBUNTU)
            },
        )
        ChatTerminalCommandView(command = command) {
            context.getSystemService(ClipboardManager::class.java)
                ?.setPrimaryClip(ClipData.newPlainText("command", command))
        }
        if (download?.distribution == LinuxDistribution.UBUNTU) {
            ChatTerminalDownloadProgress(fraction = download.fraction, extracting = download.extracting)
        }
        ChatTerminalActions(
            isUbuntuInstalled = isUbuntuInstalled,
            isDownloadingUbuntu = isDownloadingUbuntu,
            isRunning = isRunning,
            hasOutput = output != null,
            onInstallUbuntu = { workspace.install(LinuxDistribution.UBUNTU) },
            onCancel = { controller.cancelSudoCommand() },
            onRun = { controller.executeSudoCommand(index) },
        )
        if (isRunning) {
            ChatTerminalInteractiveBar(
                onSendLine = controller::sendCommandLine,
                onSendUp = controller::sendCommandUp,
                onSendDown = controller::sendCommandDown,
                onInterrupt = controller::sendCommandInterrupt,
            )
        }
        if (output != null) {
            ChatTerminalOutput(
                output = output,
                expanded = outputExpanded,
                onToggleExpanded = { outputExpanded = !outputExpanded },
                onCopy = {
                    context.getSystemService(ClipboardManager::class.java)
                        ?.setPrimaryClip(ClipData.newPlainText("terminal_output", output))
                },
                onSendToRoot = {
                    val snippet = "```\n$output\n```"
                    controller.input = if (controller.input.isBlank()) snippet else "${controller.input}\n\n$snippet"
                },
            )
        }
    }
}

@Composable
private fun ChatTerminalHeader(
    isRunning: Boolean,
    exitCode: Int?,
    onOpenFullTerminal: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = ">_",
            style = MaterialTheme.typography.labelMedium.copy(
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold,
            ),
            color = Accent,
        )
        Spacer(Modifier.width(8.dp))
        Text(
            text = stringResource(R.string.chat_terminal_title),
            style = MaterialTheme.typography.labelMedium.copy(
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold,
                letterSpacing = 1.sp,
            ),
            color = Accent,
        )
        Spacer(Modifier.weight(1f))

        when {
            isRunning -> {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(12.dp),
                        strokeWidth = 2.dp,
                        color = Accent,
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(
                        text = stringResource(R.string.chat_terminal_running),
                        style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace),
                        color = AccentSoft,
                    )
                }
            }
            exitCode != null -> {
                val isSuccess = exitCode == 0
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(4.dp))
                        .background(if (isSuccess) Accent.copy(alpha = 0.2f) else Danger.copy(alpha = 0.2f))
                        .padding(horizontal = 6.dp, vertical = 2.dp),
                ) {
                    Text(
                        text = if (isSuccess) stringResource(R.string.chat_terminal_success)
                        else stringResource(R.string.chat_terminal_exit_code, exitCode),
                        style = MaterialTheme.typography.labelSmall.copy(
                            color = if (isSuccess) Accent else Danger,
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Bold,
                        ),
                    )
                }
            }
            else -> {
                Text(
                    text = stringResource(R.string.chat_terminal_needs_approval),
                    style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace),
                    color = TextMuted,
                )
            }
        }

        Spacer(Modifier.width(8.dp))
        Box(
            modifier = Modifier
                .clip(RoundedCornerShape(4.dp))
                .background(Accent.copy(alpha = 0.15f))
                .clickable(role = Role.Button, onClick = onOpenFullTerminal)
                .padding(horizontal = 6.dp, vertical = 2.dp),
        ) {
            Text(
                text = stringResource(R.string.chat_terminal_open_full),
                style = MaterialTheme.typography.labelSmall.copy(
                    fontFamily = FontFamily.Monospace,
                    fontSize = 9.sp,
                    fontWeight = FontWeight.Bold,
                ),
                color = Accent,
            )
        }
    }
}

@Composable
private fun ChatTerminalCommandView(command: String, onCopy: () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 10.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(Ink)
            .border(1.dp, Hairline, RoundedCornerShape(8.dp))
            .clickable(onClick = onCopy)
            .padding(horizontal = 10.dp, vertical = 8.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "$ ",
                style = MaterialTheme.typography.bodySmall.copy(
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold,
                ),
                color = Accent,
            )
            Box(modifier = Modifier.weight(1f).horizontalScroll(rememberScrollState())) {
                Text(
                    text = command,
                    style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                    color = TextPrimary,
                )
            }
        }
    }
}

@Composable
private fun ChatTerminalDownloadProgress(fraction: Float, extracting: Boolean) {
    Column(modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) {
        LinearProgressIndicator(
            progress = { fraction },
            modifier = Modifier.fillMaxWidth(),
            color = Accent,
        )
        Text(
            text = stringResource(
                if (extracting) R.string.terminal_extracting else R.string.terminal_downloading,
                (fraction * 100).toInt(),
            ),
            style = MaterialTheme.typography.bodySmall,
            color = TextMuted,
            modifier = Modifier.padding(top = 4.dp),
        )
    }
}

@Composable
private fun ChatTerminalActions(
    isUbuntuInstalled: Boolean,
    isDownloadingUbuntu: Boolean,
    isRunning: Boolean,
    hasOutput: Boolean,
    onInstallUbuntu: () -> Unit,
    onCancel: () -> Unit,
    onRun: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(top = 10.dp),
        horizontalArrangement = Arrangement.End,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (!isUbuntuInstalled && !isDownloadingUbuntu) {
            Text(
                text = stringResource(R.string.chat_terminal_ubuntu_required),
                style = MaterialTheme.typography.bodySmall,
                color = TextMuted,
                modifier = Modifier.weight(1f),
            )
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(6.dp))
                    .background(Accent)
                    .clickable(role = Role.Button, onClick = onInstallUbuntu)
                    .padding(horizontal = 12.dp, vertical = 6.dp),
            ) {
                Text(
                    text = stringResource(R.string.chat_terminal_install_ubuntu),
                    style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                    color = Ink,
                )
            }
        } else if (isUbuntuInstalled) {
            if (isRunning) {
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(6.dp))
                        .background(Danger.copy(alpha = 0.2f))
                        .clickable(role = Role.Button, onClick = onCancel)
                        .padding(horizontal = 12.dp, vertical = 6.dp),
                ) {
                    Text(
                        text = stringResource(R.string.action_cancel),
                        style = MaterialTheme.typography.labelSmall.copy(
                            color = Danger,
                            fontWeight = FontWeight.Bold,
                        ),
                    )
                }
            } else {
                val label = if (!hasOutput) R.string.chat_terminal_run else R.string.chat_terminal_rerun
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(6.dp))
                        .background(Accent)
                        .clickable(role = Role.Button, onClick = onRun)
                        .padding(horizontal = 14.dp, vertical = 6.dp),
                ) {
                    Text(
                        text = stringResource(label),
                        style = MaterialTheme.typography.labelSmall.copy(
                            color = Ink,
                            fontWeight = FontWeight.Bold,
                            letterSpacing = 0.5.sp,
                        ),
                    )
                }
            }
        }
    }
}

@Composable
private fun ChatTerminalOutput(
    output: String,
    expanded: Boolean,
    onToggleExpanded: () -> Unit,
    onCopy: () -> Unit,
    onSendToRoot: () -> Unit,
) {
    HorizontalDivider(
        modifier = Modifier.padding(vertical = 10.dp),
        thickness = 1.dp,
        color = Hairline,
    )

    var copiedOutput by remember { mutableStateOf(false) }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(
            modifier = Modifier
                .clickable(role = Role.Button, onClick = onToggleExpanded),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = stringResource(R.string.terminal_title).uppercase(),
                style = MaterialTheme.typography.labelSmall.copy(
                    fontFamily = FontFamily.Monospace,
                    letterSpacing = 1.sp,
                ),
                color = AccentSoft,
            )
            Spacer(Modifier.width(4.dp))
            Icon(
                painter = painterResource(if (expanded) R.drawable.ic_expand else R.drawable.ic_forward),
                contentDescription = null,
                tint = TextMuted,
                modifier = Modifier.size(16.dp),
            )
        }
        Spacer(Modifier.weight(1f))
        Box(
            modifier = Modifier
                .clip(RoundedCornerShape(4.dp))
                .clickable(role = Role.Button) {
                    onCopy()
                    copiedOutput = true
                }
                .padding(horizontal = 6.dp, vertical = 2.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    painter = painterResource(R.drawable.ic_copy),
                    contentDescription = null,
                    tint = if (copiedOutput) Accent else AccentSoft,
                    modifier = Modifier.size(13.dp),
                )
                Spacer(Modifier.width(4.dp))
                Text(
                    text = stringResource(if (copiedOutput) R.string.chat_terminal_copied else R.string.chat_terminal_copy_output),
                    style = MaterialTheme.typography.labelSmall.copy(
                        fontFamily = FontFamily.Monospace,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.SemiBold,
                    ),
                    color = if (copiedOutput) Accent else AccentSoft,
                )
            }
        }
        Spacer(Modifier.width(6.dp))
        Box(
            modifier = Modifier
                .clip(RoundedCornerShape(4.dp))
                .background(Accent.copy(alpha = 0.15f))
                .clickable(role = Role.Button, onClick = onSendToRoot)
                .padding(horizontal = 6.dp, vertical = 2.dp),
        ) {
            Text(
                text = stringResource(R.string.chat_terminal_send_to_root),
                style = MaterialTheme.typography.labelSmall.copy(
                    fontFamily = FontFamily.Monospace,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold,
                ),
                color = Accent,
            )
        }
    }

    AnimatedVisibility(visible = expanded) {
        ChatTerminalOutputBox(output = output)
    }
}

@Composable
private fun ChatTerminalOutputBox(output: String) {
    val scrollState = rememberScrollState()
    LaunchedEffect(output) {
        scrollState.scrollTo(scrollState.maxValue)
    }
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 6.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(Ink)
            .border(1.dp, Hairline, RoundedCornerShape(8.dp))
            .padding(10.dp),
    ) {
        Text(
            text = output,
            style = MaterialTheme.typography.bodySmall.copy(
                fontFamily = FontFamily.Monospace,
                fontSize = 11.sp,
                lineHeight = 16.sp,
            ),
            color = TextPrimary,
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 220.dp)
                .verticalScroll(scrollState),
        )
    }
}
