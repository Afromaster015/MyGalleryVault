package id.bayu.mygalleryvault.data.repository

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import id.bayu.mygalleryvault.core.crypto.VaultSession
import id.bayu.mygalleryvault.core.playback.VaultMediaDataSource
import id.bayu.mygalleryvault.core.playback.useWithRetriever
import id.bayu.mygalleryvault.core.storage.VaultStorage
import id.bayu.mygalleryvault.data.local.VaultFileDao
import id.bayu.mygalleryvault.data.local.VaultFileEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Thumbnail generation and media duration probing over the encrypted vault.
 * Both share ranged decryption through [VaultMediaDataSource], which is why
 * the duration probe lives here next to frame extraction.
 */
class ThumbnailService(
    private val context: Context,
    private val fileDao: VaultFileDao,
    private val storage: VaultStorage,
) {

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

    /**
     * Extracts a frame from a stored video using ranged decryption and stores
     * it as an encrypted thumbnail. Works regardless of where the MP4 moov
     * atom sits because [android.media.MediaDataSource] seeks freely over the
     * decrypted view. Safe to call repeatedly; no-op when not applicable.
     */
    suspend fun generateVideoThumbnail(fileId: Long): Boolean {
        val entity0 = fileDao.byId(fileId) ?: return false
        if (!entity0.mimeType.startsWith("video/")) return false
        val key = VaultSession.masterKey ?: return false
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

    /**
     * Builds the missing image thumbnail on demand from the stored encrypted
     * object (used when an import-time decode failed or legacy rows predate it).
     */
    suspend fun generateImageThumbnail(fileId: Long): Boolean {
        val entity0 = fileDao.byId(fileId) ?: return false
        if (!entity0.mimeType.startsWith("image/")) return false
        if (entity0.size <= 0 || entity0.size > MAX_THUMB_SOURCE_BYTES) return false
        val key = VaultSession.masterKey ?: return false
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

    suspend fun getThumbnailBitmap(fileId: Long): Bitmap? {
        val key = VaultSession.masterKey ?: return null
        val entity = fileDao.byId(fileId) ?: return null
        val ref = entity.thumbRef ?: return null
        return withContext(Dispatchers.IO) { storage.loadThumbnail(ref, key) }
    }

    /** Import-time thumbnail decoded straight from a SAF content URI; null when undecodable. */
    fun generateAndStoreThumbnail(uri: Uri, key: javax.crypto.SecretKey): String? {
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

            storeThumbnailBitmap(bitmap, key)
        } catch (_: Exception) {
            null
        }
    }

    companion object {
        const val THUMB_SIZE = 256

        /** Upper bound for decrypting an image fully into RAM just to build its thumbnail. */
        private const val MAX_THUMB_SOURCE_BYTES = 128L * 1024 * 1024

        /**
         * Above this container size the platform MediaMetadataRetriever tends to
         * crash (native >2 GiB truncation on many builds); playback itself keeps
         * working through ExoPlayer + [VaultMediaDataSource].
         */
        private const val MAX_RETRIEVER_SOURCE_BYTES = 2_000_000_000L
    }
}
