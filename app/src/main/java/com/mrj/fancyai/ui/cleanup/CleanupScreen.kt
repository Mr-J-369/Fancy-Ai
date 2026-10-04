package com.mrj.fancyai.ui.cleanup

import android.graphics.Bitmap
import android.net.Uri
import android.text.format.Formatter
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import com.mrj.fancyai.R
import com.mrj.fancyai.ui.kit.AppDialog
import com.mrj.fancyai.ui.kit.AppHeader
import com.mrj.fancyai.ui.kit.ImageLightbox
import com.mrj.fancyai.ui.theme.Accent
import com.mrj.fancyai.ui.theme.Ink
import com.mrj.fancyai.util.decodeImage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

@Composable
internal fun CleanupScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val storage = remember(context) { CleanupStorage(context) }
    val dataRoot = remember(context) { File(context.applicationInfo.dataDir).canonicalFile }
    val filesRoot = remember(context) { context.filesDir.canonicalFile }
    val scope = rememberCoroutineScope()
    val controller = remember(storage, scope) { CleanupController(storage, scope) }
    with(controller) {
        val selectedItems = remember(scan, selected) { scan?.items.orEmpty().filter { it.source.file.path in selected } }
        val selectedCount = selectedItems.size + selectedItems.count { it.sidecar != null }
        val selectedBytes = remember(selectedItems) { selectedItems.sumOf { it.source.bytes + (it.sidecar?.bytes ?: 0L) } }

        BackHandler {
            when {
                viewing != null -> viewing = null
                confirmation != null -> confirmation = null
                else -> onBack()
            }
        }
        Column(Modifier.fillMaxSize()
            .background(Brush.verticalGradient(0f to MaterialTheme.colorScheme.surfaceContainerLow, 0.28f to Ink, 1f to MaterialTheme.colorScheme.background))
            .windowInsetsPadding(WindowInsets.safeDrawing).padding(horizontal = 20.dp)) {
            AppHeader(title = stringResource(R.string.cleanup_title), onBack = onBack, subtitle = stringResource(R.string.home_category_system))
            LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(top = 12.dp, bottom = 20.dp)) {
                item(key = "overview") { Overview() }
                categories(controller, dataRoot, filesRoot)
            }
            if (selectedItems.isNotEmpty() && !busy) {
                HorizontalDivider(color = MaterialTheme.colorScheme.outline)
                Text(pluralStringResource(R.plurals.cleanup_selection, selectedCount, selectedCount),
                    style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 12.dp))
                TextButton(onClick = { confirmation = selectedItems }, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.cleanup_remove, Formatter.formatShortFileSize(context, selectedBytes)), color = Accent)
                }
            }
        }
        confirmation?.let { pending ->
            val count = pending.size + pending.count { it.sidecar != null }
            AppDialog(onDismissRequest = { confirmation = null },
                title = { Text(stringResource(R.string.cleanup_remove,
                    Formatter.formatShortFileSize(context, pending.sumOf { it.source.bytes + (it.sidecar?.bytes ?: 0L) }))) },
                text = { Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(pluralStringResource(R.plurals.cleanup_confirm_count, count, count, Formatter.formatShortFileSize(context, pending.sumOf { it.source.bytes + (it.sidecar?.bytes ?: 0L) })))
                    Text(stringResource(R.string.cleanup_confirm_detail))
                } },
                dismissButton = { TextButton(onClick = { confirmation = null }) { Text(stringResource(R.string.action_cancel), style = MaterialTheme.typography.labelMedium) } },
                confirmButton = { TextButton(onClick = {
                    confirmation = null
                    refresh(pending)
                }) { Text(stringResource(R.string.action_delete), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.error) } },
            )
        }
        viewing?.let { file ->
            var bitmap by remember(file) { mutableStateOf<Bitmap?>(null) }
            LaunchedEffect(file) {
                bitmap = withContext(Dispatchers.IO) { decodeImage(context, Uri.fromFile(file), maxEdge = 2048) }
            }
            ImageLightbox(bitmap, R.string.cleanup_view_image, { viewing = null })
        }
    }
}

@Composable
private fun CleanupController.Overview() {
    val context = LocalContext.current
    val totalBytes = remember(scan) { scan?.items.orEmpty().sumOf { it.source.bytes + (it.sidecar?.bytes ?: 0L) } }
    Text(stringResource(if (scan == null) R.string.cleanup_intro else R.string.cleanup_available),
        style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurface)
    if (scan != null) Text(Formatter.formatShortFileSize(context, totalBytes), style = MaterialTheme.typography.labelMedium.copy(fontFeatureSettings = "tnum"),
        color = Accent, modifier = Modifier.padding(top = 4.dp, bottom = 4.dp))
    Text(stringResource(R.string.cleanup_scope), style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 8.dp))
    Text(stringResource(R.string.cleanup_protection), style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 10.dp))
    TextButton(onClick = { if (!busy) refresh() }, enabled = !busy, contentPadding = PaddingValues(vertical = 12.dp)) {
        Text(stringResource(if (scan == null) R.string.cleanup_scan else R.string.cleanup_rescan), color = Accent)
    }
    if (busy) {
        LinearProgressIndicator(Modifier.fillMaxWidth(), color = Accent)
        Text(stringResource(if (deleting) R.string.cleanup_removing else R.string.cleanup_scanning),
            style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(vertical = 12.dp))
    }
    result?.let { outcome ->
        Text(stringResource(R.string.cleanup_complete, Formatter.formatShortFileSize(context, outcome.bytes)), color = Accent,
            style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(top = 12.dp))
        Text(pluralStringResource(R.plurals.cleanup_removed, outcome.removed, outcome.removed),
            style = MaterialTheme.typography.bodySmall)

    }
    Spacer(Modifier.height(8.dp))
}

private fun androidx.compose.foundation.lazy.LazyListScope.categories(
    controller: CleanupController,
    dataRoot: File,
    filesRoot: File,
) = with(controller) {
    val currentScan = scan
    val grouped = scan?.items.orEmpty().groupBy(CleanupItem::kind)
    if (currentScan != null) {
        if (currentScan.incompleteReferences) item(key = "incomplete_references") {
            Text(stringResource(R.string.cleanup_references_incomplete),
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
        }
        for ((kind, title, detail) in listOf(
            Triple(CleanupKind.Cache, R.string.cleanup_cache, R.string.cleanup_cache_detail),
            Triple(CleanupKind.Orphan, R.string.cleanup_orphans, R.string.cleanup_orphans_detail),
            Triple(CleanupKind.Duplicate, R.string.cleanup_duplicates, R.string.cleanup_duplicates_detail),
        )) {
            val entries = grouped[kind].orEmpty()
            val fileCount = entries.size + entries.count { it.sidecar != null }
            item(key = kind.name) {
                val context = LocalContext.current
                HorizontalDivider(color = MaterialTheme.colorScheme.outline)
                val open = expanded == kind
                val label = stringResource(if (open) R.string.cleanup_expanded else R.string.cleanup_collapsed)
                Column(Modifier.fillMaxWidth().heightIn(min = 48.dp)
                    .semantics { stateDescription = label }
                    .clickable(role = Role.Button, enabled = !busy) { expanded = if (open) null else kind }
                    .padding(vertical = 8.dp)) {
                    Text(stringResource(title), style = MaterialTheme.typography.labelMedium)
                    Text(pluralStringResource(R.plurals.cleanup_file_count, fileCount, fileCount,
                        Formatter.formatShortFileSize(context, entries.sumOf { it.source.bytes + (it.sidecar?.bytes ?: 0L) })), style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    AnimatedVisibility(open) {
                        Text(stringResource(detail), style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 10.dp))
                    }
                }
                if (open) {
                    if (entries.isEmpty()) Text(stringResource(R.string.cleanup_nothing),
                        style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(bottom = 16.dp))
                    else {
                        val all = entries.all { it.source.file.path in selected }
                        TextButton(enabled = !busy, onClick = {
                            val ids = entries.mapTo(mutableSetOf()) { it.source.file.path }
                            selected = if (all) selected - ids else selected + ids
                        }) { Text(stringResource(if (all) R.string.gallery_clear_selection else R.string.gallery_select_all), color = Accent) }
                    }
                }
            }
            if (expanded == kind) items(entries, key = { it.source.file.path }) { item ->
                FileRow(item, dataRoot, filesRoot)
            }
        }
        if (currentScan.items.isEmpty()) item(key = "empty") {
            Text(stringResource(R.string.cleanup_nothing), style = MaterialTheme.typography.labelMedium,
                modifier = Modifier.padding(vertical = 12.dp))
        }
    }
}

@Composable
private fun CleanupController.FileRow(item: CleanupItem, dataRoot: File, filesRoot: File) {
    val context = LocalContext.current
    Row(Modifier.fillMaxWidth().toggleable(value = item.source.file.path in selected, enabled = !busy, role = Role.Checkbox) {
        selected = if (it) selected + item.source.file.path else selected - item.source.file.path
    }.padding(bottom = 14.dp), verticalAlignment = Alignment.Top) {
        Checkbox(checked = item.source.file.path in selected, enabled = !busy,
            onCheckedChange = null,
            colors = CheckboxDefaults.colors(checkedColor = Accent, checkmarkColor = Ink))
        Column(Modifier.weight(1f).padding(top = 12.dp)) {
            Text(item.source.file.name, style = MaterialTheme.typography.bodyMedium)
            Text(item.source.file.parentFile?.relativeToOrSelf(dataRoot)?.path.orEmpty(),
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(Formatter.formatShortFileSize(context, item.source.bytes + (item.sidecar?.bytes ?: 0L)), style = MaterialTheme.typography.bodySmall, color = Accent)
            item.keeper?.let { kept ->
                Text(stringResource(R.string.cleanup_kept_copy, kept.file.relativeTo(filesRoot).path),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 6.dp))
                TextButton(onClick = { viewing = item.source.file }, enabled = !busy,
                    contentPadding = PaddingValues(vertical = 6.dp)) {
                    Text(stringResource(R.string.cleanup_view_image), color = Accent)
                }
            }
        }
    }
}
