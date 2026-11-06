package com.reiyy.yuitag.apple.account

/**
 * Outcome of an Apple login attempt or a 2FA challenge submission.
 */
sealed interface LoginResult {
    data object LoggedIn : LoginResult
    data class RequireTwoFactor(val methods: List<TwoFactorChallenge>) : LoginResult
}
