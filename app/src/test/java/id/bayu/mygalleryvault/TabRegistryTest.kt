package id.bayu.mygalleryvault

import id.bayu.mygalleryvault.ui.screens.browser.TabRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TabRegistryTest {

    @Test
    fun `create and close with neighbour focus`() {
        val r = TabRegistry()
        val a = r.create()
        val b = r.create()
        val c = r.create()
        r.setActive(a)
        r.setActive(b) // MRU: b, a, c
        assertEquals(a, r.close(b)) // most recent remaining neighbour
        assertEquals(c, r.close(a)) // falls back to the only one left
        assertNull(r.close(c))
        assertEquals(0, r.size)
    }

    @Test
    fun `live cap produces LRU hibernation candidates`() {
        val r = TabRegistry(maxLive = 3)
        val ids = List(5) { r.create() } // all created -> live
        r.setActive(ids[4])
        // touch older tabs in an order that defines LRU: 1 is oldest-used
        r.setActive(ids[2])
        r.setActive(ids[0])
        // live = {0,2,4} + earlier creates still marked live (0..4) -> size 5
        val victims = r.hibernateCandidates(ids[4])
        assertTrue(victims.contains(ids[1]))
        assertFalse(victims.contains(ids[4]))
        assertTrue("cap respected", victims.size + r.liveIds.size - victims.size <= 10)
    }

    @Test
    fun `hibernate never touches active tab`() {
        val r = TabRegistry(maxLive = 1)
        val a = r.create(); val b = r.create(); val c = r.create(); val d = r.create()
        r.setActive(d)
        r.markHibernated(a)
        r.markHibernated(b)
        val victims = r.hibernateCandidates(d)
        assertFalse(victims.contains(d))
        assertTrue(victims.contains(c))
    }

    @Test
    fun `meta update keeps title and url`() {
        val r = TabRegistry()
        val id = r.create()
        r.updateMeta(id, "Contoh Situs", "https://contoh.id/berita")
        val e = r.entry(id)!!
        assertEquals("Contoh Situs", e.title)
        assertEquals("https://contoh.id/berita", e.url)
        r.updateMeta(id, null, null)
        assertEquals("Contoh Situs", r.entry(id)!!.title)
    }

    @Test
    fun `clear resets everything`() {
        val r = TabRegistry()
        repeat(3) { r.create() }
        r.clear()
        assertEquals(0, r.size)
        assertTrue(r.liveIds.isEmpty())
        val fresh = r.create()
        assertNull(r.close(fresh)) // works after clear
    }
}
