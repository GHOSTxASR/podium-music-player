package app.podium.guide

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.podium.core.designsystem.shell.ShellPalette
import app.podium.core.designsystem.theme.CarbonColors
import app.podium.core.designsystem.theme.PodiumTheme
import app.podium.core.designsystem.theme.Spacing
import app.podium.core.designsystem.type.PodiumText
import app.podium.space.PanelButton
import app.podium.space.SmallKey
import app.podium.space.SpaceType
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive

/**
 * The tour (D-57), its own screen over everything: a model of the Podium on a dark stage, the
 * camera closing in on the part each checkpoint is about, a finger showing how, and the words
 * beneath. The real Podium isn't used to demonstrate anything; the model can be tried by hand.
 */
@Composable
fun GuideTour(guide: GuideState, palette: ShellPalette) {
    val step = guide.step
    val shown = remember { mutableStateOf<GuideStep?>(null) }
    if (step != null) shown.value = step
    AnimatedVisibility(visible = step != null, enter = fadeIn(tween(320)), exit = fadeOut(tween(260))) {
        shown.value?.let { TourScreen(it, guide, palette) }
    }
}

@Composable
private fun TourScreen(step: GuideStep, guide: GuideState, palette: ShellPalette) = SpaceType {
    val ink = CarbonColors
    val passed = step in guide.passed
    LaunchedEffect(step, passed) {
        // Done on the model: a beat to see it worked, then the next checkpoint.
        if (passed && step != GuideStep.entries.last()) {
            delay(ADVANCE_MS)
            if (guide.step == step) guide.next()
        }
    }
    BackHandler { if (step.ordinal > 1) guide.back() else guide.skip() }
    Box(
        Modifier
            .fillMaxSize()
            .background(TourBackground)
            .pointerInput(Unit) { detectTapGestures { } }, // nothing beneath is operated
    ) {
        Column(
            Modifier
                .fillMaxSize()
                .windowInsetsPadding(WindowInsets.systemBars)
                .padding(horizontal = Spacing.l, vertical = Spacing.m),
        ) {
            Row(Modifier.fillMaxWidth().height(40.dp), verticalAlignment = Alignment.CenterVertically) {
                if (step.isCheckpoint) {
                    Checkpoints(step, guide.passed, Modifier.weight(1f))
                    Spacer(Modifier.width(Spacing.m))
                    SmallKey("Skip", guide::skip)
                }
            }
            ModelStage(step, palette, onPass = { guide.pass(step) }, modifier = Modifier.weight(1f).fillMaxWidth())
            AnimatedContent(
                targetState = step,
                transitionSpec = {
                    val forward = targetState.ordinal > initialState.ordinal
                    (fadeIn(tween(260, delayMillis = 80)) + slideInHorizontally(tween(320)) { if (forward) it / 8 else -it / 8 }) togetherWith
                        (fadeOut(tween(140)) + slideOutHorizontally(tween(220)) { if (forward) -it / 8 else it / 8 })
                },
                label = "tourWords",
            ) { s ->
                Column(Modifier.fillMaxWidth().semantics(mergeDescendants = true) { liveRegion = LiveRegionMode.Polite }) {
                    s.counter?.let { PodiumText(it, PodiumTheme.type.caption, ink.labelTertiary) }
                    PodiumText(s.title, PodiumTheme.type.title, ink.labelPrimary, fontSize = 26.sp)
                    Spacer(Modifier.height(Spacing.xs))
                    PodiumText(s.line, PodiumTheme.type.rowSecondary, ink.labelSecondary, maxLines = 4)
                }
            }
            Box(Modifier.fillMaxWidth().height(32.dp), contentAlignment = Alignment.CenterStart) {
                androidx.compose.animation.AnimatedVisibility(passed, enter = fadeIn(tween(200)), exit = fadeOut(tween(120))) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CheckMark(ink.labelPrimary)
                        Spacer(Modifier.width(Spacing.s))
                        PodiumText("You've got it", PodiumTheme.type.rowSecondary, ink.labelPrimary)
                    }
                }
            }
            if (step == GuideStep.WELCOME) {
                PanelButton("Show me around", guide::begin)
                Spacer(Modifier.height(Spacing.s))
                PanelButton("Skip", guide::skip, emphasized = false)
            } else {
                Row(horizontalArrangement = Arrangement.spacedBy(Spacing.s)) {
                    if (step.ordinal > 1) PanelButton("Back", guide::back, Modifier.weight(1f), emphasized = false)
                    PanelButton(if (step == GuideStep.entries.last()) "Start using Podium" else "Next", guide::next, Modifier.weight(1.6f))
                }
            }
        }
    }
}

/** Six marks across the top: done ones lit, the current one half lit, the rest dark. */
@Composable
private fun Checkpoints(step: GuideStep, passed: Set<GuideStep>, modifier: Modifier) {
    val ink = CarbonColors
    Row(
        modifier.semantics { contentDescription = step.counter.orEmpty() },
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        for (s in GuideStep.entries.drop(1)) {
            val color = when {
                s in passed || s.ordinal < step.ordinal -> ink.labelPrimary
                s == step -> ink.labelPrimary.copy(alpha = 0.5f)
                else -> ink.separator
            }
            Box(Modifier.weight(1f).height(4.dp).background(color, RoundedCornerShape(2.dp)))
        }
    }
}

@Composable
private fun CheckMark(color: Color) {
    Canvas(Modifier.size(18.dp)) {
        val w = 2.dp.toPx()
        drawCircle(color, radius = size.minDimension / 2, style = androidx.compose.ui.graphics.drawscope.Stroke(w))
        drawLine(color, Offset(size.width * 0.28f, size.height * 0.52f), Offset(size.width * 0.44f, size.height * 0.68f), w, StrokeCap.Round)
        drawLine(color, Offset(size.width * 0.44f, size.height * 0.68f), Offset(size.width * 0.74f, size.height * 0.36f), w, StrokeCap.Round)
    }
}

/**
 * The stage: the model, the camera's shot for [step], the looping demonstration, and the
 * listener's own tries ([onPass] when one works). [geometry] maps between the stage and the model.
 */
@Composable
internal fun ModelStage(
    step: GuideStep,
    palette: ShellPalette,
    onPass: () -> Unit,
    modifier: Modifier = Modifier,
    geometry: StageGeometry = remember { StageGeometry() },
) {
    val reduced = PodiumTheme.motion.reduced
    val modelInk = remember(palette) { ModelInk.of(palette) }
    val measurer = rememberTextMeasurer()
    val density = LocalDensity.current
    val typeface = PodiumTheme.type.row
    val labels = remember(measurer, density, typeface) { ModelLabels(measurer, density, typeface) }
    var clock by remember { mutableFloatStateOf(0f) }
    LaunchedEffect(Unit) {
        val start = androidx.compose.runtime.withFrameNanos { it }
        while (isActive) androidx.compose.runtime.withFrameNanos { clock = (it - start) / 1e9f }
    }
    var stepStart by remember { mutableFloatStateOf(0f) }
    val user = remember(step) { TourUser(step) }
    val camera = remember { Camera() }
    LaunchedEffect(step) {
        stepStart = clock
        camera.moveTo(Shot.of(step), reduced)
    }
    val pass by rememberUpdatedState(onPass)
    Canvas(
        modifier
            .clipToBounds()
            .semantics { contentDescription = step.demo }
            .pointerInput(step, user) { tourGestures(step, user, geometry, { clock }, { pass() }) },
    ) {
        val scene = if (user.active(clock)) {
            user.scene(step, clock)
        } else {
            Demo.scene(step, if (reduced) Demo.stillFrame(step) else (clock - stepStart).coerceAtLeast(0f) % Demo.period(step))
        }
        geometry.update(this, camera, scene)
        drawStage(geometry, camera, modelInk, labels, scene, clock)
    }
}

private val TourBackground = Color(0xFF09090B)
private const val ADVANCE_MS = 1_100L
