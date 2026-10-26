package app.podium.update

import android.content.Context
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.File
import java.io.IOException

/** What the update notice shows (D-99); [Idle] shows nothing. */
internal sealed interface UpdateState {
    data object Idle : UpdateState

    /** A manual check under way (Settings ▸ Software update). */
    data object Checking : UpdateState

    /** A manual check found nothing newer. */
    data class UpToDate(val current: Version) : UpdateState

    /** A newer release: its notes in short, and whether Podium can install it itself. */
    data class Offer(val release: Release, val highlights: List<String>, val canInstall: Boolean) : UpdateState

    data class Downloading(val release: Release, val done: Long, val total: Long?) : UpdateState

    data class Verifying(val release: Release) : UpdateState

    /** Downloaded and checked; Android must let Podium install apps first. */
    data class NeedsPermission(val release: Release) : UpdateState

    /** Handed to Android's installer, which asks the listener to confirm. */
    data class Installing(val release: Release) : UpdateState

    data class Failed(val release: Release?, val problem: UpdateProblem, val detail: String? = null) : UpdateState
}

internal enum class UpdateProblem { NO_CONNECTION, RATE_LIMITED, NOTHING_TO_DOWNLOAD, DOWNLOAD, CORRUPT, OTHER_APP, NOT_NEWER, INSTALL }

/** What the update check keeps between runs: one small preferences file, nothing about the listener. */
internal interface UpdateStore {
    var automatic: Boolean
    var lastCheckedAt: Long
    var ignoredTag: String?
    var remindAfter: Long

    /** The last answer from GitHub, so a start within [UpdateCenter.CHECK_EVERY_MS] needn't ask again. */
    var lastAnswer: String?
}

internal class PrefsUpdateStore(context: Context) : UpdateStore {
    private val prefs = context.applicationContext.getSharedPreferences("podium-updates", Context.MODE_PRIVATE)
    override var automatic: Boolean
        get() = prefs.getBoolean("automatic", true)
        set(v) = prefs.edit().putBoolean("automatic", v).apply()
    override var lastCheckedAt: Long
        get() = prefs.getLong("last-checked", 0L)
        set(v) = prefs.edit().putLong("last-checked", v).apply()
    override var ignoredTag: String?
        get() = prefs.getString("ignored", null)
        set(v) = prefs.edit().putString("ignored", v).apply()
    override var remindAfter: Long
        get() = prefs.getLong("remind-after", 0L)
        set(v) = prefs.edit().putLong("remind-after", v).apply()
    override var lastAnswer: String?
        get() = prefs.getString("last-answer", null)
        set(v) = prefs.edit().putString("last-answer", v).apply()
}

/**
 * Podium's update check (D-99). When the display switches on (release builds, unless turned off),
 * it asks GitHub for the newest release, at most every [CHECK_EVERY_MS]. A newer one is offered in a
 * notice with its notes in short: download and install it right there, open its page on GitHub, or
 * ignore that version. Dismissed, the notice comes back after [REMIND_AFTER_MS].
 *
 * Installing: the release's `podium.apk` is downloaded into the app's cache, checked against the
 * SHA-256 GitHub gives for it, then checked to be this app, newer, signed by the same key, and only
 * then handed to Android's installer, which asks the listener to confirm.
 */
internal class UpdateCenter(
    private val net: UpdateNet,
    private val store: UpdateStore,
    private val installer: UpdateInstaller,
    private val current: Version,
    private val downloads: File,
    /** Off the main thread: downloads, checksums and handing over to the installer read and write files. */
    private val scope: CoroutineScope,
    private val now: () -> Long = System::currentTimeMillis,
    /** Checks by themselves (release builds); debug builds check only when asked. */
    private val checksByItself: Boolean,
    /** Whether a release's app can replace this one (the release package; never a debug build). */
    private val canInstallReleases: Boolean,
    private val feedUrl: String = LATEST_URL,
) {
    private val _state = MutableStateFlow<UpdateState>(UpdateState.Idle)
    val state: StateFlow<UpdateState> = _state.asStateFlow()

    /** Whether the notice is up; a download goes on behind it when it's put away. */
    private val _shown = MutableStateFlow(false)
    val shown: StateFlow<Boolean> = _shown.asStateFlow()

    private val _automatic = MutableStateFlow(store.automatic)
    val automatic: StateFlow<Boolean> = _automatic.asStateFlow()

    val version: Version get() = current

    /** Android's page where the listener lets Podium install apps. */
    fun permissionPage() = installer.permissionPage()

    private var job: Job? = null

    fun setAutomatic(on: Boolean) {
        store.automatic = on
        _automatic.value = on
    }

    /** The display switched on: a quiet check, when it's time. Problems stay quiet. */
    fun autoCheck() {
        if (!checksByItself || !store.automatic || job?.isActive == true || _state.value != UpdateState.Idle) return
        job = scope.launch {
            val release = try {
                val recent = store.lastAnswer?.takeIf { now() - store.lastCheckedAt < CHECK_EVERY_MS }
                Releases.parse(recent ?: fetch())
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                null
            } ?: return@launch
            if (release.version <= current || release.tag == store.ignoredTag || now() < store.remindAfter) return@launch
            offer(release)
        }
    }

    /** Settings ▸ Software update ▸ Check now: always asks, and says what it found. */
    fun checkNow(pretend: Version? = null, forceInstall: Boolean = false) {
        job?.cancel()
        _state.value = UpdateState.Checking
        _shown.value = true
        job = scope.launch {
            val release = try {
                Releases.parse(fetch())
            } catch (e: CancellationException) {
                throw e
            } catch (e: HttpError) {
                fail(null, if (e.code == 403 || e.code == 429) UpdateProblem.RATE_LIMITED else UpdateProblem.NO_CONNECTION)
                return@launch
            } catch (e: IOException) {
                fail(null, UpdateProblem.NO_CONNECTION)
                return@launch
            }
            val mine = pretend ?: current
            if (release == null || release.version <= mine) {
                _state.value = UpdateState.UpToDate(current)
            } else {
                offer(release, forceInstall)
            }
        }
    }

    private suspend fun fetch(): String {
        val text = net.text(feedUrl)
        store.lastCheckedAt = now()
        store.lastAnswer = text
        return text
    }

    private fun offer(release: Release, forceInstall: Boolean = false) {
        val install = (canInstallReleases || forceInstall) && release.apk != null
        _state.value = UpdateState.Offer(release, ReleaseNotes.short(release.notes), install)
        _shown.value = true
    }

    /** "Download and install". */
    fun install() {
        val release = (_state.value as? UpdateState.Offer)?.release ?: (_state.value as? UpdateState.Failed)?.release ?: return
        val apk = release.apk ?: return fail(release, UpdateProblem.NOTHING_TO_DOWNLOAD)
        job?.cancel()
        job = scope.launch {
            val file = File(downloads, "podium-${release.tag.filter { it.isLetterOrDigit() || it == '.' || it == '-' }}.apk")
            downloads.listFiles()?.filter { it != file }?.forEach { it.delete() }
            if (!(file.isFile && apk.sha256 != null && file.sha256() == apk.sha256)) {
                val part = File(downloads, file.name + ".part")
                _state.value = UpdateState.Downloading(release, 0, apk.size.takeIf { it > 0 })
                var shownAt = 0L
                try {
                    net.download(apk.url, part) { done, total ->
                        // A step a percent (or a megabyte), not every buffer.
                        val step = maxOf((total ?: apk.size).coerceAtLeast(1) / 100, 1L shl 20)
                        if (done - shownAt >= step || done == total) {
                            shownAt = done
                            _state.value = UpdateState.Downloading(release, done, total ?: apk.size.takeIf { it > 0 })
                        }
                    }
                } catch (e: CancellationException) {
                    part.delete()
                    throw e
                } catch (e: Exception) {
                    part.delete()
                    return@launch fail(release, UpdateProblem.DOWNLOAD)
                }
                file.delete()
                if (!part.renameTo(file)) return@launch fail(release, UpdateProblem.DOWNLOAD)
            }
            _state.value = UpdateState.Verifying(release)
            if (apk.sha256 != null && file.sha256() != apk.sha256) {
                file.delete()
                return@launch fail(release, UpdateProblem.CORRUPT)
            }
            handOver(release, file)
        }
    }

    /** Checked, then to Android's installer (or first, to Android's page that allows it). */
    private fun handOver(release: Release, file: File) {
        when (installer.inspect(file)) {
            ApkCheck.UPDATE -> Unit
            ApkCheck.OTHER_APP -> return fail(release, UpdateProblem.OTHER_APP)
            ApkCheck.NOT_NEWER -> return fail(release, UpdateProblem.NOT_NEWER)
            ApkCheck.UNREADABLE -> {
                file.delete()
                return fail(release, UpdateProblem.CORRUPT)
            }
        }
        pending = release to file
        if (!installer.allowed()) {
            _state.value = UpdateState.NeedsPermission(release)
            _shown.value = true
            return
        }
        _state.value = UpdateState.Installing(release)
        _shown.value = true
        try {
            installer.install(file)
        } catch (e: Exception) {
            fail(release, UpdateProblem.INSTALL)
        }
    }

    private var pending: Pair<Release, File>? = null

    /** Podium is back in front: if the listener has just allowed installs, carry on. */
    fun resumed() {
        val (release, file) = pending ?: return
        if (_state.value is UpdateState.NeedsPermission && installer.allowed()) {
            job = scope.launch { handOver(release, file) }
        }
    }

    /** What Android's installer said. Done means Android is replacing Podium. */
    fun installFinished(outcome: InstallOutcome, detail: String?) {
        val release = (_state.value as? UpdateState.Installing)?.release ?: pending?.first
        when (outcome) {
            InstallOutcome.DONE -> {
                _state.value = UpdateState.Idle
                _shown.value = false
            }
            InstallOutcome.CANCELLED -> release?.let { offer(it) }
            InstallOutcome.FAILED -> fail(release, UpdateProblem.INSTALL, detail)
        }
    }

    /** Debug builds: the install path from a file already on the phone (D-99 device test). */
    fun installFile(file: File, release: Release) {
        job?.cancel()
        job = scope.launch { handOver(release, file) }
    }

    /** "Ignore this version": not offered again; a newer one still is. */
    fun ignore() {
        (_state.value as? UpdateState.Offer)?.release?.let { store.ignoredTag = it.tag }
        close()
    }

    /** Menu, or "Not now": the notice goes. An offer comes back later; a download carries on. */
    fun dismiss() {
        when (_state.value) {
            is UpdateState.Downloading, is UpdateState.Verifying -> _shown.value = false
            is UpdateState.Offer -> {
                store.remindAfter = now() + REMIND_AFTER_MS
                close()
            }
            else -> close()
        }
    }

    /** "Cancel" while downloading. */
    fun cancel() {
        job?.cancel()
        (_state.value as? UpdateState.Downloading)?.release?.let { offer(it) } ?: close()
    }

    private fun close() {
        if (_state.value is UpdateState.Downloading || _state.value is UpdateState.Verifying) job?.cancel()
        _state.value = UpdateState.Idle
        _shown.value = false
    }

    private fun fail(release: Release?, problem: UpdateProblem, detail: String? = null) {
        _state.value = UpdateState.Failed(release, problem, detail)
        _shown.value = true
    }

    companion object {
        const val REPOSITORY = "GHOSTxASR/podium-music-player"
        const val LATEST_URL = "https://api.github.com/repos/$REPOSITORY/releases/latest"
        const val RELEASES_PAGE = "https://github.com/$REPOSITORY/releases/latest"

        /** At most one check this often (GitHub allows 60 anonymous requests an hour). */
        const val CHECK_EVERY_MS = 6 * 60 * 60_000L

        /** A dismissed offer comes back after this. */
        const val REMIND_AFTER_MS = 24 * 60 * 60_000L
    }
}
