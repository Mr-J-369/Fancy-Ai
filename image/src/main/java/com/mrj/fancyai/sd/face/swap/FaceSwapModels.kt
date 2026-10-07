package com.mrj.fancyai.sd.face.swap

/**
 * The four MNN models of the Aura Swap pipeline, hosted on the same HF repo as the
 * rest of the downloadable components (Mr-J-369/Fancy-AI). Mirrors [UpscalerModels]/
 * FaceParserModel: a flat spec table that the manifest `faceswap` component points at.
 *
 * Each [SwapModel] is verified (ONNX→MNN parity vs onnxruntime) BEFORE its sha256/size
 * is filled in here — an empty sha256 means "not yet hosted" ([isHosted] == false), the
 * same gate FaceParserModel uses, so the feature stays dark until all four are real.
 */
enum class SwapStage { DETECT, EMBED, SWAP, RESTORE }

data class SwapModel(
    val stage: SwapStage,
    val fileName: String,
    val inputSide: Int,
    val url: String,
    val sha256: String,
    val sizeBytes: Long,
) {
    val isHosted: Boolean get() = sha256.isNotBlank()
}

object FaceSwapModels {
    private const val BASE = "https://huggingface.co/Mr-J-369/Fancy-AI/resolve/main/AuraSwap"

    /** SCRFD 10G — face detect + 5-point landmarks. Standard 640-square input. */
    val DETECT = SwapModel(
        stage = SwapStage.DETECT,
        fileName = "scrfd_10g.fp16.mnn",
        inputSide = 640,
        url = "$BASE/scrfd_10g.fp16.mnn",
        sha256 = "5f87ce4a3120f31576a221add83ea2a8e9e19af83d980a04bafc2cc6a9867095",
        sizeBytes = 8_485_264L,
    )

    /** ArcFace w600k_r50 — 512-d identity embedding from a 112-aligned source face. */
    val EMBED = SwapModel(
        stage = SwapStage.EMBED,
        fileName = "arcface_w600k_r50.fp16.mnn",
        inputSide = 112,
        url = "$BASE/arcface_w600k_r50.fp16.mnn",
        sha256 = "8c5f110757fdef0320dc27c73d3dbe48f7db7041bca74a8ffec6f5e9a8bf261a",
        sizeBytes = 87_244_384L,
    )

    /** inswapper_128 — target 128 face crop + source embedding → swapped 128 face. */
    val SWAP = SwapModel(
        stage = SwapStage.SWAP,
        fileName = "inswapper_128.fp16.mnn",
        inputSide = 128,
        url = "$BASE/inswapper_128.fp16.mnn",
        sha256 = "c268cae924cdf0b9f3fcca1c286e2b556e8b8d14668c44070f468d9237b7fe30",
        sizeBytes = 277_236_896L,
    )

    /** CodeFormer — locked restorer (identity retention over GFPGAN). 512-square. */
    val RESTORE = SwapModel(
        stage = SwapStage.RESTORE,
        fileName = "codeformer.fp16.mnn",
        inputSide = 512,
        url = "$BASE/codeformer.fp16.mnn",
        sha256 = "e2accdf5e392ffe6d469a6835b4c6ea17dea396bb72506b059f87c1405f20894",
        sizeBytes = 188_833_640L,
    )

    val ALL = listOf(DETECT, EMBED, SWAP, RESTORE)

    /** True only once every stage model is hosted+verified — the feature's master gate. */
    val isHosted: Boolean get() = ALL.all { it.isHosted }

    fun of(stage: SwapStage): SwapModel = when (stage) {
        SwapStage.DETECT -> DETECT
        SwapStage.EMBED -> EMBED
        SwapStage.SWAP -> SWAP
        SwapStage.RESTORE -> RESTORE
    }
}
