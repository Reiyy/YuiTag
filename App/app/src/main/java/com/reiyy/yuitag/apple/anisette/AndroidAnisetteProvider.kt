package com.reiyy.yuitag.apple.anisette

import android.content.Context
import com.reiyy.yuitag.apple.http.HttpTransport
import io.github.tieo.taghistory.anisette.AnisetteException
import io.github.tieo.taghistory.anisette.FileMachineStorage
import io.github.tieo.taghistory.anisette.JniCoreAdi
import kotlinx.coroutines.Dispatchers
import java.io.File

/**
 * Creates an [AnisetteProvider] configured for Android using the emulated macOS CoreADI binary.
 */
fun createAndroidAnisetteProvider(context: Context, http: HttpTransport): AnisetteProvider {
    val app = context.applicationContext
    return MacAnisetteProvider(
        openAdi = { hostUuid, store ->
            val local = CoreAdiDownloader.getLocalFile(app)
            val bytes = if (local.exists() && local.length() > 0) {
                local.readBytes()
            } else {
                try {
                    app.assets.open(CoreAdiDownloader.ASSET_PATH).use { it.readBytes() }
                } catch (e: Exception) {
                    throw AnisetteException("macOS CoreADI binary not found in storage or assets: ${e.message}", e)
                }
            }
            JniCoreAdi(bytes, hostUuid, store)
        },
        storage = FileMachineStorage(File(app.filesDir, "anisette/machine.json")),
        http = http,
        nativeVersion = JniCoreAdi::version,
        blockingContext = Dispatchers.IO,
    )
}
