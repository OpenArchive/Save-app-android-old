package net.opendasharchive.openarchive.util

import net.opendasharchive.openarchive.core.logger.AppLogger

object C2paFfi {

    private var initialized = false
    private var libraryLoaded = false

    init {
        try {
            System.loadLibrary("c2pa_ffi")
            libraryLoaded = true
            AppLogger.d("C2PA FFI native library loaded")
        } catch (e: UnsatisfiedLinkError) {
            AppLogger.e("Failed to load C2PA FFI native library: ${e.message}")
            libraryLoaded = false
        }
    }

    fun initialize(): Boolean {
        if (!libraryLoaded) return false
        if (initialized) return true
        return try {
            val result = nativeInit()
            initialized = result
            result
        } catch (e: Exception) {
            AppLogger.e("C2PA FFI init failed", e)
            false
        }
    }

    fun isAvailable(): Boolean = libraryLoaded && initialized

    /**
     * Generates a fresh ECDSA P-256 key pair and a self-signed X.509 certificate.
     * Call once at setup; store the result in C2paKeyStore.
     *
     * @return JSON string {"cert_pem":"...","key_der_b64":"..."}, or null on failure.
     */
    fun generateKeyAndCertificate(): String? {
        ensureInit() ?: return null
        return try {
            nativeGenerateKeyAndCertificate().also { result ->
                if (result == null) AppLogger.w("C2PA key/cert generation returned null")
            }
        } catch (e: Exception) {
            AppLogger.e("C2PA key/cert generation failed", e)
            null
        }
    }

    /**
     * Signs [filePath] and writes a binary JUMBF .c2pa sidecar to [sidecarPath].
     * The original file is NOT modified.
     *
     * @param filePath     Absolute path to the media asset.
     * @param sidecarPath  Absolute path for the output .c2pa file.
     * @param certPem      PEM certificate from C2paKeyStore.
     * @param keyDerB64    Base64-encoded PKCS#8 DER private key from C2paKeyStore.
     * @param metadataJson JSON object with optional: title, description, author, location.
     * @return true on success.
     */
    fun generateSidecar(
        filePath: String,
        sidecarPath: String,
        certPem: String,
        keyDerB64: String,
        metadataJson: String
    ): Boolean {
        ensureInit() ?: return false
        return try {
            nativeGenerateSidecar(filePath, sidecarPath, certPem, keyDerB64, metadataJson)
        } catch (e: Exception) {
            AppLogger.e("C2PA sidecar generation failed", e)
            false
        }
    }

    // ── private ───────────────────────────────────────────────────────────────

    private fun ensureInit(): Unit? {
        if (!initialize()) {
            AppLogger.e("C2PA FFI not available")
            return null
        }
        return Unit
    }

    private external fun nativeInit(): Boolean
    private external fun nativeGenerateKeyAndCertificate(): String?
    private external fun nativeGenerateSidecar(
        filePath: String,
        sidecarPath: String,
        certPem: String,
        keyDerB64: String,
        metadataJson: String
    ): Boolean
}
