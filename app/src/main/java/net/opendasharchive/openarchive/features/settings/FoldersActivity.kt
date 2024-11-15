package net.opendasharchive.openarchive.features.settings

import android.content.Intent
import android.os.Bundle
import android.view.MenuItem
import androidx.recyclerview.widget.LinearLayoutManager
import net.opendasharchive.openarchive.FolderAdapter
import net.opendasharchive.openarchive.FolderAdapterListener
import net.opendasharchive.openarchive.R
import net.opendasharchive.openarchive.databinding.ActivityFoldersBinding
import net.opendasharchive.openarchive.db.Project
import net.opendasharchive.openarchive.db.Space
import net.opendasharchive.openarchive.features.core.BaseActivity
import net.opendasharchive.openarchive.util.extensions.hide
import net.opendasharchive.openarchive.util.extensions.toggle

class FoldersActivity: BaseActivity(), FolderAdapterListener {

    companion object {
        const val EXTRA_SHOW_ARCHIVED = "show_archived"
    }

    private lateinit var mBinding: ActivityFoldersBinding
    private lateinit var mAdapter: FolderAdapter

    private var mArchived = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        mArchived = intent.getBooleanExtra(EXTRA_SHOW_ARCHIVED, false)

        mBinding = ActivityFoldersBinding.inflate(layoutInflater)
        setContentView(mBinding.root)

        setSupportActionBar(mBinding.toolbar)
        supportActionBar?.title = getString(if (mArchived) R.string.archived_folders else R.string.folders)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        mAdapter = FolderAdapter(this)

        mBinding.rvProjects.layoutManager = LinearLayoutManager(this)
        mBinding.rvProjects.adapter = mAdapter

        mBinding.btViewArchived.toggle(!mArchived)
        mBinding.btViewArchived.setOnClickListener {
            val i = Intent(this, FoldersActivity::class.java)
            i.putExtra(EXTRA_SHOW_ARCHIVED, true)

            startActivity(i)
        }
    }

    override fun onResume() {
        super.onResume()

        val projects = if (mArchived) Space.current?.archivedProjects else Space.current?.projects

        mAdapter.update(projects ?: emptyList())
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (item.itemId == android.R.id.home) {
            finish()
            return true
        }

        return super.onOptionsItemSelected(item)
    }

    override fun projectClicked(project: Project) {
        val i = Intent(this, EditFolderActivity::class.java)
        i.putExtra(EditFolderActivity.EXTRA_CURRENT_PROJECT_ID, project.id)

        startActivity(i)
    }

    override fun getSelectedProject(): Project? {
        return null
    }
}