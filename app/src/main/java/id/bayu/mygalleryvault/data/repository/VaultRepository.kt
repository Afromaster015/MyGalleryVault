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
import id.bayu.mygalleryvault.domain.model.CancelledSignal
import id.bayu.mygalleryvault.domain.model.ImportOutcome
import id.bayu.mygalleryvault.domain.model.SortOption
import id.bayu.mygalleryvault.domain.model.TransferCancelledException
import id.bayu.mygalleryvault.domain.model.TransferProgress
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
    suspend fun importUris(
        uris: List<Uri>,
        targetFolderId: Long?,
        moveOriginals: Boolean,
        onProgress: ((TransferProgress) -> Unit)? = null,
        isCancelled: (() -> Boolean)? = null,
    ): ImportOutcome {
        val key = checkNotNull(VaultSession.masterKey) { "Vault terkunci" }
        var succeeded = 0
        val copiedNotMoved = mutableListOf<String>()
        val failed = mutableListOf<String>()

        // Resolve names/sizes up front so the (n/total) markers stay accurate even
        // when a middle item fails or is skipped.
        val resolved: List<Triple<Uri, String, Long>> = uris.map { uri ->
            val name = queryDisplayName(uri) ?: "file_${System.currentTimeMillis()}"
            Triple(uri, name, querySize(uri))
        }
        val total = resolved.size

        for ((index, res) in resolved.withIndex()) {
            val (uri, displayName, declaredSize) = res
            try {
                if (isCancelled?.invoke() == true) throw CancelledSignal()
                fun publish(done: Long, size: Long) {
                    onProgress?.invoke(
                        TransferProgress(
                            kind = id.bayu.mygalleryvault.domain.model.TransferKind.IMPORT,
                            totalItems = total,
                            completedItems = index,
                            currentItemName = displayName,
                            currentItemIndex = index + 1,
                            itemBytesDone = done,
                            itemBytesTotal = size,
                        )
                    )
                }
                publish(0, declaredSize)

                val mime = context.contentResolver.getType(uri)
                    ?: guessMimeFromName(displayName)
                    ?: "application/octet-stream"

                val input = context.contentResolver.openInputStream(uri)
                    ?: throw IOException("Tidak dapat membaca file")
                val trackedInput = when {
                    onProgress != null || isCancelled != null ->
                        ProgressInputStream(input, declaredSize, ::publish, isCancelled)

                    else -> input
                }
                val (encName, size) = withContext(Dispatchers.IO) {
                    trackedInput.use { storage.importStream(it, key) }
                }
                publish(size, size)

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
                        encryptionVersion = 2,
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
            } catch (c: CancelledSignal) {
                throw TransferCancelledException(
                    succeeded,
                    "Import dibatalkan ($succeeded dari $total file selesai)",
                )
            } catch (_: Exception) {
                failed.add(displayName)
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
                        encryptionVersion = 2,
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

    suspend fun exportFile(
        fileId: Long,
        destUri: Uri,
        onItemProgress: ((done: Long, total: Long) -> Unit)? = null,
        isCancelled: (() -> Boolean)? = null,
    ) {
        val key = checkNotNull(VaultSession.masterKey) { "Vault terkunci" }
        val entity = fileDao.byId(fileId) ?: throw IOException("File tidak ditemukan")
        try {
            withContext(Dispatchers.IO) {
                context.contentResolver.openOutputStream(destUri)?.buffered()?.use { raw ->
                    writeDecryptedTracked(entity, key, raw, onItemProgress, isCancelled)
                } ?: throw IOException("Tidak dapat membuka tujuan export")
            }
        } catch (c: CancelledSignal) {
            throw TransferCancelledException(0, "Export dibatalkan")
        }
    }

    /**
     * Streaming decryption to [rawOut] with optional realtime byte progress and
     * cooperative cancellation. The cancellation signal surfaces from inside
     * [storage.openDecrypted] so even huge videos abort mid-stream.
     */
    private fun writeDecryptedTracked(
        entity: VaultFileEntity,
        key: javax.crypto.SecretKey,
        rawOut: java.io.OutputStream,
        onItemProgress: ((Long, Long) -> Unit)?,
        isCancelled: (() -> Boolean)?,
    ) {
        if (onItemProgress == null && isCancelled == null) {
            storage.openDecrypted(entity.encryptedName, key, rawOut)
            return
        }
        val tracked =
            when {
                onItemProgress != null -> {
                    val pub = ByteProgressPublisher(onItemProgress)
                    ProgressOutputStream(rawOut, entity.size, pub, isCancelled)
                }

                else -> CancelCheckingOutputStream(rawOut, isCancelled!!)
            }
        storage.openDecrypted(entity.encryptedName, key, tracked)
        if (onItemProgress != null && entity.size > 0) onItemProgress(entity.size, entity.size)
    }

    /**
     * Export a file directly to the public Downloads folder using MediaStore.
     * Works reliably on emulators and devices where SAF CreateDocument may fail.
     * Returns the content URI of the saved file, or throws on failure.
     */
    suspend fun exportToDownloads(
        fileId: Long,
        displayName: String,
        onItemProgress: ((done: Long, total: Long) -> Unit)? = null,
        isCancelled: (() -> Boolean)? = null,
    ): Uri {
        val key = checkNotNull(VaultSession.masterKey) { "Vault terkunci" }
        val entity = fileDao.byId(fileId) ?: throw IOException("File tidak ditemukan")
        try {
            return withContext(Dispatchers.IO) {
                val resolver = context.contentResolver
                val mimeType = entity.mimeType.ifBlank { "application/octet-stream" }
                val safeName = safeExportName(displayName)

                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
                    val values = android.content.ContentValues().apply {
                        put(android.provider.MediaStore.Downloads.DISPLAY_NAME, safeName)
                        put(android.provider.MediaStore.Downloads.MIME_TYPE, mimeType)
                        put(android.provider.MediaStore.Downloads.IS_PENDING, 1)
                    }
                    val uri = try {
                        resolver.insert(
                            android.provider.MediaStore.Downloads.EXTERNAL_CONTENT_URI, values
                        )
                    } catch (e: Exception) {
                        throw IOException("Gagal membuat file di Downloads: ${e.message}")
                    } ?: throw IOException("Tidak dapat membuat file di Downloads")

                    try {
                        resolver.openOutputStream(uri)?.buffered()?.use { raw ->
                            writeDecryptedTracked(entity, key, raw, onItemProgress, isCancelled)
                        } ?: throw IOException("Tidak dapat menulis ke Downloads")

                        values.clear()
                        values.put(android.provider.MediaStore.Downloads.IS_PENDING, 0)
                        resolver.update(uri, values, null, null)
                    } catch (e: Exception) {
                        runCatching { resolver.delete(uri, null, null) }
                        throw e
                    }
                    uri
                } else {
                    val dir = java.io.File(context.getExternalFilesDir(null), "Download")
                    if (!dir.exists()) dir.mkdirs()
                    var outFile = java.io.File(dir, safeName)
                    var n = 1
                    while (outFile.exists()) {
                        val base = safeName.substringBeforeLast('.', safeName)
                        val ext = safeName.substringAfterLast('.', "")
                        outFile = java.io.File(dir, "$base($n)." + if (ext.isBlank()) "bin" else ext)
                        n++
                    }
                    outFile.outputStream().buffered().use { raw ->
                        writeDecryptedTracked(entity, key, raw, onItemProgress, isCancelled)
                    }
                    android.net.Uri.fromFile(outFile)
                }
            }
        } catch (c: CancelledSignal) {
            throw TransferCancelledException(0, "Export dibatalkan")
        }
    }

    /**
     * Bulk-export convenience: creates a document named after the file inside
     * [treeUri] (user-picked directory) and decrypts into it. SAF appends
     * " (1)" automatically on name collisions.
     *
     * NOTE: [DocumentsContract.createDocument] rejects the raw tree URI on
     * several providers ("Invalid URI", e.g. externalstorage.documents with a
     * folder name containing spaces). The tree URI must first be converted to
     * the root document URI via buildDocumentUriUsingTree.
     */
    suspend fun exportIntoDir(
        fileId: Long,
        treeUri: Uri,
        onItemProgress: ((done: Long, total: Long) -> Unit)? = null,
        isCancelled: (() -> Boolean)? = null,
    ): Boolean {
        val entity = fileDao.byId(fileId) ?: return false
        val resolver = context.contentResolver

        // Convert ".../tree/<rootId>" -> ".../document/<rootId>".
        val parentDocUri: Uri = if (treeUri.pathSegments.firstOrNull() == "tree") {
            try {
                val treeDocId = android.provider.DocumentsContract.getTreeDocumentId(treeUri)
                if (treeDocId.isNullOrBlank()) return false
                android.provider.DocumentsContract.buildDocumentUriUsingTree(treeUri, treeDocId)
            } catch (_: Exception) {
                return false
            }
        } else {
            treeUri
        }

        val docUri = runCatching {
            android.provider.DocumentsContract.createDocument(
                resolver,
                parentDocUri,
                entity.mimeType.ifBlank { "application/octet-stream" },
                safeExportName(entity.originalName),
            )
        }.getOrNull() ?: return false
        try {
            exportFile(fileId, docUri, onItemProgress, isCancelled)
            return true
        } catch (c: CancelledSignal) {
            // Never leave a half-written plaintext document in a public directory.
            runCatching { android.provider.DocumentsContract.deleteDocument(resolver, docUri) }
            throw TransferCancelledException(0, "Export dibatalkan")
        } catch (e: Exception) {
            runCatching { android.provider.DocumentsContract.deleteDocument(resolver, docUri) }
            throw e
        }
    }

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

    // ---------- Video thumbnails & duration (chunked random access, PRD §14) ----------

    /**
     * Extracts a frame from a stored video using ranged decryption and stores
     * it as an encrypted thumbnail. Works regardless of where the MP4 moov
     * atom sits because [android.media.MediaDataSource] seeks freely over the
     * decrypted view. Safe to call repeatedly; no-op when not applicable.
     */
    /**
     * When a thumbRef is present but its blob cannot be decrypted/decoded, the
     * reference is stale (older bug or partial write); delete it so generation
     * below can run. Returns true when a healthy thumbnail already exists.
     */
    private suspend fun hasHealthyThumbnail(entity: VaultFileEntity, key: javax.crypto.SecretKey): Boolean {
        val ref = entity.thumbRef ?: return false
        if (storage.loadThumbnail(ref, key) != null) return true
        storage.deleteThumbnail(ref)
        fileDao.setThumbRef(entity.id, null)
        return false
    }

    suspend fun generateVideoThumbnail(fileId: Long): Boolean {
        val entity0 = fileDao.byId(fileId) ?: return false
        if (!entity0.mimeType.startsWith("video/")) return false
        val key = VaultSession.masterKey ?: return false
        // A stale/corrupt thumbnail must not wedge regeneration forever.
        if (hasHealthyThumbnail(entity0, key)) return false
        val entity = entity0
        // Auto-detect actual format from file header instead of trusting DB version
        val actualVersion = storage.detectEncryptionVersion(entity.encryptedName)
        if (actualVersion < 2) {
            android.util.Log.d("SV_Thumb", "vthumb id=$fileId skip: v$actualVersion")
            return false
        }
        // MediaMetadataRetriever is unreliable (native >2 GiB truncation / OOM)
        // on very large sources - skip rather than risk a process crash.
        val container = storage.objectSize(entity.encryptedName)
        if (container > MAX_RETRIEVER_SOURCE_BYTES) {
            android.util.Log.d(
                "SV_Thumb", "vthumb id=$fileId skip: $container bytes > retriever cap"
            )
            return false
        }
        android.util.Log.d("SV_Thumb", "vthumb id=$fileId start size=$container")
        return withContext(Dispatchers.IO) {
            try {
                storage.openRandomReader(entity.encryptedName, key).use { reader ->
                    VaultMediaDataSource(reader).useWithRetriever { retriever ->
                        val frame = if (android.os.Build.VERSION.SDK_INT >= 27) {
                            // Bound decoder memory on high-resolution sources.
                            retriever.getScaledFrameAtTime(
                                0,
                                android.media.MediaMetadataRetriever.OPTION_CLOSEST_SYNC,
                                THUMB_SIZE,
                                THUMB_SIZE,
                            )
                        } else {
                            retriever.getFrameAtTime(
                                0,
                                android.media.MediaMetadataRetriever.OPTION_CLOSEST_SYNC,
                            )
                        } ?: run {
                            android.util.Log.d("SV_Thumb", "vthumb id=$fileId frame=null")
                            return@useWithRetriever false
                        }
                        val ref = storeThumbnailBitmap(frame, key)
                        fileDao.setThumbRef(fileId, ref)
                        android.util.Log.d("SV_Thumb", "vthumb id=$fileId OK")
                        true
                    }
                }
            } catch (t: Throwable) {
                android.util.Log.d(
                    "SV_Thumb",
                    "vthumb id=$fileId FAIL ${t.javaClass.simpleName}: ${t.message}",
                )
                false
            }
        }
    }

    /** Duration in ms via ranged decryption; null when unavailable. */
    suspend fun probeVideoDurationMs(fileId: Long): Long? {
        val entity = fileDao.byId(fileId) ?: return null
        if (!entity.mimeType.startsWith("video/")) return null
        // Lock can race this call (auto-lock between tap and effect); a locked
        // vault simply means "cannot probe now" - ExoPlayer's timeline fills
        // the duration after prepare() anyway.
        val key = VaultSession.masterKey ?: return null
        // Auto-detect actual format from file header instead of trusting DB version
        val actualVersion = storage.detectEncryptionVersion(entity.encryptedName)
        if (actualVersion < 2) return null
        // Giant files: let ExoPlayer's own timeline provide the duration after
        // prepare() instead of touching the platform retriever.
        if (storage.objectSize(entity.encryptedName) > MAX_RETRIEVER_SOURCE_BYTES) return null
        android.util.Log.d("SV_Player", "duration probe id=$fileId via retriever")
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

    /** Downscales, JPEG-compresses, encrypts and persists a thumbnail; recycles inputs. */
    private fun storeThumbnailBitmap(bitmap: Bitmap, key: javax.crypto.SecretKey): String {
        val scaled = scaleDown(bitmap, THUMB_SIZE)
        val bos = java.io.ByteArrayOutputStream()
        scaled.compress(Bitmap.CompressFormat.JPEG, 80, bos)
        if (scaled !== bitmap) scaled.recycle()
        bitmap.recycle()
        return storage.saveThumbnail(bos.toByteArray(), key)
    }

    /** Upper bound for decrypting an image fully into RAM just to build its thumbnail. */
    private val MAX_THUMB_SOURCE_BYTES = 128L * 1024 * 1024

    /**
     * Above this container size the platform MediaMetadataRetriever tends to
     * crash (native >2 GiB truncation on many builds); playback itself keeps
     * working through ExoPlayer + [VaultDataSource].
     */
    private val MAX_RETRIEVER_SOURCE_BYTES = 2_000_000_000L

    /**
     * Builds the missing image thumbnail on demand from the stored encrypted
     * object (used when an import-time decode failed or legacy rows predate it).
     */
    suspend fun generateImageThumbnail(fileId: Long): Boolean {
        val entity0 = fileDao.byId(fileId) ?: return false
        if (!entity0.mimeType.startsWith("image/")) return false
        if (entity0.size <= 0 || entity0.size > MAX_THUMB_SOURCE_BYTES) return false
        val key = VaultSession.masterKey ?: return false
        // A stale/corrupt thumbnail must not wedge regeneration forever.
        if (hasHealthyThumbnail(entity0, key)) return false
        val entity = entity0
        return withContext(Dispatchers.IO) {
            try {
                val bytes = storage.readDecrypted(entity.encryptedName, key)
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
                if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
                    return@withContext false
                }
                var sample = 1
                while (bounds.outWidth / sample > THUMB_SIZE * 2 || bounds.outHeight / sample > THUMB_SIZE * 2) {
                    sample *= 2
                }
                val bmp = BitmapFactory.decodeByteArray(
                    bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample },
                ) ?: return@withContext false
                bytes.fill(0)
                val ref = storeThumbnailBitmap(bmp, key)
                fileDao.setThumbRef(fileId, ref)
                true
            } catch (_: Exception) {
                false
            }
        }
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

    /**
     * Generate missing video thumbnails for v2 files that don't have one yet.
     * Must be called after vault is unlocked (needs masterKey).
     */
    suspend fun generateMissingVideoThumbnails() {
        val allFiles = fileDao.allOnce()
        for (entity in allFiles) {
            if (entity.mimeType.startsWith("video/") && entity.thumbRef == null) {
                runCatching { generateVideoThumbnail(entity.id) }
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

    /** Declared byte size from SAF metadata; -1 when the provider does not report it. */
    private fun querySize(uri: Uri): Long =
        context.contentResolver.query(
            uri, arrayOf(OpenableColumns.SIZE), null, null, null,
        )?.use { cursor ->
            if (cursor.moveToFirst()) {
                val idx = cursor.getColumnIndex(OpenableColumns.SIZE)
                if (idx >= 0 && !cursor.isNull(idx)) return cursor.getLong(idx)
            }
            -1L
        } ?: -1L

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

        /** Removes characters that make SAF/MediaStore reject a display name. */
        fun safeExportName(name: String): String {
            val cleaned = name.replace(Regex("[\\\\/:*?\"<>|\\x00-\\x1F]"), "_").trim()
            return cleaned.ifBlank { "file_${System.currentTimeMillis()}" }
        }

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

/** Throttles high-frequency byte callbacks to a UI-friendly rate. */
private class ByteProgressPublisher(private val sink: (done: Long, total: Long) -> Unit) {

    private var lastMs = 0L
    private var lastFrac = -1f

    operator fun invoke(done: Long, total: Long) {
        val now = android.os.SystemClock.elapsedRealtime()
        if (total > 0 && done >= total) {
            lastMs = now
            lastFrac = 1f
            sink(done, total)
            return
        }
        if (total > 0) {
            val frac = done.toFloat() / total
            if (now - lastMs < THROTTLE_MS &&
                (lastFrac < 0f || frac - lastFrac < MIN_STEP)
            ) {
                return
            }
            lastFrac = frac
        } else {
            // Unknown size: emit on a slower fixed cadence so the bar still moves.
            if (now - lastMs < UNKNOWN_THROTTLE_MS) return
        }
        lastMs = now
        sink(done, total)
    }

    companion object {
        private const val THROTTLE_MS = 120L
        private const val MIN_STEP = 0.004f
        private const val UNKNOWN_THROTTLE_MS = 400L
    }
}

/**
 * Input side progress + cooperative cancellation for imports. Every read both
 * updates the counter and checks the cancel flag, so even the middle of a huge
 * copy aborts immediately instead of waiting for the file to finish.
 */
private class ProgressInputStream(
    private val source: java.io.InputStream,
    private val totalBytes: Long,
    private val emit: (done: Long, total: Long) -> Unit,
    private val cancelled: (() -> Boolean)?,
) : java.io.InputStream() {

    private var count = 0L

    override fun read(): Int {
        checkCancel()
        val v = source.read()
        if (v >= 0) bump(1)
        return v
    }

    override fun read(b: ByteArray, off: Int, len: Int): Int {
        checkCancel()
        val n = source.read(b, off, len)
        if (n > 0) bump(n)
        return n
    }

    override fun available(): Int = source.available()

    override fun close() = source.close()

    private fun bump(n: Int) {
        count += n
        emit(count, totalBytes)
    }

    private fun checkCancel() {
        if (cancelled?.invoke() == true) throw CancelledSignal()
    }
}

/** Output-side progress + cancellation for exports (decrypt path writes through here). */
private class ProgressOutputStream(
    private val sink: java.io.OutputStream,
    private val totalBytes: Long,
    private val publisher: ByteProgressPublisher,
    private val cancelled: (() -> Boolean)?,
) : java.io.OutputStream() {

    private var count = 0L

    override fun write(b: Int) {
        cancelled?.let { if (it()) throw CancelledSignal() }
        sink.write(b)
        bump(1)
    }

    override fun write(b: ByteArray, off: Int, len: Int) {
        cancelled?.let { if (it()) throw CancelledSignal() }
        sink.write(b, off, len)
        bump(len)
    }

    override fun flush() = sink.flush()

    override fun close() = sink.close()

    private fun bump(n: Int) {
        count += n
        publisher(count, totalBytes)
    }
}

/** Cancellation-only passthrough used when no percent tracking is requested. */
private class CancelCheckingOutputStream(
    private val sink: java.io.OutputStream,
    private val cancelled: () -> Boolean,
) : java.io.OutputStream() {

    override fun write(b: Int) {
        check()
        sink.write(b)
    }

    override fun write(b: ByteArray, off: Int, len: Int) {
        check()
        sink.write(b, off, len)
    }

    override fun flush() = sink.flush()

    override fun close() = sink.close()

    private fun check() {
        if (cancelled()) throw CancelledSignal()
    }
}
