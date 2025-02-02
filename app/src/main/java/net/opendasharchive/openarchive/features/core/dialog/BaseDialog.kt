package net.opendasharchive.openarchive.features.core.dialog

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.ViewModel
import net.opendasharchive.openarchive.core.presentation.theme.DefaultBoxPreview
import net.opendasharchive.openarchive.features.core.BaseButton
import net.opendasharchive.openarchive.features.core.BaseDestructiveButton
import net.opendasharchive.openarchive.features.core.BaseNeutralButton
import net.opendasharchive.openarchive.features.core.UiText


data class ButtonConfig(
    val text: UiText, val action: () -> Unit = {}
)

@Composable
fun BaseDialog(
    onDismiss: () -> Unit,
    icon: ImageVector? = null,
    iconColor: Color? = null,
    title: String,
    message: String,
    hasCheckbox: Boolean = false,
    onCheckBoxStateChanged: (Boolean) -> Unit = {},
    checkBoxHint: String = "Do not show me this again",
    positiveButton: ButtonConfig,
    neutralButton: ButtonConfig? = null,
    destructiveButton: ButtonConfig? = null,
    backgroundColor: Color = MaterialTheme.colorScheme.surfaceContainerHigh
) {

    Dialog(
        onDismissRequest = { onDismiss.invoke() },
        properties = DialogProperties(
            dismissOnBackPress = true,
            dismissOnClickOutside = true,
            usePlatformDefaultWidth = true
        )
    ) {
        Box(
            Modifier
                .clip(RoundedCornerShape(12.dp))
                .fillMaxWidth()
                .background(backgroundColor)
        ) {

            Card() {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 16.dp, end = 16.dp, top = 18.dp, bottom = 12.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {

                    icon?.let { icon ->
                        Icon(
                            modifier = Modifier.size(24.dp),
                            imageVector = icon,
                            contentDescription = null,
                            tint = iconColor ?: Color.Unspecified
                        )

                        Spacer(modifier = Modifier.height(18.dp))
                    }

                    BaseDialogTitle(title)

                    Spacer(Modifier.height(4.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center
                    ) {
                        BaseDialogContent(message)
                    }

                    if (hasCheckbox) {
                        Spacer(Modifier.height(12.dp))

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.Center,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Checkbox(
                                checked = false,
                                onCheckedChange = { onCheckBoxStateChanged.invoke(it) })

                            BaseDialogContent(checkBoxHint)
                        }

                    }

                    Spacer(Modifier.height(18.dp))

                    BaseButton(
                        modifier = Modifier.fillMaxWidth(),
                        text = positiveButton.text.asString(),
                        onClick = {
                            positiveButton.action()
                            onDismiss()
                        })

                    neutralButton?.let { btn ->
                        Spacer(modifier = Modifier.height(4.dp))
                        BaseNeutralButton(
                            modifier = Modifier.fillMaxWidth(), text = btn.text.asString(), onClick = {
                                btn.action()
                                onDismiss()
                            })
                    }

                    destructiveButton?.let { btn ->
                        Spacer(modifier = Modifier.height(4.dp))
                        BaseDestructiveButton(
                            modifier = Modifier.fillMaxWidth(),
                            onClick = {
                                btn.action()
                                onDismiss()
                            },
                            text = btn.text.asString()
                        )
                    }
                }

            }


        }
    }
}

@Composable
fun BaseDialogTitle(
    text: String,
    modifier: Modifier = Modifier
) {
    Text(
        text = text,
        style = MaterialTheme.typography.headlineSmall.copy(
            fontSize = 18.sp,
            fontWeight = FontWeight.Bold
        ),
        modifier = modifier
    )
}

@Composable
fun BaseDialogContent(
    text: String,
    modifier: Modifier = Modifier
) {
    Text(
        text = text,
        textAlign = TextAlign.Center,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        style = MaterialTheme.typography.bodyMedium.copy(
            fontSize = 14.sp,
            fontWeight = FontWeight.Normal,
        ),
        modifier = modifier
    )
}


class DialogStateManager : ViewModel() {
    private val _dialogConfig = mutableStateOf<DialogConfig?>(null)
    val dialogConfig: State<DialogConfig?> = _dialogConfig

    fun showDialog(config: DialogConfig) {
        _dialogConfig.value = config
    }

    fun dismissDialog() {
        _dialogConfig.value = null
    }
}

@Composable
fun DialogHost(dialogStateManager: DialogStateManager) {
    val currentDialog by dialogStateManager.dialogConfig

    currentDialog?.let { config ->
        BaseDialog(
            onDismiss = {
                dialogStateManager.dismissDialog()
            },
            icon = config.icon,
            iconColor = config.iconColor.invoke(),
            title = config.title.asString(),
            message = config.message.asString(),
            positiveButton = config.positiveButton,
            destructiveButton = config.negativeButton,
            hasCheckbox = config.showCheckbox,
            onCheckBoxStateChanged = { config.checkboxState = it },
            checkBoxHint = config.checkboxText?.asString() ?: "",
        )
    }
}

@Preview
@Composable
private fun BaseDialogPreview() {
    DefaultBoxPreview {

        BaseDialog(
            onDismiss = {},
            icon = Icons.Filled.Check,
            iconColor = MaterialTheme.colorScheme.primary,
            title = "Success",
            message = "You have added a folder successfully",
            positiveButton = ButtonConfig(UiText.DynamicString("OK")),
        )

    }
}

@Preview
@Composable
private fun WarningDialogPreview() {
    DefaultBoxPreview {

        BaseDialog(
            onDismiss = {},
            icon = Icons.Default.Warning,
            iconColor = MaterialTheme.colorScheme.primary,
            title = "Warning",
            message = "Once uploaded, you will not be able to edit media",
            positiveButton = ButtonConfig(UiText.DynamicString("OK")),
            neutralButton = ButtonConfig(UiText.DynamicString("Cancel")),
            hasCheckbox = true,
            checkBoxHint = "Do not show me this again",
            onCheckBoxStateChanged = { },
        )
    }
}

@Preview
@Composable
private fun ErrorDialogPreview() {
    DefaultBoxPreview {

        BaseDialog(
            onDismiss = {},
            icon = Icons.Default.ErrorOutline,
            iconColor = MaterialTheme.colorScheme.error,
            title = "Image upload unsuccessful",
            message = "Give a reason here? Lorem Ipsum text can go here if needed",
            positiveButton = ButtonConfig(UiText.DynamicString("Retry")),
            destructiveButton = ButtonConfig(UiText.DynamicString("Remove Image")),
        )
    }
}