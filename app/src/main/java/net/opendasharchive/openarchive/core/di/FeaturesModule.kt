package net.opendasharchive.openarchive.core.di

import net.opendasharchive.openarchive.features.internetarchive.internetArchiveModule
import org.koin.dsl.module

val featuresModule = module {
    includes(internetArchiveModule)
    // TODO: have some registry of feature modules
}