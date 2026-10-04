package com.mrj.fancyai.ui.benchmark

import android.content.Context
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.mrj.fancyai.R
import com.mrj.fancyai.engine.LocalLlmModel
import com.mrj.fancyai.ui.kit.AppDialog
import com.mrj.fancyai.ui.settings.LlmSettingsStore
import com.mrj.fancyai.ui.theme.Accent
import com.mrj.fancyai.ui.theme.Danger
import com.mrj.fancyai.ui.theme.Ink
import com.mrj.fancyai.util.AppLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.DateFormat
import java.util.Date

internal class BenchmarkSession(
    private val app: Context,
    private val scope: kotlinx.coroutines.CoroutineScope,
    val store: BenchmarkStore,
    val runner: BenchmarkRunner,
    selectedPath: androidx.compose.runtime.MutableState<String?>,
    repetitions: androidx.compose.runtime.MutableIntState,
    openGroups: androidx.compose.runtime.MutableState<List<String>?>,
    expandedRun: androidx.compose.runtime.MutableState<String?>,
    comparison: androidx.compose.runtime.MutableState<List<String>>,
) {
    var selectedPath by selectedPath
    var repetitions by repetitions
    var openGroups by openGroups
    var expandedRun by expandedRun
    var comparison by comparison
    var models by mutableStateOf<List<LocalLlmModel>>(emptyList())
    var runs by mutableStateOf<List<BenchmarkRun>>(emptyList())
    var progress by mutableStateOf(BenchmarkProgress())
    private var runJob by mutableStateOf<Job?>(null)
    var loaded by mutableStateOf(false)
    var configuration by mutableStateOf<BenchmarkConfiguration?>(null)
    val model: LocalLlmModel? get() = models.find { it.path == selectedPath }
    val running: Boolean get() = runJob?.isActive == true

    suspend fun load() {
        models = store.loadModels()
        val history = store.load()
        runs = history
        if (openGroups == null) openGroups = listOfNotNull(history.firstOrNull()?.let { "${it.configuration.runtime.name}:${it.configuration.modelPath}" })
        selectedPath = selectedPath.takeIf { path -> models.any { it.path == path } }
            ?: models.firstOrNull()?.path
        selectedPath?.let(store::setSelectedModelPath)
        loaded = true
    }

    suspend fun loadConfiguration(model: LocalLlmModel?) {
        configuration = null
        if (model != null) {
            configuration = withContext(Dispatchers.IO) { benchmarkConfiguration(LlmSettingsStore.localSnapshot(app, model)) }
        }
    }

    fun stop() {
        progress = BenchmarkProgress()
        runner.activeClient?.cancel()
        runJob?.cancel()
        runJob = null
    }

    fun start() {
        val selected = model ?: return
        if (running) return
        runJob = scope.launch {
            try {
                runner.run(selected, repetitions) { progress = it }?.let { completed ->
                    expandedRun = completed.id
                    openGroups = (listOf("${completed.configuration.runtime.name}:${completed.configuration.modelPath}") + openGroups.orEmpty()).distinct()
                    runs = (listOf(completed) + runs).distinctBy(BenchmarkRun::id)
                    store.save(completed)
                }
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                throw cancelled
            } catch (failure: Throwable) {
                AppLog.write(android.util.Log.ERROR, "Benchmark", "Benchmark run failed", failure)
            } finally {
                runJob = null
            }
        }
    }

    fun remove(run: BenchmarkRun?) {
        scope.launch {
            if (run == null) store.clear() else store.delete(run.id)
            runs = runs.filterNot { run == null || it.id == run.id }
            comparison = if (run == null) emptyList() else comparison - run.id
            expandedRun = expandedRun.takeUnless { run == null || it == run.id }
            if (run == null) openGroups = emptyList()
        }
    }
}

@Composable
internal fun BenchmarkScreen(onBack: () -> Unit) {
    val app = LocalContext.current.applicationContext
    val store = remember(app) { BenchmarkStore(app) }
    val runner = remember(app) { BenchmarkRunner(app) }
    val scope = rememberCoroutineScope()
    val selectedPathState = rememberSaveable { mutableStateOf(store.selectedModelPath() ?: LlmSettingsStore.selectedModelPath(app)) }
    val repetitionsState = rememberSaveable { mutableIntStateOf(store.repetitions()) }
    val openGroupsState = rememberSaveable { mutableStateOf<List<String>?>(null) }
    val expandedRunState = rememberSaveable { mutableStateOf<String?>(null) }
    val comparisonState = rememberSaveable { mutableStateOf<List<String>>(emptyList()) }
    var sheet by rememberSaveable { mutableStateOf<BenchmarkSheet?>(null) }
    var deleteRun by remember { mutableStateOf<BenchmarkRun?>(null) }
    var clearRequested by rememberSaveable { mutableStateOf(value = false) }

    val session = remember(app, scope) {
        BenchmarkSession(app, scope, store, runner, selectedPathState, repetitionsState,
            openGroupsState, expandedRunState, comparisonState)
    }
    with(session) {
        val model = session.model
        LaunchedEffect(app) { load() }
        LaunchedEffect(model) { loadConfiguration(model) }
        val groups = remember(runs) { runs.groupBy { "${it.configuration.runtime.name}:${it.configuration.modelPath}" } }
        BackHandler { stop(); onBack() }
        DisposableEffect(runner) {
            onDispose {
                runner.activeClient?.cancel()
                runner.activeClient?.close()
            }
        }

        Box(Modifier.fillMaxSize()
            .background(Brush.verticalGradient(0f to MaterialTheme.colorScheme.surfaceContainerLow, 0.28f to Ink, 1f to MaterialTheme.colorScheme.background))
            .windowInsetsPadding(WindowInsets.safeDrawing)) {
            Box(
                Modifier
                    .align(Alignment.TopStart)
                    .width(3.dp)
                    .height(280.dp)
                    .background(Brush.verticalGradient(listOf(Accent, Color.Transparent))),
            )
            LazyColumn(
                modifier = Modifier
                    .widthIn(max = 760.dp)
                    .fillMaxSize()
                    .align(Alignment.TopCenter),
                contentPadding = PaddingValues(start = 16.dp, top = 8.dp, end = 16.dp, bottom = 56.dp),
            ) {
                item {
                    Header { stop(); onBack() }
                    SectionMark(R.string.benchmark_section_test, R.string.benchmark_setup_label)
                    SetupPanel(
                        model = model,
                        configuration = configuration,
                        repetitions = repetitions,
                        loaded = loaded,
                        running = running,
                        onModels = { sheet = BenchmarkSheet.MODELS },
                        onRepetitions = { count ->
                            repetitions = count
                            store.setRepetitions(count)
                        },
                        onRun = ::start,
                        onStop = ::stop,
                    )
                    if (running) ProgressPanel(progress)
                }
                benchmarkHistory(
                    session, groups,
                    onCompare = { sheet = BenchmarkSheet.COMPARE },
                    onClear = { clearRequested = true },
                    onDelete = { deleteRun = it },
                )
            }
        }

        when (sheet) {
            BenchmarkSheet.MODELS -> ModelPicker(
                models = models,
                selectedPath = selectedPath,
                onSelect = { selected ->
                    selectedPath = selected.path
                    store.setSelectedModelPath(selected.path)
                    sheet = null
                },
            ) { sheet = null }
            BenchmarkSheet.COMPARE -> comparison.mapNotNull { id -> runs.find { it.id == id } }.takeIf { it.size == 2 }?.let {
                ComparisonSheet(it[0], it[1]) { sheet = null }
            }
            null -> Unit
        }
        if (deleteRun != null || clearRequested) {
            HistoryDeletionDialog(deleteRun, onConfirm = ::remove, onDismiss = {
                deleteRun = null
                clearRequested = false
            })
        }
    }
}

@Composable
private fun HistoryDeletionDialog(pending: BenchmarkRun?, onConfirm: (BenchmarkRun?) -> Unit, onDismiss: () -> Unit) {
    AppDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(if (pending == null) R.string.benchmark_clear_title else R.string.benchmark_delete_title)) },
        text = {
            Text(if (pending == null) stringResource(R.string.benchmark_clear_message)
                else stringResource(R.string.benchmark_delete_message, DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(pending.createdAtEpochMilliseconds))))
        },
        confirmButton = {
            TextButton(onClick = {
                onDismiss()
                onConfirm(pending)
            }) { Text(stringResource(if (pending == null) R.string.benchmark_clear_history else R.string.benchmark_delete_run), color = Danger) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.action_cancel), style = MaterialTheme.typography.labelMedium)
            }
        },
    )
}

private enum class BenchmarkSheet { MODELS, COMPARE }
