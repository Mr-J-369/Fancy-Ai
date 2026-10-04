package com.mrj.fancyai.service.voice

import android.Manifest
import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaPlayer
import android.media.MediaRecorder
import android.os.Handler
import android.os.Looper
import androidx.annotation.RequiresPermission
import com.mrj.fancyai.service.llm.CloudProvider
import com.mrj.fancyai.voice.SttEngine
import com.mrj.fancyai.voice.TtsEngine
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.UUID
import java.util.concurrent.CancellationException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.math.sqrt

internal class CloudSttEngine(
    private val provider: CloudProvider,
    private val apiKey: String,
    private val model: String,
) : SttEngine {
    private val mutablePartial = MutableStateFlow("")
    private val listenLock = Mutex()
    private val recorder = VoiceAudioRecorder()
    private val runtime = CloudVoiceRuntime()
    @Volatile private var released = false

    override val partial: StateFlow<String> = mutablePartial

    @RequiresPermission(Manifest.permission.RECORD_AUDIO)
    override suspend fun listen(): String = listenLock.withLock {
        check(!released)
        mutablePartial.value = ""
        val wav = recorder.record()
        if (wav.isEmpty()) "" else runtime.transcribe(provider, apiKey, model, wav)
    }

    override fun cancel() {
        recorder.cancel()
        runtime.cancel()
    }

    override fun release() {
        if (released) return
        released = true
        cancel()
    }
}

internal class CloudTtsEngine(
    context: Context,
    private val provider: CloudProvider,
    private val apiKey: String,
    private val model: String,
    private val voice: String,
) : TtsEngine {
    private val speechLock = Mutex()
    private val runtime = CloudVoiceRuntime()
    private val player = VoiceAudioPlayer(context.applicationContext)
    @Volatile private var released = false

    override suspend fun speak(text: String) {
        val spoken = text.trim()
        if (spoken.isEmpty()) return
        speechLock.withLock {
            check(!released)
            val audio = runtime.synthesize(provider, apiKey, model, voice, spoken)
            check(!released)
            player.play(audio)
        }
    }

    override fun stop() {
        runtime.cancel()
        player.stop()
    }

    override fun release() {
        if (released) return
        released = true
        runtime.cancel()
        player.release()
    }
}

internal class VoiceAudioRecorder {
    private val active = AtomicReference<Recording?>()

    @RequiresPermission(Manifest.permission.RECORD_AUDIO)
    suspend fun record(
        manual: Boolean = false,
        onFrame: (FloatArray) -> Unit = {},
    ): ByteArray = withContext(Dispatchers.IO) {
        val frameSamples = (CLOUD_SAMPLE_RATE * CLOUD_FRAME_MILLIS) / 1_000
        val minimumBuffer = AudioRecord.getMinBufferSize(
            CLOUD_SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
        )
        check(minimumBuffer > 0)
        val recorder = AudioRecord(
            MediaRecorder.AudioSource.VOICE_RECOGNITION,
            CLOUD_SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
            maxOf(minimumBuffer, frameSamples * Short.SIZE_BYTES),
        )
        check(recorder.state == AudioRecord.STATE_INITIALIZED) {
            recorder.release()
            "Audio recorder could not initialize"
        }
        val recording = Recording(recorder)
        check(active.compareAndSet(null, recording)) {
            recorder.release()
            "Voice input is already active"
        }
        val pcm = ByteArrayOutputStream()
        val frame = ShortArray(frameSamples)
        val endpointer = Endpointer()
        var heardSpeech = true
        try {
            recorder.startRecording()
            check(recorder.recordingState == AudioRecord.RECORDSTATE_RECORDING)
            while (true) {
                coroutineContext.ensureActive()
                if (recording.finished.get()) break
                val count = recorder.read(frame, 0, frame.size)
                if (recording.cancelled.get()) throw CancellationException("Cloud STT cancelled")
                if (count < 0) error("Audio recording failed ($count)")
                if (count == 0) continue
                writePcm16(pcm, frame, count)
                onFrame(FloatArray(count) { frame[it] / 32768f })
                if (manual) {
                    if (pcm.size() >= CLOUD_SAMPLE_RATE * Short.SIZE_BYTES * 15) break
                } else when (endpointer.accept(rms(frame, count))) {
                    Endpointer.Decision.CONTINUE -> Unit
                    Endpointer.Decision.END -> break
                    Endpointer.Decision.EMPTY -> {
                        heardSpeech = false
                        break
                    }
                }
            }
        } finally {
            active.compareAndSet(recording, null)
            runCatching {
                if (recorder.recordingState == AudioRecord.RECORDSTATE_RECORDING) recorder.stop()
            }
            recorder.release()
        }
        if (!heardSpeech || (pcm.size() == 0)) ByteArray(0) else pcm16Wav(pcm.toByteArray())
    }

    fun cancel() {
        active.get()?.let { recording ->
            recording.cancelled.set(true)
            runCatching {
                if (recording.recorder.recordingState == AudioRecord.RECORDSTATE_RECORDING) {
                    recording.recorder.stop()
                }
            }
        }
    }

    fun finish() {
        active.get()?.finished?.set(true)
    }

    fun rms(samples: ShortArray, count: Int): Double {
        var sum = 0.0
        repeat(count) { index ->
            val sample = samples[index].toDouble()
            sum += sample * sample
        }
        return sqrt(sum / count)
    }

    fun writePcm16(output: ByteArrayOutputStream, samples: ShortArray, count: Int) {
        repeat(count) { index ->
            val sample = samples[index].toInt()
            output.write(sample and 0xff)
            output.write((sample ushr 8) and 0xff)
        }
    }

    fun pcm16Wav(pcm: ByteArray): ByteArray = ByteArrayOutputStream(44 + pcm.size).apply {
        write("RIFF".toByteArray(Charsets.US_ASCII))
        writeInt32(36 + pcm.size)
        write("WAVEfmt ".toByteArray(Charsets.US_ASCII))
        writeInt32(16)
        writeInt16(1)
        writeInt16(1)
        writeInt32(CLOUD_SAMPLE_RATE)
        writeInt32(CLOUD_SAMPLE_RATE * Short.SIZE_BYTES)
        writeInt16(Short.SIZE_BYTES)
        writeInt16(Short.SIZE_BITS)
        write("data".toByteArray(Charsets.US_ASCII))
        writeInt32(pcm.size)
        write(pcm)
    }.toByteArray()

    fun ByteArrayOutputStream.writeInt16(value: Int) {
        write(value and 0xff)
        write((value ushr 8) and 0xff)
    }

    fun ByteArrayOutputStream.writeInt32(value: Int) {
        writeInt16(value)
        writeInt16(value ushr 16)
    }

    private class Recording(val recorder: AudioRecord) {
        val cancelled = AtomicBoolean(false)
        val finished = AtomicBoolean(false)
    }

    private class Endpointer {
        private var elapsed = 0
        private var trailingSilence = 0
        private var speechStarted = false

        fun accept(level: Double): Decision {
            elapsed += CLOUD_FRAME_MILLIS
            if (level >= CLOUD_SILENCE_THRESHOLD) {
                speechStarted = true
                trailingSilence = 0
            } else if (speechStarted) {
                trailingSilence += CLOUD_FRAME_MILLIS
            }
            return when {
                !speechStarted && (elapsed >= CLOUD_NO_SPEECH_TIMEOUT_MILLIS) -> Decision.EMPTY
                speechStarted && (trailingSilence >= CLOUD_TRAILING_SILENCE_MILLIS) -> Decision.END
                speechStarted && (elapsed >= CLOUD_MAX_UTTERANCE_MILLIS) -> Decision.END
                else -> Decision.CONTINUE
            }
        }

        enum class Decision { CONTINUE, END, EMPTY }
    }

}

internal class VoiceSampleEngine(private val context: Context) : SttEngine {
    private val recorder = VoiceAudioRecorder()
    override val partial: StateFlow<String> = MutableStateFlow("")

    @RequiresPermission(Manifest.permission.RECORD_AUDIO)
    override suspend fun listen(): String {
        val wav = recorder.record(manual = true)
        withContext(Dispatchers.IO) { VoiceLibrary.writeDraft(context, wav) }
        return ""
    }

    fun finish() = recorder.finish()
    override fun cancel() = recorder.cancel()
    override fun release() = cancel()
}

private const val CLOUD_SAMPLE_RATE = 16_000
private const val CLOUD_FRAME_MILLIS = 20
private const val CLOUD_SILENCE_THRESHOLD = 500.0
private const val CLOUD_TRAILING_SILENCE_MILLIS = 1_200
private const val CLOUD_MAX_UTTERANCE_MILLIS = 15_000
private const val CLOUD_NO_SPEECH_TIMEOUT_MILLIS = 8_000

internal class VoiceAudioPlayer(private val context: Context) {
    private val mainHandler = Handler(Looper.getMainLooper())
    private var active: Playback? = null
    @Volatile private var released = false

    suspend fun play(audio: ByteArray) {
        val file = File(context.cacheDir, "voice-${UUID.randomUUID()}.audio")
        try {
            withContext(Dispatchers.IO) { file.writeBytes(audio) }
            play(file)
        } finally {
            withContext(NonCancellable + Dispatchers.IO) { file.delete() }
        }
    }

    suspend fun play(file: File) {
        withContext(Dispatchers.Main.immediate) {
            check(!released)
            check(active == null)
            suspendCancellableCoroutine { continuation ->
                val playback = Playback(MediaPlayer(), continuation)
                active = playback
                continuation.invokeOnCancellation {
                    mainHandler.post { cleanup(playback, stopFirst = true) }
                }
                playback.player.setOnPreparedListener { prepared ->
                    runCatching(prepared::start).onFailure { failure ->
                        finish(playback, failure)
                    }
                }
                playback.player.setOnCompletionListener {
                    finish(playback, null)
                }
                playback.player.setOnErrorListener { _, what, extra ->
                    finish(playback, IllegalStateException("Audio playback failed ($what/$extra)"))
                    true
                }
                runCatching {
                    playback.player.setDataSource(file.absolutePath)
                    playback.player.prepareAsync()
                }.onFailure { failure ->
                    finish(playback, failure)
                }
            }
        }
    }

    fun stop() {
        mainHandler.post {
            active?.let { playback ->
                playback.continuation.cancel(CancellationException("Cloud TTS stopped"))
                cleanup(playback, stopFirst = true)
            }
        }
    }

    fun release() {
        if (released) return
        released = true
        stop()
    }

    private fun finish(playback: Playback, failure: Throwable?) {
        if (active !== playback) return
        cleanup(playback, stopFirst = false)
        if (!playback.continuation.isActive) return
        if (failure == null) {
            playback.continuation.resume(Unit)
        } else {
            playback.continuation.resumeWithException(failure)
        }
    }

    private fun cleanup(playback: Playback, stopFirst: Boolean) {
        if (active !== playback) return
        active = null
        if (stopFirst) runCatching(playback.player::stop)
        runCatching(playback.player::release)
    }

    private class Playback(
        val player: MediaPlayer,
        val continuation: CancellableContinuation<Unit>,
    )
}
