package id.bayu.mygalleryvault.data.local

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(tableName = "vault_files", indices = [Index("folderId"), Index("encryptedName", unique = true)])
data class VaultFileEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val encryptedName: String,
    val originalName: String,
    val mimeType: String,
    val size: Long,
    val folderId: Long?,
    val createdAt: Long,
    val modifiedAt: Long,
    val encryptionVersion: Int,
    val thumbRef: String?,
)

@Entity(tableName = "folders")
data class FolderEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val parentId: Long?,
    val name: String,
    val createdAt: Long,
)

@Entity(tableName = "security_events")
data class SecurityEventEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val eventType: String,
    val timestamp: Long,
    val metadata: String?,
    val acknowledged: Boolean = false,
)

@Entity(tableName = "settings")
data class SettingEntity(
    @PrimaryKey val key: String,
    val value: String,
)
