package id.bayu.mygalleryvault.data.repository

import android.graphics.Bitmap
import id.bayu.mygalleryvault.core.crypto.VaultKeyManager
import id.bayu.mygalleryvault.core.crypto.VaultSession
import id.bayu.mygalleryvault.core.security.SecurePhotoStore
import id.bayu.mygalleryvault.data.local.SecurityEventDao
import id.bayu.mygalleryvault.data.local.SecurityEventEntity
import id.bayu.mygalleryvault.data.local.SettingsDao
import id.bayu.mygalleryvault.data.local.SettingEntity
import id.bayu.mygalleryvault.domain.model.SettingsKeys
import id.bayu.mygalleryvault.domain.model.UnlockResult
import id.bayu.mygalleryvault.domain.model.VaultSlot
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Authentication & security events. Handles both vault slots (§25): the same
 * PIN prompt opens whichever slot's PIN matches, without revealing that a
 * second vault may exist.
 */
class AuthRepository(
    private val keyManager: VaultKeyManager,
    private val settingsDao: SettingsDao,
    private val securityEventDao: SecurityEventDao,
    private val photoStore: SecurePhotoStore,
) {

    val isVaultCreated: Boolean get() = keyManager.isVaultCreated
    val isDecoyCreated: Boolean get() = keyManager.isDecoyCreated

    // ---------- settings passthrough ----------

    suspend fun failedThreshold(): Int =
        settingsDao.get(SettingsKeys.FAILED_THRESHOLD)?.toIntOrNull() ?: DEFAULT_THRESHOLD

    suspend fun setFailedThreshold(value: Int) {
        settingsDao.put(SettingEntity(SettingsKeys.FAILED_THRESHOLD, value.coerceIn(3, 10).toString()))
    }

    suspend fun breakInAlertEnabled(): Boolean =
        settingsDao.get(SettingsKeys.BREAK_IN_ENABLED)?.toBooleanStrictOrNull() ?: true

    suspend fun setBreakInAlertEnabled(enabled: Boolean) {
        settingsDao.put(SettingEntity(SettingsKeys.BREAK_IN_ENABLED, enabled.toString()))
    }

    suspend fun breakInPhotosEnabled(): Boolean =
        settingsDao.get(SettingsKeys.BREAK_IN_PHOTOS)?.toBooleanStrictOrNull() ?: false

    suspend fun setBreakInPhotosEnabled(enabled: Boolean) {
        settingsDao.put(SettingEntity(SettingsKeys.BREAK_IN_PHOTOS, enabled.toString()))
    }

    suspend fun biometricEnabled(): Boolean =
        keyManager.isBiometricEnabled &&
            settingsDao.get(SettingsKeys.BIOMETRIC_ENABLED) == "true"

    suspend fun setBiometricAllowed(allowed: Boolean) {
        settingsDao.put(SettingEntity(SettingsKeys.BIOMETRIC_ENABLED, allowed.toString()))
    }

    // ---------- setup / unlock (§19-21, §25) ----------

    suspend fun setupVault(pin: CharArray) {
        require(pin.size >= MIN_PIN_LENGTH) { "PIN minimal $MIN_PIN_LENGTH digit" }
        // PBKDF2 is CPU-heavy; never run it on the caller's (main) thread.
        val key = withContext(Dispatchers.Default) { keyManager.createVault(pin) }
        settingsDao.put(SettingEntity(SettingsKeys.FAILED_COUNT, "0"))
        VaultSession.unlock(key, VaultSlot.REAL)
    }

    /** Creates an independent decoy vault (§25). PIN must differ from the real one. */
    suspend fun createDecoyVault(pin: CharArray): Result<Unit> {
        if (!isVaultCreated) return Result.failure(IllegalStateException("Vault utama belum ada"))
        if (pin.size < MIN_PIN_LENGTH) {
            return Result.failure(IllegalArgumentException("PIN minimal $MIN_PIN_LENGTH digit"))
        }
        val matchesReal = withContext(Dispatchers.Default) {
            runCatching { keyManager.unlockWithPin(pin, VaultSlot.REAL) }.getOrNull() != null
        }
        if (matchesReal) {
            return Result.failure(IllegalArgumentException("PIN decoy harus berbeda dari PIN utama"))
        }
        return withContext(Dispatchers.Default) {
            try {
                keyManager.createVault(pin, VaultSlot.DECOY)
                Result.success(Unit)
            } catch (e: Exception) {
                Result.failure(e)
            }
        }
    }

    suspend fun disableDecoyVault() {
        keyManager.wipeDecoy()
    }

    /**
     * Tries the real vault first, then the decoy (§25). A wrong PIN increments
     * the global failed counter; reaching the threshold flags a break-in so the
     * caller can capture evidence (§27-28).
     */
    suspend fun unlock(pin: CharArray): UnlockResult = withContext(Dispatchers.Default) {
        val threshold = failedThreshold()
        val previousCount = settingsDao.get(SettingsKeys.FAILED_COUNT)?.toIntOrNull() ?: 0

        val realKey = runCatching { keyManager.unlockWithPin(pin, VaultSlot.REAL) }.getOrNull()
        if (realKey != null) {
            resetFailedCount()
            VaultSession.unlock(realKey, VaultSlot.REAL)
            return@withContext UnlockResult.Success(VaultSlot.REAL)
        }

        if (keyManager.isDecoyCreated) {
            val decoyKey = runCatching { keyManager.unlockWithPin(pin, VaultSlot.DECOY) }.getOrNull()
            if (decoyKey != null) {
                resetFailedCount()
                VaultSession.unlock(decoyKey, VaultSlot.DECOY)
                return@withContext UnlockResult.Success(VaultSlot.DECOY)
            }
        }

        val newCount = previousCount + 1
        settingsDao.put(SettingEntity(SettingsKeys.FAILED_COUNT, newCount.toString()))
        securityEventDao.insert(
            SecurityEventEntity(
                eventType = EVENT_FAILED_ATTEMPT,
                timestamp = System.currentTimeMillis(),
                metadata = "attempt=$newCount",
            )
        )
        val breach = threshold > 0 && newCount % threshold == 0 && breakInAlertEnabled()
        // When [breach] is set the lock-flow caller records a single BREAKIN_ALERT
        // via [recordBreakin], optionally attaching an intruder snapshot (§28).
        val backoff = minOf(BACKOFF_BASE_MS shl (newCount - 1).coerceAtMost(5), MAX_BACKOFF_MS)
        UnlockResult.Failed(newCount, backoff, breakInDetected = breach)
    }

    /**
     * Stores a break-in event with an optional intruder photo (§28).
     * Called from the lock flow when the failure counter breaches the threshold.
     */
    suspend fun recordBreakin(attemptCount: Int, photoJpeg: ByteArray?) {
        var photoName: String? = null
        if (photoJpeg != null) {
            photoName = withContext(Dispatchers.IO) {
                photoStore.save(photoJpeg, "evt_${System.currentTimeMillis()}")
            }
        }
        recordBreakinEvent(attemptCount, photoName)
    }

    private suspend fun recordBreakinEvent(attemptCount: Int, photoName: String?) {
        securityEventDao.insert(
            SecurityEventEntity(
                eventType = EVENT_BREAKIN_ALERT,
                timestamp = System.currentTimeMillis(),
                metadata = buildString {
                    append("failed_attempts=$attemptCount")
                    if (photoName != null) append(";photo=$photoName")
                },
            )
        )
    }

    data class BreakInAlertUi(
        val eventId: Long,
        val timestamp: Long,
        val attempts: Int,
        val photo: Bitmap?,
    )

    /** Unacknowledged break-in alerts shown to the owner after a successful login (§28). */
    suspend fun pendingBreakInAlerts(): List<BreakInAlertUi> {
        val rows = securityEventDao.unacknowledgedBreakIns()
        return rows.mapNotNull { row ->
            val meta = parseMetadata(row.metadata)
            val photo = meta["photo"]?.let { name ->
                withContext(Dispatchers.IO) { photoStore.loadBitmap(name) }
            }
            BreakInAlertUi(
                eventId = row.id,
                timestamp = row.timestamp,
                attempts = meta["failed_attempts"]?.toIntOrNull() ?: 0,
                photo = photo,
            )
        }
    }

    data class SecurityLogEntry(
        val eventType: String,
        val timestamp: Long,
        val attemptCount: Int,
        val hasPhoto: Boolean,
        val thumbnail: Bitmap?,
    )

    suspend fun recentSecurityEvents(limit: Int = 30): List<SecurityLogEntry> =
        securityEventDao.recentOnce(limit).map { row ->
            val meta = parseMetadata(row.metadata)
            val photoName = meta["photo"]
            SecurityLogEntry(
                eventType = row.eventType,
                timestamp = row.timestamp,
                attemptCount = meta["attempt"]?.toIntOrNull()
                    ?: meta["failed_attempts"]?.toIntOrNull() ?: 0,
                hasPhoto = photoName != null,
                thumbnail = photoName?.let {
                    withContext(Dispatchers.IO) { photoStore.loadBitmap(it) }
                },
            )
        }

    suspend fun acknowledgeSecurityEvents(ids: List<Long>) {
        if (ids.isNotEmpty()) securityEventDao.acknowledge(ids)
    }

    suspend fun clearSecurityLog() {
        securityEventDao.clear()
    }

    // ---------- biometric (real slot only, §21) ----------

    fun prepareBiometricEnable(): VaultKeyManager.BiometricCipherRequest =
        keyManager.prepareBiometricEnable()

    fun completeBiometricEnable(request: VaultKeyManager.BiometricCipherRequest) {
        val master = checkNotNull(VaultSession.masterKey) { "Vault harus terbuka" }
        keyManager.completeBiometricEnable(request, master)
    }

    fun prepareBiometricUnlock(): VaultKeyManager.BiometricDecryptRequest =
        keyManager.prepareBiometricUnlock()

    private val bgScope = CoroutineScope(
        SupervisorJob() + Dispatchers.Default
    )

    fun completeBiometricUnlock(request: VaultKeyManager.BiometricDecryptRequest) {
        val key = keyManager.completeBiometricUnlock(request)
        bgScope.launch {
            resetFailedCount()
        }
        VaultSession.unlock(key, VaultSlot.REAL)
    }

    fun disableBiometric() {
        keyManager.disableBiometric()
    }

    // ---------- change pin (operates on the currently open slot) ----------

    suspend fun changePin(oldPin: CharArray, newPin: CharArray): Boolean {
        require(newPin.size >= MIN_PIN_LENGTH) { "PIN minimal $MIN_PIN_LENGTH digit" }
        val currentSlot = VaultSession.slot ?: VaultSlot.REAL
        return withContext(Dispatchers.Default) {
            val verified = try {
                keyManager.unlockWithPin(oldPin, currentSlot)
            } catch (_: Exception) {
                null
            } ?: return@withContext false
            val master = VaultSession.masterKey ?: verified
            keyManager.changePin(newPin, master, currentSlot)
            verified.encoded.fill(0)
            true
        }
    }

    // ---------- helpers ----------

    private suspend fun resetFailedCount() {
        settingsDao.put(SettingEntity(SettingsKeys.FAILED_COUNT, "0"))
    }

    private fun parseMetadata(metadata: String?): Map<String, String> =
        metadata.orEmpty().split(';')
            .filter { it.contains('=') }
            .associate {
                val idx = it.indexOf('=')
                it.substring(0, idx) to it.substring(idx + 1)
            }

    companion object {
        const val MIN_PIN_LENGTH = 6
        const val DEFAULT_THRESHOLD = 5
        const val EVENT_FAILED_ATTEMPT = "FAILED_ATTEMPT"
        const val EVENT_BREAKIN_ALERT = "BREAKIN_ALERT"
        private const val BACKOFF_BASE_MS = 1000L
        private const val MAX_BACKOFF_MS = 30_000L
    }
}
