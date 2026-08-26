package id.bayu.mygalleryvault.data.repository

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.provider.OpenableColumns
import android.webkit.MimeTypeMap
import id.bayu.mygalleryvault.core.crypto.VaultSession
import id.bayu.mygalleryvault.core.playback.VaultMediaDataSource
import id.bayu.mygalleryvault.core.playback.useWithRetriever
import id.bayu.mygalleryvault.core.storage.VaultStorage
import id.bayu.mygalleryvault.data.local.AppDatabase
import id.bayu.mygalleryvault.data.local.FolderEntity
import id.bayu.mygalleryvault.data.local.VaultFileEntity
import id.bayu.mygalleryvault.domain.model.ImportOutcome
import id.bayu.mygalleryvault.domain.model.SortOption
import id.bayu.mygalleryvault.domain.model.VaultEntry
import id.bayu.mygalleryvault.domain.model.VaultFile
import id.bayu.mygalleryvault.domain.model.VaultFolder
import id.bayu.mygalleryvault.domain.model.VaultStats
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException

class VaultRepository(
    private val context: Context,
    private val db: AppDatabase,
    private val storage: VaultStorage,
) {

    private val fileDao = db.vaultFileDao()
    private val folderDao = db.folderDao()

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
            val filesInFolder = mutableListOf<VaultFileEntity>()
            fileDao.byFolder(current).map { filesInFolder.addAll(it) }
            filesInFolder.forEach { entity ->
                storage.deleteObject(entity.encryptedName)
                storage.deleteThumbnail(entity.thumbRef)
            }
            fileDao.deleteAllInFolder(current)
            folderDao.delete(current)
        }
    }

    // ---------- Import (§9, §10, §31) ----------

    @SuppressLint("Recycle")
    suspend fun importUris(uris: List<Uri>, targetFolderId: Long?, moveOriginals: Boolean): ImportOutcome {
        val key = checkNotNull(VaultSession.masterKey) { "Vault terkunci" }
        var succeeded = 0
        val copiedNotMoved = mutableListOf<String>()
        val failed = mutableListOf<String>()

        for (uri in uris) {
            try {
                val displayName = queryDisplayName(uri) ?: "file_${System.currentTimeMillis()}"
                val mime = context.contentResolver.getType(uri)
                    ?: guessMimeFromName(displayName)
                    ?: "application/octet-stream"

                val input = context.contentResolver.openInputStream(uri)
                    ?: throw IOException("Tidak dapat membaca file")
                val (encName, size) = withContext(Dispatchers.IO) {
                    input.use { storage.importStream(it, key) }
                }

                var thumbRef: String? = null
                if (mime.startsWith("image/")) {
                    thumbRef = generateAndStoreThumbnail(uri, key)
                }

                val now = System.currentTimeMillis()
                val rowId = fileDao.insert(
                    VaultFileEntity(
                        encryptedName = encName,
                        originalName = displayName,
                        mimeType = mime,
                        size = size,
                        folderId = targetFolderId,
                        createdAt = now,
                        modifiedAt = now,
                        encryptionVersion = 1,
                        thumbRef = thumbRef,
                    )
                )
                if (mime.startsWith("video/") && thumbRef == null) {
                    // v2 chunked objects allow frame extraction via ranged reads.
                    runCatching { generateVideoThumbnail(rowId) }
                }
                succeeded++

                if (moveOriginals && !deleteOriginal(uri)) {
                    copiedNotMoved.add(displayName)
                }
            } catch (_: Exception) {
                failed.add(uri.lastPathSegment ?: uri.toString())
            }
        }
        return ImportOutcome(uris.size, succeeded, copiedNotMoved, failed)
    }

    // ---------- Downloader (§39) ----------

    /**
     * Downloads [rawUrl] straight through AES-GCM encryption into the vault.
     * The response stream is never written to disk as plaintext (PRD §39);
     * [VaultStorage.importStream] performs the atomic write + integrity verify.
     */
    suspend fun importUrl(rawUrl: String, targetFolderId: Long?): ImportOutcome =
        withContext(Dispatchers.IO) {
            val key = checkNotNull(VaultSession.masterKey) { "Vault terkunci" }
            var connection: java.net.HttpURLConnection? = null
            try {
                val url = java.net.URL(rawUrl)
                if (url.protocol.equals("https", ignoreCase = true).not()) {
                    throw IOException("Hanya URL https yang didukung")
                }
                val conn = url.openConnection() as javax.net.ssl.HttpsURLConnection
                connection = conn
                conn.connectTimeout = 15_000
                conn.readTimeout = 30_000
                conn.instanceFollowRedirects = true
                conn.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android) SecureVault")
                if (conn.responseCode !in 200..299) {
                    throw IOException("Server merespons ${conn.responseCode}")
                }

                val resolved = conn.url.toString()
                val headerMime = conn.contentType?.substringBefore(';')?.trim()
                val dispositionName = parseFilenameFromDisposition(
                    conn.getHeaderField("Content-Disposition")
                )
                val displayName = dispositionName
                    ?: resolved.substringAfterLast('/').substringBefore('?').takeIf { it.isNotBlank() }
                    ?: "download_${System.currentTimeMillis()}"
                val mime = headerMime
                    ?: guessMimeFromName(displayName)
                    ?: "application/octet-stream"

                val input = conn.inputStream
                val (encName, size) = storage.importStream(input, key)

                val now = System.currentTimeMillis()
                fileDao.insert(
                    VaultFileEntity(
                        encryptedName = encName,
                        originalName = displayName,
                        mimeType = mime,
                        size = size,
                        folderId = targetFolderId,
                        createdAt = now,
                        modifiedAt = now,
                        encryptionVersion = 1,
                        thumbRef = null,
                    )
                )
                ImportOutcome(1, 1, emptyList(), emptyList())
            } catch (e: Exception) {
                ImportOutcome(1, 0, emptyList(), listOf(e.message ?: rawUrl))
            } finally {
                runCatching { connection?.disconnect() }
            }
        }

    private fun parseFilenameFromDisposition(disposition: String?): String? {
        if (disposition.isNullOrBlank()) return null
        val match = Regex("filename\\*?=(?:UTF-8''|\")?([^\";]+)", RegexOption.IGNORE_CASE).find(disposition)
        return match?.groupValues?.get(1)
            ?.replace("+", " ")
            ?.let { java.net.URLDecoder.decode(it, "UTF-8") }
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
    }

    // ---------- Export (§34) ----------

    suspend fun exportFile(fileId: Long, destUri: Uri) {
        val key = checkNotNull(VaultSession.masterKey) { "Vault terkunci" }
        val entity = fileDao.byId(fileId) ?: throw IOException("File tidak ditemukan")
        withContext(Dispatchers.IO) {
            context.contentResolver.openOutputStream(destUri)?.use { out ->
                storage.openDecrypted(entity.encryptedName, key, out)
            } ?: throw IOException("Tidak dapat membuka tujuan export")
        }
    }

    /**
     * Bulk-export convenience: creates a document named after the file inside
     * [treeUri] (user-picked directory) and decrypts into it. SAF appends
     * " (1)" automatically on name collisions.
     */
    suspend fun exportIntoDir(fileId: Long, treeUri: Uri): Boolean {
        val entity = fileDao.byId(fileId) ?: return false
        val resolver = context.contentResolver
        val docUri = android.provider.DocumentsContract.createDocument(
            resolver,
            treeUri,
            entity.mimeType.ifBlank { "application/octet-stream" },
            entity.originalName,
        ) ?: return false
        exportFile(fileId, docUri)
        return true
    }

    /** Full plaintext bytes; only used for internal viewers, never written to disk. */
    suspend fun readDecryptedBytes(fileId: Long): ByteArray {
        val key = checkNotNull(VaultSession.masterKey) { "Vault terkunci" }
        val entity = fileDao.byId(fileId) ?: throw IOException("File tidak ditemukan")
        return withContext(Dispatchers.IO) { storage.readDecrypted(entity.encryptedName, key) }
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

    // ---------- Video thumbnails & duration (chunked random access, PRD §14) ----------

    /**
     * Extracts a frame from a stored video using ranged decryption and stores
     * it as an encrypted thumbnail. Works regardless of where the MP4 moov
     * atom sits because [android.media.MediaDataSource] seeks freely over the
     * decrypted view. Safe to call repeatedly; no-op when not applicable.
     */
    suspend fun generateVideoThumbnail(fileId: Long): Boolean {
        val entity = fileDao.byId(fileId) ?: return false
        if (!entity.mimeType.startsWith("video/") || entity.thumbRef != null) return false
        if (entity.encryptionVersion < 2) return false
        val key = checkNotNull(VaultSession.masterKey) { "Vault terkunci" }
        return withContext(Dispatchers.IO) {
            try {
                storage.openRandomReader(entity.encryptedName, key).use { reader ->
                    VaultMediaDataSource(reader).useWithRetriever { retriever ->
                        val frame = retriever.getFrameAtTime(
                            0,
                            android.media.MediaMetadataRetriever.OPTION_CLOSEST_SYNC,
                        ) ?: return@useWithRetriever false
                        val scaled = scaleDown(frame, THUMB_SIZE)
                        val bos = java.io.ByteArrayOutputStream()
                        scaled.compress(Bitmap.CompressFormat.JPEG, 80, bos)
                        if (scaled !== frame) scaled.recycle()
                        frame.recycle()
                        val ref = storage.saveThumbnail(bos.toByteArray(), key)
                        fileDao.setThumbRef(fileId, ref)
                        true
                    }
                }
            } catch (_: Exception) {
                false
            }
        }
    }

    /** Duration in ms via ranged decryption; null when unavailable. */
    suspend fun probeVideoDurationMs(fileId: Long): Long? {
        val entity = fileDao.byId(fileId) ?: return null
        if (!entity.mimeType.startsWith("video/") || entity.encryptionVersion < 2) return null
        val key = checkNotNull(VaultSession.masterKey) { "Vault terkunci" }
        return withContext(Dispatchers.IO) {
            try {
                storage.openRandomReader(entity.encryptedName, key).use { reader ->
                    VaultMediaDataSource(reader).useWithRetriever { retriever ->
                        retriever.extractMetadata(
                            android.media.MediaMetadataRetriever.METADATA_KEY_DURATION
                        )?.toLongOrNull()
                    }
                }
            } catch (_: Exception) {
                null
            }
        }
    }

    private fun scaleDown(bitmap: Bitmap, target: Int): Bitmap {
        val scale = target.toFloat() / maxOf(bitmap.width, bitmap.height)
        return if (scale >= 1f) bitmap else Bitmap.createScaledBitmap(
            bitmap,
            (bitmap.width * scale).toInt().coerceAtLeast(1),
            (bitmap.height * scale).toInt().coerceAtLeast(1),
            true,
        )
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

    // ---------- Thumbnails ----------

    suspend fun getThumbnailBitmap(fileId: Long): Bitmap? {
        val key = VaultSession.masterKey ?: return null
        val entity = fileDao.byId(fileId) ?: return null
        val ref = entity.thumbRef ?: return null
        return withContext(Dispatchers.IO) { storage.loadThumbnail(ref, key) }
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

    private fun queryDisplayName(uri: Uri): String? {
        context.contentResolver.query(
            uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null,
        )?.use { cursor ->
            if (cursor.moveToFirst()) {
                val idx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (idx >= 0) return cursor.getString(idx)
            }
        }
        return null
    }

    private fun guessMimeFromName(name: String): String? {
        val ext = MimeTypeMap.getFileExtensionFromUrl(name)?.lowercase() ?: return null
        return MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext)
    }

    private fun deleteOriginal(uri: Uri): Boolean = try {
        android.provider.DocumentsContract.deleteDocument(context.contentResolver, uri)
    } catch (_: Exception) {
        false
    }

    private fun generateAndStoreThumbnail(uri: Uri, key: javax.crypto.SecretKey): String? {
        return try {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            context.contentResolver.openInputStream(uri)?.use {
                BitmapFactory.decodeStream(it, null, bounds)
            } ?: return null
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

            val targetSize = THUMB_SIZE
            var sample = 1
            while (bounds.outWidth / sample > targetSize * 2 || bounds.outHeight / sample > targetSize * 2) {
                sample *= 2
            }
            val opts = BitmapFactory.Options().apply { inSampleSize = sample }
            val bitmap = context.contentResolver.openInputStream(uri)?.use {
                BitmapFactory.decodeStream(it, null, opts)
            } ?: return null

            val scaled = if (bitmap.width > targetSize || bitmap.height > targetSize) {
                val scale = targetSize.toFloat() / maxOf(bitmap.width, bitmap.height)
                Bitmap.createScaledBitmap(
                    bitmap,
                    (bitmap.width * scale).toInt().coerceAtLeast(1),
                    (bitmap.height * scale).toInt().coerceAtLeast(1),
                    true,
                )
            } else bitmap

            val bytes = java.io.ByteArrayOutputStream().use { bos ->
                scaled.compress(Bitmap.CompressFormat.JPEG, 80, bos)
                bos.toByteArray()
            }
            if (scaled !== bitmap) scaled.recycle()
            bitmap.recycle()
            storage.saveThumbnail(bytes, key)
        } catch (_: Exception) {
            null
        }
    }

    companion object {
        const val THUMB_SIZE = 256
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
