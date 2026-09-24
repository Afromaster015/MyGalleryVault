package id.bayu.mygalleryvault.ui.screens.browser

import android.annotation.SuppressLint
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.graphics.Bitmap
import android.os.Bundle
import android.view.Gravity
import android.view.SurfaceView
import android.view.TextureView
import android.view.View
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.OpenInNew
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.DeleteSweep
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.FindInPage
import androidx.compose.material.icons.rounded.Image
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.KeyboardArrowUp
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.OpenInBrowser
import androidx.compose.material.icons.rounded.Public
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material.icons.rounded.Shield
import androidx.compose.material.icons.rounded.Tab
import androidx.compose.material.icons.rounded.TravelExplore
import androidx.compose.material.icons.rounded.Translate
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.fragment.app.FragmentActivity
import id.bayu.mygalleryvault.SecureVaultApp
import id.bayu.mygalleryvault.core.browser.ShieldBlocker
import id.bayu.mygalleryvault.core.browser.TranslateUrl
import id.bayu.mygalleryvault.domain.model.SearchEngine
import id.bayu.mygalleryvault.domain.model.TranslateLanguage
import id.bayu.mygalleryvault.ui.theme.AppRadius
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Private browser "Shield Edition" (PRD §38):
 * - Multi-tab with LRU hibernation, state persists across tab switches
 *   (session owned by [BrowserSession]; wiped only on explicit action or
 *   process death / swipe-from-recents)
 * - Brave-style shields with live blocked-count badge, per-tab off,
 *   popup/new-tab blocking, path-pattern rules + host blocklist
 */
/** New tabs open straight on Google: the old custom start screen confused more than it
 *  helped. The omnibox still searches whichever engine is chosen in Settings. */
private const val START_PAGE = "https://www.google.com/"

/** Shown once per app process, not once per browser visit. */
private var disclaimerShownThisSession = false

/**
 * Gap between the browser chrome controls. Small on purpose: the icon buttons already carry their
 * own padding, so this only has to keep the omnibox from touching them.
 */
private val BrowserChromeGap = 4.dp

/** What a long-press landed on, plus every address the menu can act upon. Lives on the session
 *  so the WebView's long-press listener does not depend on this screen still being composed. */
data class WebContextTarget(
    val kind: Kind,
    val linkUrl: String? = null,
    val imageUrl: String? = null,
) {
    enum class Kind { LINK, IMAGE, IMAGE_LINK }
}

/** One row of the long-press menu. */
private data class ContextAction(
    val label: String,
    val icon: ImageVector,
    val onClick: () -> Unit,
)

/** Size of the video surface inside the page's fullscreen view, or null while it has no size yet.
 *  WebView draws a <video> into a SurfaceView sized to the video, so the first such surface
 *  carries the aspect ratio we need to turn the screen the right way. */
private fun fullscreenVideoSize(root: View): Pair<Int, Int>? {
    if ((root is SurfaceView || root is TextureView) && root.width > 0 && root.height > 0) {
        return root.width to root.height
    }
    if (root is ViewGroup) {
        for (i in 0 until root.childCount) {
            fullscreenVideoSize(root.getChildAt(i))?.let { return it }
        }
    }
    return null
}

/**
 * Long-press becomes a browser context menu. Plain text falls through untouched so Android's
 * own text selection (block + copy + web search) keeps working. The target is written straight
 * into [BrowserSession]: the WebView outlives this screen, so a listener that wrote into
 * composable state would go silent the moment the screen was re-created.
 */
private fun handleWebLongPress(wv: WebView): Boolean {
    val hit = wv.hitTestResult
    val extra = hit.extra
    return when (hit.type) {
        WebView.HitTestResult.ANCHOR_TYPE,
        WebView.HitTestResult.SRC_ANCHOR_TYPE -> {
            if (extra.isNullOrBlank()) return false
            BrowserSession.contextTarget = WebContextTarget(WebContextTarget.Kind.LINK, linkUrl = extra)
            true
        }

        WebView.HitTestResult.IMAGE_TYPE -> {
            if (extra.isNullOrBlank()) return false
            BrowserSession.contextTarget = WebContextTarget(WebContextTarget.Kind.IMAGE, imageUrl = extra)
            true
        }

        WebView.HitTestResult.IMAGE_ANCHOR_TYPE,
        WebView.HitTestResult.SRC_IMAGE_ANCHOR_TYPE -> {
            // The anchor href is known immediately; the <img> src arrives on a callback
            // (the extra only carries the link, not the picture inside it).
            val handler = object : android.os.Handler(android.os.Looper.getMainLooper()) {
                override fun handleMessage(msg: android.os.Message) {
                    val src = msg.data?.getString("src")?.takeIf { it.isNotBlank() }
                    BrowserSession.contextTarget = WebContextTarget(
                        WebContextTarget.Kind.IMAGE_LINK,
                        linkUrl = extra,
                        imageUrl = src,
                    )
                }
            }
            wv.requestFocusNodeHref(android.os.Message.obtain(handler))
            true
        }

        else -> false
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PrivateBrowserScreen(
    activity: FragmentActivity,
    onBack: () -> Unit,
) {
    val app = activity.application as SecureVaultApp
    val scope = rememberCoroutineScope()
    val settingsRepo = app.container.settingsRepository

    // ---- session-owned state (survives leaving this screen) ----
    val registry = BrowserSession.registry
    val webViews = BrowserSession.webViews
    val savedStates = BrowserSession.savedStates
    val desktopModes = BrowserSession.desktopModes
    val progressMap = BrowserSession.progressMap
    val navMap = BrowserSession.navMap
    val urlMap = BrowserSession.urlMap
    val errorMap = BrowserSession.errorMap
    val hostWhitelist = BrowserSession.hostWhitelist

    var activeTabId by remember { mutableStateOf(BrowserSession.activeTabId) }
    var shieldsOffTabs by remember { mutableStateOf(BrowserSession.shieldsOffTabs) }
    var tabsBumper by remember { mutableIntStateOf(BrowserSession.tabsBumper) }

    fun bumpVersion() {
        tabsBumper++
        BrowserSession.tabsBumper = tabsBumper
        BrowserSession.activeTabId = activeTabId
        BrowserSession.shieldsOffTabs = shieldsOffTabs
    }

    // ---------------- omnibox / chrome state ----------------
    var urlInput by remember { mutableStateOf(urlMap[activeTabId].orEmpty()) }
    // While the field holds focus the user owns the text, so an incoming navigation must not
    // overwrite what is being typed.
    val omniboxInteraction = remember { MutableInteractionSource() }
    val omniboxFocused by omniboxInteraction.collectIsFocusedAsState()
    val focusManager = LocalFocusManager.current
    var menuOpen by remember { mutableStateOf(false) }
    var showEngineSheet by remember { mutableStateOf(false) }
    var showTranslateSheet by remember { mutableStateOf(false) }
    var showTabSwitcher by remember { mutableStateOf(false) }
    var showFindBar by remember { mutableStateOf(false) }
    var findQuery by remember { mutableStateOf("") }
    var showShieldPanel by remember { mutableStateOf(false) }
    var shieldBadge by remember { mutableLongStateOf(0L) }
    var showDisclaimer by remember { mutableStateOf(!disclaimerShownThisSession) }
    var canGoBackGlobal by remember { mutableStateOf(false) }
    var canGoForwardGlobal by remember { mutableStateOf(false) }
    var loading by remember { mutableStateOf(false) }
    val searchEngine by settingsRepo.searchEngine.collectAsStateWithLifecycle(SearchEngine.DUCKDUCKGO)
    val shieldsDefaultOn by settingsRepo.shieldsDefaultOn.collectAsStateWithLifecycle(true)
    val translateTarget by settingsRepo.translateTarget
        .collectAsStateWithLifecycle(TranslateLanguage.INDONESIAN)

    // init: load blocklist, restore existing tabs or seed the first one.
    LaunchedEffect(Unit) {
        ShieldBlocker.ensureLoaded(activity)
        if (registry.size == 0) {
            val id = registry.create()
            activeTabId = id
            BrowserSession.activeTabId = id
            urlInput = ""
            tabsBumper++
        } else {
            val id = activeTabId
                ?: registry.all().firstOrNull()?.id
                ?: registry.create().also {
                    activeTabId = it
                    BrowserSession.activeTabId = it
                    tabsBumper++
                }
            BrowserSession.activeTabId = id
        }
    }

    // The address bar mirrors the active tab instead of waiting for a history commit. That wait
    // is why it used to lag behind the page, and why navigation that never reloads the document
    // (pushState, replaceState, hash changes) did not reach it at all. Typing wins while the
    // field is focused, and the bar resyncs the moment focus leaves it.
    val liveUrl = activeTabId?.let { urlMap[it] }.orEmpty()
    LaunchedEffect(liveUrl, activeTabId, omniboxFocused) {
        if (!omniboxFocused && liveUrl.isNotBlank() && !liveUrl.startsWith("data:")) {
            urlInput = liveUrl
        }
    }

    fun shieldsOn(tabId: String): Boolean =
        !shieldsOffTabs.contains(tabId) && shieldsDefaultOn

    fun toggleShields() {
        shieldsOffTabs =
            if (shieldsOffTabs.contains(activeTabId)) shieldsOffTabs - activeTabId!!
            else shieldsOffTabs + (activeTabId ?: return)
    }

    fun activeWebView(): WebView? = activeTabId?.let { webViews[it] }

    fun submitOmnibox() {
        if (urlInput.isBlank()) return
        val resolved = SearchEngine.resolveInput(urlInput, searchEngine)
        // Show the resolved address right away instead of waiting for the WebView to report it
        // back, and drop focus so the bar returns to its idle look and the keyboard closes.
        activeTabId?.let { urlMap[it] = resolved }
        urlInput = resolved
        focusManager.clearFocus()
        activeWebView()?.loadUrl(resolved)
    }

    // ---------------- shared actions ----------------

    /** Loads an address in the tab that is already on screen. */
    fun navigateCurrent(url: String) {
        activeTabId?.let { urlMap[it] = url }
        urlInput = url
        activeWebView()?.loadUrl(url)
    }

    fun copyToClipboard(text: String) {
        val cm = activity.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return
        cm.setPrimaryClip(ClipData.newPlainText("URL", text))
        Toast.makeText(activity, "Alamat disalin", Toast.LENGTH_SHORT).show()
    }

    /**
     * Sends the page through the translate proxy into [target], in the tab already on screen, so
     * Back afterwards returns to the untranslated page. The address is read back to the real site
     * first when it is already translated, which makes choosing another language replace the
     * translation instead of stacking one proxy on top of another.
     */
    fun translateActivePage(target: TranslateLanguage) {
        val tabId = activeTabId ?: return
        val current = urlMap[tabId]?.takeIf { it.isNotBlank() } ?: return
        val source = TranslateUrl.originalUrl(current) ?: current
        val proxied = TranslateUrl.proxyUrl(source, target) ?: run {
            Toast.makeText(activity, "Halaman ini tidak bisa diterjemahkan", Toast.LENGTH_SHORT).show()
            return
        }
        navigateCurrent(proxied)
    }

    fun shareUrl(text: String) {
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, text)
        }
        activity.startActivity(Intent.createChooser(send, "Bagikan link"))
    }

    fun downloadToVault(url: String) {
        Toast.makeText(activity, "Mengunduh ke vault...", Toast.LENGTH_SHORT).show()
        scope.launch {
            val outcome = app.container.currentStack().transfers.importUrl(url, null)
            val msg = if (outcome.succeeded > 0) "Tersimpan di vault"
            else "Unduhan gagal: ${outcome.failed.firstOrNull() ?: "unknown"}"
            Toast.makeText(activity, msg, Toast.LENGTH_LONG).show()
        }
    }

    /**
     * Turns the screen to match the video the page just went fullscreen with. Nothing measured
     * means nothing rotated: a letterboxed video beats a screen turned the wrong way.
     */
    fun followFullscreenVideoAspect(target: View) {
        val handler = android.os.Handler(android.os.Looper.getMainLooper())
        var tries = 0
        val poll = object : Runnable {
            override fun run() {
                // The user may have left fullscreen while we were waiting for layout.
                if (BrowserSession.fullscreenView !== target) return
                val size = fullscreenVideoSize(target)
                if (size == null) {
                    if (++tries < 20) handler.postDelayed(this, 50)
                    return
                }
                activity.requestedOrientation =
                    if (size.first >= size.second) ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
                    else ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT
            }
        }
        handler.postDelayed(poll, 50)
    }

    // ---------------- tab operations ----------------

    /** Parks a tab's WebView: state saved, page dropped, resources released. */
    fun hibernate(victimIds: List<String>) {
        victimIds.forEach { vid ->
            if (vid == activeTabId) return@forEach
            webViews.remove(vid)?.let { wv ->
                runCatching { savedStates[vid] = Bundle().also { wv.saveState(it) } }
                runCatching {
                    wv.stopLoading()
                    wv.loadUrl("about:blank")
                    wv.destroy()
                }
            }
            registry.markHibernated(vid)
        }
    }

    fun openNewTab(initialUrl: String? = null): String {
        BrowserSession.requestExitFullscreen()
        activeTabId?.let { hibernate(registry.hibernateCandidates(it)) }
        val id = registry.create()
        if (initialUrl != null) BrowserSession.pendingUrls[id] = initialUrl
        activeTabId = id
        BrowserSession.activeTabId = id
        urlInput = initialUrl.orEmpty()
        loading = false
        bumpVersion()
        return id
    }

    // ---------------- webview factory ----------------

    @SuppressLint("SetJavaScriptEnabled")
    @Suppress("DEPRECATION")
    fun createWebView(tabId: String): WebView {
        val wv = WebView(activity)
        wv.settings.apply {
            javaScriptEnabled = true
            // Age gates / consent modals and most modern sites read localStorage or
            // sessionStorage before anything renders. With these off the script throws and
            // the dialog never appears, which is why such pages would not open at all.
            domStorageEnabled = true
            databaseEnabled = true
            @Suppress("DEPRECATION")
            saveFormData = false
            @Suppress("DEPRECATION")
            setSavePassword(false)
            cacheMode = WebSettings.LOAD_NO_CACHE
            allowFileAccess = false
            allowContentAccess = false
            mediaPlaybackRequiresUserGesture = true
            javaScriptCanOpenWindowsAutomatically = false
            // REQUIRED for onCreateWindow() to fire; without this, target=_blank
            // / window.open() popups navigate THIS webview directly (newtab ads).
            setSupportMultipleWindows(true)
        }
        wv.isSaveEnabled = false

        wv.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(
                view: WebView,
                request: WebResourceRequest,
            ): Boolean {
                val scheme = request.url.scheme?.lowercase()
                if (scheme != "https") return true
                // Main-frame redirect into a known ad/tracker host or URL pattern:
                // count and refuse, otherwise the popup hijack wins the tab.
                if (shieldsOn(tabId) && request.isForMainFrame &&
                    request.url.host !in hostWhitelist
                ) {
                    val blocked = ShieldBlocker.isBlocked(request.url) ||
                        ShieldBlocker.matchesPathRule(request.url.toString().lowercase())
                    if (blocked) {
                        ShieldBlocker.increment(tabId)
                        return true
                    }
                }
                return false
            }

            override fun shouldInterceptRequest(
                view: WebView,
                request: WebResourceRequest,
            ): WebResourceResponse? {
                val urlStr = request.url.toString().lowercase()
                val host = request.url.host
                if (shieldsOn(tabId)) {
                    val hostBlocked = host != null && host !in hostWhitelist &&
                        ShieldBlocker.isBlocked(request.url)
                    val patternBlocked = ShieldBlocker.matchesPathRule(urlStr)
                    if (hostBlocked || patternBlocked) {
                        ShieldBlocker.increment(tabId)
                        return WebResourceResponse(
                            "text/plain", "utf-8", java.io.ByteArrayInputStream(ByteArray(0)),
                        )
                    }
                }
                return null
            }

            override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                ShieldBlocker.resetTab(tabId)
                errorMap.remove(tabId)
                // The destination is known the moment the load starts, which is what lets the
                // address bar keep up with the page instead of trailing a history commit.
                url?.let { urlMap[tabId] = it }
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                // Settle on the final address after any redirects.
                url?.let { urlMap[tabId] = it }
            }

            override fun onReceivedError(
                view: WebView?,
                request: WebResourceRequest?,
                error: WebResourceError?,
            ) {
                if (request?.isForMainFrame != true) return
                val detail = error?.description?.toString()?.takeIf { it.isNotBlank() }
                errorMap[tabId] = detail ?: "Penyebab tidak diketahui"
            }

            override fun doUpdateVisitedHistory(view: WebView?, url: String?, isReload: Boolean) {
                registry.updateMeta(tabId, view?.title, url)
                tabsBumper++
                BrowserSession.tabsBumper = tabsBumper
                url?.let { urlMap[tabId] = it }
                if (tabId == activeTabId) {
                    canGoBackGlobal = view?.canGoBack() == true
                    canGoForwardGlobal = view?.canGoForward() == true
                }
            }
        }

        wv.webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(view: WebView?, newProgress: Int) {
                progressMap[tabId] = newProgress
            }

            /**
             * A new window is honored only when the user's own tap asked for it
             * (target=_blank on a link). Anything the page opened on its own is still a
             * popup hijack ("iklan newtab") and gets swallowed and counted.
             */
            override fun onCreateWindow(
                view: WebView?,
                isDialog: Boolean,
                isUserGesture: Boolean,
                resultMsg: android.os.Message?,
            ): Boolean {
                if (!isUserGesture) {
                    ShieldBlocker.increment(tabId)
                    return true // blocked
                }
                val transport = resultMsg?.obj as? WebView.WebViewTransport ?: return true
                val newId = openNewTab()
                val newWv = webViews[newId] ?: createWebView(newId).also { webViews[newId] = it }
                transport.webView = newWv
                resultMsg.sendToTarget()
                bumpVersion()
                return true
            }

            /**
             * HTML5 video fullscreen. WebView hands us a view to fill the screen with and expects
             * the WINDOW to hold it, so it is attached to the window and not routed through
             * Compose: a view reparented by a recomposition could end up attached twice, which is
             * what made fullscreen pile up. The WebView itself stays put and is simply covered.
             */
            override fun onShowCustomView(
                view: View?,
                callback: WebChromeClient.CustomViewCallback?,
            ) {
                val target = view ?: return
                val cb = callback ?: return
                // Replace a custom view that never got its hide callback instead of refusing the
                // request: refusing would leave every later fullscreen tap with nothing on screen.
                BrowserSession.fullscreenView?.let { (it.parent as? ViewGroup)?.removeView(it) }
                (target.parent as? ViewGroup)?.removeView(target)
                BrowserSession.orientationBeforeFullscreen = activity.requestedOrientation
                activity.window.addContentView(
                    target,
                    FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        Gravity.CENTER,
                    ),
                )
                BrowserSession.fullscreenView = target
                BrowserSession.fullscreenCallback = cb
                followFullscreenVideoAspect(target)
            }

            override fun onHideCustomView() {
                val target = BrowserSession.fullscreenView ?: return
                BrowserSession.fullscreenView = null
                BrowserSession.fullscreenCallback = null
                (target.parent as? ViewGroup)?.removeView(target)
                activity.requestedOrientation = BrowserSession.orientationBeforeFullscreen
            }
        }

        wv.setFindListener { _, numberOfMatches, _ ->
            BrowserSession.findMatches = numberOfMatches
        }

        wv.setDownloadListener { url, _, _, _, _ -> downloadToVault(url) }

        wv.setOnLongClickListener { handleWebLongPress(wv) }

        wv.loadUrl(BrowserSession.pendingUrls.remove(tabId) ?: START_PAGE)
        return wv
    }

    fun wakeIfNeeded(id: String) {
        if (!webViews.containsKey(id)) {
            val wv = createWebView(id)
            savedStates[id]?.let { bundle -> runCatching { wv.restoreState(bundle) } }
            webViews[id] = wv
        }
    }

    fun closeTab(id: String) {
        webViews.remove(id)?.apply {
            runCatching {
                stopLoading(); loadUrl("about:blank"); clearHistory(); destroy()
            }
        }
        savedStates.remove(id)
        BrowserSession.pendingUrls.remove(id)
        desktopModes.remove(id)
        progressMap.remove(id)
        navMap.remove(id)
        urlMap.remove(id)
        ShieldBlocker.removeTab(id)
        registry.close(id)
        if (activeTabId == id) {
            val next = registry.all().firstOrNull()?.id
            if (next == null) openNewTab() else {
                activeTabId = next
                BrowserSession.activeTabId = next
                wakeIfNeeded(next)
            }
        }
        bumpVersion()
    }

    fun switchTo(id: String) {
        if (id == activeTabId) return
        BrowserSession.requestExitFullscreen()
        activeTabId?.let { cur ->
            webViews[cur]?.let { wv ->
                runCatching { savedStates[cur] = Bundle().also { wv.saveState(it) } }
            }
        }
        activeTabId = id
        BrowserSession.activeTabId = id
        registry.setActive(id)
        wakeIfNeeded(id)
        urlInput = urlMap[id].orEmpty().ifBlank { registry.entry(id)?.url.orEmpty() }
        val nav = navMap[id]
        canGoBackGlobal = nav?.first == true
        canGoForwardGlobal = nav?.second == true
        hibernate(registry.hibernateCandidates(id))
        bumpVersion()
    }

    fun wipeBrowserData() {
        BrowserSession.requestExitFullscreen()
        BrowserSession.wipeAll(activity)
        activeTabId = null
        val id = registry.create()
        activeTabId = id
        BrowserSession.activeTabId = id
        urlInput = ""
        shieldBadge = 0
        showFindBar = false
        bumpVersion()
        Toast.makeText(activity, "Semua tab & data browser dibersihkan", Toast.LENGTH_SHORT).show()
    }

    BackHandler(enabled = true) {
        when {
            BrowserSession.fullscreenView != null -> BrowserSession.requestExitFullscreen()

            showFindBar -> {
                showFindBar = false
                activeWebView()?.clearMatches()
            }

            // Back walks the page's own history first, then closes the tab the same way the tab
            // switcher's X does. Only the last remaining tab hands you back to the Gallery, so a
            // tab never silently disappears while others are still open.
            activeWebView()?.canGoBack() == true -> activeWebView()?.goBack()

            registry.size > 1 -> closeTab(activeTabId ?: return@BackHandler)

            else -> onBack()
        }
    }

    // badge ticker
    LaunchedEffect(activeTabId) {
        while (true) {
            shieldBadge = activeTabId?.let { ShieldBlocker.countFor(it).toLong() } ?: 0L
            delay(700)
        }
    }

    val activeProgress = activeTabId?.let { progressMap[it] } ?: 100
    if (loading != (activeProgress in 1..99)) loading = activeProgress in 1..99
    val animatedProgress by animateFloatAsState(
        targetValue = activeProgress / 100f,
        animationSpec = tween(200),
        label = "browserProgress",
    )

    // While a video owns the screen the system bars step aside. The check in onDispose uses the
    // captured isFullscreen and not the live state: this effect is re-created when fullscreen
    // STARTS, and reading live state there would exit the video the instant it went fullscreen.
    val isFullscreen = BrowserSession.fullscreenView != null
    DisposableEffect(isFullscreen) {
        val controller = WindowCompat.getInsetsController(activity.window, activity.window.decorView)
        if (isFullscreen) {
            controller.hide(WindowInsetsCompat.Type.systemBars())
            controller.systemBarsBehavior =
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        } else {
            controller.show(WindowInsetsCompat.Type.systemBars())
        }
        onDispose {
            if (isFullscreen) BrowserSession.requestExitFullscreen()
            controller.show(WindowInsetsCompat.Type.systemBars())
        }
    }

    Box(Modifier.fillMaxSize()) {
    Column(
        Modifier
            .fillMaxSize()
            .statusBarsPadding()
    ) {
        // One rhythm for the whole strip: the controls sit at even gaps and centre on the omnibox,
        // so the pair on the left and the pair on the right stay symmetric around it.
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(BrowserChromeGap),
            modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp),
        ) {
            Box {
                IconButton(onClick = { showTabSwitcher = true }) {
                    Icon(Icons.Rounded.Tab, "Daftar tab", tint = MaterialTheme.colorScheme.primary)
                }
                if ((tabsBumper.let { registry.size }) > 1) {
                    Text(
                        "${registry.size}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onPrimary,
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .background(MaterialTheme.colorScheme.primary, CircleShape)
                            .padding(horizontal = 4.dp),
                    )
                }
            }
            Box {
                IconButton(onClick = { toggleShields() }) {
                    Icon(
                        Icons.Rounded.Shield,
                        contentDescription = "Shields ${if (activeTabId?.let{shieldsOn(it)} == true) "aktif" else "mati"}",
                        tint = if (activeTabId?.let{shieldsOn(it)} == true) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.outline,
                    )
                }
                if (shieldBadge > 0 && (activeTabId?.let{shieldsOn(it)} == true)) {
                    AnimatedContent(
                        targetState = shieldBadge.coerceAtMost(999).toInt(),
                        transitionSpec = { fadeIn(tween(120)) togetherWith fadeOut(tween(120)) },
                        label = "shieldBadge",
                        modifier = Modifier.align(Alignment.TopEnd),
                    ) { count ->
                        Text(
                            "$count",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onPrimary,
                            modifier = Modifier
                                .background(MaterialTheme.colorScheme.primary, CircleShape)
                                .padding(horizontal = 4.dp),
                        )
                    }
                }
            }

            OutlinedTextField(
                value = urlInput,
                onValueChange = { urlInput = it },
                placeholder = { Text("Cari / masukkan URL") },
                singleLine = true,
                textStyle = MaterialTheme.typography.bodyMedium,
                shape = AppRadius.pill,
                interactionSource = omniboxInteraction,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Go),
                keyboardActions = KeyboardActions(onGo = { submitOmnibox() }),
                // No forced height. Squeezing Material's outlined field below its own single-line
                // height is what clipped the text; left alone, it keeps the line whole and centres
                // it, and it lands on the same pill as the Gallery search bar.
                modifier = Modifier.weight(1f),
            )
            IconButton(onClick = { activeWebView()?.reload() }) {
                Icon(Icons.Rounded.Refresh, "Muat ulang")
            }
            Box {
                IconButton(onClick = { menuOpen = true }) {
                    Icon(Icons.Rounded.MoreVert, contentDescription = "Menu lainnya")
                }
                DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                    DropdownMenuItem(
                        text = { Text("Mesin pencari: ${searchEngine.label}") },
                        onClick = { menuOpen = false; showEngineSheet = true },
                        leadingIcon = { Icon(Icons.Rounded.TravelExplore, null) },
                    )
                    DropdownMenuItem(
                        text = { Text(if (showFindBar) "Tutup cari di halaman" else "Cari di halaman") },
                        onClick = {
                            menuOpen = false
                            if (showFindBar) {
                                showFindBar = false
                                activeWebView()?.clearMatches()
                            } else showFindBar = true
                        },
                        leadingIcon = { Icon(Icons.Rounded.FindInPage, null) },
                    )
                    DropdownMenuItem(
                        text = { Text("Terjemahkan halaman") },
                        onClick = { menuOpen = false; showTranslateSheet = true },
                        leadingIcon = { Icon(Icons.Rounded.Translate, null) },
                    )
                    DropdownMenuItem(
                        text = {
                            Text(
                                if (desktopModes[activeTabId] == true) "Mode seluler"
                                else "Mode desktop"
                            )
                        },
                        onClick = {
                            menuOpen = false
                            val key = activeTabId ?: return@DropdownMenuItem
                            val next = !(desktopModes[key] == true)
                            desktopModes[key] = next
                            activeWebView()?.settings?.apply {
                                useWideViewPort = next
                                loadWithOverviewMode = next
                            }
                            activeWebView()?.reload()
                        },
                        leadingIcon = { Icon(Icons.Rounded.Public, null) },
                    )
                    DropdownMenuItem(
                        text = { Text("Panel Shields") },
                        onClick = { menuOpen = false; showShieldPanel = true },
                        leadingIcon = { Icon(Icons.Rounded.Shield, null) },
                    )
                    DropdownMenuItem(
                        text = { Text("Tab baru") },
                        onClick = { menuOpen = false; openNewTab() },
                        leadingIcon = { Icon(Icons.Rounded.Add, null) },
                    )
                    DropdownMenuItem(
                        text = { Text("Tutup semua tab & bersihkan data") },
                        onClick = { menuOpen = false; scope.launch { wipeBrowserData() } },
                        leadingIcon = { Icon(Icons.Rounded.DeleteSweep, null) },
                    )
                }
            }
        }

        if (loading) {
            LinearProgressIndicator(
                progress = { animatedProgress },
                modifier = Modifier.fillMaxWidth(),
            )
        }

        AnimatedVisibility(
            visible = showFindBar,
            enter = expandVertically(tween(200)) + fadeIn(tween(200)),
            exit = shrinkVertically(tween(180)) + fadeOut(tween(160)),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .background(MaterialTheme.colorScheme.surfaceVariant)
                    .padding(horizontal = 8.dp),
            ) {
                OutlinedTextField(
                    value = findQuery,
                    onValueChange = {
                        findQuery = it
                        activeWebView()?.findAllAsync(it)
                    },
                    placeholder = { Text("Temukan di halaman") },
                    singleLine = true,
                    modifier = Modifier.weight(1f),
                )
                Text("${BrowserSession.findMatches}", style = MaterialTheme.typography.bodySmall)
                IconButton(onClick = { activeWebView()?.findNext(false) }) {
                    Icon(Icons.Rounded.KeyboardArrowUp, contentDescription = "Hasil sebelumnya")
                }
                IconButton(onClick = { activeWebView()?.findNext(true) }) {
                    Icon(Icons.Rounded.KeyboardArrowDown, contentDescription = "Hasil berikutnya")
                }
                IconButton(onClick = { showFindBar = false }) { Icon(Icons.Rounded.Close, "Tutup") }
            }
        }

        Box(Modifier.fillMaxSize()) {
            AndroidView(
                factory = { ctx -> android.widget.FrameLayout(ctx) },
                update = { container ->
                    val id = activeTabId
                    if (id != null) {
                        wakeIfNeeded(id)
                        val target = webViews[id] ?: return@AndroidView
                        val parent = target.parent as? android.view.ViewGroup
                        if (parent !== container) {
                            parent?.removeView(target)
                            container.removeAllViews()
                            container.addView(
                                target,
                                android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                                android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                            )
                        }
                    }
                },
                modifier = Modifier.fillMaxSize(),
            )

            val loadFailure = activeTabId?.let { errorMap[it] }
            if (loadFailure != null) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(MaterialTheme.colorScheme.background)
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                            onClick = {},
                        )
                        .padding(horizontal = 32.dp),
                    verticalArrangement = Arrangement.Center,
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Icon(
                        Icons.Rounded.ErrorOutline,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.error,
                        modifier = Modifier.size(48.dp),
                    )
                    Spacer(Modifier.height(8.dp))
                    Text("Halaman gagal dimuat", style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(4.dp))
                    Text(
                        loadFailure,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                    )
                    Spacer(Modifier.height(8.dp))
                    TextButton(onClick = {
                        activeTabId?.let { errorMap.remove(it) }
                        activeWebView()?.reload()
                    }) { Text("Coba lagi") }
                }
            }
        }
    }
    }

    if (showTabSwitcher) {
        ModalBottomSheet(onDismissRequest = { showTabSwitcher = false }) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("Tab (${registry.size})", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.weight(1f))
                TextButton(onClick = {
                    showTabSwitcher = false
                    openNewTab()
                }) { Text("+ Baru") }
            }
            LazyColumn(modifier = Modifier.height(340.dp)) {
                items(tabsBumper.let { registry.all() }, key = { it.id }) { entry ->
                    Row(
                        modifier = Modifier
                            .animateItem()
                            .fillMaxWidth()
                            .clickable {
                                showTabSwitcher = false
                                switchTo(entry.id)
                            }
                            .padding(horizontal = 16.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Box(
                            Modifier
                                .size(34.dp)
                                .background(
                                    if (entry.id == activeTabId) MaterialTheme.colorScheme.primary
                                    else MaterialTheme.colorScheme.surfaceVariant,
                                    CircleShape,
                                ),
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(
                                Icons.Rounded.Public,
                                null,
                                tint = MaterialTheme.colorScheme.onSurface,
                                modifier = Modifier.size(18.dp),
                            )
                        }
                        Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                            Text(
                                entry.title.ifBlank { entry.url.ifBlank { "Tab kosong" } },
                                maxLines = 1,
                                style = MaterialTheme.typography.bodyMedium,
                            )
                            Text(
                                entry.url.substringAfter("//").substringBefore('/'),
                                maxLines = 1,
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                        IconButton(onClick = { closeTab(entry.id) }) {
                            Icon(Icons.Rounded.Close, "Tutup tab")
                        }
                    }
                }
            }
            Spacer(Modifier.height(24.dp))
        }
    }

    if (showEngineSheet) {
        ModalBottomSheet(onDismissRequest = { showEngineSheet = false }) {
            Text(
                "Mesin pencari",
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 6.dp),
            )
            SearchEngine.entries.forEach { engine ->
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable {
                            scope.launch { settingsRepo.setSearchEngine(engine) }
                            showEngineSheet = false
                        }
                        .padding(horizontal = 12.dp),
                ) {
                    RadioButton(
                        selected = engine == searchEngine,
                        onClick = {
                            scope.launch { settingsRepo.setSearchEngine(engine) }
                            showEngineSheet = false
                        },
                    )
                    Text(engine.label)
                }
            }
            Spacer(Modifier.height(24.dp))
        }
    }

    // Translate is opted into per page: the note says where the page goes before any language is
    // picked, so nothing leaves the phone unless the tap below is deliberate.
    if (showTranslateSheet) {
        ModalBottomSheet(onDismissRequest = { showTranslateSheet = false }) {
            Text(
                "Terjemahkan halaman",
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 6.dp),
            )
            Text(
                "Halaman ini akan dikirim ke layanan penerjemah (Google Translate). Selama kamu " +
                    "tidak memilih bahasa di bawah, tidak ada isi halaman yang keluar dari HP. " +
                    "Situs yang butuh login bisa gagal tampil.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 2.dp),
            )
            LazyColumn(modifier = Modifier.height(340.dp)) {
                items(TranslateLanguage.entries) { language ->
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                showTranslateSheet = false
                                scope.launch { settingsRepo.setTranslateTarget(language) }
                                translateActivePage(language)
                            }
                            .padding(horizontal = 12.dp),
                    ) {
                        RadioButton(
                            selected = language == translateTarget,
                            onClick = {
                                showTranslateSheet = false
                                scope.launch { settingsRepo.setTranslateTarget(language) }
                                translateActivePage(language)
                            },
                        )
                        Text(language.label)
                    }
                }
            }
            Spacer(Modifier.height(24.dp))
        }
    }

    if (BrowserSession.contextTarget != null) {
        val target = BrowserSession.contextTarget ?: WebContextTarget(WebContextTarget.Kind.LINK)
        val link = target.linkUrl
        val image = target.imageUrl
        ModalBottomSheet(onDismissRequest = { BrowserSession.contextTarget = null }) {
            Text(
                (link ?: image).orEmpty(),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp),
            )
            Spacer(Modifier.height(8.dp))
            val actions = when (target.kind) {
                WebContextTarget.Kind.LINK -> listOfNotNull(
                    link?.let { ContextAction("Buka", Icons.Rounded.OpenInBrowser) { navigateCurrent(it) } },
                    link?.let { ContextAction("Buka di tab baru", Icons.AutoMirrored.Rounded.OpenInNew) { openNewTab(it) } },
                    link?.let { ContextAction("Salin alamat link", Icons.Rounded.ContentCopy) { copyToClipboard(it) } },
                    link?.let { ContextAction("Bagikan link", Icons.Rounded.Share) { shareUrl(it) } },
                    link?.let { ContextAction("Unduh ke vault", Icons.Rounded.Download) { downloadToVault(it) } },
                )

                WebContextTarget.Kind.IMAGE -> listOfNotNull(
                    image?.let { ContextAction("Buka gambar", Icons.Rounded.OpenInBrowser) { navigateCurrent(it) } },
                    image?.let { ContextAction("Buka gambar di tab baru", Icons.AutoMirrored.Rounded.OpenInNew) { openNewTab(it) } },
                    image?.let { ContextAction("Simpan gambar ke vault", Icons.Rounded.Download) { downloadToVault(it) } },
                    image?.let { ContextAction("Salin alamat gambar", Icons.Rounded.ContentCopy) { copyToClipboard(it) } },
                    image?.let { ContextAction("Bagikan gambar", Icons.Rounded.Share) { shareUrl(it) } },
                )

                WebContextTarget.Kind.IMAGE_LINK -> listOfNotNull(
                    link?.let { ContextAction("Buka link", Icons.Rounded.OpenInBrowser) { navigateCurrent(it) } },
                    link?.let { ContextAction("Buka link di tab baru", Icons.AutoMirrored.Rounded.OpenInNew) { openNewTab(it) } },
                    image?.let { ContextAction("Buka gambar di tab baru", Icons.Rounded.Image) { openNewTab(it) } },
                    image?.let { ContextAction("Simpan gambar ke vault", Icons.Rounded.Download) { downloadToVault(it) } },
                    link?.let { ContextAction("Salin alamat link", Icons.Rounded.ContentCopy) { copyToClipboard(it) } },
                    link?.let { ContextAction("Bagikan link", Icons.Rounded.Share) { shareUrl(it) } },
                )
            }
            actions.forEach { action ->
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable {
                            BrowserSession.contextTarget = null
                            action.onClick()
                        }
                        .padding(horizontal = 20.dp, vertical = 12.dp),
                ) {
                    Icon(
                        action.icon,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.size(16.dp))
                    Text(action.label, style = MaterialTheme.typography.bodyMedium)
                }
            }
            Spacer(Modifier.height(24.dp))
        }
    }

    if (showShieldPanel) {
        AlertDialog(
            onDismissRequest = { showShieldPanel = false },
            title = { Text("Shields") },
            text = {
                Column {
                    Text(
                        if (activeTabId?.let{shieldsOn(it)} == true)
                            "$shieldBadge iklan/tracker/popup diblokir pada halaman ini."
                        else
                            "Shields MATI untuk tab ini."
                    )
                    Spacer(Modifier.height(8.dp))
                    TextButton(onClick = { toggleShields() }) {
                        Text(
                            if (activeTabId?.let{shieldsOn(it)} == true) "Matikan untuk tab ini"
                            else "Nyalakan untuk tab ini"
                        )
                    }
                    val curHost = activeTabId?.let { urlMap[it] }?.substringAfter("//")?.substringBefore('/')
                    if (!curHost.isNullOrBlank()) {
                        TextButton(onClick = {
                            hostWhitelist.add(curHost)
                            showShieldPanel = false
                            activeWebView()?.reload()
                        }) { Text("Izinkan iklan di \"$curHost\" (sesi ini)") }
                    }
                }
              },
            confirmButton = {},
            dismissButton = { TextButton(onClick = { showShieldPanel = false }) { Text("Tutup") } },
        )
    }

    if (showDisclaimer) {
        AlertDialog(
            onDismissRequest = { showDisclaimer = false },
            title = { Text("Browser privat") },
            text = {
                Text(
                    "Multi-tab aktif (maks $MAX_LIVE_TABS_LABEL tab hidup).\n" +
                        "Tab TETAP ADA walau Anda pindah ke Gallery. Bersihkan total " +
                        "lewat menu \u22ee \u2192 \"Tutup semua tab\".\n\n" +
                        "- TIDAK anonim: ISP & situs tetap bisa melihat aktivitas.\n" +
                        "- Hanya koneksi https.\n" +
                        "- Iklan/tracker/popup diblokir (badge 🛡️).\n" +
                        "- Unduhan langsung terenkripsi ke vault.",
                    style = MaterialTheme.typography.bodySmall,
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    disclaimerShownThisSession = true
                    showDisclaimer = false
                }) { Text("Mengerti") }
            },
        )
    }
}

private const val MAX_LIVE_TABS_LABEL = 6

