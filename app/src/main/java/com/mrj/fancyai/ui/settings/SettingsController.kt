package com.mrj.fancyai.ui.settings

import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.SystemClock
import android.provider.OpenableColumns
import android.util.Log
import androidx.annotation.StringRes
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.FileProvider
import androidx.core.content.edit
import com.mrj.fancyai.BuildConfig
import com.mrj.fancyai.R
import com.mrj.fancyai.engine.LocalLlmModel
import com.mrj.fancyai.util.AppLog
import com.mrj.fancyai.util.exportDocument
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.time.Instant
import java.util.UUID
import kotlin.math.abs

internal data class SystemPrompt(
    val title: String,
    val instruction: String,
    val templateId: String? = null,
)

internal fun builtInSystemPrompts(context: Context): List<SystemPrompt> = listOf(
    SystemPrompt(
        title = context.getString(R.string.instructions_template_text),
        instruction = context.getString(R.string.instructions_system_text_default),
        templateId = "text_reply",
    ),
    SystemPrompt(
        title = context.getString(R.string.instructions_template_images),
        instruction = context.getString(R.string.instructions_system_default),
        templateId = "image_reply",
    ),
    SystemPrompt(
        title = context.getString(R.string.instructions_template_terminal),
        instruction = context.getString(R.string.instructions_system_terminal_default),
        templateId = "terminal_reply",
    ),
)

internal fun activeSystemPrompt(context: Context): SystemPrompt = readPrompts(context).let { prompts ->
    prompts[readSelectedPrompt(context, prompts.size)]
}

internal fun readAssistantInstruction(context: Context): String =
    context.getSharedPreferences("assistant_protocol", Context.MODE_PRIVATE).getString("instruction", "").orEmpty()

internal fun saveAssistantInstruction(context: Context, instruction: String) {
    context.getSharedPreferences("assistant_protocol", Context.MODE_PRIVATE).edit { putString("instruction", instruction) }
}

internal fun readPrompts(context: Context): List<SystemPrompt> {
    val preferences = context.getSharedPreferences(PROMPT_PREFERENCES, Context.MODE_PRIVATE)
    val count = preferences.getInt(KEY_PROMPT_COUNT, 0)
    val saved = List(count) { index ->
        SystemPrompt(
            title = preferences.getString("title_$index", "").orEmpty(),
            instruction = preferences.getString("instruction_$index", "").orEmpty(),
            templateId = preferences.getString("template_$index", null),
        )
    }.toMutableList()
    if (count == 0) {
        val drafts = context.getSharedPreferences(PROMPT_DRAFT_PREFERENCES, Context.MODE_PRIVATE)
        if (drafts.contains("title_0") || drafts.contains("instruction_0")) {
            saved += SystemPrompt(
                title = context.getString(R.string.instructions_default_title, 1),
                instruction = context.getString(R.string.instructions_system_default),
            )
        }
    }
    return saved + builtInSystemPrompts(context).filter { template ->
        saved.none { it.templateId == template.templateId }
    }
}

internal fun readSelectedPrompt(context: Context, count: Int): Int =
    context.getSharedPreferences(PROMPT_PREFERENCES, Context.MODE_PRIVATE)
        .getInt(KEY_SELECTED_PROMPT, 0)
        .coerceIn(0, count - 1)

internal fun saveSelectedPrompt(context: Context, selected: Int) {
    context.getSharedPreferences(PROMPT_PREFERENCES, Context.MODE_PRIVATE).edit {
        putInt(KEY_SELECTED_PROMPT, selected)
    }
}

internal fun savePrompts(context: Context, prompts: List<SystemPrompt>, selected: Int) {
    context.getSharedPreferences(PROMPT_PREFERENCES, Context.MODE_PRIVATE).edit {
        clear()
        putInt(KEY_PROMPT_COUNT, prompts.size)
        putInt(KEY_SELECTED_PROMPT, selected)
        prompts.forEachIndexed { index, prompt ->
            putString("title_$index", prompt.title)
            putString("instruction_$index", prompt.instruction)
            prompt.templateId?.let { putString("template_$index", it) }
        }
    }
}

internal fun readPromptDraft(context: Context, prompts: List<SystemPrompt>): List<SystemPrompt> {
    val preferences = context.getSharedPreferences(PROMPT_DRAFT_PREFERENCES, Context.MODE_PRIVATE)
    return prompts.mapIndexed { index, prompt ->
        prompt.copy(
            title = preferences.getString("title_$index", prompt.title).orEmpty(),
            instruction = preferences.getString("instruction_$index", prompt.instruction).orEmpty(),
        )
    }
}

internal fun savePromptDraft(context: Context, selected: Int, prompt: SystemPrompt) {
    context.getSharedPreferences(PROMPT_DRAFT_PREFERENCES, Context.MODE_PRIVATE).edit {
        putString("title_$selected", prompt.title)
        putString("instruction_$selected", prompt.instruction)
    }
}

internal fun clearPromptDraft(context: Context, selected: Int? = null) {
    context.getSharedPreferences(PROMPT_DRAFT_PREFERENCES, Context.MODE_PRIVATE).edit {
        if (selected == null) clear() else {
            remove("title_$selected")
            remove("instruction_$selected")
        }
    }
}

private const val PROMPT_PREFERENCES = "system_prompts"
private const val PROMPT_DRAFT_PREFERENCES = "system_prompt_draft"
private const val KEY_PROMPT_COUNT = "prompt_count"
private const val KEY_SELECTED_PROMPT = "selected_prompt"

internal fun documentInfo(context: Context, uri: Uri, fallback: String = uri.lastPathSegment.orEmpty()): ImportDocument {
    var name = fallback
    var size = -1L
    context.contentResolver.query(
        uri,
        arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE),
        null,
        null,
        null,
    )?.use { cursor ->
        if (cursor.moveToFirst()) {
            val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            val sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE)
            if (nameIndex >= 0) name = cursor.getString(nameIndex) ?: name
            if ((sizeIndex >= 0) && !cursor.isNull(sizeIndex)) size = cursor.getLong(sizeIndex)
        }
    }
    return ImportDocument(name, size)
}

internal data class ImportDocument(val name: String, val size: Long)

internal const val DEFAULT_CUSTOM_OFFLOAD_LAYERS = 16

internal enum class DiagnosticReport(@param:StringRes val title: Int, @param:StringRes val summary: Int, val filename: String) {
    APP_LOG(R.string.diagnostics_app_log, R.string.diagnostics_app_log_summary, "FancyAI-App-log.txt"),
    LAST_CRASH(R.string.diagnostics_last_crash, R.string.diagnostics_last_crash_summary, "FancyAI-Last-crash.txt"),
    DEVICE(R.string.diagnostics_device, R.string.diagnostics_device_summary, "FancyAI-Device-diagnostics.txt"),
}

internal fun diagnosticReport(context: Context, report: DiagnosticReport): String {
    val folder = File(context.filesDir, "diagnostics")
    return when (report) {
        DiagnosticReport.APP_LOG -> {
            val files = folder.listFiles().orEmpty().filter { it.extension == "log" }
                .sortedBy(File::lastModified)
            files.joinToString("\n") { "--- ${it.name} ---\n${it.readText()}" }
                .ifBlank { context.getString(R.string.diagnostics_no_logs) }
        }
        DiagnosticReport.LAST_CRASH -> {
            val files = folder.listFiles().orEmpty()
            val deletedBefore = context.getSharedPreferences("diagnostics", Context.MODE_PRIVATE)
                .getLong("crash_deleted_before", 0)
            val javaCrash = files
                .filter { it.name.startsWith("Last-crash-") && it.extension == "txt" }
                .filter { it.lastModified() > deletedBefore }
                .maxByOrNull(File::lastModified)
            val exits = context.getSystemService(ActivityManager::class.java)
                .getHistoricalProcessExitReasons(context.packageName, 0, 32)
            val crash = exits.filter { it.timestamp > deletedBefore }.filter {
                it.reason in setOf(
                    ApplicationExitInfo.REASON_CRASH,
                    ApplicationExitInfo.REASON_CRASH_NATIVE,
                    ApplicationExitInfo.REASON_ANR,
                )
            }.maxByOrNull { it.timestamp }
            when {
                crash == null -> javaCrash?.readText() ?: context.getString(R.string.diagnostics_no_crash)
                javaCrash != null && javaCrash.lastModified() > crash.timestamp -> javaCrash.readText()
                else -> buildString {
                    appendLine("timestamp=${Instant.ofEpochMilli(crash.timestamp)}")
                    appendLine("process=${crash.processName} pid=${crash.pid}")
                    appendLine("reason=${when (crash.reason) {
                        ApplicationExitInfo.REASON_CRASH_NATIVE -> "NATIVE_CRASH"
                        ApplicationExitInfo.REASON_ANR -> "ANR"
                        else -> "JAVA_CRASH"
                    }} status=${crash.status}")
                    appendLine("pssKiB=${crash.pss} rssKiB=${crash.rss}")
                    javaCrash?.takeIf {
                        it.name == "Last-crash-${crash.processName.substringAfter(':', "main")}.txt" &&
                            crash.reason == ApplicationExitInfo.REASON_CRASH &&
                            abs(it.lastModified() - crash.timestamp) < 10_000L
                    }?.let { appendLine(it.readText()) }
                }
            }
        }
        DiagnosticReport.DEVICE -> {
            val manager = context.getSystemService(ActivityManager::class.java)
            val memory = ActivityManager.MemoryInfo().also(manager::getMemoryInfo)
            val settings = LlmSettingsStore.snapshot(context)
            buildString {
                appendLine("timestamp=${Instant.now()}")
                appendLine("app=${BuildConfig.VERSION_NAME} code=${BuildConfig.VERSION_CODE} package=${context.packageName}")
                appendLine("device=${Build.MANUFACTURER} ${Build.MODEL}")
                appendLine("android=${Build.VERSION.RELEASE} sdk=${Build.VERSION.SDK_INT} abi=${Build.SUPPORTED_ABIS.joinToString()}")
                appendLine("totalMemoryMiB=${memory.totalMem / 1048576} availableMemoryMiB=${memory.availMem / 1048576} lowMemory=${memory.lowMemory}")
                appendLine("engineConfigured=${settings != null}")
                settings?.sessionConfig(systemInstruction = "")?.let { config ->
                    appendLine("runtime=${config.runtime} provider=${config.cloudProvider}")
                    appendLine("contextTokens=${config.contextTokens} maxOutputTokens=${config.maxOutputTokens} cpuThreads=${config.cpuThreads}")
                    appendLine("mmap=${config.useMmap} llamaBackend=${config.llamaBackend} liteRtBackend=${config.liteRtBackend}")
                }
            }
        }
    }
}

internal class DiagnosticsController(
    private val context: Context,
    private val scope: CoroutineScope,
    private val showMessage: suspend (Int) -> Unit,
) {
    var text by mutableStateOf<String?>(null)
    var exporting by mutableStateOf(false)
    var logging by mutableStateOf(AppLog.enabled)
    var deleted by mutableStateOf(false)

    suspend fun load(report: DiagnosticReport?) {
        text = null
        deleted = false
        if (report == null) return
        text = withContext(Dispatchers.IO) { diagnosticReport(context, report) }
    }

    fun save(uri: Uri) {
        scope.launch {
            exporting = true
            try {
                val content = checkNotNull(text)
                withContext(Dispatchers.IO) { exportDocument(context, uri) { it.write(content.toByteArray(Charsets.UTF_8)) } }
                showMessage(R.string.diagnostics_saved)
            } finally {
                exporting = false
            }
        }
    }

    fun share(report: DiagnosticReport?, content: String?) {
        scope.launch {
            exporting = true
            try {
                val file = withContext(Dispatchers.IO) {
                    val root = File(context.cacheDir, "diagnostic_exports").apply { mkdirs() }
                    root.listFiles().orEmpty().filter { System.currentTimeMillis() - it.lastModified() > 86_400_000L }
                        .forEach { it.deleteRecursively() }
                    val folder = File(root, UUID.randomUUID().toString()).apply { mkdirs() }
                    File(folder, checkNotNull(report).filename).apply { writeText(checkNotNull(content)) }
                }
                val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
                val intent = Intent(Intent.ACTION_SEND).setType("text/plain")
                    .putExtra(Intent.EXTRA_STREAM, uri)
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    .apply { clipData = ClipData.newRawUri("", uri) }
                context.startActivity(Intent.createChooser(intent, context.getString(R.string.action_share)))
            } finally {
                exporting = false
            }
        }
    }

    fun delete(report: DiagnosticReport) {
        scope.launch {
            exporting = true
            try {
                withContext(Dispatchers.IO) {
                    when (report) {
                        DiagnosticReport.APP_LOG -> AppLog.deleteReports(context, crashes = false)
                        DiagnosticReport.LAST_CRASH -> {
                            AppLog.deleteReports(context, crashes = true)
                            context.getSharedPreferences("diagnostics", Context.MODE_PRIVATE).edit(commit = true) {
                                putLong("crash_deleted_before", System.currentTimeMillis())
                            }
                        }
                        DiagnosticReport.DEVICE -> Unit
                    }
                    File(context.cacheDir, "diagnostic_exports").walkBottomUp().forEach { file ->
                        if (file.isFile && file.name == report.filename) file.delete()
                        if (file.isDirectory && file.listFiles()?.isEmpty() == true) file.delete()
                    }
                }
                text = null
                deleted = true
            } finally {
                exporting = false
            }
        }
    }

    fun setLoggingEnabled(value: Boolean) {
        scope.launch {
            exporting = true
            try {
                withContext(Dispatchers.IO) { AppLog.setEnabled(context, value) }
                logging = AppLog.enabled
            } finally {
                exporting = false
            }
        }
    }
}

internal class EnginePreferences(val context: Context) {
    var liteRtBackend by mutableStateOf(LlmSettingsStore.liteRtBackend(context))
    var llamaBackend by mutableStateOf(LlmSettingsStore.llamaBackend(context))
    var llamaOffloadLayers by mutableIntStateOf(LlmSettingsStore.llamaOffloadLayers(context))
    var speculativeDecoding by mutableStateOf(LlmSettingsStore.speculativeDecoding(context))
    var contextTokens by mutableIntStateOf(LlmSettingsStore.contextTokens(context))
    var cpuThreads by mutableIntStateOf(LlmSettingsStore.cpuThreads(context))
    var promptThreads by mutableIntStateOf(LlmSettingsStore.promptThreads(context))
    var batchTokens by mutableIntStateOf(LlmSettingsStore.batchTokens(context))
    var microBatchTokens by mutableIntStateOf(LlmSettingsStore.microBatchTokens(context))
    var flashAttention by mutableStateOf(LlmSettingsStore.flashAttention(context))
    var quantizedKvCache by mutableStateOf(LlmSettingsStore.quantizedKvCache(context))
    var cacheTypeK by mutableStateOf(LlmSettingsStore.cacheType(context, key = true))
    var cacheTypeV by mutableStateOf(LlmSettingsStore.cacheType(context, key = false))
    var cpuRepack by mutableStateOf(LlmSettingsStore.cpuRepack(context))
    var useMmap by mutableStateOf(LlmSettingsStore.useMmap(context))
}

internal class EngineSettingsController(val context: Context, private val scope: CoroutineScope) {
    private val modelStore = LlmSettingsStore.localModels(context)
    val liteRtGreedySampling = LlmSettingsStore.generation(context, GenerationTarget.LITERT).topK == 1
    var preferences by mutableStateOf(EnginePreferences(context))
    var models by mutableStateOf(emptyList<LocalLlmModel>())
    var selectedPath by mutableStateOf(LlmSettingsStore.selectedModelPath(context))
    var localActive by mutableStateOf(LlmSettingsStore.activeCloudProvider(context) == null)
    var refresh by mutableIntStateOf(0)
    var errorMessage by mutableIntStateOf(0)
    var pendingRemoval by mutableStateOf<LocalLlmModel?>(null)
    var importingName by mutableStateOf<String?>(null)
    var importProgress by mutableStateOf<Float?>(null)
    val selectedModel get() = models.firstOrNull { it.path == selectedPath }

    suspend fun loadModels(onSelectionChanged: () -> Unit) {
        val started = SystemClock.elapsedRealtime()
        withContext(Dispatchers.IO) { runCatching(modelStore::installed) }.onSuccess { installed ->
            Log.i("Engines", "loadModels scanMs=${SystemClock.elapsedRealtime() - started} models=${installed.size}")
            models = installed
            val nextSelection = selectedPath?.takeIf { path -> installed.any { it.path == path } } ?: installed.firstOrNull()?.path
            if (selectedPath != nextSelection) {
                selectedPath = nextSelection
                LlmSettingsStore.selectModel(
                    context,
                    nextSelection,
                    activate = LlmSettingsStore.activeCloudProvider(context) == null,
                )
                onSelectionChanged()
            }
            localActive = LlmSettingsStore.activeCloudProvider(context) == null
            errorMessage = 0
        }.onFailure {
            errorMessage = R.string.engines_read_failed
        }
    }

    private var activeJob: Job? = null

    fun importModel(uri: Uri, onSelectionChanged: () -> Unit) {
        activeJob = scope.launch {
            importingName = ""
            try {
                selectedPath = LlmSettingsStore.importModel(context, uri) { name, progress ->
                    scope.launch { importingName = name; importProgress = progress }
                }.path
                localActive = true
                refresh++
                onSelectionChanged()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                errorMessage = R.string.engines_import_failed
            } finally {
                importingName = null
                importProgress = null
                activeJob = null
            }
        }
    }

    fun downloadStarter(model: StarterLlmModel, onSelectionChanged: () -> Unit) {
        if (importingName != null) return
        activeJob = scope.launch {
            importingName = model.name
            importProgress = 0f
            try {
                selectedPath = LlmSettingsStore.downloadStarterModel(context, model) { name, progress ->
                    scope.launch { importingName = name; importProgress = progress }
                }.path
                localActive = true
                refresh++
                onSelectionChanged()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                errorMessage = R.string.engines_import_failed
            } finally {
                importingName = null
                importProgress = null
                activeJob = null
            }
        }
    }

    fun stopDownload() {
        activeJob?.cancel()
        activeJob = null
        importingName = null
        importProgress = null
    }

    fun remove(model: LocalLlmModel) {
        scope.launch {
            withContext(Dispatchers.IO) { runCatching { modelStore.remove(model) } }.onSuccess {
                refresh++
            }.onFailure {
                errorMessage = R.string.engines_remove_failed
            }
        }
    }
}
