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
import androidx.compose.ui.graphics.graphicsLayer
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
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.podium.core.designsystem.component.Pinwheel
import app.podium.core.designsystem.theme.BoneColors
import app.podium.core.designsystem.theme.CarbonColors
import app.podium.core.designsystem.theme.LocalLyricsBackground
import app.podium.core.designsystem.theme.LocalLyricsTheme
import app.podium.core.designsystem.theme.LyricsBackground
import app.podium.core.designsystem.theme.LyricsTheme
import app.podium.core.designsystem.theme.PodiumTheme
import app.podium.core.designsystem.type.LocalLyricsTypeface
import app.podium.core.designsystem.type.PodiumText
import app.podium.core.interaction.InputTargetEffect
import app.podium.core.interaction.PodiumInput
import app.podium.core.interaction.WheelButton
import app.podium.core.interaction.WheelContext
import app.podium.core.interaction.rememberPodiumHaptics
import app.podium.core.model.MediaKind
import app.podium.core.lyrics.Lyrics
import app.podium.core.lyrics.LyricsAttribution
import app.podium.core.lyrics.LyricsRequest
import app.podium.core.lyrics.LyricsResult
import app.podium.core.lyrics.LyricsTiming
import app.podium.core.lyrics.WordTiming
import app.podium.feature.nowplaying.line.ContinuousLine
import app.podium.feature.nowplaying.line.LineInk
import app.podium.feature.nowplaying.line.LineScript
import app.podium.player.api.AudioPulse
import app.podium.player.api.PlayIntent
import app.podium.player.api.PlaybackController
import app.podium.player.api.PlaybackStatus
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
 * - Lyrics without usable times are paced across the song by Podium ([app.podium.core.lyrics.LyricsFormatter],
 *   D-50), so they follow the music too; the details say the timing is an estimate. Only when the
 *   song's length is unknown do they stay plain, moved through with the Wheel.
 * - The Continuous line theme (D-81, [LyricsTheme.CONTINUOUS_LINE]) draws the same lyrics as one
 *   line writing by hand on black ([ContinuousLine]); everything else here — finding the words,
 *   consent, the Wheel, the details — is shared.
 * - In both themes the words swell very slightly with the music's bass ([pulse], D-82), like a
 *   speaker cone: smoothly, and only while the music plays and motion isn't reduced.
 * - In the themes that aren't a line (Classic), long stretches without words bring the orb (D-83,
 *   [OrbStage]): the camera pulls back from the words into the orb, and goes in through its hollow
 *   as the next line starts. A song without words, or none found, shows the orb throughout. The
 *   Continuous line keeps its own instrumental line art.
 */
@Composable
fun LyricsScreen(controller: PlaybackController, gateway: LyricsGateway, pulse: AudioPulse = AudioPulse.None) {
    val snapshot by controller.snapshot.collectAsStateWithLifecycle()
    val item = snapshot.item
    val haptics = rememberPodiumHaptics()
    val request = remember(item?.trackId, item?.title, item?.artistDisplay) {
        // A live station isn't a song: nothing to look up (D-90).
        item?.takeIf { it.kind != MediaKind.LIVE }?.let { LyricsRequest(it.trackId.value, it.title, it.artistDisplay, it.albumTitle, it.durationMs) }
    }
    var reload by remember { mutableIntStateOf(0) }
    var result by remember(request) { mutableStateOf<LyricsResult?>(null) }
    LaunchedEffect(request, reload) {
        result = null
        result = request?.let { gateway.lyrics(it) }
    }
    // The lyrics service asked Podium to wait (D-84): it asks again by itself once the wait is over.
    LaunchedEffect(result) {
        val limited = result as? LyricsResult.RateLimited ?: return@LaunchedEffect
        delay(limited.retryInMs + RETRY_SLACK_MS)
        reload++
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
    // The music actually moving (not just asked to): what the drawing's clock follows (D-82).
    val advancing = snapshot.status == PlaybackStatus.Playing

    // Follow playback efficiently: wake when the next word or line is due (or twice a second to
    // notice a seek), and change state only when it changes — the screen redraws once per word.
    // Coming back to the screen (from Home, or the app reopened) starts following afresh, from
    // where the music is now; so does the player getting going again after a hiccup.
    var resumes by remember { mutableIntStateOf(0) }
    LifecycleResumeEffect(Unit) {
        resumes++
        following = true
        onPauseOrDispose { }
    }
    val status = snapshot.status
    // The music's sound is measured for this screen only while it shows (D-83).
    LifecycleResumeEffect(pulse) {
        val listening = pulse.listen()
        onPauseOrDispose { listening.close() }
    }
    val continuous = LocalLyricsTheme.current == LyricsTheme.CONTINUOUS_LINE
    // When the orb comes (D-83): Classic only, from the lyrics' own times.
    val orb = remember(lyrics, result, item?.durationMs, continuous) {
        when {
            continuous -> OrbTimeline.NEVER
            lyrics is Lyrics.Synced -> OrbTimeline.of(lyrics, item?.durationMs)
            lyrics == Lyrics.Instrumental || result == LyricsResult.NotFound -> OrbTimeline.ALWAYS
            else -> OrbTimeline.NEVER
        }
    }
    if (lyrics is Lyrics.Synced) {
        val starts = remember(lyrics) { HashMap<Int, List<Long>>() }
        fun startsOf(i: Int) = starts.getOrPut(i) { WordTiming.starts(lyrics.lines[i], lyrics.lines.getOrNull(i + 1)?.startMs) }
        val orbState = remember(orb) { OrbTimeline.State() }
        LaunchedEffect(lyrics, following, playing, resumes, status, orb) {
            if (!following) shown = Int.MAX_VALUE
            var lastPosition = -1L
            var lastClock = 0L
            while (isActive && following) {
                val position = controller.positionMs()
                // Diagnostics for lyrics that drift from the music: while playing, the player's
                // position should move with the clock. Facts only (no titles).
                val clock = android.os.SystemClock.elapsedRealtime()
                if (playing && lastPosition >= 0) {
                    val drift = (position - lastPosition) - (clock - lastClock)
                    if (kotlin.math.abs(drift) > DRIFT_LOG_MS) android.util.Log.i("PodiumLyrics", "position moved ${drift}ms off the clock at ${position}ms (status ${status::class.simpleName})")
                }
                lastPosition = position
                lastClock = clock
                // While the orb holds and the camera goes back in, the page shows the line to come.
                orb.at(position.toDouble(), orbState)
                val i = if (orbState.showsNext) upcomingLine(lyrics, position) else LyricsTiming.displayIndex(lyrics.lines, position)
                val words = startsOf(i)
                index = i
                shown = WordTiming.shown(words, position)
                val next = listOfNotNull(LyricsTiming.nextChangeMs(lyrics.lines, position), WordTiming.nextWordMs(words, position), orb.nextChangeMs(position)).minOrNull()
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
                        result is LyricsResult.Failed || result is LyricsResult.Offline -> reload++
                        result is LyricsResult.RateLimited -> Unit // it asks again by itself as soon as it may
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

    // Classic: the two states of the display, Bone's paper and Carbon's black, never anything else;
    // turning with every line, or staying one (D-100). Continuous line: one matte black sheet with
    // ivory ink (D-81).
    val inverted = lyricInverted(LocalLyricsBackground.current, continuous, lines.isNotEmpty(), index)
    val reduced = PodiumTheme.motion.reduced
    val spec = if (reduced || continuous) snap<Color>() else tween<Color>(INVERT_MS)
    val paper by animateColorAsState(if (continuous) LineInk.Paper else if (inverted) CarbonColors.canvas else BoneColors.canvas, spec, label = "lyricPaper")
    val ink by animateColorAsState(if (continuous) LineInk.Ivory else if (inverted) CarbonColors.labelPrimary else BoneColors.labelPrimary, spec, label = "lyricInk")
    val quiet = if (continuous) LineInk.Quiet else if (inverted) CarbonColors.labelSecondary else BoneColors.labelSecondary
    // What the line draws: the same lyrics, timed by the same rules (LYRICS_ARCHITECTURE.md §12.3).
    val script = remember(lyrics, item?.durationMs, continuous) {
        if (!continuous) {
            null
        } else {
            when (lyrics) {
                is Lyrics.Synced -> LineScript.synced(lyrics, item?.durationMs)
                is Lyrics.Plain -> LineScript.plain(lyrics.lines)
                Lyrics.Instrumental -> LineScript.instrumental(item?.durationMs)
                null -> null
            }
        }
    }

    Box(
        Modifier
            .fillMaxSize()
            .background(paper)
            .pointerInput(Unit) { detectTapGestures(onTap = { details = !details }) },
    ) {
        when {
            item == null -> Message("Nothing playing", "Choose a song, then open its lyrics.", ink, quiet)
            item.kind == MediaKind.LIVE -> Message("Live radio", "A station plays many songs, so it has no lyrics here. Choose a song to see its words.", ink, quiet)
            result == null -> Finding(ink, quiet)
            result == LyricsResult.NeedsConsent -> Message(
                "Find lyrics online?",
                "Podium sends the song title, artist, album and length to ${gateway.attribution.name}. Press Center to allow. Menu goes back.",
                ink,
                quiet,
            )
            // Classic: no words to show, so the orb shows the music (D-83).
            result == LyricsResult.NotFound && orb.always -> OrbStage(controller, pulse, orb, advancing, following = true, paper, "No lyrics found", page = {})
            result == LyricsResult.NotFound -> Message("No lyrics found", "Lyrics for this song aren't available.", ink, quiet)
            result == LyricsResult.Offline -> Message("Lyrics unavailable offline", "Connect, then press Center to try again.", ink, quiet)
            result is LyricsResult.RateLimited -> Message(
                "The lyrics service is busy",
                "It asked Podium to wait. Podium asks again by itself ${waitText((result as LyricsResult.RateLimited).retryInMs)}.",
                ink,
                quiet,
            )
            result == LyricsResult.Failed -> Message("Couldn't load lyrics", "Press Center to try again.", ink, quiet)
            script != null && item != null -> {
                // A song without words is drawn too: one instrumental composition across it.
                val words = lyrics != Lyrics.Instrumental
                ContinuousLine(
                    controller = controller,
                    script = script,
                    trackKey = item.trackId.value,
                    // Synced words follow the music unless the Wheel is reading; a song without words always does.
                    following = !words || (lyrics is Lyrics.Synced && following),
                    readingLine = if (lyrics is Lyrics.Synced && following) -1 else index,
                    advancing = advancing,
                    pulse = pulse,
                    description = if (words) lines.getOrElse(index) { "" } else "Instrumental. This song has no words.",
                )
                if (!words) {
                    PodiumText("Instrumental", PodiumTheme.type.footnote, quiet, textAlign = TextAlign.Center, modifier = Modifier.align(Alignment.TopCenter).fillMaxWidth().padding(top = 14.dp))
                }
            }
            lyrics == Lyrics.Instrumental && orb.always -> OrbStage(controller, pulse, orb, advancing, following = true, paper, "Instrumental", page = {})
            lyrics == Lyrics.Instrumental -> Message("Instrumental", "This song has no words.", ink, quiet)
            else -> OrbStage(controller, pulse, orb, advancing, following = lyrics is Lyrics.Synced && following, paper, caption = null) { swell ->
                Lyric(lines.getOrElse(index) { "" }, ink, quiet, if (lyrics is Lyrics.Synced) shown else Int.MAX_VALUE, swell)
            }
        }
        if (details && item != null) {
            Column(Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp)) {
                PodiumText(item.title, PodiumTheme.type.footnote, quiet, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
                val note = buildString {
                    append(item.artistDisplay)
                    if (lyrics is Lyrics.Plain) append(". Not synced: turn the Wheel to read")
                    if (lyrics is Lyrics.Synced && lyrics.estimated) append(". Timing estimated by Podium")
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
private fun Lyric(text: String, color: Color, quiet: Color, shown: Int, swell: () -> Float = { 0f }) {
    val measurer = rememberTextMeasurer(cacheSize = 64)
    val base = PodiumTheme.type.title
    val typeface = LocalLyricsTypeface.current
    val family = remember(text, typeface) { typeface.familyFor(text) }
    val style = remember(base, family) { base.copy(fontFamily = family, fontWeight = FontWeight.Medium, letterSpacing = 0.sp) }
    val density = LocalDensity.current
    val reduced = PodiumTheme.motion.reduced
    BoxWithConstraints(
        Modifier
            .fillMaxSize()
            // The bass swell (D-82): a layer property read while drawing, so the words are never re-laid.
            .graphicsLayer {
                val s = 1f + SWELL_SCALE * swell()
                scaleX = s
                scaleY = s
            }
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

/** Looking for the words: the loading pinwheel in the lyric paper's own ink (D-43). */
@Composable
private fun Finding(ink: Color, quiet: Color) {
    Column(
        Modifier.fillMaxSize().semantics { contentDescription = "Finding lyrics" },
        verticalArrangement = androidx.compose.foundation.layout.Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Pinwheel(size = 34.dp, color = ink)
        PodiumText("Finding lyrics", PodiumTheme.type.footnote, quiet, modifier = Modifier.padding(top = 14.dp))
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
private const val DRIFT_LOG_MS = 1_200L
private const val RESUME_FOLLOW_MS = 5_000L
private const val DETAILS_MS = 4_000L
private const val INVERT_MS = 90

/**
 * Whether Classic's paper is Carbon's black now (D-100): every other line when it turns with the
 * lines ([LyricsBackground.AUTOMATIC], as it always has: light until there are lines), always when
 * it's black, never when it's white. Continuous line draws on its own sheet.
 */
internal fun lyricInverted(background: LyricsBackground, continuous: Boolean, hasLines: Boolean, index: Int): Boolean = when {
    continuous -> false
    background == LyricsBackground.BLACK -> true
    background == LyricsBackground.WHITE -> false
    else -> hasLines && index % 2 == 1
}
private const val WORD_FADE_MS = 140

/** How much a strong bass hit swells the lyric (D-82): under two percent, felt more than seen. */
private const val SWELL_SCALE = 0.018f

/** The next line with words that hasn't started at [positionMs] (the one the orb leads into), or the last line. */
private fun upcomingLine(lyrics: Lyrics.Synced, positionMs: Long): Int {
    val lines = lyrics.lines
    for (i in lines.indices) if (lines[i].startMs > positionMs && WordTiming.words(lines[i]).isNotEmpty()) return i
    return lines.lastIndex
}

/** "in 40 s", "in 2 min": how long until Podium asks the lyrics service again. */
private fun waitText(ms: Long): String {
    val seconds = ((ms + RETRY_SLACK_MS + 999) / 1000).coerceAtLeast(1)
    return if (seconds < 90) "in $seconds s" else "in ${(seconds + 30) / 60} min"
}

private const val RETRY_SLACK_MS = 500L
