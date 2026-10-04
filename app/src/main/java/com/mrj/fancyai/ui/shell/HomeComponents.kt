package com.mrj.fancyai.ui.shell

import android.content.Context
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredWidth
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.mrj.fancyai.R
import com.mrj.fancyai.engine.LiteRtBackend
import com.mrj.fancyai.engine.LlamaBackend
import com.mrj.fancyai.engine.LocalLlmRuntime
import com.mrj.fancyai.ui.settings.EngineStatusNames
import com.mrj.fancyai.ui.settings.LlmSettingsStore
import com.mrj.fancyai.ui.settings.cloudProviderName
import com.mrj.fancyai.ui.theme.Accent
import com.mrj.fancyai.ui.theme.AccentSoft
import com.mrj.fancyai.ui.theme.Ink

@Composable
internal fun HomeWallpaper() {
    Image(
        painter = painterResource(R.drawable.root_home),
        contentDescription = null,
        alpha = 0.72f,
        contentScale = ContentScale.Crop,
        modifier = Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top)),
    )
    Box(
        Modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    0f to Ink.copy(alpha = 0.02f),
                    0.48f to Ink.copy(alpha = 0.06f),
                    0.7f to Ink.copy(alpha = 0.68f),
                    1f to Ink,
                ),
            ),
    )
}

@Composable
internal fun RootPage(
    engineName: String?,
    engineLine: String,
    onOpenEngines: () -> Unit,
    onOpenChat: () -> Unit,
) {
    val ready = engineName != null
    val rootChatDescription = stringResource(R.string.home_talk_to_root)
    Column(
        Modifier.fillMaxSize().padding(horizontal = 24.dp),
    ) {
        Box(
            Modifier.weight(1f).fillMaxWidth()
                .semantics { contentDescription = rootChatDescription }
                .clickable(role = Role.Button, onClick = onOpenChat),
        )
        Row(
            Modifier.fillMaxWidth()
                .clickable(role = Role.Button, onClick = onOpenEngines)
                .padding(vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    stringResource(R.string.home_root),
                    style = MaterialTheme.typography.headlineMedium,
                    color = AccentSoft,
                )
                Text(
                    stringResource(if (ready) R.string.home_root_ready else R.string.home_root_needs_engine),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 8.dp, end = 8.dp),
                )
                Text(
                    if (ready) engineLine else stringResource(R.string.home_finish_engine_setup),
                    style = MaterialTheme.typography.labelSmall,
                    color = if (ready) MaterialTheme.colorScheme.onSurfaceVariant else Accent,
                    modifier = Modifier.padding(top = 12.dp),
                )
            }
            Icon(painter = painterResource(R.drawable.ic_forward), contentDescription = null, tint = Accent, modifier = Modifier.size(22.dp))
        }
        Spacer(Modifier.height(8.dp))
    }
}

internal fun homeEngineLine(context: Context, status: EngineStatusNames): String {
    if (status.active == null) return ""
    status.activeCloud?.let { provider ->
        return context.getString(R.string.home_cloud_engine,
            context.getString(cloudProviderName(provider)), status.active)
    }
    val engine = LlmSettingsStore.selectedEngine(context) ?: return ""
    val runtime = when (engine.model.runtime) {
        LocalLlmRuntime.LITERT -> R.string.engines_litert
        LocalLlmRuntime.LLAMA -> R.string.engines_llama
        LocalLlmRuntime.MNN -> R.string.engines_mnn
    }
    val backend = when (engine.model.runtime) {
        LocalLlmRuntime.LITERT ->
            if (engine.liteRtBackend == LiteRtBackend.CPU) R.string.engines_cpu else R.string.engines_gpu
        LocalLlmRuntime.MNN -> when (engine.llamaBackend) {
            LlamaBackend.OPENCL -> R.string.engines_opencl
            else -> R.string.engines_cpu
        }
        LocalLlmRuntime.LLAMA -> when (engine.llamaBackend) {
            LlamaBackend.CPU -> R.string.engines_cpu
            LlamaBackend.OPENCL -> R.string.engines_opencl
            LlamaBackend.HEXAGON -> R.string.engines_hexagon
        }
    }
    return context.getString(R.string.home_local_engine, context.getString(runtime), context.getString(backend))
}

@Composable
internal fun CategoryBar(selected: HomeCategory, onSelect: (HomeCategory) -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .selectableGroup()
            .horizontalScroll(rememberScrollState())
            .padding(start = 12.dp, end = 12.dp, top = 30.dp),
        horizontalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        HomeCategory.entries.forEach { category ->
            val active = category == selected
            Column(
                Modifier
                    .heightIn(min = 48.dp)
                    .widthIn(min = 68.dp)
                    .selectable(
                        selected = active,
                        onClick = { onSelect(category) },
                        role = Role.Tab,
                    )
                    .padding(horizontal = 10.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Bottom,
            ) {
                Text(
                    stringResource(category.nameRes),
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal,
                    color = if (active) AccentSoft else MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(9.dp))
                Box(
                    Modifier
                        .requiredWidth(if (active) 20.dp else 4.dp)
                        .height(1.dp)
                        .background(if (active) Accent else Color.Transparent),
                )
            }
        }
    }
}

@Composable
internal fun AppTile(
    app: HomeApp,
    onClick: (() -> Unit)?,
) {
    val status = stringResource(R.string.status_ready)
    Column(
        Modifier
            .fillMaxWidth()
            .then(if (onClick == null) Modifier else Modifier.clickable(role = Role.Button, onClick = onClick))
            .semantics(mergeDescendants = true) {
                stateDescription = status
            },
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Image(
            painter = painterResource(app.icon),
            contentDescription = null,
            alpha = 0.72f,
            modifier = Modifier.size(64.dp),
        )
        Text(
            stringResource(app.name),
            style = MaterialTheme.typography.labelMedium,
            textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
        )
    }
}

@Composable
internal fun HomeDock(
    visible: Boolean,
    onOpenCharacters: () -> Unit,
    onOpenGallery: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenChat: () -> Unit,
) {
    Column(
        Modifier
            .fillMaxWidth()
            .then(
                if (visible) {
                    Modifier.background(
                        Brush.verticalGradient(
                            colors = listOf(Color.Transparent, Ink.copy(alpha = 0.94f)),
                        ),
                    )
                } else {
                    Modifier
                },
            )
            .windowInsetsPadding(
                WindowInsets.safeDrawing.only(WindowInsetsSides.Bottom + WindowInsetsSides.Horizontal),
            ),
    ) {
        if (visible) {
            HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.72f))
            Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 8.dp)) {
                DockApps.forEach { app ->
                    val actionModifier = when (app.id) {
                        "chat" -> Modifier.clickable(role = Role.Button, onClick = onOpenChat)
                        "characters" -> Modifier.clickable(role = Role.Button, onClick = onOpenCharacters)
                        "gallery" -> Modifier.clickable(role = Role.Button, onClick = onOpenGallery)
                        "settings" -> Modifier.clickable(role = Role.Button, onClick = onOpenSettings)
                        else -> Modifier.semantics { disabled() }
                    }
                    Column(
                        Modifier
                            .weight(1f)
                            .heightIn(min = 48.dp)
                            .then(actionModifier),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center,
                    ) {
                        Image(
                            painter = painterResource(app.icon),
                            contentDescription = null,
                            alpha = 0.72f,
                            modifier = Modifier.size(40.dp),
                        )
                        Text(
                            stringResource(app.name),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.padding(top = 4.dp),
                        )
                    }
                }
            }
        } else {
            Spacer(Modifier.height(65.dp))
        }
    }
}
