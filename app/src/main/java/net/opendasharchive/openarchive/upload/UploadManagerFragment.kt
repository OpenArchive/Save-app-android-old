package net.opendasharchive.openarchive.upload

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.DividerItemDecoration
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import net.opendasharchive.openarchive.R
import net.opendasharchive.openarchive.databinding.FragmentUploadManagerBinding
import net.opendasharchive.openarchive.db.Media
import net.opendasharchive.openarchive.db.UploadMediaAdapter
import net.opendasharchive.openarchive.features.main.MainActivity

open class UploadManagerFragment : SKBottomSheetDialogFragment() {

    companion object {
        const val TAG = "ModalBottomSheet-UploadManagerFragment"
        private val STATUSES =
            listOf(Media.Status.Uploading, Media.Status.Queued, Media.Status.Error)
    }

    open var uploadMediaAdapter: UploadMediaAdapter? = null

    private lateinit var binding: FragmentUploadManagerBinding

    private lateinit var mItemTouchHelper: ItemTouchHelper

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        binding = FragmentUploadManagerBinding.inflate(inflater, container, false)

        binding.uploadList.layoutManager = LinearLayoutManager(activity)

        val decorator = DividerItemDecoration(binding.uploadList.context, DividerItemDecoration.VERTICAL)
        val divider = ContextCompat.getDrawable(binding.uploadList.context, R.drawable.divider)
        if (divider != null) decorator.setDrawable(divider)

        binding.uploadList.addItemDecoration(decorator)
        binding.uploadList.setHasFixedSize(true)

        uploadMediaAdapter = UploadMediaAdapter(
            activity = activity,
            mediaItems = Media.getByStatus(STATUSES, Media.ORDER_PRIORITY),
            recyclerView = binding.uploadList,
        )

        uploadMediaAdapter?.doImageFade = false
        binding.uploadList.adapter = uploadMediaAdapter

        mItemTouchHelper = ItemTouchHelper(object : SwipeToDeleteCallback(context) {
            override fun isEditingAllowed(): Boolean {
                return uploadMediaAdapter?.isEditMode == true
            }

            override fun onMove(
                recyclerView: RecyclerView,
                viewHolder: RecyclerView.ViewHolder,
                target: RecyclerView.ViewHolder
            ): Boolean {
                uploadMediaAdapter?.onItemMove(
                    viewHolder.bindingAdapterPosition,
                    target.bindingAdapterPosition
                )

                return true
            }

            override fun onSwiped(viewHolder: RecyclerView.ViewHolder, direction: Int) {
                uploadMediaAdapter?.deleteItem(viewHolder.bindingAdapterPosition)
            }
        })

        mItemTouchHelper.attachToRecyclerView(binding.uploadList)

        binding.root.findViewById<View>(R.id.done_button)?.setOnClickListener {
            dismiss() // Close the bottom sheet when clicked
        }

        return binding.root
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    override fun onDestroy() {
        super.onDestroy()

        // Notify MainActivity that this fragment is dismissed
        (activity as? MainActivity)?.uploadManagerFragment = null
    }

    open fun updateItem(mediaId: Long) {
        uploadMediaAdapter?.updateItem(mediaId, -1)
    }

    open fun removeItem(mediaId: Long) {
        uploadMediaAdapter?.removeItem(mediaId)
    }

    fun setEditMode(isEditMode: Boolean) {
        uploadMediaAdapter?.isEditMode = isEditMode
        uploadMediaAdapter?.notifyDataSetChanged()
    }

    open fun refresh() {
        uploadMediaAdapter?.updateData(Media.getByStatus(STATUSES, Media.ORDER_PRIORITY))
    }

    open fun getUploadingCounter(): Int {
        return uploadMediaAdapter?.media?.size ?: 0
    }
}