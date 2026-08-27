package id.bayu.mygalleryvault.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface VaultFileDao {

    @Query("SELECT * FROM vault_files WHERE folderId IS :folderId")
    fun byFolder(folderId: Long?): Flow<List<VaultFileEntity>>

    @Query("SELECT * FROM vault_files WHERE originalName LIKE '%' || :query || '%' ORDER BY originalName COLLATE NOCASE")
    suspend fun search(query: String): List<VaultFileEntity>

    @Query("SELECT * FROM vault_files WHERE folderId IS :folderId ORDER BY createdAt DESC LIMIT :limit")
    suspend fun recentInFolder(folderId: Long?, limit: Int): List<VaultFileEntity>

    @Query("SELECT * FROM vault_files ORDER BY createdAt DESC")
    fun all(): Flow<List<VaultFileEntity>>

    @Query("SELECT * FROM vault_files WHERE id = :id")
    suspend fun byId(id: Long): VaultFileEntity?

    @Query("SELECT * FROM vault_files")
    suspend fun allOnce(): List<VaultFileEntity>

    @Query("SELECT * FROM vault_files WHERE originalName = :name AND folderId IS :folderId LIMIT 1")
    suspend fun byNameInFolder(name: String, folderId: Long?): VaultFileEntity?

    @Query("SELECT COUNT(*) FROM vault_files WHERE encryptionVersion < 2")
    suspend fun countLegacy(): Int

    @Query("SELECT * FROM vault_files WHERE encryptionVersion < 2")
    suspend fun allLegacy(): List<VaultFileEntity>

    @Query("UPDATE vault_files SET encryptedName = :newName, encryptionVersion = :version WHERE id = :id")
    suspend fun swapEncryptedObject(id: Long, newName: String, version: Int)

    @Query("UPDATE vault_files SET encryptionVersion = :version WHERE id = :id")
    suspend fun setEncryptionVersion(id: Long, version: Int)

    @Query("SELECT * FROM vault_files WHERE encryptionVersion < 2 AND mimeType LIKE 'video/%'")
    suspend fun allLegacyVideos(): List<VaultFileEntity>

    @Query("UPDATE vault_files SET thumbRef = :ref WHERE id = :id")
    suspend fun setThumbRef(id: Long, ref: String?)

    @Query("SELECT COUNT(*) FROM vault_files")
    suspend fun count(): Int

    @Query("SELECT COALESCE(SUM(size), 0) FROM vault_files")
    suspend fun totalSize(): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(file: VaultFileEntity): Long

    @Update
    suspend fun update(file: VaultFileEntity)

    @Query("UPDATE vault_files SET folderId = :folderId WHERE id = :fileId")
    suspend fun moveToFolder(fileId: Long, folderId: Long?)

    @Query("DELETE FROM vault_files WHERE id = :id")
    suspend fun delete(id: Long)

    @Query("DELETE FROM vault_files WHERE folderId = :folderId")
    suspend fun deleteAllInFolder(folderId: Long)
}

@Dao
interface FolderDao {

    @Query("SELECT * FROM folders WHERE parentId IS :parentId ORDER BY name COLLATE NOCASE")
    fun children(parentId: Long?): Flow<List<FolderEntity>>

    @Query("SELECT * FROM folders WHERE parentId IS :parentId")
    suspend fun childrenOnce(parentId: Long?): List<FolderEntity>

    @Query("SELECT * FROM folders WHERE id = :id")
    suspend fun byId(id: Long): FolderEntity?

    @Query("SELECT * FROM folders WHERE parentId IS :parentId AND name = :name COLLATE NOCASE LIMIT 1")
    suspend fun byName(parentId: Long?, name: String): FolderEntity?

    @Query("SELECT * FROM folders ORDER BY name COLLATE NOCASE")
    suspend fun all(): List<FolderEntity>

    @Query("SELECT COUNT(*) FROM folders")
    suspend fun count(): Int

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(folder: FolderEntity): Long

    @Update
    suspend fun update(folder: FolderEntity)

    @Query("DELETE FROM folders WHERE id = :id")
    suspend fun delete(id: Long)
}

@Dao
interface SecurityEventDao {

    @Query("SELECT * FROM security_events ORDER BY timestamp DESC LIMIT :limit")
    fun recent(limit: Int = 20): Flow<List<SecurityEventEntity>>

    @Query("SELECT * FROM security_events ORDER BY timestamp DESC LIMIT :limit")
    suspend fun recentOnce(limit: Int): List<SecurityEventEntity>

    @Query(
        "SELECT * FROM security_events WHERE eventType = 'BREAKIN_ALERT' AND acknowledged = 0 " +
            "ORDER BY timestamp DESC"
    )
    suspend fun unacknowledgedBreakIns(): List<SecurityEventEntity>

    @Insert
    suspend fun insert(event: SecurityEventEntity): Long

    @Query("UPDATE security_events SET acknowledged = 1 WHERE id IN (:ids)")
    suspend fun acknowledge(ids: List<Long>)

    @Query("DELETE FROM security_events")
    suspend fun clear()
}

@Dao
interface SettingsDao {

    @Query("SELECT value FROM settings WHERE `key` = :key")
    suspend fun get(key: String): String?

    @Query("SELECT * FROM settings")
    fun observeAll(): Flow<List<SettingEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun put(setting: SettingEntity)

    @Query("DELETE FROM settings WHERE `key` = :key")
    suspend fun delete(key: String)
}
