package net.opendasharchive.openarchive.features.settings.passcode.passcode_entry

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import net.opendasharchive.openarchive.features.settings.passcode.AppConfig
import net.opendasharchive.openarchive.features.settings.passcode.PasscodeRepository

class PasscodeEntryViewModel(
    private val repository: PasscodeRepository,
    private val config: AppConfig
) : ViewModel() {

    private val _passcode = MutableStateFlow("")
    val passcode: StateFlow<String> = _passcode.asStateFlow()

    private val _isCheckingPasscode = MutableStateFlow(false)
    val isCheckingPasscode: StateFlow<Boolean> = _isCheckingPasscode.asStateFlow()

    private val _uiEvent = Channel<PasscodeEntryUiEvent>()
    val uiEvent = _uiEvent.receiveAsFlow()

    val passcodeLength: Int
        get() = config.passcodeLength

    fun onNumberClick(number: String) {

        if (_isCheckingPasscode.value) return

        if (_passcode.value.length >= config.passcodeLength) return

        _passcode.value += number

        if (_passcode.value.length == config.passcodeLength) {
            _isCheckingPasscode.value = true
            checkPasscode()
        }
    }

    fun onBackspaceClick() {
        if (_isCheckingPasscode.value || _passcode.value.isEmpty()) return
        _passcode.value = _passcode.value.dropLast(1)
    }

    private fun checkPasscode() = viewModelScope.launch {
        val currentPasscode = _passcode.value
        delay(200)
        val (passcodeHash, passcodeSalt) = repository.getPasscodeHashAndSalt()

        if (passcodeHash != null && passcodeSalt != null) {
            val hash = repository.hashPasscode(currentPasscode, passcodeSalt)
            if (hash.contentEquals(passcodeHash)) {
                repository.resetFailedAttempts()
                _uiEvent.send(PasscodeEntryUiEvent.Success)
            } else {
                repository.recordFailedAttempt()
                val remainingAttempts: Int? = if (config.maxRetryLimitEnabled) {
                    repository.getRemainingAttempts()
                } else null

                if (repository.isLockedOut()) {
                    _uiEvent.send(PasscodeEntryUiEvent.LockedOut)
                } else {
                    _uiEvent.send(PasscodeEntryUiEvent.IncorrectPasscode(remainingAttempts))
                }
            }
        } else {
            _uiEvent.send(PasscodeEntryUiEvent.PasscodeNotSet)
        }

        _passcode.value = ""
        _isCheckingPasscode.value = false

    }
}

sealed class PasscodeEntryUiEvent {
    data object Success : PasscodeEntryUiEvent()
    data class IncorrectPasscode(val remainingAttempts: Int? = null) : PasscodeEntryUiEvent()
    data object PasscodeNotSet : PasscodeEntryUiEvent()
    data object LockedOut : PasscodeEntryUiEvent()
}