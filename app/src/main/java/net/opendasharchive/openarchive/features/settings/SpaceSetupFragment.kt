package net.opendasharchive.openarchive.features.settings

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.os.bundleOf
import androidx.fragment.app.setFragmentResult
import net.opendasharchive.openarchive.databinding.FragmentSpaceSetupBinding
import net.opendasharchive.openarchive.db.Space
import net.opendasharchive.openarchive.features.onboarding.BaseFragment
import net.opendasharchive.openarchive.features.settings.passcode.AppConfig
import net.opendasharchive.openarchive.util.extensions.hide
import net.opendasharchive.openarchive.util.extensions.show
import org.koin.android.ext.android.inject
import kotlin.getValue

class SpaceSetupFragment : BaseFragment() {

    private val appConfig by inject<AppConfig>()

    private lateinit var binding: FragmentSpaceSetupBinding

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        binding = FragmentSpaceSetupBinding.inflate(inflater)

        binding.webdav.setOnClickListener {
            setFragmentResult(RESULT_REQUEST_KEY, bundleOf(RESULT_BUNDLE_KEY to RESULT_VAL_WEBDAV))
        }

        if (Space.has(Space.Type.INTERNET_ARCHIVE)) {
            this@SpaceSetupFragment.binding.internetArchive.hide()
        } else {
            binding.internetArchive.setOnClickListener {
                setFragmentResult(
                    RESULT_REQUEST_KEY,
                    bundleOf(RESULT_BUNDLE_KEY to RESULT_VAL_INTERNET_ARCHIVE)
                )
            }
        }

        if (appConfig.snowbirdEnabled) {
            binding.snowbird.show()
        } else {
            binding.snowbird.hide()
        }


        binding.snowbird.setOnClickListener {
            setFragmentResult(RESULT_REQUEST_KEY, bundleOf(RESULT_BUNDLE_KEY to RESULT_VAL_RAVEN))
        }

        return binding.root
    }

    companion object {
        const val RESULT_REQUEST_KEY = "space_setup_fragment_result"
        const val RESULT_BUNDLE_KEY = "space_setup_result_key"
        const val RESULT_VAL_DROPBOX = "dropbox"
        const val RESULT_VAL_WEBDAV = "webdav"
        const val RESULT_VAL_RAVEN = "raven"
        const val RESULT_VAL_INTERNET_ARCHIVE = "internet_archive"
        const val RESULT_VAL_GDRIVE = "gdrive"
    }

    override fun getToolbarTitle() = "Select a Server"
    override fun getToolbarSubtitle(): String? = null
    override fun shouldShowBackButton() = true
}