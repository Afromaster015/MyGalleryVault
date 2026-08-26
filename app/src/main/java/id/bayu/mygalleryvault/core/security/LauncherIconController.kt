package id.bayu.mygalleryvault.core.security

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager

/**
 * Hide/show the launcher icon via an activity-alias (PRD §29).
 *
 * This is only a privacy/convenience layer: the app remains visible in system
 * Settings, permissions screens, and to anyone with adb access.
 */
object LauncherIconController {

    const val LAUNCHER_ALIAS = "id.bayu.mygalleryvault.Launcher"

    fun isHidden(context: Context): Boolean =
        componentState(context) == PackageManager.COMPONENT_ENABLED_STATE_DISABLED

    fun setHidden(context: Context, hidden: Boolean) {
        val state =
            if (hidden) PackageManager.COMPONENT_ENABLED_STATE_DISABLED
            else PackageManager.COMPONENT_ENABLED_STATE_ENABLED
        context.packageManager.setComponentEnabledSetting(
            ComponentName(context, LAUNCHER_ALIAS),
            state,
            PackageManager.DONT_KILL_APP,
        )
    }

    private fun componentState(context: Context): Int =
        context.packageManager.getComponentEnabledSetting(
            ComponentName(context, LAUNCHER_ALIAS),
        )
}
