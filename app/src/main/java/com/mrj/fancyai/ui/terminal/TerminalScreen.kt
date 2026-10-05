package com.mrj.fancyai.ui.terminal

import android.annotation.SuppressLint
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.text.format.Formatter
import android.util.Base64
import android.util.Log
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.webkit.ConsoleMessage
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.mrj.fancyai.R
import com.mrj.fancyai.terminal.LinuxDistribution
import com.mrj.fancyai.terminal.TerminalSession
import com.mrj.fancyai.terminal.TerminalWorkspace
import com.mrj.fancyai.terminal.TerminalWorkspaceState
import com.mrj.fancyai.terminal.exportTerminalSources
import com.mrj.fancyai.ui.files.FileManagerScreen
import com.mrj.fancyai.ui.kit.AppDialog
import com.mrj.fancyai.ui.theme.Accent
import com.mrj.fancyai.ui.theme.Danger
import com.mrj.fancyai.ui.theme.Hairline
import com.mrj.fancyai.ui.theme.Ink
import com.mrj.fancyai.ui.theme.SlateRaised
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import java.io.ByteArrayInputStream
import android.view.ViewGroup.LayoutParams as ViewLayoutParams
import android.view.WindowInsets as AndroidWindowInsets

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun TerminalScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val runtime = remember(context) { TerminalWorkspace.get(context) }
    val state by runtime.state.collectAsState()
    val scope = rememberCoroutineScope()
    val selectedSession = rememberSaveable { mutableStateOf<String?>(null) }
    var selectedId by selectedSession
    val session = state.sessions.firstOrNull { it.id == selectedId } ?: state.sessions.lastOrNull()
    var menu by remember { mutableStateOf(false) }
    var environments by rememberSaveable { mutableStateOf(false) }
    var files by rememberSaveable { mutableStateOf(false) }
    var deleting by remember { mutableStateOf<LinuxDistribution?>(null) }
    var closing by remember { mutableStateOf<TerminalSession?>(null) }
    val terminalView = remember { mutableStateOf<TerminalView?>(null) }
    var view by terminalView

    val exportSources = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri ->
        if (uri != null) scope.launch {
            withContext(Dispatchers.IO) { exportTerminalSources(context, uri) }
        }
    }

    if (files) {
        FileManagerScreen(initialDirectory = runtime.environments.workspace) { files = false }
        return
    }
    BackHandler(onBack = onBack)
    Column(Modifier.fillMaxSize().background(Ink).windowInsetsPadding(WindowInsets.safeDrawing).imePadding()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            val back = stringResource(R.string.settings_back)
            TextButton(onClick = onBack, modifier = Modifier.semantics { contentDescription = back }) {
                Icon(painter = painterResource(R.drawable.ic_back), contentDescription = null, tint = MaterialTheme.colorScheme.onSurface, modifier = Modifier.size(22.dp))
            }
            Text(stringResource(R.string.terminal_title), style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
            Box {
                val more = stringResource(R.string.action_more_options)
                TextButton(onClick = { menu = true }, modifier = Modifier.semantics { contentDescription = more }) {
                    Icon(painter = painterResource(R.drawable.ic_more), contentDescription = null, tint = MaterialTheme.colorScheme.onSurface, modifier = Modifier.size(22.dp))
                }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    DropdownMenuItem(text = { Text(stringResource(R.string.terminal_new_session)) }, onClick = { menu = false; environments = true })
                    DropdownMenuItem(text = { Text(stringResource(R.string.terminal_files)) }, onClick = { menu = false; files = true })
                    DropdownMenuItem(text = { Text(stringResource(R.string.terminal_copy)) }, enabled = session != null, onClick = { menu = false; view?.copySelection() })
                    DropdownMenuItem(text = { Text(stringResource(R.string.terminal_paste)) }, enabled = session != null, onClick = {
                        menu = false
                        val clipboard = context.getSystemService(ClipboardManager::class.java)
                        clipboard.primaryClip?.getItemAt(0)?.coerceToText(context)?.let { view?.evaluateJavascript("window.pasteTerminal?.(${JsonPrimitive(it.toString())})", null) }
                    })
                    DropdownMenuItem(text = { Text(stringResource(R.string.terminal_end_session)) }, enabled = session != null && !state.busy, onClick = { menu = false; closing = session })
                    DropdownMenuItem(text = { Text(stringResource(R.string.terminal_sources)) }, onClick = { menu = false; exportSources.launch("fancy-terminal-sources.zip") })
                }
            }
        }
        HorizontalDivider(color = Hairline)
        state.error?.let { error ->
            Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.terminal_error, error), color = Danger, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                TextButton(onClick = runtime::clearError) { Text(stringResource(R.string.files_close)) }
            }
        }
        if (state.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
        TerminalSessions(
            state, runtime, session, selectedSession, terminalView,
            onDelete = { deleting = it },
        )
    }
    if (environments) ModalBottomSheet(onDismissRequest = { environments = false }, containerColor = Ink) {
        EnvironmentList(state, runtime, onDelete = { deleting = it }, onStart = {
            selectedId = null
            runtime.open(it)
            environments = false
        })
    }
    deleting?.let { distribution ->
        AppDialog(
            onDismissRequest = { deleting = null },
            title = { Text(stringResource(R.string.remove_item_title, stringResource(when (distribution) { LinuxDistribution.DEBIAN -> R.string.terminal_debian; LinuxDistribution.UBUNTU -> R.string.terminal_ubuntu }))) },
            text = { Text(stringResource(R.string.terminal_delete_description)) },
            confirmButton = { TextButton(onClick = { deleting = null; runtime.delete(distribution) }) { Text(stringResource(R.string.action_delete), style = MaterialTheme.typography.labelMedium, color = Danger) } },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text(stringResource(R.string.action_cancel), style = MaterialTheme.typography.labelMedium) } },
        )
    }
    closing?.let { current ->
        AppDialog(
            onDismissRequest = { closing = null },
            title = { Text(stringResource(R.string.terminal_end_session)) },
            text = { Text(stringResource(R.string.terminal_end_description)) },
            confirmButton = { TextButton(onClick = { closing = null; runtime.close(current) }) { Text(stringResource(R.string.files_close)) } },
            dismissButton = { TextButton(onClick = { closing = null }) { Text(stringResource(R.string.action_cancel), style = MaterialTheme.typography.labelMedium) } },
        )
    }
}


@Composable
private fun ColumnScope.TerminalSessions(
    state: TerminalWorkspaceState,
    runtime: TerminalWorkspace,
    session: TerminalSession?,
    selectedSession: androidx.compose.runtime.MutableState<String?>,
    terminalView: androidx.compose.runtime.MutableState<TerminalView?>,
    onDelete: (LinuxDistribution) -> Unit,
) {
    val context = LocalContext.current
    var selectedId by selectedSession
    var view by terminalView
    if (session == null) {
        EnvironmentList(state, runtime, onDelete = onDelete, onStart = { runtime.open(it) }, modifier = Modifier.weight(1f))
    } else {
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())) {
            state.sessions.forEachIndexed { index, item ->
                Text(
                    stringResource(R.string.terminal_session_label, stringResource(when (item.distribution) { LinuxDistribution.DEBIAN -> R.string.terminal_debian; LinuxDistribution.UBUNTU -> R.string.terminal_ubuntu }), index + 1),
                    style = MaterialTheme.typography.labelLarge,
                    color = if (item == session) Accent else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.background(if (item == session) SlateRaised else Ink)
                        .clickable(role = Role.Tab) { selectedId = item.id }
                        .heightIn(min = 48.dp).padding(horizontal = 16.dp, vertical = 14.dp),
                )
            }
        }
        key(session.id) {
            val exitCode by session.exitCode.collectAsState()
            val label = stringResource(R.string.terminal_input)
            val outputLimit = stringResource(R.string.terminal_output_limit)
            val language = LocalConfiguration.current.locales[0].toLanguageTag()
            val density = LocalDensity.current
            val fontSize = 12f * density.fontScale
            val foreground = MaterialTheme.colorScheme.onBackground.toArgb()
            AndroidView(
                factory = { ctx ->
                    TerminalView(ctx, session, runtime).also { terminal ->
                        view = terminal
                        terminal.onError = runtime::reportError
                        terminal.onSelection = { selected ->
                            if (selected.isNotEmpty()) context.getSystemService(ClipboardManager::class.java)
                                .setPrimaryClip(ClipData.newPlainText(label, selected))
                        }
                    }
                },
                modifier = Modifier.weight(1f).fillMaxWidth(),
                update = { it.appearance(Ink.toArgb(), foreground, Accent.toArgb(), fontSize, label, outputLimit, language) },
                onRelease = { it.dispose(); if (view === it) view = null },
            )
            exitCode?.let { code ->
                Text(stringResource(R.string.terminal_exited, code), color = if (code == 0) Accent else Danger,
                    modifier = Modifier.padding(12.dp), style = MaterialTheme.typography.bodySmall)
            }
            TerminalKeys(session, enabled = exitCode == null, onPaste = { view?.pasteFromClipboard() }, onToggleKeyboard = { view?.showKeyboard() })
        }
    }
}

@Composable
private fun EnvironmentList(
    state: TerminalWorkspaceState,
    runtime: TerminalWorkspace,
    onDelete: (LinuxDistribution) -> Unit,
    onStart: (LinuxDistribution) -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    LazyColumn(modifier.fillMaxWidth()) {
        item {
            Column(Modifier.padding(horizontal = 20.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(stringResource(R.string.terminal_workspace_title), style = MaterialTheme.typography.titleLarge, color = Accent)
                Text(stringResource(R.string.terminal_description), style = MaterialTheme.typography.bodyLarge)
                Text(stringResource(R.string.terminal_trust), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        items(LinuxDistribution.entries) { distribution ->
            Column(Modifier.padding(horizontal = 20.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(stringResource(when (distribution) { LinuxDistribution.DEBIAN -> R.string.terminal_debian; LinuxDistribution.UBUNTU -> R.string.terminal_ubuntu }), style = MaterialTheme.typography.titleMedium)
                val download = state.download?.takeIf { it.distribution == distribution }
                when {
                    download != null -> {
                        Text(stringResource(if (download.extracting) R.string.terminal_extracting else R.string.terminal_downloading,
                            (download.fraction * 100).toInt()), style = MaterialTheme.typography.bodyMedium)
                        LinearProgressIndicator(progress = { download.fraction }, modifier = Modifier.fillMaxWidth())
                        TextButton(onClick = runtime::cancelInstall) { Text(stringResource(R.string.action_cancel), style = MaterialTheme.typography.labelMedium) }
                    }
                    distribution in state.installed -> Row {
                        TextButton(onClick = { onStart(distribution) }, enabled = !state.busy) { Text(stringResource(R.string.terminal_open)) }
                        Spacer(Modifier.width(16.dp))
                        TextButton(onClick = { onDelete(distribution) }, enabled = !state.busy && state.download == null) {
                            Text(stringResource(R.string.action_delete), style = MaterialTheme.typography.labelMedium, color = Danger)
                        }
                    }
                    else -> TextButton(onClick = { runtime.install(distribution) }, enabled = state.download == null && !state.busy) {
                        Text(stringResource(R.string.vision_download_recommended, Formatter.formatShortFileSize(context, distribution.bytes)))
                    }
                }
                HorizontalDivider(color = Hairline)
            }
        }
        item {
            Text(stringResource(R.string.terminal_workspace_hint), modifier = Modifier.padding(24.dp),
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun TerminalKeys(
    session: TerminalSession,
    enabled: Boolean,
    onPaste: () -> Unit = {},
    onToggleKeyboard: () -> Unit = {},
) {
    var control by remember { mutableStateOf(false) }
    // Ctrl is consumed by the next actual terminal input, including hardware/IME input.
    DisposableEffect(session, control) {
        session.controlNext = control
        session.onControlConsumed = { control = false }
        onDispose { session.controlNext = false; session.onControlConsumed = {} }
    }
    HorizontalDivider(color = Hairline)
    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.SpaceEvenly) {
        val keys = listOf(
            stringResource(R.string.terminal_key_escape) to "\u001b",
            stringResource(R.string.terminal_key_tab) to "\t",
            stringResource(R.string.terminal_key_control) to "",
            stringResource(R.string.terminal_paste) to "ACTION_PASTE",
            stringResource(R.string.terminal_toggle_keyboard) to "ACTION_KEYBOARD",
            "←" to "\u001b[D",
            "↓" to "\u001b[B",
            "↑" to "\u001b[A",
            "→" to "\u001b[C",
        )
        keys.forEach { (label, sequence) ->
            val description = when (label) {
                "←" -> stringResource(R.string.terminal_move_left)
                "↓" -> stringResource(R.string.terminal_move_down)
                "↑" -> stringResource(R.string.terminal_move_up)
                "→" -> stringResource(R.string.terminal_move_right)
                else -> label
            }
            TextButton(
                modifier = Modifier.semantics { contentDescription = description },
                onClick = {
                    when (sequence) {
                        "" -> control = !control
                        "ACTION_PASTE" -> onPaste()
                        "ACTION_KEYBOARD" -> onToggleKeyboard()
                        else -> session.write(sequence)
                    }
                },
                enabled = enabled,
            ) {
                when (sequence) {
                    "\u001b[D", "\u001b[B", "\u001b[A", "\u001b[C" -> Icon(
                        painter = painterResource(when (sequence) {
                            "\u001b[D" -> R.drawable.ic_left
                            "\u001b[B" -> R.drawable.ic_down
                            "\u001b[A" -> R.drawable.ic_up
                            else -> R.drawable.ic_right
                        }),
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(22.dp),
                    )
                    "ACTION_PASTE" -> Icon(
                        painter = painterResource(R.drawable.ic_paste),
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(20.dp),
                    )
                    "ACTION_KEYBOARD" -> Icon(
                        painter = painterResource(R.drawable.ic_keyboard),
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(20.dp),
                    )
                    else -> Text(label, color = if (sequence.isEmpty() && control) Accent else MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

/** Only bundled terminal assets may load. Process output is terminal data, never HTML or JavaScript. */
@SuppressLint("SetJavaScriptEnabled", "ViewConstructor") // Created by Compose with a live session, never inflated from XML.
class TerminalView(context: Context, private val session: TerminalSession, private val workspace: TerminalWorkspace) : WebView(context) {
    private var ready = false
    private var disposed = false
    private var configuration: JsonObject? = null
    var onSelection: (String) -> Unit = {}
    var onError: (String) -> Unit = {}

    init {
        // Chromium forces a zero document height for WRAP_CONTENT, even with Compose's fixed bounds.
        layoutParams = ViewLayoutParams(MATCH_PARENT, MATCH_PARENT)
        setBackgroundColor(android.graphics.Color.TRANSPARENT)
        settings.javaScriptEnabled = true
        settings.allowFileAccess = false
        settings.allowContentAccess = false
        settings.blockNetworkLoads = true
        settings.domStorageEnabled = false
        isFocusableInTouchMode = true
        addJavascriptInterface(Bridge(), "FancyTerminal")
        webChromeClient = object : WebChromeClient() {
            override fun onConsoleMessage(message: ConsoleMessage): Boolean {
                if (message.messageLevel() == ConsoleMessage.MessageLevel.ERROR) {
                    val detail = "${message.sourceId()}:${message.lineNumber()}: ${message.message()}"
                    Log.e("FancyTerminal", detail)
                    onError(detail)
                }
                return true
            }
        }
        webViewClient = object : WebViewClient() {
            override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
                val detail = "${request.url}: ${error.description} (${error.errorCode})"
                Log.e("FancyTerminal", detail)
                onError(detail)
            }
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean = true
            override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse {
                val name = request.url.path?.removePrefix("/")
                val type = when (name) {
                    "index.html" -> "text/html"
                    "xterm.js", "addon-fit.js", "terminal.js" -> "text/javascript"
                    "xterm.css", "terminal.css" -> "text/css"
                    else -> null
                }
                if (request.url.scheme != "https" || request.url.host != HOST || type == null) {
                    return WebResourceResponse("text/plain", "UTF-8", 403, "Forbidden", emptyMap(), ByteArrayInputStream(byteArrayOf()))
                }
                return WebResourceResponse(type, "UTF-8", context.assets.open("terminal/$name"))
            }
        }
        loadUrl("https://$HOST/index.html")
    }

    fun appearance(background: Int, foreground: Int, accent: Int, fontSize: Float, label: String, outputLimit: String, language: String) {
        val config = buildJsonObject {
            put("background", "#%06x".format(background.and(0xffffff)))
            put("foreground", "#%06x".format(foreground.and(0xffffff)))
            put("cursor", "#%06x".format(accent.and(0xffffff)))
            put("fontSize", fontSize)
            put("fontScale", workspace.fontScale)
            put("label", label)
            put("outputLimit", outputLimit)
            put("language", language)
        }
        configuration = config
        if (ready) evaluateJavascript("window.configureTerminal?.($config)", null)
    }

    fun copySelection() {
        evaluateJavascript("window.selectedTerminalText?.()") { encoded ->
            val text = (Json.parseToJsonElement(encoded) as? JsonPrimitive)?.contentOrNull.orEmpty()
            onSelection(text)
        }
    }

    fun pasteFromClipboard() {
        val clipboard = context.getSystemService(ClipboardManager::class.java)
        val text = clipboard.primaryClip?.getItemAt(0)?.coerceToText(context)?.toString()
        if (!text.isNullOrEmpty()) {
            evaluateJavascript("window.pasteTerminal?.(${JsonPrimitive(text)})", null)
        }
    }

    fun showKeyboard() {
        requestFocus()
        evaluateJavascript("window.focusTerminalInput?.()", null)
        val imm = context.getSystemService(android.view.inputmethod.InputMethodManager::class.java)
        imm?.showSoftInput(this, android.view.inputmethod.InputMethodManager.SHOW_IMPLICIT)
        windowInsetsController?.show(AndroidWindowInsets.Type.ime())
    }

    fun dispose() {
        disposed = true
        session.detach()
        removeJavascriptInterface("FancyTerminal")
        stopLoading()
        destroy()
    }

    @Suppress("unused") // Called by the bundled terminal.js through @JavascriptInterface.
    inner class Bridge {
        @JavascriptInterface fun ready() {
            post {
                if (!disposed && !ready) {
                    ready = true
                    configuration?.let { evaluateJavascript("window.configureTerminal?.($it)", null) }
                    session.attach { bytes ->
                        val data = Base64.encodeToString(bytes, Base64.NO_WRAP)
                        evaluateJavascript("window.receiveTerminal('$data')", null)
                    }
                }
            }
        }
        @JavascriptInterface fun input(text: String) { post { if (!disposed) session.write(text) } }
        @JavascriptInterface fun fontScale(scale: Float) {
            post {
                if (!disposed && scale.isFinite()) {
                    val value = scale.coerceIn(0.5f, 2.5f)
                    workspace.fontScale = value
                    configuration = configuration?.let { JsonObject(it + ("fontScale" to JsonPrimitive(value))) }
                }
            }
        }
        @JavascriptInterface fun showKeyboard() {
            post {
                if (!disposed) {
                    requestFocus()
                    windowInsetsController?.show(AndroidWindowInsets.Type.ime())
                }
            }
        }
        @JavascriptInterface fun resize(columns: Int, rows: Int) { post { if (!disposed) session.resize(columns, rows) } }
    }

    private companion object { const val HOST = "terminal.fancyai.invalid" }
}
