package app.podium.feature.nowplaying

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.podium.core.designsystem.theme.BoneColors
import app.podium.core.designsystem.theme.CarbonColors
import app.podium.core.designsystem.theme.PodiumTheme
import app.podium.core.designsystem.type.LocalTypographyPreset
import app.podium.core.designsystem.type.PodiumText
import app.podium.core.interaction.InputTargetEffect
import app.podium.core.interaction.PodiumInput
import app.podium.core.interaction.WheelButton
import app.podium.core.interaction.WheelContext
import app.podium.core.interaction.rememberPodiumHaptics
import app.podium.core.lyrics.Lyrics
import app.podium.core.lyrics.LyricsAttribution
import app.podium.core.lyrics.LyricsRequest
import app.podium.core.lyrics.LyricsResult
import app.podium.core.lyrics.LyricsTiming
import app.podium.core.lyrics.WordTiming
import app.podium.player.api.PlayIntent
import app.podium.player.api.PlaybackController
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive

/** Where the lyrics screen gets its words (the app's lyrics repository behind it). */
interface LyricsGateway {
    val attribution: LyricsAttribution
    suspend fun lyrics(request: LyricsRequest): LyricsResult

    /** The listener agreed to send song details to the lyrics service (D-11). */
    fun allow()
}

/**
 * Now Playing ▸ Lyrics (LYRICS_ARCHITECTURE.md §7): the whole display becomes the lyric. One line at
 * a time, as large as it fits, justified edge to edge; and every new line inverts the display —
 * light paper with dark words, then dark with light, and so on — like an old monochrome player
 * stepping through its screens. No gradients, no karaoke sweep; the lyric itself is the interface.
 *
 * - Synced lyrics follow the playback position of whoever plays (Podium or another app), and each
 *   line's words appear as they are sung ([WordTiming]): in place, each fading in, the line's layout
 *   fixed from its first word so nothing moves.
 * - Turning the Wheel reads ahead or back; following resumes after a few seconds, or with Center,
 *   which also jumps playback to the line read — only when the player can seek.
 * - Center plays/pauses while following; Menu goes back; a tap shows the song and the credit.
 * - Plain lyrics never pretend to be synced: the Wheel moves through them.
 */
@Composable
fun LyricsScreen(controller: PlaybackController, gateway: LyricsGateway) {
    val snapshot by controller.snapshot.collectAsStateWithLifecycle()
    val item = snapshot.item
    val haptics = rememberPodiumHaptics()
    val request = remember(item?.trackId, item?.title, item?.artistDisplay) {
        item?.let { LyricsRequest(it.trackId.value, it.title, it.artistDisplay, it.albumTitle, it.durationMs) }
    }
    var reload by remember { mutableIntStateOf(0) }
    var result by remember(request) { mutableStateOf<LyricsResult?>(null) }
    LaunchedEffect(request, reload) {
        result = null
        result = request?.let { gateway.lyrics(it) }
    }
    val lyrics = (result as? LyricsResult.Found)?.lyrics
    val lines: List<String> = when (lyrics) {
        is Lyrics.Synced -> lyrics.lines.map { it.text }
        is Lyrics.Plain -> lyrics.lines
        else -> emptyList()
    }

    var index by remember(lyrics) { mutableIntStateOf(0) }
    // How many words of the line are in; everything while reading, for plain lyrics, or paused on it.
    var shown by remember(lyrics) { mutableIntStateOf(Int.MAX_VALUE) }
    var following by remember(lyrics) { mutableStateOf(true) }
    var lastTurn by remember { mutableLongStateOf(0L) }
    var details by remember { mutableStateOf(false) }
    val playing = snapshot.intent == PlayIntent.PLAY

    // Follow playback efficiently: wake when the next word or line is due (or twice a second to
    // notice a seek), and change state only when it changes — the screen redraws once per word.
    if (lyrics is Lyrics.Synced) {
        val starts = remember(lyrics) { HashMap<Int, List<Long>>() }
        fun startsOf(i: Int) = starts.getOrPut(i) { WordTiming.starts(lyrics.lines[i], lyrics.lines.getOrNull(i + 1)?.startMs) }
        LaunchedEffect(lyrics, following, playing) {
            if (!following) shown = Int.MAX_VALUE
            while (isActive && following) {
                val position = controller.positionMs()
                val i = LyricsTiming.displayIndex(lyrics.lines, position)
                val words = startsOf(i)
                index = i
                shown = WordTiming.shown(words, position)
                val next = listOfNotNull(LyricsTiming.nextChangeMs(lyrics.lines, position), WordTiming.nextWordMs(words, position)).minOrNull()
                delay(if (playing && next != null) (next - position).coerceIn(MIN_WAIT_MS, MAX_WAIT_MS) else MAX_WAIT_MS)
            }
        }
        // Reading ahead or back ends after a pause: the lyric goes back to what's being sung.
        LaunchedEffect(lastTurn, following) {
            if (!following) {
                delay(RESUME_FOLLOW_MS)
                following = true
            }
        }
    }
    LaunchedEffect(details) {
        if (details) {
            delay(DETAILS_MS)
            details = false
        }
    }

    InputTargetEffect(WheelContext.LIST_FOCUS) { input ->
        when (input) {
            is PodiumInput.Rotate -> {
                if (lines.isEmpty()) return@InputTargetEffect true
                val next = (index + input.detents.coerceIn(-1, 1)).coerceIn(0, lines.lastIndex)
                if (next == index) haptics.boundary() else haptics.step()
                index = next
                if (lyrics is Lyrics.Synced) {
                    following = false
                    shown = Int.MAX_VALUE
                    lastTurn = System.nanoTime()
                }
                true
            }
            is PodiumInput.Press -> when (input.button) {
                WheelButton.CENTER -> {
                    when {
                        result == LyricsResult.NeedsConsent -> {
                            gateway.allow()
                            reload++
                        }
                        lyrics is Lyrics.Synced && !following -> {
                            // Jump to the line read, when the player can; otherwise just follow again.
                            if (snapshot.controls.seek) controller.seekTo(lyrics.lines[index].startMs) else haptics.reject()
                            following = true
                        }
                        result is LyricsResult.Failed || result is LyricsResult.Offline || result is LyricsResult.RateLimited -> reload++
                        else -> controller.togglePlayPause()
                    }
                    haptics.confirm()
                    true
                }
                else -> false
            }
            is PodiumInput.LongPress -> if (input.button == WheelButton.CENTER) {
                details = !details
                true
            } else false
            is PodiumInput.Release -> false
        }
    }

    // The two states of the display: Bone's paper and Carbon's black, never anything else.
    val inverted = lines.isNotEmpty() && index % 2 == 1
    val reduced = PodiumTheme.motion.reduced
    val spec = if (reduced) snap<Color>() else tween<Color>(INVERT_MS)
    val paper by animateColorAsState(if (inverted) CarbonColors.canvas else BoneColors.canvas, spec, label = "lyricPaper")
    val ink by animateColorAsState(if (inverted) CarbonColors.labelPrimary else BoneColors.labelPrimary, spec, label = "lyricInk")
    val quiet = if (inverted) CarbonColors.labelSecondary else BoneColors.labelSecondary

    Box(
        Modifier
            .fillMaxSize()
            .background(paper)
            .pointerInput(Unit) { detectTapGestures(onTap = { details = !details }) },
    ) {
        when {
            item == null -> Message("Nothing playing", "Choose a song, then open its lyrics.", ink, quiet)
            result == null -> Message("Finding lyrics", null, ink, quiet)
            result == LyricsResult.NeedsConsent -> Message(
                "Find lyrics online?",
                "Podium sends the song title, artist, album and length to ${gateway.attribution.name}. Press Center to allow. Menu goes back.",
                ink,
                quiet,
            )
            result == LyricsResult.NotFound -> Message("No lyrics found", "Lyrics for this song aren't available.", ink, quiet)
            result == LyricsResult.Offline -> Message("Lyrics unavailable offline", "Connect, then press Center to try again.", ink, quiet)
            result == LyricsResult.RateLimited -> Message("Too many requests", "Wait a moment, then press Center to try again.", ink, quiet)
            result == LyricsResult.Failed -> Message("Couldn't load lyrics", "Press Center to try again.", ink, quiet)
            lyrics == Lyrics.Instrumental -> Message("Instrumental", "This song has no words.", ink, quiet)
            else -> Lyric(lines.getOrElse(index) { "" }, ink, quiet, if (lyrics is Lyrics.Synced) shown else Int.MAX_VALUE)
        }
        if (details && item != null) {
            Column(Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp)) {
                PodiumText(item.title, PodiumTheme.type.footnote, quiet, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
                val note = buildString {
                    append(item.artistDisplay)
                    if (lyrics is Lyrics.Plain) append(". Not synced: turn the Wheel to read")
                    // Most lyrics time whole lines: the words' pace inside a line is then Podium's estimate.
                    if (lyrics is Lyrics.Synced && lyrics.lines.getOrNull(index)?.let(WordTiming::isEstimated) == true) append(". Words paced to the line")
                    if (lyrics is Lyrics.Synced && !following) append(". Center returns to the music")
                }
                PodiumText(note, PodiumTheme.type.footnote, quiet, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
                if (result is LyricsResult.Found) {
                    PodiumText("Lyrics from ${gateway.attribution.name}", PodiumTheme.type.footnote, quiet, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
                }
            }
        }
    }
}

/**
 * One lyric line, as large as it fits, justified (see [LyricLayout]). Drawn, never clipped. The first
 * [shown] words are in, each fading in and settling a little upward as it arrives; the layout is
 * the whole line's, so words appear in their places and nothing reflows. Before a line's first word
 * (only before the song's first line), a quiet ellipsis waits.
 */
@Composable
private fun Lyric(text: String, color: Color, quiet: Color, shown: Int) {
    val measurer = rememberTextMeasurer(cacheSize = 64)
    val base = PodiumTheme.type.title
    val preset = LocalTypographyPreset.current
    val family = remember(text, preset) { preset.familyFor(text) }
    val style = remember(base, family) { base.copy(fontFamily = family, fontWeight = FontWeight.Medium, letterSpacing = 0.sp) }
    val density = LocalDensity.current
    val reduced = PodiumTheme.motion.reduced
    BoxWithConstraints(
        Modifier
            .fillMaxSize()
            .semantics {
                contentDescription = text
                liveRegion = LiveRegionMode.Polite
            },
    ) {
        // Generous margins: the words never touch the edge of the display.
        val marginX = maxWidth * 0.09f
        val marginY = maxHeight * 0.12f
        val widthPx = with(density) { (maxWidth - marginX * 2).toPx() }
        val heightPx = with(density) { (maxHeight - marginY * 2).toPx() }
        val maxFont = with(density) { (maxHeight * 0.16f).coerceAtMost(64.dp).toPx() }
        val minFont = with(density) { 20.sp.toPx() }
        val layout = remember(text, widthPx, heightPx, style) {
            LyricLayout.layout(text, widthPx, heightPx, maxFont, minFont) { word, px ->
                measurer.measure(word, style.copy(fontSize = with(density) { px.toSp() })).size.width.toFloat()
            }
        }
        val originX = with(density) { marginX.toPx() }
        val originY = with(density) { marginY.toPx() }
        val wordStyle = remember(layout.fontPx, style) {
            style.copy(fontSize = with(density) { layout.fontPx.toSp() }, lineHeight = with(density) { layout.lineHeightPx.toSp() })
        }
        val count = layout.lines.sumOf { it.words.size }
        // One animated value per word, reset with the line.
        key(text) {
            val spec = if (reduced) snap<Float>() else tween<Float>(WORD_FADE_MS)
            val alphas = List(count) { i -> animateFloatAsState(if (i < shown) 1f else 0f, spec, label = "word") }
            val waiting = count > 0 && shown == 0
            val rise = layout.fontPx * 0.12f
            // Each word measured once per line; fading only changes how it's drawn.
            val measured = remember(layout, wordStyle, color) {
                layout.lines.flatMap { line -> line.words.map { measurer.measure(it.text, wordStyle.copy(color = color), softWrap = false) } }
            }
            val dots = remember(wordStyle, quiet) { measurer.measure("…", wordStyle.copy(color = quiet), softWrap = false) }
            Canvas(Modifier.fillMaxSize()) {
                var i = 0
                for (line in layout.lines) for (word in line.words) {
                    val a = alphas[i].value
                    val text = measured[i++]
                    if (a <= 0.01f) continue
                    drawText(text, topLeft = Offset(originX + word.x, originY + line.y + rise * (1f - a)), alpha = a)
                }
                if (waiting) drawText(dots, topLeft = Offset((size.width - dots.size.width) / 2f, (size.height - dots.size.height) / 2f))
            }
        }
    }
}

@Composable
private fun Message(title: String, body: String?, ink: Color, quiet: Color) {
    Column(Modifier.fillMaxSize().padding(horizontal = 24.dp), verticalArrangement = androidx.compose.foundation.layout.Arrangement.Center) {
        PodiumText(title, PodiumTheme.type.nowPlayingTitle, ink, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth(), maxLines = 2)
        if (body != null) {
            PodiumText(body, PodiumTheme.type.body, quiet, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth().padding(top = 8.dp), maxLines = 5)
        }
    }
}

private const val MIN_WAIT_MS = 16L
private const val MAX_WAIT_MS = 500L
private const val RESUME_FOLLOW_MS = 5_000L
private const val DETAILS_MS = 4_000L
private const val INVERT_MS = 90
private const val WORD_FADE_MS = 140
