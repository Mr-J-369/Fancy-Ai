package com.mrj.fancyai.engine

import androidx.annotation.Keep

/** Uses the model's own tokenizer.json, including its special-token processing. */
@Keep
class MemoryTokenizer(path: String) : AutoCloseable {
    private var handle = create(path)

    @Synchronized
    fun encode(text: String): IntArray {
        check(handle != 0L)
        return encodeNative(handle, text)
    }

    @Synchronized
    fun decode(ids: IntArray): String {
        check(handle != 0L)
        return decodeNative(handle, ids)
    }

    @Synchronized
    override fun close() {
        if (handle != 0L) destroy(handle)
        handle = 0L
    }

    private external fun create(path: String): Long
    private external fun encodeNative(handle: Long, text: String): IntArray
    private external fun decodeNative(handle: Long, ids: IntArray): String
    private external fun destroy(handle: Long)

    companion object {
        init { System.loadLibrary("fancy_memory_tokenizer") }
    }
}
