package id.bayu.mygalleryvault.core.crypto

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.security.keystore.UserNotAuthenticatedException
import id.bayu.mygalleryvault.domain.model.VaultSlot
import java.io.File
import java.io.IOException
import java.security.GeneralSecurityException
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/**
 * Master key lifecycle per PRD §4-5:
 * - Random 256-bit master key (SecureRandom), never persisted in plaintext.
 * - Wrapped (AES-GCM) by a KEK derived from the user PIN via PBKDF2-HmacSHA256.
 * - Optionally also wrapped by an Android Keystore key gated by BiometricPrompt (§21).
 *
 * Supports two independent vault slots (§25-26): REAL and DECOY. Blob file names
 * are deliberately neutral so the on-disk layout does not reveal which is which.
 */
class VaultKeyManager(private val keysDir: File) {

    companion object {
        private const val REAL_BLOB_FILE = "kb_a61f.bin"
        private const val DECOY_BLOB_FILE = "kb_93d7.bin"
        private const val LEGACY_REAL_BLOB_FILE = "keyblob.bin"
        private const val BIO_BLOB_FILE = "bioblob.bin"
        private const val BLOB_MAGIC = "SVKB"
        private const val BIO_MAGIC = "SVIO"
        private const val BLOB_VERSION: Byte = 1
        /** magic (4) + version (1) prefix that precedes the salt in every blob. */
        private const val BLOB_HEADER_BYTES = BLOB_MAGIC.length + 1
        private const val SALT_BYTES = 16
        private const val MASTER_KEY_BITS = 256
        private const val GCM_TAG_BITS = 128
        private const val NONCE_BYTES = 12
        private const val PBKDF2_ITERATIONS = 310_000
        private const val ANDROID_KEYSTORE = "AndroidKeyStore"
        private const val BIOMETRIC_KEY_ALIAS = "sv_biometric_key"
    }

    private val random = SecureRandom()

    private fun blobFile(slot: VaultSlot): File = when (slot) {
        VaultSlot.REAL -> {
            val modern = File(keysDir, REAL_BLOB_FILE)
            if (modern.exists()) modern else File(keysDir, LEGACY_REAL_BLOB_FILE)
        }
        VaultSlot.DECOY -> File(keysDir, DECOY_BLOB_FILE)
    }

    val isVaultCreated: Boolean
        get() = blobFile(VaultSlot.REAL).exists()

    val isDecoyCreated: Boolean
        get() = blobFile(VaultSlot.DECOY).exists()

    val isBiometricEnabled: Boolean
        get() = File(keysDir, BIO_BLOB_FILE).exists()

    @Throws(GeneralSecurityException::class, IOException::class)
    fun createVault(pin: CharArray, slot: VaultSlot = VaultSlot.REAL): SecretKey {
        if (slot == VaultSlot.DECOY && !isVaultCreated) {
            throw GeneralSecurityException("Vault utama belum dibuat")
        }
        keysDir.mkdirs()
        val masterBytes = ByteArray(MASTER_KEY_BITS / 8)
        random.nextBytes(masterBytes)
        val master = SecretKeySpec(masterBytes, "AES")
        writeWrappedWithPin(pin, master, slot)
        masterBytes.fill(0)
        return master
    }

    @Throws(IOException::class)
    fun unlockWithPin(pin: CharArray, slot: VaultSlot = VaultSlot.REAL): SecretKey? {
        val blobFile = blobFile(slot)
        if (!blobFile.exists()) throw IOException("Vault not initialized")
        val blob = blobFile.readBytes()
        var input = try {
            readBlob(blob, BLOB_MAGIC)
        } catch (_: GeneralSecurityException) {
            return null
        }
        return try {
            // Layout must mirror writeWrappedWithPin(): [magic|ver][salt][iters u32][nonce][wrapped]
            val saltOff = BLOB_HEADER_BYTES
            val iterOff = saltOff + SALT_BYTES
            val nonceOff = iterOff + 4
            val wrappedOff = nonceOff + NONCE_BYTES

            val salt = input.copyOfRange(saltOff, iterOff)
            val iterations = ((input[iterOff].toInt() and 0xFF) shl 24) or
                ((input[iterOff + 1].toInt() and 0xFF) shl 16) or
                ((input[iterOff + 2].toInt() and 0xFF) shl 8) or
                (input[iterOff + 3].toInt() and 0xFF)
            if (iterations < 10_000 || input.size < wrappedOff) throw GeneralSecurityException("Bad blob")
            val iv = input.copyOfRange(nonceOff, wrappedOff)
            val wrapped = input.copyOfRange(wrappedOff, input.size)

            val kek = deriveKek(pin, salt, iterations)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, kek, GCMParameterSpec(GCM_TAG_BITS, iv))
            val masterBytes = cipher.doFinal(wrapped)
            SecretKeySpec(masterBytes, "AES")
        } catch (_: Exception) {
            null
        } finally {
            input.fill(0)
        }
    }

    @Throws(GeneralSecurityException::class, IOException::class)
    fun changePin(newPin: CharArray, unlockedMasterKey: SecretKey, slot: VaultSlot = VaultSlot.REAL) {
        writeWrappedWithPin(newPin, unlockedMasterKey, slot)
    }

    // ---- Biometric wrap/unwrap (§21) ----

    class BiometricCipherRequest internal constructor(val cipher: Cipher)

    @Throws(GeneralSecurityException::class)
    fun prepareBiometricEnable(): BiometricCipherRequest {
        ensureBiometricKey()
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        try {
            cipher.init(Cipher.ENCRYPT_MODE, getBiometricKey())
        } catch (_: UserNotAuthenticatedException) {
            // Expected: cipher becomes authorized after BiometricPrompt succeeds.
        }
        return BiometricCipherRequest(cipher)
    }

    /**
     * Called from BiometricPrompt success callback when enabling.
     * Requires an already-unlocked session key.
     */
    @Throws(GeneralSecurityException::class, IOException::class)
    fun completeBiometricEnable(request: BiometricCipherRequest, unlockedMasterKey: SecretKey) {
        val masterRaw = unlockedMasterKey.encoded ?: throw GeneralSecurityException("No key material")
        val wrapped = request.cipher.doFinal(masterRaw)
        val usedIv = request.cipher.iv
        keysDir.mkdirs()
        val out = java.io.ByteArrayOutputStream()
        out.write(BIO_MAGIC.toByteArray(Charsets.US_ASCII))
        out.write(BLOB_VERSION.toInt())
        out.write(usedIv.size)
        out.write(usedIv)
        out.write(wrapped)
        File(keysDir, BIO_BLOB_FILE).writeBytes(out.toByteArray())
    }

    /**
     * Prepares a decrypt cipher for biometric unlock. May throw
     * [UserNotAuthenticatedException]; that signals to start BiometricPrompt
     * with a CryptoObject wrapping this cipher.
     */
    class BiometricDecryptRequest internal constructor(
        val cipher: Cipher,
        internal val wrapped: ByteArray,
    )

    @Throws(GeneralSecurityException::class, IOException::class)
    fun prepareBiometricUnlock(): BiometricDecryptRequest {
        val bioFile = File(keysDir, BIO_BLOB_FILE)
        if (!bioFile.exists()) throw IOException("Biometric unlock not configured")
        val blob = bioFile.readBytes()
        val magic = BIO_MAGIC.toByteArray(Charsets.US_ASCII)
        if (blob.size <= magic.size + 1 || !blob.copyOfRange(0, magic.size).contentEquals(magic)) {
            throw IOException("Corrupted biometric blob")
        }
        val ivLen = blob[5].toInt()
        val iv = blob.copyOfRange(6, 6 + ivLen)
        val wrapped = blob.copyOfRange(6 + ivLen, blob.size)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        try {
            cipher.init(Cipher.DECRYPT_MODE, getBiometricKey(), GCMParameterSpec(GCM_TAG_BITS, iv))
        } catch (_: UserNotAuthenticatedException) {
            // Expected: cipher becomes authorized after BiometricPrompt succeeds.
        }
        return BiometricDecryptRequest(cipher, wrapped)
    }

    /** Called from BiometricPrompt success callback when unlocking. */
    @Throws(GeneralSecurityException::class)
    fun completeBiometricUnlock(request: BiometricDecryptRequest): SecretKey {
        val masterBytes = request.cipher.doFinal(request.wrapped)
        return SecretKeySpec(masterBytes, "AES")
    }

    fun disableBiometric() {
        File(keysDir, BIO_BLOB_FILE).delete()
        try {
            val ks = java.security.KeyStore.getInstance(ANDROID_KEYSTORE)
            ks.load(null)
            ks.deleteEntry(BIOMETRIC_KEY_ALIAS)
        } catch (_: Exception) {
        }
    }

    fun wipeDecoy() {
        blobFile(VaultSlot.DECOY).delete()
    }

    fun wipeAll() {
        disableBiometric()
        blobFile(VaultSlot.REAL).delete()
        blobFile(VaultSlot.DECOY).delete()
    }

    // ---- internals ----

    private fun writeWrappedWithPin(pin: CharArray, master: SecretKey, slot: VaultSlot) {
        val salt = ByteArray(SALT_BYTES)
        random.nextBytes(salt)
        val iterations = PBKDF2_ITERATIONS
        val nonce = CryptoEngine.newNonce()
        val kek = deriveKek(pin, salt, iterations)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, kek, GCMParameterSpec(GCM_TAG_BITS, nonce))
        val masterRaw = master.encoded ?: throw GeneralSecurityException("No key material")
        val wrapped = cipher.doFinal(masterRaw)

        val out = java.io.ByteArrayOutputStream()
        out.write(BLOB_MAGIC.toByteArray(Charsets.US_ASCII))
        out.write(BLOB_VERSION.toInt())
        out.write(salt)
        out.write(byteArrayOf(
            (iterations ushr 24).toByte(),
            (iterations ushr 16).toByte(),
            (iterations ushr 8).toByte(),
            iterations.toByte(),
        ))
        out.write(nonce)
        out.write(wrapped)
        val fileName = when (slot) {
            VaultSlot.REAL -> REAL_BLOB_FILE
            VaultSlot.DECOY -> DECOY_BLOB_FILE
        }
        val legacy = if (slot == VaultSlot.REAL) File(keysDir, LEGACY_REAL_BLOB_FILE) else null
        val tmp = File(keysDir, "$fileName.tmp")
        tmp.writeBytes(out.toByteArray())
        val target = File(keysDir, fileName)
        legacy?.delete()
        if (!tmp.renameTo(target)) {
            target.writeBytes(tmp.readBytes())
            tmp.delete()
        }
    }

    private fun deriveKek(pin: CharArray, salt: ByteArray, iterations: Int): SecretKey {
        val spec = PBEKeySpec(pin, salt, iterations, MASTER_KEY_BITS)
        try {
            val factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
            val key = factory.generateSecret(spec)
            return SecretKeySpec(key.encoded, "AES")
        } finally {
            spec.clearPassword()
        }
    }

    private fun readBlob(blob: ByteArray, expectedMagic: String): ByteArray {
        val magic = expectedMagic.toByteArray(Charsets.US_ASCII)
        require(blob.size > magic.size + 1 && blob[magic.size] == BLOB_VERSION) {
            "Bad blob"
        }
        for (i in magic.indices) {
            if (blob[i] != magic[i]) throw GeneralSecurityException("Bad blob magic")
        }
        return blob
    }

    private fun getBiometricKey(): SecretKey {
        val ks = java.security.KeyStore.getInstance(ANDROID_KEYSTORE)
        ks.load(null)
        val entry = ks.getEntry(BIOMETRIC_KEY_ALIAS, null)
            as? java.security.KeyStore.SecretKeyEntry
            ?: throw GeneralSecurityException("Biometric key missing")
        return entry.secretKey
    }

    private fun ensureBiometricKey() {
        val ks = java.security.KeyStore.getInstance(ANDROID_KEYSTORE)
        ks.load(null)
        if (ks.containsAlias(BIOMETRIC_KEY_ALIAS)) return
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(
                BIOMETRIC_KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(MASTER_KEY_BITS)
                .setUserAuthenticationRequired(true)
                .setUserAuthenticationValidityDurationSeconds(-1)
                .build()
        )
        generator.generateKey()
    }
}
