package id.bayu.mygalleryvault.core.playback

import android.media.MediaDataSource
import id.bayu.mygalleryvault.core.storage.VaultRandomReader

/**
 * Exposes a chunk-encrypted vault object as a seekable [MediaDataSource]
 * (API 23+) so MediaMetadataRetriever can read frames/duration through the
 * decrypted view without any plaintext touching disk.
 */
class VaultMediaDataSource(
    private val reader: VaultRandomReader,
) : MediaDataSource() {

    override fun getSize(): Long = reader.plainSize

    override fun readAt(position: Long, buffer: ByteArray, offset: Int, size: Int): Int {
        if (position >= reader.plainSize) return -1
        return synchronized(reader) { reader.readAt(position, buffer, offset, size) }
    }

    override fun close() {
        reader.close()
    }
}

/**
 * [android.media.MediaMetadataRetriever] gained AutoCloseable/close() only in
 * API 29; older devices must use release(). Centralized here so call sites can
 * stay tidy and safe across all supported APIs.
 */
inline fun <T> MediaDataSource.useWithRetriever(block: (android.media.MediaMetadataRetriever) -> T): T {
    val retriever = android.media.MediaMetadataRetriever()
    var closed = false
    try {
        retriever.setDataSource(this)
        return block(retriever)
    } finally {
        closed = true
        if (android.os.Build.VERSION.SDK_INT >= 29) {
            runCatching { retriever.close() }
        } else {
            @Suppress("DEPRECATION")
            runCatching { retriever.release() }
        }
    }
}
