package io.github.tieo.taghistory.anisette

import java.io.File

/**
 * Thrown when anisette or CoreADI emulation fails.
 */
class AnisetteException @JvmOverloads constructor(
    message: String,
    cause: Throwable? = null,
) : RuntimeException(message, cause)

/**
 * JNI bindings for ottjni (Unicorn x86_64 emulation of Apple's macOS CoreADI).
 * Package, class, and method names must match the Rust exported symbols in libottjni.so:
 * Java_io_github_tieo_taghistory_anisette_CoreAdiJni_*
 */
internal class CoreAdiJni private constructor() {
    companion object {
        const val LIBRARY_PATH_PROPERTY = "taghistory.ottjni.path"

        init {
            val path = System.getProperty(LIBRARY_PATH_PROPERTY)
            if (path != null) System.load(path) else System.loadLibrary("ottjni")
        }

        @JvmStatic external fun nativeVersion(): String
        @JvmStatic external fun nativeOpen(image: ByteArray, hostUuid: ByteArray, store: ByteArray): Long
        @JvmStatic external fun nativeClose(handle: Long)
        @JvmStatic external fun nativeStore(handle: Long): ByteArray
        @JvmStatic external fun nativeIsProvisioned(handle: Long): Boolean
        /** `[mid, otp, routing as 8 big-endian bytes]` */
        @JvmStatic external fun nativeHeaders(handle: Long): Array<ByteArray>
        /** `[cpim, session as 4 big-endian bytes]` */
        @JvmStatic external fun nativeProvisioningBegin(handle: Long, spim: ByteArray): Array<ByteArray>
        @JvmStatic external fun nativeProvisioningFinish(handle: Long, session: Int, ptm: ByteArray, tk: ByteArray, routing: Long)
        /** `[mid, srm]` */
        @JvmStatic external fun nativeSync(handle: Long, sim: ByteArray): Array<ByteArray>
        @JvmStatic external fun nativeUpdateRouting(handle: Long, routing: Long)
    }
}

/** CoreADI for one machine. Not thread safe; callers should serialize calls. */
interface CoreAdi {
    fun isProvisioned(): Boolean
    fun headers(): AdiHeaders
    fun provisioningBegin(spim: ByteArray): AdiProvisioning
    fun provisioningFinish(session: Int, ptm: ByteArray, tk: ByteArray, routing: Long)
    fun sync(sim: ByteArray): AdiSync
    fun store(): ByteArray
}

class AdiHeaders(val mid: ByteArray, val otp: ByteArray, val routing: Long)
class AdiProvisioning(val cpim: ByteArray, val session: Int)
class AdiSync(val mid: ByteArray, val srm: ByteArray)

/** Where the machine file lives. It must survive app restarts: it is the Mac's identity. */
interface MachineStorage {
    fun read(): ByteArray?
    fun write(bytes: ByteArray)
}

/** Atomic file-backed MachineStorage. */
class FileMachineStorage(private val file: File) : MachineStorage {
    override fun read(): ByteArray? = file.takeIf { it.isFile }?.readBytes()

    override fun write(bytes: ByteArray) {
        file.parentFile?.mkdirs()
        val tmp = File(file.parentFile, file.name + ".tmp")
        tmp.writeBytes(bytes)
        if (!tmp.renameTo(file)) {
            file.delete()
            if (!tmp.renameTo(file)) {
                tmp.copyTo(file, overwrite = true)
                tmp.delete()
            }
        }
    }
}

/** [CoreAdi] backed by ottjni. */
class JniCoreAdi(image: ByteArray, hostUuid: ByteArray, store: ByteArray) : CoreAdi {
    private val handle = CoreAdiJni.nativeOpen(image, hostUuid, store)

    override fun isProvisioned(): Boolean = CoreAdiJni.nativeIsProvisioned(handle)

    override fun headers(): AdiHeaders {
        val (mid, otp, routingBytes) = CoreAdiJni.nativeHeaders(handle)
        val routing = bytesToLong(routingBytes)
        return AdiHeaders(mid, otp, routing)
    }

    override fun provisioningBegin(spim: ByteArray): AdiProvisioning {
        val (cpim, sessionBytes) = CoreAdiJni.nativeProvisioningBegin(handle, spim)
        val session = bytesToLong(sessionBytes).toInt()
        return AdiProvisioning(cpim, session)
    }

    override fun provisioningFinish(session: Int, ptm: ByteArray, tk: ByteArray, routing: Long) {
        CoreAdiJni.nativeProvisioningFinish(handle, session, ptm, tk, routing)
    }

    override fun sync(sim: ByteArray): AdiSync {
        val (mid, srm) = CoreAdiJni.nativeSync(handle, sim)
        return AdiSync(mid, srm)
    }

    override fun store(): ByteArray = CoreAdiJni.nativeStore(handle)

    companion object {
        fun version(): String = CoreAdiJni.nativeVersion()

        private fun bytesToLong(b: ByteArray): Long {
            var res = 0L
            for (byte in b) {
                res = (res shl 8) or (byte.toLong() and 0xFF)
            }
            return res
        }
    }
}
