package id.bayu.mygalleryvault.domain.model

import java.io.IOException

/** Whether a tracked transfer writes into the vault (import) or out of it (export). */
enum class TransferKind {
    IMPORT,
    EXPORT,
    /** Encrypted .svbackup export (Settings → Backup). */
    BACKUP,
    /** Encrypted .svbackup restore/re-import into this device. */
    RESTORE,
}

/**
 * Realtime per-item transfer progress for multi-file import/export.
 *
 * UI contract: `currentItemName (currentItemIndex/totalItems)` is shown while a
 * determinate bar tracks the current item's byte fraction; overall progress is
 * derived from completed items plus the in-flight item.
 */
data class TransferProgress(
    val kind: TransferKind,
    val totalItems: Int,
    val completedItems: Int,
    val currentItemName: String,
    val currentItemIndex: Int,
    val itemBytesDone: Long,
    val itemBytesTotal: Long,
) {
    /** 0..1 of the current item, or null when the source size is unknown. */
    val itemFraction: Float?
        get() = if (itemBytesTotal > 0) {
            (itemBytesDone.toDouble() / itemBytesTotal).toFloat().coerceIn(0f, 1f)
        } else null

    /** Count-based overall progress; items with unknown size contribute only when finished. */
    val overallFraction: Float
        get() = if (totalItems <= 0) {
            1f
        } else {
            ((completedItems + (itemFraction ?: 0f)).toDouble() / totalItems)
                .toFloat()
                .coerceIn(0f, 1f)
        }

    companion object {
        /** Progress snapshot for a single-item transfer (e.g. one export). */
        fun single(kind: TransferKind, itemName: String, bytesDone: Long, bytesTotal: Long): TransferProgress =
            TransferProgress(
                kind = kind,
                totalItems = 1,
                completedItems = 0,
                currentItemName = itemName,
                currentItemIndex = 1,
                itemBytesDone = bytesDone,
                itemBytesTotal = bytesTotal,
            )
    }
}

/** Thrown when the user cancels an in-flight transfer; [completedItems] survived before abort. */
class TransferCancelledException(val completedItems: Int, message: String) : IOException(message)

/** Internal cooperative-cancellation signal raised from inside stream wrappers. */
internal class CancelledSignal : RuntimeException("cancelled")
