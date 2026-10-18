package com.mrj.fancyai.service.llm;

import com.mrj.fancyai.service.llm.ILlmEngineCallback;
import android.os.SharedMemory;

/** Command surface for the private local-model engine process. Lifecycle commands stay
 * fire-and-forget; token counting is a synchronous query on the loaded vocab. */
interface ILlmEngineService {
    oneway void open(long requestId, in SharedMemory request, ILlmEngineCallback callback);
    oneway void generate(long requestId, in SharedMemory request, boolean thinking, ILlmEngineCallback callback);
    oneway void cancel(long requestId);
    oneway void shutdown(long requestId, ILlmEngineCallback callback);
    int[] countTokens(in String[] texts);
    boolean saveKvCache(String path);
    boolean loadKvCache(String path);
}
