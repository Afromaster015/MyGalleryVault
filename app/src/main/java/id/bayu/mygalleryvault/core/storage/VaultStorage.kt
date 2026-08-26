package id.bayu.mygalleryvault.core.storage

import android.content.Context
import android.graphics.Bitmap
import id.bayu.mygalleryvault.core.crypto.CryptoEngine
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.security.SecureRandom
import javax.crypto.SecretKey

/** Above this plaintext size, import verification checks head+tail chunks only. */
private const val FULL_VERIFY_LIMIT = 256L * 1024 * 1024

/**
 * Manages the encrypted object store inside app-private storage (PRD §11).
 * Layout per slot (neutral sub-dir names, PRD §26):
 * files/vault/{slotDir}/objects/ and files/vault/{slotDir}/thumbs/
 */
class VaultStorage(private val context: Context, slotDir: String? = null) {

    private val baseDir: File =
        if (slotDir == null) File(context.filesDir, "vault") else File(context.filesDir, "vault/$slotDir")

    private val random = SecureRandom()

    val objectsDir: File get() = File(baseDir, "objects")
    val thumbsDir: File get() = File(baseDir, "thumbs")

    /** Root of this slot's storage; used when wiping a slot entirely. */
    val rootDir: File get() = baseDir

    fun wipeAll() {
        baseDir.deleteRecursively()
    }

    fun ensureDirs() {
        objectsDir.mkdirs()
        thumbsDir.mkdirs()
    }

    fun newEncryptedName(): String {
        val bytes = ByteArray(16)
        random.nextBytes(bytes)
        val sb = StringBuilder(32)
        for (b in bytes) sb.append("%02x".format(b))
        return "$sb.enc"
    }

    /**
     * Streams [input] through chunked AES-GCM (format v2) into a temporary
     * file, then verifies integrity before atomically renaming it into place
     * (PRD §10/§47). Verification is a full decrypt pass for files up to
     * [FULL_VERIFY_LIMIT]; larger ones get head+tail chunk checks so a 5 GB
     * video does not double its import time.
     */
    fun importStream(input: java.io.InputStream, key: SecretKey): Pair<String, Long> {
        ensureDirs()
        val finalName = newEncryptedName()
        val tmp = File(objectsDir, "$finalName.tmp")
        var plainSize = 0L
        try {
            tmp.outputStream().buffered().use { fos ->
                val counting = object : java.io.InputStream() {
                    override fun read(): Int {
                        val v = input.read()
                        if (v >= 0) plainSize++
                        return v
                    }

                    override fun read(b: ByteArray, off: Int, len: Int): Int {
                        val n = input.read(b, off, len)
                        if (n > 0) plainSize += n
                        return n
                    }
                }
                CryptoEngine.encryptStream(counting, fos, key)
            }
            if (plainSize <= FULL_VERIFY_LIMIT) {
                tmp.inputStream().buffered().use { fis ->
                    CryptoEngine.decryptStream(fis, NullOutputStream, key)
                }
            } else {
                VaultRandomReader.open(tmp, key).use { r ->
                    val probe = ByteArray(1)
                    require(r.readAt(0, probe, 0, 1) == 1)
                    require(r.readAt(r.plainSize - 1, probe, 0, 1) == 1)
                }
            }
            val target = File(objectsDir, finalName)
            if (!tmp.renameTo(target)) {
                tmp.copyTo(target, overwrite = true)
                tmp.delete()
            }
            return finalName to plainSize
        } finally {
            tmp.delete()
        }
    }

    /**
     * Random-access decrypted view over a v2 object. Powers instant-seek
     * playback and video thumbnails without materializing plaintext (PRD §14).
     * Throws [IntegrityViolationException] for v1 containers - callers fall
     * back to [openDecrypted].
     */
    fun openRandomReader(encryptedName: String, key: SecretKey): VaultRandomReader =
        VaultRandomReader.open(File(objectsDir, encryptedName), key)

    fun openDecrypted(encryptedName: String, key: SecretKey, output: java.io.OutputStream) {
        val f = File(objectsDir, encryptedName)
        if (!f.exists()) throw IOException("Encrypted object missing")
        f.inputStream().buffered().use { fis ->
            CryptoEngine.decryptStream(fis, output, key)
        }
    }

    fun readDecrypted(encryptedName: String, key: SecretKey): ByteArray {
        val out = ByteArrayOutputStream()
        openDecrypted(encryptedName, key, out)
        return out.toByteArray()
    }

    fun objectSize(encryptedName: String): Long = File(objectsDir, encryptedName).length()

    /**
     * Detects the actual encryption version from the file header.
     * This is needed because the DB version might not match the actual file format
     * (e.g., files imported with v1 DB flag but v2 actual format).
     */
    fun detectEncryptionVersion(encryptedName: String): Int {
        val f = File(objectsDir, encryptedName)
        if (!f.exists()) return 2
        return try {
            java.io.RandomAccessFile(f, "r").use { raf ->
                val header = ByteArray(6)
                raf.readFully(header)
                // Check magic "SVLT"
                if (header[0] == 0x53.toByte() && header[1] == 0x56.toByte() &&
                    header[2] == 0x4C.toByte() && header[3] == 0x54.toByte()
                ) {
                    header[4].toInt() // version byte
                } else {
                    2 // default to v2 if can't detect
                }
            }
        } catch (_: Exception) {
            2 // default to v2 on error
        }
    }

    fun deleteObject(encryptedName: String) {
        File(objectsDir, encryptedName).delete()
    }

    fun saveThumbnail(bytes: ByteArray, key: SecretKey): String {
        ensureDirs()
        val ref = newEncryptedName()
        val tmp = File(thumbsDir, "$ref.tmp")
        tmp.outputStream().buffered().use { CryptoEngine.encryptStream(bytes.inputStream(), it, key) }
        val target = File(thumbsDir, ref)
        if (!tmp.renameTo(target)) {
            tmp.copyTo(target, overwrite = true)
            tmp.delete()
        }
        return ref
    }

    fun loadThumbnail(ref: String, key: SecretKey): Bitmap? {
        val f = File(thumbsDir, ref)
        if (!f.exists()) return null
        return try {
            val bytes = readDecrypted(f.name, key)
            android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
        } catch (_: Exception) {
            null
        }
    }

    fun deleteThumbnail(ref: String?) {
        if (ref != null) File(thumbsDir, ref).delete()
    }

    /** Removes orphan encrypted objects/thumbs not referenced by any DB row (crash recovery, PRD §47). */
    fun reconcileOrphans(validObjectNames: Set<String>, validThumbRefs: Set<String>) {
        ensureDirs()
        val pending = pendingNames()
        objectsDir.listFiles()?.forEach { f ->
            if (f.name.endsWith(".enc") && f.name !in validObjectNames && f.name !in pending) {
                f.delete()
            }
        }
        thumbsDir.listFiles()?.forEach { f ->
            if (f.name.endsWith(".enc") && f.name !in validThumbRefs) {
                f.delete()
            }
        }
    }

    private fun pendingNames(): Set<String> =
        objectsDir.listFiles()
            ?.filter { it.name.endsWith(".tmp") }
            ?.map { it.name.removeSuffix(".tmp") }
            ?.toSet()
            ?: emptySet()
}

private object NullOutputStream : java.io.OutputStream() {
    override fun write(b: Int) {}
    override fun write(b: ByteArray, off: Int, len: Int) {}
}

/**
 * Thin wrapper around [ChunkedCipher.Reader] so callers never touch the raw
 * crypto layer. One open instance serves unlimited seeks with a single-chunk
 * in-memory cache; keep one per playback session and close it when done.
 */
class VaultRandomReader private constructor(
    private val reader: id.bayu.mygalleryvault.core.crypto.ChunkedCipher.Reader,
) : AutoCloseable {

    val plainSize: Long get() = reader.plainSize

    fun readAt(offset: Long, buf: ByteArray, off: Int, len: Int): Int =
        reader.readAt(offset, buf, off, len)

    override fun close() = reader.close()

    companion object {
        fun open(file: File, key: javax.crypto.SecretKey): VaultRandomReader {
            val raf = java.io.RandomAccessFile(file, "r")
            try {
                return VaultRandomReader(
                    id.bayu.mygalleryvault.core.crypto.ChunkedCipher.Reader(raf, key)
                )
            } catch (t: Throwable) {
                runCatching { raf.close() }
                throw t
            }
        }
    }
}
