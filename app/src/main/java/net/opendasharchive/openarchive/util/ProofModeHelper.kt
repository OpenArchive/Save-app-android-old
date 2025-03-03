package net.opendasharchive.openarchive.util

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Build
import android.security.keystore.UserNotAuthenticatedException
import androidx.fragment.app.FragmentActivity
import net.opendasharchive.openarchive.features.main.MainActivity
import net.opendasharchive.openarchive.util.SecureFileUtil.decryptAndRestore
import net.opendasharchive.openarchive.util.SecureFileUtil.encryptAndSave
import org.witness.proofmode.crypto.pgp.PgpUtils
import org.witness.proofmode.service.MediaWatcher
import timber.log.Timber
import java.io.File // OK

object ProofModeHelper {

    private var initialized = false

    fun init(activity: FragmentActivity, completed: () -> Unit) {
        if (initialized) return completed()

        // Disable ProofMode GPS data tracking by default.
        if (Prefs.proofModeLocation) Prefs.proofModeLocation = false

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val encryptedPassphrase = Prefs.proofModeEncryptedPassphrase

            if (encryptedPassphrase?.isNotEmpty() == true) {
                // Sometimes this gets out of sync because of the restarts.
                Prefs.useProofModeKeyEncryption = true

                val key = Hbks.loadKey()

                if (key != null) {
                    Hbks.decrypt(encryptedPassphrase, key, activity) { plaintext, e ->
                        // User failed or denied authentication. Stop app in that case.
                        if (e is UserNotAuthenticatedException) {
                            Runtime.getRuntime().exit(0)
                        } else {
                            finishInit(activity, completed, plaintext)
                        }
                    }
                } else {
                    // Oh, oh. User removed passphrase lock.
                    Prefs.proofModeEncryptedPassphrase = null
                    Prefs.useProofModeKeyEncryption = false

                    removePgpKey(activity)

                    finishInit(activity, completed)
                }
            } else {
                // Sometimes this gets out of sync because of the restarts.
                Prefs.useProofModeKeyEncryption = false

                finishInit(activity, completed)
            }
        } else {
            finishInit(activity, completed)
        }
    }

    private fun finishInit(context: Context, completed: () -> Unit, passphrase: String? = null) {
        // Store unencrypted passphrase so MediaWatcher can read it temporarily.
        Prefs.temporaryUnencryptedProofModePassphrase = passphrase

        // Load or create PGP key using the decrypted passphrase OR the default passphrase.
        PgpUtils.getInstance(context, Prefs.temporaryUnencryptedProofModePassphrase)

        // Initialize MediaWatcher with the correct passphrase.
        MediaWatcher.getInstance(context)

        // Remove again to avoid leaking unencrypted passphrase.
        Prefs.temporaryUnencryptedProofModePassphrase = null

        initialized = true

        completed()
    }

    fun removePgpKey(context: Context) {
        val pgpFiles = listOf(
            File(context.filesDir, "pkr.asc.enc"),
            File(context.filesDir, "pub.asc.enc")
        )

        for (file in pgpFiles) {
            try {
                if (file.exists()) {
                    file.delete()
                    Timber.d("Deleted encrypted PGP key file: ${file.absolutePath}")
                }
            } catch (e: Exception) {
                Timber.e(e, "Failed to delete PGP key file.")
            }
        }
    }

    fun restartApp(activity: Activity) {
        val i = Intent(activity, MainActivity::class.java)
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        activity.startActivity(i)

        activity.finish()

        Prefs.store()

        Runtime.getRuntime().exit(0)
    }

    /**
     * Saves and encrypts PGP keys.
     */
    fun savePgpKey(context: Context, fileName: String, data: ByteArray) {
        val file = File(context.filesDir, fileName)
        val encryptedFile = File(context.filesDir, "$fileName.enc")

        try {
            file.writeBytes(data) // Write raw data first
            file.encryptAndSave(encryptedFile) // Encrypt and save securely
            file.delete() // Remove the unencrypted version

            Timber.d("PGP key saved securely: ${encryptedFile.absolutePath}")
        } catch (e: Exception) {
            Timber.e(e, "Failed to save PGP key securely.")
        }
    }

    /**
     * Loads and decrypts a PGP key.
     */
    fun loadPgpKey(context: Context, fileName: String): ByteArray? {
        val encryptedFile = File(context.filesDir, "$fileName.enc")
        val decryptedFile = File(context.filesDir, fileName)

        return if (encryptedFile.exists()) {
            try {
                encryptedFile.decryptAndRestore(decryptedFile) // Decrypt before reading
                val data = decryptedFile.readBytes()
                decryptedFile.delete() // Remove after reading

                Timber.d("PGP key decrypted successfully.")
                data
            } catch (e: Exception) {
                Timber.e(e, "Failed to decrypt PGP key.")
                null
            }
        } else {
            Timber.w("Encrypted PGP key file not found.")
            null
        }
    }
}
