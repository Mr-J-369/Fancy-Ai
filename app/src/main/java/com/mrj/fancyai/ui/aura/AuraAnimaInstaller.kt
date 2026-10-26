package com.mrj.fancyai.ui.aura

import android.content.Context
import android.util.Log
import com.mrj.fancyai.R
import com.mrj.fancyai.util.AppLog
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

internal const val ANIMA_ENCODER_BYTES = 1_192_135_096L
internal const val ANIMA_VAE_BYTES = 253_806_246L
internal const val ANIMA_COMPONENTS_TOTAL_BYTES = ANIMA_ENCODER_BYTES + ANIMA_VAE_BYTES
private const val AURA_ANIMA_TAG = "AuraAnima"

private const val ANIMA_ENCODER_NAME = "qwen_3_06b_base.safetensors"
private const val ANIMA_ENCODER_URL =
    "https://huggingface.co/circlestone-labs/Anima/resolve/main/split_files/text_encoders/qwen_3_06b_base.safetensors"
private const val ANIMA_VAE_NAME = "qwen_image_vae.safetensors"
private const val ANIMA_VAE_URL =
    "https://huggingface.co/circlestone-labs/Anima/resolve/main/split_files/vae/qwen_image_vae.safetensors"

internal fun isAnimaComponentsInstalled(context: Context): Boolean {
    val baseDir = File(context.filesDir, "anima_components")
    return File(baseDir, ANIMA_ENCODER_NAME).length() > 0L &&
        File(baseDir, ANIMA_VAE_NAME).length() > 0L
}

internal fun AuraController.installAnimaComponents() {
    if (state.operation != null || state.generating) return
    scope.launch {
        val baseDir = File(app.filesDir, "anima_components").apply { mkdirs() }
        val encoderTarget = File(baseDir, ANIMA_ENCODER_NAME)
        val vaeTarget = File(baseDir, ANIMA_VAE_NAME)
        val needEncoder = encoderTarget.length() <= 0L
        val needVae = vaeTarget.length() <= 0L
        if (!needEncoder && !needVae) {
            refresh()
            return@launch
        }
        val totalBytes = (if (needEncoder) ANIMA_ENCODER_BYTES else 0L) +
            (if (needVae) ANIMA_VAE_BYTES else 0L)
        var completedBytes = 0L
        val success = try {
            withContext(Dispatchers.IO) {
                operation(app.getString(R.string.aura_downloading_anima_components), 0f)
                if (needEncoder) {
                    downloadDirect(app, ANIMA_ENCODER_URL, encoderTarget, ANIMA_ENCODER_BYTES) { copied, _ ->
                        val currentOverall = completedBytes + copied
                        operation(
                            app.getString(R.string.aura_downloading_anima_components),
                            currentOverall.toFloat() / totalBytes,
                        )
                    }
                    completedBytes += encoderTarget.length()
                }
                if (needVae) {
                    downloadDirect(app, ANIMA_VAE_URL, vaeTarget, ANIMA_VAE_BYTES) { copied, _ ->
                        val currentOverall = completedBytes + copied
                        operation(
                            app.getString(R.string.aura_downloading_anima_components),
                            currentOverall.toFloat() / totalBytes,
                        )
                    }
                    completedBytes += vaeTarget.length()
                }
            }
            true
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            AppLog.write(Log.ERROR, AURA_ANIMA_TAG, "Anima components download failed", failure)
            false
        } finally {
            operation(null)
        }
        if (success) {
            refresh()
        } else {
            state = state.copy(error = app.getString(R.string.aura_anima_components_install_failed))
        }
    }
}
