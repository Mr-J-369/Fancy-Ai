package com.mrj.fancyai.sd.face.swap

import com.mrj.fancyai.sd.hd.Rgb

/**
 * Pure orchestration of the swap: detect → embed (source) → swap → restore → blend-back.
 * Operates on ARGB IntArrays so it's testable without Bitmap/Android, and holds NO MNN state —
 * [MnnFaceSwap] must already be loaded by the caller (ImageService, on its serialized queue).
 *
 * The model-specific I/O conventions confirmed in M0 live in exactly two places: the per-stage
 * normalization constants below, and [MnnFaceSwap]'s native side (SCRFD output decode + the
 * inswapper embedding/emap convention). Everything else here is plain geometry.
 */
object FaceSwapPipeline {

    // inswapper + CodeFormer run on CPU (see MnnFaceSwap): the GPU backend miscomputes them.
    // Per-stage input normalization, (px/255 − mean)/std.
    private const val DET_MEAN = 0.5f; private const val DET_STD = 128f / 255f   // SCRFD (px−127.5)/128
    private const val ARC_MEAN = 0.5f; private const val ARC_STD = 0.5f          // ArcFace [-1,1]
    private const val SWAP_MEAN = 0f;  private const val SWAP_STD = 1f           // inswapper px/255
    private const val CF_MEAN = 0.5f;  private const val CF_STD = 0.5f           // CodeFormer [-1,1]

    private const val DET_SIDE = 640
    private const val SWAP_SIDE = 128
    private const val RESTORE_SIDE = 512

    data class Options(val allFaces: Boolean = false, val fidelity: Float = 0.7f, val feather: Float = 0.08f)

    data class Face(val score: Float, val cx: Float, val cy: Float, val area: Float, val kps: FloatArray)

    /** Returns a new composited target buffer, or null if no usable face was found in either image. */
    fun swap(
        srcPx: IntArray, srcW: Int, srcH: Int,
        tgtPx: IntArray, tgtW: Int, tgtH: Int,
        opts: Options = Options(),
    ): IntArray? {
        val srcFace = detect(srcPx, srcW, srcH).maxByOrNull { it.area } ?: return null
        val srcChw = SwapAlign.warpToChw(
            srcPx, srcW, srcH,
            SwapAlign.similarityToTemplate(srcFace.kps, ARC_SIDE), ARC_SIDE, ARC_MEAN, ARC_STD,
        )
        val emb = MnnFaceSwap.nativeEmbed(srcChw)
        if (emb.size != 512) return null

        val targets = detect(tgtPx, tgtW, tgtH)
        if (targets.isEmpty()) return null
        val chosen = if (opts.allFaces) targets else listOf(targets.maxByOrNull { it.area }!!)

        var out = tgtPx
        for (face in chosen) {
            val a128 = SwapAlign.similarityToTemplate(face.kps, SWAP_SIDE)
            val tgtAligned = SwapAlign.warpToChw(out, tgtW, tgtH, a128, SWAP_SIDE, SWAP_MEAN, SWAP_STD)
            val swapped = MnnFaceSwap.nativeSwap(tgtAligned, emb)
            if (swapped.size != 3 * SWAP_SIDE * SWAP_SIDE) continue

            val restored = restoreTo512(swapped, opts.fidelity) ?: continue
            val restoredArgb = SwapBlend.chwToArgb(restored, RESTORE_SIDE, CF_MEAN, CF_STD)
            val a512 = SwapAlign.similarityToTemplate(face.kps, RESTORE_SIDE)
            out = SwapBlend.pasteBack(out, tgtW, tgtH, restoredArgb, RESTORE_SIDE, a512, opts.feather)
        }
        return out
    }

    private const val ARC_SIDE = 112

    /** Upscale the 128 swapped face to CodeFormer's 512 input and restore. */
    private fun restoreTo512(swapped128: FloatArray, fidelity: Float): FloatArray? {
        val argb = SwapBlend.chwToArgb(swapped128, SWAP_SIDE, SWAP_MEAN, SWAP_STD)
        val chw512 = resampleToChw(argb, SWAP_SIDE, SWAP_SIDE, RESTORE_SIDE, CF_MEAN, CF_STD)
        val restored = MnnFaceSwap.nativeRestore(chw512, fidelity.coerceIn(0f, 1f))
        return restored.takeIf { it.size == 3 * RESTORE_SIDE * RESTORE_SIDE }
    }

    /**
     * SCRFD detect with aspect-preserving letterbox into [DET_SIDE]; native returns faces in the
     * 640 input frame as flat [score,x1,y1,x2,y2, 5×(x,y)] (15 floats each), which we un-letterbox
     * back to source pixels. Decode/NMS happen native, where the SCRFD multi-stride output lives.
     */
    private fun detect(px: IntArray, w: Int, h: Int): List<Face> {
        // SCRFD's largest anchor is at stride 32, so a face that fills the frame (extreme close-up)
        // overflows it and scores ~0 → missed. Retry progressively zoomed-out (the image placed in
        // a smaller central region of the 640 canvas) so an oversized face shrinks into range.
        // Normal-framed faces hit on the first (zoom = 1) pass.
        val fit = minOf(DET_SIDE / w.toFloat(), DET_SIDE / h.toFloat())
        for (zoom in floatArrayOf(1f, 0.6f, 0.4f)) {
            val faces = detectAt(px, w, h, fit * zoom)
            if (faces.isNotEmpty()) return faces
        }
        return emptyList()
    }

    /** One SCRFD pass at a given letterbox [scale] (image centered, rest padded). */
    private fun detectAt(px: IntArray, w: Int, h: Int, scale: Float): List<Face> {
        val padX = (DET_SIDE - w * scale) * 0.5f
        val padY = (DET_SIDE - h * scale) * 0.5f
        val chw = letterboxChw(px, w, h, scale, padX, padY)
        val raw = MnnFaceSwap.nativeDetect(chw)
        val faces = ArrayList<Face>(raw.size / 15)
        var i = 0
        while (i + 15 <= raw.size) {
            val kps = FloatArray(10)
            var minx = Float.MAX_VALUE; var miny = Float.MAX_VALUE
            var maxx = -Float.MAX_VALUE; var maxy = -Float.MAX_VALUE
            for (k in 0 until 5) {
                val kx = (raw[i + 5 + 2 * k] - padX) / scale
                val ky = (raw[i + 5 + 2 * k + 1] - padY) / scale
                kps[2 * k] = kx; kps[2 * k + 1] = ky
                if (kx < minx) minx = kx; if (kx > maxx) maxx = kx
                if (ky < miny) miny = ky; if (ky > maxy) maxy = ky
            }
            val area = (maxx - minx).coerceAtLeast(0f) * (maxy - miny).coerceAtLeast(0f)
            faces += Face(raw[i], (minx + maxx) * 0.5f, (miny + maxy) * 0.5f, area, kps)
            i += 15
        }
        return faces
    }

    /** Sample [px] into a [DET_SIDE]² CHW buffer with letterbox padding and SCRFD normalization. */
    private fun letterboxChw(px: IntArray, w: Int, h: Int, scale: Float, padX: Float, padY: Float): FloatArray {
        val out = FloatArray(3 * DET_SIDE * DET_SIDE)
        val plane = DET_SIDE * DET_SIDE
        val pad = (-DET_MEAN) / DET_STD   // padded border value after normalization
        for (oy in 0 until DET_SIDE) {
            val sy = (oy - padY) / scale
            for (ox in 0 until DET_SIDE) {
                val sx = (ox - padX) / scale
                val i = oy * DET_SIDE + ox
                if (sx < 0f || sy < 0f || sx > w - 1f || sy > h - 1f) {
                    out[i] = pad; out[plane + i] = pad; out[2 * plane + i] = pad; continue
                }
                val p = px[sy.toInt() * w + sx.toInt()]
                out[i] = (Rgb.red(p) / 255f - DET_MEAN) / DET_STD
                out[plane + i] = (Rgb.green(p) / 255f - DET_MEAN) / DET_STD
                out[2 * plane + i] = (Rgb.blue(p) / 255f - DET_MEAN) / DET_STD
            }
        }
        return out
    }

    /** Plain stretch-resize of an ARGB buffer into a CHW float buffer with (px/255−mean)/std. */
    private fun resampleToChw(src: IntArray, w: Int, h: Int, outSide: Int, mean: Float, std: Float): FloatArray {
        val out = FloatArray(3 * outSide * outSide)
        val plane = outSide * outSide
        for (oy in 0 until outSide) {
            val sy = (oy.toLong() * h / outSide).toInt().coerceIn(0, h - 1)
            for (ox in 0 until outSide) {
                val sx = (ox.toLong() * w / outSide).toInt().coerceIn(0, w - 1)
                val p = src[sy * w + sx]; val i = oy * outSide + ox
                out[i] = (Rgb.red(p) / 255f - mean) / std
                out[plane + i] = (Rgb.green(p) / 255f - mean) / std
                out[2 * plane + i] = (Rgb.blue(p) / 255f - mean) / std
            }
        }
        return out
    }
}
