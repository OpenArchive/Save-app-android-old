package net.opendasharchive.openarchive.features.settings.passcode.passcode_setup

import android.content.res.Configuration.UI_MODE_NIGHT_YES
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import net.opendasharchive.openarchive.R
import net.opendasharchive.openarchive.core.presentation.theme.Theme
import net.opendasharchive.openarchive.features.settings.passcode.AppHapticFeedbackType
import net.opendasharchive.openarchive.features.settings.passcode.HapticManager
import net.opendasharchive.openarchive.features.settings.passcode.components.MessageManager
import net.opendasharchive.openarchive.features.settings.passcode.components.NumericKeypad
import net.opendasharchive.openarchive.features.settings.passcode.components.PasscodeDots
import org.koin.androidx.compose.koinViewModel
import org.koin.compose.koinInject

@Composable
fun PasscodeSetupScreen(
    onPasscodeSet: () -> Unit,
    onCancel: () -> Unit,
    viewModel: PasscodeSetupViewModel = koinViewModel(),
    hapticManager: HapticManager = koinInject()
) {

    val passcode by viewModel.passcode.collectAsStateWithLifecycle()
    val isConfirming by viewModel.isConfirming.collectAsStateWithLifecycle()
    val isProcessing by viewModel.isProcessing.collectAsStateWithLifecycle()
    var shouldShake by remember { mutableStateOf(false) }
    val hapticFeedback = LocalHapticFeedback.current

    LaunchedEffect(Unit) {
        hapticManager.init(hapticFeedback)
    }

    // Function to handle UI events
    LaunchedEffect(Unit) {
        viewModel.uiEvent.collectLatest { event ->
            when (event) {
                PasscodeSetupUiEvent.PasscodeSet -> onPasscodeSet()
                PasscodeSetupUiEvent.PasscodeDoNotMatch -> {
                    hapticManager.performHapticFeedback(AppHapticFeedbackType.Error)
                    shouldShake = true
                    MessageManager.showMessage("Passcodes do not match. Try again.")
                    delay(500)
                    shouldShake = false
                }
            }
        }
    }


    PasscodeSetupScreenContent(
        passcode = passcode,
        passcodeLength = viewModel.passcodeLength,
        isConfirming = isConfirming,
        isProcessing = isProcessing,
        shouldShake = shouldShake,
        onCancel = onCancel,
        onNumberClick = viewModel::onNumberClick,
        onBackspaceClick = viewModel::onBackspaceClick
    )
}

@Composable
private fun PasscodeSetupScreenContent(
    passcode: String,
    passcodeLength: Int,
    isConfirming: Boolean,
    isProcessing: Boolean,
    shouldShake: Boolean,
    onCancel: () -> Unit,
    onNumberClick: (String) -> Unit,
    onBackspaceClick: () -> Unit
) {


    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
        verticalArrangement = Arrangement.SpaceAround,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // Top section with logo
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Top,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp)
                .padding(bottom = 24.dp)
        ) {
            Image(
                painter = painterResource(R.drawable.savelogo),
                contentDescription = null,
                modifier = Modifier.size(100.dp),
                contentScale = ContentScale.Fit
            )

            Spacer(modifier = Modifier.height(16.dp))

            Text(
                text = "Remember this PIN. If you forget it, you will need to reset the application and all data will be erased.",
                color = MaterialTheme.colorScheme.error,
                textAlign = TextAlign.Center,
                fontWeight = FontWeight.Light,
                style = MaterialTheme.typography.labelMedium
            )
        }


        // Middle section with prompt and passcode dots
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 24.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = if (isConfirming) "Confirm Your Passcode" else "Set Your Passcode",
                style = TextStyle(
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onBackground
                )
            )


            Spacer(modifier = Modifier.height(32.dp))

            // Passcode dots display
            PasscodeDots(
                passcodeLength = passcodeLength,
                currentPasscodeLength = passcode.length,
                shouldShake = shouldShake
            )

            Spacer(modifier = Modifier.height(32.dp))

            // Custom numeric keypad
            NumericKeypad(
                isEnabled = !isProcessing,
                onNumberClick = { number ->
                    onNumberClick(number)
                }
            )

            Spacer(modifier = Modifier.height(16.dp))

            Spacer(modifier = Modifier.height(16.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceAround
            ) {
                TextButton(
                    onClick = {
                        onCancel()
                    }
                ) {
                    Text(
                        text = "Cancel",
                        modifier = Modifier.padding(8.dp),
                        style = TextStyle(
                            fontSize = 16.sp,
                            fontWeight = FontWeight.Bold,
                        ),
                    )
                }

                TextButton(
                    enabled = passcode.isNotEmpty(),
                    onClick = {
                        onBackspaceClick()
                    }
                ) {
                    Text(
                        text = "Delete",
                        modifier = Modifier.padding(8.dp),
                        style = TextStyle(
                            fontSize = 16.sp,
                            fontWeight = FontWeight.Bold
                        ),
                    )
                }


            }
        }
    }
}

@Preview(uiMode = UI_MODE_NIGHT_YES)
@Preview
@Composable
private fun PasscodeSetupScreenPreview() {
    Theme {
        PasscodeSetupScreenContent(
            passcode = "123",
            passcodeLength = 6,
            isConfirming = false,
            isProcessing = false,
            shouldShake = false,
            onCancel = { },
            onNumberClick = { },
            onBackspaceClick = { }
        )
    }
}