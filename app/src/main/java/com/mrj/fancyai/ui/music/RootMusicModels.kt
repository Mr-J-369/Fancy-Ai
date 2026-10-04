package com.mrj.fancyai.ui.music

import com.mrj.fancyai.R
import java.io.File

internal enum class RootMusicTier(
    val model: String,
    val priceLabel: Int,
) {
    CLIP("google/lyria-3-clip-preview", R.string.producer_price_clip),
    PRO("google/lyria-3-pro-preview", R.string.producer_price_pro),
}

internal enum class RootAudioFormat(val extension: String, val mimeType: String) {
    MP3("mp3", "audio/mpeg"),
    WAV("wav", "audio/wav"),
    UNKNOWN("bin", "application/octet-stream"),
}

internal data class RootMusicTrack(
    val id: String,
    val file: File,
    val title: String,
    val brief: String,
    val lyrics: String,
    val providerText: String,
    val tier: RootMusicTier,
    val model: String,
    val priceUsd: String,
    val createdAt: Long,
    val format: RootAudioFormat,
)

internal enum class RootProducerFailure {
    AUTHENTICATION,
    PAYMENT,
    RATE_LIMIT,
    NETWORK,
    PROVIDER,
    INVALID_RESPONSE,
}

internal class RootProducerException(
    val failure: RootProducerFailure,
    cause: Throwable? = null,
) : Exception(failure.name, cause)
