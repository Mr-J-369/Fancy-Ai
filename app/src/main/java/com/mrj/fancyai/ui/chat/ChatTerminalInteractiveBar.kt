package com.mrj.fancyai.ui.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mrj.fancyai.R
import com.mrj.fancyai.ui.theme.Accent
import com.mrj.fancyai.ui.theme.Danger
import com.mrj.fancyai.ui.theme.Hairline
import com.mrj.fancyai.ui.theme.Ink
import com.mrj.fancyai.ui.theme.SlateRaised
import com.mrj.fancyai.ui.theme.TextMuted
import com.mrj.fancyai.ui.theme.TextPrimary

@Composable
internal fun ChatTerminalInteractiveBar(
    onSendLine: (String) -> Unit,
    onSendUp: () -> Unit,
    onSendDown: () -> Unit,
    onInterrupt: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var customText by remember { mutableStateOf("") }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(top = 10.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(Ink)
            .border(1.dp, Accent.copy(alpha = 0.35f), RoundedCornerShape(8.dp))
            .padding(8.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ChatKeyPill(label = stringResource(R.string.chat_terminal_key_enter)) { onSendLine("") }
            ChatKeyPill(label = stringResource(R.string.chat_terminal_key_up)) { onSendUp() }
            ChatKeyPill(label = stringResource(R.string.chat_terminal_key_down)) { onSendDown() }
            ChatKeyPill(label = stringResource(R.string.chat_terminal_key_yes)) { onSendLine("y") }
            ChatKeyPill(label = stringResource(R.string.chat_terminal_key_no)) { onSendLine("n") }
            ChatKeyPill(label = stringResource(R.string.chat_terminal_key_interrupt), isDanger = true) { onInterrupt() }
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            BasicTextField(
                value = customText,
                onValueChange = { customText = it },
                modifier = Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(6.dp))
                    .background(SlateRaised)
                    .padding(horizontal = 8.dp, vertical = 6.dp),
                textStyle = TextStyle(
                    color = TextPrimary,
                    fontSize = 11.sp,
                    fontFamily = FontFamily.Monospace,
                ),
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                keyboardActions = KeyboardActions {
                    val trimmed = customText.trim()
                    if (trimmed.isNotEmpty()) {
                        onSendLine(trimmed)
                        customText = ""
                    }
                },
                decorationBox = { innerTextField ->
                    if (customText.isEmpty()) {
                        Text(
                            text = stringResource(R.string.chat_terminal_input_placeholder),
                            style = TextStyle(
                                color = TextMuted,
                                fontSize = 11.sp,
                                fontFamily = FontFamily.Monospace,
                            ),
                        )
                    }
                    innerTextField()
                },
            )

            Box(
                modifier = Modifier
                    .padding(start = 6.dp)
                    .clip(RoundedCornerShape(6.dp))
                    .background(Accent)
                    .clickable(role = Role.Button) {
                        val trimmed = customText.trim()
                        if (trimmed.isNotEmpty()) {
                            onSendLine(trimmed)
                            customText = ""
                        }
                    }
                    .padding(horizontal = 10.dp, vertical = 6.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = "SEND",
                    style = MaterialTheme.typography.labelSmall.copy(
                        fontFamily = FontFamily.Monospace,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold,
                    ),
                    color = Ink,
                )
            }
        }
    }
}

@Composable
private fun ChatKeyPill(
    label: String,
    isDanger: Boolean = false,
    onClick: () -> Unit,
) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(6.dp))
            .background(if (isDanger) Danger.copy(alpha = 0.2f) else SlateRaised)
            .border(1.dp, if (isDanger) Danger.copy(alpha = 0.5f) else Hairline, RoundedCornerShape(6.dp))
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 6.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium.copy(
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold,
                fontSize = 11.sp,
            ),
            color = if (isDanger) Danger else TextPrimary,
        )
    }
}
