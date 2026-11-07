/*
 * Portions of this file are derived from TagHistory (https://github.com/tieo/TagHistory)
 * Copyright (c) 2025 Shane B.
 * Copyright (c) 2025-2026 tieo.
 *
 * Licensed under the GNU General Public License v2.0 or later.
 */

package com.reiyy.yuitag.apple.anisette

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Builds the anisette part of Apple requests from [provider].
 * Provider values are cached for [CACHE_TTL_SECONDS] seconds.
 */
class AnisetteClient(
    private val provider: AnisetteProvider,
    private val clockMillis: () -> Long = { System.currentTimeMillis() },
) {
    private var cachedHeaders: Map<String, String>? = null
    private var cacheExpiresAtMs: Long = 0L
    private val refreshLock = Mutex()

    /** The machine every request is sent as. */
    suspend fun machine(): MachineIdentity = provider.machine()

    /**
     * Anisette headers for a request. [withClientInfo] adds the client info
     * trio expected by the 2FA endpoints (Xcode identity).
     */
    suspend fun getHeaders(withClientInfo: Boolean = false): Map<String, String> {
        val headers = LinkedHashMap(fetchAnisetteDict())
        if (withClientInfo) {
            headers["X-Mme-Client-Info"] = machine().clientInfoFor(XCODE_BUNDLE)
            headers["X-Apple-App-Info"] = "com.apple.gs.xcode.auth"
            headers["X-Xcode-Version"] = "11.2 (11B41)"
        }
        return headers
    }

    /**
     * "Client provided data": embedded in GSA SRP request body.
     */
    suspend fun getCpd(): Map<String, Any> {
        val out = linkedMapOf<String, Any>()
        out["bootstrap"] = true
        out["icscrec"] = true
        out["pbe"] = false
        out["prkgen"] = true
        out["svct"] = "iCloud"
        out.putAll(fetchAnisetteDict())
        return out
    }

    /**
     * Answers Apple's resync request (`hsc 434`) and invalidates cached headers.
     */
    suspend fun resync(sim: ByteArray) {
        refreshLock.withLock {
            provider.resync(sim)
            cachedHeaders = null
            cacheExpiresAtMs = 0L
        }
    }

    private suspend fun fetchAnisetteDict(): Map<String, String> {
        cachedHeaders?.let { if (clockMillis() < cacheExpiresAtMs) return it }

        return refreshLock.withLock {
            cachedHeaders?.let { if (clockMillis() < cacheExpiresAtMs) return@withLock it }

            val fresh = provider.getHeaders()
            cachedHeaders = fresh
            cacheExpiresAtMs = clockMillis() + CACHE_TTL_SECONDS * 1000L
            fresh
        }
    }

    companion object {
        const val CACHE_TTL_SECONDS: Int = 30
        const val XCODE_BUNDLE = "com.apple.AOSKit/282 (com.apple.dt.Xcode/3594.4.19)"
    }
}
