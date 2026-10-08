package com.mrj.fancyai.ui.memory

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Icon
import androidx.compose.ui.res.painterResource
import com.mrj.fancyai.ui.kit.Artwork
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.mrj.fancyai.R
import com.mrj.fancyai.service.memory.CharacterMemory
import com.mrj.fancyai.service.memory.MemoryRecord
import com.mrj.fancyai.ui.characters.CharacterCard
import com.mrj.fancyai.ui.kit.AppDialog
import com.mrj.fancyai.ui.kit.AppHeader
import com.mrj.fancyai.ui.kit.CompactSwitchRow
import com.mrj.fancyai.ui.kit.MessageMarkdown
import com.mrj.fancyai.ui.kit.PostInput
import com.mrj.fancyai.ui.theme.Accent
import com.mrj.fancyai.ui.theme.Ink
import com.mrj.fancyai.ui.theme.SlateRaised
import com.mrj.fancyai.ui.theme.TextMuted
import com.mrj.fancyai.ui.theme.TextPrimary
import kotlinx.coroutines.launch
import kotlin.math.max
import kotlin.math.min

@Composable
internal fun MemoryLibraryScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val controller = remember(context, scope) { MemoryController(context.applicationContext, scope) }
    var characterId by rememberSaveable { mutableStateOf<String?>(null) }
    var settings by rememberSaveable { mutableStateOf(false) }
    var search by rememberSaveable { mutableStateOf("") }
    LaunchedEffect(controller) { controller.load() }
    val selected = controller.characters.firstOrNull { it.id == characterId }
    BackHandler { if (characterId != null) characterId = null else onBack() }
    Column(
        Modifier.fillMaxSize()
            .background(Brush.verticalGradient(listOf(MaterialTheme.colorScheme.surfaceContainerLow, MaterialTheme.colorScheme.background)))
            .windowInsetsPadding(WindowInsets.safeDrawing)
            .padding(horizontal = 20.dp),
    ) {
        if (selected != null) {
            CharacterMemoryPage(selected, controller.installed, controller, onBack = { characterId = null })
        } else {
            AppHeader(stringResource(R.string.memory_library_title), null, onBack)
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = { settings = false }) {
                    Text(stringResource(R.string.characters_title), color = if (!settings) Accent else TextMuted)
                }
                TextButton(onClick = { settings = true }) {
                    Text(stringResource(R.string.memory_model_settings), color = if (settings) Accent else TextMuted)
                }
            }
            HorizontalDivider()
            LazyColumn(contentPadding = PaddingValues(vertical = 24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                if (controller.loading) item { LinearProgressIndicator(Modifier.fillMaxWidth()) }
                if (!settings) {
                    item {
                        Text(stringResource(R.string.memory_archive_heading), style = MaterialTheme.typography.headlineLarge)
                        Text(stringResource(R.string.memory_library_subtitle), style = MaterialTheme.typography.bodyMedium,
                            color = TextMuted, modifier = Modifier.padding(top = 8.dp, bottom = 20.dp))
                        PostInput(search, { search = it }, hint = stringResource(R.string.characters_search_hint))
                    }
                    val visible = controller.characters.filter { it.name.contains(search, true) || it.handle.contains(search, true) }
                    if (visible.isEmpty() && !controller.loading) item {
                        Text(stringResource(R.string.memory_no_characters), color = TextMuted, modifier = Modifier.padding(vertical = 24.dp))
                    }
                    items(visible, key = CharacterCard::id) { character ->
                        Row(Modifier.fillMaxWidth().clickable(role = Role.Button) { characterId = character.id }.padding(vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically) {
                            Artwork(path = character.avatarPath, resource = character.avatarResource, contentDescription = character.name, modifier =
                                Modifier.size(56.dp).clip(RoundedCornerShape(10.dp)))
                            Column(Modifier.weight(1f).padding(horizontal = 16.dp)) {
                                Text(character.name, style = MaterialTheme.typography.titleMedium)
                                Text(stringResource(if (controller.enabled(character.id)) R.string.memory_recall_on else R.string.memory_recall_off),
                                    style = MaterialTheme.typography.labelMedium, color = TextMuted, modifier = Modifier.padding(top = 4.dp))
                            }
                            Icon(painterResource(R.drawable.ic_forward), stringResource(R.string.action_open), tint = TextMuted, modifier = Modifier.size(18.dp))
                        }
                        HorizontalDivider()
                    }
                } else item { MemorySettingsPage(controller) }
            }
        }
    }
}

@Composable
private fun MemorySettingsPage(controller: MemoryController) {
    var recallLatest by remember { mutableStateOf(controller.recallLatest) }
    var maxInjections by remember { mutableIntStateOf(controller.maxInjections) }
    var minimumScore by remember { mutableFloatStateOf(controller.minimumScore) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) controller.install(uri)
    }
    Column {
        Text(stringResource(R.string.memory_model_heading), style = MaterialTheme.typography.headlineMedium)
        Text(stringResource(R.string.memory_package_summary), style = MaterialTheme.typography.bodyMedium,
            color = TextMuted, modifier = Modifier.padding(top = 8.dp, bottom = 20.dp))
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(6.dp).background(if (controller.installed) Accent else TextMuted, RoundedCornerShape(3.dp)))
            Text(stringResource(if (controller.installed) R.string.memory_package_ready else R.string.memory_package_title),
                style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(start = 10.dp))
        }
        val progress = controller.progress
        if (progress != null) {
            LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth().padding(top = 20.dp))
            TextButton(onClick = { controller.installation?.cancel() }) { Text(stringResource(R.string.action_cancel)) }
        } else if (controller.removing) {
            LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 20.dp))
        } else if (!controller.loading) {
            Row(Modifier.fillMaxWidth().padding(top = 12.dp), horizontalArrangement = Arrangement.End) {
                if (controller.installed) {
                    TextButton(onClick = controller::removeModel) { Text(stringResource(R.string.memory_remove_model), color = MaterialTheme.colorScheme.error) }
                } else {
                    TextButton(onClick = { picker.launch(arrayOf("application/zip", "application/octet-stream")) }) { Text(stringResource(R.string.memory_import)) }
                    Button(onClick = { controller.install(null) }) { Text(stringResource(R.string.memory_install)) }
                }
            }
        }
        controller.installError?.let { failure ->
            Text(stringResource(failure), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(top = 8.dp))
        }
        Text(stringResource(R.string.memory_model_removal_note), style = MaterialTheme.typography.bodySmall, color = TextMuted)
        HorizontalDivider(Modifier.padding(vertical = 24.dp))
        Text(stringResource(R.string.memory_recall_settings), style = MaterialTheme.typography.titleLarge)
        CompactSwitchRow(title = stringResource(R.string.memory_recall_latest), summary = stringResource(R.string.memory_recall_latest_summary), checked = recallLatest, onCheckedChange = {
            recallLatest = it
            controller.recallLatest = it
        })
        Text(pluralStringResource(R.plurals.memory_max_injections, maxInjections, maxInjections), style = MaterialTheme.typography.labelLarge)
        Slider(value = maxInjections.toFloat().coerceIn(0f, 8f), onValueChange = {
            maxInjections = it.toInt()
            controller.maxInjections = maxInjections
        }, valueRange = 0f..8f, steps = 7)
        Text(stringResource(R.string.memory_minimum_score, String.format(LocalConfiguration.current.locales[0], "%.2f", minimumScore)), style = MaterialTheme.typography.labelLarge)
        Slider(value = minimumScore.coerceIn(0f, 1f), onValueChange = {
            minimumScore = it
            controller.minimumScore = it
        })
    }
}

@Composable
private fun CharacterMemoryPage(character: CharacterCard, installed: Boolean, controller: MemoryController, onBack: () -> Unit) {
    val context = LocalContext.current
    val store = remember(character.id) { CharacterMemory(context, character.id) }
    var enabled by remember(character.id) { mutableStateOf(controller.enabled(character.id)) }
    var autoCollect by remember(character.id) { mutableStateOf(controller.autoCollect(character.id)) }
    var records by remember(character.id) { mutableStateOf<List<MemoryRecord>>(emptyList()) }
    var revision by remember { mutableIntStateOf(0) }
    var loading by remember { mutableStateOf(true) }
    var editing by remember { mutableStateOf<MemoryRecord?>(null) }
    var deleting by remember { mutableStateOf<MemoryRecord?>(null) }
    var graph by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(character.id, revision, controller.revision) {
        loading = true
        try {
            records = store.records()
        } finally { loading = false }
    }
    AppHeader(character.name, stringResource(R.string.memory_character_subtitle), onBack)
    LazyColumn(contentPadding = PaddingValues(vertical = 20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        item {
            Row(Modifier.fillMaxWidth().padding(bottom = 20.dp), verticalAlignment = Alignment.CenterVertically) {
                Artwork(path = character.avatarPath, resource = character.avatarResource, contentDescription = character.name, modifier = Modifier.size(64.dp).clip(RoundedCornerShape(12.dp)))
                Column(Modifier.padding(start = 16.dp)) {
                    Text(character.handle, style = MaterialTheme.typography.labelLarge, color = TextMuted)
                    Text(pluralStringResource(R.plurals.memory_entries_count, records.size, records.size), style = MaterialTheme.typography.headlineMedium)
                }
            }
            if (!installed) Text(stringResource(R.string.memory_package_title), color = MaterialTheme.colorScheme.onSurfaceVariant)
            CompactSwitchRow(title = stringResource(R.string.memory_enabled), summary = stringResource(R.string.memory_enabled_summary), checked = enabled, enabled = installed || enabled, onCheckedChange = {
                enabled = it
                controller.setEnabled(character.id, it)
            })
            CompactSwitchRow(title = stringResource(R.string.memory_autocollect), summary = stringResource(R.string.memory_autocollect_summary), checked = autoCollect, enabled = installed || autoCollect, onCheckedChange = {
                autoCollect = it
                controller.setAutoCollect(character.id, it)
            })
        }
        if (loading) item { LinearProgressIndicator(Modifier.fillMaxWidth()) }
        item {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.memory_entries_title), style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                TextButton(onClick = { graph = !graph }) { Text(stringResource(if (graph) R.string.memory_list_view else R.string.memory_graph_title)) }
            }
        }
        if (records.isNotEmpty()) {
            item {
                TextButton(enabled = !controller.clearing, onClick = { controller.deleteAll(character.id) }) {
                    Text(stringResource(R.string.memory_clear_character), color = MaterialTheme.colorScheme.error)
                }
                if (controller.clearing) LinearProgressIndicator(Modifier.fillMaxWidth())
            }
            if (graph) item {
                MemoryGraph(records, onSelect = { editing = it }, modifier = Modifier.fillMaxWidth().height(400.dp))
            } else items(records, key = { it.id }) { record ->
                Column(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceContainerLow, RoundedCornerShape(8.dp)).padding(18.dp)) {
                    Text(java.text.DateFormat.getDateTimeInstance(java.text.DateFormat.MEDIUM, java.text.DateFormat.SHORT).format(java.util.Date(record.maxTimestamp)), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    MessageMarkdown(record.summary, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface, modifier = Modifier.padding(top = 12.dp))
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                        TextButton(onClick = { editing = record }) { Text(stringResource(R.string.action_edit)) }
                        TextButton(onClick = { deleting = record }) { Text(stringResource(R.string.action_delete), color = MaterialTheme.colorScheme.error) }
                    }
                    HorizontalDivider()
                }
            }
        } else if (!loading) item { Text(stringResource(R.string.memory_empty), color = MaterialTheme.colorScheme.onSurfaceVariant) }
    }
    editing?.let { record ->
        MemoryEditor(character.id, record, controller, store, onDismiss = { editing = null }, onSaved = { editing = null; revision++ })
    }
    deleting?.let { record ->
        AppDialog(
            onDismissRequest = { if (!controller.clearing) deleting = null },
            title = { Text(stringResource(R.string.memory_delete_title)) },
            text = { Text(stringResource(R.string.memory_delete_summary)) },
            confirmButton = {
                TextButton(enabled = !controller.clearing, onClick = {
                    controller.delete(character.id, record).invokeOnCompletion { deleting = null }
                }) { Text(stringResource(R.string.action_delete)) }
            },
            dismissButton = { TextButton(enabled = !controller.clearing, onClick = { deleting = null }) { Text(stringResource(R.string.action_cancel)) } },
        )
    }
}


@Composable
private fun MemoryEditor(characterId: String, record: MemoryRecord, controller: MemoryController, store: CharacterMemory, onDismiss: () -> Unit, onSaved: () -> Unit) {
    val key = "$characterId:${record.id}"
    val saved = remember(key) { controller.draft(characterId, record) }
    var summary by remember(key) { mutableStateOf(saved.summary) }
    var raw by remember(key) { mutableStateOf(saved.rawText) }
    var relationships by remember(key) { mutableStateOf(saved.relationships) }
    var saving by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val dismiss = { if (!saving) onDismiss() }
    Dialog(onDismissRequest = dismiss, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).windowInsetsPadding(WindowInsets.safeDrawing).imePadding().padding(horizontal = 20.dp)) {
            AppHeader(stringResource(R.string.memory_edit_title), null, dismiss)
            LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(vertical = 20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                item {
                    PostInput(value = summary, keyboardOptions = KeyboardOptions.Default, onValueChange = { summary = it; controller.saveDraft(characterId, record, summary, raw, relationships) }, enabled = !saving, label = stringResource(R.string.memory_summary), modifier = Modifier.fillMaxWidth(), minLines = 3)
                }
                item {
                    PostInput(value = raw, keyboardOptions = KeyboardOptions.Default, onValueChange = { raw = it; controller.saveDraft(characterId, record, summary, raw, relationships) }, enabled = !saving, label = stringResource(R.string.memory_raw_text), modifier = Modifier.fillMaxWidth(), minLines = 4)
                }
                item { Text(stringResource(R.string.memory_graph_title), style = MaterialTheme.typography.titleMedium) }
                itemsIndexed(relationships) { index, relation ->
                    Column(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceContainer, MaterialTheme.shapes.medium).padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        PostInput(value = relation.subject, keyboardOptions = KeyboardOptions.Default, onValueChange = { value -> relationships = relationships.toMutableList().also { it[index] = relation.copy(subject = value) }; controller.saveDraft(characterId, record, summary, raw, relationships) }, enabled = !saving, label = stringResource(R.string.memory_subject), modifier = Modifier.fillMaxWidth())
                        PostInput(value = relation.relationship, keyboardOptions = KeyboardOptions.Default, onValueChange = { value -> relationships = relationships.toMutableList().also { it[index] = relation.copy(relationship = value) }; controller.saveDraft(characterId, record, summary, raw, relationships) }, enabled = !saving, label = stringResource(R.string.memory_relation), modifier = Modifier.fillMaxWidth())
                        PostInput(value = relation.target, keyboardOptions = KeyboardOptions.Default, onValueChange = { value -> relationships = relationships.toMutableList().also { it[index] = relation.copy(target = value) }; controller.saveDraft(characterId, record, summary, raw, relationships) }, enabled = !saving, label = stringResource(R.string.memory_object), modifier = Modifier.fillMaxWidth())
                        TextButton(enabled = !saving, onClick = { relationships = relationships.filterIndexed { i, _ -> i != index }; controller.saveDraft(characterId, record, summary, raw, relationships) }, modifier = Modifier.align(Alignment.End)) { Text(stringResource(R.string.action_delete)) }
                    }
                }
                item {
                    TextButton(enabled = !saving, onClick = { relationships = relationships + MemoryRelationship("", "", ""); controller.saveDraft(characterId, record, summary, raw, relationships) }) { Text(stringResource(R.string.memory_add_relationship)) }
                }
            }
            if (saving) LinearProgressIndicator(Modifier.fillMaxWidth())
            Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), horizontalArrangement = Arrangement.End) {
                TextButton(enabled = !saving, onClick = dismiss) { Text(stringResource(R.string.action_cancel)) }
                Button(enabled = !saving, onClick = {
                    saving = true
                    scope.launch {
                        try {
                            controller.save(store, characterId, record, summary, raw, relationships)
                            onSaved()
                        } finally { saving = false }
                    }
                }) { Text(stringResource(R.string.action_save)) }
            }
        }
    }
}


/** A readable relationship map. Only relationships present in the records are drawn. */
@Composable
internal fun MemoryGraph(
    records: List<MemoryRecord>,
    onSelect: (MemoryRecord) -> Unit,
    modifier: Modifier = Modifier,
) {
    val layout = remember(records) { buildMemoryGraphLayout(records) }
    Column(modifier) {
        BoxWithConstraints(Modifier.fillMaxWidth().weight(1f).clip(RoundedCornerShape(8.dp))) {
        val viewportWidth = maxWidth
        val viewportHeight = maxHeight - 48.dp
        val localDensity = androidx.compose.ui.platform.LocalDensity.current
        val fitScale = remember(layout, viewportWidth, viewportHeight) {
            min(1f, min(viewportWidth.value / layout.width, viewportHeight.value / layout.height).coerceAtLeast(.4f))
        }
        var scale by remember(records, fitScale) { mutableFloatStateOf(fitScale) }
    var panX by remember(records) { mutableFloatStateOf(0f) }
    var panY by remember(records) { mutableFloatStateOf(0f) }

    Column(Modifier.fillMaxSize()) {
    Box(
        Modifier.fillMaxWidth().weight(1f).clip(RoundedCornerShape(8.dp))
            .background(MaterialTheme.colorScheme.surface.copy(alpha = .94f))
            .pointerInput(records, viewportWidth, viewportHeight, localDensity.density) {
                detectTransformGestures { _, pan, zoom, _ ->
                    scale = (scale * zoom).coerceIn(.4f, 2.5f)
                    val width = layout.width * localDensity.density * scale
                    val height = layout.height * localDensity.density * scale
                    val availableWidth = viewportWidth.value * localDensity.density
                    val availableHeight = viewportHeight.value * localDensity.density
                    panX = (panX + pan.x).coerceIn(min(0f, availableWidth - width), max(0f, availableWidth - width))
                    panY = (panY + pan.y).coerceIn(min(0f, availableHeight - height), max(0f, availableHeight - height))
                }
            },
    ) {
        Box(
            Modifier
                .wrapContentSize(Alignment.TopStart, unbounded = true)
                .requiredSize(layout.width.dp, layout.height.dp)
                .graphicsLayer {
                    transformOrigin = TransformOrigin(0f, 0f)
                    scaleX = scale
                    scaleY = scale
                    translationX = panX
                    translationY = panY
                },
        ) {
            MemoryGraphEdges(layout)
            layout.nodes.forEach { node ->
                val linked = node.recordIndexes.mapNotNull(records::getOrNull)
                val memoryNode = node.kind == MemoryNodeKind.Memory
                val description = stringResource(
                    if (memoryNode) R.string.memory_graph_memory_node else R.string.memory_graph_entity_node,
                    node.label,
                )
                Surface(
                    modifier = Modifier
                        .offset(node.x.dp, node.y.dp)
                        .size(NODE_WIDTH.dp, NODE_HEIGHT.dp)
                        .semantics {
                            contentDescription = description
                            role = Role.Button
                        }
                        .clickable(enabled = linked.isNotEmpty()) {
                            linked.firstOrNull()?.let(onSelect)
                        },
                    shape = RoundedCornerShape(6.dp),
                    color = if (memoryNode) SlateRaised else Ink,
                    contentColor = TextPrimary,
                    border = androidx.compose.foundation.BorderStroke(
                        1.dp,
                        if (memoryNode) Accent.copy(alpha = .4f) else TextMuted.copy(alpha = .25f),
                    ),
                ) {
                    Text(
                        text = node.label,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                        maxLines = 2,
                        style = if (memoryNode) MaterialTheme.typography.bodyMedium else MaterialTheme.typography.titleSmall,
                    )
                }
            }
        }
    }
        TextButton(
            onClick = { scale = fitScale; panX = 0f; panY = 0f },
            modifier = Modifier.align(Alignment.End).height(48.dp),
        ) { Text(stringResource(R.string.memory_graph_reset)) }
    }
    }
}
}

@Composable
private fun MemoryGraphEdges(layout: MemoryGraphLayout) {
    Canvas(Modifier.fillMaxSize()) {
        layout.edges.forEach { edge ->
            val start = layout.nodes[edge.from].let { androidx.compose.ui.geometry.Offset((it.x + NODE_WIDTH / 2) * density, (it.y + NODE_HEIGHT / 2) * density) }
            val end = layout.nodes[edge.to].let { androidx.compose.ui.geometry.Offset((it.x + NODE_WIDTH / 2) * density, (it.y + NODE_HEIGHT / 2) * density) }
            drawLine(
                color = TextMuted.copy(alpha = .35f),
                start = start,
                end = end,
                strokeWidth = 2.dp.toPx(),
            )
            if (edge.relationship.isNotBlank()) {
                val delta = end - start
                val length = delta.getDistance()
                if (length > 0f) {
                    val inset = min(
                        NODE_WIDTH.dp.toPx() / 2f / kotlin.math.abs(delta.x).coerceAtLeast(.001f),
                        NODE_HEIGHT.dp.toPx() / 2f / kotlin.math.abs(delta.y).coerceAtLeast(.001f),
                    )
                    val tip = end - delta * inset
                    val direction = delta / length
                    val wing = androidx.compose.ui.geometry.Offset(-direction.y, direction.x) * 4.dp.toPx()
                    val base = tip - direction * 8.dp.toPx()
                    drawLine(Accent, base + wing, tip, 2.dp.toPx())
                    drawLine(Accent, base - wing, tip, 2.dp.toPx())
                }
            }
        }
    }
    layout.edges.forEach { edge ->
        if (edge.relationship.isEmpty()) return@forEach
        val start = layout.nodes[edge.from]
        val end = layout.nodes[edge.to]
        Text(
            text = edge.relationship,
            modifier = Modifier
                .offset(
                    x = (((start.x + end.x + NODE_WIDTH) / 2f) - 42f).dp,
                    y = (((start.y + end.y + NODE_HEIGHT) / 2f) - 14f).dp,
                )
                .background(MaterialTheme.colorScheme.surface, RoundedCornerShape(6.dp))
                .padding(horizontal = 5.dp, vertical = 2.dp),
            maxLines = 1,
            style = MaterialTheme.typography.labelMedium,
            color = TextMuted,
        )
    }
}
