package id.bayu.mygalleryvault.data.repository

import id.bayu.mygalleryvault.core.config.GlobalConfig
import id.bayu.mygalleryvault.core.lock.AutoLockManager
import id.bayu.mygalleryvault.data.local.SettingsDao
import id.bayu.mygalleryvault.data.local.SettingEntity
import id.bayu.mygalleryvault.domain.model.AutoLockOption
import id.bayu.mygalleryvault.domain.model.SearchEngine
import id.bayu.mygalleryvault.domain.model.SettingsKeys
import id.bayu.mygalleryvault.domain.model.ShakeSensitivity
import id.bayu.mygalleryvault.domain.model.SubtitleEdge
import id.bayu.mygalleryvault.domain.model.SubtitleStyle
import id.bayu.mygalleryvault.domain.model.ViewMode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

class SettingsRepository(private val settingsDao: SettingsDao) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    val autoLock: Flow<AutoLockOption> =
        settingsDao.observeAll().map { rows ->
            parseAutoLock(rows.firstOrNull { it.key == SettingsKeys.AUTO_LOCK }?.value)
        }

    val screenshotProtection: Flow<Boolean> =
        settingsDao.observeAll().map { rows ->
            rows.firstOrNull { it.key == SettingsKeys.SCREENSHOT_PROTECTION }?.value != "false"
        }

    val biometricEnabled: Flow<Boolean> =
        settingsDao.observeAll().map { rows ->
            rows.firstOrNull { it.key == SettingsKeys.BIOMETRIC_ENABLED }?.value == "true"
        }

    val shakeEnabled: Flow<Boolean> =
        settingsDao.observeAll().map { rows ->
            rows.firstOrNull { it.key == SettingsKeys.SHAKE_ENABLED }?.value == "true"
        }

    val shakeSensitivity: Flow<ShakeSensitivity> =
        settingsDao.observeAll().map { rows ->
            ShakeSensitivity.fromName(
                rows.firstOrNull { it.key == SettingsKeys.SHAKE_SENSITIVITY }?.value
            )
        }

    val breakInAlertEnabled: Flow<Boolean> =
        settingsDao.observeAll().map { rows ->
            rows.firstOrNull { it.key == SettingsKeys.BREAK_IN_ENABLED }?.value != "false"
        }

    val breakInPhotosEnabled: Flow<Boolean> =
        settingsDao.observeAll().map { rows ->
            rows.firstOrNull { it.key == SettingsKeys.BREAK_IN_PHOTOS }?.value == "true"
        }

    val searchEngine: Flow<SearchEngine> =
        settingsDao.observeAll().map { rows ->
            SearchEngine.fromName(
                rows.firstOrNull { it.key == SettingsKeys.SEARCH_ENGINE }?.value
            )
        }

    val shieldsDefaultOn: Flow<Boolean> =
        settingsDao.observeAll().map { rows ->
            rows.firstOrNull { it.key == SettingsKeys.SHIELDS_DEFAULT_ON }?.value != "false"
        }

    /** Gallery presentation: thumbnail grid (default) or detail list. */
    val homeViewMode: Flow<ViewMode> =
        settingsDao.observeAll().map { rows ->
            if (rows.firstOrNull { it.key == SettingsKeys.HOME_VIEW_MODE }?.value == "LIST") {
                ViewMode.LIST
            } else {
                ViewMode.GRID
            }
        }

    fun start() {
        scope.launch {
            autoLock.collect { option -> AutoLockManager.delayMillis = option.delayMillis }
        }
        scope.launch {
            screenshotProtection.collect { GlobalConfig.screenshotProtection = it }
        }
        scope.launch {
            shakeEnabled.collect { GlobalConfig.shakeEnabled = it }
        }
        scope.launch {
            shakeSensitivity.collect { GlobalConfig.shakeThreshold = it.threshold }
        }
    }

    suspend fun getRaw(key: String): String? = settingsDao.get(key)

    suspend fun setAutoLock(option: AutoLockOption) {
        settingsDao.put(SettingEntity(SettingsKeys.AUTO_LOCK, option.name))
        AutoLockManager.delayMillis = option.delayMillis
    }

    suspend fun setScreenshotProtection(enabled: Boolean) {
        settingsDao.put(SettingEntity(SettingsKeys.SCREENSHOT_PROTECTION, enabled.toString()))
    }

    suspend fun setBiometricEnabled(enabled: Boolean) {
        settingsDao.put(SettingEntity(SettingsKeys.BIOMETRIC_ENABLED, enabled.toString()))
    }

    suspend fun setShakeEnabled(enabled: Boolean) {
        settingsDao.put(SettingEntity(SettingsKeys.SHAKE_ENABLED, enabled.toString()))
    }

    suspend fun setShakeSensitivity(sensitivity: ShakeSensitivity) {
        settingsDao.put(SettingEntity(SettingsKeys.SHAKE_SENSITIVITY, sensitivity.name))
    }

    suspend fun setBreakInAlertEnabled(enabled: Boolean) {
        settingsDao.put(SettingEntity(SettingsKeys.BREAK_IN_ENABLED, enabled.toString()))
    }

    suspend fun setBreakInPhotosEnabled(enabled: Boolean) {
        settingsDao.put(SettingEntity(SettingsKeys.BREAK_IN_PHOTOS, enabled.toString()))
    }

    suspend fun setSearchEngine(engine: SearchEngine) {
        settingsDao.put(SettingEntity(SettingsKeys.SEARCH_ENGINE, engine.name))
    }

    suspend fun setShieldsDefaultOn(on: Boolean) {
        settingsDao.put(SettingEntity(SettingsKeys.SHIELDS_DEFAULT_ON, on.toString()))
    }

    suspend fun setHomeViewMode(mode: ViewMode) {
        settingsDao.put(SettingEntity(SettingsKeys.HOME_VIEW_MODE, mode.name))
    }

    suspend fun subtitleStyle(): SubtitleStyle = SubtitleStyle(
        sizeSp = getRaw(SettingsKeys.SUBTITLE_SIZE_SP)?.toIntOrNull() ?: 22,
        textColor = getRaw(SettingsKeys.SUBTITLE_TEXT_COLOR)?.toLongOrNull()?.toInt()
            ?: 0xFFFFFFFF.toInt(),
        bgColor = getRaw(SettingsKeys.SUBTITLE_BG_COLOR)?.toLongOrNull()?.toInt() ?: 0,
        edge = SubtitleEdge.fromName(getRaw(SettingsKeys.SUBTITLE_EDGE)),
    )

    suspend fun setSubtitleStyle(style: SubtitleStyle) {
        settingsDao.put(SettingEntity(SettingsKeys.SUBTITLE_SIZE_SP, style.sizeSp.toString()))
        settingsDao.put(
            SettingEntity(
                SettingsKeys.SUBTITLE_TEXT_COLOR,
                (style.textColor.toLong() and 0xFFFFFFFFL).toString(),
            )
        )
        settingsDao.put(
            SettingEntity(
                SettingsKeys.SUBTITLE_BG_COLOR,
                (style.bgColor.toLong() and 0xFFFFFFFFL).toString(),
            )
        )
        settingsDao.put(SettingEntity(SettingsKeys.SUBTITLE_EDGE, style.edge.name))
    }

    private fun parseAutoLock(value: String?): AutoLockOption =
        AutoLockOption.entries.firstOrNull { it.name == value } ?: AutoLockOption.IMMEDIATE
}
