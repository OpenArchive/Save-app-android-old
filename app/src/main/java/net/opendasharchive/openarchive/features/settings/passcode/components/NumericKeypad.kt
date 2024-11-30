package net.opendasharchive.openarchive.features.settings.passcode.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import net.opendasharchive.openarchive.features.settings.passcode.AppHapticFeedbackType
import net.opendasharchive.openarchive.features.settings.passcode.HapticManager

@Composable
fun NumericKeypad(
    modifier: Modifier = Modifier,
    numbers: List<List<String>> = listOf(
        listOf("1", "2", "3"),
        listOf("4", "5", "6"),
        listOf("7", "8", "9"),
        listOf("", "0", "⌫")
    ),
    isEnabled: Boolean = true,
    onNumberClick: (String) -> Unit,
    onBackspaceClick: () -> Unit,
) {
    Column(modifier = modifier) {
        numbers.forEach { row ->
            Row(
                horizontalArrangement = Arrangement.spacedBy(24.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                row.forEach { label ->
                    if (label.isNotEmpty()) {
                        NumberButton(
                            label = label,
                            enabled = isEnabled,
                            onClick = {
                                if (label == "⌫") {
                                    onBackspaceClick()
                                } else {
                                    onNumberClick(label)
                                }
                            },
                        )
                    } else {
                        Spacer(modifier = Modifier.size(72.dp))
                    }
                }
            }
            Spacer(modifier = Modifier.height(16.dp))


        }
    }
}

@Composable
private fun NumberButton(
    label: String,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {

    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()
    val backgroundColor by animateColorAsState(
        targetValue = if (isPressed) MaterialTheme.colorScheme.primary.copy(alpha = 0.2f) else Color.Transparent,
        animationSpec = spring(),
        label = ""
    )

    Box(
        modifier = Modifier
            .size(72.dp)
            .background(color = backgroundColor, shape = CircleShape)
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                enabled = enabled,
                onClick = {
                    HapticManager.performHapticFeedback(AppHapticFeedbackType.KeyPress)
                    onClick()
                }
            )
            .border(width = 2.dp, color = MaterialTheme.colorScheme.primary, shape = CircleShape),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = label,
            style = TextStyle(
                fontSize = 24.sp,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onBackground
            )
        )
    }
}