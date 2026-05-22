package net.opendasharchive.openarchive.util

import android.content.Context
import net.opendasharchive.openarchive.core.logger.AppLogger
import net.opendasharchive.openarchive.core.security.C2paKeyStore
import org.json.JSONObject
import java.io.File

/**
 * C2PA sidecar generator.
 *
 * Produces a binary JUMBF .c2pa file beside the original media asset.
 * The original file is NEVER modified — only a sidecar is written.
 *
 * Sidecar can be validated with:
 *   c2patool original.jpg --external-manifest original.c2pa
 */
object C2paHelper {

    private var initialized = false

    fun init(context: Context) {
        if (initialized) return
        initialized = true
        if (C2paFfi.initialize()) {
            ensureCredentials(context)
            AppLogger.i("C2PA Helper initialized (FFI ready)")
        } else {
            AppLogger.d("C2PA Helper initialized (FFI unavailable — sidecar disabled)")
        }
    }

    /**
     * Generates a binary .c2pa sidecar for [mediaFile].
     * Returns the sidecar File on success, null if disabled or failed.
     *
     * @param context   Application context
     * @param mediaFile Original media file (not modified)
     * @param mediaHash MD5/SHA hash used as the sidecar filename key
     * @param metadata  Optional metadata: title, description, author, location
     */
    fun generateSidecar(
        context: Context,
        mediaFile: File,
        mediaHash: String,
        metadata: Map<String, String> = emptyMap()
    ): File? {
        if (!Prefs.useC2pa) return null
        if (!C2paFfi.isAvailable()) return null
        if (!mediaFile.exists()) {
            AppLogger.e("[C2PA] Asset not found: ${mediaFile.absolutePath}")
            return null
        }

        val keyStore = C2paKeyStore(context)
        val certPem = keyStore.getCertPem()
        val keyDerB64 = keyStore.getKeyDerB64()
        if (certPem == null || keyDerB64 == null) {
            AppLogger.e("[C2PA] Signing credentials missing — call init() first")
            return null
        }

        val sidecarFile = getSidecarFile(context, mediaHash)
        sidecarFile.parentFile?.mkdirs()

        val metadataJson = metadata.toJsonString()
        AppLogger.d("[C2PA] Signing ${mediaFile.absolutePath} → ${sidecarFile.absolutePath}")

        val ok = C2paFfi.generateSidecar(
            filePath = mediaFile.absolutePath,
            sidecarPath = sidecarFile.absolutePath,
            certPem = certPem,
            keyDerB64 = keyDerB64,
            metadataJson = metadataJson
        )

        return if (ok && sidecarFile.exists()) {
            AppLogger.i("[C2PA] Sidecar written: ${sidecarFile.absolutePath} (${sidecarFile.length()} bytes)")
            sidecarFile
        } else {
            AppLogger.e("[C2PA] Sidecar generation failed for ${mediaFile.name}")
            null
        }
    }

    /** Returns the .c2pa sidecar path for a given media hash. */
    fun getSidecarFile(context: Context, mediaHash: String): File {
        val dir = File(context.filesDir, "c2pa_manifests")
        return File(dir, "$mediaHash.c2pa")
    }

    fun removeSidecar(context: Context, mediaHash: String) {
        val f = getSidecarFile(context, mediaHash)
        if (f.exists()) {
            f.delete()
            AppLogger.d("[C2PA] Deleted sidecar: ${f.absolutePath}")
        }
    }

    // ── private ───────────────────────────────────────────────────────────────

    /**
     * Generates signing credentials on first use and persists them in C2paKeyStore.
     * This is a one-time operation per app install.
     */
    private fun ensureCredentials(context: Context) {
        val keyStore = C2paKeyStore(context)
        if (keyStore.hasCredentials()) return

        AppLogger.i("[C2PA] Generating signing credentials (first run)")
        val json = C2paFfi.generateKeyAndCertificate()
        if (json == null) {
            AppLogger.e("[C2PA] Failed to generate signing credentials")
            return
        }

        try {
            val obj = JSONObject(json)
            keyStore.putCertPem(obj.getString("cert_pem"))
            keyStore.putKeyDerB64(obj.getString("key_der_b64"))
            AppLogger.i("[C2PA] Signing credentials stored")
        } catch (e: Exception) {
            AppLogger.e("[C2PA] Failed to parse/store credentials", e)
        }
    }

    private fun Map<String, String>.toJsonString(): String {
        val obj = JSONObject()
        forEach { (k, v) -> obj.put(k, v) }
        return obj.toString()
    }
}
