package app.podium.core.designsystem.shell

import android.graphics.Bitmap
import android.graphics.Paint
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.keyframes
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
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
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.ImageShader
import androidx.compose.ui.graphics.ShaderBrush
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.min
import androidx.compose.ui.unit.sp
import app.podium.core.designsystem.R
import app.podium.core.designsystem.theme.PodiumMotion
import app.podium.core.designsystem.theme.PodiumTheme
import app.podium.core.interaction.InputTargetEffect
import app.podium.core.interaction.PodiumInput
import app.podium.core.interaction.WheelContext
import app.podium.core.interaction.rememberPodiumHaptics
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.text.NumberFormat
import kotlin.math.roundToInt

private val Silver = Color(0xFFDADADF)
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
 * the memory counting up — then the screen clears to Podium's wordmark, silver with glitter set in
 * it, arriving as the startup chord plays ([onChime]), and a progress bar fills under it, stalling
 * now and then like a real disk.
 * [checks] carry real values (filled in by the caller); a late value is picked up as its line
 * types. Any Wheel press skips ahead. With reduced motion: the lines appear at once, then the wordmark.
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

        // The wordmark, with the chord: it settles into place as the chord blooms, then the bar fills.
        stage = 1
        chime()
        haptics.confirm()
        if (reduced) {
            word.snapTo(1f); bar.snapTo(1f); progress.snapTo(1f)
            delay(700)
        } else {
            launch { word.animateTo(1f, tween(620, easing = PodiumMotion.EmphasizedDecelerate)) }
            launch {
                delay(400)
                haptics.press()
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
            BootMark(word = { word.value }, bar = { bar.value }, progress = { progress.value }, Modifier.fillMaxSize())
        }
    }
}

/**
 * The boot's second stage (D-71): the wordmark, as the website draws it, and the progress bar under
 * it. [word] (0–1) brings the wordmark in, [bar] the bar, and [progress] fills it.
 */
@Composable
internal fun BootMark(word: () -> Float, bar: () -> Float, progress: () -> Float, modifier: Modifier = Modifier) {
    BoxWithConstraints(modifier, contentAlignment = Alignment.Center) {
        val screen = maxWidth
        // Three quarters of the width; on a wide, short screen, held to about a third of its height.
        val markWidth = min(screen * 0.76f, maxHeight * 1.8f)
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            GlitterWordmark(width = markWidth, appear = word)
            Spacer(Modifier.height(24.dp))
            // The progress bar: a hairline capsule, filling.
            Canvas(Modifier.width(min(screen * 0.58f, 220.dp)).height(7.dp).graphicsLayer { alpha = bar() }) {
                val radius = CornerRadius(size.height / 2f)
                drawRoundRect(PhosphorDim, cornerRadius = radius, style = Stroke(1.dp.toPx()))
                val inset = 1.5.dp.toPx()
                val w = (size.width - inset * 2) * progress()
                if (w > 0f) {
                    drawRoundRect(
                        Silver,
                        topLeft = Offset(inset, inset),
                        size = Size(w, size.height - inset * 2),
                        cornerRadius = CornerRadius((size.height - inset * 2) / 2f),
                    )
                }
            }
        }
    }
}

private val WordmarkFamily = FontFamily(Font(R.font.archivo_wordmark))

/** From the top of the line to the bottom: white where the light falls, then a soft silver. */
private val SilverStops = arrayOf(0.18f to Color(0xFFEFEFF2), 0.52f to Color(0xFFD3D3D9), 0.84f to Color(0xFFA3A3AD))

/**
 * "PODIUM" in Archivo at weight 900 and width 125, [width] wide: the website's wordmark, in silver
 * with fine flakes set into the letters, bright and dark. The flakes hold still (static glitter,
 * unlike the body's, which follows the light). [appear] (0–1) fades it in as it settles to size.
 */
@Composable
private fun GlitterWordmark(width: Dp, appear: () -> Float) {
    val density = LocalDensity.current
    val flakes = remember(density.density) { wordmarkFlakes(density.density) }
    // The logotype never follows the font-size setting: it is sized to the screen, like a picture.
    val fontSize = with(density) { (width / WordmarkEms).toSp() }
    BasicText(
        "PODIUM",
        Modifier
            .clearAndSetSemantics { }
            .graphicsLayer {
                val a = appear()
                alpha = a
                scaleX = 0.94f + 0.06f * a
                scaleY = scaleX
                compositingStrategy = CompositingStrategy.Offscreen
            }
            .drawWithCache {
                val silver = Brush.verticalGradient(*SilverStops)
                val glitter = ShaderBrush(ImageShader(flakes, TileMode.Repeated, TileMode.Repeated))
                onDrawWithContent {
                    drawContent()
                    drawRect(silver, blendMode = BlendMode.SrcAtop)
                    drawRect(glitter, blendMode = BlendMode.SrcAtop)
                }
            },
        style = TextStyle(fontFamily = WordmarkFamily, fontSize = fontSize, letterSpacing = (-0.01).em, color = Color.White, textAlign = TextAlign.Center),
        maxLines = 1,
        softWrap = false,
    )
}

/** "PODIUM" is this many ems wide at the wordmark's letter spacing. */
private const val WordmarkEms = 5.31f

/**
 * One tile of the wordmark's flakes, from the body's seeded field (so it is the same every launch):
 * bright flakes and darker specks on transparent, each about a dp across.
 */
private fun wordmarkFlakes(pxPerDp: Float): ImageBitmap {
    val tile = (96 * pxPerDp).roundToInt().coerceIn(128, 512)
    val flakes = GlitterField.flakes(tile, density = 0.85f, amount = 0.55f, flakePx = GlitterField.flakePx(0.4f, pxPerDp), seed = 71)
    val bitmap = Bitmap.createBitmap(tile, tile, Bitmap.Config.ARGB_8888)
    val canvas = android.graphics.Canvas(bitmap)
    val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    for (f in flakes) {
        paint.color = if (f.lit) {
            android.graphics.Color.argb((140 + 115 * f.brightness).roundToInt(), 255, 255, 255)
        } else {
            android.graphics.Color.argb((30 + 70 * f.brightness).roundToInt(), 24, 24, 30)
        }
        // Each flake at its place and wrapped across the edges, so the tiles join seamlessly.
        for (dx in intArrayOf(0, -tile, tile)) for (dy in intArrayOf(0, -tile, tile)) {
            val x = f.x + dx
            val y = f.y + dy
            if (x < -f.size || y < -f.size || x > tile + f.size || y > tile + f.size) continue
            canvas.save()
            canvas.rotate(f.angle, x, y)
            canvas.drawRect(x - f.size / 2, y - f.size / 2, x + f.size / 2, y + f.size / 2, paint)
            canvas.restore()
        }
    }
    return bitmap.asImageBitmap()
}

/** Where the dot leaders end, so results line up in a column. */
private const val LeaderColumn = 16
