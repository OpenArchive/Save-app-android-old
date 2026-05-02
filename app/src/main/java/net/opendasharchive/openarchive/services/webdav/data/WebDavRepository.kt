package net.opendasharchive.openarchive.services.webdav.data

import android.content.Context
import android.util.Xml
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import net.opendasharchive.openarchive.core.domain.Vault
import net.opendasharchive.openarchive.core.repositories.SpaceRepository
import net.opendasharchive.openarchive.features.folders.Folder
import net.opendasharchive.openarchive.services.SaveClient
import net.opendasharchive.openarchive.util.DateUtils
import net.opendasharchive.openarchive.util.toKotlinLocalDateTime
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.xmlpull.v1.XmlPullParser
import java.io.IOException
import java.io.InputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class WebDavRepository(
    private val context: Context,
    private val spaceRepository: SpaceRepository
) {
    @Throws(IOException::class)
    suspend fun getFolders(vault: Vault): List<Folder> = withContext(Dispatchers.IO) {
        val auth = spaceRepository.getVaultAuth(vault.id)
            ?: throw IOException("Credentials unavailable for selected server")
        val root = vault.hostUrl?.encodedPath?.trimEnd('/')

        val client = SaveClient.get(
            context = context,
            user = auth.username,
            password = auth.secret,
            forceCloseConnection = true,
            allowHttp2 = false
        )

        val body = PROPFIND_BODY.toRequestBody("application/xml; charset=utf-8".toMediaTypeOrNull())
        val request = Request.Builder()
            .url(vault.host)
            .method("PROPFIND", body)
            .header("Depth", "1")
            .build()

        val response = client.newCall(request).execute()
        if (response.code != 207) {
            response.close()
            throw IOException("PROPFIND failed: ${response.code} ${response.message}")
        }

        val resources = response.use { r ->
            r.body?.byteStream()?.let { parsePropfind(it) } ?: emptyList()
        }

        resources.mapNotNull { resource ->
            if (resource.isDirectory && resource.href.trimEnd('/') != root) {
                Folder(
                    name = resource.name,
                    modified = resource.modified?.toKotlinLocalDateTime() ?: DateUtils.nowDateTime
                )
            } else null
        }
    }

    // --- PROPFIND XML parser ---

    private data class PropfindResource(
        val href: String,
        val name: String,
        val isDirectory: Boolean,
        val modified: Date?
    )

    private fun parsePropfind(stream: InputStream): List<PropfindResource> {
        val resources = mutableListOf<PropfindResource>()
        val parser = Xml.newPullParser().apply {
            setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, true)
            setInput(stream, null)
        }

        var href = ""
        var displayName = ""
        var isCollection = false
        var lastModified: Date? = null
        var captureTag = ""

        while (parser.eventType != XmlPullParser.END_DOCUMENT) {
            when (parser.eventType) {
                XmlPullParser.START_TAG -> when (parser.name) {
                    "response"       -> { href = ""; displayName = ""; isCollection = false; lastModified = null; captureTag = "" }
                    "collection"     -> isCollection = true
                    "href",
                    "displayname",
                    "getlastmodified" -> captureTag = parser.name
                }
                XmlPullParser.TEXT -> {
                    val text = parser.text?.trim().orEmpty()
                    if (text.isNotEmpty()) when (captureTag) {
                        "href"            -> href = text
                        "displayname"     -> displayName = text
                        "getlastmodified" -> lastModified = parseHttpDate(text)
                    }
                }
                XmlPullParser.END_TAG -> when (parser.name) {
                    "response" -> {
                        if (href.isNotBlank()) {
                            val name = displayName.ifBlank {
                                href.trimEnd('/').substringAfterLast('/')
                            }
                            resources.add(PropfindResource(href, name, isCollection, lastModified))
                        }
                        captureTag = ""
                    }
                    "href", "displayname", "getlastmodified" -> captureTag = ""
                }
            }
            parser.next()
        }
        return resources
    }

    private fun parseHttpDate(value: String): Date? = try {
        HTTP_DATE_FORMAT.parse(value)
    } catch (e: Exception) { null }

    companion object {
        private val HTTP_DATE_FORMAT = SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss z", Locale.US)

        private const val PROPFIND_BODY =
            """<?xml version="1.0" encoding="utf-8"?><propfind xmlns="DAV:"><prop><resourcetype/><displayname/><getlastmodified/></prop></propfind>"""
    }
}
