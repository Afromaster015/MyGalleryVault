package id.bayu.mygalleryvault

import id.bayu.mygalleryvault.core.crypto.VaultKeyManager
import id.bayu.mygalleryvault.core.security.SecurePhotoStore
import id.bayu.mygalleryvault.data.local.SecurityEventEntity
import id.bayu.mygalleryvault.data.local.SettingEntity
import id.bayu.mygalleryvault.data.repository.AuthRepository
import id.bayu.mygalleryvault.domain.model.UnlockResult
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * Reproduces the exact user flow that crashed on device/emulator:
 * setup vault on first run -> app restart (fresh instances, same storage) ->
 * unlock with the created PIN.
 */
class UnlockFlowReproTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private class FakeSettingsDao : id.bayu.mygalleryvault.data.local.SettingsDao {
        val map = HashMap<String, String>()
        override suspend fun get(key: String): String? = map[key]
        override fun observeAll(): Flow<List<SettingEntity>> =
            MutableStateFlow(map.map { SettingEntity(it.key, it.value) })
        override suspend fun put(setting: SettingEntity) { map[setting.key] = setting.value }
        override suspend fun delete(key: String) { map.remove(key) }
    }

    private class FakeSecurityEventDao : id.bayu.mygalleryvault.data.local.SecurityEventDao {
        val rows = mutableListOf<SecurityEventEntity>()
        var nextId = 1L
        override fun recent(limit: Int): Flow<List<SecurityEventEntity>> =
            MutableStateFlow(rows.take(limit))
        override suspend fun recentOnce(limit: Int): List<SecurityEventEntity> = rows.take(limit)
        override suspend fun unacknowledgedBreakIns(): List<SecurityEventEntity> =
            rows.filter { it.eventType == AuthRepository.EVENT_BREAKIN_ALERT && !it.acknowledged }
        override suspend fun insert(event: SecurityEventEntity): Long {
            rows.add(event.copy(id = nextId)); return nextId++
        }
        override suspend fun acknowledge(ids: List<Long>) {
            for (i in rows.indices) if (rows[i].id in ids) rows[i] = rows[i].copy(acknowledged = true)
        }
        override suspend fun clear() = rows.clear()
    }

    @Test
    fun `unlock succeeds after simulated app restart`() = runBlocking {
        val keysDir: File = tmp.newFolder("keys")
        val pin = "654321".toCharArray()

        // ---- first launch: create vault ----
        val auth1 = AuthRepository(
            VaultKeyManager(keysDir),
            FakeSettingsDao(),
            FakeSecurityEventDao(),
            SecurePhotoStore(tmp.newFolder("sec")),
        )
        auth1.setupVault(pin)

        // ---- second launch: brand-new instances over the same storage ----
        val settings2 = FakeSettingsDao()
        val events2 = FakeSecurityEventDao()
        val auth2 = AuthRepository(
            VaultKeyManager(keysDir),
            settings2,
            events2,
            SecurePhotoStore(tmp.newFolder("sec2")),
        )
        assertTrue(auth2.isVaultCreated)

        val result = auth2.unlock("654321".toCharArray())
        assertTrue("Expected Success but got $result", result is UnlockResult.Success)
        Unit
    }

    @Test
    fun `wrong pin increments counter and eventually flags break-in`() = runBlocking {
        val keysDir: File = tmp.newFolder("keys2")
        val settings = FakeSettingsDao()
        val events = FakeSecurityEventDao()
        val auth = AuthRepository(
            VaultKeyManager(keysDir),
            settings,
            events,
            SecurePhotoStore(tmp.newFolder("sec3")),
        )
        auth.setupVault("111111".toCharArray())
        // emulate app restart: session locked again
        id.bayu.mygalleryvault.core.crypto.VaultSession.lockNow()

        var breachSeen = false
        repeat(AuthRepository.DEFAULT_THRESHOLD) { attempt ->
            val r = auth.unlock("999999".toCharArray())
            val failed = r as? UnlockResult.Failed
            assertTrue("Expected Failed but got $r", failed != null)
            breachSeen = breachSeen || failed!!.breakInDetected
            assertEquals(attempt + 1, failed!!.attemptCount)
            // Mirror the LockViewModel caller: on breach it records the alert (§28).
            if (failed.breakInDetected) auth.recordBreakin(failed.attemptCount, null)
        }
        assertTrue("Threshold breach not flagged", breachSeen)

        // Owner-visible alerts flow (§28): pending -> acknowledge -> gone.
        val pending = auth.pendingBreakInAlerts()
        assertEquals(1, pending.size)
        assertEquals(AuthRepository.DEFAULT_THRESHOLD, pending[0].attempts)
        auth.acknowledgeSecurityEvents(listOf(pending[0].eventId))
        assertTrue(auth.pendingBreakInAlerts().isEmpty())
        assertTrue(events.rows.any { it.eventType == AuthRepository.EVENT_BREAKIN_ALERT })

        // correct PIN afterwards still unlocks and resets the counter
        val ok = auth.unlock("111111".toCharArray())
        assertTrue("Expected Success after correct PIN but got $ok", ok is UnlockResult.Success)
        assertEquals("0", settings.map["failed_count"])
        Unit
    }
}
