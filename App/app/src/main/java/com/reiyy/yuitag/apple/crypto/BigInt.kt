/*
 * Portions of this file are derived from TagHistory (https://github.com/tieo/TagHistory)
 * Copyright (c) 2025 Shane B.
 * Copyright (c) 2025-2026 tieo.
 *
 * Licensed under the GNU General Public License v2.0 or later.
 */

package com.reiyy.yuitag.apple.crypto

import java.math.BigInteger

/**
 * Arbitrary-precision unsigned integer abstraction for SRP-6a.
 * Only the operations SRP-6a needs are exposed.
 * Matches pysrp's `long_to_bytes` semantics (no leading zero padding).
 */
class BigInt internal constructor(internal val value: BigInteger) : Comparable<BigInt> {
    operator fun plus(other: BigInt): BigInt = BigInt(value.add(other.value))
    operator fun minus(other: BigInt): BigInt = BigInt(value.subtract(other.value))
    operator fun times(other: BigInt): BigInt = BigInt(value.multiply(other.value))
    fun mod(m: BigInt): BigInt = BigInt(value.mod(m.value))
    fun modPow(exponent: BigInt, m: BigInt): BigInt =
        BigInt(value.modPow(exponent.value, m.value))

    fun signum(): Int = value.signum()

    /** Unpadded big-endian byte representation (no leading zero). */
    fun toMinimalBytes(): ByteArray {
        require(value.signum() >= 0) { "Cannot encode negative BigInt" }
        val raw = value.toByteArray()
        return if (raw.size > 1 && raw[0] == 0.toByte()) raw.copyOfRange(1, raw.size) else raw
    }

    override fun compareTo(other: BigInt): Int = value.compareTo(other.value)
    override fun equals(other: Any?): Boolean = other is BigInt && value == other.value
    override fun hashCode(): Int = value.hashCode()
    override fun toString(): String = value.toString()
}

fun bigIntFromBytes(bytes: ByteArray): BigInt = BigInt(BigInteger(1, bytes))
fun bigIntFromString(value: String, radix: Int = 10): BigInt = BigInt(BigInteger(value, radix))
fun bigIntOf(value: Long): BigInt = BigInt(BigInteger.valueOf(value))
