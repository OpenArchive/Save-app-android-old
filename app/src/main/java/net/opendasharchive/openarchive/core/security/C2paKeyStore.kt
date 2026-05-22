package net.opendasharchive.openarchive.core.security

import android.content.Context
import android.content.SharedPreferences

/**
 * Encrypted storage for C2PA signing material (AES-GCM via Android Keystore).
 *
 * Stores:
 *   - certPem    — PEM-encoded self-signed X.509 certificate
 *   - keyDerB64  — Base64-encoded PKCS#8 DER private key
 *
 * Both values are generated once by C2paFfi.generateKeyAndCertificate() and
 * reused for all subsequent signing operations.
 */
class C2paKeyStore(context: Context) {

    private val secureStorage = SecureStorage(context, alias = "C2paKeyStore")

    companion object {
        private const val KEY_CERT_PEM = "c2pa_cert_pem_v2"
        private const val KEY_DER_B64 = "c2pa_key_der_b64_v2"

        // Legacy keys from the pre-rcgen era — migrated and removed on first access
        private const val LEGACY_SIGNING_KEY = "c2pa_signing_key"
        private const val LEGACY_PASSPHRASE = "c2pa_encrypted_passphrase"
    }

    fun getCertPem(): String? = secureStorage.getString(KEY_CERT_PEM)
    fun putCertPem(value: String?) = secureStorage.putString(KEY_CERT_PEM, value)

    fun getKeyDerB64(): String? = secureStorage.getString(KEY_DER_B64)
    fun putKeyDerB64(value: String?) = secureStorage.putString(KEY_DER_B64, value)

    fun hasCredentials(): Boolean = getCertPem() != null && getKeyDerB64() != null

    fun clear() {
        secureStorage.remove(KEY_CERT_PEM)
        secureStorage.remove(KEY_DER_B64)
    }

    /** One-time migration: drops legacy plaintext keys from SharedPreferences. */
    fun migrateFromPrefsIfNeeded(prefs: SharedPreferences) {
        if (prefs.contains(LEGACY_SIGNING_KEY) || prefs.contains(LEGACY_PASSPHRASE)) {
            prefs.edit()
                .remove(LEGACY_SIGNING_KEY)
                .remove(LEGACY_PASSPHRASE)
                .apply()
        }
    }
}
