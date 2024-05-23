package net.opendasharchive.openarchive.provider

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

class TorStatusDatabase private constructor(context: Context) : SQLiteOpenHelper(context, DATABASE_NAME, null, DATABASE_VERSION) {

    companion object {
        private const val DATABASE_NAME = "status.db"
        private const val DATABASE_VERSION = 1

        @Volatile
        private var instance: TorStatusDatabase? = null

        fun getInstance(context: Context): TorStatusDatabase {
            return instance ?: synchronized(this) {
                instance ?: TorStatusDatabase(context.applicationContext).also { instance = it }
            }
        }
    }

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE ${TorStatusContentProvider.TABLE_NAME} (id primary_key)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
    }
}