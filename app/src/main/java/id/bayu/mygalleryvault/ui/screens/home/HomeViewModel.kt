package id.bayu.mygalleryvault.ui.screens.home

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import id.bayu.mygalleryvault.data.repository.VaultRepository
import id.bayu.mygalleryvault.domain.model.ImportOutcome
import id.bayu.mygalleryvault.domain.model.SortOption
import id.bayu.mygalleryvault.domain.model.VaultEntry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class HomeViewModel(
    private val repo: VaultRepository,
    val folderId: Long?,
) : ViewModel() {

    private val sort = MutableStateFlow(SortOption.NEWEST)

    val entries: StateFlow<List<VaultEntry>> = repo.observeEntries(folderId, sort)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

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

    init {
        viewModelScope.launch {
            _folderName.value = repo.folderName(folderId)
        }
        // Generate missing video thumbnails in background (requires masterKey which is available after unlock)
        viewModelScope.launch(Dispatchers.IO) {
            runCatching { repo.generateMissingVideoThumbnails() }
        }
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
        viewModelScope.launch {
            _importing.value = true
            try {
                val outcome = repo.importUris(uris, folderId, moveOriginals)
                _message.value = buildImportMessage(outcome)
            } catch (e: Exception) {
                _message.value = "Import gagal: ${e.message}"
            } finally {
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
        viewModelScope.launch {
            try {
                repo.exportFile(fileId, destUri)
                _message.value = "File diekspor"
            } catch (e: Exception) {
                _message.value = "Export gagal (${e.javaClass.simpleName}): ${e.message}"
            }
        }
    }

    fun exportToDownloads(fileId: Long, displayName: String) {
        viewModelScope.launch {
            try {
                repo.exportToDownloads(fileId, displayName)
                _message.value = "File disimpan ke folder Download"
            } catch (e: Exception) {
                _message.value = "Export gagal (${e.javaClass.simpleName}): ${e.message}"
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

    fun consumeMessage() {
        _message.value = null
    }

    // ---- thumbnail cache (decrypted only in memory) ----

    private val thumbCache = object : LinkedHashMap<Long, Bitmap>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: Map.Entry<Long, Bitmap>): Boolean = size > 128
    }

    suspend fun thumbnailFor(fileId: Long, hasThumb: Boolean): Bitmap? {
        if (!hasThumb) return null
        synchronized(thumbCache) { thumbCache[fileId] }?.let { return it }
        return try {
            withContext(Dispatchers.IO) {
                val bmp = repo.getThumbnailBitmap(fileId) ?: return@withContext null
                synchronized(thumbCache) { thumbCache[fileId] = bmp }
                bmp
            }
        } catch (_: Exception) {
            null
        }
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
