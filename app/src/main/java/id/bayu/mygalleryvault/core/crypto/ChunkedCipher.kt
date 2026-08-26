package id.bayu.mygalleryvault.core.crypto

import java.io.DataInputStream
import java.io.EOFException
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.io.RandomAccessFile
import java.security.GeneralSecurityException
import javax.crypto.Cipher
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Chunked authenticated encryption (vault format v2, PRD §6-7 adapted for
 * large media).
 *
 * Layout (big-endian):
 *   MAGIC "SVLT"(4) | VERSION=2(1) | ALG=AES256-GCM(1) | chunkSize(u32) | fileNonce(12)
 *   followed by N chunks, each = GCM ciphertext+tag of [chunkSize] plaintext
 *   bytes (last chunk may be shorter). Per-chunk nonce = fileNonce XOR be32(index)
 *   which guarantees uniqueness within a file and across files (PRD §7).
 *
 * Enables random-access decryption: only the chunks covering a requested byte
 * range are ever decrypted - the basis of instant-seek video playback without
 * any plaintext touching disk.
 */
object ChunkedCipher {

    const val FORMAT_VERSION: Byte = 2
    const val DEFAULT_CHUNK_SIZE = 4 * 1024 * 1024
    val HEADER_SIZE = 4 + 1 + 1 + 4 + 12

    class Header(
        val chunkSize: Int,
        val fileNonce: ByteArray,
        /** Total plaintext length derivable from the container size. */
        val plainSize: Long,
        val chunkCount: Int,
    )

    fun newFileNonce(): ByteArray = CryptoEngine.newNonce()

    fun chunkNonce(fileNonce: ByteArray, index: Long): ByteArray =
        fileNonce.clone().also {
            it[0] = (it[0].toInt() xor (index ushr 24).toInt()).toByte()
            it[1] = (it[1].toInt() xor (index ushr 16).toInt()).toByte()
            it[2] = (it[2].toInt() xor (index ushr 8).toInt()).toByte()
            it[3] = (it[3].toInt() xor index.toInt()).toByte()
        }

    fun writeHeader(output: OutputStream, chunkSize: Int, fileNonce: ByteArray) {
        output.write(CryptoEngine.MAGIC)
        output.write(FORMAT_VERSION.toInt())
        output.write(CryptoEngine.ALG_AES256_GCM.toInt())
        output.write(byteArrayOf(
            (chunkSize ushr 24).toByte(), (chunkSize ushr 16).toByte(),
            (chunkSize ushr 8).toByte(), chunkSize.toByte(),
        ))
        output.write(fileNonce)
    }

    /** Streaming encryptor producing the v2 layout. Returns the resulting header info. */
    fun encrypt(input: InputStream, output: OutputStream, key: SecretKey): Header =
        encrypt(input, output, key, DEFAULT_CHUNK_SIZE)

    fun encrypt(input: InputStream, output: OutputStream, key: SecretKey, chunkSize: Int): Header {
        require(chunkSize >= 8 * 1024) { "chunkSize too small" }
        val fileNonce = newFileNonce()
        writeHeader(output, chunkSize, fileNonce)

        val plain = ByteArray(chunkSize)
        var index = 0L
        var plainSize = 0L
        while (true) {
            var filled = 0
            while (filled < chunkSize) {
                val r = input.read(plain, filled, chunkSize - filled)
                if (r == -1) break
                filled += r
            }
            if (filled == 0) break // covers both EOF-after-full-chunk and empty input
            val cipher = Cipher.getInstance(CryptoEngine.TRANSFORMATION)
            cipher.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(CryptoEngine.GCM_TAG_BITS, chunkNonce(fileNonce, index)))
            val ct = cipher.doFinal(plain, 0, filled)
            output.write(ct)
            plainSize += filled
            index++
            if (filled < chunkSize) break // EOF reached with short/final block
        }
        return Header(chunkSize, fileNonce, plainSize, index.toInt())
    }

    /** Parses and validates the header; derives plainSize/chunkCount from container size. */
    fun readHeader(source: RandomAccessFile): Header {
        val h = ByteArray(HEADER_SIZE)
        source.seek(0)
        source.readFully(h)
        for (i in CryptoEngine.MAGIC.indices) {
            if (h[i] != CryptoEngine.MAGIC[i]) throw IntegrityViolationException("Not a vault encrypted file")
        }
        if (h[4] != FORMAT_VERSION) throw IntegrityViolationException("Unsupported format version")
        if (h[5] != CryptoEngine.ALG_AES256_GCM) throw IntegrityViolationException("Unsupported algorithm")
        val chunkSize = ((h[6].toInt() and 0xFF) shl 24) or ((h[7].toInt() and 0xFF) shl 16) or
            ((h[8].toInt() and 0xFF) shl 8) or (h[9].toInt() and 0xFF)
        if (chunkSize < 8 * 1024 || chunkSize > 64 * 1024 * 1024) {
            throw IntegrityViolationException("Invalid chunk size")
        }
        val fileNonce = h.copyOfRange(10, HEADER_SIZE)
        val containerLen = source.length()
        val body = containerLen - HEADER_SIZE
        if (body < 0) throw IntegrityViolationException("Truncated container")
        val stride = chunkSize + CryptoEngine.GCM_TAG_BYTES
        val chunkCount = if (body == 0L) 0 else ((body + stride - 1) / stride).toInt()
        val lastCt = if (body == 0L) 0L else body - (chunkCount - 1L) * stride
        if (chunkCount > 0 && (lastCt < CryptoEngine.GCM_TAG_BYTES + 1 || lastCt > stride)) {
            throw IntegrityViolationException("Corrupt container length")
        }
        val lastPlain = if (chunkCount == 0) 0L else lastCt - CryptoEngine.GCM_TAG_BYTES
        val plainSize = (chunkCount.toLong() - 1).coerceAtLeast(0) * chunkSize + lastPlain
        return Header(chunkSize, fileNonce, plainSize, chunkCount)
    }

    /**
     * Sequential/random-access decryptor over an open container. Holds at most
     * one decrypted chunk in memory. Not thread-safe; synchronize externally.
     */
    class Reader(private val raf: RandomAccessFile, private val key: SecretKey) : AutoCloseable {

        val header: Header = readHeader(raf)

        private val stride = header.chunkSize + CryptoEngine.GCM_TAG_BYTES
        private var cachedIndex = -1L
        private var cachedPlain: ByteArray? = null

        val plainSize: Long get() = header.plainSize

        /**
         * Reads up to [len] plaintext bytes at absolute [offset] into
         * [buf].[off], spanning chunk boundaries transparently; returns bytes
         * read or -1 at end of stream.
         */
        fun readAt(offset: Long, buf: ByteArray, off: Int, len: Int): Int {
            if (len <= 0) return 0
            if (offset >= plainSize) return -1
            var pos = offset
            var written = 0
            while (written < len && pos < plainSize) {
                val index = pos / header.chunkSize
                val inChunkOffset = (pos % header.chunkSize).toInt()
                val chunk = decrypted(index)
                val n = minOf(chunk.size - inChunkOffset, len - written)
                System.arraycopy(chunk, inChunkOffset, buf, off + written, n)
                written += n
                pos += n
            }
            return if (written == 0) -1 else written
        }

        /** Decrypt-on-demand with a one-chunk cache for sequential reads. */
        private fun decrypted(index: Long): ByteArray {
            if (index == cachedIndex) return cachedPlain!!
            if (index >= header.chunkCount) throw EOFException("Chunk $index beyond end")
            val ctLen = if (index == header.chunkCount - 1L) {
                raf.length() - HEADER_SIZE - index * stride
            } else stride
            raf.seek(HEADER_SIZE + index * stride)
            val ct = ByteArray(ctLen.toInt())
            raf.readFully(ct)
            val cipher = Cipher.getInstance(CryptoEngine.TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(CryptoEngine.GCM_TAG_BITS, chunkNonce(header.fileNonce, index)))
            val plain = try {
                cipher.doFinal(ct)
            } catch (_: Exception) {
                throw IntegrityViolationException("Integrity check failed at chunk $index")
            }
            cachedIndex = index
            cachedPlain = plain
            return plain
        }

        override fun close() {
            cachedPlain = null
            cachedIndex = -1
            raf.close()
        }
    }

    /** Full sequential decryption of a v2 container (export/share/verify paths). */
    fun decrypt(input: InputStream, output: OutputStream, key: SecretKey) {
        val din = DataInputStream(input)
        val h = ByteArray(HEADER_SIZE)
        try {
            din.readFully(h)
        } catch (_: EOFException) {
            throw IntegrityViolationException("Truncated container")
        }
        for (i in CryptoEngine.MAGIC.indices) {
            if (h[i] != CryptoEngine.MAGIC[i]) throw IntegrityViolationException("Not a vault encrypted file")
        }
        if (h[4] != FORMAT_VERSION) throw IntegrityViolationException("Unsupported format version")
        val chunkSize = ((h[6].toInt() and 0xFF) shl 24) or ((h[7].toInt() and 0xFF) shl 16) or
            ((h[8].toInt() and 0xFF) shl 8) or (h[9].toInt() and 0xFF)
        val fileNonce = h.copyOfRange(10, HEADER_SIZE)

        val ct = ByteArray(chunkSize + CryptoEngine.GCM_TAG_BYTES)
        var index = 0L
        while (true) {
            var filled = 0
            try {
                while (filled < ct.size) {
                    val r = din.read(ct, filled, ct.size - filled)
                    if (r == -1) break
                    filled += r
                }
            } catch (_: IOException) {
                break
            }
            if (filled == 0) break
            if (filled < CryptoEngine.GCM_TAG_BYTES) throw IntegrityViolationException("Truncated chunk $index")
            val cipher = Cipher.getInstance(CryptoEngine.TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(CryptoEngine.GCM_TAG_BITS, chunkNonce(fileNonce, index)))
            val plain = try {
                cipher.doFinal(ct, 0, filled)
            } catch (_: GeneralSecurityException) {
                throw IntegrityViolationException("Integrity check failed at chunk $index")
            }
            output.write(plain)
            index++
            if (filled < ct.size) break
        }
    }
}
