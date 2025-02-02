package net.opendasharchive.openarchive.features.settings.passcode.passcode_setup

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.view.MenuItem
import androidx.activity.OnBackPressedCallback
import androidx.activity.compose.setContent
import net.opendasharchive.openarchive.core.presentation.theme.SaveAppTheme
import net.opendasharchive.openarchive.features.core.BaseActivity
import net.opendasharchive.openarchive.features.settings.passcode.components.DefaultScaffold

class PasscodeSetupActivity : BaseActivity() {

    companion object {
        const val EXTRA_PASSCODE_ENABLED = "passcode_enabled"
    }

    private val onBackPressedCallback = object : OnBackPressedCallback(enabled = true) {
        override fun handleOnBackPressed() {
            setResult(RESULT_CANCELED)
            finish()
        }
    }


    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        onBackPressedDispatcher.addCallback(onBackPressedCallback)

        setContent {
            SaveAppTheme {
                DefaultScaffold {
                    PasscodeSetupScreen(
                        onPasscodeSet = {
                            // Passcode successfully set
                            setResult(RESULT_OK, Intent().apply {
                                putExtra(EXTRA_PASSCODE_ENABLED, true)
                            })
                            finish()
                        },
                        onCancel = {
                            // User canceled the setup
                            setResult(RESULT_CANCELED)
                            finish()
                        }
                    )
                }
            }
        }
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (item.itemId == android.R.id.home) {
            // Cancel passcode setup
            setResult(Activity.RESULT_CANCELED)
            finish()
            return true
        }
        return super.onOptionsItemSelected(item)
    }
}