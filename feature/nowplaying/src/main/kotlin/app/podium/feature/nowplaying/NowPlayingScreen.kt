package app.podium.feature.nowplaying

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.min
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.podium.core.common.PodiumError
import app.podium.core.designsystem.artwork.ArtworkImage
import app.podium.core.designsystem.artwork.BackgroundExtension
import app.podium.core.designsystem.artwork.artworkRadius
import app.podium.core.designsystem.artwork.rememberArtwork
import app.podium.core.designsystem.component.LocalOverlayHost
import app.podium.core.designsystem.component.LocalScreenInsets
import app.podium.core.designsystem.component.MenuAction
import app.podium.core.designsystem.component.MenuSpec
import app.podium.core.designsystem.component.MessageState
import app.podium.core.designsystem.component.ProgressBar
import app.podium.core.designsystem.component.formatDuration
import app.podium.core.designsystem.component.rememberProgressTick
import app.podium.core.designsystem.symbol.PodiumSymbol
import app.podium.core.designsystem.symbol.Symbol
import app.podium.core.designsystem.theme.PodiumTheme
import app.podium.core.designsystem.theme.Spacing
import app.podium.core.designsystem.type.PodiumText
import app.podium.core.interaction.InputTargetEffect
import app.podium.core.interaction.PodiumInput
import app.podium.core.interaction.WheelButton
import app.podium.core.interaction.WheelContext
import app.podium.core.interaction.rememberPodiumHaptics
import app.podium.core.model.QualityLabel
import app.podium.player.api.PlaybackController
import app.podium.player.api.PlaybackStatus
import app.podium.player.api.VolumeController
import app.podium.sources.api.ResolutionPath
import kotlinx.coroutines.delay
import kotlin.math.ceil

private enum class WheelMode { Volume, Scrub }

/**
 * Now Playing (design-system.md §6.7). The Wheel is volume; Center switches it to scrubbing
 * (auto-returns after 3 s idle). Only measured quality is labelled.
 */
@Composable
fun NowPlayingScreen(controller: PlaybackController, volume: VolumeController, onUpNext: () -> Unit) {
    val snapshot by controller.snapshot.collectAsStateWithLifecycle()
    val volumeState by volume.state.collectAsStateWithLifecycle()
    val insets = LocalScreenInsets.current
    val colors = PodiumTheme.colors
    val type = PodiumTheme.type
    val haptics = rememberPodiumHaptics()
    val overlay = LocalOverlayHost.current
    val motion = PodiumTheme.motion
    val item = snapshot.item

    var mode by remember { mutableStateOf(WheelMode.Volume) }
    var lastWheelAt by remember { mutableLongStateOf(0L) }
    var showVolume by remember { mutableStateOf(false) }
    var detentRemainder by remember { mutableIntStateOf(0) }
    var scrubTarget by remember { mutableStateOf<Long?>(null) }

    // Volume bar lingers 1.5 s after the last detent; scrub mode exits after 3 s idle.
    LaunchedEffect(lastWheelAt, mode) {
        if (mode == WheelMode.Scrub) {
            delay(3_000)
            mode = WheelMode.Volume
            scrubTarget = null
        } else if (showVolume) {
            delay(1_500)
            showVolume = false
        }
    }

    InputTargetEffect(if (mode == WheelMode.Scrub) WheelContext.SCRUB else WheelContext.VOLUME) { input ->
        when (input) {
            is PodiumInput.Rotate -> {
                lastWheelAt = System.nanoTime()
                if (mode == WheelMode.Volume) {
                    if (volumeState.isFixed) return@InputTargetEffect true
                    showVolume = true
                    // A full sweep of the volume range is about 1.25 rotations on any device (D-16).
                    val detentsPerStep = ceil(30.0 / volumeState.max.coerceAtLeast(1)).toInt().coerceAtLeast(1)
                    detentRemainder += input.detents
                    val steps = detentRemainder / detentsPerStep
                    detentRemainder -= steps * detentsPerStep
                    if (steps != 0) {
                        val before = volumeState.level
                        volume.step(steps)
                        if ((steps > 0 && before >= volumeState.max) || (steps < 0 && before <= 0)) haptics.boundary() else haptics.step()
                    }
                } else {
                    val duration = item?.durationMs ?: return@InputTargetEffect true
                    val stepMs = (duration / 100).coerceIn(1_000, 10_000)
                    val from = scrubTarget ?: controller.positionMs()
                    val target = (from + input.detents * stepMs).coerceIn(0, duration)
                    scrubTarget = target
                    controller.seekTo(target)
                    if (target == 0L || target == duration) haptics.boundary() else haptics.step()
                }
                true
            }
            is PodiumInput.Press -> when (input.button) {
                WheelButton.CENTER -> {
                    lastWheelAt = System.nanoTime()
                    mode = if (mode == WheelMode.Volume) WheelMode.Scrub else WheelMode.Volume
                    scrubTarget = null
                    showVolume = false
                    true
                }
                WheelButton.MENU -> if (mode == WheelMode.Scrub) {
                    mode = WheelMode.Volume
                    scrubTarget = null
                    true
                } else false
                else -> false
            }
            is PodiumInput.LongPress -> if (input.button == WheelButton.CENTER) {
                overlay.show(MenuSpec(item?.title, listOf(MenuAction("Up Next", onSelect = onUpNext))))
                true
            } else false
            is PodiumInput.Release -> false
        }
    }

    if (item == null) {
        Box(Modifier.fillMaxSize().padding(top = insets.top, bottom = insets.bottom), contentAlignment = Alignment.Center) {
            MessageState(PodiumSymbol.Note, "Nothing playing", "Choose a song from Music to start.")
        }
        return
    }

    Box(Modifier.fillMaxSize()) {
        // The artwork's colour extends, blurred, into the whole canvas behind the screen.
        BackgroundExtension(rememberArtwork(item.artworkUri, 96.dp), Modifier.fillMaxSize())
        Column(
            Modifier
                .fillMaxSize()
                .padding(top = insets.top + Spacing.s, bottom = insets.bottom)
                .padding(horizontal = Spacing.xxl),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            // The artwork takes whatever height the metadata leaves, so nothing ever sits under the Wheel.
            BoxWithConstraints(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                val artSize = min(maxWidth, maxHeight)
                ArtworkImage(
                    uri = item.artworkUri,
                    size = artSize,
                    fallbackText = item.albumTitle ?: item.title,
                    modifier = Modifier.shadow(32.dp, RoundedCornerShape(artworkRadius(artSize)), clip = false),
                )
            }
            Spacer(Modifier.height(Spacing.xl))
            PodiumText(item.title, type.nowPlayingTitle, colors.labelPrimary, maxLines = 2, textAlign = TextAlign.Center)
            PodiumText(item.artistDisplay, type.nowPlayingSubtitle, colors.labelSecondary, textAlign = TextAlign.Center)
            Spacer(Modifier.height(Spacing.l))

            val playing = snapshot.status == PlaybackStatus.Playing
            val duration = item.durationMs ?: 0L
            AnimatedContent(
                targetState = showVolume && mode == WheelMode.Volume,
                transitionSpec = { fadeIn(motion.fadeFast()) togetherWith fadeOut(motion.fadeFast()) },
                label = "progressOrVolume",
            ) { volumeVisible ->
                if (volumeVisible) {
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.semantics { contentDescription = "Volume" }) {
                        Symbol(PodiumSymbol.VolumeDown, colors.labelSecondary, size = 18.dp)
                        ProgressBar({ volumeState.fraction }, running = false, modifier = Modifier.weight(1f).padding(horizontal = Spacing.s))
                        Symbol(PodiumSymbol.VolumeUp, colors.labelSecondary, size = 18.dp)
                    }
                } else {
                    ProgressBar(
                        value = { if (duration > 0) (scrubTarget ?: controller.positionMs()).toFloat() / duration else 0f },
                        running = playing,
                        emphasized = mode == WheelMode.Scrub,
                    )
                }
            }
            val tick = rememberProgressTick(playing, hz = 4)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                @Suppress("UNUSED_EXPRESSION") tick
                val position = scrubTarget ?: controller.positionMs()
                BasicText(formatDuration(position) ?: "0:00", style = type.caption.copy(color = colors.labelSecondary))
                BasicText("${item.indexInQueue + 1} of ${item.queueSize}", style = type.caption.copy(color = colors.labelTertiary))
                BasicText(
                    "−" + (formatDuration((duration - position).coerceAtLeast(0)) ?: "0:00"),
                    style = type.caption.copy(color = colors.labelSecondary),
                )
            }
            Spacer(Modifier.height(Spacing.m))

            // Status line: errors explain themselves; otherwise the honest quality label. Then notes on
            // where the audio comes from. A fixed three-line slot, so the artwork never jumps.
            val status = snapshot.status
            val line = when {
                status is PlaybackStatus.Error -> errorCopy(status.error)
                mode == WheelMode.Scrub -> "Scrubbing. Press the center to finish."
                else -> QualityLabel.forNowPlaying(snapshot.quality)
            }
            val notes = buildList {
                if (snapshot.quality.codecMismatch) {
                    snapshot.quality.sourceClaimed?.let(QualityLabel::format)?.let { add("Source reported $it") }
                }
                if (item.resolutionPath != null && item.resolutionPath != ResolutionPath.OWN_SOURCE) {
                    item.servedByDisplayName?.let { add("Playing from $it") }
                }
            }
            val slot = with(LocalDensity.current) { (type.footnote.lineHeight * StatusLines).toDp() }
            Column(Modifier.height(slot), horizontalAlignment = Alignment.CenterHorizontally) {
                line?.let { PodiumText(it, type.footnote, if (status is PlaybackStatus.Error) colors.critical else colors.labelTertiary) }
                notes.take(StatusLines - 1).forEach { PodiumText(it, type.footnote, colors.labelTertiary) }
            }
            UpNextButton(onUpNext)
        }
    }
}

private const val StatusLines = 3

@Composable
private fun UpNextButton(onClick: () -> Unit) {
    Box(
        Modifier
            .size(44.dp)
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onClick)
            .semantics { contentDescription = "Up Next" },
        contentAlignment = Alignment.Center,
    ) {
        Symbol(PodiumSymbol.Queue, PodiumTheme.colors.labelSecondary, size = 24.dp, weight = 500)
    }
}

/** Error copy (design-system.md §11): what happened, what's next; never raw exception text. */
internal fun errorCopy(error: PodiumError): String = when (error) {
    is PodiumError.InvalidMedia, is PodiumError.UnsupportedFormat -> "This song can't be played. Skipping to the next one."
    is PodiumError.NotFound -> "This song isn't available right now. Skipping to the next one."
    is PodiumError.Network, PodiumError.Offline -> "Can't reach the music source. Check your connection."
    is PodiumError.AuthRequired -> "Sign in to this source again to keep listening."
    is PodiumError.PermissionRequired -> "Allow access to music on this phone to play this song."
    else -> "Something went wrong playing this song."
}
