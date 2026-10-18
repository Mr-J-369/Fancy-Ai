package com.mrj.fancyai.service.memory

import android.content.Context
import android.util.AtomicFile
import androidx.core.content.edit
import com.mrj.fancyai.ui.memory.MemoryRelationship
import com.mrj.fancyai.util.AppLog
import com.mrj.fancyai.util.writeAtomicFile
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.util.UUID
import kotlin.math.ln
import kotlin.time.TimeSource

@Serializable
internal data class MemoryRecord(
    val id: String = "",
    val sessionId: String = "",
    val minTimestamp: Long = 0,
    val maxTimestamp: Long = 0,
    val rawText: String = "",
    val summary: String = "",
    val embeddingModel: String = "",
    val rawEmbedding: List<Double> = emptyList(),
    val summaryEmbedding: List<Double> = emptyList(),
    val relationships: List<MemoryRelationship> = emptyList(),
    val turnIndex: Int? = null,
)

internal val memoryJson = Json { ignoreUnknownKeys = true; encodeDefaults = true }

/** Character-local memory. Lexical BM25-style recall; no model, no IPC, no blocking. */
internal class CharacterMemory(context: Context, characterId: String) {
    private val preferences = context.getSharedPreferences("chat_memory", Context.MODE_PRIVATE)
    private val key = characterId.also { require(it.isNotBlank() && it != "." && it != ".." && '/' !in it && '\\' !in it) }
    private val root = File(context.filesDir, "chat_memory/$key")

    suspend fun records(): List<MemoryRecord> = withContext(Dispatchers.IO) {
        modelLock.withLock { read().sortedByDescending { it.maxTimestamp } }
    }

    suspend fun save(record: MemoryRecord) = withContext(Dispatchers.IO) {
        val merged = modelLock.withLock {
            val base = readRecord(record.id) ?: record
            base.copy(
                rawText = record.rawText,
                summary = record.summary,
                rawEmbedding = emptyList(),
                summaryEmbedding = emptyList(),
                embeddingModel = LEXICAL_MODEL,
                relationships = record.relationships,
            )
        }
        modelLock.withLock { writeRecord(merged) }
    }

    suspend fun recall(query: String, newConversation: Boolean): List<String> {
        migrateThreshold()
        if (!preferences.getBoolean("$key.enabled", false)) return emptyList()
        val started = TimeSource.Monotonic.markNow()
        return try {
            val memories = withContext(Dispatchers.IO) { modelLock.withLock { read() } }
            val limit = preferences.getInt("maxInjections", 1).coerceAtLeast(0)
            if (memories.isEmpty() || limit == 0) return emptyList()
            val latest = if (newConversation && preferences.getBoolean("recallLatest", true)) memories.maxByOrNull { it.maxTimestamp } else null
            val matches = rankLexical(query, memories, preferences.getFloat("minimumScore", DEFAULT_MINIMUM_SCORE))
            val blocks = mutableListOf<String>()
            var injected = 0
            for (record in (matches + listOfNotNull(latest)).distinctBy { it.id }.take(limit)) {
                val block = "[Memory]\n${record.summary}"
                if (injected + block.length > MAX_INJECT_CHARS && blocks.isNotEmpty()) break
                blocks.add(block)
                injected += block.length
            }
            blocks
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            AppLog.write(android.util.Log.ERROR, "Memory", "Recall failed", failure)
            emptyList()
        } finally {
            AppLog.write(android.util.Log.INFO, "Memory", "Recall ended elapsedMs=${started.elapsedNow().inWholeMilliseconds}")
        }
    }

    suspend fun collect(
        sessionId: String,
        turnIndex: Int,
        timestamp: Long,
        messages: List<String>,
    ) {
        try {
            migrateThreshold()
            if (!preferences.getBoolean("$key.enabled", false) || !preferences.getBoolean("$key.autoCollect", false)) return
            if (messages.size < 2) return
            val raw = condense(messages)
            val id = UUID.nameUUIDFromBytes("$sessionId:$turnIndex".toByteArray(Charsets.UTF_8)).toString()
            val unchanged = withContext(Dispatchers.IO) {
                modelLock.withLock { readRecord(id)?.takeIf { it.rawText == raw } }
            }
            if (unchanged != null) return
            AppLog.write(android.util.Log.INFO, "Memory", "Collection started messages=${messages.size}")
            val duplicate = withContext(Dispatchers.IO) {
                modelLock.withLock {
                    val fingerprint = keywordSet(raw)
                    read().any { it.id != id && jaccard(fingerprint, keywordSet(it.rawText)) >= DEDUPE_SIMILARITY }
                }
            }
            if (duplicate) {
                AppLog.write(android.util.Log.INFO, "Memory", "Collection skipped as duplicate")
                return
            }
            withContext(Dispatchers.IO) {
                modelLock.withLock {
                    val previous = readRecord(id)
                    if (previous?.rawText == raw) return@withLock
                    val collected = MemoryRecord(id = id, sessionId = sessionId, minTimestamp = timestamp, maxTimestamp = timestamp,
                        rawText = raw, summary = raw, embeddingModel = LEXICAL_MODEL,
                        rawEmbedding = emptyList(), summaryEmbedding = emptyList(),
                        relationships = previous?.relationships ?: emptyList(), turnIndex = turnIndex)
                    writeRecord(collected)
                    evictOldestLocked()
                    AppLog.write(android.util.Log.INFO, "Memory", "Collection saved")
                }
            }
        } catch (cancelled: CancellationException) {
            AppLog.write(android.util.Log.INFO, "Memory", "Collection cancelled")
            throw cancelled
        } catch (failure: Exception) {
            AppLog.write(android.util.Log.ERROR, "Memory", "Collection failed", failure)
        }
    }

    /** A published post is one authored artifact, not a conversation window. */
    suspend fun collectPost(
        sessionId: String,
        timestamp: Long,
        entry: String,
    ) {
        try {
            migrateThreshold()
            if (!preferences.getBoolean("$key.enabled", false) || !preferences.getBoolean("$key.autoCollect", false)) return
            if (entry.isBlank()) return
            val id = UUID.nameUUIDFromBytes(sessionId.toByteArray(Charsets.UTF_8)).toString()
            val condensed = condense(listOf(entry))
            val unchanged = withContext(Dispatchers.IO) {
                modelLock.withLock { readRecord(id)?.takeIf { it.rawText == condensed } }
            }
            if (unchanged != null) return
            AppLog.write(android.util.Log.INFO, "Memory", "Post collection started")
            withContext(Dispatchers.IO) {
                modelLock.withLock {
                    val previous = readRecord(id)
                    if (previous?.rawText == condensed) return@withLock
                    val collected = MemoryRecord(id = id, sessionId = sessionId, minTimestamp = timestamp, maxTimestamp = timestamp,
                        rawText = condensed, summary = condensed, embeddingModel = LEXICAL_MODEL,
                        rawEmbedding = emptyList(), summaryEmbedding = emptyList(),
                        relationships = previous?.relationships ?: emptyList(),)
                    writeRecord(collected)
                    evictOldestLocked()
                    AppLog.write(android.util.Log.INFO, "Memory", "Post collection saved")
                }
            }
        } catch (cancelled: CancellationException) {
            AppLog.write(android.util.Log.INFO, "Memory", "Post collection cancelled")
            throw cancelled
        } catch (failure: Exception) {
            AppLog.write(android.util.Log.ERROR, "Memory", "Post collection failed", failure)
        }
    }

    suspend fun deleteSession(sessionId: String) = withContext(Dispatchers.IO) {
        if (!root.exists() || sessionId.isBlank()) return@withContext
        modelLock.withLock {
            val records = read()
            records.filter { it.sessionId == sessionId }.forEach { record ->
                AtomicFile(File(root, "${UUID.fromString(record.id)}.json")).delete()
            }
        }
    }

    suspend fun deleteSessionFrom(sessionId: String, fromTurnIndex: Int) = withContext(Dispatchers.IO) {
        if (!root.exists() || sessionId.isBlank()) return@withContext
        modelLock.withLock {
            read().filter { it.sessionId == sessionId && (it.turnIndex == null || it.turnIndex >= fromTurnIndex) }.forEach { record ->
                AtomicFile(File(root, "${UUID.fromString(record.id)}.json")).delete()
            }
        }
    }

    private fun migrateThreshold() {
        if (preferences.getInt("modelVersion", 1) >= MODEL_VERSION) return
        preferences.edit { putInt("modelVersion", MODEL_VERSION) }
    }

    private fun condense(messages: List<String>): String {
        val full = messages.takeLast(3).joinToString("\n")
        if (full.length <= MAX_STORED_CHARS) return full
        val shorter = messages.takeLast(2).joinToString("\n")
        if (shorter.length <= MAX_STORED_CHARS) return shorter
        val cut = shorter.take(MAX_STORED_CHARS)
        val end = cut.indexOfLast { it in SENTENCE_ENDS }
        if (end > MAX_STORED_CHARS / 2) return cut.substring(0, end + 1)
        val space = cut.indexOfLast(Char::isWhitespace)
        return if (space > 0) cut.substring(0, space) else cut
    }

    private fun rankLexical(query: String, memories: List<MemoryRecord>, minimumScore: Float): List<MemoryRecord> {
        if (query.isBlank()) return emptyList()
        var tokens = memoryKeywords(query).take(MAX_QUERY_TOKENS)
        if (tokens.isEmpty()) tokens = memoryKeywords(query, filterStops = false).take(MAX_QUERY_TOKENS)
        if (tokens.isEmpty()) return emptyList()
        val count = memories.size.toDouble()
        val docFreq = mutableMapOf<String, Int>()
        val docTokens = memories.map { memory ->
            keywordSet(memory.rawText + "\n" + memory.summary).onEach { token -> docFreq[token] = (docFreq[token] ?: 0) + 1 }
        }
        val live = tokens.filter { (docFreq[it] ?: 0) / count <= DYNAMIC_STOP_RATIO }.ifEmpty { tokens }
        val scored = memories.mapIndexedNotNull { index, memory ->
            var score = 0.0
            for (token in live) {
                val idf = ln(1.0 + count / (docFreq[token] ?: 1))
                if (token in docTokens[index]) {
                    score += idf
                } else if (token.length >= MIN_PREFIX_CHARS) {
                    // Inflections share a stem: dogs/dog, собаки/собакой, Hunde/Hund.
                    score += docTokens[index]
                        .filter { it.length >= MIN_PREFIX_CHARS && (it.startsWith(token) || token.startsWith(it)) }
                        .maxOfOrNull { ln(1.0 + count / (docFreq[it] ?: 1)) }?.times(PREFIX_WEIGHT) ?: 0.0
                }
            }
            if (score <= 0.0) null else memory to score
        }.sortedByDescending { it.second }
        if (scored.isEmpty()) return emptyList()
        val (_, best) = scored.first()
        val ordered = scored.filter { (_, score) -> score / best >= minimumScore }
            .sortedWith(compareByDescending<Pair<MemoryRecord, Double>> { (_, score) -> score }
                .thenByDescending { (memory, _) -> memory.maxTimestamp })
            .map { (memory, _) -> memory }
        return ordered.ifEmpty { scored.take(1).map { (memory, _) -> memory } }
    }

    private fun keywordSet(text: String): Set<String> = memoryKeywords(text).toSet()

    private fun jaccard(first: Set<String>, second: Set<String>): Double {
        if (first.isEmpty() || second.isEmpty()) return 0.0
        val union = first.size + second.size - first.intersect(second).size
        if (union == 0) return 0.0
        return first.intersect(second).size.toDouble() / union
    }

    private fun read(): List<MemoryRecord> {
        if (!root.exists()) return emptyList()
        return java.nio.file.Files.newDirectoryStream(root.toPath(), "*.json").use { files ->
            files.map { file ->
                memoryJson.decodeFromString<MemoryRecord>(AtomicFile(file.toFile()).openRead().bufferedReader(Charsets.UTF_8).use { it.readText() })
            }
        }
    }

    private fun readRecord(id: String): MemoryRecord? {
        val file = File(root, "$id.json")
        if (!file.isFile) return null
        return memoryJson.decodeFromString<MemoryRecord>(AtomicFile(file).openRead().bufferedReader(Charsets.UTF_8).use { it.readText() })
    }

    private fun writeRecord(record: MemoryRecord) {
        writeAtomicFile(
            File(root, "${UUID.fromString(record.id)}.json"),
            memoryJson.encodeToString(record).toByteArray(Charsets.UTF_8),
        )
    }

    private fun evictOldestLocked() {
        val all = read()
        if (all.size > MAX_RECORDS_PER_CHARACTER) {
            all.sortedBy { it.maxTimestamp }
                .take(all.size - MAX_RECORDS_PER_CHARACTER)
                .forEach { record -> AtomicFile(File(root, "${UUID.fromString(record.id)}.json")).delete() }
        }
    }

    companion object {
        val modelLock = Mutex()

        internal const val MODEL_VERSION = 3
        internal const val DEFAULT_MINIMUM_SCORE = 0.35f
        internal const val MAX_RECORDS_PER_CHARACTER = 500
        internal const val MAX_STORED_CHARS = 500
        internal const val MAX_INJECT_CHARS = 1500
        internal const val DEDUPE_SIMILARITY = 0.9
        internal const val MAX_QUERY_TOKENS = 12
        internal const val LEXICAL_MODEL = "lexical-v1"
        private const val DYNAMIC_STOP_RATIO = 0.5
        private const val MIN_PREFIX_CHARS = 3
        private const val PREFIX_WEIGHT = 0.5

        private val SENTENCE_ENDS = setOf('.', '!', '?', '。', '！', '？', '؟', '…', '\n')
    }
}
