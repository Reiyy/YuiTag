package com.reiyy.yuitag.apple.crypto

/**
 * GSA login flow crypto helpers: password-to-PBKDF2, SPD AES-CBC decrypt, X9.63 KDF.
 */
object GsaCrypto {

    const val PROTOCOL_S2K = "s2k"
    const val PROTOCOL_S2K_FO = "s2k_fo"

    /**
     * Apple's GSA password pre-hash.
     */
    fun encryptPassword(
        password: String,
        salt: ByteArray,
        iterations: Int,
        protocol: String,
    ): ByteArray {
        require(protocol == PROTOCOL_S2K || protocol == PROTOCOL_S2K_FO) {
            "Unsupported SRP protocol: $protocol"
        }
        val preHash = sha256(password.encodeToByteArray())
        val pbkdfPassword = when (protocol) {
            PROTOCOL_S2K -> preHash
            else -> toLowerHex(preHash).encodeToByteArray()
        }
        return pbkdf2HmacSha256(pbkdfPassword, salt, iterations, 32)
    }

    /**
     * SPD = "Signed Plist Data" blob returned by the GSA server.
     * AES-128-CBC with key and IV derived from the SRP session key via HMAC-SHA256.
     */
    fun decryptSpdAesCbc(sessionKey: ByteArray, ciphertext: ByteArray): ByteArray {
        val extraKey = hmacSha256(sessionKey, "extra data key:".encodeToByteArray())
        val extraIvFull = hmacSha256(sessionKey, "extra data iv:".encodeToByteArray())
        val iv = extraIvFull.copyOfRange(0, 16)
        return aesCbcDecryptPkcs7(extraKey, iv, ciphertext)
    }

    /**
     * ANSI X9.63 KDF with SHA-256.
     */
    fun x963Kdf(secret: ByteArray, sharedInfo: ByteArray, length: Int): ByteArray {
        val hLen = 32
        val blocks = (length + hLen - 1) / hLen
        val out = ByteArray(blocks * hLen)
        for (i in 1..blocks) {
            val block = sha256(secret + intBigEndian(i) + sharedInfo)
            block.copyInto(out, destinationOffset = (i - 1) * hLen)
        }
        return if (out.size == length) out else out.copyOf(length)
    }

    private fun toLowerHex(bytes: ByteArray): String {
        val hexChars = "0123456789abcdef"
        val out = CharArray(bytes.size * 2)
        for (i in bytes.indices) {
            val v = bytes[i].toInt() and 0xFF
            out[i * 2] = hexChars[v ushr 4]
            out[i * 2 + 1] = hexChars[v and 0xF]
        }
        return out.concatToString()
    }

    private fun intBigEndian(value: Int): ByteArray = byteArrayOf(
        ((value ushr 24) and 0xFF).toByte(),
        ((value ushr 16) and 0xFF).toByte(),
        ((value ushr 8) and 0xFF).toByte(),
        (value and 0xFF).toByte(),
    )
}
