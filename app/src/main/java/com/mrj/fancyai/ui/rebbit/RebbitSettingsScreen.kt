package com.mrj.fancyai.ui.rebbit

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.core.content.edit
import com.mrj.fancyai.R
import com.mrj.fancyai.ui.kit.AppHeader
import com.mrj.fancyai.ui.kit.CompactSwitch
import com.mrj.fancyai.ui.kit.CompactSwitchRow
import com.mrj.fancyai.ui.kit.PostInput
import com.mrj.fancyai.ui.theme.AccentSoft
import com.mrj.fancyai.ui.theme.Hairline
import com.mrj.fancyai.ui.theme.Ink

@Composable
internal fun RebbitController.RebbitSettingsScreen(
    communitiesOnly: Boolean, communities: List<String>, onBack: () -> Unit, onOpenCommunity: (String) -> Unit,
) {
    val shown = remember(communities, communitySearch) { communities.filter { it.contains(communitySearch.trim(), ignoreCase = true) } }
    val communitiesLabel = stringResource(R.string.rebbit_browse_communities)
    val defaultPrompt = stringResource(R.string.rebbit_default_prompt)
    Column(Modifier.fillMaxSize()
        .background(Brush.verticalGradient(0f to MaterialTheme.colorScheme.surfaceContainerLow, 0.28f to Ink, 1f to MaterialTheme.colorScheme.background))
        .windowInsetsPadding(WindowInsets.safeDrawing).imePadding().padding(horizontal = 0.dp)) {
        AppHeader(title = if (communitiesOnly) communitiesLabel else stringResource(R.string.action_instructions), subtitle = null, onBack = onBack)
        HorizontalDivider(color = Hairline)
        LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(if (communitiesOnly) 8.dp else 16.dp)) {
            if (!communitiesOnly) {
                item {
                    Text(stringResource(R.string.rebbit_prompt_section),
                        style = MaterialTheme.typography.labelSmall, color = AccentSoft)
                    PostInput(
                        value = rebbitPrompt,
                        hint = stringResource(R.string.rebbit_prompt_hint),
                        singleLine = false,
                        minLines = 6,
                        maxLines = 14,
                        onValueChange = {
                            rebbitPrompt = it
                            preferences.edit { putString(KEY_REBBIT_PROMPT, it) }
                        },
                        modifier = Modifier.padding(top = 16.dp),
                    )
                    TextButton(onClick = {
                        rebbitPrompt = defaultPrompt
                        preferences.edit { putString(KEY_REBBIT_PROMPT, rebbitPrompt) }
                    }) {
                        Text(stringResource(R.string.action_reset_prompt), style = MaterialTheme.typography.labelMedium)
                    }
                    CompactSwitchRow(
                        stringResource(R.string.chat_thinking),
                        thinking,
                        ::updateThinking,
                    )
                }

            } else {
                item {
                    Text(communitiesLabel, style = MaterialTheme.typography.labelSmall, color = AccentSoft)
                    Text(stringResource(R.string.rebbit_community_summary),
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 6.dp))
                    Row(Modifier.padding(top = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                        PostInput(value = communityDraft, hint = stringResource(R.string.rebbit_community_hint), singleLine = true, onValueChange = {
                            communityDraft = it.filterNot(Char::isWhitespace).take(REBBIT_COMMUNITY_INPUT_LIMIT)
                            preferences.edit { putString(KEY_REBBIT_COMMUNITY_DRAFT, communityDraft) }
                        }, modifier = Modifier.weight(1f))
                        TextButton(enabled = normalizedCommunity(communityDraft) != null && communities.none { it.equals(normalizedCommunity(communityDraft), true) }, onClick = ::addCommunity) { Text(stringResource(R.string.action_add), style = MaterialTheme.typography.labelMedium) }
                    }
                }
                item { PostInput(value = communitySearch, hint = stringResource(R.string.rebbit_community_filter_hint), singleLine = true, onValueChange = {
                    communitySearch = it
                    preferences.edit { putString(KEY_COMMUNITY_SEARCH, it) }
                }) }
                item {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(stringResource(R.string.rebbit_community_all), style = MaterialTheme.typography.labelMedium, modifier = Modifier.weight(1f))
                        CompactSwitch(checked = communities.isNotEmpty() && communities.all(enabledCommunities::contains), onCheckedChange = { setAllCommunitiesEnabled(it, communities) }, enabled = communities.isNotEmpty(), label = stringResource(R.string.rebbit_community_all))
                    }
                }
                if (communities.isEmpty()) item { Text(stringResource(R.string.rebbit_community_empty), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                items(shown, key = { it }) { community ->
                    Column {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f).heightIn(min = 48.dp).clickable(role = Role.Button) { onOpenCommunity(community) }, verticalArrangement = Arrangement.Center) {
                                Text(community, style = MaterialTheme.typography.labelMedium)
                            }
                            val toggleLabel = stringResource(R.string.rebbit_community_toggle, community)
                            CompactSwitch(checked = community in enabledCommunities,
                                onCheckedChange = { setCommunityEnabled(community, it) }, label = toggleLabel)
                            Text(
                                stringResource(R.string.action_remove),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.error,
                                modifier = Modifier.heightIn(min = 48.dp)
                                    .clickable(role = Role.Button) { removeCommunity(community) }
                                    .padding(start = 12.dp, top = 17.dp),
                            )
                        }
                        HorizontalDivider(color = Hairline)
                    }
                }
            }
        }
    }
}
