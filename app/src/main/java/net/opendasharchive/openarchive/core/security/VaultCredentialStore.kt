package net.opendasharchive.openarchive.core.security

interface VaultCredentialStore {
    // S3 secret (derived key after xauthn)
    suspend fun putSecret(vaultId: Long, secret: String)
    suspend fun getSecret(vaultId: Long): String?
    suspend fun hasSecret(vaultId: Long): Boolean
    suspend fun deleteSecret(vaultId: Long)

    // Original login password — stored so the app can silently re-authenticate when S3 keys expire.
    // Encrypted with the same AES-256-GCM + Android Keystore key as the S3 secret.
    // Deleted atomically with the vault on server removal.
    suspend fun putLoginPassword(vaultId: Long, password: String)
    suspend fun getLoginPassword(vaultId: Long): String?
    suspend fun deleteLoginPassword(vaultId: Long)
}

