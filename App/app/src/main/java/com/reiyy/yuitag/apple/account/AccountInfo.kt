/*
 * Portions of this file are derived from TagHistory (https://github.com/tieo/TagHistory)
 * Copyright (c) 2025 Shane B.
 * Copyright (c) 2025-2026 tieo.
 *
 * Licensed under the GNU General Public License v2.0 or later.
 */

package com.reiyy.yuitag.apple.account

import com.google.gson.annotations.SerializedName

/**
 * Account information extracted from Apple's SPD payload after SRP authentication.
 */
data class AccountInfo(
    @SerializedName("account_name") val accountName: String? = null,
    @SerializedName("first_name") val firstName: String? = null,
    @SerializedName("last_name") val lastName: String? = null,
    @SerializedName("trusted_device_2fa") var trustedDevice2fa: Boolean = false,
)
