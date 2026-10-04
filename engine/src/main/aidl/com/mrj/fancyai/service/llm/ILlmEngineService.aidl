package com.mrj.fancyai.service.llm;

import com.mrj.fancyai.service.llm.ILlmEngineCallback;
import android.os.SharedMemory;

/** Non-blocking command surface for the private local-model engine process. */
oneway interface ILlmEngineService {
    void open(long requestId, in SharedMemory request, ILlmEngineCallback callback);
    void generate(long requestId, in SharedMemory request, boolean thinking, ILlmEngineCallback callback);
    void cancel(long requestId);
    void shutdown(long requestId, ILlmEngineCallback callback);
}
