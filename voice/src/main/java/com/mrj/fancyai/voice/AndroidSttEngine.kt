package com.mrj.fancyai.voice

import android.Manifest
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import androidx.annotation.RequiresPermission
import java.util.Locale
import java.util.concurrent.CancellationException
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

class AndroidSttEngine(
    context: Context,
    private val locale: Locale = Locale.getDefault(),
) : SttEngine {
    private val appContext = context.applicationContext
    private val mainHandler = Handler(Looper.getMainLooper())
    private val mutablePartial = MutableStateFlow("")
    private var active: Session? = null
    @Volatile private var released = false

    override val partial: StateFlow<String> = mutablePartial

    @RequiresPermission(Manifest.permission.RECORD_AUDIO)
    override suspend fun listen(): String = withContext(Dispatchers.Main.immediate) {
        check(!released) { "Android STT engine is released" }
        check(active == null) { "Android STT is already listening" }
        check(SpeechRecognizer.isRecognitionAvailable(appContext)) {
            "Android speech recognition is unavailable"
        }

        mutablePartial.value = ""
        suspendCancellableCoroutine { continuation ->
            val recognizer = SpeechRecognizer.createSpeechRecognizer(appContext)
            val session = Session(recognizer, continuation)
            active = session
            recognizer.setRecognitionListener(listener(session))
            continuation.invokeOnCancellation {
                mainHandler.post { cleanup(session, cancelFirst = true) }
            }
            runCatching {
                recognizer.startListening(
                    Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                        putExtra(
                            RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                            RecognizerIntent.LANGUAGE_MODEL_FREE_FORM,
                        )
                        putExtra(RecognizerIntent.EXTRA_LANGUAGE, locale.toLanguageTag())
                        putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                    },
                )
            }.onFailure { finish(session, Result.failure(it)) }
        }
    }

    override fun cancel() {
        mainHandler.post {
            val session = active ?: return@post
            session.continuation.cancel(CancellationException("Android STT cancelled"))
            cleanup(session, cancelFirst = true)
        }
    }

    override fun release() {
        if (released) return
        released = true
        mainHandler.post {
            val session = active ?: return@post
            session.continuation.cancel(CancellationException("Android STT released"))
            cleanup(session, cancelFirst = true)
        }
    }

    private fun listener(session: Session) = object : RecognitionListener {
        override fun onReadyForSpeech(params: Bundle?) = Unit
        override fun onBeginningOfSpeech() = Unit
        override fun onRmsChanged(rmsdB: Float) = Unit
        override fun onBufferReceived(buffer: ByteArray?) = Unit
        override fun onEndOfSpeech() = Unit

        override fun onError(error: Int) {
            finish(session, Result.failure(SpeechRecognitionException(error)))
        }

        override fun onResults(results: Bundle?) {
            val text = results
                ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                ?.firstOrNull()
                .orEmpty()
                .trim()
            finish(session, Result.success(text))
        }

        override fun onPartialResults(partialResults: Bundle?) {
            if (active === session) {
                mutablePartial.value = partialResults
                    ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    ?.firstOrNull()
                    .orEmpty()
                    .trim()
            }
        }

        override fun onEvent(eventType: Int, params: Bundle?) = Unit
    }

    private fun finish(session: Session, result: Result<String>) {
        if (active !== session) return
        cleanup(session, cancelFirst = false)
        if (!session.continuation.isActive) return
        result.onSuccess(session.continuation::resume)
            .onFailure(session.continuation::resumeWithException)
    }

    private fun cleanup(session: Session, cancelFirst: Boolean) {
        if (active !== session) return
        active = null
        if (cancelFirst) runCatching(session.recognizer::cancel)
        runCatching(session.recognizer::destroy)
    }

    private class Session(
        val recognizer: SpeechRecognizer,
        val continuation: CancellableContinuation<String>,
    )
}
