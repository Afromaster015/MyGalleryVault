package id.bayu.mygalleryvault.domain.model

data class VaultFile(
    val id: Long,
    val name: String,
    val mimeType: String,
    val size: Long,
    val folderId: Long?,
    val createdAt: Long,
    val modifiedAt: Long,
    val hasThumbnail: Boolean,
    val isImage: Boolean,
    val isVideo: Boolean,
    /** Random object name - needed for vault:// playback URIs. */
    val encryptedName: String = "",
    /** 1 = legacy single-stream, 2 = chunked instant-seek. */
    val encryptionVersion: Int = 1,
)

data class VaultFolder(
    val id: Long,
    val parentId: Long?,
    val name: String,
)

sealed class VaultEntry {
    data class Folder(val folder: VaultFolder) : VaultEntry()
    data class File(val file: VaultFile) : VaultEntry()
}

enum class SortOption(val label: String) {
    NAME_ASC("Nama A-Z"),
    NAME_DESC("Nama Z-A"),
    NEWEST("Terbaru"),
    OLDEST("Terlama"),
    LARGEST("Terbesar"),
    SMALLEST("Terkecil"),
    TYPE("Tipe"),
}

enum class AutoLockOption(val label: String, val delayMillis: Long) {
    IMMEDIATE("Segera", 0L),
    SECONDS_30("30 detik", 30_000L),
    MINUTE_1("1 menit", 60_000L),
    MINUTES_5("5 menit", 300_000L),
}

/** Browse presentation inside vault screens. */
enum class ViewMode {
    GRID,
    LIST,
}

object SettingsKeys {
    const val AUTO_LOCK = "auto_lock"
    const val SCREENSHOT_PROTECTION = "screenshot_protection"
    const val HOME_VIEW_MODE = "home_view_mode"
    /** Digit count of the PRIMARY vault PIN - drives the lock-screen dot slots. */
    const val PIN_LENGTH = "pin_length"
    /** Absolute epoch until which PIN entry stays locked after wrong attempts. */
    const val LOCKED_UNTIL = "locked_until"
    const val BIOMETRIC_ENABLED = "biometric_enabled"
    const val FAILED_THRESHOLD = "failed_threshold"
    const val FAILED_COUNT = "failed_count"
    const val SHAKE_ENABLED = "shake_enabled"
    const val SHAKE_SENSITIVITY = "shake_sensitivity"
    const val BREAK_IN_ENABLED = "break_in_enabled"
    const val BREAK_IN_PHOTOS = "break_in_photos"
    const val SEARCH_ENGINE = "search_engine"
    const val SHIELDS_DEFAULT_ON = "shields_default_on"
    const val SUBTITLE_SIZE_SP = "subtitle_size_sp"
    const val SUBTITLE_TEXT_COLOR = "subtitle_text_color"
    const val SUBTITLE_BG_COLOR = "subtitle_bg_color"
    const val SUBTITLE_EDGE = "subtitle_edge"
}

/** Caption outline/shadow style for the video player subtitle overlay. */
enum class SubtitleEdge(val label: String) {
    NONE("Tanpa"),
    OUTLINE("Garis tepi"),
    DROP_SHADOW("Bayangan");

    companion object {
        fun fromName(value: String?): SubtitleEdge =
            entries.firstOrNull { it.name == value } ?: OUTLINE
    }
}

/** Persisted subtitle appearance (player). Colors are ARGB ints. */
data class SubtitleStyle(
    val sizeSp: Int = 22,
    val textColor: Int = 0xFFFFFFFF.toInt(),
    val bgColor: Int = 0x00000000,
    val edge: SubtitleEdge = SubtitleEdge.OUTLINE,
)

/** Omnibox fallback engines for the private browser (PRD §38). */
enum class SearchEngine(val label: String, val queryTemplate: String) {
    DUCKDUCKGO("DuckDuckGo", "https://duckduckgo.com/?q=%s"),
    GOOGLE("Google", "https://www.google.com/search?q=%s"),
    BING("Bing", "https://www.bing.com/search?q=%s"),
    BRAVE("Brave Search", "https://search.brave.com/search?q=%s"),
    STARTPAGE("Startpage", "https://www.startpage.com/sp/search?query=%s");

    companion object {
        fun fromName(value: String?): SearchEngine =
            entries.firstOrNull { it.name == value } ?: DUCKDUCKGO

        /** Non-URL input (contains spaces / no dot) becomes an engine query. */
        fun resolveInput(raw: String, engine: SearchEngine): String {
            val trimmed = raw.trim()
            if (trimmed.isEmpty()) return trimmed
            val looksLikeUrl = !trimmed.contains(' ') &&
                (trimmed.contains('.') || trimmed.startsWith("http"))
            return if (looksLikeUrl) {
                if (trimmed.startsWith("http")) trimmed else "https://$trimmed"
            } else {
                engine.queryTemplate.replace(
                    "%s",
                    java.net.URLEncoder.encode(trimmed, "UTF-8"),
                )
            }
        }
    }
}
