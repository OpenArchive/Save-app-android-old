package net.opendasharchive.openarchive.features.settings

import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.view.MenuItem
import androidx.preference.Preference
import androidx.preference.PreferenceFragmentCompat
import androidx.preference.SwitchPreferenceCompat
import info.guardianproject.netcipher.proxy.OrbotHelper
import net.opendasharchive.openarchive.CleanInsightsManager
import net.opendasharchive.openarchive.R
import net.opendasharchive.openarchive.databinding.ActivitySettingsContainerBinding
import net.opendasharchive.openarchive.features.core.BaseActivity
import net.opendasharchive.openarchive.util.AlertHelper
import net.opendasharchive.openarchive.util.Prefs
import net.opendasharchive.openarchive.util.Theme

class GeneralSettingsActivity: BaseActivity() {

    class Fragment: PreferenceFragmentCompat() {

        private var mCiConsentPref: SwitchPreferenceCompat? = null

        override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
            setPreferencesFromResource(R.xml.prefs_general, rootKey)

            findPreference<Preference>(Prefs.USE_TOR)?.setOnPreferenceChangeListener { _, newValue ->
                val activity = activity ?: return@setOnPreferenceChangeListener true

                if (newValue as Boolean) {
                    if (!OrbotHelper.isOrbotInstalled(activity) && !OrbotHelper.isTorServicesInstalled(activity)) {
                        AlertHelper.show(activity,
                            R.string.prefs_install_tor_summary,
                            R.string.prefs_use_tor_title,
                            buttons = listOf(
                                AlertHelper.positiveButton(R.string.action_install) { _, _ ->
                                    activity.startActivity(
                                        OrbotHelper.getOrbotInstallIntent(activity))
                                },
                                AlertHelper.negativeButton(R.string.action_cancel)
                            ))

                        return@setOnPreferenceChangeListener false
                    }
                }

                true
            }

            findPreference<Preference>("proof_mode")?.setOnPreferenceClickListener {
                startActivity(Intent(context, ProofModeSettingsActivity::class.java))

                true
            }

            findPreference<Preference>(Prefs.THEME)?.setOnPreferenceChangeListener { _, newValue ->
                Theme.set(Theme.get(newValue as? String))

                true
            }

            findPreference<Preference>(Prefs.PROHIBIT_SCREENSHOTS)?.setOnPreferenceClickListener { _ ->
                if (activity is BaseActivity) {
                    // make sure this gets settings change gets applied instantly
                    // (all other activities rely on the hook in BaseActivity.onResume())
                    (activity as BaseActivity).updateScreenshotPrevention()
                }

                true
            }

            mCiConsentPref = findPreference("health_checks")

            mCiConsentPref?.setOnPreferenceChangeListener { _, newValue ->
                if (newValue as? Boolean == false) {
                    CleanInsightsManager.deny()
                }
                else {
                    startActivity(Intent(context, ConsentActivity::class.java))
                }

                true
            }

            // Hide Tor option on android 14+ because NetCipher doesn't work on this API level
            // https://github.com/OpenArchive/Save-app-android/issues/534
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                findPreference<Preference>(Prefs.USE_TOR)?.let {
                     it.isVisible = false
                }
            }
        }

        override fun onResume() {
            super.onResume()

            mCiConsentPref?.isChecked = CleanInsightsManager.hasConsent()
        }
    }


    private lateinit var mBinding: ActivitySettingsContainerBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        mBinding = ActivitySettingsContainerBinding.inflate(layoutInflater)
        setContentView(mBinding.root)

        setSupportActionBar(mBinding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        supportFragmentManager
            .beginTransaction()
            .replace(mBinding.container.id, Fragment())
            .commit()
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (item.itemId == android.R.id.home) {
            finish()
            return true
        }

        return super.onOptionsItemSelected(item)
    }
}