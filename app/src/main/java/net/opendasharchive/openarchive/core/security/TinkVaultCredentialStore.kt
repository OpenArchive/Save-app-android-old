package net.opendasharchive.openarchive.core.security

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStoreFile
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

// Replaces TinkVaultCredentialStore — same AES-256-GCM + Android Keystore, no Tink dependency.
// Migration: if decryption fails (pre-existing Tink-encrypted data), the credential is cleared
// and the user will be prompted to re-enter their server password on next connection.
//
// DataStore must be a process-wide singleton for a given file (Android requirement). The
// companion object holds the single instance so that the migration-time store (created before
// Koin) and the Koin-injected store share the same underlying DataStore and never conflict.
class TinkVaultCredentialStore(
    context: Context,
    private val io: CoroutineDispatcher = Dispatchers.IO
) : VaultCredentialStore {

    private val appContext = context.applicationContext

    private val dataStore: DataStore<Preferences>
        get() = getOrCreateDataStore(appContext)

    private fun getOrCreateKey(): SecretKey {
        val ks = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        (ks.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        val kg = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        kg.init(
            KeyGenParameterSpec.Builder(KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .setRandomizedEncryptionRequired(true)
                // setUserAuthenticationRequired intentionally omitted: the upload foreground
                // service must be able to silently re-authenticate and decrypt credentials
                // while the device is locked (e.g. overnight background uploads). Requiring
                // user-presence would break that flow. Storage is protected by Android
                // Keystore hardware isolation + AES-256-GCM + app-private DataStore.
                .build()
        )
        return kg.generateKey()
    }

    override suspend fun putSecret(vaultId: Long, secret: String) =
        encryptToSlot(secretKey(vaultId), secret)

    override suspend fun getSecret(vaultId: Long): String? =
        decryptFromSlot(secretKey(vaultId))

    override suspend fun hasSecret(vaultId: Long): Boolean = withContext(io) {
        dataStore.data.first().contains(secretKey(vaultId))
    }

    override suspend fun deleteSecret(vaultId: Long) = withContext(io) {
        dataStore.edit { prefs -> prefs.remove(secretKey(vaultId)) }
        Unit
    }

    override suspend fun putLoginPassword(vaultId: Long, password: String) =
        encryptToSlot(loginPasswordKey(vaultId), password)

    override suspend fun getLoginPassword(vaultId: Long): String? =
        decryptFromSlot(loginPasswordKey(vaultId))

    override suspend fun deleteLoginPassword(vaultId: Long) = withContext(io) {
        dataStore.edit { prefs -> prefs.remove(loginPasswordKey(vaultId)) }
        Unit
    }

    private suspend fun encryptToSlot(key: Preferences.Key<String>, plaintext: String) = withContext(io) {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey())
        val payload = cipher.iv + cipher.doFinal(plaintext.toByteArray(Charsets.UTF_8))
        dataStore.edit { prefs -> prefs[key] = Base64.encodeToString(payload, Base64.NO_WRAP) }
        Unit
    }

    private suspend fun decryptFromSlot(key: Preferences.Key<String>): String? = withContext(io) {
        val encoded = dataStore.data.first()[key] ?: return@withContext null
        runCatching {
            val payload = Base64.decode(encoded, Base64.NO_WRAP)
            val iv = payload.sliceArray(0 until GCM_IV_LENGTH)
            val ciphertext = payload.sliceArray(GCM_IV_LENGTH until payload.size)
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, getOrCreateKey(), GCMParameterSpec(GCM_TAG_BITS, iv))
            String(cipher.doFinal(ciphertext), Charsets.UTF_8)
        }.getOrElse {
            dataStore.edit { prefs -> prefs.remove(key) }
            null
        }
    }

    private fun secretKey(vaultId: Long) = stringPreferencesKey("vault_secret_$vaultId")
    private fun loginPasswordKey(vaultId: Long) = stringPreferencesKey("vault_login_$vaultId")

    companion object {
        const val DATASTORE_FILE_NAME = "vault_secure_credentials"
        const val KEY_ALIAS = "openarchive_vault_master_key"
        private const val ANDROID_KEYSTORE = "AndroidKeyStore"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val GCM_IV_LENGTH = 12
        private const val GCM_TAG_BITS = 128

        @Volatile private var sharedDataStore: DataStore<Preferences>? = null

        private fun getOrCreateDataStore(context: Context): DataStore<Preferences> =
            sharedDataStore ?: synchronized(this) {
                sharedDataStore ?: PreferenceDataStoreFactory.create(
                    scope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
                    produceFile = { context.applicationContext.preferencesDataStoreFile(DATASTORE_FILE_NAME) }
                ).also { sharedDataStore = it }
            }
    }
}
