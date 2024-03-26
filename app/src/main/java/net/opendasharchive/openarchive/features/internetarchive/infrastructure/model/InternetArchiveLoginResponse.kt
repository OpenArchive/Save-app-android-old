package net.opendasharchive.openarchive.features.internetarchive.infrastructure.model

data class InternetArchiveLoginResponse(
    val success: Boolean,
    val values: Values,
    val version: Int,
) {
    data class Values(
        val s3: S3? = null,
        val screenname: String? = null,
        val email: String? = null,
        val itemname: String? = null,
        val reason: String? = null,
    )

    data class S3(
        val access: String,
        val secret: String,
    )
}
