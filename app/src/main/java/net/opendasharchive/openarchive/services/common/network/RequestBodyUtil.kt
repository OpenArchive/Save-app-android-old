package net.opendasharchive.openarchive.services.common.network

import android.content.ContentResolver
import android.net.Uri
import net.opendasharchive.openarchive.core.logger.AppLogger
import okhttp3.MediaType
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.RequestBody
import okhttp3.internal.closeQuietly
import okio.BufferedSink
import okio.Source
import okio.source
import timber.log.Timber
import java.io.*

fun createListener(cancellable: () -> Boolean, onProgress: (Long) -> Unit = {}, onComplete: () -> Unit = {}) = object : RequestListener {
    override fun transferred(bytes: Long) = onProgress(bytes)

    override fun continueUpload() =  cancellable()

    override fun transferComplete() = onComplete()
}

/**
 * Created by n8fr8 on 12/29/17.
 */
object RequestBodyUtil {

    fun create(mediaType: MediaType?, inputStream: InputStream, contentLength: Long? = null,
               listener: RequestListener?): RequestBody {
        return object : RequestBody() {
            override fun contentType() = mediaType

            override fun contentLength(): Long {
                return try {
                    contentLength ?: inputStream.available().toLong()
                } catch (e: IOException) {
                    AppLogger.i("BodyRequestUtil couldn't get contentLength, returning 0 instead", e)
                    0
                }
            }

            @Throws(IOException::class)
            override fun writeTo(sink: BufferedSink) {
                var source: Source? = null
                try {
                    source = inputStream.source()
                    sink.writeAll(source, listener)
                } finally {
                    source!!.closeQuietly()
                }
            }
        }
    }

    private const val SEGMENT_SIZE = 262144  // 256 KB — 4× fewer loop iterations than 64 KB
    fun create(
        cr: ContentResolver,
        uri: Uri,
        contentLength: Long,
        mediaType: MediaType?,
        listener: RequestListener?
    ): RequestBody {
        return object : RequestBody() {
            var inputStream: InputStream? = null
            var mListener: RequestListener? = null
            private fun init() {
                try {
                    inputStream = if (uri.scheme != null && uri.scheme == "file") FileInputStream(
                        uri.path?.let { File(it) }
                    ) else cr.openInputStream(uri)
                    mListener = listener
                } catch (e: FileNotFoundException) {
                    AppLogger.e("BodyRequest init failed", e)
                }
            }

            override fun contentType() = mediaType

            override fun contentLength() = contentLength

            @Synchronized
            @Throws(IOException::class)
            override fun writeTo(sink: BufferedSink) {
                init()
                val stream = inputStream ?: throw IOException("Failed to open file: ${uri.path}")
                var source: Source? = null
                try {
                    source = stream.source()
                    sink.writeAll(source, listener)
                } finally {
                    source?.closeQuietly()
                }
            }
        }
    }

    fun BufferedSink.writeAll(source: Source, listener: RequestListener?) {
        if (listener == null) {
            // writeAll() only emits complete 8KB Okio segments to the underlying sink;
            // a small payload (< 8KB) sits entirely in the Okio buffer.  OkHttp's
            // Exchange$RequestBodySink.close() checks transferred == contentLength, and
            // if the flush inside RealBufferedSink.close() fails (server sent RST before
            // we could drain the buffer), transferred stays 0 → ProtocolException.
            // Explicitly flush here so all bytes reach Exchange$RequestBodySink.write()
            // (and thus transferred is updated) before the outer close() check runs.
            writeAll(source)
            flush()
        } else {
            var total: Long = 0
            var read: Long
            while (source.read(buffer, SEGMENT_SIZE.toLong()).also {
                    read = it
                } != -1L && listener.continueUpload()) {
                total += read
                listener.transferred(total)
                flush()
            }
            listener.transferComplete()
        }
    }

    fun create(fileSource: File, mediaType: MediaType?, listener: RequestListener?): RequestBody {
        return object : RequestBody() {
            var inputStream: InputStream? = null
            private fun init() {
                try {
                    inputStream = FileInputStream(fileSource)
                } catch (e: FileNotFoundException) {
                    AppLogger.e("RequestBody init failed", e)
                }
            }

            override fun contentType() = mediaType

            override fun contentLength() = fileSource.length()

            @Synchronized
            @Throws(IOException::class)
            override fun writeTo(sink: BufferedSink) {
                init()
                val stream = inputStream ?: throw IOException("File not found: ${fileSource.path}")
                val source = stream.source()
                try {
                    sink.writeAll(source, listener)
                } finally {
                    source.closeQuietly()
                }
            }
        }
    }
}
