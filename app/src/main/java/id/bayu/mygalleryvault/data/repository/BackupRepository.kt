package id.bayu.mygalleryvault.data.repository

import android.content.Context
import androidx.room.withTransaction
import id.bayu.mygalleryvault.core.backup.BackupCodec
import id.bayu.mygalleryvault.core.backup.BackupException
import id.bayu.mygalleryvault.core.crypto.CryptoEngine
import id.bayu.mygalleryvault.core.crypto.IntegrityViolationException
import id.bayu.mygalleryvault.core.crypto.VaultKeyManager
import id.bayu.mygalleryvault.core.crypto.VaultSession
import id.bayu.mygalleryvault.core.storage.VaultStorage
import id.bayu.mygalleryvault.VaultStack
import id.bayu.mygalleryvault.data.local.FolderEntity
import id.bayu.mygalleryvault.data.local.VaultFileEntity
import id.bayu.mygalleryvault.domain.model.CancelledSignal
import id.bayu.mygalleryvault.domain.model.TransferCancelledException
import id.bayu.mygalleryvault.domain.model.TransferKind
import id.bayu.mygalleryvault.domain.model.TransferProgress
import id.bayu.mygalleryvault.domain.model.VaultSlot
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.io.OutputStream

data class BackupSummary(
    val fileCount: Int,
    val folderCount: Int,
    val totalBytes: Long,
)

data class RestoreResult(
    val imported: Int,
    val skipped: Int,
    val foldersCreated: Int,
)

/**
 * Encrypted cross-device vault backup/restore (PRD §36-37a).
 *
 * Export bundles the HP1 master key (wrapped by a PBKDF2 KDF of the user's PIN,
 * AES-GCM authenticated) together with the still-encrypted objects/thumbnails
 * into one portable `.svbackup` file - the container never contains plaintext.
 *
 * Restore unwraps the master using the ORIGINAL HP1 PIN entered by the user
 * (§37a.3), verifies integrity, then re-encrypts every item under the CURRENT
 * device's master key before storing it natively (Opsi A, §37a.4). Merge
 * behavior per §37a.5: skip duplicates (default) or rename duplicates; existing
 * data is never overwritten silently. Any integrity failure aborts the entire
 * restore without touching the destination database (§37a.6).
 */
class BackupRepository(private val context: Context) {

    companion object {
        private const val INTEGRITY_MSG =
            "Integritas gagal: backup dimodifikasi atau rusak saat transit. Import dibatalkan."
    }

    // ---------- Export (HP 1 -> .svbackup) ----------

    @Throws(BackupException::class, TransferCancelledException::class)
    suspend fun exportBackup(
        dest: OutputStream,
        pin: CharArray,
        slot: VaultSlot,
        stack: VaultStack,
        keyManager: VaultKeyManager,
        onProgress: ((TransferProgress) -> Unit)? = null,
        isCancelled: (() -> Boolean)? = null,
    ): BackupSummary = withContext(Dispatchers.IO) {
        val master = runCatching { keyManager.unlockWithPin(pin, slot) }.getOrNull()
            ?: throw BackupException("PIN salah atau file backup tidak valid")
        val masterRaw = master.encoded
            ?: throw BackupException("Gagal membaca kunci vault")

        val files = stack.repository.allFilesOnce()
        val folders = stack.repository.allFoldersOnce()
        val thumbOwners = files.filter { it.thumbRef != null }
            .associateBy({ it.thumbRef!! }, { it.originalName })
        val totalItems = files.size + thumbOwners.size + 1 // + metadata block

        fun publish(done: Long, size: Long, index: Int, name: String) {
            onProgress?.invoke(
                TransferProgress(
                    kind = TransferKind.BACKUP,
                    totalItems = totalItems,
                    completedItems = index,
                    currentItemName = name,
                    currentItemIndex = index + 1,
                    itemBytesDone = done,
                    itemBytesTotal = size,
                )
            )
        }

        try {
            dest.use { raw ->
                java.io.BufferedOutputStream(raw, 256 * 1024).use { out ->
                    val trackedOut: OutputStream =
                        when {
                            onProgress != null || isCancelled != null ->
                                CancelCheckingOutputStream(out, isCancelled ?: { false })

                            else -> out
                        }
                    val salt = BackupCodec.newSalt()
                    val header = BackupCodec.Header(salt, BackupCodec.DEFAULT_ITERATIONS)
                    publish(0, 0, 0, "Metadata vault")
                    BackupCodec.writeHeader(trackedOut, salt, BackupCodec.DEFAULT_ITERATIONS)
                    BackupCodec.writeWrappedMaster(trackedOut, header, pin, masterRaw)
                    BackupCodec.writeEncryptedBlock(
                        trackedOut,
                        buildMetadataJson(files, folders).toByteArray(Charsets.UTF_8),
                        master,
                    )

                    val pub = ByteProgressPublisher { done, size ->
                        publish(done, size, 0, "Metadata vault")
                    }

                    var index = 0
                    for (file in files) {
                        if (isCancelled?.invoke() == true) throw CancelledSignal()
                        val src = File(stack.storage.objectsDir, file.encryptedName)
                        if (!src.exists()) continue // crash-recovery gap; skip rather than fail whole export
                        val srcLen = src.length()
                        index++
                        val itemIndex = index
                        val emit = ByteProgressPublisher { done, size ->
                            publish(done, size, itemIndex, file.originalName)
                        }
                        val counted = ProgressInputStream(src.inputStream().buffered(), srcLen, { d: Long, t: Long -> emit(d, t) }, isCancelled)
                        BackupCodec.writeItem(
                            trackedOut,
                            BackupCodec.OBJECT_PREFIX + file.encryptedName,
                            counted,
                            srcLen,
                        )
                    }
                    val writtenThumbs = HashSet<String>()
                    for (file in files) {
                        val ref = file.thumbRef ?: continue
                        if (!writtenThumbs.add(ref)) continue
                        if (isCancelled?.invoke() == true) throw CancelledSignal()
                        val src = File(stack.storage.thumbsDir, ref)
                        if (!src.exists()) continue
                        val srcLen = src.length()
                        index++
                        val itemIndex = index
                        val thumbName = "Thumbnail ${thumbOwners[ref] ?: file.originalName}"
                        val emit = ByteProgressPublisher { done, size ->
                            publish(done, size, itemIndex, thumbName)
                        }
                        val counted = ProgressInputStream(src.inputStream().buffered(), srcLen, { d: Long, t: Long -> emit(d, t) }, isCancelled)
                        BackupCodec.writeItem(
                            trackedOut, BackupCodec.THUMB_PREFIX + ref, counted, srcLen,
                        )
                    }
                    BackupCodec.writeTerminator(trackedOut)
                }
            }
        } catch (c: CancelledSignal) {
            throw TransferCancelledException(0, "Backup dibatalkan")
        } catch (e: BackupException) {
            throw e
        } catch (e: Exception) {
            throw BackupException(e.message ?: "Export backup gagal")
        }
        BackupSummary(files.size, folders.size, files.sumOf { it.size })
    }

    // ---------- Restore (.svbackup -> current device, re-encrypted) ----------

    @Throws(BackupException::class, TransferCancelledException::class)
    suspend fun restoreBackup(
        source: InputStream,
        originalPin: CharArray,
        renameConflicts: Boolean,
        stack: VaultStack,
        onProgress: ((TransferProgress) -> Unit)? = null,
        isCancelled: (() -> Boolean)? = null,
    ): RestoreResult = withContext(Dispatchers.IO) {
        val targetKey = VaultSession.masterKey
            ?: throw BackupException("Vault tujuan terkunci")

        val createdObjects = mutableListOf<String>()
        val createdThumbs = mutableListOf<String>()
        val createdFolderIds = mutableListOf<Long>()

        try {
            source.use { rawSource ->
                java.io.BufferedInputStream(rawSource, 256 * 1024).use { input ->
                    val header = BackupCodec.readHeader(input)
                val hp1Master = BackupCodec.readWrappedMaster(input, header, originalPin)
                val meta = JSONObject(
                    String(BackupCodec.readEncryptedBlock(input, hp1Master), Charsets.UTF_8)
                )

                val foldersJson = meta.optJSONArray("folders") ?: JSONArray()
                val filesJson = meta.optJSONArray("files") ?: JSONArray()

                val thumbRefs = LinkedHashSet<String>()
                for (i in 0 until filesJson.length()) {
                    filesJson.getJSONObject(i)
                        .optString("thumbRef", "")
                        .takeIf { it.isNotEmpty() }
                        ?.let { thumbRefs.add(it) }
                }
                val totalItems = filesJson.length() + thumbRefs.size

                // Original names are available from metadata BEFORE body items
                // stream in, so the progress dialog can show real filenames.
                val displayNames = HashMap<String, String>()
                for (i in 0 until filesJson.length()) {
                    val f = filesJson.getJSONObject(i)
                    val enc = f.optString("encName", "")
                    val name = f.optString("name", enc)
                    if (enc.isNotEmpty()) displayNames[BackupCodec.OBJECT_PREFIX + enc] = name
                    val tref = f.optString("thumbRef", "")
                    if (tref.isNotEmpty()) displayNames[BackupCodec.THUMB_PREFIX + tref] =
                        "Thumbnail $name"
                }

                fun publish(done: Long, size: Long, processed: Int, name: String) {
                    onProgress?.invoke(
                        TransferProgress(
                            kind = TransferKind.RESTORE,
                            totalItems = totalItems,
                            completedItems = processed,
                            currentItemName = name,
                            currentItemIndex = processed + 1,
                            itemBytesDone = done,
                            itemBytesTotal = size,
                        )
                    )
                }
                if (totalItems > 0) publish(0, 0, 0, displayNames.values.firstOrNull() ?: "...")

                val folderMap = HashMap<Long, Long?>() // old id -> new id
                createFoldersRespectingParentOrder(foldersJson, stack, folderMap, createdFolderIds)

                // Single sequential pass over body items: decrypt+verify with the HP1
                // key, immediately re-encrypt under this device's key (§37a.4 Opsi A).
                val objMap = HashMap<String, Pair<String, Long>>() // old encName -> (new encName, plainSize)
                val thumbMap = HashMap<String, String>()
                var processed = 0

                while (true) {
                    val returnedPath = BackupCodec.readItem(input) { path, data, size ->
                        if (isCancelled?.invoke() == true) throw CancelledSignal()
                        val name = displayNames[path] ?: path
                        val emit = ByteProgressPublisher { done, total ->
                            publish(done, total, processed, name)
                        }
                        publish(0, size, processed, name)
                        val counted =
                            if (onProgress != null || isCancelled != null) {
                                ProgressInputStream(data, size, { d: Long, t: Long -> emit(d, t) }, isCancelled)
                            } else data
                        when {
                            path.startsWith(BackupCodec.OBJECT_PREFIX) -> {
                                val oldName = path.removePrefix(BackupCodec.OBJECT_PREFIX)
                                val tmp = File.createTempFile("svr", ".tmp", context.cacheDir)
                                try {
                                    tmp.outputStream().buffered().use { fos ->
                                        decryptVerified(counted, fos, hp1Master)
                                    }
                                    val (newName, plainSize) = stack.storage.importStream(tmp.inputStream(), targetKey)
                                    objMap[oldName] = newName to plainSize
                                    createdObjects.add(newName)
                                } finally {
                                    tmp.delete()
                                }
                            }
                            path.startsWith(BackupCodec.THUMB_PREFIX) -> {
                                val oldRef = path.removePrefix(BackupCodec.THUMB_PREFIX)
                                val bos = ByteArrayOutputStream()
                                decryptVerified(counted, bos, hp1Master)
                                val newRef = stack.storage.saveThumbnail(bos.toByteArray(), targetKey)
                                thumbMap[oldRef] = newRef
                                createdThumbs.add(newRef)
                            }
                            else -> {
                                // Unknown section from a newer app version: consume & discard.
                                val sink = ByteArray(8192)
                                while (data.read(sink) != -1) { /* discard */ }
                            }
                        }
                        processed++
                        publish(size, size, processed, name)
                    }
                    if (returnedPath == null) break
                }

                // All payload verified; now resolve conflicts and stage DB rows.
                val pendingEntities = mutableListOf<VaultFileEntity>()
                var skipped = 0
                for (i in 0 until filesJson.length()) {
                    val f = filesJson.getJSONObject(i)
                    val mapped = objMap[f.getString("encName")]
                        ?: throw BackupException(INTEGRITY_MSG)

                    val newFolderId: Long? = if (f.isNull("folderId")) null else folderMap[f.getLong("folderId")]
                    var name = f.getString("name")
                    val existing = stack.database.vaultFileDao().byNameInFolder(name, newFolderId)
                    if (existing != null) {
                        if (!renameConflicts) {
                            skipped++
                            continue
                        }
                        name = uniqueName(name) { candidate ->
                            stack.database.vaultFileDao().byNameInFolder(candidate, newFolderId) == null
                        }
                    }
                    pendingEntities += VaultFileEntity(
                        encryptedName = mapped.first,
                        originalName = name,
                        mimeType = f.optString("mime", "application/octet-stream"),
                        size = mapped.second,
                        folderId = newFolderId,
                        createdAt = f.optLong("createdAt", System.currentTimeMillis()),
                        modifiedAt = f.optLong("modifiedAt", System.currentTimeMillis()),
                        encryptionVersion = f.optInt("ver", 1),
                        thumbRef = f.optString("thumbRef", "").takeIf { it.isNotEmpty() }?.let { thumbMap[it] },
                    )
                }

                stack.database.withTransaction {
                    pendingEntities.forEach { stack.database.vaultFileDao().insert(it) }
                }
                RestoreResult(
                    imported = pendingEntities.size,
                    skipped = skipped,
                    foldersCreated = createdFolderIds.size,
                )
                }
            }
        } catch (c: CancelledSignal) {
            rollback(createdObjects, createdThumbs, createdFolderIds, stack)
            throw TransferCancelledException(
                createdObjects.size,
                "Restore dibatalkan (data parsial dibersihkan)",
            )
        } catch (e: TransferCancelledException) {
            rollback(createdObjects, createdThumbs, createdFolderIds, stack)
            throw e
        } catch (e: BackupException) {
            rollback(createdObjects, createdThumbs, createdFolderIds, stack)
            throw e
        } catch (e: Exception) {
            rollback(createdObjects, createdThumbs, createdFolderIds, stack)
            throw BackupException(e.message ?: "Restore gagal")
        }
    }

    // ---------- helpers ----------

    private fun buildMetadataJson(files: List<VaultFileEntity>, folders: List<FolderEntity>): String {
        val root = JSONObject()
            .put("app", "SecureVault")
            .put("schema", 1)
        val folderArr = JSONArray()
        for (f in folders) {
            folderArr.put(
                JSONObject()
                    .put("id", f.id)
                    .put("parentId", f.parentId ?: JSONObject.NULL)
                    .put("name", f.name)
                    .put("createdAt", f.createdAt)
            )
        }
        val fileArr = JSONArray()
        for (f in files) {
            fileArr.put(
                JSONObject()
                    .put("encName", f.encryptedName)
                    .put("name", f.originalName)
                    .put("mime", f.mimeType)
                    .put("size", f.size)
                    .put("folderId", f.folderId ?: JSONObject.NULL)
                    .put("createdAt", f.createdAt)
                    .put("modifiedAt", f.modifiedAt)
                    .put("ver", f.encryptionVersion)
                    .put("thumbRef", f.thumbRef ?: JSONObject.NULL)
            )
        }
        return root.put("folders", folderArr).put("files", fileArr).toString()
    }

    private fun decryptVerified(data: InputStream, out: OutputStream, key: javax.crypto.SecretKey) {
        try {
            CryptoEngine.decryptStream(data, out, key)
        } catch (_: IntegrityViolationException) {
            throw BackupException(INTEGRITY_MSG)
        }
    }

    private suspend fun createFoldersRespectingParentOrder(
        foldersJson: JSONArray,
        stack: VaultStack,
        map: MutableMap<Long, Long?>,
        createdIds: MutableList<Long>,
    ) {
        val remaining = (0 until foldersJson.length()).map { foldersJson.getJSONObject(it) }.toMutableList()
        var guard = remaining.size + 1
        while (remaining.isNotEmpty() && guard-- > 0) {
            val iterator = remaining.iterator()
            var progressed = false
            while (iterator.hasNext()) {
                val obj = iterator.next()
                val oldId = obj.getLong("id")
                val parentId = if (obj.isNull("parentId")) null else obj.getLong("parentId")
                if (parentId != null && !map.containsKey(parentId)) continue
                val newParentId: Long? = parentId?.let { map[it] }
                val existing = stack.database.folderDao().byName(newParentId, obj.getString("name"))
                val newId: Long = if (existing != null) {
                    existing.id // merge into same-named sibling instead of duplicating (§37a.5)
                } else {
                    val id = stack.database.folderDao().insert(
                        FolderEntity(
                            parentId = newParentId,
                            name = obj.getString("name"),
                            createdAt = System.currentTimeMillis(),
                        )
                    )
                    createdIds.add(id)
                    id
                }
                map[oldId] = newId
                iterator.remove()
                progressed = true
            }
            if (!progressed && remaining.isNotEmpty()) {
                throw BackupException("Struktur folder pada backup tidak konsisten")
            }
        }
    }

    private suspend fun uniqueName(original: String, isFree: suspend (String) -> Boolean): String {
        if (isFree(original)) return original
        val dot = original.lastIndexOf('.')
        val base = if (dot > 0) original.substring(0, dot) else original
        val ext = if (dot > 0) original.substring(dot) else ""
        var n = 2
        while (true) {
            val candidate = "$base ($n)$ext"
            if (isFree(candidate)) return candidate
            n++
        }
    }

    private suspend fun rollback(
        objects: List<String>,
        thumbs: List<String>,
        folderIds: List<Long>,
        stack: VaultStack,
    ) {
        runCatching {
            objects.forEach { stack.storage.deleteObject(it) }
            thumbs.forEach { stack.storage.deleteThumbnail(it) }
            folderIds.reversed().forEach { stack.database.folderDao().delete(it) }
        }
    }
}
