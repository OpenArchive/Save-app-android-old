package net.opendasharchive.openarchive.upload

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.DividerItemDecoration
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import net.opendasharchive.openarchive.R
import net.opendasharchive.openarchive.databinding.FragmentUploadManagerBinding
import net.opendasharchive.openarchive.db.Media
import net.opendasharchive.openarchive.db.MediaAdapter
import net.opendasharchive.openarchive.db.MediaViewHolder

open class UploadManagerFragment : Fragment() {

    companion object {
        private val STATUSES = listOf(Media.Status.Uploading, Media.Status.Queued, Media.Status.Error)
    }

    open var mediaAdapter: MediaAdapter? = null

    private lateinit var mBinding: FragmentUploadManagerBinding

    private lateinit var mItemTouchHelper: ItemTouchHelper

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        mBinding = FragmentUploadManagerBinding.inflate(inflater, container, false)

        mBinding.uploadList.layoutManager = LinearLayoutManager(activity)

        val decorator = DividerItemDecoration(mBinding.uploadList.context, DividerItemDecoration.VERTICAL)
        val divider = ContextCompat.getDrawable(mBinding.uploadList.context, R.drawable.divider)
        if (divider != null) decorator.setDrawable(divider)

        mBinding.uploadList.addItemDecoration(decorator)
        mBinding.uploadList.setHasFixedSize(true)

        mediaAdapter =
            MediaAdapter(
                activity,
                { MediaViewHolder.SmallRow(it) },
                Media.getByStatus(STATUSES, Media.ORDER_PRIORITY),
                mBinding.uploadList,
                listOf(Media.Status.Error)
            )

        mediaAdapter?.doImageFade = false
        mBinding.uploadList.adapter = mediaAdapter

        mItemTouchHelper = ItemTouchHelper(object : SwipeToDeleteCallback(context) {
            override fun isEditingAllowed(): Boolean {
                return mediaAdapter?.isEditMode ?: false
            }

            override fun onMove(
                recyclerView: RecyclerView,
                viewHolder: RecyclerView.ViewHolder,
                target: RecyclerView.ViewHolder
            ): Boolean {
                mediaAdapter?.onItemMove(
                    viewHolder.bindingAdapterPosition,
                    target.bindingAdapterPosition
                )

                return true
            }

            override fun onSwiped(viewHolder: RecyclerView.ViewHolder, direction: Int) {
                mediaAdapter?.deleteItem(viewHolder.bindingAdapterPosition)
            }
        })

        mItemTouchHelper.attachToRecyclerView(mBinding.uploadList)

        return mBinding.root
    }

    override fun onResume() {
        super.onResume()

        refresh()
    }

    open fun updateItem(mediaId: Long) {
        mediaAdapter?.updateItem(mediaId, -1)
    }

    open fun removeItem(mediaId: Long) {
        mediaAdapter?.removeItem(mediaId)
    }

    fun setEditMode(isEditMode: Boolean) {
        mediaAdapter?.isEditMode = isEditMode
    }

    open fun refresh() {
        mediaAdapter?.updateData(Media.getByStatus(STATUSES, Media.ORDER_PRIORITY))
    }

    open fun getUploadingCounter(): Int {
        return mediaAdapter?.media?.size ?: 0
    }
}