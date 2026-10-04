package com.mrj.fancyai.service.memory;

/** Synchronous embedding calls for the private memory process. Callers block on IO threads. */
interface IMemoryService {
    float[] embed(String text);
    void release();
}
