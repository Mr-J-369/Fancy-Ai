package com.mrj.fancyai.domain.inference

/** A single turn in a conversation, independent of any model-specific syntax. */
data class ChatTurn(val role: Role, val content: String) {
    enum class Role { USER, ASSISTANT }
}

/**
 * Prompt-formatting templates for local models. Each model family expects its own
 * control tokens around the system prompt and conversation turns; this mirrors the
 * formats the original JS engine (api.js) supported so behaviour is unchanged.
 */
enum class ChatTemplate(val id: String) {
    CHATML("chatml"),
    LLAMA3("llama3"),
    GEMMA("gemma"),
    MISTRAL("mistral"),
    ALPACA("alpaca");

    /** Renders [system] + [history] into a single completion prompt ending at the model's turn. */
    fun render(system: String, history: List<ChatTurn>): String = when (this) {
        LLAMA3 -> buildString {
            append("<|begin_of_text|><|start_header_id|>system<|end_header_id|>\n\n")
            append(system).append("<|eot_id|>")
            for (turn in history) {
                val role = if (turn.role == ChatTurn.Role.USER) "user" else "assistant"
                append("<|start_header_id|>").append(role).append("<|end_header_id|>\n\n")
                append(turn.content).append("<|eot_id|>")
            }
            append("<|start_header_id|>assistant<|end_header_id|>\n\n")
        }

        GEMMA -> buildString {
            append("<bos>")
            var first = true
            for (turn in history) {
                if (turn.role == ChatTurn.Role.USER) {
                    append("<start_of_turn>user\n")
                    if (first && system.isNotBlank()) append(system).append("\n\n")
                    append(turn.content).append("<end_of_turn>\n")
                    first = false
                } else {
                    append("<start_of_turn>model\n").append(turn.content).append("<end_of_turn>\n")
                }
            }
            append("<start_of_turn>model\n")
        }

        MISTRAL -> buildString {
            var first = true
            for (turn in history) {
                if (turn.role == ChatTurn.Role.USER) {
                    if (first) {
                        append("[INST] ")
                        if (system.isNotBlank()) append("<<SYS>>\n").append(system).append("\n<</SYS>>\n\n")
                        append(turn.content).append(" [/INST] ")
                        first = false
                    } else {
                        append("</s>[INST] ").append(turn.content).append(" [/INST] ")
                    }
                } else {
                    append(turn.content)
                }
            }
        }

        ALPACA -> buildString {
            // Alpaca has no multi-turn format; use the most recent user message only.
            val lastUser = history.lastOrNull { it.role == ChatTurn.Role.USER }?.content ?: ""
            append("### System:\n").append(system).append("\n\n")
            append("### Instruction:\n").append(lastUser).append("\n\n")
            append("### Response:\n")
        }

        CHATML -> buildString { // works for Llama 3, Mistral, Qwen, Phi
            append("<|im_start|>system\n")
            append(system.ifBlank { "You are a helpful assistant." }).append("<|im_end|>\n")
            for (turn in history) {
                val role = if (turn.role == ChatTurn.Role.USER) "user" else "assistant"
                append("<|im_start|>").append(role).append("\n").append(turn.content).append("<|im_end|>\n")
            }
            append("<|im_start|>assistant\n")
        }
    }

    companion object {
        fun from(id: String?): ChatTemplate =
            entries.firstOrNull { it.id.equals(id, ignoreCase = true) } ?: CHATML

        /**
         * Detects the prompt format from a model's embedded chat template (the Jinja
         * string in GGUF metadata), so the right control tokens are used automatically.
         * Returns null when the format isn't recognized (caller keeps its current choice).
         */
        fun detectFrom(embeddedTemplate: String): ChatTemplate? {
            val t = embeddedTemplate
            return when {
                t.contains("<|start_header_id|>") || t.contains("<|eot_id|>") -> LLAMA3
                t.contains("<start_of_turn>") -> GEMMA
                t.contains("<|im_start|>") -> CHATML
                t.contains("[INST]") -> MISTRAL
                else -> null
            }
        }
    }
}
