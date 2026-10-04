package com.mrj.fancyai.service.llm

/** Cloud history selection. Usage-billed server context honors historyLimit directly. */
object CloudContextPolicy {
    private const val MIN_OUTPUT_TOKENS = 64
    private const val MIN_PROMPT_TOKENS = 128
    private const val MAX_TEMPLATE_MARGIN_TOKENS = 128
    private const val ROLE_OVERHEAD_TOKENS = 8
    private const val IMAGE_TOKEN_ESTIMATE = 256

    /** Turn-local lore/memory cap while the provider window is unknown. */
    private const val FALLBACK_OPTIONAL_TOKENS = 384

    fun pack(config: LlmSessionConfig, upcomingInput: LlmInput = LlmInput("")): LlmSessionConfig =
        packAll(config, upcomingInput.filterBlanks()).first

    fun pack(request: LlmRequest): LlmRequest {
        val (config, input) = packAll(request.config, request.input.filterBlanks())
        return request.copy(config = config, input = input)
    }

    private fun packAll(config: LlmSessionConfig, input: LlmInput): Pair<LlmSessionConfig, LlmInput> {
        var outputTokens = config.maxOutputTokens
        var history = config.history.asSequence()
            .map { exchange ->
                exchange.copy(input = exchange.input.copy(context = emptyList(), responseFormat = "", responseSchema = ""))
            }
            .filter { (input, assistant) -> input.textWithContext().isNotBlank() || assistant.isNotBlank() || (input.imagePath != null) }
            .toList()

        // Known provider window: token budgeting below fits history to it, so no
        // turn-count cap. Unknown window: keep the turn cap as the safety bound.
        if (config.contextTokens <= 0) {
            history = history.takeLast(config.historyLimit.coerceAtLeast(0))
        }

        val historyList = history.toMutableList()
        var inputContext = input.context

        if (config.contextTokens > 0) {
            val templateMargin = (config.contextTokens / 8)
                .coerceIn(MIN_OUTPUT_TOKENS, MAX_TEMPLATE_MARGIN_TOKENS)
            val fixedTokens = LlmTokenEstimate.estimateTokens(config.systemInstruction) +
                LlmTokenEstimate.estimateTokens(config.openingMessage) +
                (if (input.imagePath != null) IMAGE_TOKEN_ESTIMATE else 0) +
                (ROLE_OVERHEAD_TOKENS * 3)
            val requiredTokens = fixedTokens +
                LlmTokenEstimate.estimateTokens(input.copy(context = emptyList()).textWithContext())
            val pinnedTokens = fixedTokens + LlmTokenEstimate.estimateTokens(input.textWithContext())
            val safeOutputLimit = (
                config.contextTokens - templateMargin - maxOf(MIN_PROMPT_TOKENS, requiredTokens)
            ).coerceAtLeast(MIN_OUTPUT_TOKENS)
            outputTokens = outputTokens.coerceAtMost(safeOutputLimit)
            val promptBudget = (config.contextTokens - outputTokens - templateMargin)
                .coerceAtLeast(MIN_PROMPT_TOKENS)
            var usedTokens = pinnedTokens + historyList.sumOf(::estimateExchangeTokens)
            if (usedTokens > promptBudget) {
                val availableBudget = (promptBudget - pinnedTokens).coerceAtLeast(0)
                val targetBudget = pinnedTokens + (availableBudget * 0.8).toInt()
                while ((usedTokens > targetBudget) && historyList.isNotEmpty()) {
                    usedTokens -= estimateExchangeTokens(historyList.removeAt(0))
                }
            }
            // Known window: fit turn-local lore/memory into the leftover budget.
            val baseTokens = fixedTokens +
                LlmTokenEstimate.estimateTokens(input.copy(context = emptyList()).textWithContext())
            var optionalBudget = (promptBudget - baseTokens - historyList.sumOf(::estimateExchangeTokens))
                .coerceAtLeast(0)
            val kept = mutableListOf<String>()
            for (block in inputContext) {
                val tokens = LlmTokenEstimate.estimateTokens(block) + 8
                if (tokens <= optionalBudget) {
                    kept.add(block)
                    optionalBudget -= tokens
                }
            }
            inputContext = kept
        } else {
            // Unknown window: preserve the historical fixed cap.
            var optionalBudget = FALLBACK_OPTIONAL_TOKENS.coerceAtLeast(0)
            val kept = mutableListOf<String>()
            for (block in inputContext) {
                val tokens = LlmTokenEstimate.estimateTokens(block) + 8
                if (tokens <= optionalBudget) {
                    kept.add(block)
                    optionalBudget -= tokens
                }
            }
            inputContext = kept
        }

        return config.copy(history = historyList, maxOutputTokens = outputTokens) to
            input.copy(context = inputContext)
    }

    private fun LlmInput.filterBlanks(): LlmInput = copy(context = context.filter(String::isNotBlank))

    private fun estimateExchangeTokens(exchange: LlmExchange): Int =
        LlmTokenEstimate.estimateTokens(exchange.input.textWithContext()) + LlmTokenEstimate.estimateTokens(exchange.assistant) +
            (if (exchange.input.imagePath != null) IMAGE_TOKEN_ESTIMATE else 0) +
            (ROLE_OVERHEAD_TOKENS * 2)
}
