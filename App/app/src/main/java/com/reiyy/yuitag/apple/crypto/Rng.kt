package com.reiyy.yuitag.apple.crypto

import java.security.SecureRandom

/**
 * Cryptographically secure random source.
 */
fun interface Rng {
    fun nextBytes(out: ByteArray)
}

fun secureRng(): Rng {
    val sr = SecureRandom()
    return Rng { out -> sr.nextBytes(out) }
}
