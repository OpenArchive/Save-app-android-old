package net.opendasharchive.openarchive.features.settings.passcode.passcode_setup

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

class PasscodeSetupViewModel(
    private val repository: PasscodeRepository,
    private val config: AppConfig
) : ViewModel() {

    private val _passcode = MutableStateFlow("")
    val passcode: StateFlow<String> = _passcode.asStateFlow()

    private val _confirmPasscode = MutableStateFlow("")
    val confirmPasscode: StateFlow<String> = _confirmPasscode.asStateFlow()

    private val _isConfirming = MutableStateFlow(false)
    val isConfirming: StateFlow<Boolean> = _isConfirming.asStateFlow()

    private val _isProcessing = MutableStateFlow(false)
    val isProcessing: StateFlow<Boolean> = _isProcessing.asStateFlow()

    val passcodeLength: Int
        get() = config.passcodeLength

    private val _uiEvent = Channel<PasscodeSetupUiEvent>()
    val uiEvent = _uiEvent.receiveAsFlow()

    fun onNumberClick(number: String) {

        if (_isProcessing.value) return // Block input during processing

        if (_passcode.value.length >= passcodeLength) return

        _passcode.value += number

        if (_passcode.value.length == config.passcodeLength) {
            _isProcessing.value = true
            processPasscodeEntry()
        }

    }

    fun onBackspaceClick() {
        if (_isConfirming.value || _passcode.value.isEmpty()) return
        _passcode.value = _passcode.value.dropLast(1)
    }

    private fun processPasscodeEntry() = viewModelScope.launch {
        if (_isConfirming.value) {
            // Confirmation step
            val passcode = _passcode.value
            delay(100)
            val confirmPasscode = _confirmPasscode.value
            if (passcode == confirmPasscode) {
                val salt = repository.generateSalt()
                val hash = repository.hashPasscode(passcode, salt)
                repository.storePasscodeHashAndSalt(hash, salt)
                _uiEvent.send(PasscodeSetupUiEvent.PasscodeSet)
            } else {
                _uiEvent.send(PasscodeSetupUiEvent.PasscodeDoNotMatch)
                delay(500) // Allow time for shake animation to complete
                reset()
            }
        } else {
            _confirmPasscode.value = _passcode.value
            _passcode.value = ""
            _isConfirming.value = true
            _isProcessing.value = false
        }
    }

    private fun reset() {
        _passcode.value = ""
        _confirmPasscode.value = ""
        _isConfirming.value = false
        _isProcessing.value = false
    }
}

sealed class PasscodeSetupUiEvent {
    data object PasscodeSet : PasscodeSetupUiEvent()
    data object PasscodeDoNotMatch : PasscodeSetupUiEvent()
}