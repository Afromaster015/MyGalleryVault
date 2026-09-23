package id.bayu.mygalleryvault.ui.screens.home

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import id.bayu.mygalleryvault.data.repository.ThumbnailService
import id.bayu.mygalleryvault.data.repository.TransferRepository
import id.bayu.mygalleryvault.data.repository.VaultRepository
import id.bayu.mygalleryvault.domain.model.ImportOutcome
import id.bayu.mygalleryvault.domain.model.SortOption
import id.bayu.mygalleryvault.domain.model.TransferCancelledException
import id.bayu.mygalleryvault.domain.model.TransferKind
import id.bayu.mygalleryvault.domain.model.TransferProgress
import id.bayu.mygalleryvault.domain.model.VaultEntry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicBoolean

class HomeViewModel(
    private val repo: VaultRepository,
    private val transfers: TransferRepository,
    private val thumbnails: ThumbnailService,
    val folderId: Long?,
) : ViewModel() {

    private val sort = MutableStateFlow(SortOption.NEWEST)

    private val _entriesLoaded = MutableStateFlow(false)
    val entriesLoaded: StateFlow<Boolean> = _entriesLoaded

    private val _entriesError = MutableStateFlow<String?>(null)
    val entriesError: StateFlow<String?> = _entriesError

    private val retries = MutableStateFlow(0)

    @OptIn(ExperimentalCoroutinesApi::class)
    val entries: StateFlow<List<VaultEntry>> = retries
        .flatMapLatest { repo.observeEntries(folderId, sort) }
        .onEach {
            _entriesError.value = null
            _entriesLoaded.value = true
        }
        .catch { t ->
            _entriesError.value = t.message ?: "Penyebab tidak diketahui"
            _entriesLoaded.value = true
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** Re-subscribes after a failed load; the failed flow has already terminated. */
    fun retry() {
        _entriesError.value = null
        _entriesLoaded.value = false
        retries.value++
    }

    val currentSort = sort

    private val _folderName = MutableStateFlow<String?>(null)
    val folderName: StateFlow<String?> = _folderName

    private val _searchQuery = MutableStateFlow("")
    val searchQuery: StateFlow<String> = _searchQuery

    private val _searchResults = MutableStateFlow<List<id.bayu.mygalleryvault.domain.model.VaultFile>>(emptyList())
    val searchResults: StateFlow<List<id.bayu.mygalleryvault.domain.model.VaultFile>> = _searchResults

    private val _importing = MutableStateFlow(false)
    val importing: StateFlow<Boolean> = _importing

    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message

    /** Live per-item progress for the running import/export, null when idle. */
    private val _transferProgress = MutableStateFlow<TransferProgress?>(null)
    val transferProgress: StateFlow<TransferProgress?> = _transferProgress

    private val transferCancelled = AtomicBoolean(false)

    fun cancelTransfer() {
        transferCancelled.set(true)
    }

    private fun beginTransfer(): Boolean {
        if (_transferProgress.value != null) return false
        transferCancelled.set(false)
        return true
    }

    init {
        viewModelScope.launch {
            _folderName.value = repo.folderName(folderId)
        }
        // Thumbnails self-heal on demand from each grid tile (see thumbnailFor);
        // a separate scanner here duplicated decoder work with that path.
    }

    fun setSort(option: SortOption) {
        sort.value = option
    }

    fun setSearchQuery(q: String) {
        _searchQuery.value = q
        if (q.isBlank()) {
            _searchResults.value = emptyList()
            return
        }
        viewModelScope.launch {
            _searchResults.value = repo.searchFiles(q)
        }
    }

    fun import(uris: List<Uri>, moveOriginals: Boolean) {
        if (uris.isEmpty()) return
        if (!beginTransfer()) return
        viewModelScope.launch {
            _importing.value = true
            try {
                val outcome = transfers.importUris(
                    uris, folderId, moveOriginals,
                    onProgress = { p -> _transferProgress.value = p },
                    isCancelled = { transferCancelled.get() },
                )
                _message.value = buildImportMessage(outcome)
            } catch (e: TransferCancelledException) {
                _message.value = e.message
            } catch (e: Exception) {
                _message.value = "Import gagal: ${e.message}"
            } finally {
                _transferProgress.value = null
                _importing.value = false
            }
        }
    }

    fun deleteFiles(ids: List<Long>) {
        viewModelScope.launch {
            try {
                repo.deleteFiles(ids)
                _message.value = "${ids.size} file dihapus"
            } catch (e: Exception) {
                _message.value = "Gagal menghapus: ${e.message}"
            }
        }
    }

    fun exportFile(fileId: Long, destUri: Uri) {
        if (!beginTransfer()) return
        viewModelScope.launch {
            try {
                val name = repo.getFile(fileId)?.name ?: "file"
                transfers.exportFile(
                    fileId, destUri,
                    onItemProgress = { done, total ->
                        _transferProgress.value = TransferProgress.single(TransferKind.EXPORT, name, done, total)
                    },
                    isCancelled = { transferCancelled.get() },
                )
                _message.value = "File diekspor"
            } catch (e: TransferCancelledException) {
                _message.value = "Export dibatalkan"
            } catch (e: Exception) {
                _message.value = "Export gagal (${e.javaClass.simpleName}): ${e.message}"
            } finally {
                _transferProgress.value = null
            }
        }
    }

    fun exportToDownloads(fileId: Long, displayName: String) {
        if (!beginTransfer()) return
        viewModelScope.launch {
            try {
                transfers.exportToDownloads(
                    fileId, displayName,
                    onItemProgress = { done, total ->
                        _transferProgress.value =
                            TransferProgress.single(TransferKind.EXPORT, displayName, done, total)
                    },
                    isCancelled = { transferCancelled.get() },
                )
                _message.value = "File disimpan ke folder Download"
            } catch (e: TransferCancelledException) {
                _message.value = "Export dibatalkan"
            } catch (e: Exception) {
                _message.value = "Export gagal (${e.javaClass.simpleName}): ${e.message}"
            } finally {
                _transferProgress.value = null
            }
        }
    }

    /**
     * Bulk export into a user-picked directory with the same realtime per-item
     * progress dialog used by import ("nama file (2/7)" + moving bar).
     */
    fun exportAllIntoDir(fileIds: List<Long>, treeUri: Uri, onFinished: (ok: Int, failed: Int) -> Unit) {
        if (fileIds.isEmpty()) return
        if (!beginTransfer()) return
        viewModelScope.launch {
            var ok = 0
            var fail = 0
            try {
                val named = fileIds.mapNotNull { id -> repo.getFile(id)?.let { it.id to it.name } }
                val total = named.size
                for ((index, item) in named.withIndex()) {
                    val (id, name) = item
                    val publish = TransferProgress(
                        kind = TransferKind.EXPORT,
                        totalItems = total,
                        completedItems = index,
                        currentItemName = name,
                        currentItemIndex = index + 1,
                        itemBytesDone = 0,
                        itemBytesTotal = 0,
                    )
                    _transferProgress.value = publish.copy(itemBytesDone = 0, itemBytesTotal = 0)
                    try {
                        val exported = transfers.exportIntoDir(
                            id, treeUri,
                            onItemProgress = { done, size ->
                                _transferProgress.value = publish.copy(
                                    itemBytesDone = done,
                                    itemBytesTotal = if (size > 0) size else 0,
                                )
                            },
                            isCancelled = { transferCancelled.get() },
                        )
                        if (exported) ok++ else fail++
                    } catch (c: TransferCancelledException) {
                        throw c
                    } catch (c: id.bayu.mygalleryvault.domain.model.CancelledSignal) {
                        throw TransferCancelledException(
                            ok,
                            "Export dibatalkan ($ok dari ${fileIds.size} file selesai)",
                        )
                    } catch (_: Exception) {
                        fail++
                    }
                }
                val msg = buildString {
                    append("Export: $ok berhasil")
                    if (fail > 0) append(", $fail gagal")
                }
                _message.value = msg
                onFinished(ok, fail)
            } catch (c: TransferCancelledException) {
                _message.value = "Export dibatalkan ($ok dari ${fileIds.size} selesai)"
                onFinished(ok, fileIds.size - ok)
            } catch (e: Exception) {
                _message.value = "Export gagal: ${e.message}"
                onFinished(ok, fail)
            } finally {
                _transferProgress.value = null
            }
        }
    }

    fun createFolder(name: String) {
        viewModelScope.launch {
            try {
                repo.createFolder(folderId, name)
            } catch (e: Exception) {
                _message.value = e.message ?: "Gagal membuat folder"
            }
        }
    }

    fun renameFolder(id: Long, newName: String) {
        viewModelScope.launch { runCatching { repo.renameFolder(id, newName) } }
    }

    fun deleteFolder(id: Long) {
        viewModelScope.launch {
            try {
                repo.deleteFolderRecursive(id)
                _message.value = "Folder dihapus beserta isinya"
            } catch (e: Exception) {
                _message.value = "Gagal menghapus folder: ${e.message}"
            }
        }
    }

    fun moveFile(fileId: Long, targetFolderId: Long?) {
        viewModelScope.launch {
            try {
                repo.moveFileTo(fileId, targetFolderId)
                _message.value = "File dipindahkan"
            } catch (e: Exception) {
                _message.value = "Gagal memindahkan: ${e.message}"
            }
        }
    }

    /**
     * Moves the current Gallery selection. A folder cannot go into itself or into its own
     * descendant, and the refusal is reported rather than swallowed: a button that quietly does
     * nothing reads as broken.
     */
    fun moveSelection(fileIds: List<Long>, folderIds: List<Long>, targetFolderId: Long?) {
        if (fileIds.isEmpty() && folderIds.isEmpty()) return
        viewModelScope.launch {
            try {
                val refused = repo.moveInto(fileIds, folderIds, targetFolderId)
                _message.value = when {
                    refused.isEmpty() -> "Dipindahkan"
                    refused.size == folderIds.size && fileIds.isEmpty() ->
                        "Tidak dipindahkan: folder tidak bisa masuk ke dalam dirinya sendiri"
                    else -> "Dipindahkan, ${refused.size} folder dilewati"
                }
            } catch (e: Exception) {
                _message.value = "Gagal memindahkan: ${e.message}"
            }
        }
    }

    fun consumeMessage() {
        _message.value = null
    }

    // ---- thumbnail pipeline (decrypted only in memory) ----

    private val thumbCache = object : LinkedHashMap<Long, Bitmap>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: Map.Entry<Long, Bitmap>): Boolean = size > 128
    }

    /** Files whose preview generation already failed this session; avoid retry storms. */
    private val failedThumbIds = HashSet<Long>()

    /**
     * Serializes ALL preview work through one worker: the grid can compose many
     * tiles at once and letting them each spawn decoders/retrievers concurrently
     * thrashes the platform media stack (visible on emulators). One job at a
     * time; waiters simply queue.
     */
    private val thumbWorker = kotlinx.coroutines.sync.Mutex()

    private fun thumbLog(msg: String) {
        android.util.Log.d(TAG_THUMB, msg)
    }

    /**
     * Returns the decrypted preview bitmap for a tile. When no stored thumbnail
     * exists yet (failed import-time decode, legacy row), one is generated on
     * demand - video frame via ranged decryption or downscaled image bytes -
     * and persisted for future sessions.
     */
    suspend fun thumbnailFor(fileId: Long, isVideo: Boolean, isImage: Boolean): Bitmap? {
        synchronized(thumbCache) { thumbCache[fileId] }?.let { return it }
        if (!isVideo && !isImage) return null
        if (synchronized(failedThumbIds) { fileId in failedThumbIds }) return null

        thumbWorker.withLock {
            // Another queued job may have produced it while we waited.
            synchronized(thumbCache) { thumbCache[fileId] }?.let { return it }
            return try {
                withContext(Dispatchers.IO) {
                    var bmp = thumbnails.getThumbnailBitmap(fileId)
                    if (bmp == null) {
                        thumbLog("generate start id=$fileId video=$isVideo image=$isImage")
                        val generated = when {
                            isVideo -> thumbnails.generateVideoThumbnail(fileId)
                            isImage -> thumbnails.generateImageThumbnail(fileId)
                            else -> false
                        }
                        thumbLog("generate id=$fileId result=$generated")
                        if (generated) bmp = thumbnails.getThumbnailBitmap(fileId)
                    } else {
                        thumbLog("load stored id=$fileId")
                    }
                    if (bmp != null) {
                        synchronized(thumbCache) { thumbCache[fileId] = bmp }
                    } else {
                        synchronized(failedThumbIds) { failedThumbIds.add(fileId) }
                    }
                    bmp
                }
            } catch (t: Throwable) {
                thumbLog("FAIL id=$fileId: ${t.javaClass.simpleName}: ${t.message}")
                synchronized(failedThumbIds) { failedThumbIds.add(fileId) }
                null
            }
        }
    }

    // ---- storage summary ----

    /** Real encrypted payload size of this slot, for the gallery storage card. */
    suspend fun storageUsedBytes(): Long =
        try {
            withContext(Dispatchers.IO) { repo.stats().totalSizeBytes }
        } catch (_: Exception) {
            0L
        }

    // ---- video duration badges ----

    /**
     * Duration in ms per video, resolved at most once per session per file.
     * Probing decrypts a ranged slice of the container, so results are cached,
     * videos that yield nothing are remembered as "no badge", and probes run
     * one at a time on their own worker so they never block thumbnail decoding.
     */
    private val durationCache = object : LinkedHashMap<Long, Long>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: Map.Entry<Long, Long>): Boolean = size > 256
    }

    private val probedWithoutDuration = HashSet<Long>()
    private val probeWorker = kotlinx.coroutines.sync.Mutex()

    suspend fun durationFor(fileId: Long, isVideo: Boolean): Long? {
        if (!isVideo) return null
        synchronized(durationCache) { durationCache[fileId] }?.let { return it }
        if (synchronized(probedWithoutDuration) { fileId in probedWithoutDuration }) return null

        val ms = probeWorker.withLock {
            synchronized(durationCache) { durationCache[fileId] }?.let { return@withLock it }
            try {
                thumbnails.probeVideoDurationMs(fileId)
            } catch (_: Exception) {
                null
            }
        }
        if (ms == null || ms <= 0L) {
            synchronized(probedWithoutDuration) { probedWithoutDuration.add(fileId) }
            return null
        }
        synchronized(durationCache) { durationCache[fileId] = ms }
        return ms
    }

    private companion object {
        const val TAG_THUMB = "SV_Thumb"
        const val PREVIEW_COUNT = 4
    }

    /**
     * 2x2 cover for a folder tile: up to four decrypted previews of the files
     * directly inside it (reuses the self-healing thumbnail pipeline).
     */
    suspend fun folderPreview(folderId: Long): List<id.bayu.mygalleryvault.domain.model.VaultFile> =
        try {
            repo.folderPreviewFiles(folderId, PREVIEW_COUNT)
        } catch (_: Exception) {
            emptyList()
        }

    suspend fun fullImageBitmap(fileId: Long): Bitmap? =
        try {
            withContext(Dispatchers.IO) {
                val bytes = repo.readDecryptedBytes(fileId)
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
            }
        } catch (_: Exception) {
            null
        }

    /** Plaintext bytes delivered only to in-app viewers or explicit share/open-with flows. */
    fun decryptedBytes(fileId: Long, onResult: (Result<ByteArray>) -> Unit) {
        viewModelScope.launch {
            onResult(runCatching { repo.readDecryptedBytes(fileId) })
        }
    }

    /** Streaming decryption to an OutputStream, for large files that shouldn't be fully loaded into memory. */
    fun streamDecryptedTo(fileId: Long, output: java.io.OutputStream, onResult: (Result<Unit>) -> Unit) {
        viewModelScope.launch {
            onResult(runCatching {
                repo.streamDecryptedTo(fileId, output)
            })
        }
    }

    private fun buildImportMessage(outcome: ImportOutcome): String {
        if (outcome.failed.isNotEmpty()) {
            return "Import: ${outcome.succeeded}/${outcome.total} berhasil, ${outcome.failed.size} gagal"
        }
        var msg = "${outcome.succeeded} file diimpor ke vault"
        if (outcome.copiedNotMoved.isNotEmpty()) {
            msg += " (${outcome.copiedNotMoved.size} original tidak dapat dihapus, mode salinan)"
        }
        return msg
    }
}

/**
 * Gallery type filter driven by the chip row. Folders belong to the ALL view only: a folder is
 * neither a photo nor a video, so keeping it in those two views made the visible items sit next to
 * containers they cannot be compared with. Files inside folders stay reachable through the search
 * bar, which queries the whole vault rather than the level currently open.
 */
enum class MediaFilter(val label: String, val noun: String) {
    ALL("All", "media"),
    PHOTOS("Photos", "foto"),
    VIDEOS("Videos", "video"),
}

fun List<VaultEntry>.filterBy(filter: MediaFilter): List<VaultEntry> = when (filter) {
    MediaFilter.ALL -> this
    MediaFilter.PHOTOS -> filterIsInstance<VaultEntry.File>().filter { it.file.isImage }
    MediaFilter.VIDEOS -> filterIsInstance<VaultEntry.File>().filter { it.file.isVideo }
}
