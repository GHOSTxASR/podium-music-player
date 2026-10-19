package app.podium

import app.podium.core.common.Outcome
import app.podium.core.common.PodiumError
import app.podium.core.model.SourceId
import app.podium.feature.settings.AddSourceResult
import app.podium.feature.settings.CustomSourceOption
import app.podium.feature.settings.CustomSourceState
import app.podium.feature.settings.CustomSourceStatus
import app.podium.feature.settings.CustomSourcesSettings
import app.podium.sources.addon.AddonConfig
import app.podium.sources.addon.AddonManifest
import app.podium.sources.addon.AddonMusicSource
import app.podium.sources.addon.AddonSetting
import app.podium.sources.addon.Addons
import app.podium.sources.api.CredentialStore
import app.podium.sources.api.HealthOutcome
import app.podium.sources.api.MusicEnvironment
import app.podium.sources.api.MusicSource
import app.podium.sources.api.NetworkPolicy
import app.podium.sources.api.SetupProblem
import app.podium.sources.api.SourceHealth
import app.podium.sources.api.SourceHealthMonitor
import app.podium.sources.api.resolve.StreamProviders
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json

/**
 * The listener's custom sources (D-91): addons they added for their streams, kept in their order.
 *
 * - For the resolver ([StreamProviders]): the sources that are on, asked before a song's own source.
 *   They never enter the source registry — they aren't the online service and are never browsed.
 * - For Settings ([CustomSourcesSettings]): add, turn on or off, reorder, change a source's settings,
 *   check, remove.
 * - Kept sealed in the [CredentialStore]: an addon's address can carry the listener's token. The
 *   address is never shown (the host alone) and never logged.
 */
class CustomSources(
    private val credentials: CredentialStore,
    private val health: SourceHealthMonitor,
    private val scope: CoroutineScope,
    private val now: () -> Long = System::currentTimeMillis,
    private val io: CoroutineDispatcher = Dispatchers.IO,
    private val fetchManifest: suspend (String) -> Outcome<AddonManifest> = Addons::manifest,
    private val sourceFor: (AddonConfig) -> AddonMusicSource = { AddonMusicSource(it, now) },
) : StreamProviders, CustomSourcesSettings {

    private class Entry(val config: AddonConfig, val enabled: Boolean, val source: AddonMusicSource) {
        val id: SourceId get() = config.sourceId
    }

    private val entries = MutableStateFlow(load())
    private val checking = MutableStateFlow<Set<SourceId>>(emptySet())

    /** What the last check found wrong, by source; cleared by a good check. */
    private val problems = MutableStateFlow<Map<SourceId, String>>(emptyMap())
    private val writeLock = Mutex()

    // --- For the resolver ------------------------------------------------------------------------

    override fun ordered(environment: MusicEnvironment): List<MusicSource> =
        entries.value.filter { it.enabled && it.source.descriptor.environment == environment }.map { it.source }

    override fun get(id: SourceId): MusicSource? = entries.value.firstOrNull { it.enabled && it.id == id }?.source

    override val serving: Flow<Set<SourceId>> =
        entries.map { list -> list.filter { it.enabled }.mapTo(HashSet()) { it.id } }.distinctUntilChanged()

    // --- For Settings ----------------------------------------------------------------------------

    override val sources: StateFlow<List<CustomSourceState>> =
        combine(entries, health.states, checking, problems) { list, states, busy, wrong ->
            list.map { e -> state(e, states[e.id], e.id in busy, wrong[e.id]) }
        }.stateIn(scope, SharingStarted.Eagerly, entries.value.map { state(it, null, false, null) })

    private fun state(e: Entry, health: SourceHealth?, busy: Boolean, problem: String?): CustomSourceState {
        val manifest = e.config.manifest
        val settled = manifest.settle(e.config.settings)
        return CustomSourceState(
            id = e.id.value,
            name = manifest.name,
            host = Addons.host(e.config.base),
            enabled = e.enabled,
            status = when {
                busy -> CustomSourceStatus.Checking
                problem != null -> CustomSourceStatus.Problem(problem)
                else -> healthProblem(health)?.let(CustomSourceStatus::Problem) ?: CustomSourceStatus.Ready
            },
            options = manifest.settings.map { s -> option(s, settled[s.key]) },
        )
    }

    private fun option(s: AddonSetting, value: String?): CustomSourceOption = CustomSourceOption(
        key = s.key,
        label = s.label,
        kind = when (s.type) {
            AddonSetting.Type.SELECT -> CustomSourceOption.Kind.CHOICE
            AddonSetting.Type.TOGGLE -> CustomSourceOption.Kind.TOGGLE
            AddonSetting.Type.TEXT -> CustomSourceOption.Kind.TEXT
        },
        value = value.orEmpty(),
        valueLabel = s.describe(value).ifEmpty { "Not set" },
        choices = s.options.map { CustomSourceOption.Choice(it.label, it.value) },
    )

    override suspend fun add(address: String, allowLocalUnencrypted: Boolean): AddSourceResult {
        val base = when (val verdict = Addons.address(address)) {
            is NetworkPolicy.Verdict.Secure -> verdict.base
            is NetworkPolicy.Verdict.LocalCleartext -> if (allowLocalUnencrypted) verdict.base else return AddSourceResult.NeedsLocalConsent
            is NetworkPolicy.Verdict.Refused -> return AddSourceResult.Problem(addressProblem(verdict.problem))
        }
        val id = Addons.sourceIdFor(base)
        if (entries.value.any { it.id == id }) return AddSourceResult.Problem("That source is already in your list.")
        val manifest = when (val r = fetchManifest(base)) {
            is Outcome.Success -> r.value
            is Outcome.Failure -> return AddSourceResult.Problem(fetchProblem(r.error))
        }
        if (!manifest.canStream) return AddSourceResult.Problem("${manifest.name} doesn't offer streams, so it can't play songs.")
        if (!manifest.canSearch) return AddSourceResult.Problem("${manifest.name} can't search, so Podium can't find songs on it.")
        // Another add may have finished while this one waited for the source.
        if (entries.value.any { it.id == id }) return AddSourceResult.Problem("That source is already in your list.")
        val config = AddonConfig(id, base, manifest)
        entries.update { it + Entry(config, enabled = true, source = sourceFor(config)) }
        persist()
        return AddSourceResult.Added(id.value, manifest.name)
    }

    override fun setEnabled(id: String, enabled: Boolean) = change(id) { Entry(it.config, enabled, it.source) }

    override fun move(id: String, offset: Int) {
        entries.update { list ->
            val from = list.indexOfFirst { it.id.value == id }
            if (from < 0) return@update list
            val to = (from + offset).coerceIn(0, list.lastIndex)
            list.toMutableList().apply { add(to, removeAt(from)) }
        }
        persist()
    }

    override fun setOption(id: String, key: String, value: String) = change(id) { e ->
        val setting = e.config.manifest.settings.firstOrNull { it.key == key } ?: return@change e
        // Only a value the source declares; never its default in place of what was asked.
        val accepted = setting.accept(value)?.takeIf { it == value } ?: return@change e
        val config = e.config.copy(settings = e.config.settings + (key to accepted))
        // A source carries its settings: a new one asks with the new values from the next song.
        Entry(config, e.enabled, sourceFor(config))
    }

    override fun check(id: String) {
        val entry = entries.value.firstOrNull { it.id.value == id } ?: return
        if (!checking.add(entry.id)) return
        scope.launch {
            when (val r = fetchManifest(entry.config.base)) {
                is Outcome.Success -> {
                    val manifest = r.value
                    if (manifest.id != entry.config.manifest.id) {
                        problems.update { it + (entry.id to "This address now answers as a different source. Remove it and add it again.") }
                    } else {
                        health.record(entry.id, HealthOutcome.SUCCESS)
                        problems.update { it - entry.id }
                        // The source's name or settings may have changed.
                        change(id) { e ->
                            val config = e.config.copy(manifest = manifest)
                            Entry(config, e.enabled, sourceFor(config))
                        }
                    }
                }
                is Outcome.Failure -> {
                    outcomeOf(r.error)?.let { health.record(entry.id, it, (r.error as? PodiumError.RateLimited)?.retryAfterMillis) }
                    problems.update { it + (entry.id to fetchProblem(r.error)) }
                }
            }
            checking.update { it - entry.id }
        }
    }

    override fun remove(id: String) {
        val entry = entries.value.firstOrNull { it.id.value == id } ?: return
        entries.update { list -> list.filterNot { it.id == entry.id } }
        problems.update { it - entry.id }
        scope.launch(io) {
            writeLock.withLock { credentials.delete(entry.id) }
            persist()
        }
    }

    private inline fun change(id: String, crossinline transform: (Entry) -> Entry) {
        entries.update { list -> list.map { if (it.id.value == id) transform(it) else it } }
        persist()
    }

    private fun MutableStateFlow<Set<SourceId>>.add(id: SourceId): Boolean {
        var added = false
        update { set -> if (id in set) set else set + id.also { added = true } }
        return added
    }

    // --- Kept on the phone -----------------------------------------------------------------------

    private fun load(): List<Entry> {
        val order = credentials.read(INDEX)?.get(KEY_ORDER)?.split(',')?.filter { it.isNotBlank() }.orEmpty()
        return order.mapNotNull { key ->
            runCatching {
                val id = SourceId(key)
                val values = credentials.read(id) ?: return@mapNotNull null
                val base = values[KEY_BASE] ?: return@mapNotNull null
                val manifest = values[KEY_MANIFEST]?.let(Addons::readManifest) ?: return@mapNotNull null
                val settings = values[KEY_SETTINGS]?.let { Json.decodeFromString(SETTINGS, it) }.orEmpty()
                val config = AddonConfig(id, base, manifest, settings)
                Entry(config, values[KEY_ENABLED] != "false", sourceFor(config))
            }.getOrNull()
        }
    }

    /** Writes the list as it is now (off the main thread, one write at a time; the last one wins). */
    private fun persist() {
        scope.launch(io) {
            writeLock.withLock {
                val list = entries.value
                list.forEach { e ->
                    credentials.write(
                        e.id,
                        mapOf(
                            KEY_BASE to e.config.base,
                            KEY_MANIFEST to Addons.writeManifest(e.config.manifest),
                            KEY_ENABLED to e.enabled.toString(),
                            KEY_SETTINGS to Json.encodeToString(SETTINGS, e.config.settings),
                        ),
                    )
                }
                credentials.write(INDEX, mapOf(KEY_ORDER to list.joinToString(",") { it.id.value }))
            }
        }
    }

    // --- Words -----------------------------------------------------------------------------------

    private fun addressProblem(problem: SetupProblem): String = when (problem) {
        SetupProblem.MISSING_FIELD -> "Type or paste the source's address first."
        SetupProblem.INSECURE_ADDRESS -> "Podium uses unencrypted addresses only on your own network. Use the https address."
        else -> "That isn't a web address. Type or paste one that starts with https://."
    }

    private fun fetchProblem(error: PodiumError): String = when (error) {
        PodiumError.Offline, is PodiumError.Network -> "Couldn't reach that address. Check it and your connection, then try again."
        is PodiumError.NotFound -> "No source answered at that address. Check it and try again."
        is PodiumError.RateLimited -> "The source is busy. Try again in a minute."
        is PodiumError.AuthRequired -> "The source refused. Check the address, including any key in it."
        is PodiumError.Server -> "The source answered with an error${error.httpStatus?.let { " (HTTP $it)" }.orEmpty()}. Try again later."
        else -> "That address didn't answer like a source. Check it's the address the source gives."
    }

    private fun healthProblem(health: SourceHealth?): String? = when (health) {
        null, SourceHealth.Healthy -> null
        is SourceHealth.RateLimited -> "Asked too often. Songs play as usual, and Podium tries it again shortly."
        is SourceHealth.Unreachable, is SourceHealth.Probing -> "Not answering. Songs play as usual until it's back."
        is SourceHealth.Degraded -> "Answering with errors. Songs it can't play play as usual."
        is SourceHealth.AuthRejected, SourceHealth.Disabled -> "Refusing Podium. Check the address, then add it again."
    }

    private fun outcomeOf(error: PodiumError): HealthOutcome? = when (error) {
        PodiumError.Offline -> null
        is PodiumError.Network -> HealthOutcome.NETWORK_FAILURE
        is PodiumError.RateLimited -> HealthOutcome.RATE_LIMIT
        is PodiumError.Server, is PodiumError.AuthRequired -> HealthOutcome.SERVER_ERROR
        is PodiumError.NotFound -> HealthOutcome.MISS
        else -> HealthOutcome.UNKNOWN
    }

    private companion object {
        val INDEX = SourceId("custom-sources")
        val SETTINGS = MapSerializer(String.serializer(), String.serializer())
        const val KEY_ORDER = "order"
        const val KEY_BASE = "base"
        const val KEY_MANIFEST = "manifest"
        const val KEY_ENABLED = "enabled"
        const val KEY_SETTINGS = "settings"
    }
}
