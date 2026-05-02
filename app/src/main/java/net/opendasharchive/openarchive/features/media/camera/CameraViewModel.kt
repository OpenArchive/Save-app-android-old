package net.opendasharchive.openarchive.features.media.camera

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.video.FileOutputOptions
import androidx.camera.video.Recording
import androidx.camera.video.VideoRecordEvent
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.core.net.toFile
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import net.opendasharchive.openarchive.core.logger.AppLogger
import net.opendasharchive.openarchive.util.C2paHelper
import net.opendasharchive.openarchive.util.MetadataCollector
import net.opendasharchive.openarchive.util.Utility
import java.io.File
import java.security.MessageDigest

class CameraViewModel : ViewModel() {

    private val _state = MutableStateFlow(CameraState())
    val state: StateFlow<CameraState> = _state.asStateFlow()

    private var currentRecording: Recording? = null

    fun updateCaptureMode(mode: CameraCaptureMode) {
        val currentFlashMode = _state.value.flashMode

        val newFlashMode = if (mode == CameraCaptureMode.VIDEO &&
            currentFlashMode == ImageCapture.FLASH_MODE_AUTO) {
            ImageCapture.FLASH_MODE_ON
        } else {
            currentFlashMode
        }

        _state.value = _state.value.copy(
            captureMode = mode,
            flashMode = newFlashMode
        )
    }

    fun toggleFlashMode() {
        val currentFlashMode = _state.value.flashMode
        val currentCaptureMode = _state.value.captureMode

        val newFlashMode = if (currentCaptureMode == CameraCaptureMode.VIDEO) {
            when (currentFlashMode) {
                ImageCapture.FLASH_MODE_OFF -> ImageCapture.FLASH_MODE_ON
                else -> ImageCapture.FLASH_MODE_OFF
            }
        } else {
            when (currentFlashMode) {
                ImageCapture.FLASH_MODE_OFF -> ImageCapture.FLASH_MODE_ON
                ImageCapture.FLASH_MODE_ON -> ImageCapture.FLASH_MODE_AUTO
                else -> ImageCapture.FLASH_MODE_OFF
            }
        }

        _state.value = _state.value.copy(flashMode = newFlashMode)
    }

    fun updateFlashSupport(isSupported: Boolean) {
        _state.value = _state.value.copy(isFlashSupported = isSupported)
    }

    fun toggleCamera() {
        _state.value = _state.value.copy(
            isFrontCamera = !_state.value.isFrontCamera,
            isFlashSupported = false,
            flashMode = ImageCapture.FLASH_MODE_OFF
        )
    }

    fun toggleGrid() {
        _state.value = _state.value.copy(showGrid = !_state.value.showGrid)
    }

    fun capturePhoto(
        context: Context,
        imageCapture: ImageCapture,
        useCleanFilenames: Boolean = false,
        applyProvenance: Boolean = false,
        onSuccess: (Uri) -> Unit,
        onError: (Exception) -> Unit
    ) {
        viewModelScope.launch {
            try {
                val filename = "IMG_${System.currentTimeMillis()}.jpg"
                val outputFile = if (useCleanFilenames) {
                    Utility.getOutputMediaFileByCacheNoTimestamp(context, filename)
                } else {
                    Utility.getOutputMediaFileByCache(context, filename)
                }

                if (outputFile == null) {
                    onError(Exception("Failed to create output file"))
                    return@launch
                }

                val outputOptions = ImageCapture.OutputFileOptions.Builder(outputFile).build()

                imageCapture.takePicture(
                    outputOptions,
                    ContextCompat.getMainExecutor(context),
                    object : ImageCapture.OnImageSavedCallback {
                        override fun onImageSaved(output: ImageCapture.OutputFileResults) {
                            viewModelScope.launch(Dispatchers.IO) {
                                if (applyProvenance) {
                                    writeProvenanceForPhoto(context, outputFile)
                                }

                                val uri = FileProvider.getUriForFile(
                                    context,
                                    "${context.packageName}.provider",
                                    outputFile
                                )
                                val capturedItem = CapturedItem(uri, CameraCaptureMode.PHOTO)
                                val updatedItems = _state.value.capturedItems + capturedItem
                                _state.value = _state.value.copy(
                                    capturedItems = updatedItems,
                                    showPreview = true,
                                    currentPreviewItem = capturedItem
                                )
                                AppLogger.d("Photo captured: $uri")
                                withContext(Dispatchers.Main) { onSuccess(uri) }
                            }
                        }

                        override fun onError(exception: ImageCaptureException) {
                            AppLogger.e("Photo capture failed", exception)
                            onError(exception)
                        }
                    }
                )
            } catch (e: Exception) {
                AppLogger.e("Error setting up photo capture", e)
                onError(e)
            }
        }
    }

    fun startVideoRecording(
        context: Context,
        videoCapture: androidx.camera.video.VideoCapture<androidx.camera.video.Recorder>,
        useCleanFilenames: Boolean = false,
        applyProvenance: Boolean = false,
        onSuccess: (Uri) -> Unit,
        onError: (Exception) -> Unit
    ) {
        if (_state.value.isRecording) {
            AppLogger.w("Video recording already in progress")
            return
        }

        try {
            val filename = "VID_${System.currentTimeMillis()}.mp4"
            val outputFile = if (useCleanFilenames) {
                Utility.getOutputMediaFileByCacheNoTimestamp(context, filename)
            } else {
                Utility.getOutputMediaFileByCache(context, filename)
            }

            if (outputFile == null) {
                onError(Exception("Failed to create output file"))
                return
            }

            val fileOutputOptions = FileOutputOptions.Builder(outputFile).build()

            val hasAudioPermission = ContextCompat.checkSelfPermission(
                context, Manifest.permission.RECORD_AUDIO
            ) == PackageManager.PERMISSION_GRANTED

            var pendingRecording = videoCapture.output.prepareRecording(context, fileOutputOptions)
            if (hasAudioPermission) {
                pendingRecording = pendingRecording.withAudioEnabled()
            } else {
                AppLogger.w("RECORD_AUDIO permission not granted, recording without audio")
            }

            currentRecording = pendingRecording.start(
                ContextCompat.getMainExecutor(context)
            ) { recordEvent ->
                when (recordEvent) {
                    is VideoRecordEvent.Start -> {
                        _state.value = _state.value.copy(
                            isRecording = true,
                            recordingStartTime = System.currentTimeMillis()
                        )
                        AppLogger.d("Video recording started")
                    }

                    is VideoRecordEvent.Finalize -> {
                        _state.value = _state.value.copy(
                            isRecording = false,
                            recordingStartTime = null
                        )

                        if (!recordEvent.hasError()) {
                            viewModelScope.launch(Dispatchers.IO) {
                                if (applyProvenance) {
                                    writeProvenanceForVideo(context, outputFile)
                                }

                                val uri = FileProvider.getUriForFile(
                                    context,
                                    "${context.packageName}.provider",
                                    outputFile
                                )
                                val capturedItem = CapturedItem(uri, CameraCaptureMode.VIDEO)
                                val updatedItems = _state.value.capturedItems + capturedItem
                                _state.value = _state.value.copy(
                                    capturedItems = updatedItems,
                                    showPreview = true,
                                    currentPreviewItem = capturedItem
                                )
                                AppLogger.d("Video captured: $uri")
                                withContext(Dispatchers.Main) { onSuccess(uri) }
                            }
                        } else {
                            val error = Exception("Video recording failed: ${recordEvent.error}")
                            AppLogger.e("Video recording failed", error)
                            onError(error)
                        }
                    }
                }
            }
        } catch (e: Exception) {
            AppLogger.e("Error starting video recording", e)
            onError(e)
        }
    }

    private suspend fun writeProvenanceForPhoto(context: Context, file: File) {
        try {
            val metadata = MetadataCollector.collectMetadata(context)
            MetadataCollector.writeExifMetadata(file, metadata)
            val hash = sha256(file)
            if (hash.isNotEmpty()) {
                C2paHelper.generateManifest(
                    context   = context,
                    mediaFile = file,
                    mediaHash = hash,
                    metadata  = buildProofMetadata(context, file, hash, metadata)
                )
            }
        } catch (e: Exception) {
            AppLogger.e("Provenance write failed for ${file.name}", e)
        }
    }

    private suspend fun writeProvenanceForVideo(context: Context, file: File) {
        try {
            val metadata = MetadataCollector.collectMetadata(context)
            val hash = sha256(file)
            if (hash.isNotEmpty()) {
                C2paHelper.generateManifest(
                    context   = context,
                    mediaFile = file,
                    mediaHash = hash,
                    metadata  = buildProofMetadata(context, file, hash, metadata)
                )
            }
        } catch (e: Exception) {
            AppLogger.e("Provenance write failed for ${file.name}", e)
        }
    }

    private fun sha256(file: File): String = try {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { stream ->
            val buffer = ByteArray(8192)
            var bytesRead: Int
            while (stream.read(buffer).also { bytesRead = it } != -1) {
                digest.update(buffer, 0, bytesRead)
            }
        }
        digest.digest().joinToString("") { "%02x".format(it) }
    } catch (e: Exception) {
        AppLogger.e("SHA-256 hash failed for ${file.name}", e)
        ""
    }

    private fun buildProofMetadata(
        context: Context,
        file: File,
        hash: String,
        metadata: MetadataCollector.CaptureMetadata
    ): Map<String, String> {
        val isoFmt = java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", java.util.Locale.US).also {
            it.timeZone = java.util.TimeZone.getTimeZone("UTC")
        }
        val mimeType = android.webkit.MimeTypeMap.getSingleton()
            .getMimeTypeFromExtension(file.extension.lowercase())
            ?: "application/octet-stream"
        return buildMap {
            put("title",            file.name)
            put("mimeType",         mimeType)
            put("File Hash SHA256", hash)
            put("File Path",        file.absolutePath)
            put("File Created",     isoFmt.format(java.util.Date(metadata.captureTime)))
            put("File Modified",    isoFmt.format(java.util.Date(file.lastModified())))
            put("Proof Generated",  isoFmt.format(java.util.Date(metadata.captureTime)))
            put("Notes", "${metadata.appName} ${metadata.appVersion}")
            put("Manufacturer", metadata.deviceMake)
            put("Hardware", "${metadata.deviceMake} ${metadata.deviceModel}")
            put("Locale",   metadata.locale)
            put("Language", metadata.language)
            metadata.screenSizeInches?.let { put("ScreenSize", it.toString()) }
            metadata.latitude?.let         { put("Location.Latitude",  it.toString()) }
            metadata.longitude?.let        { put("Location.Longitude", it.toString()) }
            metadata.locationAltitude?.let { put("Location.Altitude",  it.toString()) }
            metadata.locationAccuracy?.let { put("Location.Accuracy",  it.toString()) }
            metadata.locationBearing?.let  { put("Location.Bearing",   it.toString()) }
            metadata.locationSpeed?.let    { put("Location.Speed",     it.toString()) }
            metadata.locationTime?.let     { put("Location.Time",      it.toString()) }
            metadata.locationProvider?.let { put("Location.Provider",  it) }
            metadata.networkType?.let { put("NetworkType", it) }
            metadata.networkType?.let { put("DataType",    it) }
            metadata.ipv4?.let        { put("IPv4", it) }
            metadata.ipv6?.let        { put("IPv6", it) }
            metadata.cellInfo?.let    { put("CellInfo", it) }
        }
    }

    fun stopVideoRecording() {
        if (_state.value.isRecording) {
            currentRecording?.stop()
            currentRecording = null
        }
    }

    fun showPreview(item: CapturedItem) {
        _state.value = _state.value.copy(
            showPreview = true,
            currentPreviewItem = item
        )
    }

    fun hidePreview(deleteFile: Boolean = false) {
        _state.value.currentPreviewItem?.let { item ->
            if (deleteFile) {
                val updatedItems = _state.value.capturedItems.filter { it != item }
                deleteFile(item.uri)
                AppLogger.d("Deleted cancelled capture: ${item.uri}")
                _state.value = _state.value.copy(
                    capturedItems = updatedItems,
                    showPreview = false,
                    currentPreviewItem = null
                )
            } else {
                _state.value = _state.value.copy(
                    showPreview = false,
                    currentPreviewItem = null
                )
            }
        } ?: run {
            _state.value = _state.value.copy(
                showPreview = false,
                currentPreviewItem = null
            )
        }
    }

    fun confirmCapture(item: CapturedItem): List<Uri> {
        return if (_state.value.capturedItems.contains(item)) {
            // Remove confirmed item so clearCaptures() in onCleared doesn't try to delete it
            _state.value = _state.value.copy(
                capturedItems = _state.value.capturedItems.filter { it != item }
            )
            listOf(item.uri)
        } else {
            emptyList()
        }
    }

    fun retakeCapture(item: CapturedItem) {
        deleteFile(item.uri)
        val updatedItems = _state.value.capturedItems.filter { it != item }
        _state.value = _state.value.copy(
            capturedItems = updatedItems,
            showPreview = false,
            currentPreviewItem = null
        )
        AppLogger.d("Deleted file for retake: ${item.uri}")
    }

    fun getAllCapturedUris(): List<Uri> {
        return _state.value.capturedItems.map { it.uri }
    }

    fun clearCaptures() {
        val itemsToDelete = _state.value.capturedItems
        AppLogger.d("Deleting ${itemsToDelete.size} unconfirmed captured items")
        itemsToDelete.forEach { item -> deleteFile(item.uri) }
        _state.value = CameraState()
    }

    override fun onCleared() {
        super.onCleared()
        stopVideoRecording()
        clearCaptures()
    }

    private fun deleteFile(uri: Uri) {
        try {
            val file = when (uri.scheme) {
                "content" -> {
                    val path = uri.path
                    if (path != null) {
                        val segments = path.split("/")
                        if (segments.size >= 2) File(segments.drop(1).joinToString("/")) else null
                    } else null
                }
                "file" -> try { uri.toFile() } catch (e: Exception) { null }
                else -> null
            }

            if (file != null && file.exists()) {
                val deleted = file.delete()
                if (deleted) AppLogger.d("Deleted file: ${file.absolutePath}")
                else AppLogger.w("Failed to delete file: ${file.absolutePath}")
            } else {
                AppLogger.w("File not found or invalid URI: $uri")
            }
        } catch (e: Exception) {
            AppLogger.e("Error deleting file for URI: $uri", e)
        }
    }
}
