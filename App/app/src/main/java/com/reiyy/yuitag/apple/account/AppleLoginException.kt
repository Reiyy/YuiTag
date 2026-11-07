/*
 * Portions of this file are derived from TagHistory (https://github.com/tieo/TagHistory)
 * Copyright (c) 2025 Shane B.
 * Copyright (c) 2025-2026 tieo.
 *
 * Licensed under the GNU General Public License v2.0 or later.
 */

package com.reiyy.yuitag.apple.account

/**
 * Failure modes produced during Apple login or 2FA orchestration.
 */
class AppleLoginException(
    val kind: Kind,
    message: String,
    cause: Throwable? = null,
) : RuntimeException(message, cause) {

    enum class Kind {
        INVALID_CREDENTIALS,
        UNHANDLED_PROTOCOL,
        INVALID_STATE,
        UNAUTHORIZED,
    }
}
