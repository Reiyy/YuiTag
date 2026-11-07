/*
 * Portions of this file are derived from TagHistory (https://github.com/tieo/TagHistory)
 * Copyright (c) 2025 Shane B.
 * Copyright (c) 2025-2026 tieo.
 *
 * Licensed under the GNU General Public License v2.0 or later.
 */

package com.reiyy.yuitag.apple.account

/**
 * Outcome of an Apple login attempt or a 2FA challenge submission.
 */
sealed interface LoginResult {
    data object LoggedIn : LoginResult
    data class RequireTwoFactor(val methods: List<TwoFactorChallenge>) : LoginResult
}
