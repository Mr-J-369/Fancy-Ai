package com.mrj.fancyai.ui.cleanup

import android.app.ActivityManager
import android.content.Context
import android.os.Process
import android.os.SystemClock
import android.util.Xml
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.net.toUri
import com.mrj.fancyai.service.memory.MemoryPackage
import com.mrj.fancyai.ui.characters.ROOT_CHARACTER_ID
import com.mrj.fancyai.util.IMAGE_EXTENSIONS
import com.mrj.fancyai.util.auraSidecar
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import org.xmlpull.v1.XmlPullParser
import java.io.File
import java.nio.file.Files
import java.security.MessageDigest
import java.util.Properties

internal enum class CleanupKind { Cache, Orphan, Duplicate }

internal data class CleanupFile(val file: File, val bytes: Long, val modified: Long, val hash: String? = null)

internal data class CleanupItem(
    val kind: CleanupKind,
    val source: CleanupFile,
    val sidecar: CleanupFile? = null,
    val keeper: CleanupFile? = null,
    val keeperSidecar: CleanupFile? = null,
)

internal data class CleanupScan(val items: List<CleanupItem>, val incompleteReferences: Boolean = false)
internal data class CleanupResult(val removed: Int, val bytes: Long)

/** Only known disposable locations and unreferenced media without an owner are eligible. */
internal class CleanupStorage(context: Context) {
    private val app = context.applicationContext
    private val files = app.filesDir.canonicalFile
    private val cache = app.cacheDir.canonicalFile
    private val cacheRoots = (listOf(cache, app.codeCacheDir) + app.externalCacheDirs.filterNotNull())
        .asSequence().map(File::getCanonicalFile).distinct().toList()
    // Saved-media cleanup retains its existing age guard; cache cleanup uses live ownership instead.
    private val sessionStarted = System.currentTimeMillis() -
        (SystemClock.elapsedRealtime() - Process.getStartElapsedRealtime())

    suspend fun scan(): CleanupScan = withContext(Dispatchers.IO) {
        val references = mutableSetOf<String>()
        val completeReferences = references(references)
        val items = mutableListOf<CleanupItem>()
        val active = activeCachePaths() + references
        for (root in cacheRoots) for (file in walk(root)) {
            if (active.none { file.path == it || file.path.startsWith("$it/") }) {
                items += CleanupItem(CleanupKind.Cache, CleanupFile(file, file.length(), file.lastModified()))
            }
        }
        if (!completeReferences) return@withContext CleanupScan(items, incompleteReferences = true)
        val orphanCandidates = walk(File(files, "chat_attachments")).filter { it.extension == "image" }.toMutableList()
        val media = walk(File(files, "media"))
        val orphanAlbums = File(files, "media/characters").listFiles().orEmpty().filter {
            it.isDirectory && it.name != ROOT_CHARACTER_ID && !File(files, "characters/${it.name}").exists()
        }.toSet()
        orphanCandidates += orphanAlbums.filter { it.list()?.isEmpty() == true }
        val imageStems = media.filter { it.extension.lowercase() in IMAGE_EXTENSIONS }
            .mapTo(mutableSetOf()) { File(it.parentFile, it.nameWithoutExtension).path }
        orphanCandidates += media.filter { file ->
            val partial = ARCHIVE_PART.matches(file.name)
            val orphanSidecar = AURA_METADATA.matches(file.name) &&
                File(file.parentFile, file.nameWithoutExtension).path !in imageStems
            val ownerMissing = file.parentFile in orphanAlbums &&
                references.none { File(it).parentFile == file.parentFile && File(it).nameWithoutExtension == file.nameWithoutExtension }
            partial || orphanSidecar || ownerMissing
        }
        for (file in orphanCandidates) {
            if (file.path !in references && file.lastModified() in 1 until sessionStarted) {
                items += CleanupItem(CleanupKind.Orphan, CleanupFile(file, file.length(), file.lastModified()))
            }
        }
        findDuplicates(media, references, items)
        CleanupScan(items.sortedWith(compareBy<CleanupItem> { it.kind }.thenByDescending { it.source.bytes + (it.sidecar?.bytes ?: 0L) }.thenBy { it.source.file.path }))
    }

    private suspend fun findDuplicates(media: List<File>, references: Set<String>, items: MutableList<CleanupItem>) {
        // Size first, then streamed SHA-256. No bitmap decoding and no whole-file byte arrays.
        val orphans = items.mapTo(mutableSetOf()) { it.source.file }
        val images = media.filter { it !in orphans && !it.name.startsWith('.') && it.extension.lowercase() in IMAGE_EXTENSIONS }
        val metadataUsers = images.mapNotNull { auraSidecar(it)?.path }.groupingBy { it }.eachCount()
        for (sameSize in images.groupBy(File::length).values.filter { it.size > 1 }) {
            val hashed = sameSize.map { file -> CleanupFile(file, file.length(), file.lastModified(), digest(file)) }
            for (sameImage in hashed.groupBy(CleanupFile::hash).values.filter { it.size > 1 }) {
                // A unique generation prompt/seed is valuable even when image bytes match.
                val withMetadata = sameImage.map { image -> image to auraSidecar(image.file)?.let { CleanupFile(it, it.length(), it.lastModified(), digest(it)) } }
                for (copies in withMetadata.groupBy { it.second?.hash }.values.filter { it.size > 1 }) {
                    val removable = copies.associateWith { (image, metadata) ->
                        image.file.path !in references && image.modified in 1 until sessionStarted &&
                            (metadata == null || (metadata.file.path !in references && metadata.modified in 1 until sessionStarted && metadataUsers[metadata.file.path] == 1))
                    }
                    val ordered = copies.sortedWith(compareBy(removable::getValue)
                        .thenBy { it.first.modified }.thenBy { it.first.file.path })
                    val (keeper, keeperSidecar) = ordered.first()
                    val deletable = ordered.drop(1).filter(removable::getValue)
                    for ((copy, metadata) in deletable) {
                        items += CleanupItem(CleanupKind.Duplicate, copy, metadata, keeper, keeperSidecar)
                    }
                }
            }
        }
    }

    suspend fun remove(selected: List<CleanupItem>): CleanupResult = withContext(Dispatchers.IO) {
        var removed = 0
        var bytes = 0L
        for ((_, source, sidecar) in selected) {
            currentCoroutineContext().ensureActive()
            withContext(NonCancellable) {
                for ((file, sourceBytes) in listOfNotNull(source, sidecar)) {
                    file.delete()
                    removed++
                    bytes += sourceBytes
                }
            }
        }
        CleanupResult(removed, bytes)
    }

    private suspend fun digest(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().buffered().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                currentCoroutineContext().ensureActive()
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    /** Read known reference-bearing formats, including drafts and AtomicFile recovery files. */
    private suspend fun references(paths: MutableSet<String>): Boolean {
        app.getSharedPreferences("chat_draft", Context.MODE_PRIVATE).all.values.forEach { addReference(paths, it) }
        val preferences = File(app.applicationInfo.dataDir, "shared_prefs").canonicalFile
        for (file in walk(preferences)) {
            val name = file.name.removeSuffix(".bak")
            if (!name.endsWith(".xml")) continue
            // Validate/read disk too: getAll alone could hide unreadable preferences or recovery state.
            runCatching {
                file.inputStream().use { input ->
                    val parser = Xml.newPullParser()
                    parser.setInput(input, "UTF-8")
                    while (parser.eventType != XmlPullParser.END_DOCUMENT) {
                        if (parser.eventType == XmlPullParser.START_TAG && parser.name == "string") addReference(paths, parser.nextText())
                        parser.next()
                    }
                }
            }.onFailure { failure ->
                com.mrj.fancyai.util.AppLog.write(android.util.Log.WARN, "Cleanup", "Failed to parse shared_pref file ${file.path}: ${failure.message}")
            }
            app.getSharedPreferences(name.removeSuffix(".xml"), Context.MODE_PRIVATE).all.values.forEach { addReference(paths, it) }
        }
        var complete = true
        for (file in REFERENCE_DIRECTORIES.flatMap { walk(File(files, it)) }) {
            val name = file.name.removeSuffix(".bak").removeSuffix(".new")
            try {
                when {
                    name.endsWith(".json") -> addReference(paths, Json.parseToJsonElement(file.readText()))
                    name.endsWith(".properties") -> Properties().apply { file.inputStream().use(::load) }
                        .values.forEach { addReference(paths, it) }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                com.mrj.fancyai.util.AppLog.write(android.util.Log.WARN, "Cleanup", "Failed to parse reference file ${file.path}: ${failure.message}")
                complete = false
            }
        }
        return complete
    }

    private fun addReference(paths: MutableSet<String>, value: Any?) {
        when (value) {
            is JsonObject -> value.values.forEach { addReference(paths, it) }
            is JsonPrimitive -> addReference(paths, value.contentOrNull)
            is Iterable<*> -> value.forEach { addReference(paths, it) }
            is String -> {
                val path = if (value.startsWith("file://")) value.toUri().path.orEmpty() else value
                if (path.startsWith('/') || path.startsWith("media/") || path.startsWith("chat_attachments/")) {
                    val file = if (path.startsWith('/')) File(path) else File(files, path)
                    paths += file.canonicalPath
                }
                // A few preferences contain structured state; ordinary instruction text is left alone.
                if (value.startsWith('{') || value.startsWith('[')) {
                    addReference(paths, runCatching { Json.parseToJsonElement(value) }.getOrNull())
                }
            }
        }
    }

    @Suppress("DEPRECATION") // Android still exposes the calling app's own services.
    private fun activeCachePaths(): Set<String> {
        val active = mutableSetOf(
            File(cache, "image_service/aura-source.jpg").path,
            File(cache, "vision/input.jpg").path,
        )
        val services = app.getSystemService(ActivityManager::class.java).getRunningServices(Int.MAX_VALUE)
            .asSequence().filter { it.uid == Process.myUid() }.map { it.process.substringAfterLast(':') }.toSet()
        if (services.any { it in setOf("engine", "image", "vision", "voice") }) {
            active += app.codeCacheDir.canonicalPath
        }
        if ("image" in services) active += File(cache, "image_service").path
        if ("vision" in services) active += File(cache, "vision").path
        if ("engine" in services) {
            // LiteRT writes backend artifacts directly under cacheDir; MNN and
            // llama.cpp also own subdirectories there for the runtime's lifetime.
            cache.listFiles().orEmpty().filter {
                it.name !in setOf("image_service", "vision", "memory-multilingual-v1.zip") &&
                    !TEMP_NAME.matches(it.name)
            }.forEach { active += it.path }
        }
        if ("voice" in services) cache.listFiles().orEmpty().filter {
            it.name.startsWith("speech-") || it.name.startsWith("voice-")
        }.forEach { active += it.path }
        if (MemoryPackage.isInstalling) active += File(cache, "memory-multilingual-v1.zip").path
        // Includes in-process downloads, exports and audio playback. A closed
        // file left behind earlier in this session is immediately cleanable.
        File("/proc/self/fd").listFiles().orEmpty().forEach { descriptor ->
            runCatching { Files.readSymbolicLink(descriptor.toPath()).toString() }
                .getOrNull()?.takeIf { it.startsWith('/') }?.let { active += File(it).canonicalPath }
        }
        File("/proc/self/maps").useLines { lines ->
            lines.forEach { line ->
                val start = line.indexOf('/')
                if (start >= 0) active += line.substring(start).removeSuffix(" (deleted)")
            }
        }
        return active
    }

    private suspend fun walk(root: File): List<File> = withContext(Dispatchers.IO) {
        if (!root.exists()) return@withContext emptyList()
        val coroutineContext = currentCoroutineContext()
        Files.walk(root.toPath()).use { paths ->
            val result = mutableListOf<File>()
            paths.forEach { path ->
                coroutineContext.ensureActive()
                if (Files.isRegularFile(path, java.nio.file.LinkOption.NOFOLLOW_LINKS)) result += path.toFile()
            }
            result
        }
    }

    private companion object {
        val TEMP_NAME = Regex("voice-.*\\.audio|speech-.*\\.wav|character-card-.*\\.png|(?:group|y-comment)-.*\\.jpg")
        val ARCHIVE_PART = Regex("\\.aura-[0-9]+(?:-[0-9]+)?\\.(?:jpg|json)\\.part")
        val AURA_METADATA = Regex("aura-[0-9]+(?:-[0-9]+)?\\.json")
        val REFERENCE_DIRECTORIES = listOf("chats", "rebbit", "ustagram", "y", "groups", "characters", "character_draft", "user", "user_draft")
    }
}

internal class CleanupController(private val storage: CleanupStorage, private val scope: CoroutineScope) {
    var scan by mutableStateOf<CleanupScan?>(null)
    var busy by mutableStateOf(false)
    var deleting by mutableStateOf(false)
    var result by mutableStateOf<CleanupResult?>(null)
    var selected by mutableStateOf<Set<String>>(emptySet())
    var expanded by mutableStateOf<CleanupKind?>(null)
    var confirmation by mutableStateOf<List<CleanupItem>?>(null)
    var viewing by mutableStateOf<File?>(null)

    fun refresh(removing: List<CleanupItem>? = null) {
        busy = true
        deleting = removing != null
        result = null
        if (removing == null) {
            scan = null
            selected = emptySet()
        }
        scope.launch {
            try {
                if (removing != null) {
                    result = storage.remove(removing)
                    deleting = false
                }
                val refreshed = storage.scan()
                scan = refreshed
                selected = selected.intersect(refreshed.items.map { it.source.file.path }.toSet())
            } finally { busy = false; deleting = false }
        }
    }
}
