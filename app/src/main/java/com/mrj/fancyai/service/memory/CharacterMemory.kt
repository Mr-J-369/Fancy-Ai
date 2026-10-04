package com.mrj.fancyai.service.memory

import android.content.Context
import android.os.SystemClock
import android.util.AtomicFile
import androidx.core.content.edit
import com.mrj.fancyai.memory.MemoryEmbedder
import com.mrj.fancyai.memory.memoryKeywords
import com.mrj.fancyai.ui.memory.MemoryRelationship
import com.mrj.fancyai.util.AppLog
import com.mrj.fancyai.util.writeAtomicFile
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.util.UUID

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
)

internal val memoryJson = Json { ignoreUnknownKeys = true; encodeDefaults = true }

/** Character-local memory. Embeddings run in the isolated :memory process. */
internal class CharacterMemory(context: Context, characterId: String) {
    private val app = context.applicationContext
    private val preferences = context.getSharedPreferences("chat_memory", Context.MODE_PRIVATE)
    private val key = characterId.also { require(it.isNotBlank() && it != "." && it != ".." && '/' !in it && '\\' !in it) }
    private val root = File(context.filesDir, "chat_memory/$key")

    suspend fun records(): List<MemoryRecord> = withContext(Dispatchers.IO) {
        modelLock.withLock { read().sortedByDescending { it.maxTimestamp } }
    }

    suspend fun save(record: MemoryRecord) = withContext(Dispatchers.IO) {
        modelLock.withLock {
            val file = AtomicFile(File(root, "${UUID.fromString(record.id)}.json"))
            var saved = memoryJson.decodeFromString<MemoryRecord>(file.openRead().bufferedReader(Charsets.UTF_8).use { it.readText() })
            val raw = record.rawText
            val summary = record.summary
            if (raw != saved.rawText || summary != saved.summary) {
                val rawVector = MemoryClient.embed(app, raw).map { it.toDouble() }
                currentCoroutineContext().ensureActive()
                val summaryVector = if (summary == raw) rawVector else MemoryClient.embed(app, summary).map { it.toDouble() }
                saved = saved.copy(rawEmbedding = rawVector, summaryEmbedding = summaryVector, embeddingModel = MemoryEmbedder.EMBEDDING_MODEL)
            }
            saved = saved.copy(rawText = raw, summary = summary, relationships = record.relationships)
            currentCoroutineContext().ensureActive()
            writeAtomicFile(file.baseFile, memoryJson.encodeToString(saved).toByteArray(Charsets.UTF_8))
        }
    }

    suspend fun recall(query: String, newConversation: Boolean): List<String> {
        migrateThreshold()
        if (!preferences.getBoolean("$key.enabled", false)) return emptyList()
        val started = SystemClock.elapsedRealtime()
        AppLog.write(android.util.Log.INFO, "Memory", "Recall started newConversation=$newConversation")
        return try {
            withContext(Dispatchers.IO) {
                modelLock.withLock {
                    val memories = read()
                    val limit = preferences.getInt("maxInjections", 1).coerceAtLeast(0)
                    if (memories.isEmpty() || limit == 0) return@withLock emptyList()
                    val latest = if (newConversation && preferences.getBoolean("recallLatest", true)) memories.maxByOrNull { it.maxTimestamp } else null
                    val matches = if (query.isBlank()) emptyList() else {
                        val vector = MemoryClient.embed(app, query)
                        val scored = memories.asSequence()
                            .filter { it.embeddingModel == MemoryEmbedder.EMBEDDING_MODEL }
                            .map { memory -> memory to maxOf(score(vector, memory.rawEmbedding), score(vector, memory.summaryEmbedding)) }
                            .filter { it.second >= preferences.getFloat("minimumScore", 0.35f) }
                            .sortedByDescending { it.second }.map { it.first }.toList()
                        // Keyword fallback rescues names and terms dense vectors miss, in any language.
                        (scored + keywordHits(query, memories)).distinctBy { it.id }
                    }
                    (listOfNotNull(latest) + matches).distinctBy { it.id }.take(limit)
                        .map { "[Memory]\n${it.summary}" }
                }
            }
        } catch (_: TimeoutCancellationException) {
            AppLog.write(android.util.Log.INFO, "Memory", "Recall timed out")
            emptyList()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            AppLog.write(android.util.Log.ERROR, "Memory", "Recall failed", failure)
            emptyList()
        } finally {
            AppLog.write(android.util.Log.INFO, "Memory", "Recall ended elapsedMs=${SystemClock.elapsedRealtime() - started}")
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
            // Three individual speaker messages, not three exchanges; no greeting fabrication.
            if (messages.size < 3) return
            val raw = messages.takeLast(3).joinToString("\n")
            withContext(Dispatchers.IO) {
                modelLock.withLock {
                    val id = UUID.nameUUIDFromBytes("$sessionId:$turnIndex".toByteArray(Charsets.UTF_8)).toString()
                    val file = AtomicFile(File(root, "${UUID.fromString(id)}.json"))
                    val previous = if (file.baseFile.exists()) memoryJson.decodeFromString<MemoryRecord>(file.openRead().bufferedReader().use { it.readText() }) else null
                    if (previous?.rawText == raw) return@withLock
                    AppLog.write(android.util.Log.INFO, "Memory", "Collection started messages=${messages.size}")
                    // Verbatim storage keeps every language intact without a separate summarizer model.
                    // One embedding serves both vectors; manual graph edits survive recollection.
                    val embedding = MemoryClient.embed(app, raw).map { it.toDouble() }
                    val record = MemoryRecord(id = id, sessionId = sessionId, minTimestamp = timestamp, maxTimestamp = timestamp,
                        rawText = raw, summary = raw, embeddingModel = MemoryEmbedder.EMBEDDING_MODEL,
                        rawEmbedding = embedding, summaryEmbedding = embedding)
                    currentCoroutineContext().ensureActive()
                    val collected = record.copy(relationships = previous?.relationships ?: emptyList())
                    currentCoroutineContext().ensureActive()
                    writeAtomicFile(file.baseFile, memoryJson.encodeToString(collected).toByteArray(Charsets.UTF_8))
                    AppLog.write(android.util.Log.INFO, "Memory", "Collection saved")
                }
            }
        } catch (_: TimeoutCancellationException) {
            AppLog.write(android.util.Log.INFO, "Memory", "Collection timed out")
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
            withContext(Dispatchers.IO) {
                modelLock.withLock {
                    val id = UUID.nameUUIDFromBytes(sessionId.toByteArray(Charsets.UTF_8)).toString()
                    val file = AtomicFile(File(root, "${UUID.fromString(id)}.json"))
                    val previous = if (file.baseFile.exists()) memoryJson.decodeFromString<MemoryRecord>(file.openRead().bufferedReader().use { it.readText() }) else null
                    if (previous?.rawText == entry) return@withLock
                    AppLog.write(android.util.Log.INFO, "Memory", "Post collection started")
                    val embedding = MemoryClient.embed(app, entry).map { it.toDouble() }
                    currentCoroutineContext().ensureActive()
                    val collected = MemoryRecord(id = id, sessionId = sessionId, minTimestamp = timestamp, maxTimestamp = timestamp,
                        rawText = entry, summary = entry, embeddingModel = MemoryEmbedder.EMBEDDING_MODEL,
                        rawEmbedding = embedding, summaryEmbedding = embedding,
                        relationships = previous?.relationships ?: emptyList())
                    currentCoroutineContext().ensureActive()
                    writeAtomicFile(file.baseFile, memoryJson.encodeToString(collected).toByteArray(Charsets.UTF_8))
                    AppLog.write(android.util.Log.INFO, "Memory", "Post collection saved")
                }
            }
        } catch (_: TimeoutCancellationException) {
            AppLog.write(android.util.Log.INFO, "Memory", "Post collection timed out")
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
            val records = read()
            val targetIds = (fromTurnIndex..(fromTurnIndex + 10000)).mapTo(HashSet()) { i ->
                UUID.nameUUIDFromBytes("$sessionId:$i".toByteArray(Charsets.UTF_8)).toString()
            }
            records.filter { it.sessionId == sessionId && it.id in targetIds }.forEach { record ->
                AtomicFile(File(root, "${UUID.fromString(record.id)}.json")).delete()
            }
        }
    }

    private fun migrateThreshold() {
        if (preferences.getInt("modelVersion", 1) >= MODEL_VERSION) return
        // MiniLM scores run lower than bge-m3; carry untouched defaults to the new scale.
        if (preferences.getFloat("minimumScore", 0.6f) == 0.6f) {
            preferences.edit { putFloat("minimumScore", DEFAULT_MINIMUM_SCORE) }
        }
        preferences.edit { putInt("modelVersion", MODEL_VERSION) }
    }

    private fun keywordHits(query: String, memories: List<MemoryRecord>): List<MemoryRecord> {
        val tokens = memoryKeywords(query).take(8)
        if (tokens.isEmpty()) return emptyList()
        return memories.mapNotNull { memory ->
            val haystack = (memory.rawText + "\n" + memory.summary).lowercase()
            val hits = tokens.count { it in haystack }
            if (hits >= 2 || (tokens.size <= 2 && hits >= 1)) memory to hits else null
        }.sortedByDescending { it.second }.map { it.first }
    }

    private fun read(): List<MemoryRecord> {
        if (!root.exists()) return emptyList()
        return java.nio.file.Files.newDirectoryStream(root.toPath(), "*.json").use { files ->
            files.map { file ->
                memoryJson.decodeFromString<MemoryRecord>(AtomicFile(file.toFile()).openRead().bufferedReader(Charsets.UTF_8).use { it.readText() })
            }
        }
    }

    companion object {
        val modelLock = Mutex()

        internal const val MODEL_VERSION = 2
        internal const val DEFAULT_MINIMUM_SCORE = 0.35f

        fun score(query: FloatArray, stored: List<Double>): Double {
            return query.indices.sumOf { query[it] * stored[it] }
        }
    }
}
