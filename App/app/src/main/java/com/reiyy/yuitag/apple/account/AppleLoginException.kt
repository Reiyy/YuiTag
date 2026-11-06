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
