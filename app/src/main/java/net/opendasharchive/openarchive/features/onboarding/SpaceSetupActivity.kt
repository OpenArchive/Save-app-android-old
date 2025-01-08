package net.opendasharchive.openarchive.features.onboarding

import android.content.Intent
import android.os.Bundle
import androidx.fragment.app.Fragment
import net.opendasharchive.openarchive.R
import net.opendasharchive.openarchive.databinding.ActivitySpaceSetupBinding
import net.opendasharchive.openarchive.features.core.BaseActivity
import net.opendasharchive.openarchive.features.internetarchive.presentation.InternetArchiveFragment
import net.opendasharchive.openarchive.features.main.MainActivity
import net.opendasharchive.openarchive.features.settings.SpaceSetupFragment
import net.opendasharchive.openarchive.features.settings.SpaceSetupSuccessFragment
import net.opendasharchive.openarchive.services.gdrive.GDriveFragment
import net.opendasharchive.openarchive.services.webdav.WebDavFragment
import net.opendasharchive.openarchive.services.webdav.WebDavSetupLicenseFragment

interface ToolbarConfigurable {
    fun getToolbarTitle(): String
    fun getToolbarSubtitle(): String? = null
    fun shouldShowBackButton(): Boolean = true
}

abstract class BaseFragment : Fragment(), ToolbarConfigurable {
    override fun onResume() {
        super.onResume()
        (activity as? SpaceSetupActivity)?.updateToolbarFromFragment(this)
    }
}

class SpaceSetupActivity : BaseActivity() {

    companion object {
        const val FRAGMENT_TAG = "ssa_fragment"
    }

    private lateinit var mBinding: ActivitySpaceSetupBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        mBinding = ActivitySpaceSetupBinding.inflate(layoutInflater)
        setContentView(mBinding.root)

        setupToolbar(
            title = "Servers",
            showBackButton = true
        )

        initSpaceSetupFragmentBindings()
        initWebDavFragmentBindings()
        initWebDavCreativeLicenseBindings()
        initSpaceSetupSuccessFragmentBindings()
        initInternetArchiveFragmentBindings()
        initGDriveFragmentBindings()
    }

    fun updateToolbarFromFragment(fragment: Fragment) {
        if (fragment is ToolbarConfigurable) {
            val title = fragment.getToolbarTitle()
            val subtitle = fragment.getToolbarSubtitle()
            val showBackButton = fragment.shouldShowBackButton()
            setupToolbar(title = title, showBackButton = showBackButton)
            supportActionBar?.subtitle = subtitle
        } else {
            // Default toolbar configuration if fragment doesn't implement interface
            setupToolbar(title = "Servers", showBackButton = true)
            supportActionBar?.subtitle = null
        }
    }

    private fun initSpaceSetupSuccessFragmentBindings() {
        supportFragmentManager.setFragmentResultListener(SpaceSetupSuccessFragment.RESP_DONE, this) { _, _ ->
            finishAffinity()
            startActivity(Intent(this, MainActivity::class.java))
        }
    }

    /**
     * Init NextCloud credentials
     *
     */
    private fun initWebDavFragmentBindings() {
        supportFragmentManager.setFragmentResultListener(WebDavFragment.RESP_SAVED, this) { key, bundle ->
            val spaceId = bundle.getLong(WebDavFragment.ARG_SPACE_ID)
            supportFragmentManager
                .beginTransaction()
                .setCustomAnimations(R.anim.slide_in_right, R.anim.slide_out_left)
                .replace(
                    mBinding.spaceSetupFragment.id,
                    WebDavSetupLicenseFragment.newInstance(spaceId = spaceId, isEditing = false),
                    FRAGMENT_TAG,
                )
                .commit()
        }


        supportFragmentManager.setFragmentResultListener(WebDavFragment.RESP_CANCEL, this) { _, _ ->
            supportFragmentManager
                .beginTransaction()
                .setCustomAnimations(R.anim.slide_in_left, R.anim.slide_out_right)
                .replace(mBinding.spaceSetupFragment.id, SpaceSetupFragment(), FRAGMENT_TAG)
                .commit()
        }
    }

    /**
     * Init select Creative Commons Licensing
     *
     */
    private fun initWebDavCreativeLicenseBindings() {
        supportFragmentManager.setFragmentResultListener(WebDavSetupLicenseFragment.RESP_SAVED, this) { _, _ ->
            val message = getString(R.string.you_have_successfully_connected_to_a_private_server)
            supportFragmentManager
                .beginTransaction()
                .setCustomAnimations(R.anim.slide_in_right, R.anim.slide_out_left)
                .replace(
                    mBinding.spaceSetupFragment.id,
                    SpaceSetupSuccessFragment.newInstance(message),
                    FRAGMENT_TAG,
                )
                .commit()
        }

        supportFragmentManager.setFragmentResultListener(WebDavSetupLicenseFragment.RESP_CANCEL, this) { _, _ ->
            supportFragmentManager
                .beginTransaction()
                .setCustomAnimations(R.anim.slide_in_left, R.anim.slide_out_right)
                .replace(mBinding.spaceSetupFragment.id, WebDavFragment(), FRAGMENT_TAG)
                .commit()
        }
    }

    private fun initSpaceSetupFragmentBindings() {
        supportFragmentManager.setFragmentResultListener(SpaceSetupFragment.RESULT_REQUEST_KEY, this) { _, bundle ->
            when (bundle.getString(SpaceSetupFragment.RESULT_BUNDLE_KEY)) {
                SpaceSetupFragment.RESULT_VAL_INTERNET_ARCHIVE -> {
                    supportFragmentManager
                        .beginTransaction()
                        .setCustomAnimations(R.anim.slide_in_right, R.anim.slide_out_left)
                        .replace(
                            mBinding.spaceSetupFragment.id,
                            InternetArchiveFragment.newInstance(),
                            FRAGMENT_TAG
                        )
                        .commit()
                }

                SpaceSetupFragment.RESULT_VAL_WEBDAV -> {
                    supportFragmentManager
                        .beginTransaction()
                        .setCustomAnimations(R.anim.slide_in_right, R.anim.slide_out_left)
                        .replace(
                            mBinding.spaceSetupFragment.id,
                            WebDavFragment.newInstance(),
                            FRAGMENT_TAG
                        )
                        .commit()
                }

                SpaceSetupFragment.RESULT_VAL_GDRIVE -> {
                    supportFragmentManager
                        .beginTransaction()
                        .setCustomAnimations(R.anim.slide_in_right, R.anim.slide_out_left)
                        .replace(mBinding.spaceSetupFragment.id, GDriveFragment(), FRAGMENT_TAG)
                        .commit()
                }

                SpaceSetupFragment.RESULT_VAL_RAVEN -> {
                    supportFragmentManager
                        .beginTransaction()
                        .setCustomAnimations(R.anim.slide_in_right, R.anim.slide_out_left)
                        .replace(mBinding.spaceSetupFragment.id, GDriveFragment(), FRAGMENT_TAG)
                        .commit()
                }
            }
        }
    }

    private fun initInternetArchiveFragmentBindings() {
        supportFragmentManager.setFragmentResultListener(InternetArchiveFragment.RESP_SAVED, this) { _, _ ->
            supportFragmentManager
                .beginTransaction()
                .setCustomAnimations(R.anim.slide_in_right, R.anim.slide_out_left)
                .replace(
                    mBinding.spaceSetupFragment.id,
                    SpaceSetupSuccessFragment.newInstance(getString(R.string.you_have_successfully_connected_to_the_internet_archive)),
                    FRAGMENT_TAG
                )
                .commit()
        }

        supportFragmentManager.setFragmentResultListener(
            InternetArchiveFragment.RESP_CANCEL,
            this
        ) { _, _ ->
            supportFragmentManager
                .beginTransaction()
                .setCustomAnimations(R.anim.slide_in_left, R.anim.slide_out_right)
                .replace(mBinding.spaceSetupFragment.id, SpaceSetupFragment(), FRAGMENT_TAG)
                .commit()
        }
    }

    private fun initGDriveFragmentBindings() {
        supportFragmentManager.setFragmentResultListener(
            GDriveFragment.RESP_CANCEL,
            this
        ) { _, _ ->
            supportFragmentManager
                .beginTransaction()
                .setCustomAnimations(R.anim.slide_in_left, R.anim.slide_out_right)
                .replace(mBinding.spaceSetupFragment.id, SpaceSetupFragment(), FRAGMENT_TAG)
                .commit()
        }

        supportFragmentManager.setFragmentResultListener(
            GDriveFragment.RESP_AUTHENTICATED,
            this
        ) { _, _ ->
            supportFragmentManager
                .beginTransaction()
                .setCustomAnimations(R.anim.slide_in_right, R.anim.slide_out_left)
                .replace(
                    mBinding.spaceSetupFragment.id,
                    SpaceSetupSuccessFragment.newInstance(getString(R.string.you_have_successfully_connected_to_gdrive)),
                    FRAGMENT_TAG
                )
                .commit()
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        supportFragmentManager.findFragmentByTag(FRAGMENT_TAG)?.let {
            onActivityResult(requestCode, resultCode, data)
        }
    }
}
