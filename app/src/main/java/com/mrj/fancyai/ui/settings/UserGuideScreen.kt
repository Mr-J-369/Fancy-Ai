package com.mrj.fancyai.ui.settings

import android.content.ClipData
import android.content.Intent
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import com.mrj.fancyai.R
import com.mrj.fancyai.ui.kit.AppHeader
import com.mrj.fancyai.ui.kit.MessageMarkdown
import com.mrj.fancyai.ui.theme.Ink
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private const val USER_GUIDE_ASSET = "user_guide.md"

@Composable
internal fun UserGuideScreen(onBack: () -> Unit) {
    BackHandler(onBack = onBack)
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    var guideText by remember { mutableStateOf("") }
    LaunchedEffect(Unit) {
        guideText = withContext(Dispatchers.IO) {
            runCatching {
                context.assets.open(USER_GUIDE_ASSET).bufferedReader().use { it.readText() }
            }.getOrDefault("")
        }
    }

    Column(
        Modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    0f to MaterialTheme.colorScheme.surfaceContainerLow,
                    0.28f to Ink,
                    1f to MaterialTheme.colorScheme.background,
                ),
            )
            .windowInsetsPadding(WindowInsets.safeDrawing)
            .padding(horizontal = 20.dp),
    ) {
        AppHeader(
            title = stringResource(R.string.settings_user_guide),
            onBack = onBack,
            subtitle = stringResource(R.string.settings_user_guide_summary),
        )

        val shareLabel = stringResource(R.string.guide_share)

        OutlinedButton(
            onClick = {
                if (guideText.isNotBlank()) {
                    coroutineScope.launch {
                        val uri = withContext(Dispatchers.IO) {
                            runCatching {
                                val docsDir = File(context.cacheDir, "docs").apply { mkdirs() }
                                val guideFile = File(docsDir, USER_GUIDE_ASSET)
                                context.assets.open(USER_GUIDE_ASSET).use { input ->
                                    guideFile.outputStream().use { output ->
                                        input.copyTo(output)
                                    }
                                }
                                FileProvider.getUriForFile(
                                    context,
                                    "${context.packageName}.fileprovider",
                                    guideFile,
                                )
                            }.getOrNull()
                        } ?: return@launch

                        val sendIntent = Intent(Intent.ACTION_SEND).apply {
                            type = "text/markdown"
                            putExtra(Intent.EXTRA_SUBJECT, "FancyAI User Guide")
                            putExtra(Intent.EXTRA_STREAM, uri)
                            clipData = ClipData.newRawUri("FancyAI User Guide", uri)
                            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        }
                        context.startActivity(Intent.createChooser(sendIntent, shareLabel))
                    }
                }
            },
            enabled = guideText.isNotBlank(),
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 12.dp),
        ) {
            Text(shareLabel)
        }

        if (guideText.isBlank()) {
            Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
        } else {
            SelectionContainer(Modifier.weight(1f)) {
                Column(
                    Modifier
                        .fillMaxSize()
                        .verticalScroll(rememberScrollState())
                        .padding(bottom = 24.dp),
                ) {
                    MessageMarkdown(
                        content = guideText,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                }
            }
        }
    }
}
