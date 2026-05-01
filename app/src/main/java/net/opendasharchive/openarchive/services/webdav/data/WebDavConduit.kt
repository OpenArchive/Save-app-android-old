package net.opendasharchive.openarchive.services.webdav.data

import android.content.Context
import com.thegrizzlylabs.sardineandroid.SardineListener
import com.thegrizzlylabs.sardineandroid.impl.OkHttpSardine
import net.opendasharchive.openarchive.core.logger.AppLogger
import net.opendasharchive.openarchive.core.domain.Evidence
import net.opendasharchive.openarchive.services.Conduit
import net.opendasharchive.openarchive.services.SaveClient
import okhttp3.HttpUrl
import java.io.FileNotFoundException
import java.io.IOException


class WebDavConduit(evidence: Evidence, context: Context) : Conduit(evidence, context) {

    private lateinit var mClient: OkHttpSardine

    override suspend fun upload(): Boolean {
        try {
            val vault = spaceRepository.getSpaceById(mEvidence.vaultId) ?: return false
            val auth = spaceRepository.getVaultAuth(mEvidence.vaultId) ?: return false
            val base = vault.hostUrl ?: return false
            val path = getPath() ?: return false

            mClient = SaveClient.getSardine(mContext, auth.username, auth.secret)
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
                uploadChunked(base, path, fileName)
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
        if (!mClient.exists(url)) {
            mClient.createDirectory(url)
        } else {
            AppLogger.i("folder already exists: ", url)
        }
    }

    private suspend fun uploadSingle(base: HttpUrl, path: List<String>, fileName: String): Boolean {
        val fullPath = construct(base, path, fileName)
        AppLogger.i("Uploading single file...", "filePath: $fullPath")

        try {
            mClient.put(
                mContext.contentResolver,
                fullPath,
                mEvidence.fileUri,
                mEvidence.contentLength,
                mEvidence.mimeType,
                false,
                object : SardineListener {
                    var lastBytes: Long = 0

                    override fun transferred(bytes: Long) {
                        if (bytes > lastBytes) {
                            jobProgress(bytes)
                            lastBytes = bytes
                        }
                    }

                    override fun continueUpload(): Boolean = !mCancelled
                })
        } catch (e: Throwable) {
            jobFailed(e)
            return false
        }

        mEvidence = mEvidence.copy(serverUrl = fullPath)
        return true
    }

    @Throws(IOException::class)
    private suspend fun uploadChunked(base: HttpUrl, path: List<String>, fileName: String): Boolean {
        AppLogger.i("Uploading started as chunked upload...")
        val vault = spaceRepository.getSpaceById(mEvidence.vaultId) ?: return false
        val url = vault.hostUrl ?: return false

        val tmpBase = HttpUrl.Builder()
            .scheme(url.scheme)
            .username(url.username)
            .password(url.password)
            .host(url.host)
            .port(url.port)
            .query(url.query)
            .fragment(url.fragment)
            .addPathSegment("remote.php")
            .addPathSegment("dav")
            .build()

        val tmpPath = listOf("uploads", vault.username, fileName)

        return try {
            createFolders(tmpBase, tmpPath)

            var offset = 0

            // Use contentResolver to support both file:// and content:// URIs
            (mContext.contentResolver.openInputStream(mEvidence.fileUri)
                ?: throw IOException("Cannot open input stream for ${mEvidence.fileUri}")).use { inputStream ->
                while (!mCancelled && offset < mEvidence.contentLength) {
                    var buffer = ByteArray(CHUNK_SIZE.toInt())
                    val length = inputStream.read(buffer)
                    if (length < 1) break

                    if (length < CHUNK_SIZE) buffer = buffer.copyOfRange(0, length)

                    val total = offset + length
                    val chunkPath = construct(tmpBase, tmpPath, "$offset-$total")
                    val chunkExists = mClient.exists(chunkPath)
                    var chunkLengthMatches = false

                    if (chunkExists) {
                        val dirList = mClient.list(chunkPath)
                        chunkLengthMatches =
                            !dirList.isNullOrEmpty() && dirList.first().contentLength == length.toLong()
                    }

                    if (!chunkExists || !chunkLengthMatches) {
                        mClient.put(
                            chunkPath,
                            buffer,
                            mEvidence.mimeType,
                            object : SardineListener {
                                override fun transferred(bytes: Long) {
                                    jobProgress(offset.toLong() + bytes)
                                }

                                override fun continueUpload(): Boolean = !mCancelled
                            })
                    }

                    // Fix: offset = total (was total + 1, which skipped 1 byte per chunk boundary)
                    offset = total
                }
            }

            if (mCancelled) throw Exception("Cancelled")

            val dest = mutableListOf("files", vault.username)
            dest.addAll(path)

            mClient.move(construct(tmpBase, tmpPath, ".file"), construct(tmpBase, dest, fileName))

            mEvidence = mEvidence.copy(serverUrl = construct(base, path, fileName))
            true
        } catch (e: Throwable) {
            // Clean up partial upload slot on server to avoid orphaned temp chunks
            try {
                mClient.delete(construct(tmpBase, tmpPath))
                AppLogger.i("Cleaned up partial chunked upload at ${construct(tmpBase, tmpPath)}")
            } catch (cleanupEx: Throwable) {
                AppLogger.w("Failed to clean up partial chunks: ${cleanupEx.message}")
            }
            jobFailed(e)
            false
        }
    }

    private suspend fun uploadMetadata(base: HttpUrl, path: List<String>, fileName: String) {
        AppLogger.i("Uploading metadata....")
        val metadata = getMetadata()

        if (mCancelled) throw Exception("Cancelled")

        mClient.put(
            construct(base, path, "$fileName.meta.json"),
            metadata.toByteArray(),
            "text/plain",
            null
        )

        val c2paManifest = getC2paManifest()
        if (c2paManifest != null) {
            if (mCancelled) throw Exception("Cancelled")

            AppLogger.d("Uploading C2PA manifest: ${c2paManifest.name}")
            mClient.put(
                construct(base, path, c2paManifest.name),
                c2paManifest,
                "application/json",
                false,
                null
            )
        }
    }
}
