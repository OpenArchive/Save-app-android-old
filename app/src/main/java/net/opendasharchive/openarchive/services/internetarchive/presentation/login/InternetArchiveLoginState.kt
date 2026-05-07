package net.opendasharchive.openarchive.services.internetarchive.presentation.login

import androidx.compose.runtime.Immutable
import net.opendasharchive.openarchive.R

enum class LoginErrorType(val stringRes: Int) {
    INVALID_CREDENTIALS(R.string.error_incorrect_email_or_password),
    NETWORK_TIMEOUT(R.string.error_network_timeout),
    NETWORK_UNAVAILABLE(R.string.error_network_unavailable),
    TOR_NOT_READY(R.string.error_tor_not_ready),
    SERVER_ERROR(R.string.error_server_unavailable),
}

@Immutable
data class InternetArchiveLoginState(
    val username: String = "",
    val password: String = "",
    val isUsernameError: Boolean = false,
    val isPasswordError: Boolean = false,
    val loginErrorType: LoginErrorType? = null,
    val isBusy: Boolean = false,
    val isValid: Boolean = false,
)

sealed interface InternetArchiveLoginAction {
    data class UpdateUsername(val username: String) : InternetArchiveLoginAction
    data class UpdatePassword(val password: String) : InternetArchiveLoginAction
    data object Login : InternetArchiveLoginAction
    data object Cancel : InternetArchiveLoginAction
    data object ErrorClear : InternetArchiveLoginAction
}

sealed interface InternetArchiveLoginEvent {
    data class LoginError(val error: Throwable) : InternetArchiveLoginEvent
}
