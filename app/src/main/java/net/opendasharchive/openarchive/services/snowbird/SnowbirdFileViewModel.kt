package net.opendasharchive.openarchive.services.snowbird

import android.app.Application
import android.content.Context
import android.net.Uri
import androidx.core.content.FileProvider
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import net.opendasharchive.openarchive.db.FileUploadResult
import net.opendasharchive.openarchive.db.SnowbirdError
import net.opendasharchive.openarchive.db.SnowbirdFileItem
import net.opendasharchive.openarchive.util.BaseViewModel
import net.opendasharchive.openarchive.util.SecureFileUtil.decryptAndRestore
import net.opendasharchive.openarchive.util.SecureFileUtil.encryptAndSave
import net.opendasharchive.openarchive.util.trackProcessingWithTimeout
import timber.log.Timber
import java.io.File // OK

class SnowbirdFileViewModel(
    private val application: Application,
    private val repository: ISnowbirdFileRepository
) : BaseViewModel(application) {

    sealed class State {
        data object Idle : State()
        data object Loading : State()
        data class DownloadSuccess(val uri: Uri) : State()
        data class FetchSuccess(val files: List<SnowbirdFileItem>, var isRefresh: Boolean) : State()
        data class UploadSuccess(val result: FileUploadResult) : State()
        data class Error(val error: SnowbirdError) : State()
    }

    private val _mediaState = MutableStateFlow<State>(State.Idle)
    val mediaState: StateFlow<State> = _mediaState.asStateFlow()

    fun downloadFile(groupKey: String, repoKey: String, filename: String) {
        viewModelScope.launch {
            _mediaState.value = State.Loading
            try {
                val result = processingTracker.trackProcessingWithTimeout(30_000, "download_file") {
                    repository.downloadFile(groupKey, repoKey, filename)
                }

                _mediaState.value = when (result) {
                    is SnowbirdResult.Success -> onDownload(result.value, filename)
                    is SnowbirdResult.Error -> State.Error(result.error)
                }
            } catch (e: TimeoutCancellationException) {
                _mediaState.value = State.Error(SnowbirdError.TimedOut)
            }
        }
    }

    fun fetchFiles(groupKey: String, repoKey: String, forceRefresh: Boolean = false) {
        viewModelScope.launch {
            _mediaState.value = State.Loading
            try {
                val result = processingTracker.trackProcessingWithTimeout(60_000, "fetch_files") {
                    repository.fetchFiles(groupKey, repoKey, forceRefresh)
                }

                _mediaState.value = when (result) {
                    is SnowbirdResult.Success -> State.FetchSuccess(result.value, forceRefresh)
                    is SnowbirdResult.Error -> State.Error(result.error)
                }
            } catch (e: TimeoutCancellationException) {
                _mediaState.value = State.Error(SnowbirdError.TimedOut)
            }
        }
    }

    fun uploadFile(groupKey: String, repoKey: String, uri: Uri) {
        viewModelScope.launch {
            _mediaState.value = State.Loading
            try {
                val result = processingTracker.trackProcessingWithTimeout(30_000, "upload_file") {
                    repository.uploadFile(groupKey, repoKey, uri)
                }

                _mediaState.value = when (result) {
                    is SnowbirdResult.Success -> State.UploadSuccess(result.value)
                    is SnowbirdResult.Error -> State.Error(result.error)
                }
            } catch (e: TimeoutCancellationException) {
                _mediaState.value = State.Error(SnowbirdError.TimedOut)
            }
        }
    }

    private suspend fun onDownload(bytes: ByteArray, filename: String): State {
        Timber.d("Downloaded ${bytes.size} bytes")
        return saveByteArrayToEncryptedFile(application.applicationContext, bytes, filename).fold(
            onSuccess = { uri -> State.DownloadSuccess(uri) },
            onFailure = { error -> State.Error(SnowbirdError.GeneralError("Error saving file: ${error.message}")) }
        )
    }

    private suspend fun saveByteArrayToEncryptedFile(
        context: Context,
        byteArray: ByteArray,
        filename: String
    ): Result<Uri> = withContext(Dispatchers.IO) {
        runCatching {
            val directory = File(context.filesDir, "files").apply { mkdirs() }
            val encryptedFile = File(directory, "$filename.enc")
            val decryptedFile = File(directory, filename)

            // Save raw bytes temporarily before encryption
            decryptedFile.outputStream().use { it.write(byteArray) }

            // Encrypt the file and delete the unencrypted version
            decryptedFile.encryptAndSave(encryptedFile)
            decryptedFile.delete()

            // Return URI of encrypted file (decrypted before access)
            FileProvider.getUriForFile(
                context,
                "${context.packageName}.fileprovider",
                encryptedFile
            )
        }
    }

    private suspend fun getDecryptedFileUri(context: Context, encryptedFile: File): Uri {
        return withContext(Dispatchers.IO) {
            val decryptedFile = File(encryptedFile.parent, encryptedFile.name.removeSuffix(".enc"))

            if (encryptedFile.exists()) {
                encryptedFile.decryptAndRestore(decryptedFile)
            }

            FileProvider.getUriForFile(
                context,
                "${context.packageName}.fileprovider",
                decryptedFile
            )
        }
    }
}
