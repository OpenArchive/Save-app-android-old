package net.opendasharchive.openarchive.features.onboarding

import android.content.Intent
import android.os.Bundle
import net.opendasharchive.openarchive.R
import net.opendasharchive.openarchive.databinding.ActivitySpaceSetupBinding
import net.opendasharchive.openarchive.features.core.BaseActivity
import net.opendasharchive.openarchive.features.main.MainActivity
import net.opendasharchive.openarchive.features.settings.SpaceSetupFragment
import net.opendasharchive.openarchive.features.settings.SpaceSetupSuccessFragment
import net.opendasharchive.openarchive.services.dropbox.DropboxFragment
import net.opendasharchive.openarchive.services.gdrive.GDriveFragment
import net.opendasharchive.openarchive.services.internetarchive.InternetArchiveFragment
import net.opendasharchive.openarchive.services.internetarchive.Util
import net.opendasharchive.openarchive.services.webdav.WebDavFragment

class SpaceSetupActivity : BaseActivity() {

    companion object {
        const val FRAGMENT_TAG = "ssa_fragment"
    }

    private lateinit var mBinding: ActivitySpaceSetupBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        mBinding = ActivitySpaceSetupBinding.inflate(layoutInflater)
        setContentView(mBinding.root)

        initSpaceSetupFragmentBindings()
        initWebDavFragmentBindings()
        initSpaceSetupSuccessFragmentBindings()
        initDropboxFragmentBindings()
        initInternetArchiveFragmentBindings()
        initGDriveFragmentBindings()
    }

    private fun initSpaceSetupSuccessFragmentBindings() {
        supportFragmentManager.setFragmentResultListener(
            SpaceSetupSuccessFragment.RESP_DONE,
            this
        ) { _, _ ->
            finishAffinity()
            startActivity(Intent(this, MainActivity::class.java))
        }
    }

    private fun initWebDavFragmentBindings() {
        supportFragmentManager.setFragmentResultListener(WebDavFragment.RESP_SAVED, this) { _, _ ->
            progress3()
            supportFragmentManager
                .beginTransaction()
                .setCustomAnimations(R.anim.slide_in_right, R.anim.slide_out_left)
                .replace(
                    mBinding.spaceSetupFragment.id,
                    SpaceSetupSuccessFragment.newInstance(getString(R.string.you_have_successfully_connected_to_a_private_server)),
                    FRAGMENT_TAG,
                )
                .commit()
        }

        supportFragmentManager.setFragmentResultListener(WebDavFragment.RESP_CANCEL, this) { _, _ ->
            progress1()
            supportFragmentManager
                .beginTransaction()
                .setCustomAnimations(R.anim.slide_in_left, R.anim.slide_out_right)
                .replace(mBinding.spaceSetupFragment.id, SpaceSetupFragment(), FRAGMENT_TAG)
                .commit()
        }
    }

    private fun initSpaceSetupFragmentBindings() {
        supportFragmentManager.setFragmentResultListener(
            SpaceSetupFragment.RESULT_REQUEST_KEY, this
        ) { _, bundle ->
            when (bundle.getString(SpaceSetupFragment.RESULT_BUNDLE_KEY)) {
                SpaceSetupFragment.RESULT_VAL_DROPBOX -> {
                    progress2()
                    supportFragmentManager
                        .beginTransaction()
                        .setCustomAnimations(R.anim.slide_in_right, R.anim.slide_out_left)
                        .replace(mBinding.spaceSetupFragment.id, DropboxFragment(), FRAGMENT_TAG)
                        .commit()
                }

                SpaceSetupFragment.RESULT_VAL_INTERNET_ARCHIVE -> {
                    progress2()
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
                    progress2()
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
                    progress2()
                    supportFragmentManager
                        .beginTransaction()
                        .setCustomAnimations(R.anim.slide_in_right, R.anim.slide_out_left)
                        .replace(mBinding.spaceSetupFragment.id, GDriveFragment(), FRAGMENT_TAG)
                        .commit()
                }
            }
        }
    }

    private fun initDropboxFragmentBindings() {
        supportFragmentManager.setFragmentResultListener(
            DropboxFragment.RESP_CANCEL,
            this
        ) { _, _ ->
            progress1()
            supportFragmentManager
                .beginTransaction()
                .setCustomAnimations(R.anim.slide_in_left, R.anim.slide_out_right)
                .replace(mBinding.spaceSetupFragment.id, SpaceSetupFragment(), FRAGMENT_TAG)
                .commit()
        }

        supportFragmentManager.setFragmentResultListener(
            DropboxFragment.RESP_AUTHENTICATED,
            this
        ) { _, _ ->
            progress3()
            supportFragmentManager
                .beginTransaction()
                .setCustomAnimations(R.anim.slide_in_right, R.anim.slide_out_left)
                .replace(
                    mBinding.spaceSetupFragment.id,
                    SpaceSetupSuccessFragment.newInstance(getString(R.string.you_have_successfully_connected_to_dropbox)),
                    FRAGMENT_TAG
                )
                .commit()
        }
    }

    private fun initInternetArchiveFragmentBindings() {
        supportFragmentManager.setFragmentResultListener(
            InternetArchiveFragment.RESP_SAVED,
            this
        ) { _, _ ->
            progress3()
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
            progress1()
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
            progress1()
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
            progress3()
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
        supportFragmentManager.findFragmentByTag(FRAGMENT_TAG)
            ?.onActivityResult(requestCode, resultCode, data)
    }

    private fun progress1() {
        Util.setBackgroundTint(mBinding.progressBlock.dot1, R.color.colorSpaceSetupProgressOn)
        Util.setBackgroundTint(mBinding.progressBlock.bar1, R.color.colorSpaceSetupProgressOff)
        Util.setBackgroundTint(mBinding.progressBlock.dot2, R.color.colorSpaceSetupProgressOff)
        Util.setBackgroundTint(mBinding.progressBlock.bar2, R.color.colorSpaceSetupProgressOff)
        Util.setBackgroundTint(mBinding.progressBlock.dot3, R.color.colorSpaceSetupProgressOff)
    }

    private fun progress2() {
        Util.setBackgroundTint(mBinding.progressBlock.dot1, R.color.colorSpaceSetupProgressOn)
        Util.setBackgroundTint(mBinding.progressBlock.bar1, R.color.colorSpaceSetupProgressOn)
        Util.setBackgroundTint(mBinding.progressBlock.dot2, R.color.colorSpaceSetupProgressOn)
        Util.setBackgroundTint(mBinding.progressBlock.bar2, R.color.colorSpaceSetupProgressOff)
        Util.setBackgroundTint(mBinding.progressBlock.dot3, R.color.colorSpaceSetupProgressOff)
    }

    private fun progress3() {
        Util.setBackgroundTint(mBinding.progressBlock.dot1, R.color.colorSpaceSetupProgressOn)
        Util.setBackgroundTint(mBinding.progressBlock.bar1, R.color.colorSpaceSetupProgressOn)
        Util.setBackgroundTint(mBinding.progressBlock.dot2, R.color.colorSpaceSetupProgressOn)
        Util.setBackgroundTint(mBinding.progressBlock.bar2, R.color.colorSpaceSetupProgressOn)
        Util.setBackgroundTint(mBinding.progressBlock.dot3, R.color.colorSpaceSetupProgressOn)
    }
}