package net.opendasharchive.openarchive.core.di

import net.opendasharchive.openarchive.features.core.dialog.DefaultResourceProvider
import net.opendasharchive.openarchive.features.core.dialog.DialogStateManager
import net.opendasharchive.openarchive.features.core.dialog.ResourceProvider
import org.koin.android.ext.koin.androidApplication
import org.koin.core.module.dsl.viewModel
import org.koin.dsl.module

val coreModule = module {
    // Provide a ResourceProvider using the application context.
    single<ResourceProvider> { DefaultResourceProvider(androidApplication()) }

    // Provide DialogStateManager and let Koin inject the ResourceProvider.
    viewModel { DialogStateManager(get()) }
}


