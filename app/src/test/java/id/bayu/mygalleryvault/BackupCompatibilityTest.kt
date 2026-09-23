package id.bayu.mygalleryvault

import id.bayu.mygalleryvault.core.backup.BackupCodec
import id.bayu.mygalleryvault.core.crypto.CryptoEngine
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import javax.crypto.Cipher
import javax.crypto.SecretKey
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/**
 * Locks the compatibility contract that `.svbackup` files from earlier builds
 * (and legacy vault objects) depend on: the KDF must stay PBKDF2-HmacSHA256 at
 * [BackupCodec.DEFAULT_ITERATIONS], the container header layout must keep its
 * field order, and the v1 single-stream object format must stay readable.
 *
 * Each expectation is written independently of the production code it checks,
 * so silently changing the KDF, the header order, or dropping v1 support makes
 * restoring an older backup fail loudly here instead of in the field.
 */
class BackupCompatibilityTest {

    private val legacySalt = ByteArray(16) { it.toByte() }
    private val legacyPin = "123456".toCharArray()
    private val legacyMasterRaw = ByteArray(32) { (it * 3 + 1).toByte() }
    private val legacyMaster = SecretKeySpec(legacyMasterRaw, "AES")

    /** Independent implementation of the documented KDF; pins the hash to SHA-256. */
    private fun documentedKek(pin: CharArray, salt: ByteArray, iterations: Int): SecretKey {
        val spec = PBEKeySpec(pin, salt, iterations, 256)
        return try {
            val factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
            SecretKeySpec(factory.generateSecret(spec).encoded, "AES")
        } finally {
            spec.clearPassword()
        }
    }

    private fun v1Container(plaintext: ByteArray, nonce: ByteArray): ByteArray {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, legacyMaster, GCMParameterSpec(128, nonce))
        val ciphertext = cipher.doFinal(plaintext)
        return ByteArrayOutputStream().apply {
            write(byteArrayOf(0x53, 0x56, 0x4C, 0x54)) // "SVLT"
            write(1) // format version 1
            write(1) // AES-256-GCM
            write(nonce)
            write(ciphertext)
        }.toByteArray()
    }

    private fun legacyPayload(size: Int, seed: Int) = ByteArray(size) { ((it * 31) xor seed).toByte() }

    @Test
    fun `container header keeps magic version alg salt and iterations order`() {
        val bytes = ByteArrayOutputStream().apply {
            BackupCodec.writeHeader(this, legacySalt, BackupCodec.DEFAULT_ITERATIONS)
        }.toByteArray()

        assertEquals(26, bytes.size)
        assertEquals(0x53, bytes[0].toInt() and 0xFF)
        assertEquals(0x56, bytes[1].toInt() and 0xFF)
        assertEquals(0x42, bytes[2].toInt() and 0xFF)
        assertEquals(0x4B, bytes[3].toInt() and 0xFF)
        assertEquals(1, bytes[4].toInt())
        assertEquals(1, bytes[5].toInt())
        assertArrayEquals(legacySalt, bytes.copyOfRange(6, 22))
        val iterations = ((bytes[22].toInt() and 0xFF) shl 24) or
            ((bytes[23].toInt() and 0xFF) shl 16) or
            ((bytes[24].toInt() and 0xFF) shl 8) or
            (bytes[25].toInt() and 0xFF)
        assertEquals(310_000, iterations)
        assertEquals(310_000, BackupCodec.DEFAULT_ITERATIONS)
    }

    @Test
    fun `wrapped master produced with documented pbkdf2-sha256 still unwraps`() {
        val wrapped = CryptoEngine.encryptBytes(legacyMasterRaw, documentedKek(legacyPin, legacySalt, 310_000))
        val container = ByteArrayOutputStream().apply {
            BackupCodec.writeHeader(this, legacySalt, 310_000)
            DataOutputStream(this).writeInt(wrapped.size)
            write(wrapped)
        }.toByteArray()

        val input = ByteArrayInputStream(container)
        val header = BackupCodec.readHeader(input)
        assertArrayEquals(legacySalt, header.salt)
        assertEquals(310_000, header.iterations)

        val unwrapped = BackupCodec.readWrappedMaster(input, header, legacyPin)
        assertArrayEquals(legacyMasterRaw, unwrapped.encoded)
    }

    @Test
    fun `legacy v1 single-stream object still decrypts`() {
        val plaintext = legacyPayload(4096, 7)
        val v1 = v1Container(plaintext, ByteArray(12) { (0xA0 + it).toByte() })

        val out = ByteArrayOutputStream()
        CryptoEngine.decryptStream(ByteArrayInputStream(v1), out, legacyMaster)

        assertArrayEquals(plaintext, out.toByteArray())
    }

    @Test
    fun `older backup carrying a v1 object restores playable bytes`() {
        val objectPlaintext = legacyPayload(8192, 11)
        val thumbPlaintext = legacyPayload(300, 13)
        val metaJson = """{"app":"SecureVault","schema":1,"folders":[],"files":[]}"""
            .toByteArray(Charsets.UTF_8)

        val legacyObject = v1Container(objectPlaintext, ByteArray(12) { (0x10 + it).toByte() })
        val modernThumb = CryptoEngine.encryptBytes(thumbPlaintext, legacyMaster)

        val container = ByteArrayOutputStream().apply {
            val header = BackupCodec.Header(legacySalt, BackupCodec.DEFAULT_ITERATIONS)
            BackupCodec.writeHeader(this, legacySalt, BackupCodec.DEFAULT_ITERATIONS)
            BackupCodec.writeWrappedMaster(this, header, legacyPin, legacyMasterRaw)
            BackupCodec.writeEncryptedBlock(this, metaJson, legacyMaster)
            BackupCodec.writeItem(this, BackupCodec.OBJECT_PREFIX + "legacy.enc", ByteArrayInputStream(legacyObject), legacyObject.size.toLong())
            BackupCodec.writeItem(this, BackupCodec.THUMB_PREFIX + "thumb.enc", ByteArrayInputStream(modernThumb), modernThumb.size.toLong())
            BackupCodec.writeTerminator(this)
        }.toByteArray()

        val input = ByteArrayInputStream(container)
        val header = BackupCodec.readHeader(input)
        val hp1Master = BackupCodec.readWrappedMaster(input, header, legacyPin)
        assertArrayEquals(metaJson, BackupCodec.readEncryptedBlock(input, hp1Master))

        val restoredObject = ByteArrayOutputStream()
        assertEquals(
            BackupCodec.OBJECT_PREFIX + "legacy.enc",
            BackupCodec.readItem(input) { _, data, size ->
                assertEquals(legacyObject.size.toLong(), size)
                CryptoEngine.decryptStream(data, restoredObject, hp1Master)
            },
        )
        assertArrayEquals(objectPlaintext, restoredObject.toByteArray())

        val restoredThumb = ByteArrayOutputStream()
        assertEquals(
            BackupCodec.THUMB_PREFIX + "thumb.enc",
            BackupCodec.readItem(input) { _, data, size ->
                assertEquals(modernThumb.size.toLong(), size)
                CryptoEngine.decryptStream(data, restoredThumb, hp1Master)
            },
        )
        assertArrayEquals(thumbPlaintext, restoredThumb.toByteArray())

        assertNull(BackupCodec.readItem(input) { _, data, _ -> data.read() })
    }
}
