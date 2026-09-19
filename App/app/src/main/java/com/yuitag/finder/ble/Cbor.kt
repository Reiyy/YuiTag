package com.yuitag.finder.ble

import java.io.ByteArrayOutputStream

/**
 * 极简 CBOR (RFC 8949) 编解码器，仅覆盖 MCUmgr SMP 负载所需类型：
 * uint / nint / bstr / tstr / array / map / bool / null。
 * 无第三方依赖，编码使用定长（definite-length）格式，与 zcbor 兼容。
 */
sealed class CborValue {
    data class UInt(val value: Long) : CborValue()
    data class NInt(val value: Long) : CborValue()   // 实际值 = -1 - value
    data class Bytes(val value: ByteArray) : CborValue()
    data class Text(val value: String) : CborValue()
    data class Bool(val value: Boolean) : CborValue()
    data class ArrayVal(val items: List<CborValue>) : CborValue()
    data class MapVal(val entries: LinkedHashMap<String, CborValue>) : CborValue()
    object Null : CborValue()
    object Other : CborValue()

    fun asLong(): Long? = when (this) {
        is UInt -> value
        is NInt -> -1L - value
        else -> null
    }

    fun asBytes(): ByteArray? = (this as? Bytes)?.value

    fun asText(): String? = (this as? Text)?.value

    fun asBool(): Boolean? = (this as? Bool)?.value

    fun asMap(): LinkedHashMap<String, CborValue>? = (this as? MapVal)?.entries

    fun asArray(): List<CborValue>? = (this as? ArrayVal)?.items
}

object Cbor {

    // --- 编码 ---

    fun encodeUInt(v: Long): ByteArray = encodeHead(0, v)

    fun encodeInt(v: Long): ByteArray =
        if (v >= 0) encodeHead(0, v) else encodeHead(1, -1L - v)

    fun encodeBytes(b: ByteArray): ByteArray = encodeHead(2, b.size.toLong()) + b

    fun encodeText(s: String): ByteArray {
        val b = s.toByteArray(Charsets.UTF_8)
        return encodeHead(3, b.size.toLong()) + b
    }

    fun encodeBool(b: Boolean): ByteArray =
        byteArrayOf(((7 shl 5) or if (b) 21 else 20).toByte())

    fun encodeArray(items: List<ByteArray>): ByteArray {
        val body = ByteArrayOutputStream()
        for (it in items) body.write(it)
        return encodeHead(4, items.size.toLong()) + body.toByteArray()
    }

    /** entries: (key 字符串, 已编码的 value 字节) */
    fun encodeMap(entries: List<Pair<String, ByteArray>>): ByteArray {
        val body = ByteArrayOutputStream()
        for ((k, v) in entries) {
            body.write(encodeText(k))
            body.write(v)
        }
        return encodeHead(5, entries.size.toLong()) + body.toByteArray()
    }

    private fun encodeHead(major: Int, value: Long): ByteArray {
        val mt = major shl 5
        return when {
            value < 24L ->
                byteArrayOf((mt or value.toInt()).toByte())
            value < 0x100L ->
                byteArrayOf((mt or 24).toByte(), value.toByte())
            value < 0x10000L ->
                byteArrayOf((mt or 25).toByte(),
                    ((value shr 8) and 0xFF).toByte(), (value and 0xFF).toByte())
            value < 0x100000000L ->
                byteArrayOf((mt or 26).toByte(),
                    ((value shr 24) and 0xFF).toByte(),
                    ((value shr 16) and 0xFF).toByte(),
                    ((value shr 8) and 0xFF).toByte(),
                    (value and 0xFF).toByte())
            else ->
                byteArrayOf((mt or 27).toByte(),
                    ((value shr 56) and 0xFF).toByte(),
                    ((value shr 48) and 0xFF).toByte(),
                    ((value shr 40) and 0xFF).toByte(),
                    ((value shr 32) and 0xFF).toByte(),
                    ((value shr 24) and 0xFF).toByte(),
                    ((value shr 16) and 0xFF).toByte(),
                    ((value shr 8) and 0xFF).toByte(),
                    (value and 0xFF).toByte())
        }
    }

    // --- 解码 ---

    fun decode(data: ByteArray): CborValue {
        val r = Reader(data)
        val v = decodeValue(r)
        return v
    }

    private class Reader(val data: ByteArray) {
        var pos = 0
        fun hasRemaining(): Boolean = pos < data.size
        fun peekByte(): Int = if (pos < data.size) (data[pos].toInt() and 0xFF) else -1
        fun readByte(): Int = (data[pos++].toInt() and 0xFF)
        fun readBytes(n: Int): ByteArray {
            val out = data.copyOfRange(pos, pos + n)
            pos += n
            return out
        }
    }

    private fun decodeValue(r: Reader): CborValue {
        val initial = r.readByte()
        val major = initial ushr 5
        val ai = initial and 0x1F
        return when (major) {
            0 -> CborValue.UInt(readLength(r, ai))
            1 -> CborValue.NInt(readLength(r, ai))
            2 -> CborValue.Bytes(r.readBytes(readLength(r, ai).toInt()))
            3 -> CborValue.Text(String(r.readBytes(readLength(r, ai).toInt()), Charsets.UTF_8))
            4 -> {
                if (ai == 31) {
                    // RFC 8949 不定长 Array (0x9F ... 0xFF)
                    val items = mutableListOf<CborValue>()
                    while (r.hasRemaining() && r.peekByte() != 0xFF) {
                        items.add(decodeValue(r))
                    }
                    if (r.hasRemaining() && r.peekByte() == 0xFF) {
                        r.readByte() // 消耗 break (0xFF)
                    }
                    CborValue.ArrayVal(items)
                } else {
                    val n = readLength(r, ai).toInt()
                    CborValue.ArrayVal((0 until n).map { decodeValue(r) })
                }
            }
            5 -> {
                val m = LinkedHashMap<String, CborValue>()
                if (ai == 31) {
                    // RFC 8949 不定长 Map (0xBF ... 0xFF)，Zephyr zcbor 默认编码格式
                    while (r.hasRemaining() && r.peekByte() != 0xFF) {
                        val k = decodeValue(r)
                        if (r.hasRemaining() && r.peekByte() == 0xFF) {
                            r.readByte()
                            break
                        }
                        val v = decodeValue(r)
                        m[(k as? CborValue.Text)?.value ?: k.toString()] = v
                    }
                    if (r.hasRemaining() && r.peekByte() == 0xFF) {
                        r.readByte() // 消耗 break (0xFF)
                    }
                } else {
                    val n = readLength(r, ai).toInt()
                    for (i in 0 until n) {
                        val k = decodeValue(r)
                        val v = decodeValue(r)
                        m[(k as? CborValue.Text)?.value ?: k.toString()] = v
                    }
                }
                CborValue.MapVal(m)
            }
            6 -> {
                // 语义 tag：跳过 tag 号，解析其内容
                readLength(r, ai)
                decodeValue(r)
            }
            7 -> when (ai) {
                20 -> CborValue.Bool(false)
                21 -> CborValue.Bool(true)
                31 -> CborValue.Other // 0xFF break 码
                else -> CborValue.Null
            }
            else -> CborValue.Other
        }
    }

    private fun readLength(r: Reader, ai: Int): Long = when (ai) {
        in 0..23 -> ai.toLong()
        24 -> r.readByte().toLong()
        25 -> ((r.readByte().toLong() shl 8) or r.readByte().toLong())
        26 -> ((r.readByte().toLong() shl 24) or
            (r.readByte().toLong() shl 16) or
            (r.readByte().toLong() shl 8) or
            r.readByte().toLong())
        27 -> {
            var v = 0L
            repeat(8) { v = (v shl 8) or r.readByte().toLong() }
            v
        }
        else -> 0L
    }
}
