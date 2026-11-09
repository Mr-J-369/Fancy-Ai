package com.mrj.fancyai.ui.agents

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.mrj.fancyai.R
import com.mrj.fancyai.ui.kit.AppHeader
import com.mrj.fancyai.ui.kit.CompactSwitchRow
import com.mrj.fancyai.ui.kit.PostInput
import com.mrj.fancyai.ui.theme.AccentSoft
import com.mrj.fancyai.ui.theme.Hairline
import com.mrj.fancyai.ui.theme.Ink

@Composable
internal fun AgentsSettingsScreen(controller: AgentsController, onBack: () -> Unit) {
    val prompt by controller.agentPrompt.collectAsState()
    val isThinking by controller.thinking.collectAsState()

    Column(Modifier.fillMaxSize()
        .background(Ink)
        .windowInsetsPadding(WindowInsets.safeDrawing)
        .imePadding()
    ) {
        AppHeader(title = stringResource(R.string.action_instructions), subtitle = null, onBack = onBack)
        HorizontalDivider(color = Hairline)
        LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            item {
                Text(stringResource(R.string.action_instructions),
                    style = MaterialTheme.typography.labelSmall, color = AccentSoft)
                PostInput(
                    value = prompt,
                    hint = "Agent Instructions",
                    singleLine = false,
                    minLines = 6,
                    maxLines = 14,
                    onValueChange = { controller.updateAgentPrompt(it) },
                    modifier = Modifier.padding(top = 16.dp),
                )
                TextButton(onClick = { controller.resetAgentPrompt() }) {
                    Text(stringResource(R.string.action_reset_prompt), style = MaterialTheme.typography.labelMedium)
                }
                CompactSwitchRow(
                    stringResource(R.string.chat_thinking),
                    isThinking,
                    { controller.updateThinking(it) },
                )
            }
        }
    }
}
