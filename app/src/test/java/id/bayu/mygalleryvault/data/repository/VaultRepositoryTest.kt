package id.bayu.mygalleryvault.data.repository

import android.content.ContextWrapper
import id.bayu.mygalleryvault.core.storage.VaultStorage
import id.bayu.mygalleryvault.data.local.FolderDao
import id.bayu.mygalleryvault.data.local.FolderEntity
import id.bayu.mygalleryvault.data.local.VaultFileDao
import id.bayu.mygalleryvault.data.local.VaultFileEntity
import id.bayu.mygalleryvault.domain.model.SortOption
import id.bayu.mygalleryvault.domain.model.VaultEntry
import id.bayu.mygalleryvault.domain.model.VaultStats
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * Unit tests for VaultRepository with in-memory fake DAOs and a real
 * temp-folder VaultStorage, covering sorting, search, folder cascade delete,
 * subtitle matching, moves, and stats.
 */
class VaultRepositoryTest {

    @get:Rule
    val tmp = TemporaryFolder()

    // ---------- fakes ----------

    private class FakeVaultFileDao : VaultFileDao {
        val rows = mutableListOf<VaultFileEntity>()
        private val flow = MutableStateFlow<List<VaultFileEntity>>(emptyList())
        var nextId = 1L

        private fun publish() { flow.value = rows.toList() }
        private fun replace(id: Long, transform: (VaultFileEntity) -> VaultFileEntity) {
            val i = rows.indexOfFirst { it.id == id }
            if (i >= 0) rows[i] = transform(rows[i])
            publish()
        }

        override fun byFolder(folderId: Long?): Flow<List<VaultFileEntity>> =
            flow.map { list -> list.filter { it.folderId == folderId } }

        override suspend fun search(query: String): List<VaultFileEntity> =
            rows.filter { it.originalName.contains(query, ignoreCase = true) }
                .sortedBy { it.originalName.lowercase() }

        override suspend fun recentInFolder(folderId: Long?, limit: Int): List<VaultFileEntity> =
            rows.filter { it.folderId == folderId }
                .sortedByDescending { it.createdAt }
                .take(limit)

        override fun all(): Flow<List<VaultFileEntity>> = flow
        override suspend fun byId(id: Long): VaultFileEntity? = rows.firstOrNull { it.id == id }
        override suspend fun allOnce(): List<VaultFileEntity> = rows.toList()

        override suspend fun byNameInFolder(name: String, folderId: Long?): VaultFileEntity? =
            rows.firstOrNull { it.originalName == name && it.folderId == folderId }

        override suspend fun countLegacy(): Int = rows.count { it.encryptionVersion < 2 }
        override suspend fun allLegacy(): List<VaultFileEntity> = rows.filter { it.encryptionVersion < 2 }

        override suspend fun swapEncryptedObject(id: Long, newName: String, version: Int) =
            replace(id) { it.copy(encryptedName = newName, encryptionVersion = version) }

        override suspend fun setEncryptionVersion(id: Long, version: Int) =
            replace(id) { it.copy(encryptionVersion = version) }

        override suspend fun allLegacyVideos(): List<VaultFileEntity> =
            rows.filter { it.encryptionVersion < 2 && it.mimeType.startsWith("video/") }

        override suspend fun setThumbRef(id: Long, ref: String?) =
            replace(id) { it.copy(thumbRef = ref) }

        override suspend fun count(): Int = rows.size
        override suspend fun totalSize(): Long = rows.sumOf { it.size }

        override suspend fun insert(file: VaultFileEntity): Long {
            val id = if (file.id == 0L) nextId++ else file.id
            val idx = rows.indexOfFirst { it.id == id }
            if (idx >= 0) rows[idx] = file.copy(id = id) else rows.add(file.copy(id = id))
            publish()
            return id
        }

        override suspend fun update(file: VaultFileEntity) {
            insert(file)
        }

        override suspend fun moveToFolder(fileId: Long, folderId: Long?) =
            replace(fileId) { it.copy(folderId = folderId) }

        override suspend fun delete(id: Long) {
            rows.removeAll { it.id == id }
            publish()
        }

        override suspend fun deleteAllInFolder(folderId: Long) {
            rows.removeAll { it.folderId == folderId }
            publish()
        }
    }

    private class FakeFolderDao : FolderDao {
        val rows = mutableListOf<FolderEntity>()
        private val flow = MutableStateFlow<List<FolderEntity>>(emptyList())
        var nextId = 1L

        private fun publish() { flow.value = rows.toList() }

        override fun children(parentId: Long?): Flow<List<FolderEntity>> =
            flow.map { list ->
                list.filter { it.parentId == parentId }.sortedBy { it.name.lowercase() }
            }

        override suspend fun childrenOnce(parentId: Long?): List<FolderEntity> =
            rows.filter { it.parentId == parentId }.sortedBy { it.name.lowercase() }

        override suspend fun byId(id: Long): FolderEntity? = rows.firstOrNull { it.id == id }

        override suspend fun byName(parentId: Long?, name: String): FolderEntity? =
            rows.firstOrNull { it.parentId == parentId && it.name.equals(name, ignoreCase = true) }

        override suspend fun all(): List<FolderEntity> = rows.sortedBy { it.name.lowercase() }
        override suspend fun count(): Int = rows.size

        override suspend fun insert(folder: FolderEntity): Long {
            val id = if (folder.id == 0L) nextId++ else folder.id
            val idx = rows.indexOfFirst { it.id == id }
            if (idx >= 0) rows[idx] = folder.copy(id = id) else rows.add(folder.copy(id = id))
            publish()
            return id
        }

        override suspend fun update(folder: FolderEntity) {
            insert(folder)
        }

        override suspend fun delete(id: Long) {
            rows.removeAll { it.id == id }
            publish()
        }
    }

    // ---------- harness ----------

    private val fileDao = FakeVaultFileDao()
    private val folderDao = FakeFolderDao()

    // TemporaryFolder's root only exists after the rule runs, so resolve lazily.
    private val storage by lazy { VaultStorage(File(tmp.root, "vaultroot")) }

    private fun repo() = VaultRepository(ContextWrapper(null), fileDao, folderDao, storage)

    private fun file(
        name: String,
        folderId: Long? = null,
        size: Long = 0,
        createdAt: Long = 0,
        mime: String = "video/mp4",
        enc: String = "enc_$name",
        thumb: String? = null,
        version: Int = 2,
    ) = VaultFileEntity(
        encryptedName = enc, originalName = name, mimeType = mime, size = size,
        folderId = folderId, createdAt = createdAt, modifiedAt = createdAt,
        encryptionVersion = version, thumbRef = thumb,
    )

    private fun folder(name: String, parentId: Long? = null, createdAt: Long = 0) =
        FolderEntity(parentId = parentId, name = name, createdAt = createdAt)

    // ---------- sorting ----------

    @Test
    fun `observeEntries sorts by name ascending across folders and files`() = runBlocking {
        folderDao.insert(folder("Videos"))
        folderDao.insert(folder("Albums"))
        fileDao.insert(file("zebra.mp4", createdAt = 3))
        fileDao.insert(file("apple.jpg", createdAt = 1, mime = "image/jpeg"))

        val entries = repo().observeEntries(null, MutableStateFlow(SortOption.NAME_ASC)).first()
        assertEquals(
            listOf("Albums", "apple.jpg", "Videos", "zebra.mp4"),
            entries.map { label(it) },
        )
        Unit
    }

    @Test
    fun `observeEntries newest puts files before folders by timestamp`() = runBlocking {
        folderDao.insert(folder("OldFolder", createdAt = 0))
        fileDao.insert(file("new.mp4", createdAt = 1_000))
        fileDao.insert(file("old.mp4", createdAt = 500))

        val entries = repo().observeEntries(null, MutableStateFlow(SortOption.NEWEST)).first()
        assertEquals(listOf("new.mp4", "old.mp4", "OldFolder"), entries.map { label(it) })
        Unit
    }

    @Test
    fun `observeEntries largest sorts files by size descending`() = runBlocking {
        fileDao.insert(file("small.bin", size = 10, mime = "application/octet-stream"))
        fileDao.insert(file("big.bin", size = 1_000, mime = "application/octet-stream"))

        val entries = repo().observeEntries(null, MutableStateFlow(SortOption.LARGEST)).first()
        assertEquals(listOf("big.bin", "small.bin"), entries.map { label(it) })
        Unit
    }

    // ---------- search ----------

    @Test
    fun `searchFiles returns empty for blank query`() = runBlocking {
        fileDao.insert(file("holiday.mp4"))
        assertTrue(repo().searchFiles("").isEmpty())
        assertTrue(repo().searchFiles("   ").isEmpty())
        Unit
    }

    @Test
    fun `searchFiles matches case-insensitively like SQL LIKE`() = runBlocking {
        fileDao.insert(file("Vacation.mp4"))
        fileDao.insert(file("work.mp4"))
        val hits = repo().searchFiles("VACAT")
        assertEquals(listOf("Vacation.mp4"), hits.map { it.name })
        Unit
    }

    // ---------- folders ----------

    @Test
    fun `createFolder rejects blank name`() = runBlocking {
        try {
            repo().createFolder(null, "   ")
            throw AssertionError("expected IllegalArgumentException")
        } catch (expected: IllegalArgumentException) {
            assertTrue(expected.message!!.contains("kosong"))
        }
        Unit
    }

    @Test
    fun `renameFolder updates stored name`() = runBlocking {
        val id = repo().createFolder(null, "Old")
        repo().renameFolder(id, "  New  ")
        assertEquals("New", repo().folderName(id))
        Unit
    }

    @Test
    fun `deleteFolderRecursive purges nested rows AND encrypted objects on disk`() = runBlocking {
        val parent = repo().createFolder(null, "Parent")
        val child = repo().createFolder(parent, "Child")

        val keepId = fileDao.insert(file("keep.mp4"))                       // outside the tree
        val parentFile = fileDao.insert(file("in-parent.mp4", folderId = parent, enc = "p.enc"))
        val childFile = fileDao.insert(file("in-child.mp4", folderId = child, enc = "c.enc", thumb = "c_thumb.enc"))

        // Physical ciphertext + thumbnail the way VaultStorage would lay them out.
        storage.ensureDirs()
        File(storage.objectsDir, "p.enc").writeText("x")
        File(storage.objectsDir, "c.enc").writeText("x")
        File(storage.thumbsDir, "c_thumb.enc").writeText("x")
        File(storage.objectsDir, "keep.enc").writeText("x")

        repo().deleteFolderRecursive(parent)

        // DB rows gone for the tree, untouched outside it.
        assertTrue(fileDao.byId(parentFile) == null)
        assertTrue(fileDao.byId(childFile) == null)
        assertNotNull(fileDao.byId(keepId))
        assertTrue(folderDao.byId(parent) == null)
        assertTrue(folderDao.byId(child) == null)

        // Encrypted objects of deleted files must be gone; unrelated ones stay.
        assertTrue("parent ciphertext must be deleted", !File(storage.objectsDir, "p.enc").exists())
        assertTrue("child ciphertext must be deleted", !File(storage.objectsDir, "c.enc").exists())
        assertTrue("child thumbnail must be deleted", !File(storage.thumbsDir, "c_thumb.enc").exists())
        assertTrue("unrelated ciphertext must survive", File(storage.objectsDir, "keep.enc").exists())
        Unit
    }

    @Test
    fun `deleteFiles removes row and ciphertext but not strangers`() = runBlocking {
        val id = fileDao.insert(file("doomed.mp4", enc = "doomed.enc"))
        storage.ensureDirs()
        File(storage.objectsDir, "doomed.enc").writeText("x")
        File(storage.objectsDir, "other.enc").writeText("x")

        repo().deleteFiles(listOf(id))

        assertNull(fileDao.byId(id))
        assertTrue(!File(storage.objectsDir, "doomed.enc").exists())
        assertTrue(File(storage.objectsDir, "other.enc").exists())
        Unit
    }

    @Test
    fun `moveFileTo relocates row between folders`() = runBlocking {
        val src = repo().createFolder(null, "Src")
        val dst = repo().createFolder(null, "Dst")
        val id = fileDao.insert(file("movable.mp4", folderId = src))

        repo().moveFileTo(id, dst)
        assertEquals(dst, fileDao.byId(id)?.folderId)

        repo().moveFileTo(id, null)
        assertNull(fileDao.byId(id)?.folderId)
        Unit
    }

    // ---------- subtitles ----------

    @Test
    fun `findCompanionSubtitle matches same basename in same folder case-insensitively`() = runBlocking {
        val video = fileDao.insert(file("Film.mp4", folderId = 7))
        fileDao.insert(file("FILM.srt", folderId = 7, mime = "application/x-subrip"))
        fileDao.insert(file("Other.srt", folderId = 7, mime = "application/x-subrip"))
        fileDao.insert(file("Film.srt", folderId = 8, mime = "application/x-subrip")) // wrong folder

        val sub = repo().findCompanionSubtitle(video)
        assertNotNull("companion subtitle expected", sub)
        assertEquals("FILM.srt", sub!!.name)
        Unit
    }

    @Test
    fun `findCompanionSubtitle returns null when no basename match exists`() = runBlocking {
        val video = fileDao.insert(file("Lonely.mp4", folderId = 1))
        fileDao.insert(file("Different.srt", folderId = 1, mime = "application/x-subrip"))
        assertNull(repo().findCompanionSubtitle(video))
        Unit
    }

    @Test
    fun `listSubtitlesNear returns only sibling subtitles excluding the video`() = runBlocking {
        val video = fileDao.insert(file("Show.mp4", folderId = 3))
        fileDao.insert(file("Show.en.srt", folderId = 3, mime = "application/x-subrip"))
        fileDao.insert(file("Other.vtt", folderId = 3, mime = "text/vtt"))
        fileDao.insert(file("Show.mp4", folderId = 3, mime = "video/mp4"))      // another video
        fileDao.insert(file("Far.srt", folderId = 99, mime = "application/x-subrip"))

        val near = repo().listSubtitlesNear(video).map { it.name }.toSet()
        assertEquals(setOf("Show.en.srt", "Other.vtt"), near)
        Unit
    }

    @Test
    fun `listAllVaultSubtitles filters and sorts by lowercase name`() = runBlocking {
        fileDao.insert(file("b.srt", mime = "application/x-subrip"))
        fileDao.insert(file("A.vtt", mime = "text/vtt"))
        fileDao.insert(file("movie.mp4"))
        fileDao.insert(file("notes.zip", mime = "application/zip"))

        assertEquals(listOf("A.vtt", "b.srt"), repo().listAllVaultSubtitles().map { it.name })
        Unit
    }

    // ---------- stats ----------

    @Test
    fun `stats aggregates count size and folders`() = runBlocking {
        fileDao.insert(file("one.mp4", size = 10))
        fileDao.insert(file("two.mp4", size = 25))
        folderDao.insert(folder("Dir"))

        val s: VaultStats = repo().stats()
        assertEquals(2, s.fileCount)
        assertEquals(35L, s.totalSizeBytes)
        assertEquals(1, s.folderCount)
        Unit
    }

    private fun label(entry: VaultEntry): String = when (entry) {
        is VaultEntry.Folder -> entry.folder.name
        is VaultEntry.File -> entry.file.name
    }
}
