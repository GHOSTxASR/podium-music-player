package app.podium.core.designsystem.shell

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.keyframes
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.podium.core.designsystem.theme.PodiumMotion
import app.podium.core.designsystem.theme.PodiumTheme
import app.podium.core.designsystem.type.PodiumText
import app.podium.core.interaction.InputTargetEffect
import app.podium.core.interaction.PodiumInput
import app.podium.core.interaction.WheelContext
import app.podium.core.interaction.rememberPodiumHaptics
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.text.NumberFormat

private val MarkRing = Color(0xFFE8EEF4)
private val MarkCenter = Color(0xFF367FE0)
private val Phosphor = Color(0xFFD9D8D2)
private val PhosphorDim = Color(0xFF8E8D87)

/**
 * One line of the power-on self-test: [label] and the [result] it reports. With [countTo], the
 * result counts up to that number first ([unit] after it), like an old machine sizing its memory.
 */
data class BootCheck(val label: String, val result: String, val countTo: Long? = null, val unit: String = "")

private data class TypedLine(val label: String, val leader: String, val result: String = "", val shown: Int = 0)

/**
 * Power-on (D-26, D-32): old hardware, new software. First a self-test types itself out —
 * Podium's ROM, then each check landing with a tick you can feel (and hear, with clicks on) and
 * the memory counting up — then the screen clears to Podium's mark drawing itself as the startup
 * chord plays ([onChime]) and a progress bar fills, stalling now and then like a real disk.
 * [checks] carry real values (filled in by the caller); a late value is picked up as its line
 * types. Any Wheel press skips ahead. With reduced motion: the lines appear at once, then the mark.
 */
@Composable
fun BootScreen(checks: List<BootCheck>, onChime: () -> Unit, onFinished: () -> Unit, modifier: Modifier = Modifier) {
    val reduced = PodiumTheme.motion.reduced
    val haptics = rememberPodiumHaptics()
    val latestChecks by rememberUpdatedState(checks)
    val chime by rememberUpdatedState(onChime)
    val finished by rememberUpdatedState(onFinished)

    val lines = remember { mutableStateListOf<TypedLine>() }
    var cursor by remember { mutableStateOf(true) }
    var stage by remember { mutableStateOf(0) } // 0 self-test, 1 mark
    var skip by remember { mutableStateOf(false) }
    val postAlpha = remember { Animatable(1f) }
    val sweep = remember { Animatable(0f) }
    val dot = remember { Animatable(0f) }
    val word = remember { Animatable(0f) }
    val progress = remember { Animatable(0f) }
    val bar = remember { Animatable(0f) }

    InputTargetEffect(WheelContext.LIST_FOCUS) { input ->
        if (input is PodiumInput.Press || input is PodiumInput.Rotate) skip = true
        true
    }

    LaunchedEffect(skip) {
        if (!skip) return@LaunchedEffect
        finished()
    }

    LaunchedEffect(Unit) {
        val number = NumberFormat.getIntegerInstance()
        val header = listOf("Podium ROM 3.1", "Copyright 2026 Podium", "")

        if (reduced) {
            (header + latestChecks.map { "${it.label}  ${it.result}" }).forEach { lines += TypedLine(it, "", shown = it.length) }
            delay(500)
        } else {
            // The ROM banner types itself; then each check: label, leader dots, a beat, the result.
            for (text in header) {
                lines += TypedLine(text, "")
                for (i in 1..text.length) {
                    lines[lines.lastIndex] = lines.last().copy(shown = i)
                    delay(9)
                }
                delay(70)
            }
            haptics.tick()
            var index = 0
            while (index < latestChecks.size) {
                val check = latestChecks[index]
                val label = check.label
                lines += TypedLine(label, " " + ".".repeat((LeaderColumn - label.length).coerceAtLeast(2)) + " ")
                for (i in 1..label.length) {
                    lines[lines.lastIndex] = lines.last().copy(shown = i)
                    delay(10)
                }
                delay(110)
                val countTo = check.countTo
                if (countTo != null && countTo > 0) {
                    // Counting the memory: fast at first, settling at the total, a purr of ticks.
                    val steps = 14
                    for (s in 1..steps) {
                        val f = 1f - (1f - s / steps.toFloat()).let { it * it }
                        lines[lines.lastIndex] = lines.last().copy(result = number.format((countTo * f).toLong()) + check.unit)
                        haptics.detent()
                        delay(30)
                    }
                }
                // Take the latest value: a slow measurement (the library) may have arrived by now.
                lines[lines.lastIndex] = lines.last().copy(result = latestChecks.getOrNull(index)?.result ?: check.result)
                haptics.tick()
                delay(150)
                index++
            }
            delay(260)
            lines += TypedLine("", "")
            delay(280)
            postAlpha.animateTo(0f, tween(180))
        }

        // The mark, with the chord: the ring sweeps closed, the centre lands, the bar fills.
        stage = 1
        chime()
        haptics.confirm()
        if (reduced) {
            sweep.snapTo(1f); dot.snapTo(1f); word.snapTo(1f); bar.snapTo(1f); progress.snapTo(1f)
            delay(700)
        } else {
            launch { sweep.animateTo(1f, tween(700, easing = PodiumMotion.EmphasizedDecelerate)) }
            launch {
                delay(420)
                dot.animateTo(1f, spring(dampingRatio = 0.55f, stiffness = 500f))
            }
            launch {
                delay(430)
                haptics.press()
            }
            launch {
                delay(640)
                word.animateTo(1f, tween(420))
            }
            delay(900)
            bar.animateTo(1f, tween(200))
            progress.animateTo(
                1f,
                keyframes {
                    durationMillis = 1500
                    0.16f at 260 using LinearEasing
                    0.21f at 520 using LinearEasing // a stall, like a disk seeking
                    0.58f at 820 using LinearEasing
                    0.66f at 1100 using LinearEasing
                    1f at 1500 using LinearEasing
                },
            )
            haptics.confirm()
            delay(260)
        }
        finished()
    }

    // A blinking block cursor while the self-test types.
    LaunchedEffect(stage) {
        while (stage == 0) {
            cursor = !cursor
            delay(380)
        }
    }

    Box(
        modifier
            .fillMaxSize()
            .background(Color.Black)
            .semantics { contentDescription = "Starting Podium" },
    ) {
        if (stage == 0) {
            val style = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 11.sp, lineHeight = 16.sp, color = Phosphor)
            Column(Modifier.padding(horizontal = 16.dp, vertical = 18.dp).graphicsLayer { alpha = postAlpha.value }) {
                lines.forEachIndexed { i, line ->
                    val typedLabel = line.label.take(line.shown)
                    val done = line.shown >= line.label.length
                    val text = buildString {
                        append(typedLabel)
                        if (done && line.leader.isNotEmpty()) append(line.leader)
                        if (done) append(line.result)
                        if (i == lines.lastIndex && cursor) append('█')
                    }
                    BasicText(text, style = if (i < 2) style else style.copy(color = if (line.result.isEmpty() && done && line.leader.isNotEmpty()) PhosphorDim else Phosphor), maxLines = 1, softWrap = false)
                }
            }
        } else {
            Column(Modifier.align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally) {
                Canvas(Modifier.size(84.dp)) {
                    val stroke = 9.dp.toPx()
                    val r = size.minDimension / 2f - stroke / 2f
                    drawArc(
                        color = MarkRing,
                        startAngle = -90f,
                        sweepAngle = 360f * sweep.value,
                        useCenter = false,
                        topLeft = Offset(center.x - r, center.y - r),
                        size = Size(r * 2, r * 2),
                        style = Stroke(stroke, cap = StrokeCap.Round),
                    )
                    drawCircle(MarkCenter, radius = size.minDimension * 0.15f * dot.value)
                }
                Spacer(Modifier.height(18.dp))
                PodiumText("Podium", PodiumTheme.type.title, MarkRing, Modifier.graphicsLayer { alpha = word.value })
                Spacer(Modifier.height(26.dp))
                // The progress bar: a hairline capsule, filling.
                Canvas(Modifier.width(132.dp).height(6.dp).graphicsLayer { alpha = bar.value }) {
                    val radius = CornerRadius(size.height / 2f)
                    drawRoundRect(PhosphorDim, cornerRadius = radius, style = Stroke(1.dp.toPx()))
                    val inset = 1.5.dp.toPx()
                    val w = (size.width - inset * 2) * progress.value
                    if (w > 0f) {
                        drawRoundRect(
                            MarkRing,
                            topLeft = Offset(inset, inset),
                            size = Size(w, size.height - inset * 2),
                            cornerRadius = CornerRadius((size.height - inset * 2) / 2f),
                        )
                    }
                }
            }
        }
    }
}

/** Where the dot leaders end, so results line up in a column. */
private const val LeaderColumn = 16
