package net.opendasharchive.openarchive.features.internetarchive.presentation.login

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.tooling.preview.Preview
import kotlinx.coroutines.delay
import net.opendasharchive.openarchive.R
import net.opendasharchive.openarchive.core.presentation.theme.ThemeColors
import net.opendasharchive.openarchive.core.presentation.theme.ThemeDimensions
import net.opendasharchive.openarchive.core.state.Dispatch
import net.opendasharchive.openarchive.db.Space
import net.opendasharchive.openarchive.features.internetarchive.presentation.components.IAResult
import net.opendasharchive.openarchive.features.internetarchive.presentation.components.InternetArchiveHeader
import net.opendasharchive.openarchive.features.internetarchive.presentation.login.InternetArchiveLoginAction.CreateLogin
import net.opendasharchive.openarchive.features.internetarchive.presentation.login.InternetArchiveLoginAction.Login
import net.opendasharchive.openarchive.features.internetarchive.presentation.login.InternetArchiveLoginAction.UpdatePassword
import net.opendasharchive.openarchive.features.internetarchive.presentation.login.InternetArchiveLoginAction.UpdateUsername
import org.koin.androidx.compose.koinViewModel
import org.koin.core.parameter.parametersOf
import net.opendasharchive.openarchive.features.internetarchive.presentation.login.InternetArchiveLoginAction as Action

@Composable
fun InternetArchiveLoginScreen(space: Space, onResult: (IAResult) -> Unit) {
    val viewModel: InternetArchiveLoginViewModel = koinViewModel {
        parametersOf(space)
    }

    val state by viewModel.state.collectAsState()

    val launcher =
        rememberLauncherForActivityResult(contract = ActivityResultContracts.StartActivityForResult(),
            onResult = {})

    LaunchedEffect(Unit) {
        viewModel.actions.collect { action ->
            when (action) {
                is CreateLogin -> launcher.launch(
                    Intent(
                        Intent.ACTION_VIEW, Uri.parse(CreateLogin.URI)
                    )
                )

                is Action.Cancel -> onResult(IAResult.Cancelled)

                is Action.LoginSuccess -> onResult(IAResult.Saved)

                else -> Unit
            }
        }
    }

    InternetArchiveLoginContent(state, viewModel::dispatch)
}

@Composable
private fun InternetArchiveLoginContent(
    state: InternetArchiveLoginState, dispatch: Dispatch<Action>
) {

    // If extra paranoid could pre-hash password in memory
    // and use the store/dispatcher
    var showPassword by rememberSaveable {
        mutableStateOf(false)
    }

    LaunchedEffect(state.isLoginError) {
        while (state.isLoginError) {
            delay(3000)
            dispatch(Action.ErrorClear)
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(ThemeDimensions.spacing.medium),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {

        InternetArchiveHeader(
            modifier = Modifier.padding(bottom = ThemeDimensions.spacing.large)
        )

        OutlinedTextField(
            value = state.username,
            enabled = !state.isBusy,
            onValueChange = { dispatch(UpdateUsername(it)) },
            label = {
                Text(stringResource(R.string.label_username))
            },
            placeholder = {
                Text(stringResource(R.string.placeholder_email_or_username))
            },
            singleLine = true,
            shape = RoundedCornerShape(ThemeDimensions.roundedCorner),
            keyboardOptions = KeyboardOptions(
                imeAction = ImeAction.Next,
                autoCorrect = false,
                keyboardType = KeyboardType.Email
            ),
            isError = state.isUsernameError,
        )

        Spacer(Modifier.height(ThemeDimensions.spacing.large))

        OutlinedTextField(
            value = state.password,
            enabled = !state.isBusy,
            onValueChange = { dispatch(UpdatePassword(it)) },
            label = {
                Text(stringResource(R.string.label_password))
            },
            placeholder = {
                Text(stringResource(R.string.placeholder_password))
            },
            singleLine = true,
            trailingIcon = {
                IconButton(modifier = Modifier.sizeIn(ThemeDimensions.touchable), onClick = { showPassword = !showPassword }) {
                    Icon(
                        imageVector = if (showPassword) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                        contentDescription = "show password"
                    )
                }
            },
            shape = RoundedCornerShape(ThemeDimensions.roundedCorner),
            visualTransformation = if (showPassword) VisualTransformation.None else PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(
                keyboardType = KeyboardType.Password,
                autoCorrect = false,
                imeAction = ImeAction.Go
            ),
            isError = state.isPasswordError,
        )

        AnimatedVisibility(
            visible = state.isLoginError,
            enter = fadeIn(),
            exit = fadeOut()
        ) {
            Text(
                text = stringResource(R.string.error_incorrect_username_or_password),
                color = MaterialTheme.colorScheme.error
            )
        }
        Row(
            modifier = Modifier
                .padding(top = ThemeDimensions.spacing.small)
                .weight(1f),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = stringResource(R.string.prompt_no_account),
                color = ThemeColors.material.onBackground
            )
            TextButton(
                modifier = Modifier.heightIn(ThemeDimensions.touchable),
                onClick = { dispatch(CreateLogin) }) {
                Text(
                    text = stringResource(R.string.label_create_login),
                    fontWeight = FontWeight.Bold,
                    style = MaterialTheme.typography.bodyLarge
                )
            }
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = ThemeDimensions.spacing.medium),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceEvenly
        ) {
            TextButton(
                modifier = Modifier
                    .weight(1f)
                    .heightIn(ThemeDimensions.touchable)
                    .padding(ThemeDimensions.spacing.small),
                shape = RoundedCornerShape(ThemeDimensions.roundedCorner),
                onClick = { dispatch(Action.Cancel) }) {
                Text(stringResource(R.string.action_cancel))
            }
            Button(
                modifier = Modifier
                    .heightIn(ThemeDimensions.touchable)
                    .weight(1f),
                enabled = !state.isBusy && state.isValid,
                shape = RoundedCornerShape(ThemeDimensions.roundedCorner),
                onClick = { dispatch(Login) },
            ) {
                if (state.isBusy) {
                    CircularProgressIndicator(color = ThemeColors.material.primary)
                } else {
                    Text(stringResource(R.string.label_login))
                }
            }
        }
    }
}

@Composable
@Preview(showBackground = true)
private fun InternetArchiveLoginPreview() {
    InternetArchiveLoginContent(
        state = InternetArchiveLoginState(
            username = "user@example.org", password = "abc123"
        )
    ) {}
}
