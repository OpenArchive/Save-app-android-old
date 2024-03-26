package net.opendasharchive.openarchive.features.internetarchive.domain.usecase

import com.google.gson.Gson
import net.opendasharchive.openarchive.db.Space
import net.opendasharchive.openarchive.features.internetarchive.domain.model.InternetArchive
import net.opendasharchive.openarchive.features.internetarchive.infrastructure.repository.InternetArchiveRepository

class InternetArchiveLoginUseCase(
    private val repository: InternetArchiveRepository,
    private val gson: Gson,
    private val space: Space,
) {

    suspend operator fun invoke(email: String, password: String): Result<InternetArchive> =
        repository.login(email, password).mapCatching { response ->

            response.auth.let { auth ->
                repository.testConnection(auth).getOrThrow()
                space.username = auth.access
                space.password = auth.secret
            }

            // TODO: use local data source for database
            space.metaData = gson.toJson(response.meta)
            space.save()

            Space.current = space

            response
        }

}
