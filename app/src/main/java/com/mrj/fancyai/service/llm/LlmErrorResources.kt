package com.mrj.fancyai.service.llm

import androidx.annotation.StringRes
import com.mrj.fancyai.R

@StringRes
internal fun llmErrorResource(failure: Throwable, @StringRes fallback: Int): Int {
    generateSequence(failure) { it.cause }.filterIsInstance<MacroException>()
        .firstOrNull()?.let { return it.resource }
    val engineFailure = generateSequence(failure) { it.cause }
        .filterIsInstance<LlmEngineException>()
        .firstOrNull() ?: return fallback
    return when (engineFailure.error) {
        LlmError.MEMORY_PRESSURE -> R.string.llm_error_memory_pressure
        LlmError.OUT_OF_MEMORY -> R.string.llm_error_out_of_memory
        LlmError.CONTEXT_EXHAUSTED -> R.string.llm_error_context_exhausted
        LlmError.PROCESS_DIED -> R.string.llm_error_process_died
        LlmError.MODEL_LOAD -> R.string.llm_error_model_load
        LlmError.NETWORK -> R.string.llm_error_network
        LlmError.AUTHENTICATION -> R.string.llm_error_authentication
        LlmError.RATE_LIMIT -> R.string.llm_error_rate_limit
        else -> fallback
    }
}
