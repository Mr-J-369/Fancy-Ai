package com.mrj.fancyai.service

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.util.Log
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow
import java.util.Locale

class VoiceService(context: Context) : RecognitionListener {
    private val speechRecognizer: SpeechRecognizer = SpeechRecognizer.createSpeechRecognizer(context)
    private val textToSpeech: TextToSpeech = TextToSpeech(context) { status ->
        if (status == TextToSpeech.SUCCESS) {
            textToSpeech.language = Locale.getDefault()
            Log.d("VoiceService", "TextToSpeech initialized")
        } else {
            Log.e("VoiceService", "TextToSpeech init failed: $status")
        }
    }

    private val _recognizedText = Channel<String>(1)
    val recognizedText: Flow<String> = _recognizedText.receiveAsFlow()

    private val _isListening = Channel<Boolean>(1)
    val isListening: Flow<Boolean> = _isListening.receiveAsFlow()

    private var isActive = false

    init {
        speechRecognizer.setRecognitionListener(this)
    }

    fun startListening() {
        if (isActive) return
        isActive = true

        val intent = Intent(android.speech.RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(android.speech.RecognizerIntent.EXTRA_LANGUAGE_MODEL, android.speech.RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(android.speech.RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault().language)
            putExtra(android.speech.RecognizerIntent.EXTRA_MAX_RESULTS, 1)
            putExtra(android.speech.RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
        }

        Log.d("VoiceService", "Starting speech recognition")
        _isListening.trySend(true)
        speechRecognizer.startListening(intent)
    }

    fun stopListening() {
        isActive = false
        _isListening.trySend(false)
        speechRecognizer.stopListening()
    }

    fun speak(text: String) {
        if (textToSpeech.isSpeaking) {
            textToSpeech.stop()
        }
        textToSpeech.speak(text, TextToSpeech.QUEUE_FLUSH, null)
    }

    fun stopSpeaking() {
        if (textToSpeech.isSpeaking) {
            textToSpeech.stop()
        }
    }

    fun destroy() {
        speechRecognizer.destroy()
        textToSpeech.shutdown()
    }

    // RecognitionListener callbacks
    override fun onReadyForSpeech(params: Bundle?) {
        Log.d("VoiceService", "Ready for speech")
    }

    override fun onBeginningOfSpeech() {
        Log.d("VoiceService", "Beginning of speech")
    }

    override fun onRmsChanged(rmsdB: Float) {}

    override fun onBufferReceived(buffer: ByteArray?) {}

    override fun onEndOfSpeech() {
        Log.d("VoiceService", "End of speech")
    }

    override fun onError(error: Int) {
        Log.e("VoiceService", "Speech recognition error: $error")
        isActive = false
        _isListening.trySend(false)
    }

    override fun onResults(results: Bundle?) {
        val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
        if (!matches.isNullOrEmpty()) {
            val recognized = matches[0]
            Log.d("VoiceService", "Recognized: $recognized")
            _recognizedText.trySend(recognized)
        }
        isActive = false
        _isListening.trySend(false)
    }

    override fun onPartialResults(partialResults: Bundle?) {
        val matches = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
        if (!matches.isNullOrEmpty()) {
            Log.d("VoiceService", "Partial: ${matches[0]}")
        }
    }

    override fun onEvent(eventType: Int, params: Bundle?) {}
}
