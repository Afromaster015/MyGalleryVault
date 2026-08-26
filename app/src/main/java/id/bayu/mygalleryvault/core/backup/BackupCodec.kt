package id.bayu.mygalleryvault.core.backup

import id.bayu.mygalleryvault.core.crypto.CryptoEngine
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.security.GeneralSecurityException
import java.security.SecureRandom
import javax.crypto.SecretKey
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/** Thrown for any invalid/corrupted/wrong-PIN backup condition (PRD §37a.3, §37a.6). */
class BackupException(message: String) : IOException(message)

/**
 * Binary codec for the proprietary `.svbackup` container (PRD §37a.1).
 *
 * Layout (big-endian):
 *   MAGIC "SVBK" | VERSION(1) | ALG(1) | salt(16) | iterations(u32) |
 *   wrappedLen(u32) | wrappedMaster  <- master key of HP1 wrapped by PIN-KEK (GCM)
 *   metaLen(u32) | metaBlob        <- JSON metadata encrypted with HP1 master
 *   items...: pathLen(u16) | path(utf8) | dataLen(u64) | raw .enc bytes
 *   terminator pathLen = 0
 *
 * Object/thumbnail payloads stay in their original HP1-encrypted form inside the
 * container; nothing is ever plaintext on disk or in transit (PRD §37a.6).
 */
object BackupCodec {

    const val FILE_EXTENSION = "svbackup"
    const val MIME_TYPE = "application/octet-stream"

    private val MAGIC = byteArrayOf(0x53, 0x56, 0x42, 0x4B) // "SVBK"
    private const val VERSION: Byte = 1
    private const val ALG_PBKDF2_AES256_GCM: Byte = 0x01
    private const val SALT_BYTES = 16
    private const val GCM_TAG_BITS = 128
    const val DEFAULT_ITERATIONS = 310_000
    const val OBJECT_PREFIX = "o/"
    const val THUMB_PREFIX = "t/"

    class Header(
        val salt: ByteArray,
        val iterations: Int,
    )

    fun readHeader(input: InputStream): Header {
        val din = DataInputStream(input)
        try {
            val magic = ByteArray(MAGIC.size)
            din.readFully(magic)
            if (!magic.contentEquals(MAGIC)) throw BackupException("File bukan backup SecureVault yang valid")
            val version = din.readByte()
            if (version != VERSION) throw BackupException("Versi format backup tidak didukung")
            if (din.readByte() != ALG_PBKDF2_AES256_GCM) {
                throw BackupException("Algoritma backup tidak dikenal")
            }
            val salt = ByteArray(SALT_BYTES)
            din.readFully(salt)
            val iterations = din.readInt()
            if (iterations < 10_000) throw BackupException("Parameter KDF tidak valid")
            return Header(salt, iterations)
        } catch (e: BackupException) {
            throw e
        } catch (_: Exception) {
            throw BackupException("File backup rusak atau tidak lengkap")
        }
    }

    fun writeHeader(output: OutputStream, salt: ByteArray, iterations: Int) {
        val dout = DataOutputStream(output)
        dout.write(MAGIC)
        dout.writeByte(VERSION.toInt())
        dout.writeByte(ALG_PBKDF2_AES256_GCM.toInt())
        dout.write(salt)
        dout.writeInt(iterations)
        dout.flush()
    }

    /** Wraps [masterRaw] using a KEK derived from [pin] + [header] params. GCM tag gives integrity. */
    @Throws(BackupException::class)
    fun writeWrappedMaster(output: OutputStream, header: Header, pin: CharArray, masterRaw: ByteArray) {
        val dout = DataOutputStream(output)
        try {
            val blob = CryptoEngine.encryptBytes(masterRaw, deriveKek(pin, header))
            dout.writeInt(blob.size)
            dout.write(blob)
            dout.flush()
        } catch (e: BackupException) {
            throw e
        } catch (_: Exception) {
            throw BackupException("Gagal mengunci backup")
        }
    }

    /** Unwraps the HP1 master key. Wrong PIN / corruption -> [BackupException] (no detail oracle). */
    @Throws(BackupException::class)
    fun readWrappedMaster(input: InputStream, header: Header, pin: CharArray): SecretKey {
        val din = DataInputStream(input)
        return try {
            val len = din.readInt()
            if (len <= 0 || len > 4096) throw BackupException("File backup rusak")
            val blob = ByteArray(len)
            din.readFully(blob)
            val raw = CryptoEngine.decryptBytes(blob, deriveKek(pin, header))
            SecretKeySpec(raw, "AES")
        } catch (e: BackupException) {
            throw e
        } catch (_: Exception) {
            // Deliberately indistinguishable between wrong PIN and tampering (anti-oracle, §37a.3).
            throw BackupException("PIN salah atau file backup tidak valid")
        }
    }

    fun writeEncryptedBlock(output: OutputStream, data: ByteArray, key: SecretKey) {
        val dout = DataOutputStream(output)
        try {
            val blob = CryptoEngine.encryptBytes(data, key)
            dout.writeInt(blob.size)
            dout.write(blob)
            dout.flush()
        } catch (_: Exception) {
            throw BackupException("Gagal menulis metadata backup")
        }
    }

    fun readEncryptedBlock(input: InputStream, key: SecretKey): ByteArray {
        val din = DataInputStream(input)
        return try {
            val len = din.readInt()
            if (len <= 0 || len > 64 * 1024 * 1024) throw BackupException("File backup rusak")
            val blob = ByteArray(len)
            din.readFully(blob)
            CryptoEngine.decryptBytes(blob, key)
        } catch (e: BackupException) {
            throw e
        } catch (_: Exception) {
            throw BackupException("Metadata backup tidak dapat diverifikasi; file mungkin dimodifikasi")
        }
    }

    fun writeItem(output: OutputStream, path: String, copyFrom: InputStream, size: Long) {
        val dout = DataOutputStream(output)
        try {
            val pathBytes = path.toByteArray(Charsets.UTF_8)
            if (pathBytes.size > UShort.MAX_VALUE.toInt()) throw IOException("Path too long")
            dout.writeShort(pathBytes.size)
            dout.write(pathBytes)
            dout.writeLong(size)
            copyFrom.use { input ->
                val buf = ByteArray(DEFAULT_BUFFER_SIZE * 16)
                var remaining = size
                while (remaining > 0) {
                    val read = input.read(buf, 0, minOf(buf.size.toLong(), remaining).toInt())
                    if (read == -1) throw IOException("Unexpected end of object data")
                    dout.write(buf, 0, read)
                    remaining -= read
                }
            }
            dout.flush()
        } catch (_: Exception) {
            throw BackupException("Gagal menulis item backup")
        }
    }

    /**
     * Reads one body item and hands [consumer] the path plus a stream bounded to
     * exactly the payload length. Returns the path, or null at the terminator
     * (end of container). The consumer MUST fully consume the stream.
     */
    fun readItem(input: InputStream, consumer: (path: String, data: InputStream, size: Long) -> Unit): String? {
        val din = DataInputStream(input)
        val pathLen = try {
            din.readUnsignedShort()
        } catch (_: Exception) {
            throw BackupException("File backup berakhir secara tak terduga")
        }
        if (pathLen == 0) return null
        val pathBytes = ByteArray(pathLen)
        try {
            din.readFully(pathBytes)
            val size = din.readLong()
            if (size < 0) throw BackupException("Ukuran item backup tidak valid")
            val bounded = BoundedInput(din, size)
            val path = String(pathBytes, Charsets.UTF_8)
            consumer(path, bounded, size)
            if (bounded.remaining != 0L) throw BackupException("Item backup tidak terbaca penuh")
            return path
        } catch (e: BackupException) {
            throw e
        } catch (_: Exception) {
            throw BackupException("Item backup rusak selama proses baca")
        }
    }

    private class BoundedInput(private val din: DataInputStream, initialRemaining: Long) : InputStream() {
        var remaining: Long = initialRemaining
            private set

        override fun read(): Int {
            if (remaining <= 0) return -1
            val v = din.read()
            if (v >= 0) remaining--
            return v
        }

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (remaining <= 0L) return -1
            val n = din.read(b, off, minOf(len.toLong(), remaining).toInt())
            if (n > 0) remaining -= n
            return n
        }
    }

    fun writeTerminator(output: OutputStream) {
        val dout = DataOutputStream(output)
        try {
            dout.writeShort(0)
            dout.flush()
        } catch (_: Exception) {
            throw BackupException("Gagal menutup backup")
        }
    }

    fun newSalt(): ByteArray = ByteArray(SALT_BYTES).also { SecureRandom().nextBytes(it) }

    private fun deriveKek(pin: CharArray, header: Header): SecretKey {
        val spec = PBEKeySpec(pin, header.salt, header.iterations, 256)
        return try {
            val factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
            SecretKeySpec(factory.generateSecret(spec).encoded, "AES")
        } catch (_: GeneralSecurityException) {
            throw BackupException("Perangkat tidak mendukung PBKDF2")
        } finally {
            spec.clearPassword()
        }
    }
}
