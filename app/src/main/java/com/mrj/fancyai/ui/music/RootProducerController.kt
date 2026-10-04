package com.mrj.fancyai.ui.music

import android.content.ContentValues
import android.content.Context
import android.content.SharedPreferences
import android.media.MediaPlayer
import android.os.Environment
import android.provider.MediaStore
import android.util.AtomicFile
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.edit
import com.mrj.fancyai.R
import com.mrj.fancyai.service.llm.CloudProvider
import com.mrj.fancyai.service.llm.LlmEngineClient
import com.mrj.fancyai.ui.settings.CloudSettingsStore
import com.mrj.fancyai.util.writeAtomicFile
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.io.IOException
import java.util.UUID
import java.util.concurrent.atomic.AtomicReference
import kotlin.time.Duration.Companion.milliseconds

private val producerJson = Json { ignoreUnknownKeys = true }

internal class RootProducerController(
    val app: Context,
    val scope: CoroutineScope,
) {
    val preferences: SharedPreferences = app.getSharedPreferences(ROOT_PRODUCER_PREFERENCES, Context.MODE_PRIVATE)
    val musicRuntime = RootProducerRuntime()
    val llmRuntime = AtomicReference<LlmEngineClient?>()
    val player = AtomicReference<MediaPlayer?>()

    var imagePath by mutableStateOf<String?>(null)
    var thoughtProcess by mutableStateOf(preferences.getString("thought_process", "").orEmpty())
        internal set
    var idea by mutableStateOf(preferences.getString(KEY_IDEA, "").orEmpty())
        internal set
    var title by mutableStateOf(preferences.getString(KEY_TITLE, "").orEmpty())
        internal set
    var brief by mutableStateOf(preferences.getString(KEY_BRIEF, "").orEmpty())
        internal set
    var lyrics by mutableStateOf(preferences.getString(KEY_LYRICS, "").orEmpty())
        internal set
    var tier by mutableStateOf(runCatching { RootMusicTier.valueOf(preferences.getString(KEY_TIER, "").orEmpty()) }.getOrDefault(RootMusicTier.CLIP))
        internal set
    var planReady by mutableStateOf(
        preferences.getBoolean(KEY_PLAN_READY, false) ||
            title.isNotBlank() || brief.isNotBlank() || lyrics.isNotBlank(),
    )
        internal set
    var tracks by mutableStateOf(emptyList<RootMusicTrack>())
        internal set
    var libraryLoading by mutableStateOf(value = true)
        internal set
    var drafting by mutableStateOf(value = false)
        internal set
    var generating by mutableStateOf(value = false)
        internal set
    var notice by mutableStateOf<String?>(null)
        internal set
    var activeTrackId by mutableStateOf<String?>(null)
        internal set
    var playbackLoading by mutableStateOf(value = false)
        internal set
    var playbackPlaying by mutableStateOf(value = false)
        internal set
    var playbackPosition by mutableLongStateOf(0)
        internal set
    var playbackDuration by mutableLongStateOf(0)
        internal set

    var draftRun by mutableIntStateOf(0)
    var musicRun by mutableIntStateOf(0)
    var draftJob: Job? = null
    var musicJob: Job? = null
    var playbackJob: Job? = null

    val openRouterKey: String
        get() = CloudSettingsStore.cloudApiKey(app, CloudProvider.OPENROUTER)

    fun updateIdea(value: String) {
        idea = value
        preferences.edit { putString(KEY_IDEA, value) }
    }

    fun updateTitle(value: String) {
        title = value
        preferences.edit { putString(KEY_TITLE, value) }
    }

    fun updateBrief(value: String) {
        brief = value
        preferences.edit { putString(KEY_BRIEF, value) }
    }

    fun updateLyrics(value: String) {
        lyrics = value
        preferences.edit { putString(KEY_LYRICS, value) }
    }

    fun updateTier(value: RootMusicTier) {
        tier = value
        preferences.edit { putString(KEY_TIER, value.name) }
    }

    fun dismissNotice() {
        notice = null
    }

    fun loadLibrary() {
        scope.launch {
            try {
                tracks = withContext(Dispatchers.IO) { RootMusicStore.list(app) }
            } finally {
                libraryLoading = false
            }
        }
    }

    fun stopDraft() {
        llmRuntime.get()?.cancel()
        draftJob?.cancel()
        draftJob = null
        drafting = false
    }

    fun cancelGeneration() {
        musicRun++
        musicRuntime.cancel()
        musicJob?.cancel()
        musicJob = null
        generating = false
    }



    fun export(track: RootMusicTrack) {
        scope.launch {
            RootMusicStore.export(app, track)
            notice = app.getString(R.string.producer_saved_music, app.getString(R.string.producer_export_directory))
        }
    }

    fun remove(track: RootMusicTrack) {
        if (activeTrackId == track.id) stopPlayback()
        scope.launch {
            withContext(Dispatchers.IO) {
                track.file.delete()
                File(track.file.parentFile, "${track.file.name}.json").delete()
            }
            tracks = tracks.filterNot { it.id == track.id }
            notice = app.getString(R.string.producer_removed_notice)
        }
    }

    fun play(track: RootMusicTrack) {
        val active = player.get()
        if ((activeTrackId == track.id) && (active != null)) {
            if (playbackLoading) return
            if (active.isPlaying) {
                active.pause()
                playbackJob?.cancel()
                playbackPlaying = false
                playbackPosition = active.currentPosition.toLong()
            } else {
                if ((playbackDuration > 0) && (playbackPosition >= (playbackDuration - 250))) active.seekTo(0)
                active.start()
                playbackPlaying = true
                trackProgress(track.id)
            }
            return
        }
        stopPlayback()
        activeTrackId = track.id
        playbackLoading = true
        val next = MediaPlayer()
        player.set(next)
        next.setOnPreparedListener { prepared ->
            if (player.get() !== prepared) return@setOnPreparedListener
            playbackLoading = false
            playbackDuration = prepared.duration.toLong().coerceAtLeast(0)
            prepared.start()
            playbackPlaying = true
            trackProgress(track.id)
        }
        next.setOnCompletionListener { completed ->
            if (player.get() !== completed) return@setOnCompletionListener
            playbackJob?.cancel()
            playbackPlaying = false
            playbackPosition = playbackDuration
        }
        next.setOnErrorListener { failed, _, _ ->
            if (player.get() === failed) stopPlayback()
            true
        }
        next.setDataSource(track.file.path)
        next.prepareAsync()
    }

    fun seek(trackId: String, position: Long) {
        if ((activeTrackId != trackId) || (playbackLoading)) return
        val active = player.get() ?: return
        val bounded = position.coerceIn(0, playbackDuration)
        active.seekTo(bounded.toInt())
        playbackPosition = bounded
    }

    private fun trackProgress(trackId: String) {
        playbackJob?.cancel()
        playbackJob = scope.launch {
            while ((currentCoroutineContext().isActive) && (activeTrackId == trackId)) {
                val active = player.get() ?: break
                val isPlaying = active.isPlaying
                playbackPlaying = isPlaying
                playbackPosition = active.currentPosition.toLong()
                if (!isPlaying) break
                delay(100.milliseconds)
            }
        }
    }

    fun stopPlayback() {
        playbackJob?.cancel()
        playbackJob = null
        player.getAndSet(null)?.release()
        activeTrackId = null
        playbackLoading = false
        playbackPlaying = false
        playbackPosition = 0
        playbackDuration = 0
    }

    fun close() {
        stopDraft()
        cancelGeneration()
        stopPlayback()
    }

    companion object {
        const val ROOT_PRODUCER_PREFERENCES = "root_producer"
        const val KEY_IDEA = "idea"
        const val KEY_TITLE = "title"
        const val KEY_BRIEF = "brief"
        const val KEY_LYRICS = "lyrics"
        const val KEY_TIER = "tier"
        const val KEY_PLAN_READY = "plan_ready"
        const val PRODUCER_PLAN_OUTPUT_TOKENS = 1_024
    }
}


internal class RootMusicPayload(
    val audio: ByteArray,
    val providerText: String,
    val format: RootAudioFormat,
)

internal object RootMusicStore {
    fun list(context: Context): List<RootMusicTrack> {
        return File(context.filesDir, MUSIC_DIRECTORY).listFiles { file -> file.isFile && file.name.endsWith(METADATA_SUFFIX) }.orEmpty()
            .asSequence()
            .map(::readTrack)
            .sortedByDescending(RootMusicTrack::createdAt)
            .toList()
    }

    fun save(
        context: Context,
        title: String,
        brief: String,
        lyrics: String,
        tier: RootMusicTier,
        payload: RootMusicPayload,
    ): RootMusicTrack {
        val directory = File(context.filesDir, MUSIC_DIRECTORY).apply { mkdirs() }
        val id = UUID.randomUUID().toString()
        val createdAt = System.currentTimeMillis()
        val priceUsd = context.getString(tier.priceLabel)
        val fileName = "root-$createdAt-${id.take(8)}.${payload.format.extension}"
        val file = File(directory, fileName)
        val sidecar = File(directory, "$fileName$METADATA_SUFFIX")
        val metadata = RootTrackMetadata(
            id = id,
            fileName = fileName,
            title = title,
            brief = brief,
            lyrics = lyrics,
            providerText = payload.providerText,
            tier = tier.name,
            model = tier.model,
            priceUsd = priceUsd,
            createdAt = createdAt,
            format = payload.format.name,
        )
        try {
            writeAtomicFile(file, payload.audio)
            writeAtomicFile(sidecar, producerJson.encodeToString(metadata).toByteArray(Charsets.UTF_8))
        } catch (failure: Throwable) {
            AtomicFile(file).delete()
            AtomicFile(sidecar).delete()
            throw failure
        }
        return RootMusicTrack(
            id = id,
            file = file,
            title = title,
            brief = brief,
            lyrics = lyrics,
            providerText = payload.providerText,
            tier = tier,
            model = tier.model,
            priceUsd = priceUsd,
            createdAt = createdAt,
            format = payload.format,
        )
    }

    suspend fun export(context: Context, track: RootMusicTrack) = withContext(Dispatchers.IO) {
        var uri: android.net.Uri? = null
        try {
            val safeTitle = track.title
                .replace(Regex("[^\\p{L}\\p{N} _-]"), "")
                .trim()
                .ifBlank { context.getString(R.string.producer_file_fallback) }
            val values = ContentValues().apply {
                put(
                    MediaStore.Audio.Media.DISPLAY_NAME,
                    "$safeTitle.${track.format.extension}",
                )
                put(MediaStore.Audio.Media.MIME_TYPE, track.format.mimeType)
                put(
                    MediaStore.Audio.Media.RELATIVE_PATH,
                    "${Environment.DIRECTORY_MUSIC}/$EXPORT_DIRECTORY",
                )
                put(MediaStore.Audio.Media.IS_PENDING, 1)
            }
            uri = context.contentResolver.insert(
                MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
                values,
            ) ?: throw IOException()
            context.contentResolver.openOutputStream(uri)?.use { output ->
                track.file.inputStream().use { input -> input.copyTo(output) }
            } ?: throw IOException()
            values.clear()
            values.put(MediaStore.Audio.Media.IS_PENDING, 0)
            context.contentResolver.update(uri, values, null, null)
        } catch (failure: Throwable) {
            uri?.let { context.contentResolver.delete(it, null, null) }
            throw failure
        }
    }

@Serializable
private data class RootTrackMetadata(
    val id: String = "",
    val fileName: String = "",
    val title: String = "",
    val brief: String = "",
    val lyrics: String = "",
    val providerText: String = "",
    val tier: String = "",
    val model: String = "",
    val priceUsd: String = "",
    val createdAt: Long = 0L,
    val format: String = "",
)

    private fun readTrack(sidecar: File): RootMusicTrack {
        val metadata = producerJson.decodeFromString<RootTrackMetadata>(sidecar.readText())
        val file = File(sidecar.parentFile, metadata.fileName)
        return RootMusicTrack(
            id = metadata.id,
            file = file,
            title = metadata.title,
            brief = metadata.brief,
            lyrics = metadata.lyrics,
            providerText = metadata.providerText,
            tier = runCatching { RootMusicTier.valueOf(metadata.tier) }.getOrDefault(RootMusicTier.CLIP),
            model = metadata.model,
            priceUsd = metadata.priceUsd,
            createdAt = metadata.createdAt,
            format = metadata.format
                .takeIf(String::isNotBlank)
                ?.let { stored -> runCatching { RootAudioFormat.valueOf(stored) }.getOrNull() }
                ?: RootAudioFormat.entries.firstOrNull {
                    it.extension.equals(file.extension, ignoreCase = true)
                }
                ?: RootAudioFormat.UNKNOWN,
        )
    }

    private const val MUSIC_DIRECTORY = "music"
    private const val EXPORT_DIRECTORY = "FancyAI"
    private const val METADATA_SUFFIX = ".json"
}

internal fun detectRootAudio(bytes: ByteArray): RootAudioFormat? = when {
    (bytes.size >= 12) &&
        (bytes.copyOfRange(0, 4).toString(Charsets.US_ASCII) == "RIFF") &&
        (bytes.copyOfRange(8, 12).toString(Charsets.US_ASCII) == "WAVE") -> RootAudioFormat.WAV
    (bytes.size >= 3) &&
        (bytes[0] == 'I'.code.toByte()) &&
        (bytes[1] == 'D'.code.toByte()) &&
        (bytes[2] == '3'.code.toByte()) -> RootAudioFormat.MP3
    (bytes.size >= 2) &&
        ((bytes[0].toInt() and 0xff) == 0xff) &&
        ((bytes[1].toInt() and 0xe0) == 0xe0) -> RootAudioFormat.MP3
    else -> null
}
