package net.opendasharchive.openarchive.features.core.dialog

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import net.opendasharchive.openarchive.features.core.UiText

// Common dialog types
sealed class DialogConfig {
    abstract val icon: ImageVector
    abstract val iconColor: @Composable () -> Color?
    abstract val title: UiText
    abstract val message: UiText
    abstract val positiveButton: ButtonConfig
    abstract val negativeButton: ButtonConfig?
    abstract val showCheckbox: Boolean
    abstract val checkboxText: UiText?
    abstract var checkboxState: Boolean

    data class Success(
        override val title: UiText,
        override val message: UiText,
        override val positiveButton: ButtonConfig = ButtonConfig(UiText.DynamicString("OK")),
        override val negativeButton: ButtonConfig? = null,
        override val icon: ImageVector = Icons.Filled.Check,
        override val iconColor: @Composable () -> Color? = { MaterialTheme.colorScheme.primary },
        override val showCheckbox: Boolean = false,
        override val checkboxText: UiText? = null,
        override var checkboxState: Boolean = false
    ) : DialogConfig()

    data class Error(
        override val title: UiText,
        override val message: UiText,
        override val positiveButton: ButtonConfig = ButtonConfig(UiText.DynamicString("Retry")),
        override val negativeButton: ButtonConfig? = ButtonConfig(UiText.DynamicString("Cancel")),
        override val icon: ImageVector = Icons.Default.Error,
        override val iconColor: @Composable () -> Color? = { MaterialTheme.colorScheme.primary },
        override val showCheckbox: Boolean = false,
        override val checkboxText: UiText? = null,
        override var checkboxState: Boolean = false
    ) : DialogConfig()

    data class Warning(
        override val title: UiText,
        override val message: UiText,
        override val positiveButton: ButtonConfig = ButtonConfig(UiText.DynamicString("OK")),
        override val negativeButton: ButtonConfig? = ButtonConfig(UiText.DynamicString("Cancel")),
        override val icon: ImageVector = Icons.Default.Warning,
        override val iconColor: @Composable () -> Color? = { MaterialTheme.colorScheme.primary },
        override val showCheckbox: Boolean = false,
        override val checkboxText: UiText? = null,
        override var checkboxState: Boolean = false
    ) : DialogConfig()

    data class Info(
        override val title: UiText = UiText.DynamicString("Warning"),
        override val message: UiText,
        override val positiveButton: ButtonConfig = ButtonConfig(UiText.DynamicString("OK")),
        override val negativeButton: ButtonConfig? = null,
        override val icon: ImageVector = Icons.Default.Info,
        override val iconColor: @Composable () -> Color? = { MaterialTheme.colorScheme.primary },
        override val showCheckbox: Boolean = false,
        override val checkboxText: UiText? = null,
        override var checkboxState: Boolean = false
    ) : DialogConfig()

    data class Custom(
        override val title: UiText,
        override val message: UiText,
        override val positiveButton: ButtonConfig,
        override val negativeButton: ButtonConfig? = null,
        override val icon: ImageVector = Icons.Default.Info,
        override val iconColor: @Composable () -> Color? = { MaterialTheme.colorScheme.primary },
        override val showCheckbox: Boolean = false,
        override val checkboxText: UiText? = null,
        override var checkboxState: Boolean = false,
        val customContent: (@Composable () -> Unit)? = null
    ) : DialogConfig()
}