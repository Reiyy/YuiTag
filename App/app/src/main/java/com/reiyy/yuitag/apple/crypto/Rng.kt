/*
 * Portions of this file are derived from TagHistory (https://github.com/tieo/TagHistory)
 * Copyright (c) 2025 Shane B.
 * Copyright (c) 2025-2026 tieo.
 *
 * Licensed under the GNU General Public License v2.0 or later.
 */

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
