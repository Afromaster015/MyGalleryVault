package id.bayu.mygalleryvault.core.browser

import id.bayu.mygalleryvault.domain.model.TranslateLanguage

/**
 * Addresses for the browser's "translate this page", which hands the page to the same translate
 * proxy Chrome uses.
 *
 * The proxy is a host rewrite: dots become dashes and a real dash doubles, so www.coca-cola.com
 * is served as www-coca--cola-com.translate.goog, and the _x_tr_* query marks name the languages.
 * Both directions of that rule live only here, and are unit tested: a host built wrongly does not
 * fail loudly, it just serves a page that cannot load.
 */
object TranslateUrl {

    /** Host suffix Google serves translated pages from. */
    private const val HOST_SUFFIX = ".translate.goog"

    /** Query marks the proxy appends; they are not part of the address being translated. */
    private const val MARK_PREFIX = "_x_tr_"

    /**
     * The address of [pageUrl] translated into [target], or null when there is nothing to
     * translate (which callers report instead of guessing).
     */
    fun proxyUrl(pageUrl: String, target: TranslateLanguage): String? {
        val uri = runCatching { java.net.URI(pageUrl) }.getOrNull() ?: return null
        if (!uri.scheme.equals("https", ignoreCase = true)) return null
        val host = uri.host ?: return null
        val proxiedHost = host.replace("-", "--").replace(".", "-") + HOST_SUFFIX
        val path = uri.rawPath?.takeIf { it.isNotEmpty() } ?: "/"
        val marks = "${MARK_PREFIX}sl=auto&${MARK_PREFIX}tl=${target.code}&${MARK_PREFIX}hl=${target.code}"
        val query = uri.rawQuery
        return "https://$proxiedHost$path?" + if (query.isNullOrBlank()) marks else "$query&$marks"
    }

    /**
     * The real site address behind a translated page, so translating again replaces the current
     * translation instead of pointing the proxy at a page that is already proxied. Undoes
     * [proxyUrl]: a doubled dash goes back to a dash first, then single dashes become dots.
     * Null when this is not a translated page.
     */
    fun originalUrl(url: String): String? {
        val uri = runCatching { java.net.URI(url) }.getOrNull() ?: return null
        val host = uri.host ?: return null
        if (!host.endsWith(HOST_SUFFIX)) return null
        val realHost = host.removeSuffix(HOST_SUFFIX)
            .replace("--", "\u0000")
            .replace("-", ".")
            .replace("\u0000", "-")
        val path = uri.rawPath?.takeIf { it.isNotEmpty() } ?: "/"
        val query = uri.rawQuery
            ?.split('&')
            ?.filterNot { it.startsWith(MARK_PREFIX) }
            ?.joinToString("&")
            ?.takeIf { it.isNotBlank() }
        return "https://$realHost$path" + if (query == null) "" else "?$query"
    }
}
