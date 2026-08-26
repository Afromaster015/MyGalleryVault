package id.bayu.mygalleryvault.ui.screens.browser

/**
 * Pure state machine for browser tabs (no Android types) so LRU hibernation
 * decisions are unit-testable. UI layer owns the actual WebView pool and asks
 * this registry who should be alive.
 *
 * MRU order is maintained for both focus tracking and hibernation victims:
 * when the live-cap is exceeded, the least-recently-used non-active tab is
 * hibernated first.
 */
class TabRegistry(private val maxLive: Int = 6) {

    data class Entry(val id: String, var title: String, var url: String)

    private var seq = 0
    private val entriesById = LinkedHashMap<String, Entry>()
    private val mru = ArrayDeque<String>() // front = most recent

    /** Tabs that currently own a live WebView instance. */
    val liveIds = LinkedHashSet<String>()

    val size: Int get() = entriesById.size
    fun entry(id: String): Entry? = entriesById[id]
    fun all(): List<Entry> = entriesById.values.toList()

    /** Creates a new tab, marks it most-recent & live; returns its id. */
    fun create(): String {
        val id = "tab_${++seq}"
        entriesById[id] = Entry(id, "", "")
        mru.addFirst(id)
        liveIds.add(id)
        return id
    }

    /**
     * Closes a tab. Returns the id that should become active next
     * (most recent remaining neighbour), or null when no tabs remain.
     */
    fun close(id: String): String? {
        val wasActiveIndexInMru = mru.indexOf(id)
        entriesById.remove(id)
        mru.remove(id)
        liveIds.remove(id)
        if (entriesById.isEmpty()) return null
        // Prefer the tab that sat just before (more recent than) it; else after.
        val fallback = if (wasActiveIndexInMru > 0) mru[wasActiveIndexInMru - 1] else mru.first()
        return fallback
    }

    fun setActive(id: String) {
        require(entriesById.containsKey(id)) { "unknown tab $id" }
        mru.remove(id)
        mru.addFirst(id)
        liveIds.add(id)
    }

    fun updateMeta(id: String, title: String?, url: String?) {
        entriesById[id]?.let {
            if (!title.isNullOrBlank()) it.title = title
            if (!url.isNullOrBlank()) it.url = url
        }
    }

    /**
     * After [activeId] is focused, returns ids to hibernate (LRU first) so at
     * most [maxLive] stay live, never including the active one.
     */
    fun hibernateCandidates(activeId: String): List<String> {
        val victims = mutableListOf<String>()
        if (liveIds.size <= maxLive) return victims
        val lruFirst = mru.asReversed()
        for (id in lruFirst) {
            if (liveIds.size - victims.size <= maxLive) break
            if (id == activeId || id !in liveIds) continue
            victims.add(id)
        }
        return victims
    }

    fun markHibernated(id: String) {
        liveIds.remove(id)
    }

    fun clear() {
        entriesById.clear()
        mru.clear()
        liveIds.clear()
        seq = 0
    }
}
