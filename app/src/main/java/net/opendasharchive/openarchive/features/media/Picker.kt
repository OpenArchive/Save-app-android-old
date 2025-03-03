package net.opendasharchive.openarchive.features.media

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.view.View
import android.widget.ProgressBar
import androidx.activity.ComponentActivity
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.lifecycle.lifecycleScope
import com.esafirm.imagepicker.features.ImagePickerConfig
import com.esafirm.imagepicker.features.ImagePickerLauncher
import com.esafirm.imagepicker.features.ImagePickerMode
import com.esafirm.imagepicker.features.ImagePickerSavePath
import com.esafirm.imagepicker.features.ReturnMode
import com.esafirm.imagepicker.features.registerImagePicker
import com.google.android.material.snackbar.Snackbar
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import net.opendasharchive.openarchive.R
import net.opendasharchive.openarchive.core.logger.AppLogger
import net.opendasharchive.openarchive.db.Media
import net.opendasharchive.openarchive.db.Project
import net.opendasharchive.openarchive.util.SecureFileUtil.encryptAndSave
import net.opendasharchive.openarchive.util.Utility
import net.opendasharchive.openarchive.util.extensions.makeSnackBar
import org.witness.proofmode.crypto.HashUtils
import timber.log.Timber
import java.io.File // OK
import java.util.Date

object Picker {

    private var currentPhotoUri: Uri? = null

    fun register(
        activity: ComponentActivity,
        root: View,
        project: () -> Project?,
        completed: (List<Media>) -> Unit
    ): MediaLaunchers {

        val mpl = activity.registerImagePicker { result ->

            val snackbar = showProgressSnackBar(activity, root, activity.getString(R.string.importing_media))

            activity.lifecycleScope.launch(Dispatchers.IO) {
                val media = import(activity, project(), result.map { it.uri })

                activity.lifecycleScope.launch(Dispatchers.Main) {
                    snackbar.dismiss()
                    completed(media)
                }
            }
        }

        val fpl = activity.registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode != AppCompatActivity.RESULT_OK) return@registerForActivityResult

            val uri = result.data?.data ?: return@registerForActivityResult

            val snackbar = showProgressSnackBar(activity, root, activity.getString(R.string.importing_media))

            activity.lifecycleScope.launch(Dispatchers.IO) {
                val files = import(activity, project(), listOf(uri))

                activity.lifecycleScope.launch(Dispatchers.Main) {
                    snackbar.dismiss()
                    completed(files)
                }
            }
        }

        val cpl = activity.registerForActivityResult(ActivityResultContracts.TakePicture()) { success ->
            if (success) {
                currentPhotoUri?.let { uri ->

                    val snackbar = showProgressSnackBar(activity, root, activity.getString(R.string.importing_media))

                    activity.lifecycleScope.launch(Dispatchers.IO) {
                        val media = import(activity, project(), listOf(uri))

                        activity.lifecycleScope.launch(Dispatchers.Main) {
                            snackbar.dismiss()
                            completed(media)
                        }
                    }
                }
            }
        }

        return MediaLaunchers(
            imagePickerLauncher = mpl,
            filePickerLauncher = fpl,
            cameraLauncher = cpl
        )
    }

    fun pickMedia(activity: Activity, launcher: ImagePickerLauncher) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (needAskForPermission(activity, arrayOf(
                    Manifest.permission.READ_MEDIA_IMAGES,
                    Manifest.permission.READ_MEDIA_VIDEO))
            ) {
                return
            }
        }

        val config = ImagePickerConfig {
            mode = ImagePickerMode.MULTIPLE
            isShowCamera = false
            returnMode = ReturnMode.NONE
            isFolderMode = true
            isIncludeVideo = true
            arrowColor = Color.WHITE
            limit = 99
            savePath = ImagePickerSavePath(Environment.getExternalStorageDirectory().path, false)
        }

        launcher.launch(config)
    }

    fun canPickFiles(context: Context): Boolean {
        return mFilePickerIntent.resolveActivity(context.packageManager) != null
    }

    fun pickFiles(launcher: ActivityResultLauncher<Intent>) {
        launcher.launch(mFilePickerIntent)
    }

    private val mFilePickerIntent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
        addCategory(Intent.CATEGORY_OPENABLE)
        type = "application/*"
    }

    private fun needAskForPermission(activity: Activity, permissions: Array<String>): Boolean {
        var needAsk = false

        for (permission in permissions) {
            needAsk = ContextCompat.checkSelfPermission(
                activity,
                permission
            ) != PackageManager.PERMISSION_GRANTED
                    && ActivityCompat.shouldShowRequestPermissionRationale(activity, permission)

            if (needAsk) break
        }

        if (!needAsk) return false

        ActivityCompat.requestPermissions(activity, permissions, 2)

        return true
    }

    private fun import(context: Context, project: Project?, uris: List<Uri>): ArrayList<Media> {
        val result = ArrayList<Media>()

        for (uri in uris) {
            try {
                val media = import(context, project, uri)
                if (media != null) result.add(media)
            } catch (e: Exception) {
                AppLogger.e("Error importing media", e)
            }
        }

        return result
    }

    fun import(context: Context, project: Project?, uri: Uri): Media? {
        val title = Utility.getUriDisplayName(context, uri) ?: ""
        val file = Utility.getOutputMediaFileByCache(context, title)

        if (file == null || !file.exists()) {
            Timber.e("File creation failed: ${file?.absolutePath}")
            return null
        }

        if (!Utility.writeStreamToFile(context.contentResolver.openInputStream(uri), file)) {
            Timber.e("Failed to write stream to file: ${file.absolutePath}")
            return null
        }

        if (file.name.endsWith(".enc")) {
            Timber.d("File is already encrypted, skipping encryption: ${file.absolutePath}")
            return null
        }

        // ✅ Encrypt only if it's NOT already encrypted
        val encryptedFile = File(file.parent, "${file.name}.enc")
        val encryptedResult = file.encryptAndSave(encryptedFile)

        if (encryptedResult == null) {  // ✅ Proper null check
            Timber.e("Encryption failed: ${encryptedFile.absolutePath}")
            return null
        }

        // ✅ Proceed with saving media
        val media = Media()
        media.originalFilePath = encryptedResult.absolutePath
        media.mimeType = Utility.getMimeType(context, uri) ?: ""
        media.createDate = Date()
        media.updateDate = media.createDate
        media.sStatus = Media.Status.Local
        media.projectId = project?.id ?: 0
        media.title = title
        media.save()

        return media
    }

    fun takePhoto(context: Context, launcher: ActivityResultLauncher<Uri>) {
        val file = Utility.getOutputMediaFileByCache(context, "IMG_${System.currentTimeMillis()}.jpg")

        file?.let {
            val encryptedFile = File(it.parent, it.name + ".enc")
            it.encryptAndSave(encryptedFile)

            val uri = FileProvider.getUriForFile(
                context, "${context.packageName}.provider",
                encryptedFile
            )
            currentPhotoUri = uri
            launcher.launch(uri)
        }
    }

    @SuppressLint("RestrictedApi")
    private fun showProgressSnackBar(activity: Activity, root: View, message: String): Snackbar {
        val bar = root.makeSnackBar(message)
        (bar.view as? Snackbar.SnackbarLayout)?.addView(ProgressBar(activity))
        bar.show()
        return bar
    }
}