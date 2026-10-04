package com.mrj.fancyai.sd

/** Stable wire values shared with native_diffusion_pipeline.h. */
enum class SamplerType(val wireValue: Int) {
    EULER(0),
    EULER_A(1),
    HEUN(2),
    DPM2(3),
    DPM2_A(4),
    LMS(5),
    DPMPP_2S_A(6),
    DPMPP_2M(7),
    DPMPP_SDE(8),
    DPMPP_2M_SDE(9),
    DPMPP_3M_SDE(10),
    DDIM(11),
    LCM(12),
    ;

    companion object {
        fun fromName(name: String?): SamplerType = entries.firstOrNull { it.name == name } ?: DPMPP_2M
    }
}
