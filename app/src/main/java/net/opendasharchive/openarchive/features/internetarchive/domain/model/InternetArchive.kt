package net.opendasharchive.openarchive.features.internetarchive.domain.model

data class InternetArchive(
    val meta: MetaData,
    val auth: Auth
) {
    data class MetaData(
        val userName: String,
        val screenName: String,
        val email: String,
    )


    data class Auth(
        val access: String,
        val secret: String,
    )
}
