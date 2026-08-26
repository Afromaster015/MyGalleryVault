package id.bayu.mygalleryvault.ui.screens.browser

import android.content.Context
import android.os.Bundle
import android.webkit.CookieManager
import android.webkit.WebView
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * Process-wide browser session (PRD §38 revisited per UX request):
 *
 * Tabs, their WebViews and all observable state live HERE - not inside the
 * composable - so switching to the Gallery tab or navigating away does NOT
 * destroy browsing data. The session ends only when:
 *   - the user explicitly taps "Tutup semua tab & bersihkan data", or
 *   - the OS kills the process (swipe from Recents) - memory-resident data
 *     dies with it by design, nothing was ever persisted to disk.
 */
object BrowserSession {

    const val MAX_LIVE_TABS = 6

    val registry = TabRegistry(MAX_LIVE_TABS)

    // Live WebView instances keyed by tab id (activity-scoped context).
    val webViews = mutableMapOf<String, WebView>()
    val savedStates = mutableMapOf<String, Bundle>()

    // Observable per-tab / global chrome state.
    val desktopModes = mutableStateMapOf<String, Boolean>()
    val progressMap = mutableStateMapOf<String, Int>()
    val navMap = mutableStateMapOf<String, Pair<Boolean, Boolean>>()
    val urlMap = mutableStateMapOf<String, String>()

    var activeTabId: String? by mutableStateOf(null)
    var shieldsOffTabs: Set<String> by mutableStateOf(emptySet())
    var tabsBumper by mutableStateOf(0)
    val hostWhitelist = mutableSetOf<String>()

    fun bump() {
        tabsBumper++
    }

    /** Destroys every WebView, clears engine storage, resets registries. */
    fun wipeAll(context: Context) {
        for ((_, wv) in webViews) {
            runCatching {
                wv.stopLoading()
                wv.loadUrl("about:blank")
                wv.clearHistory()
                wv.clearFormData()
                wv.clearCache(true)
                wv.clearSslPreferences()
                wv.destroy()
            }
        }
        webViews.clear()
        savedStates.clear()
        desktopModes.clear()
        progressMap.clear()
        navMap.clear()
        urlMap.clear()
        registry.clear()
        shieldsOffTabs = emptySet()
        hostWhitelist.clear()
        activeTabId = null
        runCatching {
            CookieManager.getInstance().removeAllCookies(null)
            CookieManager.getInstance().flush()
        }
        runCatching { android.webkit.WebStorage.getInstance().deleteAllData() }
        runCatching {
            android.webkit.WebViewDatabase.getInstance(context).clearHttpAuthUsernamePassword()
        }
        id.bayu.mygalleryvault.core.browser.ShieldBlocker.resetAll()
    }
}
