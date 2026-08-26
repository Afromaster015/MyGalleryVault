package id.bayu.mygalleryvault.core.crypto

import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.EOFException
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.security.GeneralSecurityException
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.CipherInputStream
import javax.crypto.CipherOutputStream
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

class IntegrityViolationException(message: String) : GeneralSecurityException(message)

object CryptoEngine {

    internal const val TRANSFORMATION = "AES/GCM/NoPadding"
    private const val KEY_ALGORITHM = "AES"
    internal const val GCM_TAG_BITS = 128
    internal const val GCM_TAG_BYTES = GCM_TAG_BITS / 8
    private const val GCM_NONCE_BYTES = 12
    internal val HEADER_SIZE = 4 + 1 + 1 + GCM_NONCE_BYTES

    internal val MAGIC = byteArrayOf(0x53, 0x56, 0x4C, 0x54) // "SVLT"
    internal const val FORMAT_V1: Byte = 1
    internal const val ALG_AES256_GCM: Byte = 0x01

    private val random = SecureRandom()

    fun newNonce(): ByteArray {
        val nonce = ByteArray(GCM_NONCE_BYTES)
        random.nextBytes(nonce)
        return nonce
    }

    /**
     * Encrypts using format v2 (chunked AES-GCM, see [ChunkedCipher]) so large
     * media remain randomly accessible without a full decrypt pass.
     * NOTE: buffering is the caller's responsibility - any wrapper added here
     * must be flushed/closed by whoever creates it.
     */
    fun encryptStream(input: InputStream, output: OutputStream, key: SecretKey) {
        ChunkedCipher.encrypt(input, output, key)
    }

    /** Routes between the legacy single-stream (v1) and chunked (v2) formats. */
    fun decryptStream(input: InputStream, output: OutputStream, key: SecretKey) {
        val raw = BufferedInputStream(input)
        raw.mark(6)
        val probe = ByteArray(5)
        var off = 0
        while (off < probe.size) {
            val r = raw.read(probe, off, probe.size - off)
            if (r == -1) throw EOFException("Encrypted stream too short")
            off += r
        }
        raw.reset()
        try {
            if (probe[4] >= ChunkedCipher.FORMAT_VERSION) {
                ChunkedCipher.decrypt(raw, output, key)
            } else {
                decryptV1(raw, output, key)
            }
        } catch (e: IOException) {
            val cause = e.cause
            if (cause is javax.crypto.AEADBadTagException) {
                throw IntegrityViolationException("Data has been modified or corrupted")
            }
            throw e
        }
    }

    /** Legacy v1 path: [MAGIC][ver=1][alg][nonce][single GCM stream]. */
    private fun decryptV1(raw: InputStream, output: OutputStream, key: SecretKey) {
        val header = ByteArray(HEADER_SIZE)
        var off = 0
        while (off < HEADER_SIZE) {
            val r = raw.read(header, off, HEADER_SIZE - off)
            if (r == -1) throw EOFException("Encrypted stream too short")
            off += r
        }
        validateHeader(header, FORMAT_V1)
        val nonce = header.copyOfRange(6, HEADER_SIZE)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(GCM_TAG_BITS, nonce))
        CipherInputStream(raw, cipher).use { cin ->
            val buf = ByteArray(DEFAULT_BUFFER_SIZE * 8)
            while (true) {
                val read = cin.read(buf)
                if (read == -1) break
                output.write(buf, 0, read)
            }
            output.flush()
        }
    }

    fun encryptBytes(data: ByteArray, key: SecretKey): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        encryptStream(java.io.ByteArrayInputStream(data), out, key)
        return out.toByteArray()
    }

    fun decryptBytes(data: ByteArray, key: SecretKey): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        decryptStream(java.io.ByteArrayInputStream(data), out, key)
        return out.toByteArray()
    }

    fun isEncryptedFormat(file: java.io.File): Boolean {
        if (!file.exists() || file.length() < HEADER_SIZE) return false
        return try {
            java.io.RandomAccessFile(file, "r").use { raf ->
                val header = ByteArray(HEADER_SIZE)
                raf.readFully(header)
                validateHeader(header, acceptedVersion = null)
                true
            }
        } catch (_: Exception) {
            false
        }
    }

    private fun validateHeader(header: ByteArray, acceptedVersion: Byte?) {
        for (i in MAGIC.indices) {
            if (header[i] != MAGIC[i]) throw IntegrityViolationException("Not a vault encrypted file")
        }
        val ver = header[4]
        if (acceptedVersion != null && ver != acceptedVersion) throw IntegrityViolationException("Unsupported format version")
        if (acceptedVersion == null && ver !in listOf(FORMAT_V1, ChunkedCipher.FORMAT_VERSION)) {
            throw IntegrityViolationException("Unsupported format version")
        }
        if (header[5] != ALG_AES256_GCM) throw IntegrityViolationException("Unsupported algorithm")
    }
}
