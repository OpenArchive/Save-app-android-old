package net.opendasharchive.openarchive.features.main

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Point
import android.graphics.drawable.ColorDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.view.LayoutInflater
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.widget.LinearLayout
import android.widget.PopupWindow
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.RequiresApi
import androidx.core.content.ContextCompat
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.drawerlayout.widget.DrawerLayout
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.viewpager2.widget.ViewPager2
import com.google.android.material.snackbar.Snackbar
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import net.opendasharchive.openarchive.BuildConfig
import net.opendasharchive.openarchive.R
import net.opendasharchive.openarchive.core.logger.AppLogger
import net.opendasharchive.openarchive.databinding.ActivityMainBinding
import net.opendasharchive.openarchive.databinding.PopupFolderOptionsBinding
import net.opendasharchive.openarchive.db.Project
import net.opendasharchive.openarchive.db.Space
import net.opendasharchive.openarchive.extensions.getMeasurments
import net.opendasharchive.openarchive.features.core.BaseActivity
import net.opendasharchive.openarchive.features.core.UiImage
import net.opendasharchive.openarchive.features.core.asUiImage
import net.opendasharchive.openarchive.features.core.asUiText
import net.opendasharchive.openarchive.features.core.dialog.ButtonData
import net.opendasharchive.openarchive.features.core.dialog.DialogConfig
import net.opendasharchive.openarchive.features.core.dialog.DialogType
import net.opendasharchive.openarchive.features.core.dialog.showInfoDialog
import net.opendasharchive.openarchive.features.folders.AddFolderActivity
import net.opendasharchive.openarchive.features.main.adapters.FolderDrawerAdapter
import net.opendasharchive.openarchive.features.main.adapters.FolderDrawerAdapterListener
import net.opendasharchive.openarchive.features.main.adapters.SpaceDrawerAdapter
import net.opendasharchive.openarchive.features.main.adapters.SpaceDrawerAdapterListener
import net.opendasharchive.openarchive.features.media.AddMediaDialogFragment
import net.opendasharchive.openarchive.features.media.AddMediaType
import net.opendasharchive.openarchive.features.media.ContentPickerFragment
import net.opendasharchive.openarchive.features.media.MediaLaunchers
import net.opendasharchive.openarchive.features.media.Picker
import net.opendasharchive.openarchive.features.media.PreviewActivity
import net.opendasharchive.openarchive.features.onboarding.Onboarding23Activity
import net.opendasharchive.openarchive.features.onboarding.SpaceSetupActivity
import net.opendasharchive.openarchive.features.onboarding.StartDestination
import net.opendasharchive.openarchive.features.settings.passcode.AppConfig
import net.opendasharchive.openarchive.services.snowbird.SnowbirdBridge
import net.opendasharchive.openarchive.services.snowbird.service.SnowbirdService
import net.opendasharchive.openarchive.upload.UploadService
import net.opendasharchive.openarchive.util.Prefs
import net.opendasharchive.openarchive.util.ProofModeHelper
import net.opendasharchive.openarchive.util.Utility
import net.opendasharchive.openarchive.util.extensions.Position
import net.opendasharchive.openarchive.util.extensions.cloak
import net.opendasharchive.openarchive.util.extensions.hide
import net.opendasharchive.openarchive.util.extensions.scaleAndTintDrawable
import net.opendasharchive.openarchive.util.extensions.scaled
import net.opendasharchive.openarchive.util.extensions.show
import org.koin.android.ext.android.inject
import org.koin.androidx.viewmodel.ext.android.viewModel
import timber.log.Timber
import java.text.NumberFormat


class MainActivity : BaseActivity(), SpaceDrawerAdapterListener, FolderDrawerAdapterListener {

    private val appConfig by inject<AppConfig>()
    private val viewModel by viewModel<MainViewModel>()

    private var mMenuDelete: MenuItem? = null

    private var mSnackBar: Snackbar? = null

    private lateinit var binding: ActivityMainBinding
    private lateinit var mPagerAdapter: ProjectAdapter
    private lateinit var mSpaceAdapter: SpaceDrawerAdapter
    private lateinit var mFolderAdapter: FolderDrawerAdapter

    private lateinit var mediaLaunchers: MediaLaunchers

    private var mSelectedPageIndex: Int = 0
    private var mSelectedMediaPageIndex: Int = 0
    private var serverListOffset: Float = 0F
    private var serverListCurOffset: Float = 0F

    private var selectModeToggle: Boolean = false
    private var currentSelectionCount = 0

    private enum class FolderBarMode { INFO, SELECTION, EDIT }

    // Hold the current mode (default to INFO)
    private var folderBarMode = FolderBarMode.INFO

    // Current page getter/setter (updates bottom navbar accordingly)
    private var mCurrentPagerItem
        get() = binding.contentMain.pager.currentItem
        set(value) {
            binding.contentMain.pager.currentItem = value
            updateBottomNavbar(value)
        }

    // ----- Activity Result Launchers & Permission Launcher -----
    private val mNewFolderResultLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
            if (it.resultCode == RESULT_OK) {
                refreshProjects(it.data?.getLongExtra(AddFolderActivity.EXTRA_FOLDER_ID, -1))
            }
        }


    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted: Boolean ->
        if (isGranted) {
            Timber.d("Able to post notifications")
        } else {
            Timber.d("Need to explain")
        }
    }


    override fun onCreate(savedInstanceState: Bundle?) {
        ///enableEdgeToEdge()

        super.onCreate(savedInstanceState)
        installSplashScreen()

//        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
//            window.insetsController?.let {
//                it.hide(WindowInsets.Type.statusBars())
//                it.hide(WindowInsets.Type.systemBars())
//                it.systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
//            }
//        } else {
//            // For older versions, use the deprecated approach
//            window.setFlags(
//                WindowManager.LayoutParams.FLAG_FULLSCREEN,
//                WindowManager.LayoutParams.FLAG_FULLSCREEN
//            )
//        }

//        window.apply {
//            clearFlags(WindowManager.LayoutParams.FLAG_TRANSLUCENT_STATUS)
//            addFlags(WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS)
//            statusBarColor = ContextCompat.getColor(this@MainActivity, R.color.colorPrimary)
//            // optional. if you want the icons to be light.
//            decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR
//        }


        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        viewModel.log("MainActivity onCreate called")

        initMediaLaunchers()
        setupToolbarAndPager()
        setupNavigationDrawer()
        setupBottomNavBar()
        setupFolderBar()


        if (appConfig.isDwebEnabled) {
            checkNotificationPermissions()
            SnowbirdBridge.getInstance().initialize()
            startForegroundService(Intent(this, SnowbirdService::class.java))
            handleIntent(intent)
        }


        if (BuildConfig.DEBUG) {
            binding.contentMain.imgLogo.setOnLongClickListener {
                startActivity(Intent(this, HomeActivity::class.java))
                true
            }
        }
    }

    override fun onResume() {
        super.onResume()
        refreshSpace()
        mCurrentPagerItem = mSelectedPageIndex
        if (!Prefs.didCompleteOnboarding) {
            startActivity(Intent(this, Onboarding23Activity::class.java))
        }
        importSharedMedia(intent)
        if (serverListOffset == 0F) {
            val dims = binding.spaces.getMeasurments()
            serverListOffset = -dims.second.toFloat()
            serverListCurOffset = serverListOffset
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

    // ----- Initialization Methods -----
    private fun initMediaLaunchers() {
        mediaLaunchers = Picker.register(
            activity = this,
            root = binding.root,
            project = { getSelectedProject() },
            completed = { media ->
                refreshCurrentProject()
                if (media.isNotEmpty()) navigateToPreview()
            }
        )
    }

    private fun setupToolbarAndPager() {
        setSupportActionBar(binding.contentMain.toolbar)
        supportActionBar?.apply {
            setDisplayHomeAsUpEnabled(false)
            title = null
        }

        mPagerAdapter = ProjectAdapter(supportFragmentManager, lifecycle)
        binding.contentMain.pager.adapter = mPagerAdapter

        binding.contentMain.pager.registerOnPageChangeCallback(object :
            ViewPager2.OnPageChangeCallback() {
            override fun onPageSelected(position: Int) {
                mSelectedPageIndex = position
                if (position < mPagerAdapter.settingsIndex) {
                    mSelectedMediaPageIndex = position
                    val selectedProject = getSelectedProject()
                    mFolderAdapter.updateSelectedProject(selectedProject)
                }
                if (!appConfig.multipleProjectSelectionMode) {
                    getCurrentMediaFragment()?.cancelSelection()
                }
                updateBottomNavbar(position)
                refreshCurrentProject()
            }
        })
    }

    private fun setupNavigationDrawer() {
        // Drawer listener resets state on close
        binding.root.addDrawerListener(object : DrawerLayout.DrawerListener {
            override fun onDrawerClosed(drawerView: View) {
                collapseSpacesList()
            }

            override fun onDrawerOpened(drawerView: View) {
                //
            }

            override fun onDrawerSlide(drawerView: View, slideOffset: Float) {
                //
            }

            override fun onDrawerStateChanged(newState: Int) {
                //
            }
        })

        binding.navigationDrawerHeader.setOnClickListener { toggleSpacesList() }
        binding.dimOverlay.setOnClickListener { collapseSpacesList() }

        mSpaceAdapter = SpaceDrawerAdapter(this)
        binding.spaces.layoutManager = LinearLayoutManager(this)
        binding.spaces.adapter = mSpaceAdapter

        mFolderAdapter = FolderDrawerAdapter(this)
        binding.folders.layoutManager = LinearLayoutManager(this)
        binding.folders.adapter = mFolderAdapter

        binding.btnAddFolder.scaleAndTintDrawable(Position.Start, 0.75)
        binding.btnAddFolder.setOnClickListener {
            closeDrawer()
            navigateToAddFolder()
        }

        updateCurrentSpaceAtDrawer()
    }

    private fun setupBottomNavBar() {
        with(binding.contentMain.bottomNavBar) {
            onMyMediaClick = {
                mCurrentPagerItem = mSelectedMediaPageIndex
            }
            onAddClick = { addClicked(AddMediaType.GALLERY) }
            onSettingsClick = {
                mCurrentPagerItem = mPagerAdapter.settingsIndex
            }

            if (Picker.canPickFiles(this@MainActivity)) {
                setAddButtonLongClickEnabled()
                onAddLongClick = {
                    val addMediaBottomSheet =
                        ContentPickerFragment { actionType -> addClicked(actionType) }
                    addMediaBottomSheet.show(supportFragmentManager, ContentPickerFragment.TAG)
                }
                supportFragmentManager.setFragmentResultListener(
                    AddMediaDialogFragment.RESP_TAKE_PHOTO, this@MainActivity
                ) { _, _ -> addClicked(AddMediaType.CAMERA) }
                supportFragmentManager.setFragmentResultListener(
                    AddMediaDialogFragment.RESP_PHOTO_GALLERY, this@MainActivity
                ) { _, _ -> addClicked(AddMediaType.GALLERY) }
                supportFragmentManager.setFragmentResultListener(
                    AddMediaDialogFragment.RESP_FILES, this@MainActivity
                ) { _, _ -> addClicked(AddMediaType.FILES) }
            }
        }
    }

    private fun setupFolderBar() {
        // Tapping the edit button shows the folder options popup.
        binding.contentMain.btnEdit.setOnClickListener { btnView ->
            val location = IntArray(2)
            binding.contentMain.btnEdit.getLocationOnScreen(location)
            val point = Point(location[0], location[1])
            showFolderOptionsPopup(point)
        }
        // In selection mode, cancel selection reverts to INFO mode.
        binding.contentMain.btnCancelSelection.setOnClickListener {
            setFolderBarMode(FolderBarMode.INFO)
            getCurrentMediaFragment()?.cancelSelection()
        }
        // In the edit (rename) container, cancel button reverts to INFO mode.
        binding.contentMain.btnCancelEdit.setOnClickListener {
            setFolderBarMode(FolderBarMode.INFO)
        }
        // Listen for the "done" action to commit a rename.
        binding.contentMain.etFolderName.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == android.view.inputmethod.EditorInfo.IME_ACTION_DONE) {
                val newName = binding.contentMain.etFolderName.text.toString().trim()
                if (newName.isNotEmpty()) {
                    renameCurrentFolder(newName)
                    setFolderBarMode(FolderBarMode.INFO)
                } else {
                    Snackbar.make(binding.root, "Folder name cannot be empty", Snackbar.LENGTH_SHORT).show()
                }
                true
            } else false
        }

        binding.contentMain.btnRemoveSelected.setOnClickListener {
            showDeleteConfirmDialog()
        }
    }

    // Called when a new folder name is confirmed. (Adjust as needed to update your data store.)
    private fun renameCurrentFolder(newName: String) {
        val project = getSelectedProject()
        project?.let {
            it.description = newName
            it.save()
            refreshCurrentProject()
            Snackbar.make(binding.root, "Folder renamed", Snackbar.LENGTH_SHORT).show()
        }
    }

    private fun showFolderOptionsPopup(p: Point) {
        val layoutInflater = getSystemService(LAYOUT_INFLATER_SERVICE) as LayoutInflater
        val popupBinding = PopupFolderOptionsBinding.inflate(layoutInflater)
        val popup = PopupWindow(this).apply {
            contentView = popupBinding.root
            width = LinearLayout.LayoutParams.WRAP_CONTENT
            height = LinearLayout.LayoutParams.WRAP_CONTENT
            isFocusable = true
            setBackgroundDrawable(ColorDrawable())
            animationStyle = R.style.popup_window_animation
        }

        // Option to toggle selection mode
        popupBinding.menuFolderBarSelectMedia.setOnClickListener {
            popup.dismiss()
            setFolderBarMode(FolderBarMode.SELECTION)
        }
        // Rename folder
        popupBinding.menuFolderBarRenameFolder.setOnClickListener {
            popup.dismiss()
            setFolderBarMode(FolderBarMode.EDIT)
        }

        // Adjust popup position if needed
        val x = 200
        val y = 60
        popup.showAtLocation(binding.root, Gravity.NO_GRAVITY, p.x + x, p.y + y)
    }

    fun setSelectionMode(isSelecting: Boolean) {
        if (isSelecting) {
            setFolderBarMode(FolderBarMode.SELECTION)
        } else {
            setFolderBarMode(FolderBarMode.INFO)
        }
    }

    // New helper: update the cancel selection TextView to show the number of selected items.
    fun updateSelectedCount(count: Int) {
        // For example, if count > 0 display “Selected: X”; otherwise, revert to “Select Media”.
        //binding.contentMain.tvSelectedCount.text = if (count > 0) "Selected: $count" else "Select Media"
    }

    private fun showDeleteConfirmDialog() {
        dialogManager.showDialog(
            config = DialogConfig(
                type = DialogType.Warning,
                title = R.string.menu_delete.asUiText(),
                message = R.string.menu_delete_desc.asUiText(),
                icon = UiImage.DrawableResource(R.drawable.ic_trash),
                positiveButton = ButtonData(
                    text = R.string.lbl_ok.asUiText(),
                    action = {
                        getCurrentMediaFragment()?.deleteSelected()
                        updateSelectedCount(0)
                    }
                )
            )
        )
    }

    private fun getCurrentMediaFragment(): MainMediaFragment? {
        val currentItem = binding.contentMain.pager.currentItem
        return supportFragmentManager.findFragmentByTag("f$currentItem") as? MainMediaFragment
    }


    // ----- Drawer Helpers -----
    private fun toggleDrawerState() {
        if (binding.root.isDrawerOpen(binding.drawerContent)) {
            closeDrawer()
        } else {
            openDrawer()
        }
    }

    private fun openDrawer() {
        binding.root.openDrawer(binding.drawerContent)
    }

    private fun closeDrawer() {
        binding.root.closeDrawer(binding.drawerContent)
    }

    private fun toggleSpacesList() {
        if (serverListCurOffset == serverListOffset) {
            expandSpacesList()
        } else {
            collapseSpacesList()
        }
    }

    private fun expandSpacesList() {
        serverListCurOffset = 0f
        binding.spaceListMore.setImageDrawable(
            ContextCompat.getDrawable(this, R.drawable.ic_expand_less)
        )
        binding.spaces.visibility = View.VISIBLE
        binding.dimOverlay.visibility = View.VISIBLE
        binding.spaces.bringToFront()
        binding.dimOverlay.bringToFront()
        binding.spaces.animate()
            .translationY(0f).alpha(1f).setDuration(200)
            .withStartAction {
                binding.spacesHeaderSeparator.alpha = 0.3f
                binding.folders.alpha = 0.3f
                binding.btnAddFolder.alpha = 0.3f
            }
        binding.dimOverlay.animate().alpha(1f).setDuration(200)
        binding.navigationDrawerHeader.elevation = 8f
    }

    private fun collapseSpacesList() {
        serverListCurOffset = serverListOffset
        binding.spaceListMore.setImageDrawable(
            ContextCompat.getDrawable(this, R.drawable.ic_expand_more)
        )

        binding.spaces.animate()
            .translationY(serverListOffset).alpha(0f).setDuration(200)
            .withEndAction {
                binding.spaces.visibility = View.GONE
                binding.dimOverlay.visibility = View.GONE
                binding.spacesHeaderSeparator.alpha = 1f
                binding.folders.alpha = 1f
                binding.btnAddFolder.alpha = 1f
            }
        binding.dimOverlay.animate().alpha(0f).setDuration(200)
        binding.navigationDrawerHeader.elevation = 0f
    }

    private fun updateCurrentSpaceAtDrawer() {
        val drawable = Space.current?.getAvatar(applicationContext)
            ?.scaled(R.dimen.avatar_size, applicationContext)
        binding.spaceIcon.setImageDrawable(drawable)
        mSpaceAdapter.notifyDataSetChanged()
    }

    // ----- Refresh & Update Methods -----
    /**
     * Updates the visibility of the current folder container.
     * The container is only visible if:
     *   1. We are not on the settings page AND
     *   2. There is a current space with at least one project.
     */
    // Central function to update folder bar state
    private fun setFolderBarMode(mode: FolderBarMode) {
        folderBarMode = mode
        when (mode) {
            FolderBarMode.INFO -> {
                binding.contentMain.folderInfoContainer.visibility = View.VISIBLE
                binding.contentMain.folderSelectionContainer.visibility = View.GONE
                binding.contentMain.folderEditContainer.visibility = View.GONE
            }

            FolderBarMode.SELECTION -> {
                binding.contentMain.folderInfoContainer.visibility = View.GONE
                binding.contentMain.folderSelectionContainer.visibility = View.VISIBLE
                binding.contentMain.folderEditContainer.visibility = View.GONE
            }

            FolderBarMode.EDIT -> {
                binding.contentMain.folderInfoContainer.visibility = View.GONE
                binding.contentMain.folderSelectionContainer.visibility = View.GONE
                binding.contentMain.folderEditContainer.visibility = View.VISIBLE
                // Prepopulate the rename field with the current folder name
                binding.contentMain.etFolderName.text = getSelectedProject()?.description ?: ""
                binding.contentMain.etFolderName.requestFocus()
            }
        }
    }

    private fun updateCurrentFolderVisibility() {
        val projects = Space.current?.projects ?: emptyList()
        if (mCurrentPagerItem == mPagerAdapter.settingsIndex || projects.isEmpty()) {
            binding.contentMain.folderBar.hide()
            // Reset to default mode
            setFolderBarMode(FolderBarMode.INFO)
        } else {
            binding.contentMain.folderBar.show()
            setFolderBarMode(FolderBarMode.INFO)
        }

        mFolderAdapter.notifyDataSetChanged()
    }

    private fun updateBottomNavbar(position: Int) {
        binding.contentMain.bottomNavBar.updateSelectedItem(isSettings = position == mPagerAdapter.settingsIndex)
        updateCurrentFolderVisibility()
    }

    private fun refreshSpace() {
        val currentSpace = Space.current
        if (currentSpace != null) {
            binding.spaceNameLayout.visibility = View.VISIBLE
            binding.spaceName.text = currentSpace.friendlyName
            currentSpace.setAvatar(binding.contentMain.spaceIcon)
        } else {
            binding.spaceNameLayout.visibility = View.INVISIBLE
            binding.contentMain.folderBar.visibility = View.INVISIBLE
        }

        mSpaceAdapter.update(Space.getAll().asSequence().toList())
        updateCurrentSpaceAtDrawer()
        refreshProjects()
        updateCurrentFolderVisibility()
    }

    private fun refreshProjects(setProjectId: Long? = null) {
        val projects = Space.current?.projects ?: emptyList()
        mPagerAdapter.updateData(projects)
        binding.contentMain.pager.adapter = mPagerAdapter

        setProjectId?.let {
            mCurrentPagerItem = mPagerAdapter.getProjectIndexById(it, default = 0)
        }
        mFolderAdapter.update(projects)
    }

    private fun refreshCurrentProject() {
        val project = getSelectedProject()

        if (project != null) {
            binding.contentMain.pager.post {
                mPagerAdapter.notifyProjectChanged(project)
            }
            binding.contentMain.folderInfoContainer.visibility = View.VISIBLE
            project.space?.setAvatar(binding.contentMain.spaceIcon)
            binding.contentMain.folderName.text = project.description
        }
        updateCurrentFolderVisibility()
        refreshCurrentFolderCount()
    }

    private fun refreshCurrentFolderCount() {
        val project = getSelectedProject()

        if (project != null) {
            val count = project.collections.map { it.size }
                .reduceOrNull { acc, count -> acc + count } ?: 0
            binding.contentMain.itemCount.text = NumberFormat.getInstance().format(count)
            if (!selectModeToggle) {
                binding.contentMain.itemCount.show()
            }
        } else {
            binding.contentMain.itemCount.cloak()
        }
    }

    // ----- Navigation & Media Handling -----
    private fun navigateToAddServer() {
        closeDrawer()
        startActivity(Intent(this, SpaceSetupActivity::class.java))
    }

    private fun navigateToAddFolder() {
        val intent = Intent(this, SpaceSetupActivity::class.java)
        intent.putExtra("start_destination", StartDestination.ADD_FOLDER.name)
        mNewFolderResultLauncher.launch(intent)
//        mNewFolderResultLauncher.launch(Intent(this, AddFolderActivity::class.java))
    }

    private fun addClicked(mediaType: AddMediaType) {

        when {
            getSelectedProject() != null -> {
                if (Prefs.addMediaHint) {
                    when (mediaType) {
                        AddMediaType.CAMERA -> Picker.takePhoto(this, mediaLaunchers.cameraLauncher)
                        AddMediaType.GALLERY -> Picker.pickMedia(
                            this,
                            mediaLaunchers.imagePickerLauncher
                        )

                        AddMediaType.FILES -> Picker.pickFiles(mediaLaunchers.filePickerLauncher)
                    }
                } else {
                    dialogManager.showInfoDialog(
                        icon = R.drawable.perm_media_24px.asUiImage(),
                        title = R.string.press_and_hold_options_media_screen_title.asUiText(),
                        message = R.string.press_and_hold_options_media_screen_message.asUiText(),
                        onDone = {
                            Prefs.addMediaHint = true
                        }
                    )
                }
            }

            Space.current == null -> navigateToAddServer()
            else -> {
                navigateToAddFolder()
            }
        }
    }

    private fun importSharedMedia(imageIntent: Intent?) {
        if (imageIntent?.action != Intent.ACTION_SEND) return
        val uri =
            imageIntent.data ?: imageIntent.clipData?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.uri
        val path = uri?.path ?: return
        if (path.contains(packageName)) return

        mSnackBar?.show()
        lifecycleScope.launch(Dispatchers.IO) {
            val media = Picker.import(this@MainActivity, getSelectedProject(), uri)
            lifecycleScope.launch(Dispatchers.Main) {
                mSnackBar?.dismiss()
                intent = null
                if (media != null) {
                    navigateToPreview()
                }
            }
        }
    }

    private fun navigateToPreview() {
        val projectId = getSelectedProject()?.id ?: return
        PreviewActivity.start(this, projectId)
    }

    // ----- Permissions & Intent Handling -----
    private fun checkNotificationPermissions() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            when {
                ContextCompat.checkSelfPermission(
                    this,
                    Manifest.permission.POST_NOTIFICATIONS
                ) == PackageManager.PERMISSION_GRANTED -> Timber.d("We have notifications permissions")

                shouldShowRequestPermissionRationale(Manifest.permission.POST_NOTIFICATIONS) -> showNotificationPermissionRationale()
                else -> requestPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
    }

    private fun showNotificationPermissionRationale() {
        Utility.showMaterialWarning(this, "Accept!") { Timber.d("thing") }
    }

    private fun handleIntent(intent: Intent) {
        if (intent.action == Intent.ACTION_VIEW) {
            intent.data?.takeIf { it.scheme == "save-veilid" }?.let { processUri(it) }
        }
    }

    private fun processUri(uri: Uri) {
        val path = uri.path
        val queryParams = uri.queryParameterNames.associateWith { uri.getQueryParameter(it) }
        AppLogger.d("Path: $path, QueryParams: $queryParams")
    }

    // ----- Overrides -----
    override fun onCreateOptionsMenu(menu: Menu?): Boolean {
        menuInflater.inflate(R.menu.menu_main, menu)
        return super.onCreateOptionsMenu(menu)
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            R.id.menu_folders -> {
                toggleDrawerState()
                true
            }

            else -> super.onOptionsItemSelected(item)
        }
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

    // ----- Adapter Listeners -----
    override fun onProjectSelected(project: Project) {
        binding.root.closeDrawer(binding.drawerContent)
        mCurrentPagerItem = mPagerAdapter.projects.indexOf(project)
    }

    override fun getSelectedProject(): Project? {
        return mPagerAdapter.getProject(mCurrentPagerItem)
    }

    override fun onSpaceSelected(space: Space) {
        Space.current = space
        refreshSpace()
        updateCurrentSpaceAtDrawer()
        collapseSpacesList()
        binding.root.closeDrawer(binding.drawerContent)
    }

    override fun onAddNewSpace() {
        collapseSpacesList()
        closeDrawer()
        val intent = Intent(this, SpaceSetupActivity::class.java)
        startActivity(intent)
    }

    override fun getSelectedSpace(): Space? {
        val currentSpace = Space.current
        AppLogger.i("current space requested by adapter... = $currentSpace")
        return Space.current
    }


    fun updateAfterDelete(done: Boolean) {
        mMenuDelete?.isVisible = !done
        if (done) {
            refreshCurrentFolderCount()
        }
    }

}
