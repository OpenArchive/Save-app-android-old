package net.opendasharchive.openarchive.features.settings.passcode

import android.content.Context
import android.os.VibrationEffect
import android.os.Vibrator
import androidx.compose.ui.hapticfeedback.HapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType

enum class AppHapticFeedbackType {
    KeyPress,
    Error,
}

object HapticManager {
    var isEnabled: Boolean = true // Set this based on PasscodeConfig

    private var vibrator: Vibrator? = null
    private var hapticFeedback: HapticFeedback? = null

    fun init(context: Context) {
        vibrator = context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
    }

    fun init(hapticFeedback: HapticFeedback) {
        HapticManager.hapticFeedback = hapticFeedback
    }

    fun performHapticFeedback(type: AppHapticFeedbackType) {
        if (!isEnabled) return

        hapticFeedback?.let {
            // Using Compose HapticFeedback
            when (type) {
                AppHapticFeedbackType.KeyPress -> it.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                AppHapticFeedbackType.Error -> it.performHapticFeedback(HapticFeedbackType.LongPress)
            }
        } ?: vibrator?.let {
            // Using Vibrator from Context
            if (it.hasVibrator()) {
                when (type) {
                    AppHapticFeedbackType.KeyPress -> vibrate(50)  // 50ms for key press
                    AppHapticFeedbackType.Error -> vibrate(200)    // 200ms for error
                }
            }
        }
    }

    private fun vibrate(duration: Long) {
        vibrator?.vibrate(VibrationEffect.createOneShot(duration, VibrationEffect.DEFAULT_AMPLITUDE))
    }
}