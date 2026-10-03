package app.podium

import app.podium.feature.settings.MusicFolderSettings
import app.podium.sources.api.Capability
import app.podium.sources.api.FolderSelection
import app.podium.sources.api.MusicFolder
import app.podium.sources.api.SourceRegistry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

/**
 * Music-folder settings for the library source that reads from storage (D-32), found by its
 * folder facet — never by which source it is. With no such usable source, there's nothing to choose.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class RegistryMusicFolders(private val registry: SourceRegistry, scope: CoroutineScope) : MusicFolderSettings {

    private val facet = registry.connectedSources
        .map { sources ->
            sources.firstOrNull { it.enabled && it.capabilities.isUsable(Capability.LIBRARY) && registry.get(it.sourceId)?.folders != null }
                ?.let { registry.get(it.sourceId)?.folders }
        }
        .distinctUntilChanged()
        .stateIn(scope, SharingStarted.Eagerly, null)

    override val folders: StateFlow<List<MusicFolder>?> = facet
        .flatMapLatest { f -> f?.folders()?.map<List<MusicFolder>, List<MusicFolder>?> { it } ?: flowOf(null) }
        .stateIn(scope, SharingStarted.Eagerly, null)

    override val selection: StateFlow<FolderSelection> = facet
        .flatMapLatest { f -> f?.selection ?: flowOf(FolderSelection.Everything) }
        .stateIn(scope, SharingStarted.Eagerly, FolderSelection.Everything)

    override fun select(selection: FolderSelection) {
        facet.value?.select(selection)
    }
}
