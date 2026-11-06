package com.reiyy.yuitag.apple.anisette

/**
 * Produces anisette data for Apple GSA / iCloud requests.
 */
interface AnisetteProvider {
    /**
     * One-time password and machine id plus the machine's identity:
     * `X-Apple-I-MD`, `X-Apple-I-MD-M`, `X-Apple-I-MD-RINFO`,
     * `X-Apple-I-MD-LU`, `X-Mme-Device-Id`, `X-Apple-I-SRL-NO`,
     * `X-Apple-I-Client-Time`, `X-Apple-I-TimeZone`, `X-Apple-Locale`,
     * `loc`. Provisions the machine with Apple first if it never was.
     */
    suspend fun getHeaders(): Map<String, String>

    /** The machine the headers belong to. Provisions it first if it never was. */
    suspend fun machine(): MachineIdentity

    /**
     * Answers Apple's resync request (`hsc 434`), whose `X-Apple-I-MD-DATA` is [sim].
     */
    suspend fun resync(sim: ByteArray)

    /** Underlying ottjni crate version. */
    suspend fun version(): String
}

/**
 * Machine identity claimed by the emulated Mac.
 */
data class MachineIdentity(
    val deviceId: String,
    val localUserUuid: String,
    val serial: String,
    val clientInfo: String,
) {
    val platform: String get() = clientInfo.substringBeforeLast(" <")

    fun clientInfoFor(bundle: String): String = "$platform <$bundle>"
}
