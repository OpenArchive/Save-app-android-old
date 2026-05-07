package net.opendasharchive.openarchive.core.repositories

import kotlinx.coroutines.flow.Flow
import net.opendasharchive.openarchive.core.domain.VaultAuth
import net.opendasharchive.openarchive.core.domain.Vault

interface SpaceRepository {
    suspend fun getSpaces(): List<Vault>
    fun observeSpaces(): Flow<List<Vault>>
    fun observeHasDwebSpace(): Flow<Boolean>
    suspend fun getCurrentSpace(): Vault?
    fun observeCurrentSpace(): Flow<Vault?>
    suspend fun setCurrentSpace(id: Long)
    fun observeSpace(id: Long): Flow<Vault?>

    suspend fun getSpaceById(id: Long): Vault?
    suspend fun getVaultAuth(vaultId: Long): VaultAuth?
    suspend fun updateSpace(vaultId: Long, vault: Vault): Boolean
    suspend fun addSpace(vault: Vault): Long
    suspend fun deleteSpace(id: Long): Boolean

    // Stores the original login password encrypted (AES-256-GCM + Android Keystore).
    // Used for silent re-authentication when S3 keys expire, without user intervention.
    // Deleted atomically with the vault in deleteSpace().
    suspend fun storeLoginPassword(vaultId: Long, password: String)
    suspend fun getLoginPassword(vaultId: Long): String?
}
