package com.mrj.fancyai.ui.settings

import android.content.ActivityNotFoundException
import android.content.Intent
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.annotation.StringRes
import androidx.core.net.toUri
import com.mrj.fancyai.BuildConfig
import com.mrj.fancyai.R
import com.mrj.fancyai.ui.kit.AppHeader
import com.mrj.fancyai.ui.theme.Ink
import com.mrj.fancyai.update.InAppUpdater
import kotlinx.coroutines.launch

@Composable
internal fun AboutScreen(onBack: () -> Unit) {
    BackHandler(onBack = onBack)
    val context = LocalContext.current
    val resources = LocalResources.current
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()
            .background(Brush.verticalGradient(0f to MaterialTheme.colorScheme.surfaceContainerLow, 0.28f to Ink, 1f to MaterialTheme.colorScheme.background))
            .windowInsetsPadding(WindowInsets.safeDrawing).padding(horizontal = 20.dp)) {
            AppHeader(title = stringResource(R.string.settings_about), onBack = onBack, subtitle = stringResource(R.string.settings_about_summary))
            Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(vertical = 24.dp)) {
                Text(stringResource(R.string.app_name), style = MaterialTheme.typography.headlineLarge)
                Text(
                    stringResource(R.string.about_version, BuildConfig.VERSION_NAME),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(24.dp))
                HorizontalDivider(color = MaterialTheme.colorScheme.outline)
                SettingsRow(
                    title = stringResource(R.string.update_check_title),
                    summary = stringResource(R.string.update_check_summary),
                    onClick = {
                        (context as? ComponentActivity)?.let { activity ->
                            InAppUpdater(activity).check()
                        }
                    },
                )
                HorizontalDivider(color = MaterialTheme.colorScheme.outline)
                AboutLinks.forEach { link ->
                    SettingsRow(
                        title = stringResource(link.title),
                        summary = stringResource(link.summary),
                        onClick = {
                            val uri = link.url.toUri()
                            try {
                                context.startActivity(Intent(if (uri.scheme == "mailto") Intent.ACTION_SENDTO else Intent.ACTION_VIEW, uri))
                            } catch (_: ActivityNotFoundException) {
                                scope.launch { snackbar.showSnackbar(resources.getString(R.string.about_link_failed)) }
                            }
                        },
                    )
                    HorizontalDivider(color = MaterialTheme.colorScheme.outline)
                }
            }
        }
        SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter))
    }
}

private data class AboutLink(@param:StringRes val title: Int, @param:StringRes val summary: Int, val url: String)

private val AboutLinks = listOf(
    AboutLink(R.string.about_sponsor, R.string.about_sponsor_summary, "https://ko-fi.com/mrj369"),
    AboutLink(R.string.about_privacy, R.string.about_privacy_summary, "https://huggingface.co/Mr-J-369/Fancy-AI/blob/main/PRIVACY.md"),
    AboutLink(R.string.about_changelog, R.string.about_changelog_summary, "https://fancyai-os.com/changelog"),
    AboutLink(R.string.about_email, R.string.about_email_address, "mailto:support@fancyai-os.com"),
    AboutLink(R.string.about_telegram, R.string.about_telegram_summary, "https://t.me/Fancy_Ai_Chat"),
    AboutLink(R.string.about_issues, R.string.about_issues_summary, "https://github.com/Mr-J-369/Fancy-Ai/issues"),
    AboutLink(R.string.about_website, R.string.about_website_summary, "https://fancyai-os.com"),
)
