package id.bayu.mygalleryvault

import id.bayu.mygalleryvault.domain.model.VaultEntry
import id.bayu.mygalleryvault.domain.model.VaultFile
import id.bayu.mygalleryvault.domain.model.VaultFolder
import id.bayu.mygalleryvault.ui.screens.home.GallerySelection
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The Gallery selection has to keep file ids and folder ids apart. They come from two tables with
 * separate autoincrement keys, so "file 3" and "folder 3" are both real and unrelated, and the
 * selection is what decides whether an id is handed to the file deleter or the folder deleter.
 */
class GallerySelectionTest {

    private fun file(id: Long) = VaultEntry.File(
        VaultFile(
            id = id,
            name = "f$id",
            mimeType = "image/jpeg",
            size = 1L,
            folderId = null,
            createdAt = 0L,
            modifiedAt = 0L,
            hasThumbnail = false,
            isImage = true,
            isVideo = false,
        ),
    )

    private fun folder(id: Long) = VaultEntry.Folder(VaultFolder(id = id, name = "d$id", parentId = null))

    /** The reported defect: one long-press must select exactly the entry that was held. */
    @Test
    fun `holding one entry selects exactly one entry`() {
        val selection = GallerySelection.Empty.toggle(file(3))

        assertEquals(1, selection.count)
        assertTrue(selection.isFileSelected(3))
        assertFalse(selection.isFolderSelected(3))
    }

    /** The old bare id list marked both, because 3 == 3 across the two tables. */
    @Test
    fun `a file and a folder that share a number are separate entries`() {
        val selection = GallerySelection.Empty.toggle(file(3)).toggle(folder(5))

        assertTrue(selection.isFileSelected(3))
        assertFalse(selection.isFolderSelected(3))
        assertTrue(selection.isFolderSelected(5))
        assertFalse(selection.isFileSelected(5))
        assertEquals(2, selection.count)
    }

    /** Delete and move route by kind, so a folder must never land in the file lists. */
    @Test
    fun `folders and files end up in their own lists`() {
        val selection = GallerySelection.Empty
            .toggle(file(7))
            .toggle(folder(7))

        assertEquals(setOf(7L), selection.fileIds)
        assertEquals(setOf(7L), selection.folderIds)
        assertEquals(2, selection.count)
    }

    @Test
    fun `toggling the same entry twice deselects it`() {
        val selection = GallerySelection.Empty.toggle(file(1)).toggle(file(1))

        assertTrue(selection.isEmpty)
        assertEquals(0, selection.count)
    }

    @Test
    fun `selection is only empty when both kinds are`() {
        assertTrue(GallerySelection.Empty.isEmpty)
        assertFalse(GallerySelection.Empty.toggleFolder(2).isEmpty)
    }

    /** "Pilih semua" keeps taking the files at this level and leaves folders alone, as before. */
    @Test
    fun `select all takes files only`() {
        val selection = GallerySelection.allFiles(listOf(1L, 2L, 3L))

        assertEquals(3, selection.count)
        assertTrue(selection.folderIds.isEmpty())
    }
}
