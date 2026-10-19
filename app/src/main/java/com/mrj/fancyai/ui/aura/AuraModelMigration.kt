package com.mrj.fancyai.ui.aura

import android.content.Context
import java.io.File

internal fun migrateModelFilenames(context: Context) {
    val models = File(context.filesDir, "sd_models")
    if (!models.isDirectory) return
    val renames = mapOf(
        "z_image_turbo_q4_0/dit.gguf" to "z_image_turbo_q4_0/z_image_turbo-Q4_0.gguf",
        "zit_khv/dit.safetensors" to "zit_khv/zitKHV_v10khv.safetensors",
        "flux2_klein_4b/dit.safetensors" to "flux2_klein_4b/flux-2-klein-4b-fp8.safetensors",
        "flux2_klein_9b/dit.gguf" to "flux2_klein_9b/flux-2-klein-9b-Q4_0.gguf",
        "moss_image/dit.gguf" to "moss_image/MOSS-Image-Base-Q4_0.gguf",
        "moss_image/llm.gguf" to "moss_image/Qwen3.5-9B-Q4_0.gguf",
    )
    for ((from, to) in renames) {
        val source = File(models, from)
        val destination = File(models, to)
        if (source.isFile && !destination.exists()) {
            runCatching { source.renameTo(destination) }
        }
    }
    runCatching { File(models, "z_image_turbo_q4_0/llm.gguf").delete() }
}
