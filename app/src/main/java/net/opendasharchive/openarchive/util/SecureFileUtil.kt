package net.opendasharchive.openarchive.util

import android.net.Uri
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import androidx.core.net.toFile
import net.opendasharchive.openarchive.util.SecureFileUtil.decryptAndRestore
import net.opendasharchive.openarchive.util.SecureFileUtil.encryptAndSave
import timber.log.Timber
import java.io.File
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.IvParameterSpec

object SecureFileUtil {
    private const val KEY_ALIAS = "SecureFileKey"
    private const val TRANSFORMATION = "AES/CBC/PKCS5Padding"
    private const val KEYSTORE_PROVIDER = "AndroidKeyStore"
    private const val AES_ALGORITHM = "AES"

    private fun getOrCreateSecretKey(): SecretKey {
        val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }

        return if (keyStore.containsAlias(KEY_ALIAS)) {
            val keyEntry = keyStore.getEntry(KEY_ALIAS, null) as? KeyStore.SecretKeyEntry
            keyEntry?.secretKey ?: generateSecretKey() // Generate if null
        } else {
            generateSecretKey()
        }
    }

    private fun generateSecretKey(): SecretKey {
        val keyGenerator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        val keySpec = KeyGenParameterSpec.Builder(
            KEY_ALIAS,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
        )
            .setBlockModes(KeyProperties.BLOCK_MODE_CBC)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_PKCS7)
            .setKeySize(256)
            .build()

        keyGenerator.init(keySpec)
        return keyGenerator.generateKey()
    }

    private fun encryptData(data: ByteArray): Pair<ByteArray, ByteArray> {
        val secretKey = getOrCreateSecretKey()
        requireNotNull(secretKey) { "Encryption key could not be generated!" } // Throw clear error

        val cipher = Cipher.getInstance("AES/CBC/PKCS7Padding")
        cipher.init(Cipher.ENCRYPT_MODE, secretKey)

        return Pair(cipher.iv, cipher.doFinal(data))
    }


    private fun decryptData(iv: ByteArray, encryptedData: ByteArray): ByteArray {
        val secretKey = getOrCreateSecretKey()

        return try {
            val cipher = Cipher.getInstance("AES/CBC/PKCS7Padding")
            cipher.init(Cipher.DECRYPT_MODE, secretKey, IvParameterSpec(iv))

            cipher.doFinal(encryptedData)
        } catch (e: Exception) {
            Timber.e(e, "Decryption failed! Invalid key or corrupted data.")
            ByteArray(0) // Return empty byte array if decryption fails
        }
    }

    /** Encrypt and Write Any File (Non-Nullable) **/
    fun File.encryptAndSave(encryptedFile: File): File? {
        if (!this.exists()) {
            Timber.e("File not found for encryption: ${this.absolutePath}")
            return null
        }

        // ✅ Prevent double encryption
        if (this.name.endsWith(".enc")) {
            Timber.d("File is already encrypted: ${this.absolutePath}")
            return this // Return existing encrypted file
        }

        return try {
            val inputBytes = this.readBytes()
            val (iv, encryptedBytes) = encryptData(inputBytes) // Your encryption logic

            encryptedFile.writeBytes(encryptedBytes)
            Timber.d("File encrypted successfully: ${encryptedFile.absolutePath}")
            encryptedFile
        } catch (e: Exception) {
            Timber.e(e, "Encryption failed for file: ${this.absolutePath}")
            null
        }
    }

    /** Decrypt and Restore Any File (Non-Nullable) **/
    fun File.decryptAndRestore(decryptedFile: File): File {
        if (!this.exists()) {
            Timber.e("File not found for decryption: ${this.absolutePath}")
            return decryptedFile // Return original file if decryption fails
        }

        return try {
            val encryptedBytes = this.readBytes()

            // ✅ Extract IV and encrypted data correctly
            if (encryptedBytes.size < 17) {
                Timber.e("Encrypted file is too small to contain valid IV and data.")
                return decryptedFile
            }

            val iv = encryptedBytes.copyOfRange(0, 16) // First 16 bytes for IV
            val cipherText = encryptedBytes.copyOfRange(16, encryptedBytes.size)

            val decryptedBytes = decryptData(iv, cipherText) // Pass both IV and encrypted data

            // ✅ Validate decrypted data before writing
            if (decryptedBytes.isEmpty()) {
                Timber.e("Decryption failed or produced empty data: ${this.absolutePath}")
                return decryptedFile
            }

            decryptedFile.writeBytes(decryptedBytes)
            Timber.d("File decrypted successfully: ${decryptedFile.absolutePath}")
            decryptedFile
        } catch (e: Exception) {
            Timber.e(e, "Decryption failed for file: ${this.absolutePath}")
            decryptedFile // Return original file if decryption fails
        }
    }
}

/** Encrypt File-Based URIs (Non-Nullable) **/
fun Uri.encryptFileUri(): File {
    if (scheme != "file") {
        throw IllegalArgumentException("Only file:// URIs are supported for encryption")
    }

    val originalFile = toFile()
    val encryptedFile = File(originalFile.parent, originalFile.name + ".enc")

    try {
        originalFile.encryptAndSave(encryptedFile)
    } catch (e: Exception) {
        throw RuntimeException("Encryption failed for ${originalFile.absolutePath}", e)
    }

    return encryptedFile
}

/** Decrypt File-Based URIs (Non-Nullable) **/
fun Uri.decryptFileUri(): File {
    if (scheme != "file") {
        throw IllegalArgumentException("Only file:// URIs are supported for decryption")
    }

    val encryptedFile = toFile()
    val decryptedFile = File(encryptedFile.parent, encryptedFile.name.removeSuffix(".enc"))

    try {
        encryptedFile.decryptAndRestore(decryptedFile)
    } catch (e: Exception) {
        throw RuntimeException("Decryption failed for ${encryptedFile.absolutePath}", e)
    }

    return decryptedFile
}
