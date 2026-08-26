package id.bayu.mygalleryvault.core.crypto

import id.bayu.mygalleryvault.domain.model.VaultSlot
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import javax.crypto.SecretKey

/**
 * Holds the decrypted master key strictly in memory while the vault is unlocked.
 * Cleared on lock events (auto-lock, manual lock). Also tracks which slot
 * (real / decoy) is currently open (PRD §25).
 */
object VaultSession {

    @Volatile
    var masterKey: SecretKey? = null
        private set

    @Volatile
    var slot: VaultSlot? = null
        private set

    private val _unlocked = MutableStateFlow(false)
    val isUnlocked: StateFlow<Boolean> = _unlocked

    fun unlock(key: SecretKey, slot: VaultSlot) {
        masterKey = key
        this.slot = slot
        _unlocked.value = true
    }

    fun lockNow() {
        val wasUnlocked = _unlocked.value
        masterKey = null
        slot = null
        _unlocked.value = false
        if (wasUnlocked) {
            // Best-effort scrub of cached key material references.
            System.gc()
        }
    }
}
