package net.opendasharchive.openarchive.services.internetarchive.data

import android.content.Context
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit
import net.opendasharchive.openarchive.R
import net.opendasharchive.openarchive.core.domain.Evidence
import net.opendasharchive.openarchive.core.domain.VaultAuth
import net.opendasharchive.openarchive.core.domain.Vault
import net.opendasharchive.openarchive.core.logger.AppLogger
import net.opendasharchive.openarchive.services.Conduit
import net.opendasharchive.openarchive.services.IaSlowDownException
import net.opendasharchive.openarchive.services.SaveClient
import okhttp3.Call
import okhttp3.Headers
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import org.json.JSONObject
import java.io.File
import java.io.IOException
import androidx.core.net.toFile
import androidx.core.net.toUri
import net.opendasharchive.openarchive.services.common.network.RequestBodyUtil
import net.opendasharchive.openarchive.services.common.network.RequestListener
import net.opendasharchive.openarchive.services.common.network.createListener
import net.opendasharchive.openarchive.util.Utility

class IaConduit(evidence: Evidence, context: Context) : Conduit(evidence, context) {

    @Volatile
    private var currentCall: Call? = null

    override fun cancel() {
        super.cancel()
        currentCall?.cancel()
    }


    companion object {
        const val ARCHIVE_BASE_URL = "https://archive.org/"
        const val NAME = "Internet Archive"

        const val ARCHIVE_API_ENDPOINT = "https://s3.us.archive.org"
        private const val ARCHIVE_DETAILS_ENDPOINT = "https://archive.org/details/"

        private fun getSlug(title: String): String {
            return title.replace("[^A-Za-z\\d]".toRegex(), "-")
        }

        val textMediaType = "texts".toMediaTypeOrNull()
    }

    override suspend fun upload(): Boolean {
        sanitize()

        try {
            val vault = spaceRepository.getSpaceById(mEvidence.vaultId) ?: return false
            val auth = spaceRepository.getVaultAuth(mEvidence.vaultId) ?: return false
            val mimeType = mEvidence.mimeType

            // Use a longer read timeout — IA S3 processes the file before ACKing, which
            // can take well over 60s for large files, especially over TOR.
            val client = SaveClient.get(mContext).newBuilder()
                .readTimeout(5, TimeUnit.MINUTES)
                .build()

            val fileName = getUploadFileName(mEvidence, true)
            val metaJson = getMetadata()

            if (mEvidence.serverUrl.isBlank()) {
                val slug = getSlug(mEvidence.title)
                val newIdentifier = "$slug-${Utility.RandomString(4).nextString()}"
                mEvidence = mEvidence.copy(serverUrl = newIdentifier)
            }

            // If this is a retry and the file is already on IA, skip re-uploading.
            if (client.isAlreadyUploaded(fileName, auth)) {
                AppLogger.i("IA: $fileName already uploaded to ${mEvidence.serverUrl}, skipping content upload")
            } else {
                // Pre-flight: check IA queue capacity before attempting upload.
                // https://s3.us.archive.org/?check_limit=1&accesskey=…&bucket=…
                // Returns over_limit=1 when the task queue is full and uploads will 503.
                client.waitForIaCapacity(auth)

                // Upload content — retry on transient network errors (connection drop, TOR circuit rotation)
                var ioAttempt = 0
                val maxIoRetries = 3
                while (true) {
                    if (mCancelled) return false
                    try {
                        client.uploadContent(fileName, mimeType, vault, auth)
                        break
                    } catch (e: IOException) {
                        ioAttempt++
                        if (ioAttempt >= maxIoRetries || mCancelled) throw e
                        // Short base delay + jitter so parallel retries don't hit IA in lockstep.
                        val baseMs = 2_000L * ioAttempt
                        val jitterMs = (Math.random() * 500).toLong()
                        val delayMs = baseMs + jitterMs
                        AppLogger.w("IA upload network error (attempt $ioAttempt/$maxIoRetries), retrying in ${delayMs / 1000}s: ${e.message}")
                        delay(delayMs)
                    }
                }
            }

            // upload metadata — non-fatal if it fails
            try {
                client.uploadMetaData(metaJson, fileName, auth)
            } catch (e: Throwable) {
                AppLogger.e("Failed to upload meta.json for $fileName", e)
            }

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

    private suspend fun OkHttpClient.uploadContent(
        fileName: String,
        mimeType: String,
        vault: Vault,
        auth: VaultAuth
    ) {
        val url = "${ARCHIVE_API_ENDPOINT}/${mEvidence.serverUrl}/$fileName"
        val listener = createListener(cancellable = { !mCancelled }, onProgress = { jobProgress(it) })

        val requestBody = run {
            val uri = mEvidence.originalFilePath.toUri()
            val scheme = uri.scheme
            if (scheme == null || scheme == "file") {
                // plain path or file:// URI — open directly as File to avoid ContentResolver issues
                val file = if (scheme == "file") uri.toFile() else File(mEvidence.originalFilePath)
                RequestBodyUtil.create(file, mimeType.toMediaTypeOrNull(), listener)
            } else {
                RequestBodyUtil.create(
                    mContext.contentResolver,
                    uri,
                    mEvidence.contentLength,
                    mimeType.toMediaTypeOrNull(),
                    listener
                )
            }
        }

        val request = Request.Builder()
            .url(url)
            .put(requestBody)
            .headers(mainHeader(vault, auth))
            .build()

        execute(request)
    }

    @Throws(IOException::class)
    private suspend fun OkHttpClient.uploadMetaData(content: String, fileName: String, auth: VaultAuth) {
        val requestBody = RequestBodyUtil.create(
            textMediaType,
            content.byteInputStream(),
            content.length.toLong(),
            createListener(cancellable = { !mCancelled })
        )

        val url = "${ARCHIVE_API_ENDPOINT}/${mEvidence.serverUrl}/$fileName.meta.json"

        val request = Request.Builder()
            .url(url)
            .put(requestBody)
            .headers(metadataHeader(auth))
            .build()

        execute(request)
    }

    /// upload proof mode
    @Throws(IOException::class)
    private suspend fun OkHttpClient.uploadProofFiles(uploadFile: File, auth: VaultAuth) {
        val requestBody = RequestBodyUtil.create(
            mContext.contentResolver,
            Uri.fromFile(uploadFile),
            uploadFile.length(),
            textMediaType, createListener(cancellable = { !mCancelled })
        )

        val url = "$ARCHIVE_API_ENDPOINT/${mEvidence.serverUrl}/${uploadFile.name}"

        val request = Request.Builder()
            .url(url)
            .put(requestBody)
            .headers(metadataHeader(auth))
            .build()

        execute(request)
    }

    private fun mainHeader(vault: Vault, auth: VaultAuth): Headers {
        val builder = Headers.Builder()
            .add("Accept", "*/*")
            .add("x-archive-auto-make-bucket", "1")
            .add("x-amz-auto-make-bucket", "1")
            .add("x-archive-interactive-priority", "1")
            .add("x-archive-meta-language", "eng") // FIXME set based on locale or selected.
            // Defer IA's derive pipeline until all files in the item are uploaded.
            // Without this, IA starts transcoding/OCR immediately on each PUT, which
            // competes for server resources and slows down the upload ACK for large files.
            .add("x-archive-queue-derive", "0")
            .add("Authorization", "LOW " + auth.username + ":" + auth.secret)

        val author = mEvidence.author
        if (author.isNotEmpty()) {
            builder.add("x-archive-meta-author", author)
        }

        if (mEvidence.contentLength > 0) {
            builder.add("x-archive-size-hint", mEvidence.contentLength.toString())
        }

        val collection = when {
            mEvidence.mimeType.startsWith("video") -> "opensource_movies"
            mEvidence.mimeType.startsWith("audio") -> "opensource_audio"
            else -> "opensource_media"
        }
        builder.add("x-archive-meta-collection", collection)

        if (mEvidence.mimeType.isNotEmpty()) {
            val mediaType = when {
                mEvidence.mimeType.startsWith("image") -> "image"
                mEvidence.mimeType.startsWith("video") -> "movies"
                mEvidence.mimeType.startsWith("audio") -> "audio"
                else -> "data"
            }
            builder.add("x-archive-meta-mediatype", mediaType)
        }

        if (mEvidence.location.isNotEmpty()) {
            builder.add("x-archive-meta-location", sanitizeHeaderValue(mEvidence.location))
        }

        if (mEvidence.tags.isNotEmpty()) {
            val tags = mEvidence.tags + listOf(mContext.getString(R.string.default_tags))
            builder.add("x-archive-meta-subject", tags.joinToString(","))
        }

        if (mEvidence.description.isNotEmpty()) {
            builder.add("x-archive-meta-description", sanitizeHeaderValue(mEvidence.description))
        }

        if (mEvidence.title.isNotEmpty()) {
            builder.add("x-archive-meta-title", mEvidence.title)
        }

        var licenseUrl = vault.licenseUrl

        if (licenseUrl.isNullOrEmpty()) {
            licenseUrl = "https://creativecommons.org/licenses/by/4.0/"
        }

        builder.add("x-archive-meta-licenseurl", licenseUrl)

        return builder.build()
    }

    /// headers for meta-data and proof mode
    private fun metadataHeader(auth: VaultAuth): Headers {
        return Headers.Builder()
            .add("x-amz-auto-make-bucket", "1")
            .add("x-archive-meta-language", "eng") // TODO: FIXME set based on locale or selected
            .add("Authorization", "LOW " + auth.username + ":" + auth.secret)
            .add("x-archive-meta-mediatype", "texts")
            .add("x-archive-meta-collection", "opensource")
            .build()
    }

    @Throws(Exception::class)
    private suspend fun OkHttpClient.execute(request: Request) {
        // Start at 2 s and double each attempt (2 → 4 → 8 → 16 → 20 s) with ±500 ms jitter.
        // iOS background URLSession retries transparently at the OS level; we approximate that
        // here without the 30 s penalty that was making uploads feel stalled on Android.
        var delayMs = 2_000L
        val maxRetries = 5
        var activeRequest = request
        repeat(maxRetries) { attempt ->
            if (mCancelled) throw IOException("Cancelled")
            val call = newCall(activeRequest)
            currentCall = call
            val code: Int
            val message: String
            val location: String?
            try {
                val response = withContext(Dispatchers.IO) { call.execute() }
                code = response.code
                message = response.message
                location = response.header("Location")
                response.close()
            } finally {
                currentCall = null
            }
            when {
                code in 200..299 -> return
                // IA is "much more likely to issue 307 redirects than Amazon" (IAS3 docs).
                // OkHttp won't follow 307 on PUT with a streaming body — handle manually.
                code == 307 || code == 301 || code == 302 -> {
                    if (location.isNullOrBlank()) throw RuntimeException("$code redirect with no Location header")
                    AppLogger.i("IA redirect $code → $location (attempt ${attempt + 1})")
                    activeRequest = activeRequest.newBuilder().url(location).build()
                    // No delay — redirect should be followed immediately
                }
                code == 503 && attempt < maxRetries - 1 -> {
                    val jitterMs = (Math.random() * 500).toLong()
                    val waitMs = delayMs + jitterMs
                    AppLogger.w("IA returned 503 Slow Down, retrying in ${waitMs / 1000}s (attempt ${attempt + 1}/$maxRetries)")
                    delay(waitMs)
                    delayMs = minOf(delayMs * 2, 20_000L)
                }
                else -> throw RuntimeException("$code: $message")
            }
        }
        throw IaSlowDownException("IA returned 503 Slow Down after $maxRetries attempts — re-queuing for later retry")
    }

    /**
     * Checks IA's task queue via the check_limit API before uploading.
     * If over_limit=1, waits with exponential backoff until capacity is available.
     * Prevents 503 SlowDown by not even attempting upload when queue is full.
     * https://archive.org/developers/ias3.html
     */
    private suspend fun OkHttpClient.waitForIaCapacity(auth: VaultAuth) {
        var delayMs = 3_000L
        val maxWaitAttempts = 5
        repeat(maxWaitAttempts) { attempt ->
            if (mCancelled) return
            try {
                val url = "$ARCHIVE_API_ENDPOINT/?check_limit=1&accesskey=${auth.username}&bucket=${mEvidence.serverUrl}"
                val request = Request.Builder().url(url).get().build()
                val body = withContext(Dispatchers.IO) { newCall(request).execute() }.use { it.body?.string() }
                val overLimit = body?.let { JSONObject(it).optInt("over_limit", 0) } ?: 0
                if (overLimit == 0) {
                    AppLogger.i("IA queue capacity OK (attempt ${attempt + 1})")
                    return
                }
                val jitterMs = (Math.random() * 500).toLong()
                val waitMs = delayMs + jitterMs
                AppLogger.w("IA queue over limit, waiting ${waitMs / 1000}s before upload (attempt ${attempt + 1}/$maxWaitAttempts)")
                delay(waitMs)
                delayMs = minOf(delayMs * 2, 15_000L)
            } catch (e: Exception) {
                // check_limit failure is non-fatal — proceed with upload attempt
                AppLogger.w("IA check_limit failed (non-fatal): ${e.message}")
                return
            }
        }
        AppLogger.w("IA queue still over limit after $maxWaitAttempts checks — proceeding anyway")
    }

    /**
     * Returns true if this file is already present on IA for the current identifier.
     * Used to skip re-uploading on retry after a partial success (file uploaded but
     * jobSucceeded() didn't persist, e.g. process was killed).
     */
    private suspend fun OkHttpClient.isAlreadyUploaded(fileName: String, auth: VaultAuth): Boolean {
        if (mEvidence.serverUrl.isBlank()) return false
        return try {
            val url = "https://archive.org/metadata/${mEvidence.serverUrl}"
            val request = Request.Builder()
                .url(url)
                .header("Authorization", "LOW ${auth.username}:${auth.secret}")
                .get()
                .build()
            val body = withContext(Dispatchers.IO) { newCall(request).execute() }.use { it.body?.string() }
            if (body.isNullOrBlank()) return false
            val json = JSONObject(body)
            val files = json.optJSONArray("files") ?: return false
            for (i in 0 until files.length()) {
                if (files.getJSONObject(i).optString("name") == fileName) return true
            }
            false
        } catch (e: Exception) {
            AppLogger.w("IA metadata check failed (non-fatal): ${e.message}")
            false
        }
    }

    private fun sanitizeHeaderValue(value: String): String {
        return value.replace("[^\\x20-\\x7E]".toRegex(), "") // Removes non-ASCII characters
    }
}
