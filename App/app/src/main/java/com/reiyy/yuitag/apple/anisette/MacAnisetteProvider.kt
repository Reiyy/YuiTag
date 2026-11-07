/*
 * Portions of this file are derived from TagHistory (https://github.com/tieo/TagHistory)
 * Copyright (c) 2025 Shane B.
 * Copyright (c) 2025-2026 tieo.
 *
 * Licensed under the GNU General Public License v2.0 or later.
 */

package com.reiyy.yuitag.apple.anisette

import com.google.gson.Gson
import com.reiyy.yuitag.apple.gsa.GSA_CONNECTION
import com.reiyy.yuitag.apple.http.HttpRequest
import com.reiyy.yuitag.apple.http.HttpTransport
import com.reiyy.yuitag.apple.plist.PlistValue
import com.reiyy.yuitag.apple.plist.XmlPlist
import com.reiyy.yuitag.apple.plist.anyToPlist
import io.github.tieo.taghistory.anisette.AnisetteException
import io.github.tieo.taghistory.anisette.CoreAdi
import io.github.tieo.taghistory.anisette.MachineStorage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.time.Instant
import kotlin.coroutines.CoroutineContext
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlin.random.Random

/**
 * Anisette provider using Apple's macOS CoreADI run under Unicorn x86_64 emulation.
 * Registers a virtual Mac identity with Apple's MidService once, saving state to [storage].
 */
@OptIn(ExperimentalEncodingApi::class)
class MacAnisetteProvider(
    private val openAdi: (hostUuid: ByteArray, store: ByteArray) -> CoreAdi,
    private val storage: MachineStorage,
    private val http: HttpTransport,
    private val nativeVersion: () -> String,
    private val clockMillis: () -> Long = { System.currentTimeMillis() },
    private val random: Random = Random.Default,
    private val blockingContext: CoroutineContext = Dispatchers.IO,
) : AnisetteProvider {

    private val lock = Mutex()
    private var machine: MachineFile? = null
    private var adi: CoreAdi? = null
    private var provisionedChecked = false

    override suspend fun version(): String = nativeVersion()

    override suspend fun machine(): MachineIdentity = serialized { provisioned().first.identity() }

    override suspend fun getHeaders(): Map<String, String> = serialized {
        val (file, core) = provisioned()
        val headers = core.headers()
        save(file, core)
        linkedMapOf(
            "X-Apple-I-Client-Time" to isoTime(),
            "X-Apple-I-TimeZone" to "UTC",
            "loc" to LOCALE,
            "X-Apple-Locale" to LOCALE,
            "X-Apple-I-MD" to Base64.encode(headers.otp),
            "X-Apple-I-MD-LU" to Base64.encode(file.localUserUuid.encodeToByteArray()),
            "X-Apple-I-MD-M" to Base64.encode(headers.mid),
            "X-Apple-I-MD-RINFO" to headers.routing.toString(),
            "X-Mme-Device-Id" to file.deviceId.uppercase(),
            "X-Apple-I-SRL-NO" to file.serial,
        )
    }

    override suspend fun resync(sim: ByteArray) = serialized {
        val (file, core) = provisioned()
        val sync = core.sync(sim)
        save(file, core)
        val urls = urlBag(file)
        val request = linkedMapOf<String, Any?>(
            "srm" to Base64.encode(sync.srm),
            "X-Apple-I-MD-M" to Base64.encode(sync.mid),
        )
        file.routing?.let { request["X-Apple-I-MD-RINFO"] = it }
        val status = midService(file, urls.require("midSyncMachine"), request).dict("Status")
        val ec = status?.int64("ec")
        if (ec != 0L) {
            throw AnisetteException("Apple refused the machine's resync: ec=$ec ${status?.string("em").orEmpty()}")
        }
    }

    private suspend fun <T> serialized(block: suspend () -> T): T =
        lock.withLock { withContext(blockingContext) { block() } }

    private suspend fun provisioned(): Pair<MachineFile, CoreAdi> {
        val file = machine ?: load().also { machine = it }
        val core = adi ?: openAdi(file.hostUuidBytes(), file.storeBytes()).also { adi = it }
        if (!provisionedChecked) {
            if (!core.isProvisioned()) provision(file, core)
            provisionedChecked = true
        }
        return machine!! to core
    }

    private suspend fun provision(file: MachineFile, core: CoreAdi) {
        val urls = urlBag(file)
        val spim = midService(file, urls.require("midStartProvisioning"), emptyMap()).string("spim")
            ?: throw AnisetteException("midStartProvisioning gave no spim")
        val begun = core.provisioningBegin(Base64.decode(spim))
        val finished = midService(
            file,
            urls.require("midFinishProvisioning"),
            mapOf("cpim" to Base64.encode(begun.cpim)),
        )
        val ptm = finished.string("ptm") ?: throw AnisetteException("midFinishProvisioning gave no ptm")
        val tk = finished.string("tk") ?: throw AnisetteException("midFinishProvisioning gave no tk")
        val routing = when (val r = finished["X-Apple-I-MD-RINFO"]) {
            is PlistValue.Int64 -> r.value
            is PlistValue.Str -> r.value.toLongOrNull() ?: 0L
            else -> 0L
        }
        core.provisioningFinish(begun.session, Base64.decode(ptm), Base64.decode(tk), routing)
        val updated = file.copy(routing = routing, provisionedAtMs = clockMillis())
        machine = updated
        save(updated, core)
    }

    private fun load(): MachineFile {
        storage.read()?.let { bytes ->
            runCatching { GSON.fromJson(bytes.decodeToString(), MachineFile::class.java) }
                .getOrNull()
                ?.takeIf { it.version == MachineFile.VERSION }
                ?.let { return it }
        }
        return mint().also { storage.write(GSON.toJson(it).encodeToByteArray()) }
    }

    private fun save(file: MachineFile, core: CoreAdi) {
        val updated = file.copy(storeB64 = Base64.encode(core.store()))
        machine = updated
        storage.write(GSON.toJson(updated).encodeToByteArray())
    }

    private fun mint(): MachineFile = MachineFile(
        version = MachineFile.VERSION,
        hostUuid = random.nextBytes(16).toHex(),
        deviceId = "00006000-" + random.nextBytes(8).toHex().uppercase(),
        localUserUuid = random.nextBytes(16).toHex().uppercase(),
        serial = "C02" + (1..9).map { SERIAL_ALPHABET[random.nextInt(SERIAL_ALPHABET.length)] }.joinToString(""),
        clientInfo = CLIENT_INFO,
    )

    private suspend fun urlBag(file: MachineFile): Map<String, String> {
        val response = http.execute(HttpRequest("GET", LOOKUP_URL, midServiceHeaders(file, clientTime = false)))
        if (!response.isOk()) throw AnisetteException("GSA lookup failed: HTTP ${response.statusCode}")
        val urls = (XmlPlist.parse(response.body) as? PlistValue.Dict)?.dict("urls")
            ?: throw AnisetteException("GSA lookup has no urls")
        return urls.entries.mapNotNull { (k, v) -> (v as? PlistValue.Str)?.let { k to it.value } }.toMap()
    }

    private suspend fun midService(file: MachineFile, url: String, request: Map<String, Any?>): PlistValue.Dict {
        val body = XmlPlist.encode(
            anyToPlist(linkedMapOf<String, Any?>("Header" to emptyMap<String, Any?>(), "Request" to request)),
        )
        val response = http.execute(HttpRequest("POST", url, midServiceHeaders(file, clientTime = true), body))
        if (!response.isOk()) throw AnisetteException("${url.substringAfterLast('/')} failed: HTTP ${response.statusCode}")
        return (XmlPlist.parse(response.body) as? PlistValue.Dict)?.dict("Response")
            ?: throw AnisetteException("${url.substringAfterLast('/')} gave no Response")
    }

    private fun midServiceHeaders(file: MachineFile, clientTime: Boolean): Map<String, String> =
        linkedMapOf(
            "User-Agent" to MID_SERVICE_USER_AGENT,
            "Content-Type" to "application/x-www-form-urlencoded",
            "Accept" to "*/*",
            "X-Mme-Device-Id" to file.deviceId,
            "X-MMe-Client-Info" to file.clientInfo,
            "X-Apple-I-MD-LU" to file.localUserUuid,
            "X-Apple-Client-App-Name" to "Setup",
            "X-Apple-I-SRL-NO" to file.serial,
            GSA_CONNECTION,
        ).apply { if (clientTime) put("X-Apple-I-Client-Time", isoTime()) }

    private fun isoTime(): String {
        val s = Instant.ofEpochMilli(clockMillis()).toString()
        val dot = s.indexOf('.')
        return if (dot >= 0) s.substring(0, dot) + "Z" else s
    }

    private fun Map<String, String>.require(name: String): String =
        this[name] ?: throw AnisetteException("GSA lookup has no $name")

    companion object {
        const val LOOKUP_URL = "https://gsa.apple.com/grandslam/GsService2/lookup"
        const val CLIENT_INFO = "<MacBookPro13,2> <macOS;13.1;22C65> <com.apple.AuthKit/1 (com.apple.akd/1.0)>"
        const val CFNETWORK_DARWIN = "CFNetwork/1404.0.5 Darwin/22.2.0"
        private const val MID_SERVICE_USER_AGENT = "akd/1.0 CFNetwork/1404.0.5 Darwin/22.3.0"
        private const val LOCALE = "en_US"
        private const val SERIAL_ALPHABET = "0123456789ABCDEFGHJKLMNPQRSTUVWXYZ"
        private val GSON = Gson()
    }
}

data class MachineFile(
    val version: Int = VERSION,
    val hostUuid: String = "",
    val deviceId: String = "",
    val localUserUuid: String = "",
    val serial: String = "",
    val clientInfo: String = "",
    val routing: Long? = null,
    val provisionedAtMs: Long? = null,
    val storeB64: String = "",
) {
    fun identity() = MachineIdentity(deviceId, localUserUuid, serial, clientInfo)
    fun hostUuidBytes(): ByteArray = hostUuid.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
    @OptIn(ExperimentalEncodingApi::class)
    fun storeBytes(): ByteArray = if (storeB64.isEmpty()) ByteArray(0) else Base64.decode(storeB64)

    companion object {
        const val VERSION = 1
    }
}

private fun ByteArray.toHex(): String = joinToString("") { (it.toInt() and 0xff).toString(16).padStart(2, '0') }
