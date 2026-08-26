package id.bayu.mygalleryvault.core.security

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import id.bayu.mygalleryvault.core.crypto.CryptoEngine
import java.io.File
import java.security.KeyStore
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey

/**
 * Device-bound encrypted storage for break-in alert snapshots (PRD §28).
 *
 * Photos are encrypted with a dedicated Android Keystore key that is NOT gated
 * by user authentication, because captures happen while the vault is locked.
 * They can only be decrypted on this device and never leave it.
 */
class SecurePhotoStore(private val dir: File) {

    companion object {
        private const val KEY_ALIAS = "sv_sec_photo_key"
        private const val ANDROID_KEYSTORE = "AndroidKeyStore"
    }

    private fun key(): SecretKey {
        val ks = KeyStore.getInstance(ANDROID_KEYSTORE)
        ks.load(null)
        val entry = ks.getEntry(KEY_ALIAS, null) as? KeyStore.SecretKeyEntry
        if (entry != null) return entry.secretKey
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build()
        )
        return generator.generateKey()
    }

    /** Returns the stored object name (without .enc) or null when saving failed. */
    fun save(bytes: ByteArray, name: String): String? = try {
        dir.mkdirs()
        File(dir, "$name.enc").writeBytes(CryptoEngine.encryptBytes(bytes, key()))
        name
    } catch (_: Exception) {
        null
    }

    fun loadBitmap(name: String): Bitmap? = try {
        val f = File(dir, "$name.enc")
        if (!f.exists()) null
        else {
            val plain = CryptoEngine.decryptBytes(f.readBytes(), key())
            BitmapFactory.decodeByteArray(plain, 0, plain.size)
        }
    } catch (_: Exception) {
        null
    }

    fun delete(name: String) {
        File(dir, "$name.enc").delete()
    }
}
