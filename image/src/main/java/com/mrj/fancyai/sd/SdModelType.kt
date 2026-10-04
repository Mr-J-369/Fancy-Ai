package com.mrj.fancyai.sd

import java.io.File

enum class SdModelType {
    SD15, SDXL, DIT;

    companion object {
        fun detect(dir: String): SdModelType {
            val d = File(dir)
            val isDit = File(d, "dit.safetensors").exists() ||
                File(d, "dit.gguf").exists() ||
                File(d, "DIT").exists() ||
                File(d, "ZIT").exists() ||
                d.listFiles()?.any {
                    (it.name.endsWith(".safetensors", ignoreCase = true) || it.name.endsWith(".gguf", ignoreCase = true)) &&
                        it.name != "vae.safetensors" && it.name != "ae.safetensors" && it.name != "llm.gguf"
                } == true
            return when {
                isDit -> DIT
                File(d, "SDXL").exists() -> SDXL
                else -> SD15
            }
        }
    }
}

enum class SdRuntimeType {
    QNN, MNN, DIT
}

enum class MnnMemoryPolicy(val wireValue: Int) {
    LOW_MEMORY(0),
    BALANCED(1),
    SPEED_FIRST(2);

    companion object {
        fun fromName(value: String?): MnnMemoryPolicy =
            entries.firstOrNull { it.name == value } ?: LOW_MEMORY
    }
}

enum class MnnBackend(val wireValue: Int) {
    AUTOMATIC(0),
    CPU(1),
    OPENCL(2);

    companion object {
        fun fromName(value: String?): MnnBackend =
            entries.firstOrNull { it.name == value } ?: AUTOMATIC
    }
}

data class SdPackageType(
    val model: SdModelType,
    val runtime: SdRuntimeType,
)
