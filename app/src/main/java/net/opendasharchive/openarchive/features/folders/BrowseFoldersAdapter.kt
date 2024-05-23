package net.opendasharchive.openarchive.features.folders

import android.graphics.drawable.Drawable
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView
import net.opendasharchive.openarchive.R
import net.opendasharchive.openarchive.databinding.FolderRowBinding
import net.opendasharchive.openarchive.util.extensions.clone
import net.opendasharchive.openarchive.util.extensions.scaled
import net.opendasharchive.openarchive.util.extensions.tint
import java.text.SimpleDateFormat

class BrowseFoldersAdapter(
    private val folders: List<BrowseFoldersViewModel.Folder> = emptyList(),
    private val onClick: (folder: BrowseFoldersViewModel.Folder) -> Unit
) : RecyclerView.Adapter<BrowseFoldersAdapter.FolderViewHolder>() {

    companion object {
        private var sOriginalColor = 0
        private var sHighlightColor = 0
        private var sIcon: Drawable? = null
        private val formatter = SimpleDateFormat.getDateTimeInstance(SimpleDateFormat.LONG, SimpleDateFormat.MEDIUM)
    }

    private var mSelected = -1

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): FolderViewHolder {
        val binding = FolderRowBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        val context = binding.root.context

        if (sOriginalColor == 0) sOriginalColor = binding.name.currentTextColor
        if (sHighlightColor == 0) sHighlightColor = ContextCompat.getColor(context, R.color.colorPrimary)
        if (sIcon == null) sIcon = ContextCompat.getDrawable(context, R.drawable.ic_folder)?.scaled(0.75, context)

        return FolderViewHolder(binding, onClick)
    }

    override fun onBindViewHolder(holder: FolderViewHolder, position: Int) {
        holder.onBindView(position, mSelected == position)
    }

    override fun getItemCount(): Int {
        return folders.size
    }

    inner class FolderViewHolder(
        private val binding: FolderRowBinding,
        private val onClick: (folder: BrowseFoldersViewModel.Folder) -> Unit
    ) : RecyclerView.ViewHolder(binding.root)
    {
        fun onBindView(position: Int, selected: Boolean) {
            val color = if (selected) sHighlightColor else sOriginalColor

            binding.icon.setImageDrawable(sIcon?.clone()?.tint(color))
            binding.name.setTextColor(color)

            binding.name.text = folders[position].name
            binding.timestamp.text = formatter.format(folders[position].modified)

            binding.root.setOnClickListener {
                mSelected = position
                this@BrowseFoldersAdapter.notifyDataSetChanged()

                onClick.invoke(folders[position])
            }
        }
    }
}