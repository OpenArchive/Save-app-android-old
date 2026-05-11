package net.opendasharchive.openarchive.services.internetarchive.data

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class InternetArchiveLoginRequest(
    val email: String,
    val password: String
) {
    override fun toString(): String = "InternetArchiveLoginRequest(email=$email)"
}

@Serializable
data class InternetArchiveLoginResponse(
    val success: Boolean,
    val values: Values,
    val version: Int
) {
    @Serializable
    data class Values(
        val s3: S3? = null,
        val screenname: String? = null,
        val email: String? = null,
        @SerialName("itemname") val itemName: String? = null,
        val reason: String? = null
    )

    @Serializable
    data class S3(
        val access: String,
        val secret: String
    ) {
        override fun toString(): String = "S3(access=***)"
    }
}

class UnauthenticatedException : Exception("Unauthenticated")
