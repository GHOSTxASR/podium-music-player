package app.podium.feature.nowplaying

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.podium.core.designsystem.artwork.rememberArtworkState
import app.podium.core.designsystem.component.ImmersiveHost
import app.podium.core.designsystem.component.formatDuration
import app.podium.core.designsystem.shell.rememberDeviceTilt
import app.podium.core.designsystem.theme.PodiumMotion
import app.podium.core.designsystem.theme.PodiumTheme
import app.podium.core.designsystem.theme.Spacing
import app.podium.core.designsystem.type.PodiumText
import app.podium.core.interaction.InputTargetEffect
import app.podium.core.interaction.PodiumInput
import app.podium.core.interaction.WheelAcceleration
import app.podium.core.interaction.WheelButton
import app.podium.core.interaction.WheelContext
import app.podium.core.interaction.rememberPodiumHaptics
import app.podium.core.model.MediaKind
import app.podium.core.model.QueueUid
import app.podium.player.api.AudioPulse
import app.podium.player.api.PlayIntent
import app.podium.player.api.PlaybackController
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random

/** What Now Playing and its vinyl share (D-104). */
@Stable
internal class VinylState {
    /** Where Now Playing's cover is, in the window: where the sleeve lifts from and returns to. */
    var coverBounds by mutableStateOf<Rect?>(null)

    /** While the vinyl holds the cover, Now Playing's own copy hides. */
    var holdsCover by mutableStateOf(false)

    var open by mutableStateOf(false)

    /** Counts the system's back presses while it's open (the shell asks; the stage decides). */
    var backPresses by mutableIntStateOf(0)
}

/** One record on the stage: the song it plays and its cover. */
private data class RecordSide(val uid: QueueUid, val artworkUri: String?, val title: String, val artist: String)

/**
 * Now Playing's vinyl (D-104). The cover lifts out of Now Playing into the middle of the display as
 * everything else goes out of focus; the record slides up out of it, the sleeve drops away below the
 * display, the record settles in the middle and the tonearm swings onto it, starting the music if it
 * was paused. The record turns at 33⅓ rpm while the music plays, its label a disc cut from the cover,
 * the room's light lying still across its grooves; a seek turns it forwards or back by as much music
 * as it skips. Below it a dot visualiser moves with the sound; at the foot, the song's name.
 *
 * The Wheel does four things here and nothing else: Menu goes back (out of seeking first), ⏮ and ⏭
 * change the song — the record rolls away and the next one rolls in, the other way for ⏮ — and Center
 * seeks (turn to seek; Center or Menu to stop). Going back runs it all the other way: the sleeve, with
 * the cover of whatever plays now, rises from below, takes the record back and returns to its place
 * in Now Playing. Reduced motion: it fades in and out, the record still.
 */
@Composable
internal fun VinylStage(controller: PlaybackController, pulse: AudioPulse, vinyl: VinylState, host: ImmersiveHost, onClosed: () -> Unit) {
    val snapshot by controller.snapshot.collectAsStateWithLifecycle()
    val queue by controller.queue.collectAsStateWithLifecycle()
    val item = snapshot.item
    val reduced = PodiumTheme.motion.reduced
    val haptics = rememberPodiumHaptics()
    val scope = rememberCoroutineScope()

    // The choreography: the way in and the way out as clocks (0–1, linear time), each part of the
    // stage a function of them; the tonearm, the spin, the visualiser and the words on their own.
    val enter = remember { Animatable(0f) }
    val leave = remember { Animatable(0f) }
    var entered by remember { mutableStateOf(false) }
    var leaving by remember { mutableStateOf(false) }
    var closeWhenIn by remember { mutableStateOf(false) }
    val arm = remember { Animatable(0f) }
    val spinSpeed = remember { Animatable(0f) }
    val pill = remember { Animatable(0f) }
    val words = remember { Animatable(0f) }
    val seekSpin = remember { Animatable(0f) }
    var seeking by remember { mutableStateOf(false) }
    var lastSeekAt by remember { mutableLongStateOf(0L) }

    // The record playing, and while the song changes the one rolling away.
    var current by remember { mutableStateOf(item?.toSide()) }
    var outgoing by remember { mutableStateOf<RecordSide?>(null) }
    val roll = remember { Animatable(1f) }
    var rollBack by remember { mutableStateOf(false) }

    // Moment to moment, read while drawing: the record's turn, the visualiser's time and loudness.
    val angle = remember { mutableFloatStateOf(0f) }
    val clock = remember { mutableFloatStateOf(0f) }
    val level = remember { mutableFloatStateOf(0f) }

    SideEffect { vinyl.holdsCover = true }

    fun close() {
        if (leaving) return
        if (!entered) {
            closeWhenIn = true
            return
        }
        leaving = true
        seeking = false
        scope.launch {
            if (reduced) {
                leave.animateTo(1f, tween(ReducedFadeMs))
            } else {
                launch { arm.animateTo(0f, tween(ArmUpMs, easing = PodiumMotion.Standard)) }
                launch { pill.animateTo(0f, tween(260)) }
                launch { words.animateTo(0f, tween(220)) }
                leave.animateTo(1f, tween(LeaveMs, easing = LinearEasing))
            }
            vinyl.holdsCover = false
            onClosed()
        }
    }

    // In: the clock runs; the tonearm, the visualiser and the words come in at their moments.
    LaunchedEffect(Unit) {
        if (reduced) {
            arm.snapTo(1f)
            pill.snapTo(1f)
            words.snapTo(1f)
            enter.animateTo(1f, tween(ReducedFadeMs))
        } else {
            val way = launch { enter.animateTo(1f, tween(EnterMs, easing = LinearEasing)) }
            launch {
                delay(PillAtMs)
                pill.animateTo(1f, tween(520, easing = PodiumMotion.Standard))
            }
            launch {
                delay(WordsAtMs)
                words.animateTo(1f, tween(420, easing = PodiumMotion.Standard))
            }
            delay(ArmAtMs)
            arm.animateTo(1f, tween(ArmDownMs, easing = PodiumMotion.Standard))
            way.join()
        }
        // The needle is down: felt as it lands (D-105), and the record plays.
        haptics.collision(NeedleLands)
        if (snapshot.intent != PlayIntent.PLAY) controller.play()
        entered = true
        if (closeWhenIn) close()
    }

    // How far out of focus the display behind is: in as the cover lifts, out as it lands.
    LaunchedEffect(Unit) {
        snapshotFlow { stageDepth(enter.value, leave.value, reduced) }.collect { host.depth = it }
    }

    // The system's back, as Menu.
    LaunchedEffect(vinyl.backPresses) {
        if (vinyl.backPresses == 0) return@LaunchedEffect
        if (seeking) seeking = false else close()
    }

    // The record turns while the needle is on it and the music plays, easing up and down.
    LaunchedEffect(reduced) {
        if (reduced) return@LaunchedEffect
        snapshotFlow { arm.value > 0.97f && snapshot.intent == PlayIntent.PLAY }
            .distinctUntilChanged()
            .collectLatest { on -> spinSpeed.animateTo(if (on) 1f else 0f, tween(if (on) SpinUpMs else SpinDownMs, easing = PodiumMotion.Standard)) }
    }

    // Measured only while the stage shows.
    DisposableEffect(pulse) {
        val listening = pulse.listen()
        onDispose { listening.close() }
    }

    // Every frame: the turn, seeks found in the song's position, and the sound.
    LaunchedEffect(Unit) {
        var last = withFrameNanos { it }
        var lastPosition = controller.positionMs()
        var lastUid = snapshot.item?.uid
        val levels = FloatArray(3)
        while (true) {
            withFrameNanos { now ->
                val dt = ((now - last) / 1e9f).coerceIn(0f, 0.1f)
                last = now
                clock.floatValue += dt
                if (!reduced) angle.floatValue = (angle.floatValue + RecordDegreesPerSecond * dt * spinSpeed.value) % 360f
                val position = controller.positionMs()
                val uid = snapshot.item?.uid
                if (uid != lastUid) {
                    lastUid = uid
                } else if (!reduced) {
                    // A jump the music didn't play through (a seek, a restart) turns the record by as much.
                    val expected = lastPosition + if (snapshot.intent == PlayIntent.PLAY) (dt * 1000f).toLong() else 0L
                    val jump = position - expected
                    if (abs(jump) > SeekJumpMs) {
                        scope.launch { seekSpin.animateTo(seekSpin.targetValue + jump * RecordDegreesPerMs, spring(dampingRatio = 1f, stiffness = 140f)) }
                    }
                }
                lastPosition = position
                // Loudness on a root curve: quiet passages still move, loud ones fill the capsule.
                level.floatValue = visualiserLevel(level.floatValue, pulse.levelsAt(position, levels), levels[AudioPulse.WHOLE])
            }
        }
    }

    // A new song: the record rolls away and the next rolls in (the other way for ⏮), the arm lifting
    // off and coming back down. Before the stage is in, or reduced, the label just changes.
    LaunchedEffect(item?.uid) {
        val now = item?.toSide() ?: return@LaunchedEffect
        val was = current
        if (was == null || was.uid == now.uid) {
            current = now
            return@LaunchedEffect
        }
        if (!entered || leaving || reduced) {
            current = now
            return@LaunchedEffect
        }
        rollBack = songChangeIsBack(was.uid, item.indexInQueue, queue.entries.map { it.uid })
        outgoing = was
        current = now
        launch { arm.animateTo(ArmLifted, tween(200, easing = PodiumMotion.Standard)) }
        roll.snapTo(0f)
        roll.animateTo(1f, tween(RollMs, easing = PodiumMotion.Spatial))
        outgoing = null
        arm.animateTo(1f, tween(320, easing = PodiumMotion.Standard))
        haptics.collision(NeedleLands * 0.7f)
    }

    // Seeking ends by itself after a while untouched, as on Now Playing.
    LaunchedEffect(seeking, lastSeekAt) {
        if (!seeking) return@LaunchedEffect
        delay(SeekIdleMs)
        seeking = false
    }

    val canSeek = snapshot.controls.seek && (item?.durationMs ?: 0L) > 0L && item?.kind != MediaKind.LIVE
    InputTargetEffect(if (seeking) WheelContext.SCRUB else WheelContext.OVERLAY) { input ->
        // Nothing reaches what's behind; until it's in, and while it leaves, only Menu counts.
        if (leaving) return@InputTargetEffect true
        when (input) {
            is PodiumInput.Rotate -> {
                if (seeking && entered) {
                    lastSeekAt = System.nanoTime()
                    val duration = snapshot.item?.durationMs
                    if (duration != null && duration > 0) {
                        val stepMs = (duration / 200).coerceIn(500, 3_000)
                        val delta = input.detents * stepMs * WheelAcceleration.seekMultiplier(input.velocity)
                        val target = (controller.positionMs() + delta).coerceIn(0, duration)
                        controller.seekTo(target)
                        if (target == 0L || target == duration) haptics.boundary()
                    }
                }
                true
            }
            is PodiumInput.Press -> {
                when (input.button) {
                    WheelButton.MENU -> if (seeking) seeking = false else close()
                    WheelButton.CENTER -> if (entered && canSeek) {
                        seeking = !seeking
                        lastSeekAt = System.nanoTime()
                        haptics.toggle(on = seeking)
                    } else {
                        haptics.reject()
                    }
                    WheelButton.NEXT -> if (entered) controller.next()
                    WheelButton.PREVIOUS -> if (entered) controller.previous()
                    // Everything else stays still here (D-104).
                    else -> haptics.reject()
                }
                true
            }
            is PodiumInput.LongPress -> true
            is PodiumInput.Release -> true
        }
    }

    val density = LocalDensity.current
    var origin by remember { mutableStateOf(Offset.Zero) }
    val tilt = rememberDeviceTilt(enabled = !reduced)
    val title = current?.title.orEmpty()
    val artist = current?.artist.orEmpty()
    BoxWithConstraints(
        Modifier
            .fillMaxSize()
            .onGloballyPositioned { origin = it.positionInRoot() }
            // Every touch stops here: nothing behind it answers while the vinyl shows.
            .pointerInput(Unit) {
                awaitPointerEventScope {
                    while (true) awaitPointerEvent().changes.forEach { it.consume() }
                }
            }
            .semantics {
                contentDescription = "Vinyl. $title, $artist"
                customActions = listOf(CustomAccessibilityAction("Back to Now Playing") { close(); true })
            },
    ) {
        val geometry = remember(constraints.maxWidth, constraints.maxHeight, density) {
            StageGeometry(constraints.maxWidth.toFloat(), constraints.maxHeight.toFloat(), density)
        }
        // The records, rendered once per cover and size, kept by song so the one rolling away keeps its face.
        val records = remember { mutableStateMapOf<QueueUid, ImageBitmap>() }
        val currentCover = rememberArtworkState(current?.artworkUri, CoverSize).bitmap
        val currentRecord by rememberRecord(currentCover, geometry.recordPx)
        current?.uid?.let { uid -> currentRecord?.let { SideEffect { records[uid] = it } } }
        SideEffect { records.keys.retainAll(setOfNotNull(current?.uid, outgoing?.uid)) }
        Canvas(Modifier.fillMaxSize()) {
            val e = enter.value * EnterMs / 1000f
            val u = leave.value * LeaveMs / 1000f
            val depth = stageDepth(enter.value, leave.value, reduced)
            drawRect(Color.Black.copy(alpha = ScrimAlpha * depth))
            // Where the sleeve starts from and returns to: Now Playing's cover, in this stage.
            val home = vinyl.coverBounds?.translate(-origin) ?: geometry.sleeveRect
            val fade = if (reduced) (if (leaving) 1f - leave.value else enter.value) else 1f
            val placed = placeRecord(geometry, e, u, entered, leaving, reduced)
            // The record rolling away, then the one playing; each turns with the music and by its seeks.
            val turn = angle.floatValue + seekSpin.value
            val r = roll.value
            val travel = geometry.width / 2f + geometry.recordR + 24.dp.toPx()
            val away = if (rollBack) 1f else -1f
            outgoing?.let { side ->
                val dx = away * r * travel
                records[side.uid]?.let { drawRecord(it, geometry.center + Offset(dx, 0f), geometry.recordR, turn + rollDegrees(dx, geometry.recordR), tilt.value, fade) }
            }
            val record = currentRecord ?: current?.uid?.let { records[it] }
            if (record != null && placed.visible) {
                val dx = if (outgoing != null) -away * (1f - r) * travel else 0f
                drawRecord(record, placed.center + Offset(dx, 0f), placed.radius, turn + rollDegrees(dx, geometry.recordR), tilt.value, fade)
            }
            // The sleeve: lifting in from the cover's place, dropping away; rising again to take the record back.
            placeSleeve(geometry, home, e, u, entered, leaving, reduced)?.let { (rect, corner) ->
                drawSleeve(currentCover, rect, corner)
            }
            // The tonearm arrives with the record settling and leaves before the sleeve takes it home.
            val armPresence = when {
                reduced -> fade
                leaving -> 1f - easeInOut(segment(u, 0.35f, 0.35f))
                entered -> 1f
                else -> easeOut(segment(e, 1.0f, 0.45f))
            }
            drawTonearm(geometry, arm.value, armPresence)
            drawVisualiser(geometry, pill.value * fade, level.floatValue * ((pill.value - 0.55f) / 0.45f).coerceIn(0f, 1f), clock.floatValue)
        }
        // The song, minimal, at the foot; the times beside the visualiser while seeking.
        val seekShown by animateSeekPresence(seeking)
        val positionText by produceState("" to "", seeking, item?.uid) {
            while (true) {
                val position = controller.positionMs()
                val duration = item?.durationMs ?: 0L
                value = (formatDuration(position) ?: "0:00") to ("−" + (formatDuration((duration - position).coerceAtLeast(0)) ?: "0:00"))
                if (!seeking) break
                delay(120)
            }
        }
        with(density) {
            Row(
                Modifier
                    .offset { IntOffset(((geometry.width - geometry.pillWidth) / 2f).roundToInt(), (geometry.pillCenter.y + geometry.pillHeight / 2f + 6.dp.toPx()).roundToInt()) }
                    .width(geometry.pillWidth.toDp())
                    .graphicsLayer { alpha = seekShown },
            ) {
                PodiumText(positionText.first, PodiumTheme.type.caption, Color.White.copy(alpha = 0.72f))
                Spacer(Modifier.weight(1f))
                PodiumText(positionText.second, PodiumTheme.type.caption, Color.White.copy(alpha = 0.72f))
            }
        }
        AnimatedContent(
            targetState = current?.uid to (title to artist),
            contentKey = { it.first },
            transitionSpec = { fadeIn(tween(320, delayMillis = 120)) togetherWith fadeOut(tween(200)) },
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .padding(horizontal = Spacing.edge * 3)
                .padding(bottom = Spacing.edge + Spacing.m)
                .graphicsLayer { alpha = words.value },
            label = "vinylWords",
        ) { (_, song) ->
            Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                PodiumText(song.first, PodiumTheme.type.body.copy(fontWeight = FontWeight.SemiBold), Color.White, textAlign = TextAlign.Center)
                Spacer(Modifier.height(2.dp))
                PodiumText(song.second, PodiumTheme.type.footnote, Color.White.copy(alpha = 0.6f), textAlign = TextAlign.Center)
            }
        }
    }
}

private fun app.podium.player.api.NowPlayingItem.toSide() = RecordSide(uid, artworkUri, title, artistDisplay)

/** The seek times' presence, eased. */
@Composable
private fun animateSeekPresence(seeking: Boolean): State<Float> {
    val presence = remember { Animatable(0f) }
    LaunchedEffect(seeking) { presence.animateTo(if (seeking) 1f else 0f, tween(if (seeking) 180 else 260)) }
    return presence.asState()
}

/** The record for a cover at [sizePx], rendered off the main thread; null until it's ready. */
@Composable
private fun rememberRecord(cover: ImageBitmap?, sizePx: Int): State<ImageBitmap?> =
    produceState<ImageBitmap?>(null, cover, sizePx) {
        value = withContext(Dispatchers.Default) { renderRecord(cover, sizePx) }
    }

/** Where everything sits on a display of [width] × [height] px. */
private class StageGeometry(val width: Float, val height: Float, density: Density) {
    val unit = density.density
    val recordD = min(width * 0.74f, height * 0.47f)
    val recordR = recordD / 2f
    val recordPx = recordD.roundToInt().coerceAtLeast(64)
    val center = Offset(width / 2f, height * 0.41f)

    /** The sleeve as it holds the record, before the record slides out of it. */
    val sleeveSide = min(width * 0.62f, height * 0.40f)
    val sleeveRect = Rect(Offset(width / 2f - sleeveSide / 2f, height * 0.60f - sleeveSide / 2f), Size(sleeveSide, sleeveSide))

    /** The record in the sleeve, and once slid up out of it. */
    val pulledR = sleeveSide * 0.47f
    val pulledCenter = Offset(width / 2f, sleeveRect.center.y - sleeveSide * 0.62f)

    // The tonearm: its pivot beyond the record's top right, hanging down past the record at rest.
    val pivot = Offset(min(center.x + recordR + 22f * unit, width - 24f * unit), center.y - recordR + 6f * unit)
    val armLength = recordD * 0.74f
    val playDegrees = solvePlayAngle(pivot, armLength, center, recordR * 0.86f)

    // The visualiser below the record.
    val pillWidth = recordD * 0.78f
    val pillHeight = 40f * unit
    val pillCenter = Offset(width / 2f, center.y + recordR + 22f * unit + pillHeight / 2f)
}

/** The arm's turn (degrees from hanging straight down, toward the record) that sets the needle on the groove at [radius]. */
private fun solvePlayAngle(pivot: Offset, length: Float, center: Offset, radius: Float): Float {
    fun distance(deg: Float): Float {
        val a = deg * PI.toFloat() / 180f
        val tip = Offset(pivot.x - length * sin(a), pivot.y + length * cos(a))
        return (tip - center).getDistance()
    }
    var lo = 0f
    var hi = 60f
    repeat(30) {
        val mid = (lo + hi) / 2f
        if (distance(mid) > radius) lo = mid else hi = mid
    }
    return (lo + hi) / 2f
}

private class Placed(val center: Offset, val radius: Float, val visible: Boolean)

/** The record's place through the way in, play and the way out. */
private fun placeRecord(g: StageGeometry, e: Float, u: Float, entered: Boolean, leaving: Boolean, reduced: Boolean): Placed {
    if (reduced) return Placed(g.center, g.recordR, true)
    if (leaving) {
        // Up and smaller over the sleeve as it rises, then down into it.
        val gather = easeInOut(segment(u, 0.25f, 0.5f))
        val pack = easeInOut(segment(u, 0.8f, 0.45f))
        val center = lerp(lerp(g.center, g.pulledCenter, gather), g.sleeveRect.center, pack)
        val radius = lerpF(g.recordR, g.pulledR, gather)
        return Placed(center, radius, u < 1.3f)
    }
    if (entered) return Placed(g.center, g.recordR, true)
    // Behind the sleeve as it lifts; up out of it; down into the middle, settling.
    val lift = easeOut(segment(e, 0f, 0.5f))
    val reveal = easeInOut(segment(e, 0.45f, 0.6f))
    val toCentre = easeInOut(segment(e, 1.05f, 0.6f))
    val sleeveNow = g.sleeveRect.center
    val out = sleeveNow + (g.pulledCenter - g.sleeveRect.center) * reveal
    val settle = segment(e, 1.6f, 0.35f)
    val sit = 1f - 0.022f * sin(PI.toFloat() * settle)
    val center = lerp(out, g.center, toCentre)
    val radius = lerpF(g.pulledR, g.recordR, toCentre) * sit
    return Placed(center, radius, lift > 0.6f)
}

/** The sleeve's rect and corner, or null when it's off the stage. */
private fun DrawScope.placeSleeve(g: StageGeometry, home: Rect, e: Float, u: Float, entered: Boolean, leaving: Boolean, reduced: Boolean): Pair<Rect, Float>? {
    val cornerHome = 14.dp.toPx()
    val cornerSleeve = 4.dp.toPx()
    if (reduced) return null
    if (leaving) {
        // Up from below the display, then back to Now Playing's cover with the record inside.
        val rise = easeOut(segment(u, 0.3f, 0.5f))
        val back = easeInOut(segment(u, 1.3f, 0.6f))
        if (rise <= 0f) return null
        val below = g.height + g.sleeveSide * 0.1f - g.sleeveRect.top
        val raised = g.sleeveRect.translate(0f, below * (1f - rise))
        return lerpRect(raised, home, back) to lerpF(cornerSleeve, cornerHome, back)
    }
    if (entered) return null
    val lift = easeOut(segment(e, 0f, 0.5f))
    val away = easeIn(segment(e, 1.05f, 0.5f))
    if (away >= 1f) return null
    val drop = (g.height - g.sleeveRect.top + 16.dp.toPx()) * away
    return lerpRect(home, g.sleeveRect, lift).translate(0f, drop) to lerpF(cornerHome, cornerSleeve, lift)
}

/** How far out of focus the display behind is: in as the cover lifts, out as it lands home. */
private fun stageDepth(enter: Float, leave: Float, reduced: Boolean): Float {
    if (reduced) return (enter * (1f - leave)).coerceIn(0f, 1f)
    val inDepth = easeOut(segment(enter * EnterMs / 1000f, 0f, 0.5f))
    val outDepth = 1f - easeInOut(segment(leave * LeaveMs / 1000f, 1.3f, 0.6f))
    return (inDepth * outDepth).coerceIn(0f, 1f)
}

/** Degrees a disc of [radius] turns rolling [dx] along the ground. */
private fun rollDegrees(dx: Float, radius: Float): Float = dx / radius * 180f / PI.toFloat()

/** The record, turned by [degrees], with its shadow beneath and the room's light still across it. */
private fun DrawScope.drawRecord(record: ImageBitmap, center: Offset, radius: Float, degrees: Float, tilt: Offset?, alpha: Float) {
    if (alpha <= 0f || radius <= 1f) return
    // A faint light behind it, so a black record stands off even a black display.
    drawCircle(Brush.radialGradient(0f to Color.White.copy(alpha = 0.07f * alpha), 1f to Color.Transparent, center = center, radius = radius * 1.6f), radius * 1.6f, center)
    drawCircle(
        Brush.radialGradient(0.7f to Color.Black.copy(alpha = 0.55f * alpha), 1f to Color.Transparent, center = center + Offset(0f, radius * 0.06f), radius = radius * 1.12f),
        radius = radius * 1.12f,
        center = center + Offset(0f, radius * 0.06f),
    )
    withTransform({
        rotate(degrees, center)
    }) {
        drawImage(
            record,
            srcOffset = IntOffset.Zero,
            srcSize = IntSize(record.width, record.height),
            dstOffset = IntOffset((center.x - radius).roundToInt(), (center.y - radius).roundToInt()),
            dstSize = IntSize((radius * 2f).roundToInt(), (radius * 2f).roundToInt()),
            alpha = alpha,
        )
    }
    // The light on the grooves stays where the room puts it (a little turned by the phone's tilt).
    val turn = (tilt?.x ?: 0f) * 24f
    val ring = Path().apply {
        fillType = PathFillType.EvenOdd
        addOval(Rect(center, radius * 0.985f))
        addOval(Rect(center, radius * LabelShare * 1.02f))
    }
    rotate(turn, center) {
        drawPath(
            ring,
            Brush.sweepGradient(
                0f to Color.Transparent,
                0.11f to Color.White.copy(alpha = 0.11f * alpha),
                0.22f to Color.Transparent,
                0.5f to Color.Transparent,
                0.61f to Color.White.copy(alpha = 0.08f * alpha),
                0.72f to Color.Transparent,
                1f to Color.Transparent,
                center = center,
            ),
        )
    }
}

/** The sleeve: the cover, square, on card with a fine edge. */
private fun DrawScope.drawSleeve(cover: ImageBitmap?, rect: Rect, corner: Float) {
    val shape = Path().apply { addRoundRect(RoundRect(rect, CornerRadius(corner))) }
    drawRect(Brush.verticalGradient(listOf(Color.Black.copy(alpha = 0.45f), Color.Transparent), startY = rect.bottom - 4f, endY = rect.bottom + rect.height * 0.08f), Offset(rect.left, rect.bottom - 4f), Size(rect.width, rect.height * 0.08f + 4f))
    clipPath(shape) {
        drawRect(Color(0xFF1C1C1F), rect.topLeft, rect.size)
        if (cover != null) {
            val side = min(cover.width, cover.height)
            drawImage(
                cover,
                srcOffset = IntOffset((cover.width - side) / 2, (cover.height - side) / 2),
                srcSize = IntSize(side, side),
                dstOffset = IntOffset(rect.left.roundToInt(), rect.top.roundToInt()),
                dstSize = IntSize(rect.width.roundToInt(), rect.height.roundToInt()),
            )
        }
    }
    drawPath(shape, Color.White.copy(alpha = 0.14f), style = Stroke(1.dp.toPx()))
}

/**
 * The tonearm: a base beyond the record's top right, a chrome tube with its counterweight behind the
 * pivot and the headshell at the end, turned in toward the record. [down] runs from rest (hanging
 * past the record) to the needle on the groove.
 */
private fun DrawScope.drawTonearm(g: StageGeometry, down: Float, alpha: Float) {
    if (alpha <= 0f) return
    val u = 1.dp.toPx()
    val p = g.pivot
    val degrees = g.playDegrees * down.coerceIn(0f, 1.05f)
    val tube = 5f * u
    val chrome = { from: Float, to: Float ->
        Brush.horizontalGradient(
            0f to Color(0xFF55575C).copy(alpha = alpha),
            0.35f to Color(0xFFEDEEF0).copy(alpha = alpha),
            0.6f to Color(0xFF9A9CA2).copy(alpha = alpha),
            1f to Color(0xFF3E3F43).copy(alpha = alpha),
            startX = from,
            endX = to,
        )
    }
    rotate(degrees, p) {
        // Its shadow on the record, a little below and to the right.
        drawRoundRect(Color.Black.copy(alpha = 0.32f * alpha), Offset(p.x - tube / 2f + 3f * u, p.y + 5f * u), Size(tube, g.armLength - 10f * u), CornerRadius(tube / 2f))
        // The counterweight behind the pivot.
        drawRoundRect(chrome(p.x - 9f * u, p.x + 9f * u), Offset(p.x - 9f * u, p.y - 40f * u), Size(18f * u, 24f * u), CornerRadius(4f * u))
        drawRoundRect(chrome(p.x - tube / 2f, p.x + tube / 2f), Offset(p.x - tube / 2f, p.y - 18f * u), Size(tube, g.armLength + 4f * u))
        // The headshell, turned in toward the record, with its cartridge and finger lift.
        val end = Offset(p.x, p.y + g.armLength - 10f * u)
        rotate(22f, end) {
            drawRoundRect(chrome(end.x - 6f * u, end.x + 6f * u), Offset(end.x - 6f * u, end.y), Size(12f * u, 24f * u), CornerRadius(2.5f * u))
            drawRoundRect(Color(0xFF1A1A1C).copy(alpha = alpha), Offset(end.x - 4f * u, end.y + 13f * u), Size(8f * u, 10f * u), CornerRadius(1.5f * u))
            drawLine(Color(0xFFCFD1D5).copy(alpha = alpha), Offset(end.x + 6f * u, end.y + 6f * u), Offset(end.x + 12f * u, end.y + 2f * u), strokeWidth = 1.5f * u)
        }
    }
    // The base, on top.
    drawCircle(Color.Black.copy(alpha = 0.4f * alpha), radius = 20f * u, center = p + Offset(2f * u, 4f * u))
    drawCircle(Brush.radialGradient(0f to Color(0xFF8C8E93).copy(alpha = alpha), 1f to Color(0xFF2A2B2E).copy(alpha = alpha), center = p - Offset(5f * u, 6f * u), radius = 26f * u), radius = 18f * u, center = p)
    drawCircle(Color.White.copy(alpha = 0.18f * alpha), radius = 18f * u, center = p, style = Stroke(1f * u))
    drawCircle(Brush.radialGradient(0f to Color(0xFFD9DADD).copy(alpha = alpha), 1f to Color(0xFF55575B).copy(alpha = alpha), center = p - Offset(2f * u, 3f * u), radius = 10f * u), radius = 7f * u, center = p)
}

/** The visualiser below the record (D-104), drawn by the one that Now Playing's nostalgic layout uses too. */
private fun DrawScope.drawVisualiser(g: StageGeometry, presence: Float, amplitude: Float, t: Float) =
    drawDotVisualiser(g.pillCenter, g.pillWidth, g.pillHeight, presence, amplitude, t)

/**
 * A record, drawn once: black vinyl with fine grooves in bands (the tracks), the gaps between them,
 * a smooth run-out by the label, a lit rim; the label a disc cut from the [cover], with a spindle hole.
 */
internal fun renderRecord(cover: ImageBitmap?, sizePx: Int): ImageBitmap {
    val image = ImageBitmap(sizePx, sizePx)
    val canvas = Canvas(image)
    CanvasDrawScope().draw(Density(1f), LayoutDirection.Ltr, canvas, Size(sizePx.toFloat(), sizePx.toFloat())) {
        val r = sizePx / 2f
        val c = Offset(r, r)
        drawCircle(Brush.radialGradient(0f to Color(0xFF17171A), 0.7f to Color(0xFF0D0D0F), 1f to Color(0xFF09090A), center = c, radius = r), r, c)
        val label = r * LabelShare
        val random = Random(sizePx)
        val gaps = List(4) { label * 1.25f + random.nextFloat() * (r * 0.9f - label * 1.25f) }.sorted()
        var radius = label * 1.12f
        while (radius < r * 0.965f) {
            val inGap = gaps.any { abs(it - radius) < r * 0.008f }
            if (!inGap) drawCircle(Color.White.copy(alpha = 0.025f + random.nextFloat() * 0.05f), radius, c, style = Stroke(max(0.6f, r / 650f)))
            radius += max(1.1f, r / 360f) * (0.8f + random.nextFloat() * 0.6f)
        }
        drawCircle(Color.White.copy(alpha = 0.05f), label * 1.12f, c, style = Stroke(max(1f, r / 300f)))
        drawCircle(Color.White.copy(alpha = 0.12f), r - max(0.75f, r / 520f), c, style = Stroke(max(1.5f, r / 260f)))
        // The label.
        val disc = Path().apply { addOval(Rect(c, label)) }
        clipPath(disc) {
            drawRect(Color(0xFF2B2B30))
            if (cover != null) {
                val side = min(cover.width, cover.height)
                drawImage(
                    cover,
                    srcOffset = IntOffset((cover.width - side) / 2, (cover.height - side) / 2),
                    srcSize = IntSize(side, side),
                    dstOffset = IntOffset((r - label).roundToInt(), (r - label).roundToInt()),
                    dstSize = IntSize((label * 2f).roundToInt(), (label * 2f).roundToInt()),
                )
            }
            drawCircle(Brush.radialGradient(0.8f to Color.Transparent, 1f to Color.Black.copy(alpha = 0.35f), center = c, radius = label), label, c)
        }
        drawCircle(Color.Black.copy(alpha = 0.5f), label, c, style = Stroke(max(1.5f, r / 200f)))
        val hole = r * 0.032f
        drawCircle(Color(0xFF060607), hole, c)
        drawCircle(Color.White.copy(alpha = 0.2f), hole, c, style = Stroke(max(1f, r / 400f)))
    }
    return image
}

private fun segment(t: Float, start: Float, duration: Float): Float = ((t - start) / duration).coerceIn(0f, 1f)
private fun easeOut(x: Float): Float = 1f - (1f - x).pow(3)
private fun easeIn(x: Float): Float = x * x * x
private fun easeInOut(x: Float): Float = if (x < 0.5f) 4f * x * x * x else 1f - (-2f * x + 2f).pow(3) / 2f
private fun lerpF(a: Float, b: Float, t: Float) = a + (b - a) * t
private fun lerp(a: Offset, b: Offset, t: Float) = Offset(lerpF(a.x, b.x, t), lerpF(a.y, b.y, t))
private fun lerpRect(a: Rect, b: Rect, t: Float) = Rect(lerpF(a.left, b.left, t), lerpF(a.top, b.top, t), lerpF(a.right, b.right, t), lerpF(a.bottom, b.bottom, t))

/** The label's share of the record's radius. */
private const val LabelShare = 0.35f

/** 33⅓ rpm. */
private const val RecordDegreesPerSecond = 200f
private const val RecordDegreesPerMs = RecordDegreesPerSecond / 1000f

/** A jump in the song's position larger than this is a seek, and turns the record. */
private const val SeekJumpMs = 400L

/** The sound when nothing is measured (another app plays, or audio Podium can't read): a calm wave. */

private const val ScrimAlpha = 0.5f

/** How hard the needle lands, as a collision (D-105): soft, a record not a drum. */
private const val NeedleLands = 0.5f
private const val EnterMs = 2_400
private const val LeaveMs = 2_000
private const val ArmAtMs = 1_700L
private const val ArmDownMs = 560
private const val ArmUpMs = 340
private const val ArmLifted = 0.6f
private const val PillAtMs = 1_850L
private const val WordsAtMs = 2_000L
private const val SpinUpMs = 700
private const val SpinDownMs = 900
private const val RollMs = 900
private const val SeekIdleMs = 4_000L
private const val ReducedFadeMs = 220

/** The cover is loaded at this size for the sleeve and the label. */
private val CoverSize = 512.dp
