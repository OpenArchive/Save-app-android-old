package net.opendasharchive.openarchive.core.di

import net.opendasharchive.openarchive.features.internetarchive.internetArchiveModule
import net.opendasharchive.openarchive.features.settings.passcode.AppConfig
import net.opendasharchive.openarchive.features.settings.passcode.passcode_entry.PasscodeEntryViewModel
import net.opendasharchive.openarchive.features.settings.passcode.PasscodeRepository
import net.opendasharchive.openarchive.features.settings.passcode.passcode_setup.PasscodeSetupViewModel
import org.koin.core.module.dsl.viewModel
import org.koin.dsl.module

val featuresModule = module {
    includes(internetArchiveModule)
    // TODO: have some registry of feature modules

    single {
        AppConfig(
            passcodeLength = 6,
            enableHapticFeedback = true,
            maxRetryLimitEnabled = false,
            biometricAuthEnabled = false,
            maxFailedAttempts = 5
        )
    }

    single { AppConfig() }
    single { PasscodeRepository(get(), get()) }
    viewModel { PasscodeEntryViewModel(get(), get()) }
    viewModel { PasscodeSetupViewModel(get(), get()) }
}