package com.mrj.fancyai.voice

import android.Manifest
import androidx.annotation.RequiresPermission
import kotlinx.coroutines.flow.StateFlow

interface SttEngine {
    val partial: StateFlow<String>

    @RequiresPermission(Manifest.permission.RECORD_AUDIO)
    suspend fun listen(): String

    fun cancel()
    fun release()
}

interface TtsEngine {
    suspend fun speak(text: String)
    fun stop()
    fun release()
}

class SpeechRecognitionException(
    val errorCode: Int,
) : Exception("Android speech recognition failed ($errorCode)")

class SpeechSynthesisException : Exception {
    constructor(errorCode: Int) : super("Android speech synthesis failed ($errorCode)")
}
