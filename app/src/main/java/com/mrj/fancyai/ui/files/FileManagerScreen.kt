package com.mrj.fancyai.ui.files

import android.content.ClipData
import android.content.ClipboardManager
import android.text.format.Formatter
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.BorderStroke
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
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.mrj.fancyai.R
import com.mrj.fancyai.ui.kit.AppDialog
import com.mrj.fancyai.ui.kit.AppHeader
import com.mrj.fancyai.ui.kit.ImageLightbox
import com.mrj.fancyai.ui.kit.UiSymbols
import com.mrj.fancyai.ui.theme.Accent
import com.mrj.fancyai.ui.theme.AccentSoft
import com.mrj.fancyai.ui.theme.Ink
import java.io.File
import java.text.DateFormat
import java.util.Date
import java.util.Locale

@Composable
internal fun FileManagerScreen(initialDirectory: File? = null, onBack: () -> Unit) {
    val context = LocalContext.current
    val controller = remember(context) { FileManagerController(context) }
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    var pendingImportTarget by rememberSaveable { mutableStateOf<String?>(null) }
    var pendingExportPath by rememberSaveable { mutableStateOf<String?>(null) }

    val importPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        val target = pendingImportTarget
        pendingImportTarget = null
        if ((target != null) && uris.isNotEmpty()) {
            controller.importFiles(target, uris, scope)
        }
    }
    val exportPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/octet-stream"),
    ) { uri ->
        val sourcePath = pendingExportPath
        pendingExportPath = null
        if ((sourcePath != null) && (uri != null)) {
            controller.exportFile(sourcePath, uri, scope)
        }
    }

    val textPreview = controller.textPreview
    val noticeText = when (val current = controller.notice) {
        is FileNotice.Text -> stringResource(current.resource)
        is FileNotice.Count -> pluralStringResource(current.resource, current.count, current.count)
        null -> null
    }
    LaunchedEffect(noticeText) {
        noticeText?.let {
            snackbar.showSnackbar(it)
            controller.notice = null
        }
    }

    BackHandler(enabled = textPreview == null, onBack = onBack)
    controller.FileTreeBrowser(
        initialDirectory = initialDirectory,
        onBack = onBack,
    ) { target ->
        pendingImportTarget = target
        importPicker.launch(arrayOf("*/*"))
    }
    if (textPreview != null) {
        TextFilePreview(textPreview) { controller.textPreview = null }
    } else {
        Box(Modifier.fillMaxSize()) {
            SnackbarHost(
                hostState = snackbar,
                modifier = Modifier.align(Alignment.BottomCenter).windowInsetsPadding(WindowInsets.safeDrawing),
            )
        }

        controller.FileOperationDialogs(
            scope,
            onImport = { (path) ->
                pendingImportTarget = path
                importPicker.launch(arrayOf("*/*"))
            },
            onExport = { (path, name) ->
                pendingExportPath = path
                exportPicker.launch(name)
            },
        )
    }

}

@Composable
private fun FileManagerController.FileOperationDialogs(
    scope: kotlinx.coroutines.CoroutineScope,
    onImport: (FileManagerEntry) -> Unit,
    onExport: (FileManagerEntry) -> Unit,
) {
    val context = LocalContext.current
    actions?.let { entry ->
        FileActionsSheet(
            entry = entry,
            canOpen = (!entry.directory) && (File(entry.name).extension.lowercase(Locale.ROOT) in PREVIEW_EXTENSIONS),
            canImport = entry.directory,
            onDismiss = { actions = null },
            onOpen = {
                actions = null
                open(entry, scope)
            },
            onDetails = {
                actions = null
                loadDetails(entry, scope)
            },
            onImport = {
                actions = null
                onImport(entry)
            },
            onExport = {
                actions = null
                onExport(entry)
            },
        ) {
            actions = null
            deleteCandidate = entry
        }
    }
    details?.let { value ->
        FileDetailsDialog(
            details = value,
            onCopyPath = {
                val clipboard = context.getSystemService(ClipboardManager::class.java)
                @Suppress("UsePropertyAccessSyntax")
                clipboard.setPrimaryClip(ClipData.newPlainText(value.entry.name, value.entry.path))
                notice = FileNotice.Text(R.string.files_path_copied)
            },
        ) { details = null }
    }
    deleteCandidate?.let { entry ->
        AppDialog(
            onDismissRequest = { deleteCandidate = null },
            title = { Text(stringResource(R.string.delete_named_title, entry.name)) },
            text = { Text(stringResource(R.string.files_delete_message)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        deleteCandidate = null
                        delete(entry, scope)
                    },
                ) {
                    Text(stringResource(R.string.action_delete), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { deleteCandidate = null }) {
                    Text(stringResource(R.string.action_cancel), style = MaterialTheme.typography.labelMedium)
                }
            },
        )
    }
    imagePreview?.let { bitmap ->
        ImageLightbox(bitmap, description = R.string.files_image_preview, onClose = { imagePreview = null })
    }
    if (busy) {
        AppDialog(
            onDismissRequest = {},
            confirmButton = {},
            title = { Text(stringResource(R.string.files_working)) },
            text = {
                Box(Modifier.fillMaxWidth().padding(12.dp), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(color = Accent)
                }
            },
        )
    }
}

@Composable
internal fun FileManagerHeader(onBack: () -> Unit, onRefresh: (() -> Unit)?) {
    val refreshDescription = stringResource(R.string.files_refresh)
    Row(
        Modifier.fillMaxWidth().heightIn(min = 64.dp).padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AppHeader(
            title = stringResource(R.string.home_app_storage),
            subtitle = stringResource(R.string.files_subtitle),
            onBack = onBack,
            modifier = Modifier.weight(1f),
        )
        if (onRefresh == null) {
            Spacer(Modifier.size(48.dp))
        } else {
            Surface(
                color = MaterialTheme.colorScheme.surfaceVariant,
                shape = RoundedCornerShape(14.dp),
                modifier = Modifier
                    .size(48.dp)
                    .semantics { contentDescription = refreshDescription }
                    .clickable(role = Role.Button, onClick = onRefresh),
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(painter = painterResource(R.drawable.ic_refresh), contentDescription = null, tint = Accent, modifier = Modifier.size(22.dp))
                }
            }
        }
    }
}

@Composable
internal fun ImportSurface(summary: String, onClick: () -> Unit) {
    Surface(
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.78f),
        shape = RoundedCornerShape(topStart = 8.dp, topEnd = 24.dp, bottomEnd = 8.dp, bottomStart = 24.dp),
        border = BorderStroke(1.dp, Accent.copy(alpha = 0.42f)),
        modifier = Modifier.fillMaxWidth().clickable(role = Role.Button, onClick = onClick),
    ) {
        Row(
            Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                Modifier.size(48.dp).background(MaterialTheme.colorScheme.primaryContainer, RoundedCornerShape(14.dp)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(painter = painterResource(R.drawable.ic_add), contentDescription = null, tint = Accent, modifier = Modifier.size(22.dp))
            }
            Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                Text(stringResource(R.string.files_import), style = MaterialTheme.typography.titleMedium)
                Text(
                    summary,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 2.dp),
                )
            }
            Icon(painter = painterResource(R.drawable.ic_up), contentDescription = null, tint = AccentSoft, modifier = Modifier.size(22.dp))
        }
    }
}

@Composable
internal fun FileSectionHeader(
    count: Int,
    sort: FileSort,
    ascending: Boolean,
    onSort: (FileSort) -> Unit,
) {
    Column(Modifier.fillMaxWidth().padding(top = 20.dp, bottom = 8.dp)) {
        Text(
            pluralStringResource(R.plurals.files_items, count, count),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Row(
            Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .selectableGroup()
                .padding(top = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            FileSort.entries.forEach { choice ->
                val selected = sort == choice
                val description = stringResource(
                    if (ascending) R.string.files_sort_ascending else R.string.files_sort_descending,
                )
                Surface(
                    color = if (selected) MaterialTheme.colorScheme.primaryContainer else Color.Transparent,
                    shape = RoundedCornerShape(10.dp),
                    border = BorderStroke(
                        1.dp,
                        if (selected) Accent else MaterialTheme.colorScheme.outline,
                    ),
                    modifier = Modifier
                        .heightIn(min = 48.dp)
                        .selectable(
                            selected = selected,
                            role = Role.RadioButton,
                        ) { onSort(choice) },
                ) {
                    Row(
                        Modifier.padding(horizontal = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            stringResource(
                                when (choice) {
                                    FileSort.NAME -> R.string.field_name
                                    FileSort.DATE -> R.string.files_sort_date
                                    FileSort.SIZE -> R.string.files_size
                                },
                            ),
                            style = MaterialTheme.typography.labelLarge,
                            color = if (selected) AccentSoft else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        if (selected) {
                            Icon(
                                painter = painterResource(if (ascending) R.drawable.ic_up else R.drawable.ic_down),
                                contentDescription = null,
                                tint = Accent,
                                modifier = Modifier
                                    .padding(start = 7.dp)
                                    .semantics { contentDescription = description }
                                    .size(22.dp),
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
internal fun FileTreeRow(
    entry: FileManagerEntry,
    expanded: Boolean,
    onOpen: () -> Unit,
    onActions: () -> Unit,
) {
    val context = LocalContext.current
    val modified = if (entry.modified > 0L) DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(entry.modified)) else UiSymbols.UNAVAILABLE
    val openable = entry.directory || (File(entry.name).extension.lowercase(Locale.ROOT) in PREVIEW_EXTENSIONS)
    val rotation by animateFloatAsState(if (expanded) 90f else 0f, label = "folder chevron")
    Column {
        Row(
            Modifier
                .fillMaxWidth()
                .heightIn(min = 56.dp)
                .clickable(enabled = openable, role = Role.Button, onClick = onOpen)
                .padding(start = (10 + (minOf(entry.depth, 8) * 22)).dp, end = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (entry.directory) {
                Icon(painter = painterResource(R.drawable.ic_forward), contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.rotate(rotation).width(24.dp).size(22.dp))
            } else {
                Spacer(Modifier.width(24.dp))
            }
            Icon(painter = painterResource(entry.mark()), contentDescription = null, tint = if (entry.directory) Accent else MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.width(34.dp).size(32.dp))
            Column(Modifier.weight(1f).padding(horizontal = 8.dp)) {
                Text(
                    entry.name,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.MiddleEllipsis,
                )
                Text(
                    if (entry.directory) modified else stringResource(R.string.two_part_meta, modified, Formatter.formatShortFileSize(context, entry.size)),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.MiddleEllipsis,
                )
            }
            val actionDescription = stringResource(R.string.files_actions)
            Box(
                Modifier
                    .size(48.dp)
                    .semantics { contentDescription = actionDescription }
                    .clickable(role = Role.Button, onClick = onActions),
                contentAlignment = Alignment.Center,
            ) {
                Icon(painter = painterResource(R.drawable.ic_more_horizontal), contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(22.dp))
            }
        }
        HorizontalDivider(
            color = MaterialTheme.colorScheme.outline.copy(alpha = 0.7f),
            modifier = Modifier.padding(start = (68 + (minOf(entry.depth, 8) * 22)).dp),
        )
    }
}

@Composable
internal fun EmptyFolderRow(depth: Int) {
    Text(
        stringResource(R.string.files_empty_folder),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(
            start = (68 + (minOf(depth, 8) * 22)).dp,
            top = 8.dp,
            bottom = 10.dp,
        ),
    )
}

@Composable
internal fun EmptyFileManager() {
    Column(
        Modifier.fillMaxWidth().padding(vertical = 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(stringResource(R.string.files_no_documents), style = MaterialTheme.typography.titleMedium)
        Text(
            stringResource(R.string.files_no_documents_summary),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 4.dp),
        )
    }
}

@Composable
internal fun FileManagerController.FileTreeBrowser(
    initialDirectory: File?,
    onBack: () -> Unit,
    onImportRequest: (String) -> Unit,
) {
    val scope = rememberCoroutineScope()
    var expanded by remember(initialDirectory) {
        mutableStateOf(setOfNotNull(initialDirectory?.path))
    }
    var sort by rememberSaveable { mutableStateOf(FileSort.NAME) }
    var ascending by rememberSaveable { mutableStateOf(value = true) }

    LaunchedEffect(this, initialDirectory, expanded, sort, ascending, revision) {
        loadEntries(initialDirectory, expanded, sort, ascending)
    }

    Box(
        Modifier
            .fillMaxSize()
            .background(
                Brush.radialGradient(
                    colors = listOf(MaterialTheme.colorScheme.surfaceVariant, Ink),
                    radius = 1_500f,
                ),
            ),
    ) {
        LazyColumn(
            modifier = Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 24.dp),
        ) {
            item { FileManagerHeader(onBack = onBack) { revision++ } }
            item {
                ImportSurface(
                    summary = stringResource(
                        if (initialDirectory == null) R.string.files_import_summary else R.string.terminal_import_summary,
                    ),
                ) {
                    onImportRequest(initialDirectory?.path ?: ROOT_IMPORT_TARGET)
                }
            }
            item {
                FileSectionHeader(
                    count = entries.count { (it.depth == 0) && !it.empty },
                    sort = sort,
                    ascending = ascending,
                ) { selected ->
                    if (sort == selected) ascending = !ascending else {
                        sort = selected
                        ascending = true
                    }
                }
            }
            if (entries.isEmpty() && !loading) {
                item { EmptyFileManager() }
            } else {
                items(entries, key = FileManagerEntry::path) { entry ->
                    if (entry.empty) {
                        EmptyFolderRow(entry.depth)
                    } else {
                        FileTreeRow(
                            entry = entry,
                            expanded = entry.path in expanded,
                            onOpen = {
                                if (entry.directory) {
                                    expanded = if (entry.path in expanded) expanded - entry.path else expanded + entry.path
                                } else open(entry, scope)
                            },
                            onActions = { actions = entry },
                        )
                    }
                }
            }
            if (loading) {
                item {
                    Box(Modifier.fillMaxWidth().padding(20.dp), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(color = Accent, modifier = Modifier.requiredSize(24.dp))
                    }
                }
            }
        }
    }
}
