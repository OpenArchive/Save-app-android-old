package net.opendasharchive.openarchive.util

import android.content.Context
import android.util.Base64
import com.google.android.play.core.integrity.IntegrityManagerFactory
import com.google.android.play.core.integrity.IntegrityTokenRequest
import com.google.android.gms.tasks.Tasks
import net.opendasharchive.openarchive.core.logger.AppLogger
import java.io.File

object SafetyNetHelper {
    /**
     * Requests a Play Integrity token and writes it as a JWT string to [outFile].
     * No-op on network or API failure — outFile simply won't exist, and the caller
     * skips it in the upload list.
     */
    fun requestGst(context: Context, mediaHash: String, outFile: File) {
        try {
            val nonce = Base64.encodeToString(mediaHash.toByteArray(Charsets.UTF_8),
                Base64.URL_SAFE or Base64.NO_WRAP)
            val manager = IntegrityManagerFactory.create(context.applicationContext)
            val request = IntegrityTokenRequest.builder().setNonce(nonce).build()
            val response = Tasks.await(manager.requestIntegrityToken(request))
            outFile.writeText(response.token())
            AppLogger.i("[GST] Play Integrity token written for $mediaHash")
        } catch (e: Exception) {
            AppLogger.w("[GST] Play Integrity request failed: ${e.message}")
        }
    }
}
