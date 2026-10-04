package com.mrj.fancyai.ui.files

import android.text.format.Formatter
import androidx.activity.compose.BackHandler
import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.mrj.fancyai.R
import com.mrj.fancyai.ui.kit.AppDialog
import com.mrj.fancyai.ui.kit.UiSymbols
import com.mrj.fancyai.ui.theme.Accent
import com.mrj.fancyai.ui.theme.Ink
import java.text.DateFormat
import java.util.Date

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun FileActionsSheet(
    entry: FileManagerEntry,
    canOpen: Boolean,
    canImport: Boolean,
    onDismiss: () -> Unit,
    onOpen: () -> Unit,
    onDetails: () -> Unit,
    onImport: () -> Unit,
    onExport: () -> Unit,
    onDelete: () -> Unit,
) {
    val context = LocalContext.current
    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = MaterialTheme.colorScheme.surface) {
        Column(Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, bottom = 16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(painter = painterResource(entry.mark()), contentDescription = null, tint = Accent, modifier = Modifier.size(32.dp))
                Column(Modifier.padding(start = 12.dp)) {
                    Text(
                        entry.name,
                        style = MaterialTheme.typography.titleMedium,
                        maxLines = 1,
                        overflow = TextOverflow.MiddleEllipsis,
                    )
                    Text(
                        if (entry.directory) stringResource(R.string.files_folder_type) else Formatter.formatShortFileSize(context, entry.size),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            HorizontalDivider(Modifier.padding(vertical = 10.dp), color = MaterialTheme.colorScheme.outline)
            if (canOpen) FileAction(R.string.action_open, onOpen)
            FileAction(R.string.action_details, onDetails)
            if (canImport) FileAction(R.string.files_import_here, onImport)
            if (!entry.directory) FileAction(R.string.action_export, onExport)
            FileAction(R.string.action_delete, onDelete, danger = true)
        }
    }
}

@Composable
internal fun FileAction(@StringRes label: Int, onClick: () -> Unit, danger: Boolean = false) {
    Box(
        Modifier.fillMaxWidth().heightIn(min = 48.dp).clickable(role = Role.Button, onClick = onClick),
        contentAlignment = Alignment.CenterStart,
    ) {
        Text(
            stringResource(label),
            style = MaterialTheme.typography.titleSmall,
            color = if (danger) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
        )
    }
}

@Composable
internal fun FileDetailsDialog(details: FileDetails, onCopyPath: () -> Unit, onDismiss: () -> Unit) {
    val context = LocalContext.current
    AppDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(details.entry.name, maxLines = 1, overflow = TextOverflow.MiddleEllipsis)
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                DetailRow(R.string.files_path, details.entry.path)
                if (!details.entry.directory) DetailRow(R.string.files_size, Formatter.formatShortFileSize(context, details.entry.size))
                DetailRow(
                    R.string.files_type,
                    stringResource(if (details.entry.directory) R.string.files_folder_type else R.string.files_file_type),
                )
                DetailRow(R.string.files_modified, if (details.entry.modified > 0L) DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(details.entry.modified)) else UiSymbols.UNAVAILABLE)
                DetailRow(R.string.files_created, if (details.created > 0L) DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(details.created)) else UiSymbols.UNAVAILABLE)
                DetailRow(
                    R.string.files_access,
                    stringResource(if (details.writable) R.string.files_read_write else R.string.files_read_only),
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.files_close)) }
        },
        dismissButton = {
            TextButton(onClick = onCopyPath) { Text(stringResource(R.string.files_copy_path)) }
        },
    )
}

@Composable
internal fun DetailRow(@StringRes label: Int, value: String) {
    Row(Modifier.fillMaxWidth()) {
        Text(
            stringResource(label),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(72.dp),
        )
        SelectionContainer(Modifier.weight(1f)) {
            Text(value, style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
internal fun TextFilePreview(preview: TextPreview, onBack: () -> Unit) {
    BackHandler(onBack = onBack)
    Column(
        Modifier
            .fillMaxSize()
            .background(Ink)
            .windowInsetsPadding(WindowInsets.safeDrawing)
            .padding(horizontal = 16.dp),
    ) {
        FileManagerHeader(onBack = onBack, onRefresh = null)
        Text(
            preview.name,
            style = MaterialTheme.typography.titleMedium,
            maxLines = 1,
            overflow = TextOverflow.MiddleEllipsis,
        )
        Box(
            Modifier
                .weight(1f)
                .fillMaxWidth()
                .padding(top = 12.dp, bottom = 16.dp)
                .verticalScroll(rememberScrollState())
                .horizontalScroll(rememberScrollState()),
        ) {
            SelectionContainer {
                Text(preview.content, style = MaterialTheme.typography.bodySmall)
            }
        }
        if (preview.truncated) {
            Text(
                stringResource(R.string.files_text_truncated),
                style = MaterialTheme.typography.labelSmall,
                color = Accent,
                modifier = Modifier.padding(bottom = 12.dp),
            )
        }
    }
}
