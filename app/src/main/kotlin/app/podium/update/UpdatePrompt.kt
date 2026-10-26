package app.podium.update

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.podium.core.designsystem.component.GlassNotice
import app.podium.core.designsystem.component.MenuAction
import app.podium.core.designsystem.component.NoticeSpec

/**
 * The update notice (D-99): what [UpdateCenter] has to say, inside Podium's screen, driven by the
 * Wheel. Nothing shows while it's idle or put away.
 */
@Composable
internal fun UpdatePrompt(updates: UpdateCenter, panelPadding: PaddingValues) {
    val state by updates.state.collectAsStateWithLifecycle()
    val shown by updates.shown.collectAsStateWithLifecycle()
    if (!shown || state == UpdateState.Idle) return
    val context = LocalContext.current
    BackHandler { updates.dismiss() }
    GlassNotice(
        spec = noticeFor(state, updates, onOpenPage = { url -> open(context, url) }, onPermission = { openSettings(context, updates) }),
        onDismiss = updates::dismiss,
        panelPadding = panelPadding,
    )
}

/** Sizes as people read them: "39 MB", "850 KB". */
internal fun megabytes(bytes: Long): String =
    if (bytes >= 1024 * 1024) "${(bytes + 512 * 1024) / (1024 * 1024)} MB" else "${(bytes + 512) / 1024} KB"

internal fun noticeFor(
    state: UpdateState,
    updates: UpdateCenter,
    onOpenPage: (String) -> Unit,
    onPermission: () -> Unit,
): NoticeSpec {
    fun github(release: Release?) = MenuAction("Open on GitHub") {
        onOpenPage(release?.pageUrl ?: UpdateCenter.RELEASES_PAGE)
        updates.dismiss()
    }
    return when (state) {
        UpdateState.Idle, UpdateState.Checking -> NoticeSpec("Checking for updates", listOf("Asking GitHub for the newest Podium."))
        is UpdateState.UpToDate -> NoticeSpec(
            "Podium is up to date",
            listOf("You have the newest version, ${state.current}."),
            listOf(MenuAction("Done") { updates.dismiss() }),
        )
        is UpdateState.Offer -> NoticeSpec(
            title = "Podium ${state.release.version} is here",
            lines = state.highlights.mapIndexed { i, line -> if (i == 0 && line.startsWith("Podium")) line else "• $line" },
            actions = listOfNotNull(
                if (state.canInstall) {
                    val size = state.release.apk?.size?.takeIf { it > 0 }?.let { " (${megabytes(it)})" }.orEmpty()
                    MenuAction("Download and install$size") { updates.install() }
                } else {
                    null
                },
                github(state.release),
                MenuAction("Ignore this version") { updates.ignore() },
            ),
        )
        is UpdateState.Downloading -> NoticeSpec(
            title = "Downloading Podium ${state.release.version}",
            lines = listOf(
                state.total?.let { "${megabytes(state.done)} of ${megabytes(it)}" } ?: megabytes(state.done),
                "Keep listening: Menu puts this away, and Podium asks again when it's ready.",
            ),
            actions = listOf(MenuAction("Cancel") { updates.cancel() }),
            progress = state.total?.let { (state.done.toFloat() / it).coerceIn(0f, 1f) } ?: 0f,
        )
        is UpdateState.Verifying -> NoticeSpec(
            "Checking the download",
            listOf("Making sure it's the release, and signed like your Podium."),
            progress = 1f,
            dismissible = true,
        )
        is UpdateState.NeedsPermission -> NoticeSpec(
            title = "Allow Podium to install updates",
            lines = listOf("Android asks once. Turn on Allow from this source, then come back to Podium."),
            actions = listOf(MenuAction("Open Android settings") { onPermission() }, github(state.release)),
        )
        is UpdateState.Installing -> NoticeSpec(
            title = "Installing Podium ${state.release.version}",
            lines = listOf("Android asks you to confirm. Podium closes while it updates; open it again after."),
        )
        is UpdateState.Failed -> {
            val (title, line) = when (state.problem) {
                UpdateProblem.NO_CONNECTION -> "Couldn't check for updates" to "Podium couldn't reach GitHub. Check your connection and try again."
                UpdateProblem.RATE_LIMITED -> "Couldn't check for updates" to "GitHub asked for fewer checks from this network. Try again in an hour."
                UpdateProblem.NOTHING_TO_DOWNLOAD -> "Nothing to download here" to "This release has no app attached. Get it from its GitHub page."
                UpdateProblem.DOWNLOAD -> "The download stopped" to "It didn't finish. Check your connection and try again, or get it from GitHub."
                UpdateProblem.CORRUPT -> "The download doesn't match" to "It isn't the file the release lists, so it wasn't installed. Try again, or get it from GitHub."
                UpdateProblem.OTHER_APP -> "This update can't replace your Podium" to "It isn't signed like the Podium on your phone, so Android won't install it over it. Get it from GitHub instead."
                UpdateProblem.NOT_NEWER -> "Nothing newer to install" to "The download isn't newer than your Podium."
                UpdateProblem.INSTALL -> "The update didn't install" to (state.detail?.let { "Android said: ${it.take(120)}. " }.orEmpty() + "Try again, or get it from GitHub.")
            }
            val retry = when (state.problem) {
                UpdateProblem.NO_CONNECTION, UpdateProblem.RATE_LIMITED -> MenuAction("Try again") { updates.checkNow() }
                UpdateProblem.DOWNLOAD, UpdateProblem.CORRUPT, UpdateProblem.INSTALL -> MenuAction("Try again") { updates.install() }
                else -> null
            }
            NoticeSpec(title, listOf(line), listOfNotNull(retry, github(state.release), MenuAction("Close") { updates.dismiss() }))
        }
    }
}

private fun open(context: Context, url: String) {
    try {
        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    } catch (_: ActivityNotFoundException) {
        // No browser: nothing to open it in.
    }
}

private fun openSettings(context: Context, updates: UpdateCenter) {
    try {
        context.startActivity(updates.permissionPage().addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    } catch (_: ActivityNotFoundException) {
        // A phone without the page: the listener can still get the update from GitHub.
    }
}
