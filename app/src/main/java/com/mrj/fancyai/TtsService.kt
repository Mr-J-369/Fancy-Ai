package com.mrj.fancyai

import android.content.Context
import android.speech.tts.TextToSpeech
import java.util.Locale

class TtsService(context: Context) {
    private var tts: TextToSpeech? = null

    init {
        tts = TextToSpeech(context) { status ->
            if (status == TextToSpeech.SUCCESS) tts?.setLanguage(Locale.US)
        }
    }

    fun speak(text: String) {
        tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "FancyAI_TTS")
    }

    fun stop() {
        tts?.stop()
    }

    fun shutdown() {
        tts?.run { stop(); shutdown() }
    }
}
