package id.bayu.mygalleryvault

import id.bayu.mygalleryvault.core.backup.BackupCodec
import id.bayu.mygalleryvault.core.backup.BackupException
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import javax.crypto.spec.SecretKeySpec

class BackupCodecTest {

    private fun sampleMaster(): SecretKeySpec = SecretKeySpec(ByteArray(32) { it.toByte() }, "AES")

    @Test
    fun `full container roundtrip preserves header key metadata and items`() {
        val buffer = ByteArrayOutputStream()

        val salt = BackupCodec.newSalt()
        BackupCodec.writeHeader(buffer, salt, BackupCodec.DEFAULT_ITERATIONS)
        val header = BackupCodec.Header(salt, BackupCodec.DEFAULT_ITERATIONS)

        val master = sampleMaster()
        BackupCodec.writeWrappedMaster(buffer, header, "123456".toCharArray(), master.encoded!!)

        val metaJson = """{"app":"SecureVault","schema":1,"files":[]}""".toByteArray(Charsets.UTF_8)
        BackupCodec.writeEncryptedBlock(buffer, metaJson, master)

        val payload1 = ByteArray(5000) { (it % 251).toByte() }
        val payload2 = ByteArray(17) { 7 }
        BackupCodec.writeItem(buffer, "o/abc.enc", ByteArrayInputStream(payload1), payload1.size.toLong())
        BackupCodec.writeItem(buffer, "t/thumb.enc", ByteArrayInputStream(payload2), payload2.size.toLong())
        BackupCodec.writeTerminator(buffer)

        // ---- read back ----
        val input = ByteArrayInputStream(buffer.toByteArray())
        val parsed = BackupCodec.readHeader(input)
        assertEquals(header.iterations, parsed.iterations)
        assertArrayEquals(header.salt, parsed.salt)

        val unwrapped = BackupCodec.readWrappedMaster(input, parsed, "123456".toCharArray())
        assertArrayEquals(master.encoded, unwrapped.encoded)

        assertArrayEquals(metaJson, BackupCodec.readEncryptedBlock(input, unwrapped))

        val out1 = ByteArrayOutputStream()
        assertEquals("o/abc.enc", BackupCodec.readItem(input) { path, data, size ->
            assertEquals(payload1.size.toLong(), size)
            data.copyTo(out1)
        })
        assertArrayEquals(payload1, out1.toByteArray())

        val out2 = ByteArrayOutputStream()
        assertEquals("t/thumb.enc", BackupCodec.readItem(input) { _, data, size ->
            assertEquals(payload2.size.toLong(), size)
            data.copyTo(out2)
        })
        assertArrayEquals(payload2, out2.toByteArray())

        assertNull(BackupCodec.readItem(input) { _, data, _ -> data.read() })
    }

    @Test(expected = BackupException::class)
    fun `wrong pin is rejected without leaking detail`() {
        val buffer = ByteArrayOutputStream()
        val salt = BackupCodec.newSalt()
        BackupCodec.writeHeader(buffer, salt, BackupCodec.DEFAULT_ITERATIONS)
        val header = BackupCodec.Header(salt, BackupCodec.DEFAULT_ITERATIONS)
        BackupCodec.writeWrappedMaster(buffer, header, "123456".toCharArray(), sampleMaster().encoded!!)

        val input = ByteArrayInputStream(buffer.toByteArray())
        val parsed = BackupCodec.readHeader(input)
        BackupCodec.readWrappedMaster(input, parsed, "999999".toCharArray())
    }

    @Test(expected = BackupException::class)
    fun `tampered magic is rejected`() {
        val buffer = ByteArrayOutputStream()
        BackupCodec.writeHeader(buffer, BackupCodec.newSalt(), BackupCodec.DEFAULT_ITERATIONS)
        val raw = buffer.toByteArray().also { it[0] = 0x00 }
        BackupCodec.readHeader(ByteArrayInputStream(raw))
    }
}
