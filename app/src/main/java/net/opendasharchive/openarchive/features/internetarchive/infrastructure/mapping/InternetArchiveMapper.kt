package net.opendasharchive.openarchive.features.internetarchive.infrastructure.mapping

import net.opendasharchive.openarchive.features.internetarchive.domain.model.InternetArchive
import net.opendasharchive.openarchive.features.internetarchive.infrastructure.model.InternetArchiveLoginResponse

class InternetArchiveMapper {

    private operator fun invoke(response: InternetArchiveLoginResponse.S3) = InternetArchive.Auth(
        access = response.access, secret = response.secret
    )

    operator fun invoke(response: InternetArchiveLoginResponse.Values) = InternetArchive(
        meta = InternetArchive.MetaData(
            userName = response.itemname ?: "",
            email = response.email ?: "",
            screenName = response.screenname ?: ""
        ),
        auth = response.s3?.let { invoke(it) } ?: InternetArchive.Auth("", "")
    )
}
