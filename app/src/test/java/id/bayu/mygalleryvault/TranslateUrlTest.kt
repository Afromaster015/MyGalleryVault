package id.bayu.mygalleryvault

import id.bayu.mygalleryvault.core.browser.TranslateUrl
import id.bayu.mygalleryvault.domain.model.TranslateLanguage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The translate proxy host rule is easy to get subtly wrong and fails silently (a wrong host just
 * serves a page that cannot load), so both directions are pinned here.
 *
 * The dashed-host expectation is not invented: asking Google Translate to translate
 * https://www.coca-cola.com/us/en redirects to exactly this translate.goog address.
 */
class TranslateUrlTest {

    @Test
    fun `dashed host is mangled the way google does it`() {
        assertEquals(
            "https://www-coca--cola-com.translate.goog/us/en" +
                "?_x_tr_sl=auto&_x_tr_tl=id&_x_tr_hl=id",
            TranslateUrl.proxyUrl("https://www.coca-cola.com/us/en", TranslateLanguage.INDONESIAN),
        )
    }

    @Test
    fun `host without a path gets a root path`() {
        assertEquals(
            "https://example-com.translate.goog/?_x_tr_sl=auto&_x_tr_tl=en&_x_tr_hl=en",
            TranslateUrl.proxyUrl("https://example.com", TranslateLanguage.ENGLISH),
        )
    }

    @Test
    fun `existing query is kept ahead of the language marks`() {
        assertEquals(
            "https://example-com.translate.goog/search?q=abc&_x_tr_sl=auto&_x_tr_tl=ja&_x_tr_hl=ja",
            TranslateUrl.proxyUrl("https://example.com/search?q=abc", TranslateLanguage.JAPANESE),
        )
    }

    @Test
    fun `only https can be proxied`() {
        assertNull(TranslateUrl.proxyUrl("http://example.com/", TranslateLanguage.INDONESIAN))
        assertNull(TranslateUrl.proxyUrl("javascript:alert(1)", TranslateLanguage.INDONESIAN))
    }

    @Test
    fun `translated address reads back to the real site`() {
        assertEquals(
            "https://www.coca-cola.com/us/en",
            TranslateUrl.originalUrl(
                "https://www-coca--cola-com.translate.goog/us/en" +
                    "?_x_tr_sl=auto&_x_tr_tl=id&_x_tr_hl=no",
            ),
        )
    }

    @Test
    fun `reading back keeps the page's own query and drops the proxy marks`() {
        assertEquals(
            "https://example.com/search?q=abc",
            TranslateUrl.originalUrl(
                "https://example-com.translate.goog/search?q=abc" +
                    "&_x_tr_sl=auto&_x_tr_tl=ja&_x_tr_hl=ja",
            ),
        )
    }

    @Test
    fun `a normal site address has no original to read back`() {
        assertNull(TranslateUrl.originalUrl("https://example.com/us/en"))
        assertNull(TranslateUrl.originalUrl("https://example.com/"))
    }

    /** Translating a translated page must replace the translation, never nest one proxy in another. */
    @Test
    fun `proxy round trip returns the address it started from`() {
        val pages = listOf(
            "https://www.coca-cola.com/us/en",
            "https://example.com/",
            "https://id.wikipedia.org/wiki/Jakarta",
            "https://example.com/search?q=abc&lang=id",
        )
        for (page in pages) {
            val proxied = TranslateUrl.proxyUrl(page, TranslateLanguage.INDONESIAN)!!
            assertEquals(page, TranslateUrl.originalUrl(proxied))
        }
    }

    @Test
    fun `re-translating a translated page does not stack proxies`() {
        val once = TranslateUrl.proxyUrl("https://example.com/page", TranslateLanguage.INDONESIAN)!!
        val twice = TranslateUrl.proxyUrl(
            TranslateUrl.originalUrl(once)!!,
            TranslateLanguage.JAPANESE,
        )!!
        assertEquals("example-com.translate.goog", twice.substringAfter("//").substringBefore('/'))
    }
}
