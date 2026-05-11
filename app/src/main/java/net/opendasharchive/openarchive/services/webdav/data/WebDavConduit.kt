package net.opendasharchive.openarchive.services.webdav.data

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import net.opendasharchive.openarchive.core.logger.AppLogger
import net.opendasharchive.openarchive.core.domain.Evidence
import net.opendasharchive.openarchive.services.Conduit
import net.opendasharchive.openarchive.services.SaveClient
import net.opendasharchive.openarchive.services.common.network.RequestBodyUtil
import net.opendasharchive.openarchive.services.common.network.createListener
import okhttp3.Call
import okhttp3.HttpUrl
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.FileNotFoundException
import java.io.IOException
import java.util.concurrent.TimeUnit


class WebDavConduit(evidence: Evidence, context: Context) : Conduit(evidence, context) {

    private lateinit var mClient: OkHttpClient

    @Volatile
    private var currentCall: Call? = null

    override fun cancel() {
        super.cancel()
        currentCall?.cancel()
    }

    override suspend fun upload(): Boolean {
        try {
            val vault = spaceRepository.getSpaceById(mEvidence.vaultId) ?: return false
            val auth = spaceRepository.getVaultAuth(mEvidence.vaultId) ?: return false
            val base = vault.hostUrl ?: return false
            val path = getPath() ?: return false

            // SaveClient.get() with user/pass adds BasicAuthInterceptor and, when Tor is
            // enabled in Prefs, routes all traffic through the SOCKS5 proxy automatically.
            // Extended write timeout: chunked uploads send 10 MB bodies; on Tor the default
            // 60s write timeout fires before the chunk is sent, leaving the upload stalled.
            mClient = SaveClient.get(
                context = mContext,
                user = auth.username,
                password = auth.secret,
                forceCloseConnection = true,
                allowHttp2 = false
            ).newBuilder()
                .writeTimeout(5, TimeUnit.MINUTES)
                .readTimeout(5, TimeUnit.MINUTES)
                .build()

            sanitize()

            val fileName = getUploadFileName(mEvidence)

            // Step 1: Create remote folders
            try {
                val archive = projectRepository.getProject(mEvidence.archiveId)
                createFolders(base, path, archive?.isRemote ?: false)
            } catch (e: Throwable) {
                jobFailed(e)
                return false
            }

            // Step 2: Validate file is accessible before starting upload.
            // RequestBodyUtil swallows FileNotFoundException and leaves inputStream=null,
            // which NPEs inside Okio.source(). Fail fast with a clear error.
            // ENOENT is not silently auto-deleted — it goes to ERROR state so the user
            // can see and act on it rather than the item disappearing without explanation.
            try {
                mContext.contentResolver.openInputStream(mEvidence.fileUri)?.close()
                    ?: throw IOException("openInputStream returned null for ${mEvidence.fileUri}")
            } catch (e: FileNotFoundException) {
                AppLogger.e("Media file missing (ENOENT), evidence ${mEvidence.id}: ${mEvidence.fileUri}")
                jobFailed(IOException("Media file not found: ${mEvidence.fileUri}", e))
                return false
            } catch (e: Throwable) {
                AppLogger.e("Media file inaccessible: ${mEvidence.fileUri}", e.message ?: "")
                jobFailed(e)
                return false
            }

            // Step 3: Upload media file (chunked for large files, single otherwise)
            AppLogger.i("Begin media file upload...")
            val uploadSuccess = if (mEvidence.contentLength > CHUNK_FILESIZE_THRESHOLD) {
                uploadChunked(base, path, fileName, vault.username)
            } else {
                uploadSingle(base, path, fileName)
            }

            // Step 4: Upload metadata only after media succeeds (non-fatal if metadata fails),
            // then mark succeeded so DB status reflects the final state.
            if (uploadSuccess) {
                try {
                    uploadMetadata(base, path, fileName)
                } catch (e: Throwable) {
                    AppLogger.e("Metadata upload failed (non-fatal): ${e.message}")
                }
                jobSucceeded()
            }

            return uploadSuccess
        } catch (e: Throwable) {
            jobFailed(e)
        }

        return false
    }

    override suspend fun createFolder(url: String) {
        if (!headExists(url)) {
            mkcol(url)
        } else {
            AppLogger.i("folder already exists: $url")
        }
    }

    private suspend fun uploadSingle(base: HttpUrl, path: List<String>, fileName: String): Boolean {
        val fullPath = construct(base, path, fileName)
        AppLogger.i("Uploading single file... $fullPath")

        try {
            val listener = createListener(cancellable = { !mCancelled }, onProgress = { jobProgress(it) })
            val requestBody = RequestBodyUtil.create(
                mContext.contentResolver,
                mEvidence.fileUri,
                mEvidence.contentLength,
                mEvidence.mimeType.toMediaTypeOrNull(),
                listener
            )
            execute(Request.Builder().url(fullPath).put(requestBody).build())
        } catch (e: Throwable) {
            jobFailed(e)
            return false
        }

        mEvidence = mEvidence.copy(serverUrl = fullPath)
        return true
    }

    @Throws(IOException::class)
    private suspend fun uploadChunked(
        base: HttpUrl,
        path: List<String>,
        fileName: String,
        username: String
    ): Boolean {
        AppLogger.i("Uploading started as chunked upload...")
        val vault = spaceRepository.getSpaceById(mEvidence.vaultId) ?: return false
        val url = vault.hostUrl ?: return false

        val tmpBase = HttpUrl.Builder()
            .scheme(url.scheme)
            .host(url.host)
            .port(url.port)
            .addPathSegment("remote.php")
            .addPathSegment("dav")
            .build()

        val tmpPath = listOf("uploads", username, fileName)

        return try {
            createFolders(tmpBase, tmpPath)

            // One HEAD to detect resume: check if the temp folder and first chunk already exist.
            // Fresh uploads skip all per-chunk existence checks (N × 1-2 RTT saved).
            val firstChunkSize = minOf(CHUNK_SIZE, mEvidence.contentLength).toInt()
            var isResuming = try {
                headExists(construct(tmpBase, tmpPath)) &&
                    headExists(construct(tmpBase, tmpPath, "0-$firstChunkSize"))
            } catch (e: Throwable) { false }

            AppLogger.i(if (isResuming) "Resuming chunked upload..." else "Fresh chunked upload, skipping per-chunk existence checks")

            var offset = 0
            // Single reusable buffer — avoids allocating 10 MB per chunk and the extra
            // copyOfRange() copy for the last (short) chunk.
            val chunkBuffer = ByteArray(CHUNK_SIZE.toInt())

            // Use contentResolver to support both file:// and content:// URIs
            (mContext.contentResolver.openInputStream(mEvidence.fileUri)
                ?: throw IOException("Cannot open input stream for ${mEvidence.fileUri}")).use { inputStream ->
                while (!mCancelled && offset < mEvidence.contentLength) {
                    // Read until buffer is full or EOF — same as readNBytes() (API 33+).
                    // Avoids short-read mid-file from a single read(byte[]) call.
                    var length = 0
                    while (length < chunkBuffer.size) {
                        val n = inputStream.read(chunkBuffer, length, chunkBuffer.size - length)
                        if (n == -1) break
                        length += n
                    }
                    if (length < 1) break

                    val total = offset + length
                    val chunkPath = construct(tmpBase, tmpPath, "$offset-$total")

                    // Only check existence when resuming. Once we find the first missing chunk,
                    // all subsequent chunks are also missing — stop scanning.
                    if (isResuming) {
                        val chunkExists = headExists(chunkPath)
                        if (chunkExists) {
                            val remoteLen = headContentLength(chunkPath)
                            if (remoteLen == length.toLong()) {
                                AppLogger.i("Resuming: chunk $offset-$total already present, skipping")
                                offset = total
                                continue
                            }
                        } else {
                            isResuming = false
                        }
                    }

                    val chunkBody = chunkBuffer.toRequestBody(mEvidence.mimeType.toMediaTypeOrNull(), byteCount = length)
                    execute(Request.Builder().url(chunkPath).put(chunkBody).build())
                    jobProgress(total.toLong())

                    offset = total
                }
            }

            if (mCancelled) throw Exception("Cancelled")

            val dest = mutableListOf("files", username)
            dest.addAll(path)

            move(construct(tmpBase, tmpPath, ".file"), construct(tmpBase, dest, fileName))

            mEvidence = mEvidence.copy(serverUrl = construct(base, path, fileName))
            true
        } catch (e: Throwable) {
            // Clean up partial upload slot on server to avoid orphaned temp chunks
            try {
                val tempFolder = construct(tmpBase, tmpPath)
                webdavDelete(tempFolder)
                AppLogger.i("Cleaned up partial chunked upload at $tempFolder")
            } catch (cleanupEx: Throwable) {
                AppLogger.w("Failed to clean up partial chunks: ${cleanupEx.message}")
            }
            jobFailed(e)
            false
        }
    }

    private suspend fun uploadMetadata(base: HttpUrl, path: List<String>, fileName: String) {
        AppLogger.i("Uploading metadata...")
        AppLogger.d("[C2PA_DEBUG] uploadMetadata: evidenceId=${mEvidence.id} hash=${mEvidence.mediaHashString}")
        val metadata = getMetadata()

        if (mCancelled) throw Exception("Cancelled")

        execute(
            Request.Builder()
                .url(construct(base, path, "$fileName.meta.json"))
                .put(metadata.toRequestBody("text/plain".toMediaTypeOrNull()))
                .build()
        )

        val c2paManifest = getC2paManifest()
        if (c2paManifest != null) {
            if (mCancelled) throw Exception("Cancelled")
            AppLogger.d("Uploading C2PA manifest: ${c2paManifest.name}")
            execute(
                Request.Builder()
                    .url(construct(base, path, c2paManifest.name))
                    .put(c2paManifest.readBytes().toRequestBody("application/json".toMediaTypeOrNull()))
                    .build()
            )
        }
    }

    // --- WebDAV HTTP helpers ---

    private suspend fun headExists(url: String): Boolean = withContext(Dispatchers.IO) {
        val response = mClient.newCall(Request.Builder().url(url).head().build()).execute()
        val code = response.code
        response.close()
        code in 200..299 || code == 207
    }

    private suspend fun headContentLength(url: String): Long = withContext(Dispatchers.IO) {
        val response = mClient.newCall(Request.Builder().url(url).head().build()).execute()
        val len = response.header("Content-Length")?.toLongOrNull() ?: -1L
        response.close()
        len
    }

    private suspend fun mkcol(url: String) = withContext(Dispatchers.IO) {
        val response = mClient.newCall(
            Request.Builder().url(url).method("MKCOL", null).build()
        ).execute()
        val code = response.code
        response.close()
        // 201 = created, 405 = already exists — both acceptable
        if (code !in 200..299 && code != 405) {
            throw IOException("MKCOL failed: $code for $url")
        }
    }

    private suspend fun move(sourceUrl: String, destinationUrl: String) = withContext(Dispatchers.IO) {
        val response = mClient.newCall(
            Request.Builder()
                .url(sourceUrl)
                .method("MOVE", null)
                .header("Destination", destinationUrl)
                .header("Overwrite", "T")
                .build()
        ).execute()
        val code = response.code
        val message = response.message
        response.close()
        if (code !in 200..299) throw IOException("MOVE failed: $code $message")
    }

    private suspend fun webdavDelete(url: String) = withContext(Dispatchers.IO) {
        val response = mClient.newCall(Request.Builder().url(url).delete().build()).execute()
        response.close()
    }

    @Throws(IOException::class)
    private suspend fun execute(request: Request) {
        val call = mClient.newCall(request)
        currentCall = call
        try {
            val response = withContext(Dispatchers.IO) { call.execute() }
            val code = response.code
            val message = response.message
            response.close()
            if (code !in 200..299) throw IOException("$code: $message")
        } finally {
            currentCall = null
        }
    }
}
