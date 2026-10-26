package app.podium.update

import android.content.Intent
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Test
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.security.MessageDigest
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Podium's update check (D-99): GitHub's newest release read, its notes in short, offered when newer,
 * ignored or put off as the listener says, downloaded, checked and handed to Android's installer.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class UpdatesTest {

    // --- Reading releases ------------------------------------------------------------------------

    @Test
    fun `versions compare by number, a leading v and a suffix aside`() {
        assertTrue(Version.of("v0.1.10")!! > Version.of("0.1.9")!!)
        assertTrue(Version.of("0.2")!! > Version.of("0.1.9")!!)
        assertEquals(0, Version.of("0.1.4")!!.compareTo(Version.of("v0.1.4-test")!!))
        assertEquals(0, Version.of("1.0")!!.compareTo(Version.of("1.0.0")!!))
        assertNull(Version.of("latest"))
    }

    @Test
    fun `the newest release is read with its app and checksum`() {
        val r = Releases.parse(release("v0.1.4"))!!
        assertEquals("0.1.4", r.version.toString())
        assertEquals("https://github.com/GHOSTxASR/podium-music-player/releases/tag/v0.1.4", r.pageUrl)
        assertEquals("https://github.com/GHOSTxASR/podium-music-player/releases/download/v0.1.4/podium.apk", r.apk!!.url)
        assertEquals(40_441_014L, r.apk!!.size)
        assertEquals(SHA, r.apk!!.sha256)
        assertNull(Releases.parse(release("v0.1.4", draft = true)), "a draft isn't a release")
        assertNull(Releases.parse(release("v0.1.4", prerelease = true)), "nor a pre-release")
        assertNull(Releases.parse("""{"message":"Not Found"}"""))
        assertNull(Releases.parse(release("v0.1.4", page = "https://evil.example/x")), "a page off GitHub isn't opened")
    }

    @Test
    fun `the notes in short - the summary, then each change's headline, what's new first`() {
        val short = ReleaseNotes.short(NOTES)
        assertEquals(
            listOf(
                "Podium 0.1.3 adds full screen, live radio, YouTube channel choice, sound effects and Apple Lossless on every phone, with a new icon.",
                "Full screen",
                "Choose your channel",
                "Live radio",
                "Sound effects",
            ),
            short,
        )
        // A bullet without a bold headline gives its first sentence.
        assertEquals(listOf("Lyrics no longer say Too many requests so often"), ReleaseNotes.short("### Fixed\n- Lyrics no longer say \"Too many requests\" so often: Podium waits.").map { it.replace("\"", "") })
    }

    @Test
    fun `only GitHub, only https`() {
        HttpsUpdateNet.check("https://api.github.com/repos/x/y/releases/latest")
        HttpsUpdateNet.check("https://release-assets.githubusercontent.com/a")
        listOf("http://github.com/a", "https://github.com.evil.example/a", "https://example.com/podium.apk").forEach { url ->
            assertTrue(runCatching { HttpsUpdateNet.check(url) }.isFailure, url)
        }
    }

    // --- The center --------------------------------------------------------------------------------

    private class Net(var answer: () -> String) : UpdateNet {
        var asked = 0
        var apk: ByteArray = APK
        var downloads = 0
        override suspend fun text(url: String): String {
            asked++
            return answer()
        }

        override suspend fun download(url: String, to: File, onProgress: (Long, Long?) -> Unit) {
            downloads++
            kotlinx.coroutines.delay(10)
            to.parentFile?.mkdirs()
            to.writeBytes(apk)
            onProgress(apk.size.toLong(), apk.size.toLong())
        }
    }

    private class Store : UpdateStore {
        override var automatic = true
        override var lastCheckedAt = 0L
        override var ignoredTag: String? = null
        override var remindAfter = 0L
        override var lastAnswer: String? = null
    }

    private class Installer : UpdateInstaller {
        var allowed = true
        var check = ApkCheck.UPDATE
        val installed = mutableListOf<File>()
        override fun allowed() = allowed
        override fun permissionPage(): Intent = throw UnsupportedOperationException()
        override fun inspect(file: File) = check
        override fun install(file: File) {
            installed += file
        }
    }

    private var clock = 1_000_000L
    private val installer = Installer()

    private fun TestScope.center(net: UpdateNet, checksByItself: Boolean = true, store: UpdateStore = Store()) = UpdateCenter(
        net = net, store = store, installer = installer, current = Version.of("0.1.3")!!,
        downloads = Files.createTempDirectory("podium-updates").toFile(),
        scope = TestScope(testScheduler), now = { clock },
        checksByItself = checksByItself, canInstallReleases = true,
    )

    @Test
    fun `a newer release is offered with its notes, an older or equal one isn't`() = runTest(StandardTestDispatcher()) {
        val c = center(Net { release("v0.1.4", sha = sha(APK)) })
        c.autoCheck()
        advanceUntilIdle()
        val offer = assertIs<UpdateState.Offer>(c.state.value)
        assertEquals("0.1.4", offer.release.version.toString())
        assertTrue(offer.canInstall)
        assertTrue(c.shown.value)

        val same = center(Net { release("v0.1.3") })
        same.autoCheck()
        advanceUntilIdle()
        assertEquals(UpdateState.Idle, same.state.value)
        assertFalse(same.shown.value)
    }

    @Test
    fun `checks by itself at most every six hours, quietly, and never in a test build`() = runTest(StandardTestDispatcher()) {
        val net = Net { release("v0.1.3") }
        val c = center(net)
        c.autoCheck(); advanceUntilIdle()
        clock += UpdateCenter.CHECK_EVERY_MS - 1
        c.autoCheck(); advanceUntilIdle()
        assertEquals(1, net.asked, "the last answer is used again within six hours")
        clock += 2
        c.autoCheck(); advanceUntilIdle()
        assertEquals(2, net.asked)

        val failing = Net { throw IOException("offline") }
        val quiet = center(failing).also { it.autoCheck(); advanceUntilIdle() }
        assertEquals(UpdateState.Idle, quiet.state.value, "a failed quiet check says nothing")

        val test = Net { release("v0.1.4") }
        center(test, checksByItself = false).autoCheck(); advanceUntilIdle()
        assertEquals(0, test.asked)

        val off = Net { release("v0.1.4") }
        center(off, store = Store().apply { automatic = false }).autoCheck(); advanceUntilIdle()
        assertEquals(0, off.asked, "turned off in Settings")
    }

    @Test
    fun `ignored, a version isn't offered again, but a newer one is, and not now waits a day`() = runTest(StandardTestDispatcher()) {
        var tag = "v0.1.4"
        val net = Net { release(tag) }
        val store = Store()
        val c = center(net, store = store)
        c.autoCheck(); advanceUntilIdle()
        c.ignore()
        assertEquals("v0.1.4", store.ignoredTag)
        assertEquals(UpdateState.Idle, c.state.value)
        clock += UpdateCenter.CHECK_EVERY_MS + 1
        c.autoCheck(); advanceUntilIdle()
        assertEquals(UpdateState.Idle, c.state.value, "the ignored version stays away")

        tag = "v0.1.5"
        clock += UpdateCenter.CHECK_EVERY_MS + 1
        c.autoCheck(); advanceUntilIdle()
        assertIs<UpdateState.Offer>(c.state.value)
        c.dismiss()
        clock += UpdateCenter.CHECK_EVERY_MS + 1
        c.autoCheck(); advanceUntilIdle()
        assertEquals(UpdateState.Idle, c.state.value, "not now: not for a day")
        clock += UpdateCenter.REMIND_AFTER_MS
        c.autoCheck(); advanceUntilIdle()
        assertIs<UpdateState.Offer>(c.state.value)
    }

    @Test
    fun `check now says what it found, and why it couldn't`() = runTest(StandardTestDispatcher()) {
        val up = center(Net { release("v0.1.3") })
        up.checkNow(); advanceUntilIdle()
        assertEquals(UpdateState.UpToDate(Version.of("0.1.3")!!), up.state.value)

        val limited = center(Net { throw HttpError(403) })
        limited.checkNow(); advanceUntilIdle()
        assertEquals(UpdateProblem.RATE_LIMITED, assertIs<UpdateState.Failed>(limited.state.value).problem)

        val offline = center(Net { throw IOException("offline") })
        offline.checkNow(); advanceUntilIdle()
        assertEquals(UpdateProblem.NO_CONNECTION, assertIs<UpdateState.Failed>(offline.state.value).problem)

        // A debug command checks as if this were older.
        val pretend = center(Net { release("v0.1.3") })
        pretend.checkNow(pretend = Version.of("0.1.0")); advanceUntilIdle()
        assertIs<UpdateState.Offer>(pretend.state.value)
    }

    @Test
    fun `installing - downloaded, its checksum matched, checked as this app, then to Android's installer`() = runTest(StandardTestDispatcher()) {
        val net = Net { release("v0.1.4", sha = sha(APK)) }
        val c = center(net)
        c.checkNow(); advanceUntilIdle()
        c.install(); advanceUntilIdle()
        assertIs<UpdateState.Installing>(c.state.value)
        assertEquals(APK.toList(), installer.installed.single().readBytes().toList())

        // Asked again (the listener cancelled Android's dialog): the file already there is used.
        c.installFinished(InstallOutcome.CANCELLED, null)
        assertIs<UpdateState.Offer>(c.state.value)
        c.install(); advanceUntilIdle()
        assertEquals(1, net.downloads)
        c.installFinished(InstallOutcome.FAILED, "INSTALL_FAILED_INSUFFICIENT_STORAGE")
        assertEquals("INSTALL_FAILED_INSUFFICIENT_STORAGE", assertIs<UpdateState.Failed>(c.state.value).detail)
    }

    @Test
    fun `a download that doesn't match, or isn't this app, is never handed over`() = runTest(StandardTestDispatcher()) {
        val wrong = Net { release("v0.1.4", sha = "0".repeat(64)) }
        val c = center(wrong)
        c.checkNow(); advanceUntilIdle()
        c.install(); advanceUntilIdle()
        assertEquals(UpdateProblem.CORRUPT, assertIs<UpdateState.Failed>(c.state.value).problem)

        installer.check = ApkCheck.OTHER_APP
        val other = center(Net { release("v0.1.4", sha = sha(APK)) })
        other.checkNow(); advanceUntilIdle()
        other.install(); advanceUntilIdle()
        assertEquals(UpdateProblem.OTHER_APP, assertIs<UpdateState.Failed>(other.state.value).problem)
        assertTrue(installer.installed.isEmpty())
    }

    @Test
    fun `Android must allow installs first, and the install carries on once it does`() = runTest(StandardTestDispatcher()) {
        installer.allowed = false
        val c = center(Net { release("v0.1.4", sha = sha(APK)) })
        c.checkNow(); advanceUntilIdle()
        c.install(); advanceUntilIdle()
        assertIs<UpdateState.NeedsPermission>(c.state.value)
        c.resumed(); advanceUntilIdle()
        assertIs<UpdateState.NeedsPermission>(c.state.value, "still not allowed")
        installer.allowed = true
        c.resumed(); advanceUntilIdle()
        assertIs<UpdateState.Installing>(c.state.value)
        assertEquals(1, installer.installed.size)
    }

    @Test
    fun `put away during the download, it carries on and comes back when there's something to say`() = runTest(StandardTestDispatcher()) {
        installer.allowed = false
        val c = center(Net { release("v0.1.4", sha = sha(APK)) })
        c.checkNow(); advanceUntilIdle()
        c.install()
        testScheduler.runCurrent()
        c.dismiss()
        assertFalse(c.shown.value)
        advanceUntilIdle()
        assertIs<UpdateState.NeedsPermission>(c.state.value)
        assertTrue(c.shown.value)
    }

    @Test
    fun `the notice offers install with the size only when it can`() = runTest(StandardTestDispatcher()) {
        val c = center(Net { release("v0.1.4", sha = sha(APK)) })
        c.checkNow(); advanceUntilIdle()
        val offer = assertIs<UpdateState.Offer>(c.state.value)
        val spec = noticeFor(offer, c, onOpenPage = {}, onPermission = {})
        assertEquals("Podium 0.1.4 is here", spec.title)
        assertEquals(listOf("Download and install (39 MB)", "Open on GitHub", "Ignore this version"), spec.actions.map { it.label })
        assertEquals("• Full screen", spec.lines[1])
        val githubOnly = noticeFor(offer.copy(canInstall = false), c, {}, {})
        assertEquals(listOf("Open on GitHub", "Ignore this version"), githubOnly.actions.map { it.label })
    }

    private companion object {
        val APK = "not really an apk".toByteArray()
        const val SHA = "8bf07d61c32a2c69b8b3bf226b43d6669423c8d194412c95055be6cfc977ee30"

        fun sha(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

        /** The v0.1.3 notes' opening, as published. */
        val NOTES = """
            Released 2026-10-19. Tag `v0.1.3`. The full list of changes is in
            [CHANGELOG.md](../../CHANGELOG.md); the decisions behind them are D-84 to D-90 in
            [decision-log.md](../decision-log.md).

            **Podium 0.1.3** adds full screen, live radio, YouTube channel choice, sound effects and Apple
            Lossless on every phone, with a new icon.

            ### What's new
            - **Full screen:** Settings ▸ Full screen hides Android's status and navigation bars. The title
              bar then shows the time, and the battery unless you turn it off.
            - **Choose your channel:** if your Google account has several YouTube channels, pick the one
              Podium uses in Settings ▸ YouTube Music ▸ Channel.
            - **Live radio:** Online ▸ Live radio has music stations streaming live now, by genre.
            - **Sound effects:** Settings ▸ Equaliser ▸ Effect has Car stereo, 3D hall and 8D.
            - **Apple Lossless (ALAC)** plays on every phone, even ones without their own ALAC decoder.

            ### Changed
            - A new app icon: the "P" of the Podium wordmark.

            ### Fixed
            - Lyrics no longer say "Too many requests" so often.
        """.trimIndent()

        fun release(tag: String, draft: Boolean = false, prerelease: Boolean = false, sha: String = SHA, page: String? = null): String {
            val html = page ?: "https://github.com/GHOSTxASR/podium-music-player/releases/tag/$tag"
            val body = kotlinx.serialization.json.JsonPrimitive(NOTES.replace("0.1.3", tag.removePrefix("v"))).toString()
            return """{"tag_name":"$tag","name":"Podium","draft":$draft,"prerelease":$prerelease,"html_url":"$html","body":$body,
                "assets":[{"name":"podium.apk","size":40441014,"content_type":"application/vnd.android.package-archive","digest":"sha256:$sha",
                "browser_download_url":"https://github.com/GHOSTxASR/podium-music-player/releases/download/$tag/podium.apk"}]}"""
        }
    }
}
