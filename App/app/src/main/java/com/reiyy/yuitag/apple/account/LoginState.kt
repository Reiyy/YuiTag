/*
 * Portions of this file are derived from TagHistory (https://github.com/tieo/TagHistory)
 * Copyright (c) 2025 Shane B.
 * Copyright (c) 2025-2026 tieo.
 *
 * Licensed under the GNU General Public License v2.0 or later.
 */

package com.reiyy.yuitag.apple.account

/**
 * State machine position of an [AppleAccount].
 */
enum class LoginState(val value: Int) {
    LOGGED_OUT(0),
    REQUIRE_2FA(1),
    AUTHENTICATED(2),
    LOGGED_IN(3);

    companion object {
        fun fromValue(v: Int): LoginState = entries.firstOrNull { it.value == v }
            ?: throw IllegalArgumentException("Unknown login state value: $v")
    }
}
