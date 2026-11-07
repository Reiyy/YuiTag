/*
 * Portions of this file are derived from TagHistory (https://github.com/tieo/TagHistory)
 * Copyright (c) 2025 Shane B.
 * Copyright (c) 2025-2026 tieo.
 *
 * Licensed under the GNU General Public License v2.0 or later.
 */

package com.reiyy.yuitag.apple.account

sealed class TwoFactorChallenge {
    abstract val displayName: String
    protected abstract val coordinator: TwoFactorCoordinator

    suspend fun request() = when (this) {
        is Sms -> coordinator.requestSms(phoneNumberId)
        is TrustedDevice -> coordinator.requestTrustedDevice()
    }

    suspend fun submit(code: String): LoginResult = when (this) {
        is Sms -> coordinator.submitSms(phoneNumberId, code)
        is TrustedDevice -> coordinator.submitTrustedDevice(code)
    }

    @ConsistentCopyVisibility
    data class Sms internal constructor(
        val phoneNumberId: Int,
        val phoneNumber: String,
        override val coordinator: TwoFactorCoordinator,
    ) : TwoFactorChallenge() {
        override val displayName: String get() = "短信验证码 ($phoneNumber)"
    }

    @ConsistentCopyVisibility
    data class TrustedDevice internal constructor(
        override val coordinator: TwoFactorCoordinator,
    ) : TwoFactorChallenge() {
        override val displayName: String get() = "受信任 Apple 设备弹窗提示"
    }
}

interface TwoFactorCoordinator {
    suspend fun requestSms(phoneNumberId: Int)
    suspend fun submitSms(phoneNumberId: Int, code: String): LoginResult
    suspend fun requestTrustedDevice()
    suspend fun submitTrustedDevice(code: String): LoginResult
}
