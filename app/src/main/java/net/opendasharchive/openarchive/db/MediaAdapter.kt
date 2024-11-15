package net.opendasharchive.openarchive.db

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Intent
import android.view.*
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.snackbar.Snackbar
import com.google.api.client.googleapis.media.MediaHttpUploader.UploadState
import net.opendasharchive.openarchive.CleanInsightsManager
import net.opendasharchive.openarchive.R
import net.opendasharchive.openarchive.core.logger.AppLogger
import net.opendasharchive.openarchive.features.media.PreviewActivity
import net.opendasharchive.openarchive.upload.BroadcastManager
import net.opendasharchive.openarchive.upload.UploadManagerActivity
import net.opendasharchive.openarchive.upload.UploadService
import net.opendasharchive.openarchive.util.AlertHelper
import net.opendasharchive.openarchive.util.Prefs
import net.opendasharchive.openarchive.util.extensions.toggle
import java.lang.ref.WeakReference

class MediaAdapter(
    activity: Activity?,
    private val generator: (parent: ViewGroup) -> MediaViewHolder,
    data: List<Media>,
    private val recyclerView: RecyclerView,
    private val supportedStatuses: List<Media.Status> = listOf(
        Media.Status.Local,
        Media.Status.Uploading,
        Media.Status.Error
    ),
    private val checkSelecting: (() -> Unit)? = null
) : RecyclerView.Adapter<MediaViewHolder>() {

    var media: ArrayList<Media> = ArrayList(data)
        private set

    var doImageFade = true

    var isEditMode = false

    var selecting = false
        private set

    private var mActivity = WeakReference(activity)

    init {
        setHasStableIds(true)
    }


    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): MediaViewHolder {
        val mvh = generator(parent)

        mvh.itemView.setOnClickListener { v ->
            if (selecting && checkSelecting != null) {
                selectView(v)
            } else {
                val pos = recyclerView.getChildLayoutPosition(v)

                when (media[pos].sStatus) {
                    Media.Status.Local -> {
                        if (supportedStatuses.contains(Media.Status.Local)) {
                            mActivity.get()?.let {
                                PreviewActivity.start(it, media[pos].projectId)
                            }
                        }
                    }

                    Media.Status.Queued, Media.Status.Uploading -> {
                        if (supportedStatuses.contains(Media.Status.Uploading)) {
                            mActivity.get()?.let {
                                it.startActivity(
                                    Intent(it, UploadManagerActivity::class.java)
                                )
                            }
                        }
                    }

                    Media.Status.Error -> {
                        if (supportedStatuses.contains(Media.Status.Error)) {
                            //CleanInsightsManager.measureEvent("backend", "upload-error", media[pos].space?.friendlyName)
                            mActivity.get()?.let {
                                AlertHelper.show(
                                    it, it.getString(R.string.upload_unsuccessful_description),
                                    R.string.upload_unsuccessful, R.drawable.ic_error, listOf(
                                        AlertHelper.positiveButton(R.string.retry) { _, _ ->

                                            media[pos].apply {
                                                sStatus = Media.Status.Queued
                                                statusMessage = ""
                                                save()

                                                BroadcastManager.postChange(it, collectionId, id)
                                            }

                                            UploadService.startUploadService(it)
                                        },
                                        AlertHelper.negativeButton(R.string.remove) { _, _ ->
                                            deleteItem(pos)
                                        },
                                        AlertHelper.neutralButton()
                                    )
                                )
                            }
                        }
                    }

                    else -> {
                        if (checkSelecting != null) {
                            selectView(v)
                        }
                    }
                }
            }
        }

        if (checkSelecting != null) {
            mvh.itemView.setOnLongClickListener { v ->
                selectView(v)

                true
            }
        }

        mvh.flagIndicator?.setOnClickListener {
            showFirstTimeFlag()

            // Toggle flag
            val mediaId = mvh.itemView.tag as? Long ?: return@setOnClickListener

            val item = media.firstOrNull { it.id == mediaId } ?: return@setOnClickListener
            item.flag = !item.flag
            item.save()

            notifyItemChanged(media.indexOf(item))
        }

        return mvh
    }

    override fun getItemCount(): Int = media.size

    override fun getItemId(position: Int): Long {
        return media[position].id
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onBindViewHolder(holder: MediaViewHolder, position: Int) {
        AppLogger.i("onBindViewHolder called for position $position")
        holder.bind(media[position], selecting, doImageFade)
        holder.handle?.toggle(isEditMode)
    }

    override fun onBindViewHolder(holder: MediaViewHolder, position: Int, payloads: MutableList<Any>) {
        if (payloads.isNotEmpty()) {
            val payload = payloads[0]
            when (payload) {
                "progress" -> {
                    holder.updateProgress(media[position].uploadPercentage ?: 0)
                }
                "full" -> {
                    holder.bind(media[position], selecting, doImageFade)
                    holder.handle?.toggle(isEditMode)
                }
            }
        } else {
            holder.bind(media[position], selecting, doImageFade)
            holder.handle?.toggle(isEditMode)
        }
    }

    fun updateItem(mediaId: Long, progress: Int, isUploaded: Boolean = false): Boolean {
        val idx = media.indexOfFirst { it.id == mediaId }
        AppLogger.i("updateItem: mediaId=$mediaId idx=$idx")
        if (idx < 0) return false

        val item = media[idx]

        if (isUploaded) {
            item.status = Media.Status.Uploaded.id
            AppLogger.i("Media item $mediaId uploaded, notifying item changed at position $idx")
            notifyItemChanged(idx, "full")
        } else if (progress >= 0) {
            item.uploadPercentage = progress
            item.status = Media.Status.Uploading.id
            notifyItemChanged(idx, "progress")
        }

        return true
    }

    fun removeItem(mediaId: Long): Boolean {
        val idx = media.indexOfFirst { it.id == mediaId }
        if (idx < 0) return false

        media.removeAt(idx)

        notifyItemRemoved(idx)

        checkSelecting?.invoke()

        return true
    }

    fun updateData(newMediaList: List<Media>) {
        val diffCallback = MediaDiffCallback(this.media, newMediaList)
        val diffResult = DiffUtil.calculateDiff(diffCallback)

        this.media.clear()
        this.media.addAll(newMediaList)

        diffResult.dispatchUpdatesTo(this)
    }

    private fun showFirstTimeFlag() {
        if (Prefs.flagHintShown) return
        val activity = mActivity.get() ?: return

        AlertHelper.show(activity, R.string.popup_flag_desc, R.string.popup_flag_title)

        Prefs.flagHintShown = true
    }

    private fun selectView(view: View) {
        val mediaId = view.tag as? Long ?: return

        val m = media.firstOrNull { it.id == mediaId } ?: return
        m.selected = !m.selected
        m.save()

        notifyItemChanged(media.indexOf(m))

        selecting = media.firstOrNull { it.selected } != null
        checkSelecting?.invoke()
    }

    fun onItemMove(oldPos: Int, newPos: Int) {
        if (!isEditMode) return

        val mediaToMov = media.removeAt(oldPos)
        media.add(newPos, mediaToMov)

        var priority = media.size

        for (item in media) {
            item.priority = priority--
            item.save()
        }

        notifyItemMoved(oldPos, newPos)
    }

    fun deleteItem(pos: Int) {
        if (pos < 0 || pos >= media.size) return

        val item = media[pos]
        var undone = false

        val snackbar =
            Snackbar.make(recyclerView, R.string.confirm_remove_media, Snackbar.LENGTH_LONG)
        snackbar.setAction(R.string.undo) { _ ->
            undone = true
            media.add(pos, item)

            notifyItemInserted(pos)
        }

        snackbar.addCallback(object : Snackbar.Callback() {
            override fun onDismissed(transientBottomBar: Snackbar?, event: Int) {
                if (!undone) {
                    val collection = item.collection

                    // Delete collection along with the item, if the collection
                    // would become empty.
                    if ((collection?.size ?: 0) < 2) {
                        collection?.delete()
                    } else {
                        item.delete()
                    }

                    BroadcastManager.postDelete(recyclerView.context, item.id)
                }

                super.onDismissed(transientBottomBar, event)
            }
        })

        snackbar.show()

        removeItem(item.id)

        mActivity.get()?.let {
            BroadcastManager.postDelete(it, item.id)
        }
    }


    fun deleteSelected(): Boolean {
        var hasDeleted = false

        for (item in media.filter { it.selected }) {
            val idx = media.indexOf(item)
            media.remove(item)

            notifyItemRemoved(idx)

            item.delete()

            hasDeleted = true
        }

        selecting = false

        checkSelecting?.invoke()

        return hasDeleted
    }
}

class MediaDiffCallback(
    private val oldList: List<Media>,
    private val newList: List<Media>
) : DiffUtil.Callback() {

    override fun getOldListSize() = oldList.size

    override fun getNewListSize() = newList.size

    override fun areItemsTheSame(oldItemPosition: Int, newItemPosition: Int): Boolean {
        return oldList[oldItemPosition].id == newList[newItemPosition].id
    }

    override fun areContentsTheSame(oldItemPosition: Int, newItemPosition: Int): Boolean {
        // Compare only the fields that affect the UI

        val oldItem = oldList[oldItemPosition]
        val newItem = newList[newItemPosition]

        return oldItem.status == newItem.status &&
                oldItem.uploadPercentage == newItem.uploadPercentage &&
                oldItem.selected == newItem.selected &&
                oldItem.title == newItem.title
    }
}