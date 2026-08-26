package id.bayu.mygalleryvault.core.config

/**
 * Process-wide snapshot of user preferences consumed outside Compose
 * (MainActivity window flags, shake detector, auto-lock).
 * Kept up to date by [id.bayu.mygalleryvault.data.repository.SettingsRepository].
 */
object GlobalConfig {

    @Volatile
    var screenshotProtection: Boolean = true

    @Volatile
    var shakeEnabled: Boolean = false

    @Volatile
    var shakeThreshold: Float = 23f
}
