package com.mrj.fancyai.sd

/** Stable wire values shared with native_diffusion_pipeline.h. */
enum class Schedule(val wireValue: Int) {
    NORMAL(0),
    KARRAS(1),
    EXPONENTIAL(2),
    SGM_UNIFORM(3),
    SIMPLE(4),
    DDIM_UNIFORM(5),
    BETA(6),
}
