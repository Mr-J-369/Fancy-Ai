package com.mrj.fancyai.ui.settings

import android.Manifest
import android.content.Context
import android.speech.SpeechRecognizer
import androidx.annotation.RequiresPermission
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.mrj.fancyai.R
import com.mrj.fancyai.service.voice.CloudVoiceModel
import com.mrj.fancyai.service.voice.CloudVoiceRuntime
import com.mrj.fancyai.service.voice.LocalVoiceDownloads
import com.mrj.fancyai.service.voice.LocalVoicePack
import com.mrj.fancyai.service.voice.VoiceModelKind
import com.mrj.fancyai.voice.SttEngine
import com.mrj.fancyai.voice.TtsEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.concurrent.CancellationException
import java.util.concurrent.atomic.AtomicReference

/** Owns Voice settings persistence, catalog requests, and test-engine lifetimes. */
internal class VoiceSettingsController(
    private val context: Context,
    private val scope: CoroutineScope,
    defaultSample: String,
) {
    private val catalogRuntime = CloudVoiceRuntime()
    private val silentPartial = MutableStateFlow("")
    val activeStt = AtomicReference<SttEngine?>()
    private val activeTts = AtomicReference<TtsEngine?>()
    val stt = VoiceSelection(context, VoiceModelKind.STT)
    val tts = VoiceSelection(context, VoiceModelKind.TTS)
    var ttsVoice by mutableStateOf(VoiceSettingsStore.voice(context, tts.provider, tts.model))
    var sample by mutableStateOf(VoiceSettingsStore.testText(context, defaultSample))
    var picker by mutableStateOf<VoiceModelKind?>(null)
    var catalogLoading by mutableStateOf<VoiceModelKind?>(null)
    var catalogError by mutableIntStateOf(0)
    var partialFlow by mutableStateOf<StateFlow<String>>(silentPartial)
    var listening by mutableStateOf(value = false)
    var transcript by mutableStateOf<String?>(null)
    var speaking by mutableStateOf(value = false)
    var sttRun by mutableIntStateOf(0)
    var ttsRun by mutableIntStateOf(0)
    var catalogRun by mutableIntStateOf(0)
    var localRevision by mutableIntStateOf(0)
    var playbackCharacters by mutableIntStateOf(VoiceSettingsStore.playbackCharacters(context))
    var skipActions by mutableStateOf(VoiceSettingsStore.skipActions(context))
    val recognitionAvailable = SpeechRecognizer.isRecognitionAvailable(context)
    val sttAvailable: Boolean
        get() = when (stt.provider) {
            VoiceProvider.ANDROID -> recognitionAvailable
            VoiceProvider.LOCAL -> LocalVoicePack.ready(context, stt.model)
            else -> VoiceSettingsStore.apiKey(context, stt.provider).isNotBlank() && stt.model.isNotBlank()
        }
    val ttsLanguageReady: Boolean
        get() = tts.model != LocalVoicePack.SUPERTONIC.id || VoiceSettingsStore.supertonicLanguage(context).isNotBlank()
    val ttsAvailable: Boolean
        get() = tts.provider == VoiceProvider.ANDROID ||
            (tts.provider == VoiceProvider.LOCAL && LocalVoicePack.ready(context, tts.model) && ttsVoice.isNotBlank() && ttsLanguageReady) ||
            (VoiceSettingsStore.apiKey(context, tts.provider).isNotBlank() && tts.model.isNotBlank())

    fun savePlaybackCharacters(value: Int) {
        playbackCharacters = value
        VoiceSettingsStore.savePlaybackCharacters(context, value)
    }


    fun stopTests() {
        sttRun++
        ttsRun++
        activeStt.getAndSet(null)?.release()
        activeTts.getAndSet(null)?.release()
        listening = false
        speaking = false
        partialFlow = silentPartial
    }

    fun selectProvider(kind: VoiceModelKind, provider: VoiceProvider) {
        stopTests()
        catalogRun++
        catalogRuntime.cancel()
        picker = null
        catalogLoading = null
        catalogError = 0
        val selection = if (kind == VoiceModelKind.STT) stt else tts
        VoiceSettingsStore.saveProvider(context, kind, provider)
        selection.provider = provider
        selection.model = VoiceSettingsStore.model(context, kind, provider)
        selection.query = VoiceSettingsStore.modelQuery(context, kind, provider)
        selection.models = emptyList()
        selection.error = 0
        if (kind == VoiceModelKind.STT) transcript = null
        else ttsVoice = VoiceSettingsStore.voice(context, provider, selection.model)
    }

    fun setModel(kind: VoiceModelKind, value: String) {
        val selection = if (kind == VoiceModelKind.STT) stt else tts
        VoiceSettingsStore.saveModel(context, kind, selection.provider, value)
        selection.model = value
        selection.error = 0
        if (kind == VoiceModelKind.TTS) ttsVoice = VoiceSettingsStore.voice(context, selection.provider, value)
    }

    fun fetchModels(kind: VoiceModelKind) {
        val selection = if (kind == VoiceModelKind.STT) stt else tts
        val provider = selection.provider
        val cloud = provider.cloud ?: return
        val apiKey = VoiceSettingsStore.apiKey(context, provider)
        if (apiKey.isBlank()) {
            catalogError = R.string.cloud_key_required
            return
        }
        catalogRuntime.cancel()
        val run = catalogRun + 1
        catalogRun = run
        catalogLoading = kind
        catalogError = 0
        scope.launch {
            try {
                val models = catalogRuntime.fetchModels(cloud, apiKey, kind)
                if ((run != catalogRun) || (selection.provider != provider)) return@launch
                selection.models = models
                catalogError = if (models.isEmpty()) R.string.voice_cloud_models_empty else 0
            } catch (cancelled: CancellationException) {
                if (!currentCoroutineContext().isActive) throw cancelled
            } catch (failure: Exception) {
                if (run == catalogRun) {
                    catalogError = voiceErrorResource(failure, R.string.cloud_models_failed)
                }
            } finally {
                if ((run == catalogRun) && (catalogLoading == kind)) catalogLoading = null
            }
        }
    }

    fun openPicker(kind: VoiceModelKind) {
        picker = kind
        catalogError = 0
        val models = if (kind == VoiceModelKind.STT) stt.models else tts.models
        if (models.isEmpty()) fetchModels(kind)
    }

    fun dismissCatalog() {
        picker = null
        catalogError = 0
    }

    @RequiresPermission(Manifest.permission.RECORD_AUDIO)
    fun listen() {
        if (listening) return
        val engine = VoiceEngineFactory.stt(context)
        val run = sttRun + 1
        sttRun = run
        activeStt.getAndSet(engine)?.release()
        partialFlow = engine.partial
        listening = true
        transcript = null
        stt.error = 0
        scope.launch {
            try {
                val result = engine.listen()
                if (run == sttRun) transcript = result
            } catch (cancelled: CancellationException) {
                if (!currentCoroutineContext().isActive) throw cancelled
            } catch (failure: Exception) {
                if (run == sttRun) {
                    stt.error = voiceErrorResource(failure, R.string.voice_stt_failed)
                }
            } finally {
                if ((run == sttRun) && activeStt.compareAndSet(engine, null)) {
                    engine.release()
                    partialFlow = silentPartial
                    listening = false
                }
            }
        }
    }

    fun testSpeech() {
        if (speaking) return
        val run = ttsRun + 1
        ttsRun = run
        speaking = true
        tts.error = 0
        scope.launch {
            var engine: TtsEngine? = null
            try {
                engine = VoiceEngineFactory.tts(context)
                if (run != ttsRun) return@launch
                activeTts.getAndSet(engine)?.release()
                engine.speak(sample)
            } catch (cancelled: CancellationException) {
                if (!currentCoroutineContext().isActive) throw cancelled
            } catch (failure: Exception) {
                if (run == ttsRun) {
                    tts.error = voiceErrorResource(failure, R.string.voice_tts_failed)
                }
            } finally {
                engine?.let { current ->
                    if (activeTts.compareAndSet(current, null)) current.release()
                }
                if (run == ttsRun) speaking = false
            }
        }
    }

    fun close() {
        catalogRuntime.cancel()
        activeStt.getAndSet(null)?.release()
        activeTts.getAndSet(null)?.release()
    }
}

/** Independent saved provider/model selection for recognition and speech. */
internal class VoiceSelection(context: Context, kind: VoiceModelKind) {
    var provider by mutableStateOf(VoiceSettingsStore.provider(context, kind))
    var model by mutableStateOf(VoiceSettingsStore.model(context, kind, provider))
    var query by mutableStateOf(VoiceSettingsStore.modelQuery(context, kind, provider))
    var models by mutableStateOf(emptyList<CloudVoiceModel>())
    var error by mutableIntStateOf(0)
}

/** Owns local voice pack installation, removal, and operation state. */
internal class LocalVoiceController(private val context: Context, private val scope: CoroutineScope) {
    val downloads = LocalVoiceDownloads()
    var downloading by mutableStateOf<LocalVoicePack?>(null)
    var downloadJob by mutableStateOf<Job?>(null)
    var progress by mutableFloatStateOf(0f)
    var extracting by mutableStateOf(value = false)
    var activeTitle by mutableIntStateOf(R.string.voice_pack_whisper)
    var error by mutableIntStateOf(0)
    var deleting by mutableStateOf<LocalVoicePack?>(null)
    var revision by mutableIntStateOf(0)
    val busy: Boolean get() = downloading != null || deleting != null

    fun select(pack: LocalVoicePack, installed: Boolean, needed: List<LocalVoicePack>, stopSpeech: () -> Unit, onModel: (String) -> Unit, onChanged: () -> Unit) {
        stopSpeech()
        error = 0
        if (installed) {
            onModel(pack.id)
            return
        }
        downloading = pack
        progress = 0f
        extracting = false
        activeTitle = pack.title
        downloadJob = scope.launch {
            try {
                needed.forEach { dependency ->
                    activeTitle = dependency.title
                    progress = 0f
                    extracting = false
                    downloads.install(context, dependency) { fraction, preparing ->
                        extracting = preparing
                        progress = fraction
                    }
                }
                onModel(pack.id)
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { error = R.string.voice_pack_failed }
            finally {
                revision++
                onChanged()
                downloading = null
                downloadJob = null
            }
        }
    }

    fun remove(pack: LocalVoicePack, model: String, stopSpeech: () -> Unit, onModel: (String) -> Unit, onChanged: () -> Unit) {
        deleting = pack
        error = 0
        stopSpeech()
        if (model == pack.id || (pack == LocalVoicePack.WHISPER && model == LocalVoicePack.ZIPFORMER.id)) onModel("")
        scope.launch {
            try {
                downloads.delete(context, pack)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                error = R.string.voice_pack_delete_failed
            } finally {
                revision++
                onChanged()
                deleting = null
            }
        }
    }
}
