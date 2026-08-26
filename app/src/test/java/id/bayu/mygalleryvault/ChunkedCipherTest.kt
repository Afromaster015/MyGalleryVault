package id.bayu.mygalleryvault

import id.bayu.mygalleryvault.core.crypto.ChunkedCipher
import id.bayu.mygalleryvault.core.crypto.CryptoEngine
import id.bayu.mygalleryvault.core.crypto.IntegrityViolationException
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.Random
import javax.crypto.spec.SecretKeySpec

class ChunkedCipherTest {

    private fun key() = SecretKeySpec(ByteArray(32) { (it * 7 + 3).toByte() }, "AES")

    private fun payload(size: Int, seed: Byte = 5): ByteArray =
        ByteArray(size) { i -> ((i * 31) xor seed.toInt()).toByte() }

    private fun encryptToTemp(data: ByteArray, chunkSize: Int? = null): File {
        val f = File.createTempFile("chunk", ".enc")
        f.outputStream().use { out ->
            if (chunkSize == null) {
                CryptoEngine.encryptStream(ByteArrayInputStream(data), out, key())
            } else {
                ChunkedCipher.encrypt(ByteArrayInputStream(data), out, key(), chunkSize)
            }
        }
        f.deleteOnExit()
        return f
    }

    private fun expectedContainerLength(plainSize: Int, chunkSize: Int): Long {
        if (plainSize == 0) return ChunkedCipher.HEADER_SIZE.toLong()
        val full = plainSize / chunkSize
        val rem = plainSize % chunkSize
        var len = ChunkedCipher.HEADER_SIZE.toLong()
        len += full.toLong() * (chunkSize + 16)
        if (rem > 0) len += rem + 16
        return len
    }

    @Test
    fun `roundtrip across edge sizes`() {
        val chunk = 4 * 1024 * 1024
        for (size in intArrayOf(0, 1, 100, 4 * 1024 * 1024 - 1, 4 * 1024 * 1024, 4 * 1024 * 1024 + 1)) {
            val data = payload(size)
            val f = encryptToTemp(data)
            assertEquals("size=$size", expectedContainerLength(size, chunk), f.length())

            val out = ByteArrayOutputStream()
            CryptoEngine.decryptStream(f.inputStream(), out, key())
            assertArrayEquals("size=$size", data, out.toByteArray())
            f.delete()
        }
    }

    @Test
    fun `custom chunk size roundtrip with random seeks`() {
        val chunk = 64 * 1024
        val data = payload(chunk * 3 + 12345)
        val f = encryptToTemp(data, chunk)

        java.io.RandomAccessFile(f, "r").use { raf ->
            val reader = ChunkedCipher.Reader(raf, key())
            assertEquals(data.size.toLong(), reader.plainSize)
            val buf = ByteArray(7777)
            val rnd = Random(42)
            repeat(200) {
                val offset = ((rnd.nextLong().let { if (it < 0) -it else it } % data.size)).toLong()
                val expectLen = minOf(buf.size, (data.size - offset).toInt())
                val got = reader.readAt(offset, buf, 0, buf.size)
                assertEquals(expectLen, got)
                for (i in 0 until got) {
                    assertEquals("offset=${offset + i}", data[(offset + i).toInt()], buf[i])
                }
            }
            // sequential full pass must match too
            val seq = ByteArrayOutputStream()
            var pos = 0L
            while (true) {
                val n = reader.readAt(pos, buf, 0, buf.size)
                if (n == -1) break
                seq.write(buf, 0, n)
                pos += n
            }
            assertArrayEquals(data, seq.toByteArray())
            reader.close()
        }
        f.delete()
    }

    @Test
    fun `tampered middle chunk fails integrity with index`() {
        val chunk = 16 * 1024
        val data = payload(chunk * 2)
        val f = encryptToTemp(data, chunk)
        val raw = f.readBytes()
        raw[ChunkedCipher.HEADER_SIZE + chunk + 20] = (raw[ChunkedCipher.HEADER_SIZE + chunk + 20].toInt() xor 0x41).toByte()
        f.writeBytes(raw)

        java.io.RandomAccessFile(f, "r").use { raf ->
            val reader = ChunkedCipher.Reader(raf, key())
            val ex = assertThrows(IntegrityViolationException::class.java) {
                reader.readAt(chunk + 10L, ByteArray(16), 0, 16)
            }
            assertTrue(ex.message!!.contains("chunk 1"))
            reader.close()
        }
        f.delete()
    }

    @Test
    fun `truncated container rejected`() {
        val data = payload(100_000)
        val f = encryptToTemp(data)
        val raw = f.readBytes()
        f.writeBytes(raw.copyOfRange(0, raw.size - 500))
        val out = ByteArrayOutputStream()
        assertThrows(Exception::class.java) {
            CryptoEngine.decryptStream(f.inputStream(), out, key())
        }
        f.delete()
    }

    @Test
    fun `wrong key rejected`() {
        val data = payload(50_000)
        val f = encryptToTemp(data)
        val other = SecretKeySpec(ByteArray(32) { 9 }, "AES")
        val out = ByteArrayOutputStream()
        assertThrows(Exception::class.java) {
            CryptoEngine.decryptStream(f.inputStream(), out, other)
        }
        f.delete()
    }
}
