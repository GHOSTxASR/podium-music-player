package app.podium.sources.api

import app.podium.core.model.SourceId
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.update

/** A source as the rest of the app sees it: identity + state, no provider details. */
data class ConnectedSource(
    val descriptor: SourceDescriptor,
    val enabled: Boolean,
    val priority: Int,
    val capabilities: SourceCapabilities,
    val health: SourceHealth,
    val authenticationState: AuthState,
) {
    val sourceId: SourceId get() = descriptor.id
}

/**
 * Owns the connected sources: registration, user priority, enablement, and the combined view of
 * capabilities and health. Sources never reference each other; anything cross-source goes through
 * here (and through StreamResolver).
 */
class SourceRegistry(private val health: SourceHealthMonitor) {

    private data class Registration(val source: MusicSource, val enabled: Boolean)

    private val registrations = MutableStateFlow<Map<SourceId, Registration>>(emptyMap())

    /** User-configured order; ids not listed sort after listed ones, in registration order. */
    private val priority = MutableStateFlow<List<SourceId>>(emptyList())

    private val registrationOrder = MutableStateFlow<List<SourceId>>(emptyList())

    fun register(source: MusicSource, enabled: Boolean = true) {
        val id = source.descriptor.id
        require(registrations.value[id] == null) { "Source $id is already registered" }
        registrations.update { it + (id to Registration(source, enabled)) }
        registrationOrder.update { it + id }
    }

    fun unregister(id: SourceId) {
        registrations.update { it - id }
        registrationOrder.update { it - id }
        priority.update { it - id }
        health.reset(id)
    }

    fun get(id: SourceId): MusicSource? = registrations.value[id]?.source

    fun isEnabled(id: SourceId): Boolean = registrations.value[id]?.enabled == true

    fun setEnabled(id: SourceId, enabled: Boolean) {
        registrations.update { all ->
            val reg = all[id] ?: return@update all
            all + (id to reg.copy(enabled = enabled))
        }
    }

    /** Set the user's priority order (lower index = asked first). */
    fun setPriority(order: List<SourceId>) {
        priority.value = order.distinct()
    }

    /** Enabled sources in priority order. */
    fun ordered(): List<MusicSource> = orderedIds().mapNotNull { id ->
        registrations.value[id]?.takeIf { it.enabled }?.source
    }

    /** Enabled sources that currently have [capability] usable, in priority order. */
    fun withCapability(capability: Capability): List<MusicSource> =
        ordered().filter { it.capabilities.value.isUsable(capability) }

    fun capabilities(id: SourceId): StateFlow<SourceCapabilities>? = get(id)?.capabilities

    fun health(id: SourceId): SourceHealth =
        if (registrations.value[id]?.enabled == false) SourceHealth.Disabled else health.health(id)

    private fun orderedIds(): List<SourceId> {
        val registered = registrationOrder.value
        val preferred = priority.value.filter { it in registrations.value }
        return preferred + registered.filter { it !in preferred }
    }

    /** Live view of all registered sources, ordered by priority, including disabled ones. */
    @OptIn(ExperimentalCoroutinesApi::class)
    val connectedSources: Flow<List<ConnectedSource>> =
        combine(registrations, priority, registrationOrder) { regs, _, _ -> regs }
            .flatMapLatest { regs ->
                val ids = orderedIds()
                if (ids.isEmpty()) return@flatMapLatest flowOf(emptyList())
                val perSource = ids.mapIndexedNotNull { index, id ->
                    val reg = regs[id] ?: return@mapIndexedNotNull null
                    val auth = reg.source.auth?.state ?: MutableStateFlow(AuthState.NotRequired)
                    combine(reg.source.capabilities, auth, health.states) { caps, authState, _ ->
                        ConnectedSource(
                            descriptor = reg.source.descriptor,
                            enabled = reg.enabled,
                            priority = index,
                            capabilities = caps,
                            health = if (reg.enabled) health.health(id) else SourceHealth.Disabled,
                            authenticationState = authState,
                        )
                    }
                }
                combine(perSource) { it.toList() }
            }

    fun snapshotIds(): List<SourceId> = orderedIds()
}
