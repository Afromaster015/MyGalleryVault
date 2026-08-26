package id.bayu.mygalleryvault

import id.bayu.mygalleryvault.core.browser.ShieldBlocker
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ShieldBlockerTest {

    private fun feed(vararg lines: String) {
        ShieldBlocker.parseAndMerge(lines.asSequence())
    }

    @Test
    fun `parses hosts format comments and plain domains`() {
        val added = ShieldBlocker.parseAndMerge(
            listOf(
                "# comment line",
                "",
                "0.0.0.0 ads.example.com",
                "::1 ipv6.example.org",
                "plain-domain.net",
                "  127.0.0.1   spaced.example.io  ",
                "not a domain!!", // invalid token filtered by regex on last token? 'domain!!' fails
            ).asSequence()
        )
        assertTrue(added >= 4)
    }

    @Test
    fun `exact host and subdomains are blocked`() {
        feed("doubleclick.net")
        assertTrue(ShieldBlocker.isBlockedHost("doubleclick.net"))
        assertTrue(ShieldBlocker.isBlockedHost("www.doubleclick.net"))
        assertTrue(ShieldBlocker.isBlockedHost("stats.g.doubleclick.net"))
        assertTrue(ShieldBlocker.isBlockedHost("DOUBLECLICK.NET"))
        assertFalse(ShieldBlocker.isBlockedHost("notdoubleclick.net"))
        assertFalse(ShieldBlocker.isBlockedHost("example.com"))
    }

    @Test
    fun `port suffix and trailing dot handled`() {
        feed("hotjar.com")
        assertTrue(ShieldBlocker.isBlockedHost("hotjar.com:443"))
        assertTrue(ShieldBlocker.isBlockedHost("api.hotjar.com.:8080"))
    }

    @Test
    fun `null blank or unknown hosts pass`() {
        feed("scorecardresearch.com")
        assertFalse(ShieldBlocker.isBlockedHost(null))
        assertFalse(ShieldBlocker.isBlockedHost(""))
        assertFalse(ShieldBlocker.isBlockedHost("wikipedia.org"))
    }

    @Test
    fun `import merge dedupes`() {
        val first = ShieldBlocker.parseAndMerge(listOf("dup.example").asSequence())
        val second = ShieldBlocker.parseAndMerge(listOf("dup.example", "fresh.example").asSequence())
        assertTrue(first >= 1)
        assertEquals(1, second)
    }
}
