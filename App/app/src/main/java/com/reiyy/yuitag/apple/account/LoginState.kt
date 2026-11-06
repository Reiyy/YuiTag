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
