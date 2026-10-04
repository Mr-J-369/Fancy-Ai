package com.mrj.fancyai.voice

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import java.util.UUID
import java.util.concurrent.CancellationException
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

class AndroidTtsEngine private constructor(
    private val textToSpeech: TextToSpeech,
) : TtsEngine {
    private val mainHandler = Handler(Looper.getMainLooper())
    private val speechLock = Mutex()
    private var active: Utterance? = null
    @Volatile private var released = false

    override suspend fun speak(text: String) {
        val spoken = text.trim()
        if (spoken.isEmpty()) return
        if (spoken.length > TextToSpeech.getMaxSpeechInputLength()) {
            throw SpeechSynthesisException(TextToSpeech.ERROR_INVALID_REQUEST)
        }

        speechLock.withLock {
            withContext(Dispatchers.Main.immediate) {
                check(!released) { "Android TTS engine is released" }
                suspendCancellableCoroutine { continuation ->
                    val utterance = Utterance(UUID.randomUUID().toString(), continuation)
                    active = utterance
                    textToSpeech.setOnUtteranceProgressListener(listener(utterance))
                    continuation.invokeOnCancellation {
                        mainHandler.post { cleanup(utterance, stopFirst = true) }
                    }
                    if (
                        textToSpeech.speak(
                            spoken,
                            TextToSpeech.QUEUE_FLUSH,
                            null,
                            utterance.id,
                        ) == TextToSpeech.ERROR
                    ) {
                        finish(
                            utterance,
                            Result.failure(SpeechSynthesisException(TextToSpeech.ERROR)),
                        )
                    }
                }
            }
        }
    }

    override fun stop() {
        mainHandler.post {
            val utterance = active
            if (utterance != null) {
                utterance.continuation.cancel(CancellationException("Android TTS stopped"))
                cleanup(utterance, stopFirst = true)
            } else {
                runCatching(textToSpeech::stop)
            }
        }
    }

    override fun release() {
        if (released) return
        released = true
        mainHandler.post {
            active?.let {
                it.continuation.cancel(CancellationException("Android TTS released"))
                cleanup(it, stopFirst = true)
            }
            runCatching(textToSpeech::shutdown)
        }
    }

    private fun listener(utterance: Utterance) = object : UtteranceProgressListener() {
        override fun onStart(utteranceId: String?) = Unit

        override fun onDone(utteranceId: String?) {
            if (utteranceId == utterance.id) {
                mainHandler.post { finish(utterance, Result.success(Unit)) }
            }
        }

        @Deprecated("Deprecated in Java")
        override fun onError(utteranceId: String?) {
            onError(utteranceId, TextToSpeech.ERROR)
        }

        override fun onError(utteranceId: String?, errorCode: Int) {
            if (utteranceId == utterance.id) {
                mainHandler.post {
                    finish(utterance, Result.failure(SpeechSynthesisException(errorCode)))
                }
            }
        }

        override fun onStop(utteranceId: String?, interrupted: Boolean) {
            if (utteranceId == utterance.id) {
                mainHandler.post {
                    if (active === utterance) {
                        active = null
                        utterance.continuation.cancel(
                            CancellationException("Android TTS stopped"),
                        )
                    }
                }
            }
        }
    }

    private fun finish(utterance: Utterance, result: Result<Unit>) {
        if (active !== utterance) return
        cleanup(utterance, stopFirst = false)
        if (!utterance.continuation.isActive) return
        result.onSuccess(utterance.continuation::resume)
            .onFailure(utterance.continuation::resumeWithException)
    }

    private fun cleanup(utterance: Utterance, stopFirst: Boolean) {
        if (active !== utterance) return
        active = null
        if (stopFirst) runCatching(textToSpeech::stop)
    }

    private class Utterance(
        val id: String,
        val continuation: CancellableContinuation<Unit>,
    )

    companion object {
        suspend fun create(context: Context): AndroidTtsEngine =
            withContext(Dispatchers.Main.immediate) {
                suspendCancellableCoroutine { continuation ->
                    val mainHandler = Handler(Looper.getMainLooper())
                    var engine: TextToSpeech? = null
                    engine = TextToSpeech(context.applicationContext) { status ->
                        mainHandler.post {
                            val initialized = engine ?: return@post
                            if (!continuation.isActive) {
                                initialized.shutdown()
                            } else if (status == TextToSpeech.SUCCESS) {
                                continuation.resume(AndroidTtsEngine(initialized))
                            } else {
                                initialized.shutdown()
                                continuation.resumeWithException(
                                    SpeechSynthesisException(status),
                                )
                            }
                        }
                    }
                    continuation.invokeOnCancellation {
                        mainHandler.post { engine.shutdown() }
                    }
                }
            }
    }
}
