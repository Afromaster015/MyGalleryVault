package id.bayu.mygalleryvault.data.repository

import android.content.Context
import id.bayu.mygalleryvault.core.crypto.VaultSession
import id.bayu.mygalleryvault.core.storage.VaultStorage
import id.bayu.mygalleryvault.data.local.FolderDao
import id.bayu.mygalleryvault.data.local.FolderEntity
import id.bayu.mygalleryvault.data.local.VaultFileDao
import id.bayu.mygalleryvault.data.local.VaultFileEntity
import id.bayu.mygalleryvault.domain.model.SortOption
import id.bayu.mygalleryvault.domain.model.VaultEntry
import id.bayu.mygalleryvault.domain.model.VaultFile
import id.bayu.mygalleryvault.domain.model.VaultFolder
import id.bayu.mygalleryvault.domain.model.VaultStats
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException

class VaultRepository(
    private val context: Context,
    private val fileDao: VaultFileDao,
    private val folderDao: FolderDao,
    private val storage: VaultStorage,
) {

    // ---------- Observe / query ----------

    fun observeEntries(folderId: Long?, sort: Flow<SortOption>): Flow<List<VaultEntry>> =
        combine(folderDao.children(folderId), fileDao.byFolder(folderId), sort) { folders, files, sortOption ->
            val entries = ArrayList<VaultEntry>(folders.size + files.size)
            folders.forEach { entries.add(VaultEntry.Folder(it.toModel())) }
            files.forEach { entries.add(VaultEntry.File(it.toModel())) }
            when (sortOption) {
                SortOption.NAME_ASC -> entries.sortedBy { label(it).lowercase() }
                SortOption.NAME_DESC -> entries.sortedByDescending { label(it).lowercase() }
                SortOption.NEWEST -> entries.sortedByDescending { entryTime(it) }
                SortOption.OLDEST -> entries.sortedBy { entryTime(it) }
                SortOption.LARGEST -> entries.sortedByDescending { fileSize(it) }
                SortOption.SMALLEST -> entries.sortedBy { fileSize(it) }
                SortOption.TYPE -> entries.sortedWith(
                    compareBy<VaultEntry> { it is VaultEntry.File }.thenBy { typeOf(it) }
                        .thenBy { label(it).lowercase() },
                )
            }
        }

    suspend fun searchFiles(query: String): List<VaultFile> =
        if (query.isBlank()) emptyList()
        else fileDao.search(query.trim()).map { it.toModel() }

    suspend fun getFile(id: Long): VaultFile? = fileDao.byId(id)?.toModel()

    /** Raw object name (e.g. for building vault:// playback URIs); internal use. */
    suspend fun encryptedNameOf(fileId: Long): String? =
        fileDao.byId(fileId)?.encryptedName

    suspend fun folderName(id: Long?): String? =
        id?.let { folderDao.byId(it)?.name }

    fun observeAllFolders(): Flow<List<id.bayu.mygalleryvault.data.local.FolderEntity>> =
        folderDao.children(null)

    suspend fun allFoldersOnce(): List<FolderEntity> = folderDao.all()

    /**
     * Cover strip for a folder tile: up to [limit] most recent files directly
     * inside it, media entries preferred so covers stay visual.
     */
    suspend fun folderPreviewFiles(folderId: Long, limit: Int): List<VaultFile> =
        fileDao.recentInFolder(folderId, limit)
            .map { it.toModel() }
            .sortedByDescending { it.isImage || it.isVideo }
            .take(limit)

    // ---------- Folder ops (§16) ----------

    suspend fun createFolder(parentId: Long?, name: String): Long {
        require(name.isNotBlank()) { "Nama folder kosong" }
        return folderDao.insert(
            FolderEntity(parentId = parentId, name = name.trim(), createdAt = System.currentTimeMillis())
        )
    }

    suspend fun renameFolder(id: Long, newName: String) {
        val folder = folderDao.byId(id) ?: return
        folderDao.update(folder.copy(name = newName.trim()))
    }

    suspend fun deleteFolderRecursive(id: Long) {
        val queue = ArrayDeque<Long>()
        queue.add(id)
        while (queue.isNotEmpty()) {
            val current = queue.removeFirst()
            folderDao.childrenOnce(current).forEach { child -> queue.add(child.id) }
            // .first(): a bare Flow.map{} would never run, leaving ciphertext orphaned on disk.
            fileDao.byFolder(current).first().forEach { entity ->
                storage.deleteObject(entity.encryptedName)
                storage.deleteThumbnail(entity.thumbRef)
            }
            fileDao.deleteAllInFolder(current)
            folderDao.delete(current)
        }
    }

    // ---------- Decrypt readers (viewers / share) ----------

    /** Full plaintext bytes; only used for internal viewers, never written to disk. */
    suspend fun readDecryptedBytes(fileId: Long): ByteArray {
        val key = checkNotNull(VaultSession.masterKey) { "Vault terkunci" }
        val entity = fileDao.byId(fileId) ?: throw IOException("File tidak ditemukan")
        return withContext(Dispatchers.IO) { storage.readDecrypted(entity.encryptedName, key) }
    }

    /** Streaming decryption directly to an OutputStream, for large files. */
    suspend fun streamDecryptedTo(fileId: Long, output: java.io.OutputStream) {
        val key = checkNotNull(VaultSession.masterKey) { "Vault terkunci" }
        val entity = fileDao.byId(fileId) ?: throw IOException("File tidak ditemukan")
        withContext(Dispatchers.IO) {
            storage.openDecrypted(entity.encryptedName, key, output)
        }
    }

    /**
     * Streaming decryption straight to a local file without loading the whole
     * plaintext into RAM (PRD §14 - required for large videos).
     */
    suspend fun decryptToFile(fileId: Long, dest: File) {
        val key = checkNotNull(VaultSession.masterKey) { "Vault terkunci" }
        val entity = fileDao.byId(fileId) ?: throw IOException("File tidak ditemukan")
        withContext(Dispatchers.IO) {
            dest.outputStream().buffered().use { out ->
                storage.openDecrypted(entity.encryptedName, key, out)
            }
        }
    }

    suspend fun allFilesOnce(): List<VaultFileEntity> = fileDao.allOnce()

    // ---------- Format v2 migration (§55 Fase 3) ----------

    suspend fun countLegacy(): Int = fileDao.countLegacy()

    /**
     * Re-encrypts one legacy single-stream object into the chunked v2 format.
     * Returns false when the item was already current or the source was
     * missing; on any failure the original row/object stay untouched.
     */
    suspend fun migrateToV2(fileId: Long): Boolean {
        val entity = fileDao.byId(fileId) ?: return false
        if (entity.encryptionVersion >= 2) return false
        val key = checkNotNull(VaultSession.masterKey) { "Vault terkunci" }
        withContext(Dispatchers.IO) {
            val tmp = File.createTempFile("mig", ".tmp", context.cacheDir)
            try {
                tmp.outputStream().buffered().use { out ->
                    storage.openDecrypted(entity.encryptedName, key, out)
                }
                val (newName, _) = storage.importStream(tmp.inputStream().buffered(), key)
                fileDao.swapEncryptedObject(entity.id, newName, 2)
                storage.deleteObject(entity.encryptedName)
            } finally {
                tmp.delete()
            }
        }
        return true
    }

    data class MigrationSummary(val migrated: Int, val failed: List<String>)

    suspend fun migrateAllLegacy(
        onProgress: (done: Int, total: Int) -> Unit,
        onItemError: (name: String, message: String) -> Unit,
    ): MigrationSummary {
        val items = fileDao.allLegacy()
        var migrated = 0
        val failed = mutableListOf<String>()
        items.forEachIndexed { index, entity ->
            try {
                if (migrateToV2(entity.id)) migrated++
            } catch (e: Exception) {
                failed.add(entity.originalName)
                onItemError(entity.originalName, e.message ?: "unknown")
            }
            onProgress(index + 1, items.size)
        }
        return MigrationSummary(migrated, failed)
    }

    // ---------- Subtitles (vault-side matching) ----------

    /** Same-name subtitle sitting next to the video (film.mp4 ↔ film.srt). */
    suspend fun findCompanionSubtitle(videoId: Long): VaultFile? {
        val video = fileDao.byId(videoId) ?: return null
        val base = video.originalName.substringBeforeLast('.', video.originalName)
        return folderSiblings(video.folderId)
            .firstOrNull { candidate ->
                candidate.id != video.id &&
                    subtitleMime(candidate.name) != null &&
                    candidate.name.substringBeforeLast('.', candidate.name)
                        .equals(base, ignoreCase = true)
            }
    }

    /** All subtitle files sharing the video's folder, for the picker sheet. */
    suspend fun listSubtitlesNear(videoId: Long): List<VaultFile> {
        val video = fileDao.byId(videoId) ?: return emptyList()
        return folderSiblings(video.folderId)
            .filter { it.id != videoId && subtitleMime(it.name) != null }
    }

    /** Every subtitle stored anywhere in this vault, sorted by name. */
    suspend fun listAllVaultSubtitles(): List<VaultFile> =
        fileDao.allOnce()
            .filter { subtitleMime(it.originalName) != null }
            .map { it.toModel() }
            .sortedBy { it.name.lowercase() }

    private suspend fun folderSiblings(folderId: Long?): List<VaultFile> =
        if (folderId == null) {
            fileDao.byFolder(null).first()
        } else {
            fileDao.byFolder(folderId).first()
        }.map { it.toModel() }

    // ---------- Delete (§35) ----------

    suspend fun deleteFiles(ids: List<Long>) {
        for (id in ids) {
            val entity = fileDao.byId(id) ?: continue
            storage.deleteObject(entity.encryptedName)
            storage.deleteThumbnail(entity.thumbRef)
            fileDao.delete(id)
        }
    }

    suspend fun moveFileTo(fileId: Long, targetFolderId: Long?) {
        fileDao.moveToFolder(fileId, targetFolderId)
    }

    /**
     * Bulk move for the Gallery selection. A file is a plain parent update. A folder is refused
     * when the target is the folder itself or sits somewhere inside it, because that would cut the
     * branch off the tree and make everything under it unreachable; refusing keeps the vault whole.
     * Returns the folder ids that were refused for that reason.
     */
    suspend fun moveInto(
        fileIds: List<Long>,
        folderIds: List<Long>,
        targetFolderId: Long?,
    ): List<Long> {
        fileIds.forEach { fileDao.moveToFolder(it, targetFolderId) }
        if (folderIds.isEmpty()) return emptyList()
        val folders = folderDao.all().associateBy { it.id }

        fun targetSitsInside(folderId: Long): Boolean {
            var cursor = targetFolderId
            while (cursor != null) {
                if (cursor == folderId) return true
                cursor = folders[cursor]?.parentId
            }
            return false
        }

        val (refused, movable) = folderIds.partition { targetSitsInside(it) }
        movable.forEach { id ->
            folders[id]?.let { folderDao.update(it.copy(parentId = targetFolderId)) }
        }
        return refused
    }

    // ---------- Stats (§33) ----------

    suspend fun stats(): VaultStats = VaultStats(
        totalSizeBytes = fileDao.totalSize(),
        fileCount = fileDao.count(),
        folderCount = folderDao.count(),
    )

    // ---------- Crash recovery (§47) ----------

    suspend fun reconcileStorage() {
        val rows = fileDao.search("")
        val validObjects = mutableSetOf<String>()
        val validThumbs = mutableSetOf<String>()
        rows.forEach {
            validObjects.add(it.encryptedName)
            it.thumbRef?.let { ref -> validThumbs.add(ref) }
        }
        storage.reconcileOrphans(validObjects, validThumbs)
    }

    /**
     * One-time fix for files that were imported with encryptionVersion=1 in DB
     * but are actually v2 on disk (because CryptoEngine.encryptStream writes v2).
     * Corrects the DB version based on the actual file header.
     */
    suspend fun fixLegacyDbRecords() {
        val legacyFiles = fileDao.allLegacy()
        for (entity in legacyFiles) {
            val actualVersion = storage.detectEncryptionVersion(entity.encryptedName)
            if (actualVersion != entity.encryptionVersion) {
                fileDao.setEncryptionVersion(entity.id, actualVersion)
            }
        }
    }

    // ---------- helpers ----------

    private fun VaultFileEntity.toModel() = VaultFile(
        id = id,
        name = originalName,
        mimeType = mimeType,
        size = size,
        folderId = folderId,
        createdAt = createdAt,
        modifiedAt = modifiedAt,
        hasThumbnail = thumbRef != null,
        isImage = mimeType.startsWith("image/"),
        isVideo = mimeType.startsWith("video/"),
        encryptedName = encryptedName,
        encryptionVersion = encryptionVersion,
    )

    private fun FolderEntity.toModel() = VaultFolder(
        id = id, parentId = parentId, name = name,
    )

    private fun label(entry: VaultEntry): String = when (entry) {
        is VaultEntry.Folder -> entry.folder.name
        is VaultEntry.File -> entry.file.name
    }

    private fun entryTime(entry: VaultEntry): Long = when (entry) {
        is VaultEntry.Folder -> 0L
        is VaultEntry.File -> entry.file.createdAt
    }

    private fun fileSize(entry: VaultEntry): Long = when (entry) {
        is VaultEntry.Folder -> 0L
        is VaultEntry.File -> entry.file.size
    }

    private fun typeOf(entry: VaultEntry): String = when (entry) {
        is VaultEntry.Folder -> ""
        is VaultEntry.File -> entry.file.mimeType
    }

    companion object {
        val SUBTITLE_EXTENSIONS = setOf("srt", "vtt", "ass", "ssa", "ttml")

        fun subtitleMime(fileName: String): String? {
            val ext = fileName.substringAfterLast('.', "").lowercase()
            return when (ext) {
                in SUBTITLE_EXTENSIONS -> when (ext) {
                    "srt" -> "application/x-subrip"
                    "vtt" -> "text/vtt"
                    "ass", "ssa" -> "application/x-ssa"
                    "ttml" -> "application/ttml+xml"
                    else -> null
                }

                else -> null
            }
        }
    }
}
