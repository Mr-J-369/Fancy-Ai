package com.mrj.fancyai.sd.face.swap

/**
 * JNI bridge to the Aura Swap pipeline (image/src/main/cpp/sd/mnn_faceswap.cpp,
 * lib `fancy_mnn_faceswap`). Native owns one MNN session per stage, all CPU
 * (the GPU backends miscompute this pipeline); alignment, cropping and
 * blend-back stay in Kotlin ([FaceSwapPipeline], [SwapAlign], [SwapBlend]).
 * Calls are serialized by the caller's mutex and models are unloaded after
 * each job, so only one set is ever resident.
 */
object MnnFaceSwap {
    init { System.loadLibrary("fancy_mnn_faceswap") }

    /** Load all four stage models. Paths come from the downloaded faceswap component. */
    external fun nativeLoad(
        detectPath: String,
        embedPath: String,
        swapPath: String,
        restorePath: String,
        useGpu: Boolean,
    ): Boolean

    /**
     * SCRFD on a 640-square CHW input. Returns faces flattened as
     * [score, x1, y1, x2, y2, kx0, ky0, … kx4, ky4] per face (15 floats each), in the
     * detector's input coordinate space; empty array = no faces. Kotlin maps boxes/kps
     * back to source pixels and builds the 5-point affine.
     */
    external fun nativeDetect(chw: FloatArray): FloatArray

    /** ArcFace: 112-aligned source face (CHW) → raw 512-d identity embedding (un-normalized). */
    external fun nativeEmbed(chw112: FloatArray): FloatArray

    /**
     * inswapper_128: 128-aligned target face (CHW) + raw source embedding (512-d) →
     * swapped 3×128×128 CHW. The emap projection (normalize → ×emap → normalize) is
     * baked into the converted MNN graph, so the raw embedding from [nativeEmbed]
     * is fed directly — no projection needed in Kotlin.
     */
    external fun nativeSwap(targetChw128: FloatArray, srcEmb512: FloatArray): FloatArray

    /** CodeFormer: 512 face (CHW) + fidelity weight (0..1) → restored 3×512×512 CHW. */
    external fun nativeRestore(chw512: FloatArray, fidelity: Float): FloatArray

    external fun nativeUnload()
}
