package id.bayu.mygalleryvault.ui.screens.home

import id.bayu.mygalleryvault.domain.model.VaultEntry

/**
 * What the Gallery currently has selected.
 *
 * Files and folders live in two tables with two independent id sequences, so both start at 1 and
 * collide constantly. A single bare list of ids therefore cannot tell folder 5 from file 5, and
 * that matters twice: the wrong tile lights up, and, because the selection decides which list
 * an id is handed to, the wrong instruction runs (a folder sent to the file deleter instead of
 * the folder deleter). Keeping the two sets apart is the only place that distinction has to exist.
 */
data class GallerySelection(
    val fileIds: Set<Long> = emptySet(),
    val folderIds: Set<Long> = emptySet(),
) {
    val isEmpty: Boolean get() = fileIds.isEmpty() && folderIds.isEmpty()
    val count: Int get() = fileIds.size + folderIds.size

    fun isFileSelected(id: Long): Boolean = id in fileIds
    fun isFolderSelected(id: Long): Boolean = id in folderIds

    fun toggleFile(id: Long): GallerySelection = copy(fileIds = fileIds.toggled(id))
    fun toggleFolder(id: Long): GallerySelection = copy(folderIds = folderIds.toggled(id))

    /** True for whichever kind this entry is, so callers holding only an entry stay kind-blind. */
    fun isSelected(entry: VaultEntry): Boolean = when (entry) {
        is VaultEntry.Folder -> isFolderSelected(entry.folder.id)
        is VaultEntry.File -> isFileSelected(entry.file.id)
    }

    fun toggle(entry: VaultEntry): GallerySelection = when (entry) {
        is VaultEntry.Folder -> toggleFolder(entry.folder.id)
        is VaultEntry.File -> toggleFile(entry.file.id)
    }

    companion object {
        val Empty = GallerySelection()

        /** "Pilih semua" takes the files at this level, which is what it has always done. */
        fun allFiles(ids: Collection<Long>): GallerySelection =
            GallerySelection(fileIds = ids.toSet())
    }
}

private fun Set<Long>.toggled(id: Long): Set<Long> = if (id in this) this - id else this + id
