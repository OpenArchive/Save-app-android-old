package net.opendasharchive.openarchive.features.internetarchive.infrastructure.repository

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import net.opendasharchive.openarchive.features.internetarchive.domain.model.InternetArchive
import net.opendasharchive.openarchive.features.internetarchive.infrastructure.datasource.InternetArchiveLocalSource
import net.opendasharchive.openarchive.features.internetarchive.infrastructure.datasource.InternetArchiveRemoteSource
import net.opendasharchive.openarchive.features.internetarchive.infrastructure.mapping.InternetArchiveMapper
import net.opendasharchive.openarchive.features.internetarchive.infrastructure.model.InternetArchiveLoginRequest
import net.opendasharchive.openarchive.features.internetarchive.infrastructure.model.UnauthenticatedException

class InternetArchiveRepository(
    private val remoteSource: InternetArchiveRemoteSource,
    private val localSource: InternetArchiveLocalSource,
    private val mapper: InternetArchiveMapper
) {
    suspend fun login(email: String, password: String): Result<InternetArchive> =
        withContext(Dispatchers.IO) {
            remoteSource.login(
                InternetArchiveLoginRequest(email, password)
            ).mapCatching { response ->
                if (response.success.not()) {
                    throw IllegalArgumentException(response.values.reason)
                }
                when (response.version) {
                    else -> mapper(response.values)
                }
            }.onSuccess { localSource.set(it) }
        }

    suspend fun testConnection(auth: InternetArchive.Auth): Result<Unit> =
        withContext(Dispatchers.IO) {
            remoteSource.testConnection(auth)
                .mapCatching { if (!it) throw UnauthenticatedException() }
        }
}
