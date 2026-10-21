package com.mrj.fancyai.ui.settings

import android.content.Intent
import android.os.Environment
import android.provider.Settings
import android.text.format.Formatter
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
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
import com.mrj.fancyai.ui.theme.Accent
import java.io.File

private fun externalStartDir(): File {
    val download = File(Environment.getExternalStorageDirectory(), "Download")
    return if (download.isDirectory && download.canRead()) download else Environment.getExternalStorageDirectory()
}

@Composable
internal fun ExternalModelPicker(
    onDismiss: () -> Unit,
    onSelect: (String) -> Unit,
) {
    val context = LocalContext.current
    var hasAccess by remember { mutableStateOf(Environment.isExternalStorageManager()) }
    val accessLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        hasAccess = Environment.isExternalStorageManager()
    }
    var currentDir by rememberSaveable { mutableStateOf(externalStartDir().absolutePath) }
    val root = remember { Environment.getExternalStorageDirectory().absolutePath }
    if (currentDir.startsWith(root).not()) currentDir = root
    val dir = remember(currentDir, hasAccess) { File(currentDir) }
    val entries = remember(dir, hasAccess) {
        if (!hasAccess) emptyList()
        else runCatching { dir.listFiles().orEmpty().toList() }.getOrDefault(emptyList())
            .filter { it.isDirectory || (it.isFile && (it.name.endsWith(".gguf", ignoreCase = true) || it.name.endsWith(".litertlm", ignoreCase = true))) }
            .sortedWith(compareBy({ it.isFile }, { it.name.lowercase() }))
    }
    AppDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.engines_link_model)) },
        dismissButton = null,
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.action_done), style = MaterialTheme.typography.labelMedium)
            }
        },
        text = {
            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    text = if (hasAccess) dir.absolutePath else stringResource(R.string.engines_all_files_required),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.MiddleEllipsis,
                )
                if (!hasAccess) {
                    TextButton(onClick = {
                        runCatching {
                            accessLauncher.launch(Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, "package:${context.packageName}".toUri()))
                        }
                    }) {
                        Text(stringResource(R.string.engines_grant_access), style = MaterialTheme.typography.labelMedium, color = Accent)
                    }
                } else {
                    if (dir.absolutePath != root) {
                        Row(
                            Modifier.fillMaxWidth().heightIn(min = 48.dp)
                                .clickable(role = Role.Button) { currentDir = dir.parent ?: root },
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(painter = painterResource(R.drawable.ic_up), contentDescription = null, tint = Accent, modifier = Modifier.padding(end = 10.dp))
                            Text("..", style = MaterialTheme.typography.titleSmall)
                        }
                    }
                    LazyColumn(Modifier.fillMaxWidth().heightIn(max = 380.dp)) {
                        items(entries, key = { it.absolutePath }) { entry ->
                            Row(
                                Modifier.fillMaxWidth().heightIn(min = 52.dp)
                                    .clickable(role = Role.Button) {
                                        if (entry.isDirectory) currentDir = entry.absolutePath else onSelect(entry.absolutePath)
                                    }
                                    .padding(vertical = 6.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Icon(
                                    painter = painterResource(if (entry.isDirectory) R.drawable.ic_forward else R.drawable.ic_add),
                                    contentDescription = null,
                                    tint = if (entry.isDirectory) Accent else MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                                Column(Modifier.weight(1f).padding(horizontal = 10.dp)) {
                                    Text(entry.name, style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                    if (entry.isFile) {
                                        Text(
                                            Formatter.formatShortFileSize(context, entry.length()),
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                    }
                                }
                            }
                        }
                    }
                    if (entries.isEmpty()) {
                        Text(
                            stringResource(R.string.engines_no_models_summary),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        },
    )
}

private fun String.toUri(): android.net.Uri = android.net.Uri.parse(this)
