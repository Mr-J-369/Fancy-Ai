package com.mrj.fancyai.ui.aura

import com.mrj.fancyai.R

internal const val KLEIN9B_BYTES = 5_616_208_032L
private const val KLEIN9B_URL =
    "https://huggingface.co/leejet/FLUX.2-klein-9B-GGUF/resolve/main/flux-2-klein-9b-Q4_0.gguf"

internal fun AuraController.installKlein9b() = installDirectModel(
    modelId = "flux2_klein_9b",
    marker = "KLEIN9B",
    url = KLEIN9B_URL,
    fileName = "dit.gguf",
    expectedBytes = KLEIN9B_BYTES,
    downloadingRes = R.string.aura_downloading_klein9b,
    failedRes = R.string.aura_klein9b_install_failed,
    logTag = "FLUX.2 Klein 9B Q4_0",
)
