package id.bayu.mygalleryvault.ui.components

import java.util.Locale

object FormatUtil {

    fun fileSize(bytes: Long): String {
        if (bytes < 1024) return "$bytes B"
        val kb = bytes / 1024.0
        if (kb < 1024) return String.format(Locale.getDefault(), "%.1f KB", kb)
        val mb = kb / 1024.0
        if (mb < 1024) return String.format(Locale.getDefault(), "%.1f MB", mb)
        val gb = mb / 1024.0
        return String.format(Locale.getDefault(), "%.2f GB", gb)
    }

    fun dateTime(epochMillis: Long): String =
        java.text.SimpleDateFormat("dd MMM yyyy HH:mm", Locale.getDefault()).format(
            java.util.Date(epochMillis)
        )
}
