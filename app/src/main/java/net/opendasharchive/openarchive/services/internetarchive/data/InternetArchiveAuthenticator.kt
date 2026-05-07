package net.opendasharchive.openarchive.services.internetarchive.data

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import net.opendasharchive.openarchive.core.domain.Credentials
import net.opendasharchive.openarchive.core.domain.Vault
import net.opendasharchive.openarchive.core.domain.VaultAuth
import net.opendasharchive.openarchive.core.domain.VaultAuthenticator
import net.opendasharchive.openarchive.core.domain.VaultType
import net.opendasharchive.openarchive.core.infrastructure.client.enqueueResult
import net.opendasharchive.openarchive.core.repositories.SpaceRepository
import net.opendasharchive.openarchive.services.SaveClient
import okhttp3.FormBody
import okhttp3.Request
import java.io.IOException

private const val LOGIN_URI = "https://archive.org/services/xauthn?op=login"
private const val ARCHIVE_API_ENDPOINT = "https://archive.org"
private const val ARCHIVE_S3_ENDPOINT = "https://s3.us.archive.org"

class InternetArchiveAuthenticator(
    private val context: Context,
    private val json: Json,
    private val spaceRepository: SpaceRepository,
) : VaultAuthenticator {

    override suspend fun authenticate(credentials: Credentials): Result<Vault> {
        if (credentials !is Credentials.InternetArchive) {
            return Result.failure(IllegalArgumentException("Invalid credentials type"))
        }

        val authDataResult = try {
            withContext(Dispatchers.IO) {
                SaveClient.get(context, retryOnConnectionFailure = true).enqueueResult(
                    Request.Builder()
                        .url(LOGIN_URI)
                        .post(
                            FormBody.Builder()
                                .add("email", credentials.email)
                                .add("password", credentials.pass).build()
                        )
                        .build()
                ) { response ->
                    if (!response.isSuccessful) {
                        return@enqueueResult Result.failure<LoginIntermediateData>(
                            IOException("IA server error ${response.code}")
                        )
                    }
                    val body = response.body?.string() ?: return@enqueueResult Result.failure(Exception("Empty response body"))
                    val data = json.decodeFromString<InternetArchiveLoginResponse>(body)

                    if (!data.success) {
                        return@enqueueResult Result.failure<LoginIntermediateData>(parseLoginReason(data.values.reason))
                    }

                    val auth = data.values.s3 ?: return@enqueueResult Result.failure<LoginIntermediateData>(Exception("S3 keys missing in response"))

                    Result.success(
                        LoginIntermediateData(
                            access = auth.access,
                            secret = auth.secret,
                            screenName = data.values.screenname ?: "",
                            email = data.values.email ?: ""
                        )
                    )
                }
            }
        } catch (e: Exception) {
            return Result.failure(e)
        }

        return authDataResult.fold(
            onSuccess = { intermediate ->
                // xauthn success already proves credentials are valid — S3 keys came directly
                // from IA's auth server. Skipping the S3 HEAD test here because Tor exit nodes
                // can trigger IA's IP-based rate limiting, making S3 return 403 even with valid
                // credentials, which previously surfaced as a false "invalid credentials" error.
                // testConnectionInternal() is still available for checking existing vault health.
                val metaData = InternetArchiveMetadata(
                    screenName = intermediate.screenName,
                    email = intermediate.email
                )

                Result.success(
                    Vault(
                        type = VaultType.INTERNET_ARCHIVE,
                        name = VaultType.INTERNET_ARCHIVE.friendlyName,
                        username = intermediate.access,
                        password = intermediate.secret,
                        displayName = intermediate.screenName,
                        metaData = json.encodeToString(InternetArchiveMetadata.serializer(), metaData),
                        host = ARCHIVE_API_ENDPOINT
                    )
                )
            },
            onFailure = { Result.failure(it) }
        )
    }

    private data class LoginIntermediateData(
        val access: String,
        val secret: String,
        val screenName: String,
        val email: String
    )

    override suspend fun testConnection(vault: Vault): Result<Unit> {
        return testConnectionInternal(vault.username, vault.password)
    }

    /**
     * Silently re-authenticates using the stored encrypted login password.
     * Called automatically when S3 keys expire (401 during upload) — no user intervention needed.
     * On success, the vault is updated in-place with fresh S3 keys and the new VaultAuth is returned.
     * On failure (bad stored password, account disabled, server error), returns failure — caller
     * should then surface a credential-expired error to the user.
     */
    suspend fun reauthenticate(vaultId: Long): Result<VaultAuth> {
        val vault = spaceRepository.getSpaceById(vaultId)
            ?: return Result.failure(Exception("Vault $vaultId not found"))

        val loginPassword = spaceRepository.getLoginPassword(vaultId)
            ?: return Result.failure(Exception("No stored login credentials for vault $vaultId"))

        val email = runCatching {
            json.decodeFromString<InternetArchiveMetadata>(vault.metaData).email
        }.getOrElse {
            return Result.failure(Exception("Cannot parse stored email for vault $vaultId"))
        }

        if (email.isBlank()) return Result.failure(Exception("Stored email is blank"))

        return authenticate(Credentials.InternetArchive(email = email, pass = loginPassword))
            .mapCatching { newVault ->
                // Preserve vaultId and non-credential fields; update only S3 keys.
                val updated = newVault.copy(
                    licenseUrl = vault.licenseUrl,
                    metaData = vault.metaData
                )
                spaceRepository.updateSpace(vaultId, updated)
                // Return fresh VaultAuth so the caller can retry immediately.
                spaceRepository.getVaultAuth(vaultId)
                    ?: error("getVaultAuth returned null after successful reauthentication")
            }
    }

    private fun parseLoginReason(reason: String?): IllegalArgumentException {
        val message = when (reason?.lowercase()?.trim()) {
            "wrong-password", "wrong_password" -> "Incorrect password"
            "account-not-found", "account_not_found", "bad-fields" -> "Account not found"
            "account-disabled", "account_disabled",
            "account-locked", "account_locked" -> "Account disabled or locked"
            null, "" -> "Login failed"
            else -> reason
        }
        return IllegalArgumentException(message)
    }

    private suspend fun testConnectionInternal(access: String, secret: String): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            // Test against S3 endpoint, not archive.org main site — the web front-end
            // can return 502/504 independently of S3 being healthy. HEAD on the bucket
            // root is cheap and confirms upload credentials are valid.
            SaveClient.get(context, retryOnConnectionFailure = true).enqueueResult(
                Request.Builder()
                    .url("$ARCHIVE_S3_ENDPOINT/")
                    .method("HEAD", null)
                    .addHeader("Authorization", "LOW $access:$secret")
                    .build()
            ) { response ->
                when {
                    response.isSuccessful -> Result.success(Unit)
                    response.code in 500..599 -> Result.failure(IOException("IA server error ${response.code}"))
                    else -> Result.failure(UnauthenticatedException())
                }
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
}

@kotlinx.serialization.Serializable
data class InternetArchiveMetadata(
    val screenName: String,
    val email: String
)
