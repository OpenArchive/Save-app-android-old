package net.opendasharchive.openarchive.services.internetarchive.data

import android.content.Context
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
import net.opendasharchive.openarchive.services.CredentialsExpiredException
import net.opendasharchive.openarchive.services.IaSlowDownException
import net.opendasharchive.openarchive.services.SaveClient
import okhttp3.Call
import okhttp3.Headers
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import org.json.JSONObject
import java.io.IOException
import androidx.core.net.toFile
import androidx.core.net.toUri
import java.io.File
import net.opendasharchive.openarchive.services.common.network.RequestBodyUtil
import net.opendasharchive.openarchive.services.common.network.RequestListener
import net.opendasharchive.openarchive.services.common.network.createListener
import net.opendasharchive.openarchive.BuildConfig
import net.opendasharchive.openarchive.util.Utility
import org.koin.core.component.inject

class IaConduit(evidence: Evidence, context: Context) : Conduit(evidence, context) {

    private val authenticator: InternetArchiveAuthenticator by inject()

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

            // Extended timeouts for IA S3:
            // - writeTimeout: IA S3 uses streaming PUT; on Tor, throughput can drop to single-digit
            //   KB/s. OkHttp's default 60s write timeout fires per 64KB segment, causing spurious
            //   SocketTimeoutException on slow-but-healthy connections.
            // - readTimeout: IA processes the file server-side before ACKing — can take minutes
            //   for large files, especially over Tor.
            val client = SaveClient.get(mContext).newBuilder()
                .writeTimeout(5, TimeUnit.MINUTES)
                .readTimeout(5, TimeUnit.MINUTES)
                // Force HTTP/1.1: IA S3's HTTP/2 RST_STREAM handling surfaces as ProtocolException
                // via different code paths than HTTP/1.1 FIN+RST. HTTP/1.1 is more predictable
                // and our retry/backoff logic was written for its behavior.
                .protocols(listOf(okhttp3.Protocol.HTTP_1_1))
                .build()

            val fileName = getUploadFileName(mEvidence, true)
            val metaJson = getMetadata()

            // serverUrl is blank on a genuine first attempt; it is set (and persisted to DB via
            // jobFailed) the moment we assign an IA identifier. An ERROR item being retried will
            // always arrive here with serverUrl already set — either from the failed attempt or
            // from a previous session that was killed mid-upload.
            val hadIdentifier = mEvidence.serverUrl.isNotBlank()
            if (!hadIdentifier) {
                val slug = getSlug(mEvidence.title)
                val newIdentifier = "$slug-${Utility.RandomString(4).nextString()}"
                mEvidence = mEvidence.copy(serverUrl = newIdentifier)
            }

            // Upload content FIRST. An IA item with content but no meta.json is harmless —
            // IA will derive defaults. The reverse (meta.json with no content) creates an
            // orphaned item stub that IA cannot clean up automatically, corrupting the identifier.
            //
            // Check isAlreadyUploaded whenever we had a prior identifier (retried/errored item).
            // A freshly assigned identifier cannot have content on IA yet — skip the round-trip.
            val alreadyUploaded = hadIdentifier && client.isAlreadyUploaded(fileName, auth)

            if (alreadyUploaded) {
                AppLogger.i("IA: $fileName already uploaded to ${mEvidence.serverUrl}, skipping content upload")
            } else {
                // On 401 (expired S3 keys), we auto-revalidate once using the stored login
                // password and retry — no user intervention needed. All other failures propagate
                // to jobFailed() via the outer catch.
                var currentAuth = auth
                var credentialRefreshed = false
                while (true) {
                    if (mCancelled) return false
                    try {
                        client.uploadContent(fileName, mimeType, vault, currentAuth)
                        break
                    } catch (e: CredentialsExpiredException) {
                        if (credentialRefreshed) throw e
                        AppLogger.w("IA S3 keys expired for vault ${mEvidence.vaultId}, attempting silent reauth")
                        val freshAuth = authenticator.reauthenticate(mEvidence.vaultId).getOrElse {
                            AppLogger.e("Silent reauth failed: ${it.message}")
                            throw e
                        }
                        currentAuth = freshAuth
                        credentialRefreshed = true
                    }
                    // No IOException retry here — execute() already retries 5x with backoff internally.
                    // Any failure that survives execute() propagates to jobFailed().
                }
            }

            // Upload metadata after content succeeds — non-fatal if it fails.
            // IA item already exists with content; missing meta.json just means IA uses defaults.
            var metadataUploaded = false
            var metaAttempt = 0
            val maxMetaRetries = 3
            while (!metadataUploaded && metaAttempt < maxMetaRetries) {
                if (metaAttempt > 0) delay(2_000L * metaAttempt)
                metaAttempt++
                try {
                    client.uploadMetaData(metaJson, fileName, auth)
                    metadataUploaded = true
                } catch (e: Throwable) {
                    AppLogger.w("meta.json attempt $metaAttempt/$maxMetaRetries failed for $fileName: ${e.message}")
                }
            }
            if (!metadataUploaded) {
                AppLogger.e("meta.json failed all retries for $fileName — item uploaded without custom metadata")
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
        // Encode to bytes first so Content-Length is byte count (not char count).
        // content.length is UTF-16 char units; non-ASCII chars (e.g. accented titles,
        // CJK locations) produce more UTF-8 bytes → OkHttp FixedLengthSink would see
        // fewer bytes than declared and throw ProtocolException: unexpected end of stream.
        // No progress listener — metadata is small, no meaningful progress to report,
        // and a cancellable listener that exits early also triggers the same ProtocolException.
        val bytes = content.toByteArray(Charsets.UTF_8)
        val requestBody = RequestBodyUtil.create(
            textMediaType,
            bytes.inputStream(),
            bytes.size.toLong(),
            null
        )

        val url = "${ARCHIVE_API_ENDPOINT}/${mEvidence.serverUrl}/$fileName.meta.json"

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
            .add("User-Agent", "SaveApp/${BuildConfig.VERSION_NAME} (Android ${android.os.Build.VERSION.RELEASE})")
            .add("x-archive-auto-make-bucket", "1")
            .add("x-amz-auto-make-bucket", "1")
            .add("x-archive-interactive-priority", "1")
            .add("x-archive-meta-language", "eng") // FIXME set based on locale or selected.
            // x-archive-queue-derive intentionally NOT set on content upload — mirrors iOS
            // behaviour. The metadata upload (first) already sets it to defer derive.
            // Setting it on the content upload (last file) puts the item in a lower-priority
            // batch derive queue on IA's side, which causes more 503 Slow Down responses.
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
        // mediatype and collection must match what mainHeader() sets on the content upload.
        // Mismatching (e.g. "texts"/"opensource" for a video) forces IA's catalog to reconcile
        // the conflict during derive, adding latency before the item appears on the site.
        val (mediatype, collection) = when {
            mEvidence.mimeType.startsWith("video") -> "movies" to "opensource_movies"
            mEvidence.mimeType.startsWith("audio") -> "audio" to "opensource_audio"
            mEvidence.mimeType.startsWith("image") -> "image" to "opensource_media"
            else -> "data" to "opensource_media"
        }
        return Headers.Builder()
            .add("User-Agent", "SaveApp/${BuildConfig.VERSION_NAME} (Android ${android.os.Build.VERSION.RELEASE})")
            .add("x-amz-auto-make-bucket", "1")
            .add("x-archive-auto-make-bucket", "1")
            .add("x-archive-queue-derive", "0")
            .add("x-archive-meta-language", "eng")
            .add("Authorization", "LOW " + auth.username + ":" + auth.secret)
            .add("x-archive-meta-mediatype", mediatype)
            .add("x-archive-meta-collection", collection)
            .build()
    }

    @Throws(Exception::class)
    private suspend fun OkHttpClient.execute(request: Request) {
        // Single attempt — no HTTP-level retry. Transient failures (5xx, ProtocolException)
        // mark the item ERROR and let the queue-level retry logic handle re-attempts after
        // the 503 cooldown window. This avoids blocking the upload queue with multi-second
        // backoff delays that would stall WebDAV and other items.
        var activeRequest = request
        val maxRedirects = 5
        var redirectCount = 0

        while (redirectCount < maxRedirects) {
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
            } catch (e: java.net.ProtocolException) {
                // Stale connection or RST mid-stream — treat as transient server error.
                connectionPool.evictAll()
                throw IaSlowDownException("IA connection error (ProtocolException): ${e.message}")
            } finally {
                currentCall = null
            }
            when {
                code in 200..299 -> return
                // IA is "much more likely to issue 307 redirects than Amazon" (IAS3 docs).
                // OkHttp won't follow 307 on PUT with a streaming body — handle manually.
                code == 307 || code == 301 || code == 302 -> {
                    if (location.isNullOrBlank()) throw IOException("$code redirect with no Location header")
                    // Validate redirect stays on IA-owned domains — prevents credential
                    // leakage to attacker-controlled hosts via MITM or malicious Location header.
                    val redirectHost = try {
                        location.toHttpUrl().host
                    } catch (e: IllegalArgumentException) {
                        throw IOException("IA redirect $code has malformed URL: $location", e)
                    }
                    if (!redirectHost.endsWith(".archive.org") && redirectHost != "archive.org") {
                        throw SecurityException("IA redirect to non-archive.org host blocked: $redirectHost")
                    }
                    AppLogger.i("IA redirect $code → $location")
                    activeRequest = activeRequest.newBuilder().url(location).build()
                    redirectCount++
                }
                code == 401 -> throw CredentialsExpiredException(
                    "Internet Archive credentials have expired. Please remove and re-add your account."
                )
                // 5xx: transient server-side error — mark ERROR and let queue handle retry after cooldown.
                code in setOf(500, 502, 503, 504) -> throw IaSlowDownException("IA returned $code")
                else -> throw IOException("$code: $message")
            }
        }
        throw IOException("Too many IA redirects")
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
        // Strip only HTTP header control characters (CR, LF, NUL) that would break the
        // header wire format. Preserve all other Unicode so non-ASCII titles, locations,
        // and descriptions (Japanese, Arabic, accented Latin, etc.) reach IA intact.
        return value.replace("[\r\n ]".toRegex(), " ").trim()
    }
}
