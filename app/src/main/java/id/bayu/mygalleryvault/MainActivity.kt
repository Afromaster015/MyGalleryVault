package id.bayu.mygalleryvault

import android.os.Bundle
import android.view.WindowManager
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import id.bayu.mygalleryvault.core.config.GlobalConfig
import id.bayu.mygalleryvault.core.lock.ShakeToClose
import id.bayu.mygalleryvault.ui.AppRoot

class MainActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        applyScreenshotProtection()
        setContent {
            AppRoot(activity = this)
        }
    }

    override fun onResume() {
        super.onResume()
        applyScreenshotProtection()
        ShakeToClose.start(this)
    }

    override fun onPause() {
        ShakeToClose.stop()
        super.onPause()
    }

    /** Screenshot/recents protection per PRD §23-24, toggleable in Settings. */
    private fun applyScreenshotProtection() {
        if (GlobalConfig.screenshotProtection) {
            window.setFlags(
                WindowManager.LayoutParams.FLAG_SECURE,
                WindowManager.LayoutParams.FLAG_SECURE,
            )
        } else {
            window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
        }
    }
}
