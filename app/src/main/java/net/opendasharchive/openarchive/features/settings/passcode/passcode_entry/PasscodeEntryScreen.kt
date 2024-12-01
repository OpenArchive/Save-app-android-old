package net.opendasharchive.openarchive.features.settings.passcode.passcode_entry

import android.content.res.Configuration
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
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
fun PasscodeEntryScreen(
    onPasscodeSuccess: () -> Unit,
    onExit: () -> Unit,
    viewModel: PasscodeEntryViewModel = koinViewModel(),
    hapticManager: HapticManager = koinInject()
) {

    val passcode by viewModel.passcode.collectAsStateWithLifecycle()
    val isCheckingPasscode by viewModel.isCheckingPasscode.collectAsStateWithLifecycle()
    var shouldShake by remember { mutableStateOf(false) }

    val hapticFeedback = LocalHapticFeedback.current

    LaunchedEffect(Unit) {
        hapticManager.init(hapticFeedback)
    }

    // Function to handle passcode entry
    LaunchedEffect(Unit) {
        viewModel.uiEvent.collectLatest { event ->
            when (event) {
                PasscodeEntryUiEvent.Success -> onPasscodeSuccess()

                PasscodeEntryUiEvent.PasscodeNotSet -> {
                    MessageManager.showMessage("Passcode not set")
                }

                is PasscodeEntryUiEvent.IncorrectPasscode -> {
                    hapticManager.performHapticFeedback(AppHapticFeedbackType.Error)
                    shouldShake = true

                    event.remainingAttempts?.let {
                        val message = "Incorrect passcode. $it attempts remaining."
                        MessageManager.showMessage(message)
                    }
                    delay(500) // Allow animation to complete
                    shouldShake = false
                }

                PasscodeEntryUiEvent.LockedOut -> {
                    MessageManager.showMessage("Too many failed attempts. App is locked.")
                    onExit()
                }
            }
        }
    }

    PasscodeEntryScreenContent(
        passcode = passcode,
        passcodeLength = viewModel.passcodeLength,
        isCheckingPasscode = isCheckingPasscode,
        shouldShake = shouldShake,
        onExit = onExit,
        onNumberClick = {
            viewModel.onNumberClick(it)
            shouldShake = false
        },
        onBackspaceClick = viewModel::onBackspaceClick
    )
}

data class PasscodeEntryScreenState(
    val passcode: String = "",
    val passcodeLength: Int,
    val isCheckingPasscode: Boolean = false,
    val shouldShake: Boolean = false
)

sealed class PasscodeEntryScreenAction {
    data class OnNumberClick(val number: String) : PasscodeEntryScreenAction()
    data object OnBackspaceClick : PasscodeEntryScreenAction()
    data object OnExit : PasscodeEntryScreenAction()
}

@Composable
fun PasscodeEntryScreenContent(
    passcode: String,
    passcodeLength: Int,
    isCheckingPasscode: Boolean,
    shouldShake: Boolean,
    onExit: () -> Unit,
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
                text = "Enter Your Passcode", style = TextStyle(
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
                isEnabled = !isCheckingPasscode,
                onNumberClick = { number ->
                    onNumberClick(number)
                }
            )

            Spacer(modifier = Modifier.height(16.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceAround
            ) {
                TextButton(
                    onClick = {
                        onExit()
                    }
                ) {
                    Text(
                        text = "Exit",
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


@Preview(uiMode = Configuration.UI_MODE_NIGHT_YES)
@Preview
@Composable
private fun PasscodeEntryScreenPreview() {

    Theme {
        PasscodeEntryScreenContent(
            passcode = "123",
            passcodeLength = 6,
            isCheckingPasscode = false,
            shouldShake = false,
            onExit = {},
            onNumberClick = {},
            onBackspaceClick = {}
        )
    }
}