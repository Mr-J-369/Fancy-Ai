package com.mrj.fancyai.sd.hd

enum class UpscaleSize(val factor: Int) { X2(2) }

enum class UpscaleStyle(val fileName: String) {
    PHOTO("realesrgan_x4plus.mnn"),
    ANIME("realesrgan_x4plus_anime.mnn"),
}

data class UpscalerModel(val style: UpscaleStyle, val url: String, val sha256: String, val sizeBytes: Long)

object UpscalerModels {
    val SOURCE_SIZES = setOf(512 to 512, 512 to 768, 768 to 512)
    const val CORE_TILE = 256
    const val PAD = 24
    const val TILE_SIDE = CORE_TILE + 2 * PAD

    val PHOTO = UpscalerModel(
        UpscaleStyle.PHOTO,
        url = "https://huggingface.co/Mr-J-369/Fancy-AI/resolve/main/realesrgan_x4plus.fp16.mnn",
        sha256 = "4897fc77dac1bb786b9439c14cd14e75bff401d7397f67cad14c0935738fa47c",
        sizeBytes = 33_637_604L,
    )
    val ANIME = UpscalerModel(
        UpscaleStyle.ANIME,
        url = "https://huggingface.co/Mr-J-369/Fancy-AI/resolve/main/realesrgan_x4plus_anime.fp16.mnn",
        sha256 = "a2ac13bd46fd3e21e9ef2e83832b659267bd0f54c0db8f2360332a257305b69c",
        sizeBytes = 9_001_288L,
    )
    fun of(style: UpscaleStyle) = if (style == UpscaleStyle.PHOTO) PHOTO else ANIME
}
