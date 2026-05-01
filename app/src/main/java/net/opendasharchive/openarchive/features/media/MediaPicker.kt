package net.opendasharchive.openarchive.features.media

import android.content.Context
import android.net.Uri
import net.opendasharchive.openarchive.core.domain.Archive
import net.opendasharchive.openarchive.core.domain.Evidence
import net.opendasharchive.openarchive.core.domain.EvidenceStatus
import net.opendasharchive.openarchive.core.domain.VaultType
import net.opendasharchive.openarchive.core.logger.AppLogger
import net.opendasharchive.openarchive.util.DateUtils
import net.opendasharchive.openarchive.util.MediaThumbnailGenerator
import net.opendasharchive.openarchive.util.Utility
import net.opendasharchive.openarchive.util.toLocalDateTime
import java.io.File
import java.io.FileNotFoundException
import java.security.MessageDigest

/**
 * Imports media files into internal storage and creates Evidence domain objects.
 *
 * Responsibility: copy file, generate thumbnail, compute hash, write DB record.
 * NOT responsible for EXIF, C2PA, or metadata — those are written at capture time
 * in CameraViewModel for WebDAV + camera + C2PA-enabled captures.
 */
object MediaPicker {

    suspend fun import(
        context: Context,
        archive: Archive,
        submissionId: Long,
        uris: List<Uri>,
        fromCamera: Boolean = false,
        vaultType: VaultType? = null,
    ): ArrayList<Evidence> {
        val result = ArrayList<Evidence>()

        for (uri in uris) {
            try {
                val evidence = import(context, archive, submissionId, uri)
                if (evidence != null) result.add(evidence)
            } catch (e: Exception) {
                AppLogger.e("Error importing media", e)
            }
        }

        return result
    }

    suspend fun import(
        context: Context,
        archive: Archive,
        submissionId: Long,
        uri: Uri,
        fromCamera: Boolean = false,
        vaultType: VaultType? = null,
    ): Evidence? = import(context, archive, submissionId, uri)

    private suspend fun import(
        context: Context,
        archive: Archive,
        submissionId: Long,
        uri: Uri,
    ): Evidence? {

        val title = Utility.getUriDisplayName(context, uri)
            ?: uri.lastPathSegment
            ?: uri.path?.substringAfterLast('/')
            ?: ""
        val file = Utility.getOutputMediaFile(context, title.ifBlank { "media" })

        try {
            context.contentResolver.openInputStream(uri)?.use { inputStream ->
                if (!Utility.writeStreamToFile(inputStream, file)) {
                    AppLogger.e("Failed to write stream to file for URI: $uri")
                    return null
                }
            } ?: run {
                AppLogger.e("Failed to open input stream for URI: $uri")
                return null
            }
        } catch (e: FileNotFoundException) {
            AppLogger.e("File not found for URI: $uri", e)
            return null
        } catch (e: SecurityException) {
            AppLogger.e("Permission denied for URI: $uri", e)
            return null
        } catch (e: java.io.IOException) {
            AppLogger.e("IO error reading URI: $uri", e)
            return null
        }

        val fileSource = uri.path?.let { File(it) }
        var createDate = DateUtils.now
        var contentLength = 0L

        if (fileSource?.exists() == true) {
            createDate = fileSource.lastModified()
            contentLength = fileSource.length()
        } else {
            contentLength = file?.length() ?: 0
        }

        val mimeType = getMimeTypeWithFallback(context, uri, file?.path)

        val mediaHashString = try {
            file?.let { f ->
                val digest = MessageDigest.getInstance("SHA-256")
                f.inputStream().use { stream ->
                    val buffer = ByteArray(8192)
                    var bytesRead: Int
                    while (stream.read(buffer).also { bytesRead = it } != -1) {
                        digest.update(buffer, 0, bytesRead)
                    }
                }
                digest.digest().joinToString("") { "%02x".format(it) }
            } ?: ""
        } catch (e: Exception) {
            AppLogger.e("Failed to generate hash for media", e)
            ""
        }

        val thumbnail = try {
            file?.let { MediaThumbnailGenerator.generateThumbnailBytes(it, mimeType) }
        } catch (e: Exception) {
            AppLogger.e("Failed to generate thumbnail for media", e)
            null
        }

        return Evidence(
            archiveId        = archive.id,
            submissionId     = submissionId,
            title            = title,
            originalFilePath = Uri.fromFile(file).toString(),
            thumbnail        = thumbnail,
            mimeType         = mimeType,
            contentLength    = contentLength,
            createdAt        = createDate.toLocalDateTime(),
            updatedAt        = createDate.toLocalDateTime(),
            status           = EvidenceStatus.LOCAL,
            mediaHashString  = mediaHashString
        )
    }

    private fun getMimeTypeWithFallback(context: Context, uri: Uri, filePath: String?): String {
        val standardMimeType = Utility.getMimeType(context, uri)
        if (!standardMimeType.isNullOrEmpty()) return standardMimeType

        val extension = when {
            filePath != null -> File(filePath).extension
            uri.path != null -> File(uri.path!!).extension
            else             -> null
        }

        return when (extension?.lowercase()) {
            "jpg", "jpeg" -> "image/jpeg"
            "png"         -> "image/png"
            "gif"         -> "image/gif"
            "webp"        -> "image/webp"
            "mp4"         -> "video/mp4"
            "mov"         -> "video/quicktime"
            "avi"         -> "video/x-msvideo"
            "mkv"         -> "video/x-matroska"
            "webm"        -> "video/webm"
            "mp3"         -> "audio/mpeg"
            "wav"         -> "audio/wav"
            "ogg"         -> "audio/ogg"
            "m4a"         -> "audio/mp4"
            else -> {
                AppLogger.w("Unknown file extension '$extension' for URI: $uri")
                "application/octet-stream"
            }
        }
    }
}
