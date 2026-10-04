package com.mrj.fancyai.ui.kit

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.stringResource
import com.mrj.fancyai.R

/** Keep the current session alive until the user confirms leaving. */
@Composable
internal fun rememberSessionExit(onExit: () -> Unit): () -> Unit {
    var requested by rememberSaveable { mutableStateOf(false) }
    if (requested) {
        AppDialog(
            onDismissRequest = { requested = false },
            title = { Text(stringResource(R.string.session_exit_title)) },
            text = { Text(stringResource(R.string.session_exit_message)) },
            confirmButton = {
                TextButton(onClick = { requested = false; onExit() }) {
                    Text(stringResource(R.string.binder_exit_confirm), color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { requested = false }) {
                    Text(stringResource(R.string.action_cancel))
                }
            },
        )
    }
    return { requested = true }
}
