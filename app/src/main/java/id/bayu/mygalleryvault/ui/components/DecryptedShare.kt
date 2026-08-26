package id.bayu.mygalleryvault.ui.components

import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import java.io.File
import java.io.IOException

/**
 * Temporarily materializes decrypted content in private cache for Share /
 * Open With (PRD §15) after explicit user confirmation.
 */
object DecryptedShare {

    fun shareDir(context: Context): File = File(context.cacheDir, "share").apply { mkdirs() }

    @Throws(IOException::class)
    fun writeForSharing(context: Context, fileName: String, bytes: ByteArray): File =
        writeForSharing(context, fileName) { it.write(bytes) }

    /**
     * Stream variant used for potentially large decrypted files (e.g. videos)
     * so they never have to be fully resident in memory.
     */
    @Throws(IOException::class)
    fun writeForSharing(context: Context, fileName: String, writer: (java.io.OutputStream) -> Unit): File {
        val dir = shareDir(context)
        dir.listFiles()?.forEach { it.delete() }
        val safeName = fileName.replace(Regex("[^A-Za-z0-9._ ()-]"), "_")
        val out = File(dir, safeName)
        out.outputStream().use(writer)
        return out
    }

    fun contentUri(context: Context, file: File) =
        FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)

    fun shareIntent(context: Context, file: File, mime: String): Intent =
        Intent.createChooser(
            Intent(Intent.ACTION_SEND).apply {
                type = mime
                putExtra(Intent.EXTRA_STREAM, contentUri(context, file))
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            },
            "Bagikan file",
        )

    fun openWithIntent(context: Context, file: File, mime: String): Intent =
        Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(contentUri(context, file), mime.ifBlank { "application/octet-stream" })
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
}
