/*
 * Portions of this file are derived from TagHistory (https://github.com/tieo/TagHistory)
 * Copyright (c) 2025 Shane B.
 * Copyright (c) 2025-2026 tieo.
 *
 * Licensed under the GNU General Public License v2.0 or later.
 */

package com.reiyy.yuitag.apple.plist

/**
 * Typed Apple property-list value tree.
 */
sealed interface PlistValue {
    data object Null : PlistValue

    data class Bool(val value: Boolean) : PlistValue

    data class Int64(val value: Long) : PlistValue

    data class Real(val value: Double) : PlistValue

    data class Str(val value: String) : PlistValue

    class Data(val bytes: ByteArray) : PlistValue {
        override fun equals(other: Any?): Boolean =
            other is Data && bytes.contentEquals(other.bytes)
        override fun hashCode(): Int = bytes.contentHashCode()
        override fun toString(): String = "Data(${bytes.size} bytes)"
    }

    data class Date(val epochMillis: Long) : PlistValue

    data class Uid(val value: Long) : PlistValue

    data class Dict(val entries: Map<String, PlistValue> = emptyMap()) : PlistValue {
        operator fun get(key: String): PlistValue? = entries[key]

        fun string(key: String): String? = (entries[key] as? Str)?.value
        fun data(key: String): ByteArray? = (entries[key] as? Data)?.bytes
        fun int64(key: String): Long? = (entries[key] as? Int64)?.value
        fun bool(key: String): Boolean? = (entries[key] as? Bool)?.value
        fun dict(key: String): Dict? = entries[key] as? Dict
        fun array(key: String): Array? = entries[key] as? Array

        fun opaque(key: String): ByteArray? = when (val v = entries[key]) {
            is Str -> v.value.encodeToByteArray()
            is Data -> v.bytes
            null -> null
            else -> throw IllegalArgumentException("Expected bytes/string for \"$key\", got ${v::class.simpleName}")
        }
    }

    data class Array(val items: List<PlistValue> = emptyList()) : PlistValue {
        val size: Int get() = items.size
        operator fun get(index: Int): PlistValue = items[index]
    }
}

fun plistDictOf(vararg pairs: Pair<String, PlistValue>): PlistValue.Dict =
    PlistValue.Dict(linkedMapOf(*pairs))

fun plistArrayOf(vararg items: PlistValue): PlistValue.Array =
    PlistValue.Array(items.toList())

fun String.asPlist(): PlistValue = PlistValue.Str(this)
fun Long.asPlist(): PlistValue = PlistValue.Int64(this)
fun Int.asPlist(): PlistValue = PlistValue.Int64(toLong())
fun Boolean.asPlist(): PlistValue = PlistValue.Bool(this)
fun ByteArray.asPlist(): PlistValue = PlistValue.Data(this)
fun Double.asPlist(): PlistValue = PlistValue.Real(this)

fun anyToPlist(value: Any?): PlistValue = when (value) {
    null -> PlistValue.Null
    is PlistValue -> value
    is String -> PlistValue.Str(value)
    is Boolean -> PlistValue.Bool(value)
    is Byte -> PlistValue.Int64(value.toLong())
    is Short -> PlistValue.Int64(value.toLong())
    is Int -> PlistValue.Int64(value.toLong())
    is Long -> PlistValue.Int64(value)
    is Float -> PlistValue.Real(value.toDouble())
    is Double -> PlistValue.Real(value)
    is ByteArray -> PlistValue.Data(value)
    is Map<*, *> -> {
        val out = linkedMapOf<String, PlistValue>()
        for ((k, v) in value) {
            val key = k as? String
                ?: throw IllegalArgumentException("Plist dict keys must be String, got ${k?.let { it::class.simpleName }}")
            out[key] = anyToPlist(v)
        }
        PlistValue.Dict(out)
    }
    is List<*> -> PlistValue.Array(value.map { anyToPlist(it) })
    else -> throw IllegalArgumentException("Cannot encode ${value::class.simpleName} as a plist value")
}
