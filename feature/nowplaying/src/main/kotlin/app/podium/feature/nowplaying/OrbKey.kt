package app.podium.feature.nowplaying

import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import app.podium.core.designsystem.component.pressFeedback
import app.podium.core.designsystem.orb.LiveOrb
import app.podium.core.designsystem.orb.OrbFace
import app.podium.core.designsystem.orb.OrbFrame
import app.podium.core.designsystem.theme.PodiumTheme
import app.podium.core.interaction.rememberPodiumHaptics
import app.podium.core.model.QueueUid
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive

/**
 * Now Playing's play key: the live orb (D-107). It does what the play key did — Center on it, or a
 * tap, starts and stops the music, with the same firm click and soft fall (D-105) and the same
 * "Play" and "Pause" for anyone reading the screen — and it is alive while it does it.
 *
 * Everything the orb knows comes together here: the music as [OrbDrive] smooths it from
 * [AudioPulse][app.podium.player.api.AudioPulse], the Wheel's focus lens as a place to look
 * ([lensGaze]), the song changing as something to watch go by, and the finger on it. [OrbMind] turns
 * all of it into one [OrbFace] per frame and [LiveOrb] draws it.
 *
 * Frames are asked for only while the orb is moving. While the music plays it always is (so does the
 * cover's light behind it); stopped, it is still between one blink or fidget and the next, and the
 * loop sleeps until the next one is due — looking in every [StillPollMs] at the longest, so a finger
 * landing on it is felt at once. The loop starts again by itself whenever the music starts or stops,
 * the lens moves, the song changes or the orb is pressed, so none of those ever waits for a poll.
 */
@Composable
internal fun OrbKey(p: NowPlayingParts, diameter: Dp, modifier: Modifier = Modifier) {
    val haptics = rememberPodiumHaptics()
    val reduced = PodiumTheme.motion.reduced
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val face = remember { OrbFace() }
    val mind = remember { OrbMind() }
    val drive = remember { OrbDrive() }
    val frame = remember { OrbFrame() }
    val levels = remember { FloatArray(3) }
    val playing = p.playing
    val controller = p.controller
    val pulse = p.pulse
    // The lens is the one thing on the screen the orb looks at (D-103's focus lens, on the tray
    // below it); the rest of the time it looks where it likes.
    val look = if (p.mode == WheelMode.Actions) Offset(lensGaze(p.actionFocus, Action.entries.size), LensGaze) else null

    // A new song: the orb watches the covers travel, and the music's references start afresh.
    val seen = remember { arrayOfNulls<QueueUid>(1) }
    LaunchedEffect(p.item.uid) {
        val was = seen[0]
        seen[0] = p.item.uid
        if (was != null && was != p.item.uid) {
            drive.reset()
            if (!reduced) mind.watch(forward = !songChangeIsBack(was, p.item.indexInQueue, p.queueOrder))
        }
    }

    LaunchedEffect(playing, reduced, pulse, look, pressed, p.item.uid) {
        var last = -1L
        while (isActive) {
            val nanos = withFrameNanos { it }
            val dt = if (last < 0L) 0f else (nanos - last) / 1e6f
            last = nanos
            // Read a little ahead of the playhead, as the lyrics' swell does, so the beat lands with it.
            val measured = playing && pulse.levelsAt(controller.positionMs() + PulseFollower.LEAD_MS, levels)
            drive.update(if (measured) levels else null, dt, playing, frame)
            mind.update(dt, playing, frame.energy, frame.low, frame.high, frame.glint, look, pressed, reduced, face)
            if (mind.atRest) {
                delay(mind.millisUntilMove().coerceIn(MinWaitMs, StillPollMs))
            } else {
                // Still moving, but perhaps slowly enough that not every frame is worth drawing.
                val pace = mind.pacingMs()
                if (pace > 0L) delay(pace)
            }
        }
    }

    LiveOrb(
        diameter = diameter,
        settled = !playing,
        face = face,
        pressed = { pressed },
        modifier = modifier
            .pressFeedback(0.96f)
            .clickable(interactionSource = interaction, indication = null) {
                // Touching the orb keeps the tray's own clock running (D-108).
                p.touched()
                // Starting is a firm click, stopping a soft fall (D-105).
                haptics.playPause(playing = !playing)
                controller.togglePlayPause()
            }
            .semantics {
                role = Role.Button
                contentDescription = if (playing) "Pause" else "Play"
            },
    )
}

/**
 * Where the focus lens sits across the action tray, in the orb's own −1 to 1 reach: the middle of the
 * [focus]th of [count] slots. The tray is as wide as the orb's column and the orb is centred in it, so
 * the slot's place in the tray is the place to look, whatever the screen's shape.
 */
internal fun lensGaze(focus: Int, count: Int): Float =
    if (count <= 0) 0f else (((focus + 0.5f) / count) - 0.5f) * 2f

/** The tray is below the orb, so looking at the lens is looking down. */
internal const val LensGaze = 0.62f

/** Resting, the orb looks in this often at the longest, so a finger is felt at once. */
private const val StillPollMs = 400L
private const val MinWaitMs = 16L
