package com.mrj.fancyai.service.llm;

import com.mrj.fancyai.service.llm.LlmChunk;
import com.mrj.fancyai.service.llm.LlmTerminal;

/** Ordered events emitted by the private local-model engine process. */
oneway interface ILlmEngineCallback {
    void onReady(long requestId);
    void onChunk(in LlmChunk chunk);
    void onTerminal(in LlmTerminal terminal);
    void onEvicted(in LlmTerminal terminal);
    void onShutdownReady(long requestId);
}
