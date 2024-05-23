package net.opendasharchive.openarchive.provider

import android.content.ContentProvider
import android.content.ContentUris
import android.content.ContentValues
import android.database.Cursor
import android.net.Uri

class TorStatusContentProvider : ContentProvider() {
    companion object {
        private const val AUTHORITY = "org.opendasharchive.safe.provider.tor"
        const val TABLE_NAME = "status"
        const val CONTENT_URI = "content://$AUTHORITY/$TABLE_NAME"
    }

    private lateinit var database: TorStatusDatabase

    override fun onCreate(): Boolean {
        database = TorStatusDatabase.getInstance(context!!)
        return true
    }

    override fun query(
        uri: Uri,
        projection: Array<String>?,
        selection: String?,
        selectionArgs: Array<String>?,
        sortOrder: String?
    ): Cursor {
        val db = database.readableDatabase
        val cursor = db.query(
            TABLE_NAME,
            projection,
            selection,
            selectionArgs,
            null,
            null,
            sortOrder
        )
        cursor.setNotificationUri(context?.contentResolver, uri)
        return cursor
    }

    override fun insert(uri: Uri, values: ContentValues?): Uri? {
        val db = database.writableDatabase
        val rowId = db.insert(TABLE_NAME, null, values)
        if (rowId != -1L) {
            context?.contentResolver?.notifyChange(uri, null)
            return ContentUris.withAppendedId(uri, rowId)
        }
        return null
    }

    override fun update(
        uri: Uri,
        values: ContentValues?,
        selection: String?,
        selectionArgs: Array<String>?
    ): Int {
        val db = database.writableDatabase
        val rowsUpdated = db.update(TABLE_NAME, values, selection, selectionArgs)
        if (rowsUpdated > 0) {
            context?.contentResolver?.notifyChange(uri, null)
        }
        return rowsUpdated
    }

    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<String>?): Int {
        val db = database.writableDatabase
        val rowsDeleted = db.delete(TABLE_NAME, selection, selectionArgs)
        if (rowsDeleted > 0) {
            context?.contentResolver?.notifyChange(uri, null)
        }
        return rowsDeleted
    }

    override fun getType(uri: Uri): String? {
        // Implement this method to return the MIME type of the data at the given URI
        // For example: return "vnd.android.cursor.item/my_table" for a single item
        // or "vnd.android.cursor.dir/my_table" for multiple items
        return null
    }

}