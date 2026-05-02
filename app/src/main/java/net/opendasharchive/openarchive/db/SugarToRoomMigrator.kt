package net.opendasharchive.openarchive.db

import androidx.room3.RoomDatabase
import com.orm.SugarRecord
import net.opendasharchive.openarchive.core.domain.EvidenceStatus
import net.opendasharchive.openarchive.core.domain.VaultType
import net.opendasharchive.openarchive.core.logger.AppLogger
import net.opendasharchive.openarchive.core.security.VaultCredentialStore
import net.opendasharchive.openarchive.util.DateUtils
import net.opendasharchive.openarchive.util.toLocalDateTime
import net.opendasharchive.openarchive.db.sugar.Collection as SugarCollection
import net.opendasharchive.openarchive.db.sugar.Media as SugarMedia
import net.opendasharchive.openarchive.db.sugar.Project as SugarProject
import net.opendasharchive.openarchive.db.sugar.Space as SugarSpace

/**
 * Standalone synchronous migrator — no Koin dependency.
 *
 * Call this with a temporary [AppDatabase] instance opened directly via
 * [androidx.room3.Room.databaseBuilder] BEFORE [startKoin] so that the Koin
 * bindings can safely target Room from the very first launch.
 */
object SugarToRoomMigrator {

    /**
     * Reads every Sugar ORM record and upserts it into the corresponding Room DAO.
     * Throws on unrecoverable failure; callers should catch and log.
     */
    suspend fun migrate(
        vaultDao: VaultDao,
        archiveDao: ArchiveDao,
        submissionDao: SubmissionDao,
        evidenceDao: EvidenceDao,
        migrationDao: MigrationDao,
        credentialStore: VaultCredentialStore
    ) {
        migrateSpaces(vaultDao, migrationDao, credentialStore)
        migrateProjects(archiveDao, migrationDao)
        migrateCollections(submissionDao, migrationDao)
        migrateMedia(evidenceDao, migrationDao)
        migrationDao.upsert(
            MigrationStateEntity(stage = "DONE", processedCount = 0, totalCount = 0, completedAt = DateUtils.now)
        )
    }

    private suspend fun migrateSpaces(
        vaultDao: VaultDao,
        migrationDao: MigrationDao,
        credentialStore: VaultCredentialStore
    ) {
        val spaces = try {
            SugarSpace.getAll().asSequence().toList()
        } catch (e: Exception) {
            AppLogger.e("SugarToRoomMigrator: migrateSpaces — failed to read Sugar spaces, skipping", e)
            migrationDao.upsert(MigrationStateEntity(stage = "PROJECTS", processedCount = 0, totalCount = 0))
            return
        }
        AppLogger.i("SugarToRoomMigrator: Migrating ${spaces.size} spaces")

        spaces.forEach { space ->
            val vaultId = vaultDao.upsert(
                VaultEntity(
                    id = space.id,
                    type = when (space.tType) {
                        SugarSpace.Type.WEBDAV -> VaultType.PRIVATE_SERVER
                        SugarSpace.Type.INTERNET_ARCHIVE -> VaultType.INTERNET_ARCHIVE
                        SugarSpace.Type.RAVEN -> VaultType.DWEB_STORAGE
                    },
                    name = space.name,
                    username = space.username,
                    displayName = space.displayname,
                    host = space.host,
                    metaData = space.metaData,
                    licenseUrl = space.license,
                    createdAt = DateUtils.now.toLocalDateTime()
                )
            )
            if (space.password.isNotBlank()) {
                credentialStore.putSecret(vaultId, space.password)
            }
        }
        migrationDao.upsert(MigrationStateEntity(stage = "PROJECTS", processedCount = 0, totalCount = 0))
    }

    private suspend fun migrateProjects(archiveDao: ArchiveDao, migrationDao: MigrationDao) {
        val projects = try {
            SugarRecord.findAll(SugarProject::class.java).asSequence().toList()
        } catch (e: Exception) {
            AppLogger.e("SugarToRoomMigrator: migrateProjects — failed to read Sugar projects, skipping", e)
            migrationDao.upsert(MigrationStateEntity(stage = "COLLECTIONS", processedCount = 0, totalCount = 0))
            return
        }
        AppLogger.i("SugarToRoomMigrator: Migrating ${projects.size} projects")

        projects.forEach { project ->
            archiveDao.upsert(
                ArchiveEntity(
                    id = project.id,
                    description = project.description,
                    createdAt = project.created?.time?.toLocalDateTime(),
                    vaultId = project.spaceId ?: -1,
                    archived = project.isArchived,
                    openSubmissionId = project.openCollectionId,
                    licenseUrl = project.licenseUrl,
                    isRemote = false
                )
            )
        }
        migrationDao.upsert(MigrationStateEntity(stage = "COLLECTIONS", processedCount = 0, totalCount = 0))
    }

    private suspend fun migrateCollections(submissionDao: SubmissionDao, migrationDao: MigrationDao) {
        val collections = try {
            SugarRecord.findAll(SugarCollection::class.java).asSequence().toList()
        } catch (e: Exception) {
            AppLogger.e("SugarToRoomMigrator: migrateCollections — failed to read Sugar collections, skipping", e)
            migrationDao.upsert(MigrationStateEntity(stage = "MEDIA", processedCount = 0, totalCount = 0))
            return
        }
        AppLogger.i("SugarToRoomMigrator: Migrating ${collections.size} collections")

        collections.forEach { collection ->
            submissionDao.upsert(
                SubmissionEntity(
                    id = collection.id,
                    archiveId = collection.projectId ?: -1,
                    uploadedAt = collection.uploadDate?.time?.toLocalDateTime(),
                    serverUrl = collection.serverUrl
                )
            )
        }
        migrationDao.upsert(MigrationStateEntity(stage = "MEDIA", processedCount = 0, totalCount = 0))
    }

    private suspend fun migrateMedia(evidenceDao: EvidenceDao, migrationDao: MigrationDao) {
        val mediaList = try {
            SugarRecord.findAll(SugarMedia::class.java).asSequence().toList()
        } catch (e: Exception) {
            AppLogger.e("SugarToRoomMigrator: migrateMedia — failed to read Sugar media, skipping", e)
            return
        }
        AppLogger.i("SugarToRoomMigrator: Migrating ${mediaList.size} media items")

        mediaList.forEach { media ->
            evidenceDao.upsert(
                EvidenceEntity(
                    id = media.id,
                    originalFilePath = media.originalFilePath,
                    mimeType = media.mimeType,
                    createdAt = media.createDate?.time?.toLocalDateTime(),
                    updatedAt = media.updateDate?.time?.toLocalDateTime(),
                    uploadedAt = media.uploadDate?.time?.toLocalDateTime(),
                    serverUrl = media.serverUrl,
                    title = media.title,
                    description = media.description,
                    author = media.author,
                    location = media.location,
                    tags = media.tags,
                    licenseUrl = media.licenseUrl,
                    mediaHashString = media.mediaHashString,
                    status = when (media.sStatus) {
                        SugarMedia.Status.Local -> EvidenceStatus.LOCAL
                        SugarMedia.Status.Queued -> EvidenceStatus.QUEUED
                        SugarMedia.Status.Uploading -> EvidenceStatus.QUEUED
                        SugarMedia.Status.Uploaded -> EvidenceStatus.UPLOADED
                        SugarMedia.Status.Error -> EvidenceStatus.ERROR
                        else -> EvidenceStatus.NEW
                    },
                    statusMessage = media.statusMessage,
                    archiveId = media.projectId,
                    submissionId = media.collectionId,
                    contentLength = media.contentLength,
                    progress = media.progress,
                    flag = media.flag,
                    priority = media.priority
                )
            )
        }
    }
}
