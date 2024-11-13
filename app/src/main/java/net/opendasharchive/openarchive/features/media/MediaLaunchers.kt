package net.opendasharchive.openarchive.features.media

import android.content.Intent
import android.net.Uri
import androidx.activity.result.ActivityResultLauncher
import com.esafirm.imagepicker.features.ImagePickerLauncher

data class MediaLaunchers(
    val imagePickerLauncher: ImagePickerLauncher,
    val filePickerLauncher: ActivityResultLauncher<Intent>,
    val cameraLauncher: ActivityResultLauncher<Uri>
)