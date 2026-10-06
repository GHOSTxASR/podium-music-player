package app.podium.sources.api

import app.podium.core.model.SourceId

/**
 * What the listener chose about sources (D-35): which are on, and the order Podium prefers them in
 * when it picks one automatically. Only explicit choices are kept, so a source added in a later
 * version starts with its own default instead of an old snapshot.
 */
data class SourcePreferences(
    /** Explicit on/off choices; a source not listed keeps the default it was registered with. */
    val enabled: Map<SourceId, Boolean> = emptyMap(),
    /** Preferred order, most preferred first; unlisted sources follow in registration order. */
    val priority: List<SourceId> = emptyList(),
)

/** Where [SourcePreferences] survive restarts. The app stores them; tests keep them in memory. */
interface SourcePreferencesStore {
    fun load(): SourcePreferences
    fun save(preferences: SourcePreferences)
}

class InMemorySourcePreferencesStore(private var preferences: SourcePreferences = SourcePreferences()) : SourcePreferencesStore {
    override fun load(): SourcePreferences = preferences

    override fun save(preferences: SourcePreferences) {
        this.preferences = preferences
    }
}

/**
 * The one way source choices change (D-35): it applies stored preferences to the [SourceRegistry]
 * — the authoritative list everything else reads — and persists every change the listener makes.
 * Nothing else keeps its own list of sources, their order, or whether they're on.
 */
class SourceSettings(
    private val registry: SourceRegistry,
    private val store: SourcePreferencesStore,
) {
    private var current: SourcePreferences = store.load()

    /** Apply the stored choices to the sources registered so far. Call after registering sources. */
    @Synchronized
    fun apply() {
        current.enabled.forEach { (id, on) -> if (registry.get(id) != null) registry.setEnabled(id, on) }
        registry.setPriority(current.priority)
    }

    @Synchronized
    fun setEnabled(id: SourceId, enabled: Boolean) {
        if (registry.get(id) == null) return
        registry.setEnabled(id, enabled)
        current = current.copy(enabled = current.enabled + (id to enabled))
        store.save(current)
    }

    /**
     * Move [id] to position [toIndex] among [peers] (e.g. the online sources, as Settings lists them).
     * Sources outside [peers] keep their places: the peers are reordered within the slots they hold.
     */
    @Synchronized
    fun move(id: SourceId, toIndex: Int, peers: List<SourceId>) {
        val all = registry.snapshotIds()
        val known = peers.filter { it in all }.distinct()
        if (id !in known) return
        val reordered = (known - id).toMutableList().apply { add(toIndex.coerceIn(0, size), id) }
        val slots = all.withIndex().filter { it.value in known }.map { it.index }
        val order = all.toMutableList()
        slots.zip(reordered).forEach { (slot, source) -> order[slot] = source }
        registry.setPriority(order)
        current = current.copy(priority = order)
        store.save(current)
    }
}
