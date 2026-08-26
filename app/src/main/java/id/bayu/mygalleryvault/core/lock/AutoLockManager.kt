package id.bayu.mygalleryvault.core.lock

import android.app.Activity
import android.app.Application
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import id.bayu.mygalleryvault.core.crypto.VaultSession

/**
 * Auto-lock per PRD §22:
 * - Immediately (default), 30s, 1m, 5m after app goes to background / screen off.
 */
object AutoLockManager : Application.ActivityLifecycleCallbacks {

    const val IMMEDIATE = 0L
    const val THIRTY_SECONDS = 30_000L
    const val ONE_MINUTE = 60_000L
    const val FIVE_MINUTES = 300_000L

    @Volatile
    var delayMillis: Long = IMMEDIATE

    /**
     * Set to true right before launching an external picker (SAF/photo/etc).
     * The resulting background stop must NOT lock the vault, otherwise the
     * user is bounced to the lock screen mid-import and loses their selection.
     */
    @Volatile
    var suppressNextBackground: Boolean = false

    private var startedActivities = 0
    private val handler = Handler(Looper.getMainLooper())

    /** Wrap every launcher.launch(...) call with this. */
    fun launchWithoutAutoLock(block: () -> Unit) {
        suppressNextBackground = true
        block()
    }

    fun register(application: Application) {
        application.registerActivityLifecycleCallbacks(this)
    }

    private val lockRunnable = Runnable { VaultSession.lockNow() }

    private fun scheduleLock() {
        if (!VaultSession.isUnlocked.value) return
        handler.removeCallbacks(lockRunnable)
        if (delayMillis <= 0L) {
            VaultSession.lockNow()
        } else {
            handler.postDelayed(lockRunnable, delayMillis)
        }
    }

    override fun onActivityStarted(activity: Activity) {
        startedActivities++
        if (startedActivities == 1) {
            handler.removeCallbacks(lockRunnable)
        }
    }

    override fun onActivityStopped(activity: Activity) {
        startedActivities--
        if (startedActivities == 0) {
            if (suppressNextBackground) {
                // Our own picker/export activity is in front; keep session alive.
                suppressNextBackground = false
                return
            }
            scheduleLock()
        }
    }

    override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {}
    override fun onActivityResumed(activity: Activity) {}
    override fun onActivityPaused(activity: Activity) {}
    override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) {}
    override fun onActivityDestroyed(activity: Activity) {}
}
