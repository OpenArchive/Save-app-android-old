package net.opendasharchive.openarchive.features.main

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.GridLayoutManager
import net.opendasharchive.openarchive.R
import net.opendasharchive.openarchive.databinding.FragmentMainMediaBinding
import net.opendasharchive.openarchive.databinding.ViewSectionBinding
import net.opendasharchive.openarchive.db.Collection
import net.opendasharchive.openarchive.db.Media
import net.opendasharchive.openarchive.db.MediaAdapter
import net.opendasharchive.openarchive.db.MediaViewHolder
import net.opendasharchive.openarchive.upload.BroadcastManager
import net.opendasharchive.openarchive.util.AlertHelper
import net.opendasharchive.openarchive.util.extensions.toggle
import kotlin.collections.set

class MainMediaFragment : Fragment() {

    companion object {
        private const val COLUMN_COUNT = 4
        private const val ARG_PROJECT_ID = "project_id"

        fun newInstance(projectId: Long): MainMediaFragment {
            val args = Bundle()
            args.putLong(ARG_PROJECT_ID, projectId)

            val fragment = MainMediaFragment()
            fragment.arguments = args

            return fragment
        }
    }

    private var mAdapters = HashMap<Long, MediaAdapter>()
    private var mSection = HashMap<Long, SectionViewHolder>()
    private var mProjectId = -1L
    private var mCollections = ArrayList<Collection>()

    private lateinit var mBinding: FragmentMainMediaBinding

    private val mMessageReceiver: BroadcastReceiver = object : BroadcastReceiver() {

        override fun onReceive(context: Context, intent: Intent) {
            val action = BroadcastManager.getAction(intent)
            val mediaId = action?.mediaId ?: return

            if (mediaId < 0) return

            when (action) {
                BroadcastManager.Action.Change -> {
                    updateItem(mediaId)
                }

                BroadcastManager.Action.Delete -> {
                    refresh()
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setHasOptionsMenu(true)
    }

    override fun onStart() {
        super.onStart()
        BroadcastManager.register(requireContext(), mMessageReceiver)
    }

    override fun onStop() {
        super.onStop()
        BroadcastManager.unregister(requireContext(), mMessageReceiver)
    }

    @Deprecated("Deprecated in Java")
    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            R.id.menu_delete -> {
                AlertHelper.show(
                    requireContext(), R.string.confirm_remove_media, null, buttons = listOf(
                        AlertHelper.positiveButton(R.string.remove) { _, _ ->
                            deleteSelected()
                        },
                        AlertHelper.negativeButton()
                    )
                )
                true
            }

            else -> super.onOptionsItemSelected(item)
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        mProjectId = arguments?.getLong(ARG_PROJECT_ID, -1) ?: -1

        mBinding = FragmentMainMediaBinding.inflate(inflater, container, false)

        refresh()

        return mBinding.root
    }

    fun updateItem(mediaId: Long) {
        for (adapter in mAdapters.values) {
            if (adapter.updateItem(mediaId)) break
        }
    }

    fun refresh() {
        mCollections = ArrayList(Collection.getByProject(mProjectId))

        // Remove all sections, which' collections don't exist anymore.
        val toDelete = mAdapters.keys.filter { id ->
            mCollections.firstOrNull { it.id == id } == null
        }.toMutableList()

        mCollections.forEach { collection ->
            val media = collection.media

            // Also remove all empty collections.
            if (media.isEmpty()) {
                toDelete.add(collection.id)
                return@forEach
            }

            val adapter = mAdapters[collection.id]
            val holder = mSection[collection.id]

            if (adapter != null) {
                adapter.updateData(media)
                holder?.setHeader(collection, media)
            } else if (media.isNotEmpty()) {
                val view = createMediaList(collection, media)

                mBinding.mediaContainer.addView(view, 0)
            }
        }

        // DO NOT delete the collection here, this could lead to a race condition
        // while adding images.
        deleteCollections(toDelete, false)

        if (::mBinding.isInitialized) {
            mBinding.addMediaHint.toggle(mCollections.isEmpty())
        }
    }

    fun deleteSelected() {
        val toDelete = ArrayList<Long>()

        mCollections.forEach { collection ->
            if (mAdapters[collection.id]?.deleteSelected() == true) {
                val media = collection.media

                if (media.isEmpty()) {
                    toDelete.add(collection.id)
                } else {
                    mSection[collection.id]?.setHeader(collection, media)
                }
            }
        }

        deleteCollections(toDelete, true)
    }

    private fun createMediaList(collection: Collection, media: List<Media>): View {
        val holder = SectionViewHolder(ViewSectionBinding.inflate(layoutInflater))

        holder.recyclerView.setHasFixedSize(true)
        holder.recyclerView.layoutManager = GridLayoutManager(activity, COLUMN_COUNT)

        holder.setHeader(collection, media)

        val mediaAdapter = MediaAdapter(
            requireActivity(),
            { MediaViewHolder.Box(it) },
            media,
            holder.recyclerView
        ) {
            (activity as? MainActivity)?.updateAfterDelete(mAdapters.values.firstOrNull { it.selecting } == null)
        }

        holder.recyclerView.adapter = mediaAdapter
        mAdapters[collection.id] = mediaAdapter
        mSection[collection.id] = holder

        return holder.root
    }

    private fun deleteCollections(collectionIds: List<Long>, cleanup: Boolean) {
        collectionIds.forEach { collectionId ->
            mAdapters.remove(collectionId)

            val holder = mSection.remove(collectionId)
            (holder?.root?.parent as? ViewGroup)?.removeView(holder.root)

            val idx = mCollections.indexOfFirst { it.id == collectionId }

            if (idx > -1 && idx < mCollections.size) {
                val collection = mCollections.removeAt(idx)

                if (cleanup) collection.delete()
            }
        }
    }
}
