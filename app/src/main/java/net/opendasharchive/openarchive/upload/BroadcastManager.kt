package net.opendasharchive.openarchive.upload

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import androidx.localbroadcastmanager.content.LocalBroadcastManager

object BroadcastManager {

    enum class Action(val id: String, var collectionId: Long = -1, var mediaId: Long = -1, var progress: Long = -1) {
        Change("media_change_intent"),
        Delete("media_delete_intent")
    }

    private const val MEDIA_ID = "media_id"
    private const val COLLECTION_ID = "collection_id"
    private const val MEDIA_PROGRESS = "media_progress"

    fun postChange(context: Context, collectionId: Long, mediaId: Long) {
        val i = Intent(Action.Change.id)
        i.putExtra(MEDIA_ID, mediaId)
        i.putExtra(COLLECTION_ID, collectionId)

        LocalBroadcastManager.getInstance(context).sendBroadcast(i)
    }

    fun postProgress(context: Context, collectionId: Long, mediaId: Long, progress: Long) {
        val i = Intent(Action.Change.id)
        i.putExtra(MEDIA_ID, mediaId)
        i.putExtra(COLLECTION_ID, collectionId)
        i.putExtra(MEDIA_PROGRESS, progress)

        LocalBroadcastManager.getInstance(context).sendBroadcast(i)
    }

    fun postDelete(context: Context, mediaId: Long) {
        val i = Intent(Action.Delete.id)
        i.putExtra(MEDIA_ID, mediaId)

        LocalBroadcastManager.getInstance(context).sendBroadcast(i)
    }

    fun getAction(intent: Intent): Action? {
        val action = Action.entries.firstOrNull { it.id == intent.action }
        action?.mediaId = intent.getLongExtra(MEDIA_ID, -1)
        action?.collectionId = intent.getLongExtra(COLLECTION_ID, -1)
        action?.progress = intent.getLongExtra(MEDIA_PROGRESS, -1)

        return action
    }

    fun register(context: Context, receiver: BroadcastReceiver) {
        LocalBroadcastManager.getInstance(context)
            .registerReceiver(receiver, IntentFilter(Action.Change.id))

        LocalBroadcastManager.getInstance(context)
            .registerReceiver(receiver, IntentFilter(Action.Delete.id))
    }

    fun unregister(context: Context, receiver: BroadcastReceiver) {
        LocalBroadcastManager.getInstance(context).unregisterReceiver(receiver)
    }
}
