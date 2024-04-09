package net.opendasharchive.openarchive

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import net.opendasharchive.openarchive.databinding.RvSimpleRowBinding
import net.opendasharchive.openarchive.db.Space
import net.opendasharchive.openarchive.util.extensions.scaled
import java.lang.ref.WeakReference

interface SpaceAdapterListener {

    fun spaceClicked(space: Space)

    fun addSpaceClicked()

    fun getSelectedSpace(): Space?
}

class SpaceAdapter(listener: SpaceAdapterListener?) : ListAdapter<Space, SpaceAdapter.ViewHolder>(DIFF_CALLBACK), SpaceAdapterListener {

    class ViewHolder(private val binding: RvSimpleRowBinding) : RecyclerView.ViewHolder(binding.root) {

        fun bind(listener: WeakReference<SpaceAdapterListener>?, space: Space?) {
            val context = binding.rvTitle.context

            if (listener?.get()?.getSelectedSpace()?.id == space?.id) {
                val icon = Space.current?.getAvatar(context)?.scaled(32, context)
                val color = ContextCompat.getColor(binding.rvIcon.context, R.color.colorPrimary)
                icon?.setTint(color)
                binding.rvIcon.setImageDrawable(icon)
            }
            else {
                val icon = space?.getAvatar(context)?.scaled(32, context)
                val color = ContextCompat.getColor(binding.rvIcon.context, R.color.colorOnBackground)
                icon?.setTint(color)
                binding.rvIcon.setImageDrawable(icon)
            }

            if (space?.type == ADD_SPACE_ID) {
                binding.rvTitle.text = context.getText(R.string.add_another_account)

                val icon = ContextCompat.getDrawable(binding.rvIcon.context, R.drawable.ic_add)
                binding.rvIcon.setImageDrawable(icon)

                binding.root.setOnClickListener {
                    listener?.get()?.addSpaceClicked()
                }

                return
            } else {
                binding.rvTitle.text = space?.friendlyName
            }

            binding.rvTitle.setTextColor(
                FolderAdapter.getColor(
                    binding.rvTitle.context,
                    listener?.get()?.getSelectedSpace()?.id == space?.id
                )
            )

            if (space != null) {
                binding.root.setOnClickListener {
                    listener?.get()?.spaceClicked(space)
                }
            }
            else {
                binding.root.setOnClickListener(null)
            }
        }
    }

    companion object {
        private val DIFF_CALLBACK = object : DiffUtil.ItemCallback<Space>() {
            override fun areItemsTheSame(oldItem: Space, newItem: Space): Boolean {
                return oldItem.id == newItem.id
            }

            override fun areContentsTheSame(oldItem: Space, newItem: Space): Boolean {
                return oldItem.friendlyName == newItem.friendlyName
            }
        }

        private const val ADD_SPACE_ID = -1
    }

    private val mListener = WeakReference(listener)

    private var mLastSelected: Space? = null

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        return ViewHolder(RvSimpleRowBinding.inflate(LayoutInflater.from(parent.context),
            parent, false))
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val space = getItem(position)

        holder.bind(WeakReference(this), space)
    }

    fun update(spaces: List<Space>) {
        notifyItemChanged(getIndex(mLastSelected))

        @Suppress("NAME_SHADOWING")
        val spaces = spaces.toMutableList()
        spaces.add(Space(ADD_SPACE_ID))

        submitList(spaces)
    }

    override fun spaceClicked(space: Space) {
        notifyItemChanged(getIndex(getSelectedSpace()))
        notifyItemChanged(getIndex(space))

        mListener.get()?.spaceClicked(space)
    }

    override fun addSpaceClicked() {
        mListener.get()?.addSpaceClicked()
    }

    override fun getSelectedSpace(): Space? {
        mLastSelected = mListener.get()?.getSelectedSpace()

        return mLastSelected
    }

    private fun getIndex(space: Space?): Int {
        return if (space == null) {
            -1
        }
        else {
            currentList.indexOf(space)
        }
    }
}