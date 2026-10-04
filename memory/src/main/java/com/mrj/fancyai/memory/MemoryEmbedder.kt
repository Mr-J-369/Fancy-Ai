package com.mrj.fancyai.memory

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import com.mrj.fancyai.engine.MemoryTokenizer
import java.io.File
import java.nio.LongBuffer
import kotlin.math.sqrt

/** Multilingual MiniLM embeddings. Lives in the :memory process, never in UI or chat. */
class MemoryEmbedder(private val directory: File) : AutoCloseable {
    private var tokenizer: MemoryTokenizer? = null
    private var embeddings: OrtSession? = null

    fun embed(text: String): FloatArray {
        val tokenizer = tokenizer ?: MemoryTokenizer(File(directory, "minilm/tokenizer.json").path)
            .also { tokenizer = it }
        val environment = OrtEnvironment.getEnvironment()
        val session = embeddings ?: OrtSession.SessionOptions().use { options ->
            options.setIntraOpNumThreads(4)
            environment.createSession(File(directory, "minilm/model.onnx").path, options)
        }.also { embeddings = it }
        var combined: FloatArray? = null
        // MiniLM uses mean pooling over each window; long sources combine their
        // window vectors so no part of the source is dropped.
        for (window in windows(tokenizer.encode(text))) {
            val mean = embedWindow(environment, session, window)
            val sized = combined ?: FloatArray(mean.size).also { combined = it }
            for (index in sized.indices) sized[index] += mean[index] * (window.size - 2)
        }
        return normalize(requireNotNull(combined))
    }

    private fun embedWindow(
        environment: OrtEnvironment,
        session: OrtSession,
        window: IntArray,
    ): FloatArray {
        val shape = longArrayOf(1, window.size.toLong())
        val ids = OnnxTensor.createTensor(environment, LongBuffer.wrap(LongArray(window.size) { window[it].toLong() }), shape)
        val mask = OnnxTensor.createTensor(environment, LongBuffer.wrap(LongArray(window.size) { 1L }), shape)
        val types = OnnxTensor.createTensor(environment, LongBuffer.wrap(LongArray(window.size)), shape)
        try {
            session.run(mapOf("input_ids" to ids, "attention_mask" to mask, "token_type_ids" to types), setOf("last_hidden_state")).use { output ->
                return meanPool(output[0] as OnnxTensor)
            }
        } finally {
            types.close()
            mask.close()
            ids.close()
        }
    }

    override fun close() {
        embeddings?.close()
        embeddings = null
        tokenizer?.close()
        tokenizer = null
    }

    companion object {
        const val EMBEDDING_MODEL = "Xenova/paraphrase-multilingual-MiniLM-L12-v2@2c4055b12046f11709e9df2c122e59ffbdc2f900/quantized-mean-l2-window512"
        private const val MAX_TOKENS = 512
    }

    private fun windows(tokens: IntArray): Sequence<IntArray> {
        if (tokens.size <= MAX_TOKENS) return sequenceOf(tokens)
        return (1 until tokens.lastIndex step (MAX_TOKENS - 2)).asSequence().map { start ->
            intArrayOf(tokens.first()) + tokens.copyOfRange(start, minOf(start + MAX_TOKENS - 2, tokens.lastIndex)) + intArrayOf(tokens.last())
        }
    }

    private fun meanPool(hidden: OnnxTensor): FloatArray {
        val shape = hidden.info.shape
        val length = shape[1].toInt()
        val dim = shape[2].toInt()
        val values = FloatArray(length * dim)
        hidden.floatBuffer.get(values)
        val mean = FloatArray(dim)
        for (position in 0 until length) {
            for (index in 0 until dim) mean[index] += values[position * dim + index]
        }
        for (index in 0 until dim) mean[index] /= length
        return normalize(mean)
    }

    private fun normalize(vector: FloatArray): FloatArray {
        val length = sqrt(vector.sumOf { it.toDouble() * it })
        for (index in vector.indices) vector[index] = (vector[index] / length).toFloat()
        return vector
    }
}
