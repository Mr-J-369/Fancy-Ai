package com.mrj.fancyai.ui.aura

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import java.io.EOFException
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * An fp8 DiT safetensors' non-matmul fp8 tensors, widened while the import copies the file.
 *
 * The engine keeps F8_E4M3 native, which only the HTP matmul runs; anything else (norm scales,
 * modulations) aborts. Files that work keep only 2-D matmul weights in fp8 and every 1-D tensor
 * in higher precision. So non-matmul fp8 becomes F32 with the same values, E5M2 matmuls become
 * E4M3 by nearest rounding (same size), and ComfyUI's scaled_fp8 marker is left alone.
 */
internal object AuraFp8Widen {
    private const val F8 = "F8_E4M3"
    private const val E5M2 = "F8_E5M2"
    private const val MARKER = "scaled_fp8"
    private const val MAX_HEADER = 64L shl 20

    internal enum class Act { F32, E4M3 }

    internal fun actOf(name: String, dtype: String, shape: List<Long>): Act? {
        if (name == MARKER) return if (dtype == E5M2) Act.E4M3 else null
        if ((dtype != F8) && (dtype != E5M2)) return null
        val matmul = (shape.size == 2) && ((shape[1] % 32) == 0L) && (shape[1] > 0) && (shape[0] >= 32)
        return when {
            !matmul -> Act.F32
            dtype == E5M2 -> Act.E4M3
            else -> null
        }
    }

    fun copy(input: InputStream, out: OutputStream, onBytes: (Long) -> Unit = {}): Int {
        val lenBytes = ByteArray(8)
        if (input.readFully(lenBytes) != 8) throw EOFException("this file is too small to be a checkpoint")
        val headerLen = ByteBuffer.wrap(lenBytes).order(ByteOrder.LITTLE_ENDIAN).long
        if (headerLen !in (2..MAX_HEADER)) throw IOException("this is not a .safetensors file (its header claims $headerLen bytes)")
        val headerBytes = ByteArray(headerLen.toInt())
        if (input.readFully(headerBytes).toLong() != headerLen) throw EOFException("the file ends inside its header")
        var read = 8L + headerLen
        val header = kotlinx.serialization.json.Json.parseToJsonElement(String(headerBytes, Charsets.UTF_8)).jsonObject
        data class Entry(val name: String, val dtype: String, val shape: JsonArray, val begin: Long, val end: Long)
        val entries = header.keys.asSequence().filter { it != "__metadata__" }
            .mapNotNull { k -> (header[k] as? JsonObject)?.let { k to it } }.toList()
        val acts = entries.mapNotNull { (n, t) ->
            val shape = (t["shape"] as? JsonArray)?.map { it.jsonPrimitive.long } ?: emptyList()
            actOf(n, (t["dtype"] as? JsonPrimitive)?.content.orEmpty(), shape)?.let { n to it }
        }.toMap()
        val buf = ByteArray(1 shl 20)
        fun pump(count: Long) {
            var left = count
            while (left > 0) {
                val n = input.read(buf, 0, minOf(buf.size.toLong(), left).toInt())
                if (n < 0) throw EOFException("the file ends inside its data")
                out.write(buf, 0, n)
                left -= n
                read += n
                onBytes(read)
            }
        }
        if (acts.isEmpty()) {
            out.write(lenBytes)
            out.write(headerBytes)
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                out.write(buf, 0, n)
                read += n
                onBytes(read)
            }
            return 0
        }
        val tensors = entries.asSequence().map { (n, t) ->
            val offsets = t["data_offsets"] as? JsonArray
            val begin = offsets?.getOrNull(0)?.jsonPrimitive?.long
            val end = offsets?.getOrNull(1)?.jsonPrimitive?.long
            if ((offsets == null) || (offsets.size != 2) || (begin == null) || (end == null) || (begin < 0) || (end < begin)) {
                throw IOException("tensor '$n' has a broken place in the file")
            }
            Entry(n, (t["dtype"] as? JsonPrimitive)?.content.orEmpty(), (t["shape"] as? JsonArray) ?: JsonArray(emptyList()), begin, end)
        }.sortedBy(Entry::begin)
        val newHeader = buildJsonObject {
            (header["__metadata__"] as? JsonObject)?.let { put("__metadata__", it) }
            var at = 0L
            for ((name, dtype, shape, begin, end) in tensors) {
                val size = if (acts[name] == Act.F32) (end - begin) * 4 else end - begin
                put(
                    name,
                    buildJsonObject {
                        put("dtype", JsonPrimitive(when (acts[name]) { Act.F32 -> "F32"; Act.E4M3 -> F8; null -> dtype }))
                        put("shape", shape)
                        put("data_offsets", JsonArray(listOf(JsonPrimitive(at), JsonPrimitive(at + size))))
                    },
                )
                at += size
            }
        }
        var json = newHeader.toString().toByteArray()
        val pad = (8 - (json.size % 8)) % 8
        if (pad > 0) json += ByteArray(pad) { ' '.code.toByte() }
        out.write(ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN).putLong(json.size.toLong()).array())
        out.write(json)
        var pos = 0L
        for ((name, dtype, _, begin, end) in tensors) {
            if (begin < pos) throw IOException("tensor '$name' overlaps the one before it")
            if (begin > pos) {
                var skip = begin - pos
                while (skip > 0) {
                    val n = input.skip(skip)
                    if (n <= 0) throw EOFException("the file ends inside '$name'")
                    skip -= n
                    read += n
                }
            }
            val act = acts[name]
            if (act != null) {
                val e5m2 = dtype == E5M2
                var left = end - begin
                while (left > 0) {
                    val n = minOf(left, buf.size.toLong()).toInt()
                    if (input.readFully(buf, 0, n) != n) throw EOFException("the file ends inside '$name'")
                    if (act == Act.F32) out.write(toF32(buf, n, e5m2))
                    else {
                        for (i in 0 until n) buf[i] = E5M2_TO_E4M3[buf[i].toInt() and 0xFF]
                        out.write(buf, 0, n)
                    }
                    left -= n
                    read += n
                    onBytes(read)
                }
            } else pump(end - begin)
            pos = end
        }
        return acts.size
    }

    private fun InputStream.readFully(buf: ByteArray, offset: Int = 0, length: Int = buf.size - offset): Int {
        var got = 0
        while (got < length) {
            val n = read(buf, offset + got, length - got)
            if (n < 0) break
            got += n
        }
        return got
    }

    private val LUT = FloatArray(256) { code ->
        val sign = if ((code and 0x80) != 0) -1f else 1f
        val exp = (code shr 3) and 0xF
        val man = code and 0x7
        when (exp) {
            0xF -> if (man == 0x7) Float.NaN else sign * (1f + (man / 8f)) * Math.scalb(1f, exp - 7)
            0 -> sign * (man / 8f) * Math.scalb(1f, -6)
            else -> sign * (1f + (man / 8f)) * Math.scalb(1f, exp - 7)
        }
    }

    private val LUT_E5M2 = FloatArray(256) { code ->
        val sign = if ((code and 0x80) != 0) -1f else 1f
        val exp = (code shr 2) and 0x1F
        val man = code and 0x3
        when (exp) {
            0x1F -> if (man == 0) sign * Float.POSITIVE_INFINITY else Float.NaN
            0 -> sign * ((man / 4f)) * Math.scalb(1f, -14)
            else -> sign * (1f + (man / 4f)) * Math.scalb(1f, exp - 15)
        }
    }

    internal val E5M2_TO_E4M3 = ByteArray(256) { code ->
        val v = LUT_E5M2[code]
        when {
            v.isNaN() -> 0x7F.toByte()
            v.isInfinite() || kotlin.math.abs(v) >= 448f -> (if (v < 0) 0xFE else 0x7E).toByte()
            else -> {
                var best = 0
                var bestErr = Float.MAX_VALUE
                for (c in 0 until 256) {
                    val w = LUT[c]
                    if (w.isNaN()) continue
                    if (((c and 0x80) != 0) != ((code and 0x80) != 0)) continue
                    val err = kotlin.math.abs(w - v)
                    if (err < bestErr || (err == bestErr && c % 2 == 0)) { best = c; bestErr = err }
                }
                best.toByte()
            }
        }
    }

    internal fun toF32(raw: ByteArray, count: Int = raw.size, e5m2: Boolean = false): ByteArray {
        val dst = ByteBuffer.allocate(count * 4).order(ByteOrder.LITTLE_ENDIAN)
        val lut = if (e5m2) LUT_E5M2 else LUT
        for (i in 0 until count) dst.putFloat(lut[raw[i].toInt() and 0xFF])
        return dst.array()
    }
}
