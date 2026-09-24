package id.bayu.mygalleryvault.ui.screens.browser

import android.content.Context
import android.webkit.JavascriptInterface
import android.webkit.WebSettings
import android.webkit.WebView
import android.widget.Toast
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import id.bayu.mygalleryvault.R
import id.bayu.mygalleryvault.domain.model.TranslateLanguage
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.concurrent.Executors
import org.json.JSONArray
import org.json.JSONObject

/**
 * Translates the page a tab is showing **without moving it off its own address**.
 *
 * The app used to hand the whole page to Google's translate proxy by rewriting the address into
 * something like example-com.translate.goog. That works only while the proxy can fetch the page:
 * login-gated pages, pages behind a bot check and pages built entirely by script came up empty,
 * which is why translation failed on sites a normal browser translated fine. What Chrome and
 * Brave do instead - and what happens here now - is leave the address, the cookies and the
 * session alone and swap only the text on screen: [PageTranslator] asks the page's own JS
 * (res/raw/vault_translate.js) which words are on it, sends those words to the translate service
 * in batches, and hands the answers back to be written in place.
 *
 * What leaves the phone is the text of the page, and only after the user picks a language. The
 * page itself is never routed anywhere.
 */
class PageTranslator(private val context: Context, private var webView: WebView) {

    /** True while the page on screen is showing translated text. */
    var active by mutableStateOf(false)
        private set

    /** True while text is on its way to the translator; drives the progress line under the chrome. */
    var busy by mutableStateOf(false)
        private set

    /** Language chosen for this tab; null means the page is left in its own language. */
    private var language: TranslateLanguage? = null

    /** Language the page turned out to be written in, learnt from the first batch, so later
     *  batches are not re-guessed from a handful of words. */
    @Volatile
    private var source: String? = null

    /** Bumped on every page load and every start, so answers for a page that is gone are dropped. */
    @Volatile
    private var generation = 0

    /** Whether this run may tell the user how it went: true only when he asked for it himself. */
    private var announce = false

    /** Whether this run has said its one thing already. */
    private var spoken = false

    private val userAgent: String by lazy { WebSettings.getDefaultUserAgent(context) }

    private val bridge = Bridge()

    init {
        webView.addJavascriptInterface(bridge, BRIDGE_NAME)
    }

    /**
     * The tab was parked to save memory and now has a fresh WebView. The choice belongs to the
     * tab and not to that WebView, so it follows: parking a background tab must not silently
     * switch its translation off.
     */
    fun attach(target: WebView) {
        generation++
        busy = false
        spoken = false
        source = null
        webView = target
        target.addJavascriptInterface(bridge, BRIDGE_NAME)
    }

    /** Starts translating the current page into [target] because the user asked for it. */
    fun translate(target: TranslateLanguage) {
        language = target
        active = true
        run(target, announce = true)
    }

    /** Puts the page back the way the site wrote it and stops translating this tab. */
    fun stop() {
        language = null
        active = false
        busy = false
        generation++
        webView.evaluateJavascript("window.__vaultTr && window.__vaultTr.restore()", null)
    }

    /**
     * A tab that was asked to translate keeps translating the pages loaded in it, so reading on
     * through links does not mean choosing the language again. Silent: the user already knows.
     */
    fun onPageLoaded() {
        val target = language ?: return
        run(target, announce = false)
    }

    /** The previous page is being torn down: nothing that was asked of it is worth answering. */
    fun onPageStarted() {
        generation++
        busy = false
        spoken = false
        source = null
    }

    private fun run(target: TranslateLanguage, announce: Boolean) {
        generation++
        val gen = generation
        this.announce = announce
        busy = true
        spoken = false
        source = null
        webView.evaluateJavascript(script(), null)
        webView.evaluateJavascript("window.__vaultTr.start('${target.code}')", null)
        // A page that cannot run script at all - a PDF, say - never answers, and a bar that never
        // stops reads as broken. Whatever is still going after this much time is on its own.
        webView.postDelayed({ if (generation == gen) busy = false }, WATCHDOG_MS)
    }

    private fun script(): String = cachedScript ?: runCatching {
        context.resources.openRawResource(R.raw.vault_translate).bufferedReader().use { it.readText() }
    }.getOrNull().orEmpty().also { cachedScript = it }

    /** The one batch of strings the page is waiting for, or null when it cannot be had. */
    private fun fetch(texts: List<String>, target: String): List<String>? {
        val body = buildString {
            for (text in texts) {
                if (isNotEmpty()) append('&')
                append("q=")
                append(URLEncoder.encode(text, "UTF-8"))
            }
        }
        val address = "$ENDPOINT?client=$CLIENT&sl=${source ?: "auto"}&tl=$target"
        val answer = post(address, body) ?: return null
        val rows = TranslateReply.parse(answer)?.takeIf { it.size == texts.size } ?: return null
        if (source == null) source = TranslateReply.detectedSource(rows)
        return rows.map { it.text }
    }

    private fun post(address: String, body: String): String? = runCatching {
        val connection = URL(address).openConnection() as HttpURLConnection
        try {
            connection.requestMethod = "POST"
            connection.connectTimeout = CONNECT_TIMEOUT_MS
            connection.readTimeout = READ_TIMEOUT_MS
            connection.doOutput = true
            connection.setRequestProperty("Content-Type", "application/x-www-form-urlencoded; charset=utf-8")
            connection.setRequestProperty("User-Agent", userAgent)
            connection.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            if (connection.responseCode == HttpURLConnection.HTTP_OK) {
                connection.inputStream.bufferedReader().use { it.readText() }
            } else {
                null
            }
        } finally {
            connection.disconnect()
        }
    }.getOrNull()

    /**
     * The call the page makes for every batch, and the call it makes when a run is over. JS
     * interface calls arrive on the page's own thread, so the network work leaves immediately and
     * the answer comes back through evaluateJavascript on the main thread. Public on purpose: the
     * bridge reaches these methods by reflection, which is not allowed on a hidden class.
     */
    inner class Bridge {

        @JavascriptInterface
        fun request(payload: String) {
            val request = runCatching { JSONObject(payload) }.getOrNull()
            val id = request?.optInt("id") ?: 0
            val target = request?.optString("tl")?.takeIf { it.isNotBlank() }
            val array = request?.optJSONArray("q")
            val texts = array?.let { rows ->
                ArrayList<String>(rows.length()).also { list ->
                    for (index in 0 until rows.length()) list += rows.optString(index)
                }
            }
            if (!active || language == null) {
                // Every page gets this bridge, so a page that was never asked to translate gets
                // no work out of it either.
                answer(id, null)
                return
            }
            if (target == null || texts == null || texts.isEmpty() || texts.size > MAX_ITEMS) {
                // The page is waiting for an answer, so it gets one even when the request makes no
                // sense: staying silent would leave it waiting for a batch that never comes.
                answer(id, null)
                return
            }
            val gen = generation
            WORKER.execute {
                val translated = fetch(texts, target)
                if (gen != generation) return@execute
                answer(id, translated)
            }
        }

        @JavascriptInterface
        fun report(state: String, count: Int) {
            val speak = announce && !spoken
            spoken = true
            webView.post {
                busy = false
                if (!speak) return@post
                val message = when (state) {
                    "done" -> "Halaman ini sudah diterjemahkan"
                    "empty" -> "Tidak ada teks yang bisa diterjemahkan di halaman ini"
                    else -> "Terjemahan gagal. Periksa koneksi lalu coba lagi."
                }
                Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun answer(id: Int, translations: List<String>?) {
        if (id <= 0) return
        webView.post { webView.evaluateJavascript(reply(id, translations), null) }
    }

    private fun reply(id: Int, translations: List<String>?): String {
        // A JSON array is a JS array; the two separators JSON leaves alone must not reach the
        // page's script parser as line breaks.
        val argument = translations?.let {
            JSONArray(it).toString().replace("\u2028", "\\u2028").replace("\u2029", "\\u2029")
        } ?: "null"
        return "window.__vaultTr && window.__vaultTr.apply($id, $argument)"
    }

    private companion object {
        const val BRIDGE_NAME = "__vaultTrBridge"
        const val ENDPOINT = "https://translate.googleapis.com/translate_a/t"
        const val CLIENT = "dict-chrome-ex"
        const val MAX_ITEMS = 30
        const val CONNECT_TIMEOUT_MS = 8_000
        const val READ_TIMEOUT_MS = 15_000
        const val WATCHDOG_MS = 20_000L

        /** One queue for the whole app: batches are small and never worth parallel requests. */
        val WORKER = Executors.newSingleThreadExecutor()

        @Volatile
        var cachedScript: String? = null
    }
}

/**
 * The translator's answer, kept apart from the WebView so both rules below can be tested without
 * a device: one string of text and the language the service says it was written in.
 */
internal object TranslateReply {

    data class Row(val text: String, val source: String?)

    /**
     * Rows for the strings that were sent, in the order they were sent, or null when the answer
     * is not the shape this client expects - which is a failure the caller reports, not a page of
     * half-translated text.
     */
    fun parse(answer: String): List<Row>? {
        val array = runCatching { JSONArray(answer) }.getOrNull() ?: return null
        val rows = ArrayList<Row>(array.length())
        for (index in 0 until array.length()) {
            when (val row = array.opt(index)) {
                is JSONArray -> rows += Row(
                    row.optString(0),
                    row.optString(1).takeIf { it.isNotBlank() },
                )
                is String -> rows += Row(row, null)
                else -> return null
            }
        }
        return rows.takeIf { it.isNotEmpty() }
    }

    /**
     * The language the page is written in, taken from the languages the service named. Only
     * strings long enough to be sure about get a vote: "Home" is guessed at, a sentence is not.
     * Null when nothing was sure enough, which leaves the next batch to detect for itself.
     */
    fun detectedSource(rows: List<Row>): String? = rows
        .filter { it.text.length >= MIN_SAMPLE_CHARS }
        .mapNotNull { it.source }
        .groupingBy { it }
        .eachCount()
        .maxByOrNull { it.value }
        ?.key

    private const val MIN_SAMPLE_CHARS = 12
}
