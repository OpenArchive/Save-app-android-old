package net.opendasharchive.openarchive.features.internetarchive.presentation.details

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import net.opendasharchive.openarchive.R
import net.opendasharchive.openarchive.core.presentation.theme.ThemeColors
import net.opendasharchive.openarchive.core.presentation.theme.ThemeDimensions
import net.opendasharchive.openarchive.core.state.Dispatch
import net.opendasharchive.openarchive.db.Space
import net.opendasharchive.openarchive.features.internetarchive.presentation.components.IAResult
import net.opendasharchive.openarchive.features.internetarchive.presentation.components.InternetArchiveHeader
import net.opendasharchive.openarchive.features.internetarchive.presentation.details.InternetArchiveDetailsViewModel.Action
import org.koin.androidx.compose.koinViewModel
import org.koin.core.parameter.parametersOf

@Composable
fun InternetArchiveDetailsScreen(space: Space, onResult: (IAResult) -> Unit) {
    val viewModel: InternetArchiveDetailsViewModel = koinViewModel {
        parametersOf(space)
    }

    val state by viewModel.state.collectAsState()

    LaunchedEffect(Unit) {
        viewModel.actions.collect { action ->
            when (action) {
                is Action.Remove -> onResult(IAResult.Deleted)
                is Action.Cancel -> onResult(IAResult.Cancelled)
                else -> Unit
            }
        }
    }

    InternetArchiveDetailsContent(state, viewModel::dispatch)
}

@Composable
private fun InternetArchiveDetailsContent(
    state: InternetArchiveDetailsState,
    dispatch: Dispatch<Action>
) {

    var isRemoving by remember { mutableStateOf(false) }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp)
    ) {

        Column {

            InternetArchiveHeader()

            Spacer(Modifier.height(ThemeDimensions.spacing.large))

            Text(
                text = stringResource(id = R.string.label_username),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onBackground
            )
            Text(
                text = state.userName,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onBackground
            )

            Text(
                modifier = Modifier.padding(top = 16.dp),
                text = stringResource(id = R.string.label_screen_name),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onBackground
            )

            Text(
                text = state.screenName,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onBackground
            )

            Text(
                modifier = Modifier.padding(top = 16.dp),
                text = stringResource(id = R.string.label_email),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onBackground
            )

            Text(
                text = state.email,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onBackground
            )
        }

        Button(
            modifier = Modifier
                .padding(top = 12.dp)
                .align(Alignment.BottomCenter),
            shape = RoundedCornerShape(ThemeDimensions.roundedCorner),
            onClick = {
                isRemoving = true
            },
            colors = ButtonDefaults.buttonColors(containerColor = ThemeColors.material.error)
        ) {
            Text(stringResource(id = R.string.menu_delete))
        }
    }

    if (isRemoving) {
        RemoveInternetArchiveDialog(onDismiss = { isRemoving = false }) {
            isRemoving = false
            dispatch(Action.Remove)
        }
    }
}

@Composable
private fun RemoveInternetArchiveDialog(onDismiss: () -> Unit, onRemove: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = ThemeColors.material.surface,
        titleContentColor = ThemeColors.material.onSurface,
        textContentColor = ThemeColors.material.onSurfaceVariant,
        title = {
            Text(text = stringResource(id = R.string.remove_from_app))
        },
        text = { Text(stringResource(id = R.string.are_you_sure_you_want_to_remove_this_server_from_the_app)) },
        dismissButton = {
            OutlinedButton(
                onClick = onDismiss,
                shape = RoundedCornerShape(ThemeDimensions.roundedCorner)
            ) {
                Text(stringResource(id = R.string.action_cancel))
            }
        }, confirmButton = {
            Button(
                onClick = onRemove,
                shape = RoundedCornerShape(ThemeDimensions.roundedCorner),
                colors = ButtonDefaults.buttonColors(containerColor = ThemeColors.material.error)
            ) {
                Text(stringResource(id = R.string.remove))
            }
        })
}

@Composable
@Preview(showBackground = true)
private fun InternetArchiveScreenPreview() {
    InternetArchiveDetailsContent(
        state = InternetArchiveDetailsState(
            email = "abc@example.com",
            userName = "@abc_name",
            screenName = "ABC Name"
        )
    ) {}
}

@Composable
@Preview(showBackground = true)
private fun RemoveInternetArchiveDialogPreview() {
    RemoveInternetArchiveDialog(onDismiss = { }) {}
}
