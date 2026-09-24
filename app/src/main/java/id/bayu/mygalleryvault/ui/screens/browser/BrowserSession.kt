package id.bayu.mygalleryvault.ui.screens.browser

import android.content.Context
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebView
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
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

    /** Address a freshly created tab must open instead of the start page. */
    val pendingUrls = mutableMapOf<String, String>()

    // Observable per-tab / global chrome state.
    val desktopModes = mutableStateMapOf<String, Boolean>()
    val progressMap = mutableStateMapOf<String, Int>()
    val navMap = mutableStateMapOf<String, Pair<Boolean, Boolean>>()
    val urlMap = mutableStateMapOf<String, String>()

    /** Last main-frame load failure per tab; null/absent means the page loaded. */
    val errorMap = mutableStateMapOf<String, String>()

    var activeTabId: String? by mutableStateOf(null)
    var shieldsOffTabs: Set<String> by mutableStateOf(emptySet())
    var tabsBumper by mutableStateOf(0)
    val hostWhitelist = mutableSetOf<String>()

    /**
     * Everything a WebView opens or reports lives here for the same reason the WebViews do: a
     * tab's WebView outlives the browser screen (switching to Gallery disposes the screen but
     * not the tab). Listeners installed on a WebView keep running afterwards, so state they
     * write into must not have died with the composition they were created in - otherwise
     * long-press, find and fullscreen go silent the second time you visit the browser.
     */

    /** What the last long-press landed on; null means no context menu is open. */
    var contextTarget: WebContextTarget? by mutableStateOf(null)

    /**
     * Counts long-presses. The address of a picture inside a link arrives on a callback, and a
     * late answer must not reopen the menu of a press the user has already moved on from.
     */
    var contextToken: Int = 0
        private set

    fun nextToken(): Int {
        contextToken++
        return contextToken
    }

    /** Where the last finger went down, in view coordinates; the page is asked about that point
     *  when the hit test cannot name what was pressed (a video). */
    var lastTouchX: Float? = null
    var lastTouchY: Float? = null

    /** One in-page translation engine per tab, alive as long as that tab's WebView is. */
    val translators = mutableMapOf<String, PageTranslator>()

    /** Match count for find-in-page; the listener is installed on the WebView. */
    var findMatches by mutableIntStateOf(0)

    /** HTML5 video fullscreen: the view the page handed over, its callback, and the
     *  orientation to come back to when the video lets go. */
    var fullscreenView: View? by mutableStateOf(null)
    var fullscreenCallback: WebChromeClient.CustomViewCallback? = null
    var orientationBeforeFullscreen: Int = android.content.pm.ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED

    /**
     * Leaves fullscreen by handing the page its view back; WebView answers that with
     * onHideCustomView, which is the single place that detaches and restores.
     */
    fun requestExitFullscreen() {
        val callback = fullscreenCallback
        if (callback != null) {
            fullscreenCallback = null
            runCatching { callback.onCustomViewHidden() }
            return
        }
        fullscreenView?.let { view ->
            (view.parent as? ViewGroup)?.removeView(view)
            fullscreenView = null
        }
    }

    fun bump() {
        tabsBumper++
    }

    /** Destroys every WebView, clears engine storage, resets registries. */
    fun wipeAll(context: Context) {
        requestExitFullscreen()
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
        pendingUrls.clear()
        desktopModes.clear()
        progressMap.clear()
        navMap.clear()
        urlMap.clear()
        errorMap.clear()
        registry.clear()
        shieldsOffTabs = emptySet()
        hostWhitelist.clear()
        activeTabId = null
        contextTarget = null
        nextToken()
        translators.clear()
        findMatches = 0
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
