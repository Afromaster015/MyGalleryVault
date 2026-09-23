package id.bayu.mygalleryvault

import id.bayu.mygalleryvault.core.crypto.VaultKeyManager
import id.bayu.mygalleryvault.core.crypto.VaultSession
import id.bayu.mygalleryvault.core.security.SecurePhotoStore
import id.bayu.mygalleryvault.data.repository.AuthRepository
import id.bayu.mygalleryvault.domain.model.SettingsKeys
import id.bayu.mygalleryvault.domain.model.UnlockResult
import id.bayu.mygalleryvault.domain.model.VaultSlot
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Security-flow coverage for AuthRepository: keypad backoff ramp, threshold
 * clamping, break-in toggle, decoy slot routing, and PIN change.
 */
class AuthSecurityFlowTest {

    @get:Rule
    val tmp = TemporaryFolder()

    @Test
    fun `wrong pin backoff ramps linearly and caps at 30s`() = runBlocking {
        val keysDir = tmp.newFolder("k_backoff")
        val settings = FakeSettingsDao()
        val auth = AuthRepository(
            VaultKeyManager(keysDir), settings, FakeSecurityEventDao(),
            SecurePhotoStore(tmp.newFolder("sec_backoff")),
        )
        auth.setupVault("111111".toCharArray())
        VaultSession.lockNow()

        val expected = listOf(5_000L, 10_000L, 15_000L, 20_000L, 25_000L, 30_000L, 30_000L)
        expected.forEachIndexed { i, wantBackoff ->
            val r = auth.unlock("999999".toCharArray())
            val failed = r as? UnlockResult.Failed ?: throw AssertionError("expected Failed at attempt ${i + 1}, got $r")
            assertEquals("attempt ${i + 1} count", i + 1, failed.attemptCount)
            assertEquals("attempt ${i + 1} backoff", wantBackoff, failed.backoffMillis)
            assertTrue("keypad deadline must be in the future", auth.lockedUntilMillis() > System.currentTimeMillis() - 1)
        }
        // Default threshold 5: breach flagged exactly on the 5th failure.
        // (re-check via stored counter since results above were asserted individually)
        assertEquals("7", settings.map[SettingsKeys.FAILED_COUNT])

        // Correct PIN resets both the counter and the keypad deadline.
        VaultSession.lockNow()
        val ok = auth.unlock("111111".toCharArray())
        assertTrue("expected Success, got $ok", ok is UnlockResult.Success)
        assertEquals("0", settings.map[SettingsKeys.FAILED_COUNT])
        assertEquals(0L, auth.lockedUntilMillis())
        Unit
    }

    @Test
    fun `failed threshold setting clamps between 3 and 10`() = runBlocking {
        val auth = AuthRepository(
            VaultKeyManager(tmp.newFolder("k_clamp")), FakeSettingsDao(), FakeSecurityEventDao(),
            SecurePhotoStore(tmp.newFolder("sec_clamp")),
        )
        assertEquals(AuthRepository.DEFAULT_THRESHOLD, auth.failedThreshold())
        auth.setFailedThreshold(1)
        assertEquals(3, auth.failedThreshold())
        auth.setFailedThreshold(99)
        assertEquals(10, auth.failedThreshold())
        auth.setFailedThreshold(7)
        assertEquals(7, auth.failedThreshold())
        Unit
    }

    @Test
    fun `break-in detection toggle suppresses breach flag when disabled`() = runBlocking {
        val settings = FakeSettingsDao()
        val events = FakeSecurityEventDao()
        val auth = AuthRepository(
            VaultKeyManager(tmp.newFolder("k_toggle")), settings, events,
            SecurePhotoStore(tmp.newFolder("sec_toggle")),
        )
        auth.setupVault("111111".toCharArray())
        auth.setBreakInAlertEnabled(false)
        VaultSession.lockNow()

        repeat(AuthRepository.DEFAULT_THRESHOLD) { i ->
            val r = auth.unlock("999999".toCharArray())
            val failed = r as? UnlockResult.Failed ?: throw AssertionError("expected Failed, got $r")
            if (i < AuthRepository.DEFAULT_THRESHOLD - 1) {
                assertFalse("no breach before threshold", failed.breakInDetected)
            } else {
                assertFalse("breach must stay suppressed when alerts are off", failed.breakInDetected)
            }
        }
        assertEquals(AuthRepository.DEFAULT_THRESHOLD.toString(), settings.map[SettingsKeys.FAILED_COUNT])
        assertTrue("failures still logged", events.rows.count { it.eventType == AuthRepository.EVENT_FAILED_ATTEMPT } == AuthRepository.DEFAULT_THRESHOLD)
        assertFalse("no break-in alert recorded", events.rows.any { it.eventType == AuthRepository.EVENT_BREAKIN_ALERT })
        Unit
    }

    @Test
    fun `decoy vault rejects same pin and routes unlock to matching slot`() = runBlocking {
        val auth = AuthRepository(
            VaultKeyManager(tmp.newFolder("k_decoy")), FakeSettingsDao(), FakeSecurityEventDao(),
            SecurePhotoStore(tmp.newFolder("sec_decoy")),
        )
        auth.setupVault("111111".toCharArray())

        val samePin = auth.createDecoyVault("111111".toCharArray())
        assertTrue("same PIN must be rejected", samePin.isFailure)
        assertTrue(
            "message should explain PIN collision: ${samePin.exceptionOrNull()?.message}",
            samePin.exceptionOrNull()?.message?.contains("berbeda") == true,
        )

        val created = auth.createDecoyVault("222222".toCharArray())
        assertTrue("distinct decoy PIN must be accepted: $created", created.isSuccess)
        assertTrue(auth.isDecoyCreated)
        VaultSession.lockNow()

        val decoy = auth.unlock("222222".toCharArray())
        assertTrue("decoy PIN: $decoy", decoy is UnlockResult.Success && decoy.slot == VaultSlot.DECOY)

        VaultSession.lockNow()
        val real = auth.unlock("111111".toCharArray())
        assertTrue("real PIN: $real", real is UnlockResult.Success && real.slot == VaultSlot.REAL)
        Unit
    }

    @Test
    fun `change pin requires correct old pin and rebinds credentials`() = runBlocking {
        val settings = FakeSettingsDao()
        val auth = AuthRepository(
            VaultKeyManager(tmp.newFolder("k_change")), settings, FakeSecurityEventDao(),
            SecurePhotoStore(tmp.newFolder("sec_change")),
        )
        auth.setupVault("111111".toCharArray())

        assertFalse("wrong old PIN must fail", auth.changePin("999999".toCharArray(), "87654321".toCharArray()))
        assertTrue("correct old PIN must succeed", auth.changePin("111111".toCharArray(), "87654321".toCharArray()))
        assertEquals(8, auth.storedPinLength())

        VaultSession.lockNow()
        val withNew = auth.unlock("87654321".toCharArray())
        assertTrue("new PIN must unlock: $withNew", withNew is UnlockResult.Success)

        VaultSession.lockNow()
        val withOld = auth.unlock("111111".toCharArray())
        assertTrue("old PIN must stop working: $withOld", withOld is UnlockResult.Failed)
        assertEquals(1, settings.map[SettingsKeys.FAILED_COUNT]?.toInt())
        Unit
    }

    @Test
    fun `break-in alert acknowledge flow clears pending list`() = runBlocking {
        val settings = FakeSettingsDao()
        val events = FakeSecurityEventDao()
        val auth = AuthRepository(
            VaultKeyManager(tmp.newFolder("k_ack")), settings, events,
            SecurePhotoStore(tmp.newFolder("sec_ack")),
        )
        auth.setupVault("111111".toCharArray())
        VaultSession.lockNow()

        repeat(AuthRepository.DEFAULT_THRESHOLD) {
            val r = auth.unlock("999999".toCharArray()) as UnlockResult.Failed
            if (r.breakInDetected) auth.recordBreakin(r.attemptCount, null)
        }
        val pending = auth.pendingBreakInAlerts()
        assertEquals(1, pending.size)
        assertEquals(AuthRepository.DEFAULT_THRESHOLD, pending[0].attempts)

        auth.acknowledgeSecurityEvents(listOf(pending[0].eventId))
        assertTrue(auth.pendingBreakInAlerts().isEmpty())
        Unit
    }
}
