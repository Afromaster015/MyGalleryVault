package id.bayu.mygalleryvault.core.browser

import android.content.Context
import android.net.Uri
import id.bayu.mygalleryvault.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.net.URL
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import javax.net.ssl.HttpsURLConnection

/**
 * Brave-Shields style ad/tracker blocker for the private browser (PRD §38).
 *
 * Matching model: a set of root domains; a request host is blocked when it
 * equals the domain or is a subdomain of it. The built-in list is a
 * conservative starter curation of major ad/tracker ecosystems; users can
 * extend it by importing a hosts-format file which is persisted app-privately
 * and merged on every load.
 *
 * Per-tab blocked counters power the live badge on the shields button.
 */
object ShieldBlocker {

    @Volatile
    private var loaded = false

    private val domains = java.util.Collections.newSetFromMap(ConcurrentHashMap<String, Boolean>())

    /**
     * Substring URL rules (path patterns like /pagead/, /prebid). Built-in
     * conservative set + user-added rules persisted in the user blocklist
     * file (lines containing '/' are treated as path rules).
     */
    private val pathRules = java.util.Collections.newSetFromMap(ConcurrentHashMap<String, Boolean>())

    /** tabId -> blocked-request counter for the CURRENT page. */
    private val counters = ConcurrentHashMap<String, AtomicInteger>()

    /** Adds a persistent rule: host root when no '/', else URL-substring rule. */
    suspend fun addUserRule(context: Context, rawRule: String): Boolean {
        val rule = rawRule.trim().lowercase().removePrefix("https://").removePrefix("http://")
            .trimEnd('/')
        if (!rule.contains('/') && !HOST_REGEX.matches(rule)) return false
        val file = userBlocklistFile(context)
        file.parentFile?.mkdirs()
        file.appendText(rule + "\n")
        reload(context)
        return true
    }

    fun matchesPathRule(urlLowercase: String): Boolean {
        if (pathRules.isEmpty()) return false
        for (rule in pathRules) if (urlLowercase.contains(rule)) return true
        return false
    }

    fun countFor(tabId: String): Int = counters[tabId]?.get() ?: 0

    fun increment(tabId: String) {
        counters.computeIfAbsent(tabId) { AtomicInteger() }.incrementAndGet()
    }

    /** Called on every new page navigation so badges reflect the current page. */
    fun resetTab(tabId: String) {
        counters[tabId]?.set(0)
    }

    fun removeTab(tabId: String) {
        counters.remove(tabId)
    }

    fun resetAll() {
        counters.clear()
    }

    suspend fun ensureLoaded(context: Context) {
        if (loaded) return
        withContext(Dispatchers.IO) {
            synchronized(this@ShieldBlocker) {
                if (loaded) return@withContext
                loadBuiltin(context)
                loadUserList(context)
                loaded = true
            }
        }
    }

    /** Re-reads built-in + user lists (used right after an import). */
    suspend fun reload(context: Context) {
        synchronized(this@ShieldBlocker) {
            domains.clear()
            loaded = false
        }
        ensureLoaded(context)
    }

    fun userBlocklistFile(context: Context): File =
        File(File(context.filesDir, "browser").apply { mkdirs() }, "blocklist_user.txt")

    private fun loadBuiltin(context: Context) {
        runCatching {
            context.resources.openRawResource(R.raw.shield_hosts_builtin)
                .bufferedReader()
                .useLines { lines -> parseAndMerge(lines) }
        }
        // Built-in conservative path patterns.
        listOf(
            "/pagead/", "/pagead2", "/adservice/", "/adserver/", "/adframe.", "/popunder",
            "/prebid", "/prebid.js", "/gpt.js", "/pubfig/", "/adrequest", "/show_ad",
            "/show_ads", "/banner_ad", "/ad-delivery", "/ads.js", "/advert.",
            "/adsbygoogle", "/doubleclick", "/adx/", "/adserv", "/getads",
            "/300x250_", "/728x90_", "/468x60_", "/160x600_", "/interstitial.",
        ).forEach { pathRules.add(it) }
    }

    private fun loadUserList(context: Context) {
        val userFile = userBlocklistFile(context)
        runCatching {
            if (userFile.exists()) {
                userFile.bufferedReader()
                    .useLines { lines ->
                        for (rawLine in lines) {
                            val line = stripComment(rawLine).trim().lowercase()
                            if (line.isEmpty()) continue
                            when {
                                // AdGuard/EasyList host rule: ||domain^$options
                                line.startsWith("||") ->
                                    extractDomain(rawLine)?.let { domains.add(it) }

                                // URL-substring rule ("/pagead/") or plain domain.
                                else -> {
                                    val base = line.substringBefore('$').trim()
                                    if (base.contains('/')) pathRules.add(base)
                                    else extractDomain(rawLine)?.let { domains.add(it) }
                                }
                            }
                        }
                    }
            }
        }
    }

    /** Removes trailing comment (#...) and skips AdGuard '!' headers. */
    private fun stripComment(raw: String): String {
        val noHash = raw.substringBefore('#')
        return if (noHash.trimStart().startsWith("!")) "" else noHash
    }

    /**
     * Normalizes any supported syntax into a bare lowercase domain:
     * hosts ("0.0.0.0 ads.x.com"), plain domains, and AdGuard/EasyList
     * "||domain^" rules ($ options stripped, @@ exceptions ignored).
     * Returns null when the line yields no blockable domain.
     */
    fun extractDomain(rawLine: String): String? {
        var line = stripComment(rawLine).trim()
        if (line.isEmpty() || line.startsWith("@@")) return null
        line = line.substringBefore('$').trim()
        if (line.isEmpty()) return null
        if (line.startsWith("||")) {
            val d = line.removePrefix("||")
                .substringBefore('^').substringBefore('*')
                .substringBefore('/')
                .lowercase()
            return d.takeIf { HOST_REGEX.matches(it) }
        }
        val host = line.split(Regex("\\s+")).last().lowercase()
        return host.takeIf { HOST_REGEX.matches(it) }
    }

    /**
     * Parses hosts-format lines: `0.0.0.0 ads.example.com`, `::1 x`,
     * plain `example.com`, or AdGuard/EasyList `||example.com^`.
     * Comments (#, !), blanks and @@ exceptions are skipped.
     * Returns number of NEW entries added.
     */
    fun parseAndMerge(lines: Sequence<String>): Int {
        var added = 0
        for (rawLine in lines) {
            val host = extractDomain(rawLine) ?: continue
            if (domains.add(host)) added++
        }
        return added
    }

    /**
     * Downloads a remote hosts/blocklist over HTTPS and merges it into the
     * persistent user blocklist. Accepts hosts format, plain domains, and
     * AdGuard/EasyList "||domain^" rules. Returns number of NEW domains.
     */
    suspend fun importFromUrl(context: Context, rawUrl: String): Int {
        val url = rawUrl.trim()
        require(url.startsWith("https://", ignoreCase = true)) { "Hanya URL https" }
        val text = withContext(Dispatchers.IO) {
            var conn: HttpsURLConnection? = null
            try {
                conn = (URL(url).openConnection() as HttpsURLConnection).apply {
                    connectTimeout = 15_000
                    readTimeout = 30_000
                    instanceFollowRedirects = true
                    setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 10) SecureVaultShield/1.0")
                }
                val code = conn.responseCode
                if (code !in 200..299) throw IOException("HTTP $code")
                conn.inputStream.bufferedReader().use { it.readText() }
            } finally {
                conn?.disconnect()
            }
        }
        val added = parseAndMerge(text.lineSequence())
        if (added > 0 || text.isNotBlank()) {
            val file = userBlocklistFile(context)
            file.parentFile?.mkdirs()
            file.appendText(text.trimEnd() + "\n")
        }
        reload(context)
        return added
    }

    fun size(): Int = domains.size

    fun isBlocked(url: Uri): Boolean = isBlockedHost(url.host)

    fun isBlockedHost(hostRaw: String?): Boolean {
        if (hostRaw.isNullOrBlank()) return false
        val host = hostRaw.lowercase().substringBefore(':').removeSuffix(".")
        if (host.isEmpty()) return false
        if (domains.contains(host)) return true
        // any parent of the host listed as root => subdomain blocked
        var idx = host.indexOf('.')
        while (idx != -1) {
            if (domains.contains(host.substring(idx + 1))) return true
            idx = host.indexOf('.', idx + 1)
        }
        return false
    }

    private val HOST_REGEX = Regex("^[a-z0-9-]+(\\.[a-z0-9-]+)+$")
}
