package com.mrj.fancyai.service.voice

import com.mrj.fancyai.voice.SpeechRecognitionException
import kotlinx.coroutines.CancellableContinuation
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

internal const val NO_ANDROID_ERROR = -1

internal fun IVoiceCallback.sendError(requestId: Long, failure: Throwable) {
    runCatching {
        onError(
            requestId,
            (failure as? SpeechRecognitionException)?.errorCode ?: NO_ANDROID_ERROR,
            (failure as? CloudVoiceException)?.failure?.name.orEmpty(),
            failure.message ?: failure.javaClass.simpleName,
        )
    }
}

internal fun voiceFailure(
    androidError: Int,
    cloudFailure: String?,
    detail: String?,
): Throwable {
    if (androidError != NO_ANDROID_ERROR) return SpeechRecognitionException(androidError)
    val cloud = cloudFailure?.let { stored ->
        CloudVoiceFailure.entries.firstOrNull { it.name == stored }
    }
    return cloud?.let(::CloudVoiceException) ?: IllegalStateException(detail.orEmpty())
}

internal fun <T> CancellableContinuation<T>.complete(value: T) {
    if (isActive) runCatching { resume(value) }
}

internal fun <T> CancellableContinuation<T>.fail(failure: Throwable) {
    if (isActive) runCatching { resumeWithException(failure) }
}
