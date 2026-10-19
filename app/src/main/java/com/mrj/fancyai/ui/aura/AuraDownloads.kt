package com.mrj.fancyai.ui.aura

import android.content.Context
import android.util.Log
import com.mrj.fancyai.util.AppLog
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import java.io.File
import kotlin.time.Duration.Companion.milliseconds

internal suspend fun downloadResuming(
    context: Context,
    logTag: String,
    url: String,
    target: File,
    expectedSize: Long = 0L,
    attempts: Int = 5,
    onProgress: (Long, Long) -> Unit,
) {
    var failure: Exception? = null
    repeat(attempts) { attempt ->
        try {
            downloadDirect(context, url, target, expectedSize, onProgress)
            return
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            failure = error
            AppLog.write(Log.WARN, logTag, "Download stalled, resuming ${target.name} (attempt ${attempt + 1}/$attempts)", error)
            delay((2_000L * (attempt + 1)).milliseconds)
        }
    }
    throw checkNotNull(failure) { "Download failed for ${target.name}" }
}
