package net.opendasharchive.openarchive.services.internetarchive

import android.content.Context
import android.net.Uri
import com.google.gson.GsonBuilder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import net.opendasharchive.openarchive.R
import net.opendasharchive.openarchive.db.Media
import net.opendasharchive.openarchive.services.Conduit
import net.opendasharchive.openarchive.services.SaveClient
import net.opendasharchive.openarchive.util.SecureFileUtil.decryptAndRestore
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import java.io.File // OK
import java.io.IOException

class IaConduit(media: Media, context: Context) : Conduit(media, context) {

    companion object {
        const val ARCHIVE_BASE_URL = "https://archive.org/"
        const val NAME = "Internet Archive"

        const val ARCHIVE_API_ENDPOINT = "https://s3.us.archive.org"
        private const val ARCHIVE_DETAILS_ENDPOINT = "https://archive.org/details/"

        private fun getSlug(title: String): String {
            return title.replace("[^A-Za-z\\d]".toRegex(), "-")
        }

        val textMediaType = "texts".toMediaTypeOrNull()

        private val gson = GsonBuilder().excludeFieldsWithoutExposeAnnotation().create()
    }

    override suspend fun upload(): Boolean {
        sanitize()

        try {
            val mimeType = mMedia.mimeType
            val client = SaveClient.get(mContext)
            val fileName = getUploadFileName(mMedia, true)
            val metaJson = gson.toJson(mMedia)

            if (mMedia.serverUrl.isBlank()) {
                val slug = getSlug(mMedia.title)
                val newIdentifier = "$slug-${Util.RandomString(4).nextString()}"
                mMedia.serverUrl = newIdentifier
            }

            // ✅ **Decrypt file before uploading**
            val decryptedFile = decryptMediaFile(mMedia) ?: throw IOException("Failed to decrypt media file.")

            // ✅ **Upload decrypted content**
            client.uploadContent(decryptedFile, fileName, mimeType)

            // ✅ **Upload metadata**
            client.uploadMetaData(metaJson, fileName)

            jobSucceeded()
            return true
        } catch (e: Throwable) {
            jobFailed(e)
        }

        return false
    }

    override suspend fun createFolder(url: String) {
        // Ignored. Not used here.
    }

    private suspend fun OkHttpClient.uploadContent(decryptedFile: File, fileName: String, mimeType: String) {
        val url = "${ARCHIVE_API_ENDPOINT}/${mMedia.serverUrl}/$fileName"

        val requestBody = RequestBodyUtil.create(
            "multipart/form-data".toMediaTypeOrNull(),
            decryptedFile.inputStream(),
            decryptedFile.length(),
            createListener(cancellable = { !mCancelled }, onProgress = {
                jobProgress(it)
            })
        )

        val request = Request.Builder()
            .url(url)
            .put(requestBody)
            .headers(mainHeader())
            .build()

        execute(request)
    }

    @Throws(IOException::class)
    private fun OkHttpClient.uploadMetaData(content: String, fileName: String) {
        val requestBody = RequestBodyUtil.create(
            textMediaType,
            content.byteInputStream(),
            content.length.toLong(),
            createListener(cancellable = { !mCancelled })
        )

        val url = "${ARCHIVE_API_ENDPOINT}/${mMedia.serverUrl}/$fileName.meta.json"

        val request = Request.Builder()
            .url(url)
            .put(requestBody)
            .headers(metadataHeader())
            .build()

        enqueue(request)
    }

    /// Upload proof files
    @Throws(IOException::class)
    private fun OkHttpClient.uploadProofFiles(uploadFile: File) {
        val requestBody = RequestBodyUtil.create(
            mContext.contentResolver,
            Uri.fromFile(uploadFile),
            uploadFile.length(),
            textMediaType,
            createListener(cancellable = { !mCancelled })
        )

        val url = "$ARCHIVE_API_ENDPOINT/${mMedia.serverUrl}/${uploadFile.name}"

        val request = Request.Builder()
            .url(url)
            .put(requestBody)
            .headers(metadataHeader())
            .build()

        enqueue(request)
    }

    /// **Helper Method: Decrypt Media File Before Upload**
    private fun decryptMediaFile(media: Media): File? {
        val encryptedFile = File(media.originalFilePath)
        val decryptedFile = File(encryptedFile.parent, encryptedFile.name.removeSuffix(".enc"))

        return if (encryptedFile.exists()) {
            encryptedFile.decryptAndRestore(decryptedFile)
        } else {
            null
        }
    }

    private fun mainHeader(): Headers {
        val builder = Headers.Builder()
            .add("Accept", "*/*")
            .add("x-archive-auto-make-bucket", "1")
            .add("x-amz-auto-make-bucket", "1")
            .add("x-archive-interactive-priority", "1")
            .add("x-archive-meta-language", "eng")
            .add("Authorization", "LOW " + mMedia.space?.username + ":" + mMedia.space?.password)

        val author = mMedia.author
        if (author.isNotEmpty()) {
            builder.add("x-archive-meta-author", author)
        }

        if (mMedia.contentLength > 0) {
            builder.add("x-archive-size-hint", mMedia.contentLength.toString())
        }

        val collection = when {
            mMedia.mimeType.startsWith("video") -> "opensource_movies"
            mMedia.mimeType.startsWith("audio") -> "opensource_audio"
            else -> "opensource_media"
        }
        builder.add("x-archive-meta-collection", collection)

        if (mMedia.mimeType.isNotEmpty()) {
            val mediaType = when {
                mMedia.mimeType.startsWith("image") -> "image"
                mMedia.mimeType.startsWith("video") -> "movies"
                mMedia.mimeType.startsWith("audio") -> "audio"
                else -> "data"
            }
            builder.add("x-archive-meta-mediatype", mediaType)
        }

        if (mMedia.location.isNotEmpty()) {
            builder.add("x-archive-meta-location", mMedia.location)
        }

        if (mMedia.tags.isNotEmpty()) {
            val tags = mMedia.tagSet
            tags.add(mContext.getString(R.string.default_tags))
            mMedia.tagSet = tags

            builder.add("x-archive-meta-subject", mMedia.tags)
        }

        if (mMedia.description.isNotEmpty()) {
            builder.add("x-archive-meta-description", mMedia.description)
        }

        if (mMedia.title.isNotEmpty()) {
            builder.add("x-archive-meta-title", mMedia.title)
        }

        var licenseUrl = mMedia.licenseUrl
        if (licenseUrl.isNullOrEmpty()) {
            licenseUrl = "https://creativecommons.org/licenses/by/4.0/"
        }

        builder.add("x-archive-meta-licenseurl", licenseUrl)
        return builder.build()
    }

    private fun metadataHeader(): Headers {
        return Headers.Builder()
            .add("x-amz-auto-make-bucket", "1")
            .add("x-archive-meta-language", "eng")
            .add("Authorization", "LOW " + mMedia.space?.username + ":" + mMedia.space?.password)
            .add("x-archive-meta-mediatype", "texts")
            .add("x-archive-meta-collection", "opensource")
            .build()
    }

    @Throws(Exception::class)
    private suspend fun OkHttpClient.execute(request: Request) = withContext(Dispatchers.IO) {
        val result = newCall(request).execute()
        if (!result.isSuccessful) throw RuntimeException("${result.code}: ${result.message}")
    }

    @Throws(Exception::class)
    private fun OkHttpClient.enqueue(request: Request) {
        newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                jobFailed(e)
            }

            override fun onResponse(call: Call, response: Response) {
                if (!response.isSuccessful) {
                    jobFailed(Exception("${response.code}: ${response.message}"))
                }
            }
        })
    }
}
