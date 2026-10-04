package com.mrj.fancyai.ui.memory

import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableIntStateOf
import com.mrj.fancyai.service.memory.MemoryPackage
import com.mrj.fancyai.ui.characters.CharacterCard
import com.mrj.fancyai.ui.characters.availableCharacters
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import com.mrj.fancyai.service.memory.CharacterMemory
import com.mrj.fancyai.service.memory.MemoryRecord
import com.mrj.fancyai.memory.memoryKeywords
import com.mrj.fancyai.service.memory.memoryJson
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlin.math.max

internal class MemoryController(private val context: Context, private val scope: CoroutineScope) {
    var installed by mutableStateOf(false)
        private set
    var loading by mutableStateOf(true)
        private set
    var progress by mutableStateOf<Float?>(null)
        private set
    var installation by mutableStateOf<Job?>(null)
        private set
    var removing by mutableStateOf(false)
        private set
    var characters by mutableStateOf<List<CharacterCard>>(emptyList())
        private set

    suspend fun load() {
        try {
            characters = withContext(Dispatchers.IO) { availableCharacters(context) }
            installed = withContext(Dispatchers.IO) { MemoryPackage.installed(context) }
        } finally { loading = false }
    }

    fun install(uri: android.net.Uri?) {
        if (installation != null) return
        progress = 0f
        installation = scope.launch {
            try {
                val update: suspend (Float) -> Unit = { value -> withContext(Dispatchers.Main) { progress = value } }
                if (uri == null) MemoryPackage.download(context, update)
                else MemoryPackage.install(context, { checkNotNull(context.contentResolver.openInputStream(uri)) }, update)
                installed = true
            } finally { progress = null; installation = null }
        }
    }

    fun removeModel() {
        removing = true
        scope.launch {
            try {
                MemoryPackage.installation.withLock {
                    CharacterMemory.modelLock.withLock {
                        withContext(NonCancellable) {
                            withContext(Dispatchers.IO) {
                                val models = context.getExternalFilesDir("models") ?: File(context.filesDir, "models")
                                // The retired English-only payload is removed alongside the current one.
                                File(models, "memory-english-v1").deleteRecursively()
                                MemoryPackage.directory(context).deleteRecursively()
                            }
                            installed = false
                        }
                    }
                }
            } finally { removing = false }
        }
    }

    var clearing by mutableStateOf(false)
        private set
    var revision by mutableIntStateOf(0)
        private set

    fun deleteAll(characterId: String) {
        clearing = true
        scope.launch {
            try {
                CharacterMemory.modelLock.withLock {
                    withContext(NonCancellable) {
                        withContext(Dispatchers.IO) {
                            val root = File(context.filesDir, "chat_memory")
                            File(root, characterId).deleteRecursively()
                        }
                        drafts.edit {
                            drafts.all.keys.filter { it.startsWith("$characterId:") }.forEach(::remove)
                        }
                        revision++
                    }
                }
            } finally { clearing = false }
        }
    }

    private val preferences = context.getSharedPreferences("chat_memory", 0)

    var recallLatest: Boolean
        get() = preferences.getBoolean("recallLatest", true)
        set(value) { preferences.edit { putBoolean("recallLatest", value) } }

    var maxInjections: Int
        get() = preferences.getInt("maxInjections", 1)
        set(value) { preferences.edit { putInt("maxInjections", value) } }

    var minimumScore: Float
        get() = preferences.getFloat("minimumScore", CharacterMemory.DEFAULT_MINIMUM_SCORE)
        set(value) { preferences.edit { putFloat("minimumScore", value) } }

    fun enabled(characterId: String): Boolean = preferences.getBoolean("$characterId.enabled", false)

    fun setEnabled(characterId: String, value: Boolean) {
        preferences.edit { putBoolean("$characterId.enabled", value) }
    }

    fun autoCollect(characterId: String): Boolean = preferences.getBoolean("$characterId.autoCollect", false)

    fun setAutoCollect(characterId: String, value: Boolean) {
        preferences.edit { putBoolean("$characterId.autoCollect", value) }
    }

    private val drafts: SharedPreferences = context.getSharedPreferences("chat_memory_drafts", 0)

    fun draft(characterId: String, record: MemoryRecord): MemoryRecord {
        val key = "$characterId:${record.id}"
        return drafts.getString(key, null)?.let { runCatching { memoryJson.decodeFromString<MemoryRecord>(it) }.getOrNull() } ?: record
    }

    fun saveDraft(characterId: String, record: MemoryRecord, summary: String, raw: String, relationships: List<MemoryRelationship>) {
        drafts.edit {
            putString(
                "$characterId:${record.id}",
                memoryJson.encodeToString(MemoryRecord(summary = summary, rawText = raw, relationships = relationships)),
            )
        }
    }

    suspend fun save(
        store: CharacterMemory,
        characterId: String,
        record: MemoryRecord,
        summary: String,
        raw: String,
        relationships: List<MemoryRelationship>,
    ) {
        store.save(record.copy(summary = summary, rawText = raw, relationships = relationships))
        drafts.edit { remove("$characterId:${record.id}") }
    }

    fun delete(characterId: String, record: MemoryRecord): Job {
        clearing = true
        return scope.launch {
            try {
                withContext(Dispatchers.IO) {
                    CharacterMemory.modelLock.withLock {
                        android.util.AtomicFile(File(context.filesDir, "chat_memory/$characterId/${record.id}.json")).delete()
                    }
                }
                drafts.edit { remove("$characterId:${record.id}") }
                revision++
            } finally { clearing = false }
        }
    }
}

@Serializable
internal data class MemoryRelationship(
    val subject: String = "",
    val relationship: String = "",
    @SerialName("object") val target: String = "",
)

internal const val NODE_WIDTH = 156
internal const val NODE_HEIGHT = 64
internal const val COLUMN_GAP = 96
internal const val ROW_GAP = 20
internal const val HORIZONTAL_PADDING = 20
private const val MAX_DERIVED_ENTITIES = 24
private const val MAX_NODE_LABEL_CHARS = 140

internal enum class MemoryNodeKind { Memory, Entity }

internal data class GraphNode(
    val label: String,
    val kind: MemoryNodeKind,
    val x: Int,
    val y: Int,
    val recordIndexes: List<Int>,
)

internal data class MemoryGraphEdge(val from: Int, val to: Int, val relationship: String = "")
internal data class MemoryGraphLayout(val nodes: List<GraphNode>, val edges: List<MemoryGraphEdge>, val width: Int, val height: Int)

internal fun buildMemoryGraphLayout(records: List<MemoryRecord>): MemoryGraphLayout {
    val entities = linkedMapOf<String, EntityLinks>()
    fun link(raw: String, index: Int) {
        val label = raw.trim()
        if (label.isEmpty()) return
        entities.getOrPut(label.lowercase()) { EntityLinks(label, mutableListOf()) }.indexes.add(index)
    }
    records.forEachIndexed { index, record ->
        record.relationships.forEach { relation ->
            link(relation.subject, index)
            link(relation.target, index)
        }
    }
    // Terms shared across memories become entity nodes without any parser.
    val keywords = records.map { memoryKeywords(it.summary + "\n" + it.rawText) }
    keywords.flatten().groupingBy { it }.eachCount().entries
        .sortedByDescending { it.value }.take(MAX_DERIVED_ENTITIES)
        .filter { it.value >= 2 }
        .forEach { (keyword, _) ->
            keywords.forEachIndexed { index, recordKeywords ->
                if (keyword in recordKeywords) link(keyword, index)
            }
        }
    val rows = max(records.size, entities.size).coerceAtLeast(1)
    val nodes = buildList {
        records.forEachIndexed { index, record ->
            val label = record.summary.trim().take(MAX_NODE_LABEL_CHARS).ifEmpty { record.id }
            add(GraphNode(label, MemoryNodeKind.Memory, HORIZONTAL_PADDING, index * (NODE_HEIGHT + ROW_GAP) + HORIZONTAL_PADDING, listOf(index)))
        }
        entities.values.forEachIndexed { index, entity ->
            add(GraphNode(entity.label, MemoryNodeKind.Entity, HORIZONTAL_PADDING + NODE_WIDTH + COLUMN_GAP, index * (NODE_HEIGHT + ROW_GAP) + HORIZONTAL_PADDING, entity.indexes.distinct()))
        }
    }
    val nodeIndex = nodes.withIndex().associate { (position, node) ->
        (node.kind to node.label.lowercase()) to position
    }
    val edges = buildList {
        records.forEachIndexed { recordIndex, record ->
            record.relationships.forEach { relation ->
                val subjectNode = nodeIndex[MemoryNodeKind.Entity to relation.subject.trim().lowercase()]
                val objectNode = nodeIndex[MemoryNodeKind.Entity to relation.target.trim().lowercase()]
                if (subjectNode != null) add(MemoryGraphEdge(recordIndex, subjectNode))
                if (objectNode != null) add(MemoryGraphEdge(recordIndex, objectNode))
                if (subjectNode != null && objectNode != null) {
                    add(MemoryGraphEdge(subjectNode, objectNode, relation.relationship.trim()))
                }
            }
            keywords[recordIndex].forEach { keyword ->
                nodeIndex[MemoryNodeKind.Entity to keyword]?.let { add(MemoryGraphEdge(recordIndex, it)) }
            }
        }
    }.distinct()
    return MemoryGraphLayout(
        nodes,
        edges,
        HORIZONTAL_PADDING * 2 + NODE_WIDTH * 2 + COLUMN_GAP,
        HORIZONTAL_PADDING * 2 + (rows * (NODE_HEIGHT + ROW_GAP)),
    )
}

private data class EntityLinks(val label: String, val indexes: MutableList<Int>)
