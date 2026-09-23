package id.bayu.mygalleryvault.data.repository

import android.annotation.SuppressLint
import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import android.webkit.MimeTypeMap
import id.bayu.mygalleryvault.core.crypto.VaultSession
import id.bayu.mygalleryvault.core.storage.VaultStorage
import id.bayu.mygalleryvault.data.local.VaultFileDao
import id.bayu.mygalleryvault.data.local.VaultFileEntity
import id.bayu.mygalleryvault.domain.model.CancelledSignal
import id.bayu.mygalleryvault.domain.model.ImportOutcome
import id.bayu.mygalleryvault.domain.model.TransferCancelledException
import id.bayu.mygalleryvault.domain.model.TransferProgress
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException

/**
 * Import and export of vault files (PRD §9, §10, §31, §34, §39): SAF and
 * content URIs in, streaming AES-GCM out, with realtime byte progress and
 * cooperative cancellation.
 */
class TransferRepository(
    private val context: Context,
    private val fileDao: VaultFileDao,
    private val storage: VaultStorage,
    private val thumbnails: ThumbnailService,
) {

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
                    thumbRef = thumbnails.generateAndStoreThumbnail(uri, key)
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
                    runCatching { thumbnails.generateVideoThumbnail(rowId) }
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
     * NOTE: [android.provider.DocumentsContract.createDocument] rejects the raw
     * tree URI on several providers ("Invalid URI", e.g. externalstorage.documents
     * with a folder name containing spaces). The tree URI must first be converted
     * to the root document URI via buildDocumentUriUsingTree.
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

    // ---------- helpers ----------

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

    companion object {
        /** Removes characters that make SAF/MediaStore reject a display name. */
        fun safeExportName(name: String): String {
            val cleaned = name.replace(Regex("[\\\\/:*?\"<>|\\x00-\\x1F]"), "_").trim()
            return cleaned.ifBlank { "file_${System.currentTimeMillis()}" }
        }
    }
}
