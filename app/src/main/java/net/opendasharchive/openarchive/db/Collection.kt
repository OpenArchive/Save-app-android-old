package net.opendasharchive.openarchive.db

import com.orm.SugarRecord
import java.util.*

data class Collection(
    var projectId: Long? = null,
    var uploadDate: Date? = null,
    var serverUrl: String? = null
) : SugarRecord() {

    companion object {

        fun getByProject(projectId: Long): List<Collection> {
            return find(Collection::class.java, "project_id = ?", arrayOf(projectId.toString()),
                null, "id DESC", null)
        }

        fun get(collectionId: Long?): Collection? {
            @Suppress("NAME_SHADOWING")
            val collectionId = collectionId ?: return null

            return findById(Collection::class.java, collectionId)
        }
    }

    val media: List<Media>
        get() = find(Media::class.java, "collection_id = ?", arrayOf(id.toString()), null, "status, id DESC", null)


    override fun delete(): Boolean {
        media.forEach {
            it.delete()
        }

        return super.delete()
    }
}