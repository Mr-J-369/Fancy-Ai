package com.mrj.fancyai.ui.settings

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.speech.SpeechRecognizer
import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.core.content.ContextCompat
import androidx.core.content.edit
import com.mrj.fancyai.R
import com.mrj.fancyai.service.llm.CloudProvider
import com.mrj.fancyai.service.voice.LocalVoicePack
import com.mrj.fancyai.service.voice.SavedVoice
import com.mrj.fancyai.service.voice.VoiceLibrary
import com.mrj.fancyai.service.voice.VoiceModelKind
import com.mrj.fancyai.service.voice.VoiceSttEngine
import com.mrj.fancyai.service.voice.VoiceTtsEngine
import com.mrj.fancyai.ui.aura.EnumSelector
import com.mrj.fancyai.voice.SttEngine
import com.mrj.fancyai.voice.TtsEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Duration.Companion.seconds

internal enum class VoiceProvider(
    val cloud: CloudProvider?,
    @param:StringRes val label: Int,
    @param:StringRes val recognitionSummary: Int,
    @param:StringRes val speechSummary: Int,
) {
    ANDROID(null, R.string.voice_android, R.string.voice_stt_summary, R.string.voice_tts_summary),
    LOCAL(null, R.string.voice_local, R.string.voice_local_summary, R.string.voice_local_summary),
    DEEPINFRA(CloudProvider.DEEPINFRA, R.string.cloud_deepinfra, R.string.voice_stt_summary_cloud, R.string.voice_tts_summary_cloud),
    OPENROUTER(CloudProvider.OPENROUTER, R.string.cloud_openrouter, R.string.voice_stt_summary_cloud, R.string.voice_tts_summary_cloud),
}

internal object VoiceSettingsStore {
    private const val PREFERENCES = "voice"
    private const val KEY_TEST_TEXT = "tts_text"

    fun provider(context: Context, kind: VoiceModelKind): VoiceProvider = context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
        .getString("${kind.key}_provider", null)
        ?.let { stored -> VoiceProvider.entries.firstOrNull { it.name == stored } }
        ?: VoiceProvider.ANDROID

    fun saveProvider(context: Context, kind: VoiceModelKind, provider: VoiceProvider) {
        context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE).edit { putString("${kind.key}_provider", provider.name) }
    }

    fun model(context: Context, kind: VoiceModelKind, provider: VoiceProvider): String =
        context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE).getString(modelKey(kind, provider), "").orEmpty()

    fun saveModel(
        context: Context,
        kind: VoiceModelKind,
        provider: VoiceProvider,
        model: String,
    ) {
        context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE).edit { putString(modelKey(kind, provider), model) }
    }

    fun modelQuery(context: Context, kind: VoiceModelKind, provider: VoiceProvider): String =
        context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE).getString(queryKey(kind, provider), "").orEmpty()

    fun saveModelQuery(
        context: Context,
        kind: VoiceModelKind,
        provider: VoiceProvider,
        query: String,
    ) {
        context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE).edit { putString(queryKey(kind, provider), query) }
    }

    fun voice(context: Context, provider: VoiceProvider, model: String): String =
        context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE).getString(voiceKey(provider, model), "").orEmpty().ifBlank {
            if (provider == VoiceProvider.LOCAL) when (model) {
                LocalVoicePack.KOKORO.id -> "auto"
                LocalVoicePack.SUPERTONIC.id -> "0"
                else -> ""
            } else ""
        }

    fun supertonicLanguage(context: Context): String {
        val language = context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE).getString("supertonic_language", null) ?: Locale.getDefault().language
        return language.takeIf { it in LocalVoicePack.supertonicLanguageCodes }.orEmpty()
    }

    fun saveSupertonicLanguage(context: Context, language: String) {
        context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE).edit { putString("supertonic_language", language) }
    }

    fun saveVoice(context: Context, provider: VoiceProvider, model: String, voice: String) {
        context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE).edit { putString(voiceKey(provider, model), voice) }
    }

    fun testText(context: Context, default: String): String =
        context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE).getString(KEY_TEST_TEXT, default).orEmpty()

    fun saveTestText(context: Context, text: String) {
        context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE).edit { putString(KEY_TEST_TEXT, text) }
    }

    fun playbackCharacters(context: Context): Int =
        context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE).getInt("playback_characters", 50).coerceIn(25, 250)

    fun savePlaybackCharacters(context: Context, value: Int) {
        context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE).edit { putInt("playback_characters", value.coerceIn(25, 250)) }
    }

    fun skipActions(context: Context): Boolean = context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE).getBoolean("playback_skip_actions", true)

    fun saveSkipActions(context: Context, value: Boolean) {
        context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE).edit { putBoolean("playback_skip_actions", value) }
    }

    fun apiKey(context: Context, provider: VoiceProvider): String =
        provider.cloud?.let { CloudSettingsStore.cloudApiKey(context, it) }.orEmpty()

    private fun modelKey(kind: VoiceModelKind, provider: VoiceProvider) =
        "${kind.key}_model@${provider.name}"

    private fun queryKey(kind: VoiceModelKind, provider: VoiceProvider) =
        "${kind.key}_query@${provider.name}"

    private fun voiceKey(provider: VoiceProvider, model: String) =
        "tts_voice@${provider.name}@${model.trim().removePrefix("models/")}"
}

internal object VoiceEngineFactory {
    fun stt(context: Context): SttEngine =
        VoiceSettingsStore.provider(context, VoiceModelKind.STT).let { provider ->
            VoiceSttEngine(
                context = context,
                provider = provider.name,
                apiKey = VoiceSettingsStore.apiKey(context, provider),
                model = VoiceSettingsStore.model(context, VoiceModelKind.STT, provider),
            )
        }

    fun tts(context: Context, characterId: String? = null): VoiceTtsEngine {
        val assigned = characterId?.let { VoiceLibrary.characterVoice(context, it) }
        return (if (assigned != null) VoiceProvider.LOCAL else
            VoiceSettingsStore.provider(context, VoiceModelKind.TTS)).let { provider ->
            val model = assigned?.model ?: VoiceSettingsStore.model(context, VoiceModelKind.TTS, provider)
            VoiceTtsEngine(
                context = context,
                provider = provider.name,
                apiKey = VoiceSettingsStore.apiKey(context, provider),
                model = model,
                voice = assigned?.id ?: VoiceSettingsStore.voice(context, provider, model),
            )
        }
    }

    fun sttAvailable(context: Context): Boolean {
        return when (val provider = VoiceSettingsStore.provider(context, VoiceModelKind.STT)) {
            VoiceProvider.ANDROID -> SpeechRecognizer.isRecognitionAvailable(context)
            VoiceProvider.LOCAL -> LocalVoicePack.ready(context, VoiceSettingsStore.model(context, VoiceModelKind.STT, provider))
            else -> VoiceSettingsStore.apiKey(context, provider).isNotBlank() &&
                VoiceSettingsStore.model(context, VoiceModelKind.STT, provider).isNotBlank()
        }
    }
}

internal object ProAccess {
    val charactersLock = Any()
    val songsLock = Mutex()
}

internal class PocketVoiceController(private val context: Context, private val scope: CoroutineScope) {
    val editable: Boolean get() = !working && !playing
    val idle: Boolean get() = editable && !recording
    val canPreview: Boolean get() = hasSample && !recording && !working
    val recorder = VoiceSttEngine(context, "SAMPLE", "", "")
    private val preview = AtomicReference<TtsEngine?>()
    private var draftName by mutableStateOf(context.getSharedPreferences("voice", Context.MODE_PRIVATE).getString("voice_draft_name", "").orEmpty())
    var name: String
        get() = draftName
        set(value) { draftName = value; context.getSharedPreferences("voice", Context.MODE_PRIVATE).edit { putString("voice_draft_name", value) } }
    var hasSample by mutableStateOf(File(context.filesDir, "voice/voices/draft.wav").isFile)
    var recording by mutableStateOf(value = false)
    var elapsed by mutableIntStateOf(0)
    var working by mutableStateOf(value = false)
    var playing by mutableStateOf(value = false)
    var error by mutableIntStateOf(0)
    var deleting by mutableStateOf(value = false)

    fun record(stopSpeech: () -> Unit) {
        stopSpeech()
        preview.getAndSet(null)?.release()
        elapsed = 0
        recording = true
        error = 0
        scope.launch {
            val timer = launch { while (true) { delay(1.seconds); elapsed++ } }
            try {
                if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                    error = R.string.voice_permission_denied
                    return@launch
                }
                withContext(Dispatchers.IO) { VoiceLibrary.discardDraft(context) }
                hasSample = false
                recorder.listen()
                hasSample = File(context.filesDir, "voice/voices/draft.wav").isFile
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) {
                hasSample = File(context.filesDir, "voice/voices/draft.wav").isFile
                error = R.string.voice_library_failed
            }
            finally { timer.cancel(); recording = false }
        }
    }

    fun importSample(uri: Uri, stopSpeech: () -> Unit) {
        working = true
        error = 0
        stopSpeech()
        preview.getAndSet(null)?.release()
        scope.launch {
            try { withContext(Dispatchers.IO) { VoiceLibrary.importDraft(context, uri) }; hasSample = true }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { error = R.string.voice_library_failed }
            finally { working = false }
        }
    }

    fun preview(sample: String, stopSpeech: () -> Unit) {
        if (playing) {
            preview.get()?.stop()
            return
        }
        stopSpeech()
        playing = true
        error = 0
        scope.launch {
            try {
                val engine = preview.get() ?: VoiceTtsEngine(context, "LOCAL", "", "pocket", "draft").also(preview::set)
                engine.speak(sample)
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { error = R.string.voice_tts_failed }
            finally { playing = false }
        }
    }

    fun save(onSaved: (SavedVoice) -> Unit) {
        working = true
        preview.getAndSet(null)?.release()
        scope.launch {
            try {
                val saved = withContext(Dispatchers.IO) { VoiceLibrary.save(context) }
                name = ""
                hasSample = false
                onSaved(saved)
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { error = R.string.voice_library_save_failed }
            finally { working = false }
        }
    }

    fun delete(selected: SavedVoice, stopSpeech: () -> Unit, onDeleted: () -> Unit) {
        stopSpeech()
        deleting = false
        working = true
        scope.launch {
            try { withContext(Dispatchers.IO) { VoiceLibrary.delete(context, selected) }; onDeleted() }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { error = R.string.voice_library_save_failed }
            finally { working = false }
        }
    }

    fun close() { recorder.release(); preview.getAndSet(null)?.release() }
}

@Composable
internal fun CharacterVoiceSetting(characterId: String) {
    val context = LocalContext.current
    val voices = remember(context) { VoiceLibrary.voices(context) }
    var selected by remember(context, characterId) {
        mutableStateOf(VoiceLibrary.characterVoice(context, characterId)?.key ?: "")
    }
    if (voices.isNotEmpty()) {
        EnumSelector(
            title = stringResource(R.string.voice_title),
            selected = voices.firstOrNull { it.key == selected }?.name
                ?: stringResource(R.string.voice_choose_model),
            options = listOf(
                "" to {
                    VoiceLibrary.assign(context, characterId, "")
                    selected = ""
                },
            ) + voices.map { voice ->
                voice.name to {
                    VoiceLibrary.assign(context, characterId, voice.key)
                    selected = voice.key
                }
            },
        )
    }
}
