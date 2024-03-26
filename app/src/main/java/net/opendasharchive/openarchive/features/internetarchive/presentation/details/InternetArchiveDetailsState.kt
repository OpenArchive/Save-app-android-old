package net.opendasharchive.openarchive.features.internetarchive.presentation.details

import androidx.compose.runtime.Immutable

@Immutable
data class InternetArchiveDetailsState(
    val userName: String = "",
    val screenName: String = "",
    val email: String = "",
)

