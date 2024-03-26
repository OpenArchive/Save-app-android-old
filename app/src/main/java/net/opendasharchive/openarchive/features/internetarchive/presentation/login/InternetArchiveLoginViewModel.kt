package net.opendasharchive.openarchive.features.internetarchive.presentation.login

import net.opendasharchive.openarchive.core.presentation.StatefulViewModel
import net.opendasharchive.openarchive.db.Space
import net.opendasharchive.openarchive.features.internetarchive.domain.usecase.InternetArchiveLoginUseCase
import net.opendasharchive.openarchive.features.internetarchive.domain.usecase.ValidateLoginCredentialsUseCase
import net.opendasharchive.openarchive.features.internetarchive.presentation.login.InternetArchiveLoginAction.Cancel
import net.opendasharchive.openarchive.features.internetarchive.presentation.login.InternetArchiveLoginAction.CreateLogin
import net.opendasharchive.openarchive.features.internetarchive.presentation.login.InternetArchiveLoginAction.ErrorClear
import net.opendasharchive.openarchive.features.internetarchive.presentation.login.InternetArchiveLoginAction.Login
import net.opendasharchive.openarchive.features.internetarchive.presentation.login.InternetArchiveLoginAction.LoginError
import net.opendasharchive.openarchive.features.internetarchive.presentation.login.InternetArchiveLoginAction.LoginSuccess
import net.opendasharchive.openarchive.features.internetarchive.presentation.login.InternetArchiveLoginAction.UpdatePassword
import net.opendasharchive.openarchive.features.internetarchive.presentation.login.InternetArchiveLoginAction.UpdateUsername
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import org.koin.core.parameter.parametersOf
import net.opendasharchive.openarchive.features.internetarchive.presentation.login.InternetArchiveLoginAction as Action
import net.opendasharchive.openarchive.features.internetarchive.presentation.login.InternetArchiveLoginState as State

class InternetArchiveLoginViewModel(
    private val validateLoginCredentials: ValidateLoginCredentialsUseCase,
    private val space: Space,
) : StatefulViewModel<State, Action>(State()), KoinComponent {

    private val loginUseCase: InternetArchiveLoginUseCase by inject {
        parametersOf(space)
    }

    override fun reduce(
        state: State,
        action: Action
    ): State = when (action) {
        is UpdateUsername -> state.copy(
            username = action.value,
            isValid = validateLoginCredentials(action.value, state.password)
        )

        is UpdatePassword -> state.copy(
            password = action.value,
            isValid = validateLoginCredentials(state.username, action.value)
        )

        is Login -> state.copy(isBusy = true)
        is LoginError -> state.copy(isLoginError = true, isBusy = false)
        is LoginSuccess, is Cancel -> state.copy(isBusy = false)
        is ErrorClear -> state.copy(isLoginError = false)
        else -> state
    }

    override suspend fun effects(state: State, action: Action) {
        when (action) {
            is Login ->
                loginUseCase(state.username, state.password)
                    .onSuccess { ia ->
                        notify(LoginSuccess(ia))
                    }
                    .onFailure { dispatch(LoginError(it)) }

            is CreateLogin, is Cancel -> notify(action)
            else -> Unit
        }
    }

}
