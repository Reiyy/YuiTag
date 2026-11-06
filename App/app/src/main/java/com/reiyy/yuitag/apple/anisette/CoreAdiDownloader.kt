package com.reiyy.yuitag.apple.anisette

import android.content.Context
import com.reiyy.yuitag.ble.FileLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.InputStream
import java.security.MessageDigest

/**
 * Manages Apple's authentic macOS CoreADI x86_64 binary.
 *
 * Supports:
 * 1. Bundled asset: `assets/coreadi/CoreADI.x86_64`
 * 2. Cached file in app private files: `files/coreadi/CoreADI.x86_64`
 * 3. User manual import from external file with SHA-256 verification
 */
object CoreAdiDownloader {
    private const val TAG = "CoreAdiDownloader"
    const val ASSET_PATH = "coreadi/CoreADI.x86_64"
    const val LOCAL_FILENAME = "coreadi/CoreADI.x86_64"

    /** Expected SHA-256 hash of iTunes 12.8.3 macOS CoreADI.x86_64 */
    const val EXPECTED_SHA256 = "2eafc3c79c7f41a8afbfbeaaf23ee5dab5ad5bda55ec8e54dbc0ddf1d20ce630"

    fun getLocalFile(context: Context): File =
        File(context.filesDir, LOCAL_FILENAME)

    fun isReady(context: Context): Boolean {
        val file = getLocalFile(context)
        if (file.exists() && file.length() > 0) return true
        return try {
            context.assets.open(ASSET_PATH).use { true }
        } catch (_: Exception) {
            false
        }
    }

    suspend fun getCoreAdiBytes(context: Context): ByteArray? = withContext(Dispatchers.IO) {
        val local = getLocalFile(context)
        if (local.exists() && local.length() > 0) {
            try {
                val bytes = local.readBytes()
                if (verifyHash(bytes)) return@withContext bytes
                FileLog.w(TAG, "Local CoreADI hash mismatch, deleting corrupted file")
                local.delete()
            } catch (e: Exception) {
                FileLog.w(TAG, "Failed to read local CoreADI: ${e.message}")
            }
        }

        try {
            val bytes = context.assets.open(ASSET_PATH).use { it.readBytes() }
            if (verifyHash(bytes)) {
                // Cache into local file for uniform access
                try {
                    local.parentFile?.mkdirs()
                    local.writeBytes(bytes)
                } catch (_: Exception) {}
                return@withContext bytes
            } else {
                FileLog.w(TAG, "Asset CoreADI hash mismatch!")
            }
        } catch (_: Exception) {
            // Asset not bundled in this build
        }

        null
    }

    suspend fun importFromStream(context: Context, inputStream: InputStream): Boolean = withContext(Dispatchers.IO) {
        try {
            val bytes = inputStream.use { it.readBytes() }
            if (verifyHash(bytes)) {
                val local = getLocalFile(context)
                local.parentFile?.mkdirs()
                local.writeBytes(bytes)
                FileLog.i(TAG, "Successfully imported CoreADI.x86_64 (${bytes.size} bytes)")
                true
            } else {
                val hash = sha256Hex(bytes)
                FileLog.w(TAG, "Import rejected: hash mismatch. Got $hash, expected $EXPECTED_SHA256")
                false
            }
        } catch (e: Exception) {
            FileLog.w(TAG, "Import failed: ${e.message}")
            false
        }
    }

    fun verifyHash(bytes: ByteArray): Boolean =
        sha256Hex(bytes).equals(EXPECTED_SHA256, ignoreCase = true)

    private fun sha256Hex(bytes: ByteArray): String {
        val md = MessageDigest.getInstance("SHA-256")
        val digest = md.digest(bytes)
        return digest.joinToString("") { "%02x".format(it) }
    }
}
