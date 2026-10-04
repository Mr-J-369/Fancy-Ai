package com.mrj.fancyai.service.voice

import android.Manifest
import android.content.Context
import androidx.annotation.Keep
import androidx.annotation.RequiresPermission
import com.k2fsa.sherpa.onnx.GenerationConfig
import com.k2fsa.sherpa.onnx.OfflineModelConfig
import com.k2fsa.sherpa.onnx.OfflineRecognizer
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig
import com.k2fsa.sherpa.onnx.OfflineTts
import com.k2fsa.sherpa.onnx.OfflineTtsConfig
import com.k2fsa.sherpa.onnx.OfflineTtsKokoroModelConfig
import com.k2fsa.sherpa.onnx.OfflineTtsModelConfig
import com.k2fsa.sherpa.onnx.OfflineTtsPocketModelConfig
import com.k2fsa.sherpa.onnx.OfflineTtsSupertonicModelConfig
import com.k2fsa.sherpa.onnx.OfflineWhisperModelConfig
import com.k2fsa.sherpa.onnx.OnlineModelConfig
import com.k2fsa.sherpa.onnx.OnlineRecognizer
import com.k2fsa.sherpa.onnx.OnlineRecognizerConfig
import com.k2fsa.sherpa.onnx.OnlineTransducerModelConfig
import com.k2fsa.sherpa.onnx.WaveReader
import com.mrj.fancyai.voice.SttEngine
import com.mrj.fancyai.voice.TtsEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.text.BreakIterator
import java.util.Locale
import java.util.UUID
import java.util.concurrent.CancellationException
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.cancel as cancelScope

internal class LocalSttEngine(private val context: Context, private val model: String) : SttEngine {
    private val lock = Mutex()
    private val cleanup = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val recorder = VoiceAudioRecorder()
    private val stopped = AtomicBoolean(false)
    private val released = AtomicBoolean(false)
    private val text = MutableStateFlow("")
    private var whisper: OfflineRecognizer? = null
    private var streaming: OnlineRecognizer? = null
    override val partial: StateFlow<String> = text

    @RequiresPermission(Manifest.permission.RECORD_AUDIO)
    override suspend fun listen(): String = lock.withLock {
        withContext(Dispatchers.IO) {
            check(!released.get())
            stopped.set(false)
            text.value = ""
            val folder = File(context.filesDir, "voice/models/${LocalVoicePack.WHISPER.directory}")
            val finalPass = whisper ?: OfflineRecognizer(
                config = OfflineRecognizerConfig(
                    modelConfig = OfflineModelConfig(
                        whisper = OfflineWhisperModelConfig(
                            encoder = File(folder, "tiny-encoder.int8.onnx").absolutePath,
                            decoder = File(folder, "tiny-decoder.int8.onnx").absolutePath,
                            language = if (model == LocalVoicePack.ZIPFORMER.id) "en" else "",
                            task = "transcribe",
                        ),
                        tokens = File(folder, "tiny-tokens.txt").absolutePath, numThreads = 2, modelType = "whisper",
                    ),
                ),
            ).also { whisper = it }
            val online = if (model == LocalVoicePack.ZIPFORMER.id) {
                streaming ?: createStreaming().also { streaming = it }
            } else null
            val stream = online?.createStream()
            try {
                val wav = recorder.record { frame ->
                    if (stopped.get()) throw CancellationException("Speech input stopped")
                    if ((online != null) && (stream != null)) {
                        stream.acceptWaveform(frame, 16000)
                        while (online.isReady(stream)) {
                            if (stopped.get()) throw CancellationException("Speech input stopped")
                            online.decode(stream)
                        }
                        text.value = online.getResult(stream).text.trim()
                    }
                }
                coroutineContext.ensureActive()
                if (stopped.get()) throw CancellationException("Speech input stopped")
                if (wav.isEmpty()) return@withContext ""
                val samples = FloatArray((wav.size - 44) / 2) { index ->
                    val low = wav[44 + index * 2].toInt() and 255
                    val high = wav[45 + index * 2].toInt()
                    ((high shl 8) or low).toShort() / 32768f
                }
                val finalStream = finalPass.createStream()
                try {
                    finalStream.acceptWaveform(samples, 16000)
                    finalPass.decode(finalStream)
                    coroutineContext.ensureActive()
                    if (stopped.get()) throw CancellationException("Speech input stopped")
                    finalPass.getResult(finalStream).text.trim()
                } finally { finalStream.release() }
            } finally { stream?.release() }
        }
    }

    private fun createStreaming(): OnlineRecognizer {
        val folder = File(context.filesDir, "voice/models/${LocalVoicePack.ZIPFORMER.directory}")
        return OnlineRecognizer(config = OnlineRecognizerConfig(modelConfig = OnlineModelConfig(
            transducer = OnlineTransducerModelConfig(
                encoder = File(folder, "encoder-epoch-99-avg-1.int8.onnx").absolutePath,
                decoder = File(folder, "decoder-epoch-99-avg-1.int8.onnx").absolutePath,
                joiner = File(folder, "joiner-epoch-99-avg-1.int8.onnx").absolutePath,
            ), tokens = File(folder, "tokens.txt").absolutePath, numThreads = 2, modelType = "zipformer",
        )))
    }

    override fun cancel() { stopped.set(true); recorder.cancel() }
    override fun release() {
        if (!released.compareAndSet(false, true)) return
        cancel()
        cleanup.launch {
            try { lock.withLock { streaming?.release(); whisper?.release(); streaming = null; whisper = null } }
            finally { cleanup.cancelScope() }
        }
    }
}

internal class LocalTtsEngine(
    private val context: Context,
    private val model: String,
    private val voice: String,
) : TtsEngine {
    private val lock = Mutex()
    private val cleanup = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val player = VoiceAudioPlayer(context)
    private val stopped = AtomicBoolean(false)
    private val released = AtomicBoolean(false)
    private var synthesizer: OfflineTts? = null

    override suspend fun speak(text: String) = lock.withLock {
        withContext(Dispatchers.IO) {
            check(!released.get())
            stopped.set(false)
            val (tts, config) = configure(text)
            coroutineScope {
                // Rendezvous hands off one file while the producer prepares the next sentence.
                val audioQueue = Channel<File>(Channel.RENDEZVOUS) { it.delete() }
                val producer = launch {
                    try {
                        val iterator = BreakIterator.getSentenceInstance(Locale.getDefault()).apply { setText(text) }
                        var start = iterator.first()
                        var end = iterator.next()
                        while (end != BreakIterator.DONE) {
                            coroutineContext.ensureActive()
                            if (stopped.get()) throw CancellationException("Speech output stopped")
                            val sentence = text.substring(start, end).trim()
                            if (sentence.isNotEmpty()) {
                                val coroutine = coroutineContext
                                val audio = tts.generateWithConfigAndCallback(sentence, config,
                                    VoiceGenerationCallback { !stopped.get() && coroutine[kotlinx.coroutines.Job]!!.isActive })
                                coroutineContext.ensureActive()
                                if (stopped.get()) throw CancellationException("Speech output stopped")
                                val file = File(context.cacheDir, "speech-${UUID.randomUUID()}.wav")
                                try {
                                    check(audio.samples.isNotEmpty() && audio.save(file.absolutePath))
                                    audioQueue.send(file)
                                } catch (failure: Throwable) {
                                    file.delete()
                                    throw failure
                                }
                            }
                            start = end
                            end = iterator.next()
                        }
                    } finally { audioQueue.close() }
                }
                try {
                    for (file in audioQueue) {
                        try {
                            coroutineContext.ensureActive()
                            if (stopped.get()) throw CancellationException("Speech output stopped")
                            player.play(file)
                        } finally { file.delete() }
                    }
                } finally {
                    producer.cancel()
                    audioQueue.cancel()
                }
            }
        }
    }

    private fun configure(text: String): Pair<OfflineTts, GenerationConfig> {
        val pack = LocalVoicePack.entries.first { it.id == model }
        if (voice == "draft") {
            synthesizer?.free()
            synthesizer = null
        }
        val tts = synthesizer ?: run {
            val folder = File(context.filesDir, "voice/models/${pack.directory}")
            val config = when (pack) {
                LocalVoicePack.POCKET -> OfflineTtsModelConfig(pocket = OfflineTtsPocketModelConfig(
                    lmFlow = File(folder, "lm_flow.int8.onnx").absolutePath, lmMain = File(folder, "lm_main.int8.onnx").absolutePath,
                    encoder = File(folder, "encoder.onnx").absolutePath, decoder = File(folder, "decoder.int8.onnx").absolutePath,
                    textConditioner = File(folder, "text_conditioner.onnx").absolutePath, vocabJson = File(folder, "vocab.json").absolutePath,
                    tokenScoresJson = File(folder, "token_scores.json").absolutePath, voiceEmbeddingCacheCapacity = 1,
                ), numThreads = 2)
                LocalVoicePack.KOKORO -> OfflineTtsModelConfig(kokoro = OfflineTtsKokoroModelConfig(
                    model = File(folder, "model.onnx").absolutePath, voices = File(folder, "voices.bin").absolutePath, tokens = File(folder, "tokens.txt").absolutePath,
                    dataDir = File(folder, "espeak-ng-data").absolutePath, lexicon = File(folder, "lexicon-us-en.txt").absolutePath + "," + File(folder, "lexicon-zh.txt").absolutePath,
                ), numThreads = 2)
                LocalVoicePack.SUPERTONIC -> OfflineTtsModelConfig(supertonic = OfflineTtsSupertonicModelConfig(
                    durationPredictor = File(folder, "duration_predictor.int8.onnx").absolutePath, textEncoder = File(folder, "text_encoder.int8.onnx").absolutePath,
                    vectorEstimator = File(folder, "vector_estimator.int8.onnx").absolutePath, vocoder = File(folder, "vocoder.int8.onnx").absolutePath,
                    ttsJson = File(folder, "tts.json").absolutePath, unicodeIndexer = File(folder, "unicode_indexer.bin").absolutePath, voiceStyle = File(folder, "voice.bin").absolutePath,
                ), numThreads = 2)
                else -> error("Not a speech output pack")
            }
            OfflineTts(config = OfflineTtsConfig(model = config))
        }.also { synthesizer = it }
        val config = when (pack) {
            LocalVoicePack.POCKET -> {
                require(voice == "draft" || runCatching { UUID.fromString(voice) }.isSuccess)
                val reference = File(context.filesDir, "voice/voices/$voice.wav")
                val wave = WaveReader.readWave(reference.absolutePath)
                require(wave.samples.isNotEmpty() && wave.sampleRate > 0)
                GenerationConfig(referenceAudio = wave.samples, referenceSampleRate = wave.sampleRate)
            }
            LocalVoicePack.KOKORO -> {
                val sid = if (voice == "auto") {
                    if (text.any { Character.UnicodeScript.of(it.code) == Character.UnicodeScript.HAN } || Locale.getDefault().language == "zh") 45 else 3
                } else requireNotNull(voice.toIntOrNull())
                require(sid in 0 until tts.numSpeakers())
                GenerationConfig(sid = sid)
            }
            LocalVoicePack.SUPERTONIC -> {
                val sid = requireNotNull(voice.substringBefore('@').toIntOrNull())
                val language = voice.substringAfter('@', "")
                require(sid in 0 until tts.numSpeakers())
                require(language in LocalVoicePack.supertonicLanguageCodes)
                GenerationConfig(sid = sid, numSteps = 8, extra = mapOf("lang" to language))
            }
            else -> error("Not a speech output pack")
        }
        return tts to config
    }

    override fun stop() { stopped.set(true); player.stop() }
    override fun release() {
        if (!released.compareAndSet(false, true)) return
        stop()
        cleanup.launch {
            try { lock.withLock { synthesizer?.free(); synthesizer = null; player.release() } }
            finally { cleanup.cancelScope() }
        }
    }
}

/** JNI looks up invoke(float[]) directly; preserve this typed method in minified builds. */
@Keep
internal class VoiceGenerationCallback(private val active: () -> Boolean) : (FloatArray) -> Int {
    override fun invoke(samples: FloatArray): Int = if (active()) 1 else 0
}
