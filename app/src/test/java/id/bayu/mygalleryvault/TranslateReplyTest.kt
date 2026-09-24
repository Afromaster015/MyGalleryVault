package id.bayu.mygalleryvault

import id.bayu.mygalleryvault.ui.screens.browser.TranslateReply
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The answer shape the translate service returns, and the rule that decides which language the
 * page is written in. Both are pinned here: a change on the service's side would otherwise show
 * up only as a page that quietly stops translating.
 */
class TranslateReplyTest {

    @Test
    fun `reads one row per string sent, in the order they were sent`() {
        val rows = TranslateReply.parse("""[["Halo dunia","en"],["Selamat pagi","en"]]""")
        assertEquals(listOf("Halo dunia", "Selamat pagi"), rows?.map { it.text })
        assertEquals(listOf("en", "en"), rows?.map { it.source })
    }

    @Test
    fun `a row without a detected language still carries its text`() {
        val rows = TranslateReply.parse("""[["Halo"]]""")
        assertEquals(listOf("Halo"), rows?.map { it.text })
        assertNull(rows?.first()?.source)
    }

    @Test
    fun `an answer that is not a list of rows is refused whole`() {
        assertNull("error page", TranslateReply.parse("<html>We're sorry...</html>"))
        assertNull("empty answer", TranslateReply.parse("[]"))
        assertNull("half a row", TranslateReply.parse("""[["Halo"],42]"""))
    }

    @Test
    fun `page language comes from the strings long enough to be sure of`() {
        val rows = listOf(
            TranslateReply.Row("Home", "it"),
            TranslateReply.Row("The quick brown fox jumps over the lazy dog", "en"),
            TranslateReply.Row("Another sentence that is clearly English", "en"),
        )
        assertEquals("en", TranslateReply.detectedSource(rows))
    }

    @Test
    fun `nothing is pinned when every string was too short to tell`() {
        val rows = listOf(TranslateReply.Row("Home", "it"), TranslateReply.Row("Menu", "de"))
        assertNull(TranslateReply.detectedSource(rows))
    }
}
