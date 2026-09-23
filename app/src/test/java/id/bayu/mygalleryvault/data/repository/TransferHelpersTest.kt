package id.bayu.mygalleryvault.data.repository

import id.bayu.mygalleryvault.domain.model.CancelledSignal
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

/**
 * Characterization tests: lock down existing behavior of the transfer helpers
 * across VaultRepository / TransferRepository / ThumbnailService so the
 * repository split can be verified as behavior-preserving.
 */
class TransferHelpersTest {

    // ---------- TransferRepository.safeExportName ----------

    @Test
    fun `safeExportName keeps a clean name untouched`() {
        assertEquals("holiday.mp4", TransferRepository.safeExportName("holiday.mp4"))
    }

    @Test
    fun `safeExportName replaces SAF-rejected characters with underscore`() {
        assertEquals("a_b_c_.jpg", TransferRepository.safeExportName("a/b:c*.jpg"))
        assertEquals("x__y.png", TransferRepository.safeExportName("x<>y.png"))
        assertEquals("q_w_e.rtf", TransferRepository.safeExportName("q\\w|e.rtf"))
    }

    @Test
    fun `safeExportName turns pure-symbol input into underscores, not the blank fallback`() {
        // Cleaning produces "___" which is NOT blank, so ifBlank never fires here.
        assertEquals("___", TransferRepository.safeExportName("///"))
    }

    @Test
    fun `safeExportName falls back to a file_ name only when input is empty`() {
        assertTrue(TransferRepository.safeExportName("").startsWith("file_"))
    }

    // ---------- VaultRepository.subtitleMime ----------

    @Test
    fun `subtitleMime maps every supported extension`() {
        assertEquals("application/x-subrip", VaultRepository.subtitleMime("a.srt"))
        assertEquals("text/vtt", VaultRepository.subtitleMime("a.vtt"))
        assertEquals("application/x-ssa", VaultRepository.subtitleMime("a.ass"))
        assertEquals("application/x-ssa", VaultRepository.subtitleMime("a.ssa"))
        assertEquals("application/ttml+xml", VaultRepository.subtitleMime("a.ttml"))
    }

    @Test
    fun `subtitleMime is case-insensitive on the extension`() {
        assertEquals("application/x-subrip", VaultRepository.subtitleMime("Movie.SRT"))
    }

    @Test
    fun `subtitleMime rejects non-subtitle files`() {
        assertNull(VaultRepository.subtitleMime("movie.mp4"))
        assertNull(VaultRepository.subtitleMime("noextension"))
        assertNull(VaultRepository.subtitleMime("archive.zip"))
    }

    // ---------- ProgressInputStream ----------

    @Test
    fun `ProgressInputStream passes bytes through and reports monotonically increasing progress`() {
        val payload = ByteArray(3_000) { (it % 251).toByte() }
        val emitted = mutableListOf<Pair<Long, Long>>()
        val stream = ProgressInputStream(ByteArrayInputStream(payload), payload.size.toLong(),
            { done, total -> emitted.add(done to total) }, cancelled = null)

        val out = ByteArrayOutputStream()
        val buf = ByteArray(512)
        while (true) {
            val n = stream.read(buf)
            if (n < 0) break
            out.write(buf, 0, n)
        }

        assertArrayEquals(payload, out.toByteArray())
        assertTrue("expected at least one progress emission", emitted.isNotEmpty())
        assertTrue("progress must never decrease", emitted.map { it.first }.zipWithNext().all { (a, b) -> a <= b })
        assertEquals(payload.size.toLong(), emitted.last().first)
        assertTrue(emitted.all { it.second == payload.size.toLong() })
    }

    @Test
    fun `ProgressInputStream throws CancelledSignal mid-read when cancel flag is set`() {
        val payload = ByteArray(4_096) { 1 }
        var reads = 0
        val stream = ProgressInputStream(ByteArrayInputStream(payload), payload.size.toLong(),
            { _, _ -> }, cancelled = { ++reads > 1 })

        val buf = ByteArray(256)
        stream.read(buf) // first read passes
        try {
            stream.read(buf) // flag flips to true -> must abort
            fail("expected CancelledSignal")
        } catch (expected: CancelledSignal) {
            // characterizes cooperative cancellation
        }
    }

    // ---------- CancelCheckingOutputStream ----------

    @Test
    fun `CancelCheckingOutputStream writes through while not cancelled`() {
        val sink = ByteArrayOutputStream()
        val stream = CancelCheckingOutputStream(sink, cancelled = { false })
        stream.write(byteArrayOf(1, 2, 3))
        stream.flush()
        assertArrayEquals(byteArrayOf(1, 2, 3), sink.toByteArray())
    }

    @Test
    fun `CancelCheckingOutputStream aborts as soon as cancelled`() {
        val sink = ByteArrayOutputStream()
        val stream = CancelCheckingOutputStream(sink, cancelled = { true })
        try {
            stream.write(9)
            fail("expected CancelledSignal")
        } catch (expected: CancelledSignal) {
            assertEquals("nothing must leak past a cancelled write", 0, sink.size())
        }
    }

    // ---------- ByteProgressPublisher ----------

    @Test
    fun `ByteProgressPublisher always emits the completion event`() {
        // Under unit tests SystemClock returns 0 (returnDefaultValues), so the
        // throttle window stays open for partial events - but completion
        // (done >= total) must always pass through, on device and here alike.
        val emitted = mutableListOf<Pair<Long, Long>>()
        val publisher = ByteProgressPublisher { done, total -> emitted.add(done to total) }
        publisher(100L, 100L)
        assertEquals(listOf(100L to 100L), emitted)
    }
}
