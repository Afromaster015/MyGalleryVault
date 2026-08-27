package id.bayu.mygalleryvault.core.playback

import android.content.Context
import android.net.Uri
import androidx.media3.common.C
import androidx.media3.datasource.BaseDataSource
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.TransferListener
import id.bayu.mygalleryvault.core.crypto.VaultSession
import id.bayu.mygalleryvault.core.storage.VaultRandomReader
import id.bayu.mygalleryvault.core.storage.VaultStorage
import java.io.IOException

/** URI scheme for objects living inside the encrypted vault: `vault://<encryptedName>`. */
const val VAULT_URI_SCHEME = "vault"

fun vaultUri(encryptedName: String): Uri = Uri.parse("$VAULT_URI_SCHEME://$encryptedName")

/**
 * Media3 DataSource that decrypts vault objects on the fly with random access.
 * ExoPlayer's loader thread pulls exactly the bytes it needs (PRD §14): seek
 * anywhere instantly and never materialize plaintext on disk. Works for both
 * video streams and sideloaded subtitle tracks.
 */
class VaultDataSource(
    private val storage: VaultStorage,
) : BaseDataSource(/* isNetwork = */ false) {

    private var reader: VaultRandomReader? = null
    private var uri: Uri? = null
    private var position: Long = 0
    private var bytesRemaining: Long = C.LENGTH_UNSET.toLong()

    override fun open(dataSpec: DataSpec): Long {
        transferInitializing(dataSpec)
        // DataSource contract expects IOExceptions - a bare IllegalStateException
        // here is flagged by ExoPlayer as an unexpected loader error.
        val key = VaultSession.masterKey
            ?: throw IOException("Vault terkunci")
        val name = dataSpec.uri.host
            ?: throw IOException("Invalid vault URI: $dataSpec.uri")
        val opened = try {
            storage.openRandomReader(name, key)
        } catch (e: Exception) {
            throw IOException(e.message ?: "Cannot open vault object", e)
        }
        reader = opened
        uri = dataSpec.uri
        position = dataSpec.position
        bytesRemaining = if (dataSpec.length != C.LENGTH_UNSET.toLong()) {
            dataSpec.length
        } else {
            opened.plainSize - dataSpec.position
        }
        transferStarted(dataSpec)
        return bytesRemaining
    }

    override fun read(target: ByteArray, offset: Int, length: Int): Int {
        if (length == 0) return 0
        if (bytesRemaining == 0L) return C.RESULT_END_OF_INPUT
        val bytesRead = reader!!.readAt(position, target, offset, length)
        if (bytesRead == -1) {
            bytesRemaining = 0
            return C.RESULT_END_OF_INPUT
        }
        position += bytesRead
        if (bytesRemaining != C.LENGTH_UNSET.toLong()) bytesRemaining -= bytesRead
        bytesTransferred(bytesRead)
        return bytesRead
    }

    override fun getUri(): Uri? = uri

    override fun close() {
        uri = null
        runCatching { reader?.close() }
        reader = null
    }
}

/**
 * Routes [VAULT_URI_SCHEME] URIs to the decrypting datasource and everything
 * else (content:// SAF subtitles from phone storage) to Media3 defaults.
 */
class PlaybackDataSourceFactory(
    context: Context,
    storage: VaultStorage,
) : DataSource.Factory {

    private val appContext = context.applicationContext
    private val vaultStorage = storage

    override fun createDataSource(): DataSource = ResolvingDataSourceProxy(appContext, vaultStorage)

    private class ResolvingDataSourceProxy(
        context: Context,
        storage: VaultStorage,
    ) : DataSource {
        private val vault = VaultDataSource(storage)
        private val fallback = DefaultDataSource(context, /* allowCrossProtocolRedirects = */ false)
        private var active: DataSource? = null

        override fun addTransferListener(listener: TransferListener) {
            vault.addTransferListener(listener)
            fallback.addTransferListener(listener)
        }

        override fun open(dataSpec: DataSpec): Long {
            val target =
                if (dataSpec.uri.scheme.equals(VAULT_URI_SCHEME, ignoreCase = true)) vault else fallback
            active = target
            return target.open(dataSpec)
        }

        override fun read(buffer: ByteArray, offset: Int, length: Int): Int =
            checkNotNull(active).read(buffer, offset, length)

        override fun getUri(): Uri? = active?.uri

        override fun close() {
            active?.let { runCatching { it.close() } }
            active = null
        }
    }
}
