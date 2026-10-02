package app.podium

import app.podium.core.model.Track
import app.podium.feature.library.LibraryRepository
import app.podium.feature.library.LibraryState
import app.podium.feature.library.SourceAction
import app.podium.player.api.TrackCatalog
import app.podium.sources.api.Capability
import app.podium.sources.api.SourceRegistry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.stateIn
import java.text.Collator

/**
 * The merged library: every enabled source whose LIBRARY capability is usable contributes its
 * tracks. Provider-neutral — it asks capabilities, never source types.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class RegistryLibraryRepository(
    private val registry: SourceRegistry,
    private val catalog: TrackCatalog,
    scope: CoroutineScope,
) : LibraryRepository {

    private val collator = Collator.getInstance().apply { strength = Collator.PRIMARY }

    override val songs: StateFlow<LibraryState> = registry.connectedSources
        .map { sources ->
            sources.filter { it.enabled && it.capabilities.isUsable(Capability.LIBRARY) }.map { it.sourceId }
        }
        .distinctUntilChanged()
        .flatMapLatest { ids ->
            val libraries = ids.mapNotNull { registry.get(it)?.library }
            if (libraries.isEmpty()) {
                flowOf(LibraryState.Ready(emptyList()))
            } else {
                combine(libraries.map { it.tracks() }) { parts ->
                    val merged: List<Track> = parts.flatMap { it }.sortedWith { a, b -> collator.compare(a.title, b.title) }
                    LibraryState.Ready(merged) as LibraryState
                }
            }
        }
        .onEach { (it as? LibraryState.Ready)?.let { ready -> catalog.remember(ready.tracks) } }
        .stateIn(scope, SharingStarted.Eagerly, LibraryState.Loading)

    override val pendingActions: StateFlow<List<SourceAction>> = registry.connectedSources
        .map { sources ->
            sources.filter { it.enabled }.mapNotNull { source ->
                val state = source.capabilities[Capability.LIBRARY]
                val action = state.action
                if (state.status.isActionable && action != null) {
                    SourceAction(source.descriptor.displayName, state.note ?: source.descriptor.displayName, action)
                } else null
            }
        }
        .stateIn(scope, SharingStarted.Eagerly, emptyList())
}
