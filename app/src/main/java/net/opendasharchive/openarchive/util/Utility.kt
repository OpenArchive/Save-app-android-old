package net.opendasharchive.openarchive.util

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.provider.OpenableColumns
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import net.opendasharchive.openarchive.util.SecureFileUtil.decryptAndRestore
import net.opendasharchive.openarchive.util.SecureFileUtil.encryptAndSave
import timber.log.Timber
import java.io.*
import java.text.SimpleDateFormat
import java.util.*

object Utility {

    fun getMimeType(context: Context, uri: Uri?): String? {
        return uri?.let { context.contentResolver.getType(it) }
    }

    fun getUriDisplayName(context: Context, uri: Uri): String? {
        val cursor = context.contentResolver.query(uri, null, null, null, null) ?: return null

        var result: String? = null
        val idx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
        if (idx >= 0 && cursor.moveToFirst()) {
            result = cursor.getString(idx)
        }
        cursor.close()

        return result
    }

    /**
     * Creates a secure encrypted cache file.
     */
    fun getOutputMediaFileByCache(context: Context, fileName: String): File? {
        val dir = context.cacheDir
        if (!dir.exists() && !dir.mkdirs()) {
            Timber.e("Failed to create cache directory")
            return null
        }

        val timeStamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val file = File(dir, "$timeStamp.$fileName")

        try {
            if (!file.exists()) {
                if (file.createNewFile()) {
                    Timber.d("File created: ${file.absolutePath}")
                } else {
                    Timber.e("Failed to create file: ${file.absolutePath}")
                    return null
                }
            }
        } catch (e: IOException) {
            Timber.e(e, "IOException while creating file: ${file.absolutePath}")
            return null
        }

        return file
    }


    /**
     * Writes an input stream to an encrypted file.
     */
    fun writeStreamToFile(input1: InputStream?, file1: File?): Boolean {
        val input = input1 ?: return false
        val file = file1 ?: return false

        var success = false
        var output: FileOutputStream? = null

        try {
            val tempFile = File(file.parent, file.name + ".tmp")
            output = FileOutputStream(tempFile)

            val buffer = ByteArray(4 * 1024)
            var read: Int
            while (input.read(buffer).also { read = it } != -1) {
                output.write(buffer, 0, read)
            }
            output.flush()
            success = true

            // Encrypt the file after writing
            tempFile.encryptAndSave(file)
            tempFile.delete()

        } catch (e: IOException) {
            Timber.e(e)
        } finally {
            try {
                output?.close()
                input.close()
            } catch (e: IOException) {
                Timber.e(e)
            }
        }

        return success
    }

    /**
     * Reads and decrypts a file from cache.
     */
    fun getDecryptedCacheFile(context: Context, encryptedFile: File): File? {
        val decryptedFile = File(context.cacheDir, encryptedFile.name.removeSuffix(".enc"))

        return encryptedFile.decryptAndRestore(decryptedFile)?.also {
            Timber.d("Decrypted file available at: ${it.absolutePath}")
        } ?: run {
            Timber.e("Failed to decrypt file: ${encryptedFile.absolutePath}")
            null
        }
    }

    /**
     * Opens an app store link securely.
     */
    fun openStore(context: Context, appId: String) {
        var i = Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=$appId"))

        val capableApps = context.packageManager.queryIntentActivities(i, 0)
        if (capableApps.isEmpty()) {
            i = Intent(Intent.ACTION_VIEW, Uri.parse("https://play.google.com/store/apps/details?id=$appId"))
        }

        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        context.startActivity(i)
    }

    /**
     * Shows a Material UI Warning dialog.
     */
    fun showMaterialWarning(
        context: Context,
        message: String? = null,
        positiveButtonText: String = "Ok",
        completion: (() -> Unit)? = null
    ) {
        showMaterialMessage(context, "Oops", message, positiveButtonText, completion)
    }

    /**
     * Shows a Material UI message dialog.
     */
    fun showMaterialMessage(
        context: Context,
        title: String = "Oops",
        message: String? = null,
        positiveButtonText: String = "Ok",
        completion: (() -> Unit)? = null
    ) {
        Handler(Looper.getMainLooper()).post {
            MaterialAlertDialogBuilder(context)
                .setTitle(title)
                .setMessage(message)
                .setPositiveButton(positiveButtonText) { _, _ -> completion?.invoke() }
                .show()
        }
    }

    /**
     * Shows a confirmation dialog with positive & negative actions.
     */
    fun showMaterialPrompt(
        context: Context,
        title: String,
        message: String? = null,
        positiveButtonText: String,
        negativeButtonText: String,
        completion: (Boolean) -> Unit
    ) {
        Handler(Looper.getMainLooper()).post {
            MaterialAlertDialogBuilder(context)
                .setTitle(title)
                .setMessage(message)
                .setPositiveButton(positiveButtonText) { dialog, _ ->
                    dialog.dismiss()
                    completion(true)
                }
                .setNegativeButton(negativeButtonText) { dialog, _ ->
                    dialog.dismiss()
                    completion(false)
                }
                .show()
        }
    }
}
