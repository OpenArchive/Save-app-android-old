package net.opendasharchive.openarchive.features.main

import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import android.view.View
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.RequiresApi
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.viewpager2.widget.ViewPager2
import com.google.android.material.snackbar.Snackbar
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import net.opendasharchive.openarchive.FolderAdapter
import net.opendasharchive.openarchive.FolderAdapterListener
import net.opendasharchive.openarchive.R
import net.opendasharchive.openarchive.SpaceAdapter
import net.opendasharchive.openarchive.SpaceAdapterListener
import net.opendasharchive.openarchive.databinding.ActivityMainBinding
import net.opendasharchive.openarchive.db.Project
import net.opendasharchive.openarchive.db.Space
import net.opendasharchive.openarchive.extensions.getMeasurments
import net.opendasharchive.openarchive.features.core.BaseActivity
import net.opendasharchive.openarchive.features.folders.AddFolderActivity
import net.opendasharchive.openarchive.features.media.AddMediaDialogFragment
import net.opendasharchive.openarchive.features.media.AddMediaType
import net.opendasharchive.openarchive.features.media.MediaLaunchers
import net.opendasharchive.openarchive.features.media.Picker
import net.opendasharchive.openarchive.features.media.PreviewActivity
import net.opendasharchive.openarchive.features.onboarding.Onboarding23Activity
import net.opendasharchive.openarchive.features.onboarding.SpaceSetupActivity
import net.opendasharchive.openarchive.upload.UploadService
import net.opendasharchive.openarchive.util.AlertHelper
import net.opendasharchive.openarchive.util.Prefs
import net.opendasharchive.openarchive.util.ProofModeHelper
import net.opendasharchive.openarchive.util.extensions.Position
import net.opendasharchive.openarchive.util.extensions.cloak
import net.opendasharchive.openarchive.util.extensions.hide
import net.opendasharchive.openarchive.util.extensions.scaleAndTintDrawable
import net.opendasharchive.openarchive.util.extensions.scaled
import net.opendasharchive.openarchive.util.extensions.setDrawable
import net.opendasharchive.openarchive.util.extensions.show
import java.text.NumberFormat
import kotlin.math.roundToInt


class MainActivity : BaseActivity(), FolderAdapterListener, SpaceAdapterListener {

    private var mMenuDelete: MenuItem? = null

    private var mSnackBar: Snackbar? = null

    private lateinit var mBinding: ActivityMainBinding
    private lateinit var mPagerAdapter: ProjectAdapter
    private lateinit var mSpaceAdapter: SpaceAdapter
    private lateinit var mFolderAdapter: FolderAdapter

    private lateinit var mediaLaunchers: MediaLaunchers

    private var mLastItem: Int = 0
    private var mLastMediaItem: Int = 0
    private var serverListOffset: Float = 0F
    private var serverListCurOffset: Float = 0F

    private var mCurrentItem
        get() = mBinding.pager.currentItem
        set(value) {
            mBinding.pager.currentItem = value
            updateBottomNavbar(value)
        }

    private val mNewFolderResultLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
            if (it.resultCode == RESULT_OK) {
                refreshProjects(it.data?.getLongExtra(AddFolderActivity.EXTRA_FOLDER_ID, -1))
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        mBinding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(mBinding.root)

        mediaLaunchers = Picker.register(
            activity = this,
            root = mBinding.root,
            project = { getSelectedProject() },
            completed = { media ->
                refreshCurrentProject()

                if (media.isNotEmpty()) {
                    preview()
                }
            })

        setSupportActionBar(mBinding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(false)
        supportActionBar?.title = null

        mPagerAdapter = ProjectAdapter(supportFragmentManager, lifecycle)
        mBinding.pager.adapter = mPagerAdapter

        mBinding.pager.registerOnPageChangeCallback(object : ViewPager2.OnPageChangeCallback() {
            override fun onPageScrolled(
                position: Int, positionOffset: Float,
                positionOffsetPixels: Int
            ) {
            }

            override fun onPageSelected(position: Int) {
                mLastItem = position
                if (position < mPagerAdapter.settingsIndex) {
                    mLastMediaItem = position
                }

                updateBottomNavbar(position)

                refreshCurrentProject()
            }

            override fun onPageScrollStateChanged(state: Int) {}
        })

        mBinding.spaceName.setOnClickListener {
            var newAlpha = 0F

            if (serverListCurOffset != serverListOffset) {
                serverListCurOffset = serverListOffset
                mBinding.spaceName.setDrawable(R.drawable.ic_expand_more, Position.End, 0.75)
            } else {
                newAlpha = 1F
                serverListCurOffset = 0F
                mBinding.spaceName.setDrawable(R.drawable.ic_expand_less, Position.End, 0.75)
            }

            mBinding.spaces.visibility = View.VISIBLE
            mBinding.currentSpaceName.visibility = View.VISIBLE
            mBinding.newFolder.visibility = View.VISIBLE
            mBinding.folders.visibility = View.VISIBLE

            mBinding.spaces.animate().translationY(serverListCurOffset).alpha(newAlpha)
                .withEndAction {
                    run {
                        if (newAlpha == 0F) {
                            mBinding.spaces.hide(false)
                        }
                    }
                }
            mBinding.currentSpaceName.animate().alpha(1 - newAlpha)
            mBinding.newFolder.animate().alpha(1 - newAlpha)
            mBinding.folders.animate().alpha(1 - newAlpha)
        }

        updateCurrentSpaceAtDrawer()

        mSpaceAdapter = SpaceAdapter(this)
        mBinding.spaces.layoutManager = LinearLayoutManager(this)
        mBinding.spaces.adapter = mSpaceAdapter

        mFolderAdapter = FolderAdapter(this)
        mBinding.folders.layoutManager = LinearLayoutManager(this)
        mBinding.folders.adapter = mFolderAdapter

        mBinding.newFolder.scaleAndTintDrawable(Position.Start, 0.75)
        mBinding.newFolder.setOnClickListener {
            addFolder()
        }

        mBinding.myMediaButton.setOnClickListener {
            mCurrentItem = mLastMediaItem
        }
        mBinding.myMediaLabel.setOnClickListener {
            // perform click + play ripple animation
            mBinding.myMediaButton.isPressed = true
            mBinding.myMediaButton.isPressed = false
            mBinding.myMediaButton.performClick()
        }

        mBinding.addButton.setOnClickListener {
            addClicked(AddMediaType.GALLERY)
        }

        mBinding.settingsButton.setOnClickListener {
            mCurrentItem = mPagerAdapter.settingsIndex
        }
        mBinding.settingsLabel.setOnClickListener {
            // perform click + play ripple animation
            mBinding.settingsButton.isPressed = true
            mBinding.settingsButton.isPressed = false
            mBinding.settingsButton.performClick()
        }

        if (Picker.canPickFiles(this)) {
            mBinding.addButton.setOnLongClickListener {
                val addMediaDialogFragment = AddMediaDialogFragment()
                addMediaDialogFragment.show(supportFragmentManager, addMediaDialogFragment.tag)

                true
            }

            supportFragmentManager.setFragmentResultListener(
                AddMediaDialogFragment.RESP_TAKE_PHOTO,
                this
            ) { _, _ ->
                addClicked(AddMediaType.CAMERA)
            }
            supportFragmentManager.setFragmentResultListener(
                AddMediaDialogFragment.RESP_PHOTO_GALLERY,
                this
            ) { _, _ ->
                addClicked(AddMediaType.GALLERY)
            }
            supportFragmentManager.setFragmentResultListener(
                AddMediaDialogFragment.RESP_FILES,
                this
            ) { _, _ ->
                addClicked(AddMediaType.FILES)
            }
        }
    }

    @RequiresApi(Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
    override fun onStart() {
        super.onStart()

        ProofModeHelper.init(this) {
            // Check for any queued uploads and restart, only after ProofMode is correctly initialized.
            UploadService.startUploadService(this)
        }
    }

    override fun onResume() {
        super.onResume()

        refreshSpace()

        mCurrentItem = mLastItem

        if (!Prefs.didCompleteOnboarding) {
            startActivity(Intent(this, Onboarding23Activity::class.java))
        }

        importSharedMedia(intent)

        if (serverListOffset == 0F) {
            val dims = mBinding.spaces.getMeasurments()
            serverListOffset = -dims.second.toFloat()
            serverListCurOffset = serverListOffset
            mBinding.spaces.visibility = View.GONE
            mBinding.spaces.animate().translationY(serverListOffset)
            mBinding.spaceName.setDrawable(R.drawable.ic_expand_more, Position.End, 0.75)
        }
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.menu_main, menu)
        mMenuDelete = menu.findItem(R.id.menu_delete)

        return super.onCreateOptionsMenu(menu)
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            R.id.menu_folders -> {

                if (mBinding.root.isDrawerOpen(mBinding.folderBar)) {
                    mBinding.root.closeDrawer(mBinding.folderBar)
                } else {
                    mBinding.root.openDrawer(mBinding.folderBar)
                }
                true
            }

            else -> super.onOptionsItemSelected(item)
        }
    }

    private fun updateCurrentSpaceAtDrawer() {
        mBinding.currentSpaceName.text = Space.current?.friendlyName
        mBinding.currentSpaceName.setDrawable(
            Space.current?.getAvatar(applicationContext)?.scaled(32, applicationContext),
            Position.Start, tint = true
        )
        mBinding.currentSpaceName.compoundDrawablePadding =
            applicationContext.resources.getDimension(R.dimen.padding_small).roundToInt()
    }

    fun updateAfterDelete(done: Boolean) {
        mMenuDelete?.isVisible = !done

        if (done) refreshCurrentFolderCount()
    }

    private fun addFolder() {
        mNewFolderResultLauncher.launch(Intent(this, AddFolderActivity::class.java))

        mBinding.root.closeDrawer(mBinding.folderBar)
    }

    private fun refreshSpace() {
//        val currentSpace = Space.current

//        if (currentSpace != null) {
//            mBinding.space.setDrawable(
//                currentSpace.getAvatar(this@MainActivity)
//                    ?.scaled(32, this@MainActivity), Position.Start, tint = false
//            )
        mBinding.spaceName.text = getString(R.string.servers) // currentSpace.friendlyName
//        } else {
//            mBinding.space.setDrawable(R.drawable.avatar_default, Position.Start, tint = false)
//            mBinding.space.text = getString(R.string.app_name)
//        }

        mSpaceAdapter.update(Space.getAll().asSequence().toList())

        refreshProjects()
    }

    private fun refreshProjects(setProjectId: Long? = null) {
        val projects = Space.current?.projects ?: emptyList()

        mPagerAdapter.updateData(projects)

        mBinding.pager.adapter = mPagerAdapter

        setProjectId?.let {
            mCurrentItem = mPagerAdapter.getProjectIndexById(it, default = 0)
        }

        mFolderAdapter.update(projects)

        refreshCurrentProject()
    }

    private fun refreshCurrentProject() {
        val project = getSelectedProject()

        if (project != null) {
            mBinding.pager.post {
                mPagerAdapter.notifyProjectChanged(project)
            }

            project.space?.setAvatar(mBinding.currentFolderIcon)
            mBinding.currentFolderIcon.show()

            mBinding.currentFolderName.text = project.description
            mBinding.currentFolderName.show()
        } else {
            mBinding.currentFolderIcon.cloak()
            mBinding.currentFolderName.cloak()
        }

        refreshCurrentFolderCount()
    }

    private fun refreshCurrentFolderCount() {
        val project = getSelectedProject()

        if (project != null) {
            mBinding.currentFolderCount.text = NumberFormat.getInstance().format(
                project.collections.map { it.size }
                    .reduceOrNull { acc, count -> acc + count } ?: 0)
            mBinding.currentFolderCount.show()

//            mBinding.uploadEditButton.toggle(project.isUploading)
        } else {
            mBinding.currentFolderCount.cloak()
//            mBinding.uploadEditButton.hide()
        }
    }

    private fun importSharedMedia(data: Intent?) {
        if (data?.action != Intent.ACTION_SEND) return

        val uri = data.data ?: if ((data.clipData?.itemCount
                ?: 0) > 0
        ) data.clipData?.getItemAt(0)?.uri else null
        val path = uri?.path ?: return

        if (path.contains(packageName)) return

        mSnackBar?.show()

        lifecycleScope.launch(Dispatchers.IO) {
            val media = Picker.import(this@MainActivity, getSelectedProject(), uri)

            lifecycleScope.launch(Dispatchers.Main) {
                mSnackBar?.dismiss()
                intent = null

                if (media != null) {
                    preview()
                }
            }
        }
    }

    private fun preview() {
        val projectId = getSelectedProject()?.id ?: return

        PreviewActivity.start(this, projectId)
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)

        when (requestCode) {
            2 -> Picker.pickMedia(this, mediaLaunchers.imagePickerLauncher)
        }
    }

//    private fun showAlertIcon() {
//        mBinding.alertIcon.show()
//        TooltipCompat.setTooltipText(
//            mBinding.alertIcon,
//            getString(R.string.unsecured_internet_connection)
//        )
//    }

    override fun projectClicked(project: Project) {
        mCurrentItem = mPagerAdapter.projects.indexOf(project)

//        mBinding.root.closeDrawer(mBinding.folderBar)

//        mBinding.spacesCard.disableAnimation {
//            mBinding.spacesCard.hide()
//        }

        // make sure that even when navigating to settings and picking a folder there
        // the dataset will get update correctly
        mFolderAdapter.notifyDataSetChanged()
    }

    override fun getSelectedProject(): Project? {
        return mPagerAdapter.getProject(mCurrentItem)
    }

    override fun spaceClicked(space: Space) {
        Space.current = space

        refreshSpace()

        mBinding.root.closeDrawer(mBinding.folderBar)

//        mBinding.spacesCard.disableAnimation {
//            mBinding.spacesCard.hide()
//        }

        updateCurrentSpaceAtDrawer()
    }

    override fun addSpaceClicked() {
        mBinding.root.closeDrawer(mBinding.folderBar)

        startActivity(Intent(this, SpaceSetupActivity::class.java))
    }

    override fun getSelectedSpace(): Space? {
        return Space.current
    }

    private fun addClicked(mediaType: AddMediaType) {

        if (getSelectedProject() != null) {
            when(mediaType) {
                AddMediaType.CAMERA -> Picker.takePhoto(this@MainActivity, mediaLaunchers.cameraLauncher)
                AddMediaType.GALLERY -> Picker.pickMedia(this, mediaLaunchers.imagePickerLauncher)
                AddMediaType.FILES -> Picker.pickFiles(mediaLaunchers.filePickerLauncher)
            }

        } else {

            if (!Prefs.addFolderHintShown) {
                AlertHelper.show(
                    this,
                    R.string.before_adding_media_create_a_new_folder_first,
                    R.string.to_get_started_please_create_a_folder,
                    R.drawable.ic_folder,
                    buttons = listOf(
                        AlertHelper.positiveButton(R.string.add_a_folder) { _, _ ->
                            Prefs.addFolderHintShown = true

                            addFolder()
                        },
                        AlertHelper.negativeButton(R.string.lbl_Cancel)
                    )
                )
            } else {
                addFolder()
            }
        }
    }

    private fun updateBottomNavbar(position: Int) {
        if (position == mPagerAdapter.settingsIndex) {
            mBinding.myMediaButton.setIconResource(R.drawable.outline_perm_media_24)
            mBinding.settingsButton.setIconResource(R.drawable.ic_settings_filled)
        } else {
            mBinding.myMediaButton.setIconResource(R.drawable.perm_media_24px)
            mBinding.settingsButton.setIconResource(R.drawable.ic_settings)
        }
    }
}
