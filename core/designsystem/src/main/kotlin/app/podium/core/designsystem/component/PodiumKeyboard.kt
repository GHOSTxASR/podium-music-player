package app.podium.core.designsystem.component

import android.os.SystemClock
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.setText
import androidx.compose.ui.semantics.text
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.lerp
import app.podium.core.designsystem.glass.GlassMaterial
import app.podium.core.designsystem.glass.glass
import app.podium.core.designsystem.shell.ShellPalette
import app.podium.core.designsystem.shell.grain
import app.podium.core.designsystem.symbol.PodiumSymbol
import app.podium.core.designsystem.symbol.Symbol
import app.podium.core.designsystem.theme.PodiumTheme
import app.podium.core.interaction.rememberPodiumHaptics
import kotlinx.coroutines.delay

/*
 * The Podium keyboard (D-45, interaction-model.md §2.1). When a text field needs typing, the Wheel
 * unwinds and opens out into a keyboard in the same piece of the body; the keyboard's close key
 * folds it back into the Wheel. Settings ▸ Keyboard chooses it or the phone's own keyboard.
 */

/** One field being typed into: how to read and change its text, and what its keys do. */
@Stable
class KeyboardSession internal constructor(
    internal val owner: Any,
    internal val text: () -> String,
    internal val onText: (String) -> Unit,
    val layout: KeyLayout,
    val action: KeyboardAction,
    internal val onAction: () -> Unit,
    internal val onHide: () -> Unit,
    internal val maxLength: Int?,
    internal val accept: (Char) -> Boolean,
)

/** Who is typing now (the shell shows the keyboard while there's a session). */
@Stable
class KeyboardHost {
    var session by mutableStateOf<KeyboardSession?>(null)
        private set

    /** Whether the keyboard has room where the Wheel is (set by [WheelKeyboard]; landscape may not). */
    var fits by mutableStateOf(true)
        internal set

    val isOpen: Boolean get() = session != null

    internal fun open(session: KeyboardSession) {
        this.session = session
    }

    internal fun close(owner: Any) {
        if (session?.owner === owner) session = null
    }

    /** Close the keyboard the way its own close key does (Back, for instance). */
    fun dismiss() {
        session?.onHide?.invoke()
        session = null
    }
}

val LocalKeyboardHost = staticCompositionLocalOf<KeyboardHost?> { null }

/** Which keyboard fields use; without a provider, the phone's (previews, tests, other activities). */
val LocalKeyboardStyle = staticCompositionLocalOf { KeyboardStyle.PHONE }

/**
 * A one-line text field that types with the Podium keyboard or the phone's, as Settings says
 * (D-45). With Podium's, focusing it (a tap, or [focusRequester]) opens the keyboard where the
 * Wheel is and never summons the phone's; the text shows with a caret at its end. [onAction] runs
 * on the action key (Search/Done) or the phone's IME action; the field stays focused unless the
 * caller clears focus, which also closes the keyboard.
 */
@Composable
fun PodiumTextField(
    value: String,
    onValueChange: (String) -> Unit,
    textStyle: TextStyle,
    description: String,
    modifier: Modifier = Modifier,
    focusRequester: FocusRequester = remember { FocusRequester() },
    action: KeyboardAction = KeyboardAction.DONE,
    onAction: () -> Unit = {},
    layout: KeyLayout = KeyLayout.TEXT,
    maxLength: Int? = null,
    accept: (Char) -> Boolean = { true },
    phoneOptions: KeyboardOptions = KeyboardOptions.Default,
    cursorColor: Color = PodiumTheme.colors.labelPrimary,
) {
    val host = LocalKeyboardHost.current
    val style = LocalKeyboardStyle.current
    val miniature = LocalMiniature.current
    if (host == null || style == KeyboardStyle.PHONE || !host.fits || miniature) {
        BasicTextField(
            value = value,
            onValueChange = { raw -> onValueChange(raw.filter(accept).let { if (maxLength != null) it.take(maxLength) else it }) },
            singleLine = true,
            textStyle = textStyle,
            cursorBrush = SolidColor(cursorColor),
            keyboardOptions = phoneOptions.copy(imeAction = if (action == KeyboardAction.SEARCH) ImeAction.Search else ImeAction.Done),
            keyboardActions = KeyboardActions(onSearch = { onAction() }, onDone = { onAction() }),
            modifier = modifier.focusRequester(focusRequester).semantics { contentDescription = description },
        )
        return
    }
    val owner = remember { Any() }
    val currentValue by rememberUpdatedState(value)
    val currentChange by rememberUpdatedState(onValueChange)
    val currentAction by rememberUpdatedState(onAction)
    val focusManager = LocalFocusManager.current
    val session = remember(layout, action, maxLength) {
        KeyboardSession(
            owner = owner,
            text = { currentValue },
            onText = { currentChange(it) },
            layout = layout,
            action = action,
            onAction = { currentAction() },
            onHide = { focusManager.clearFocus() },
            maxLength = maxLength,
            accept = accept,
        )
    }
    var focused by remember { mutableStateOf(false) }
    DisposableEffect(host) { onDispose { host.close(owner) } }
    val active = focused && host.session?.owner === owner
    val scroll = rememberScrollState()
    LaunchedEffect(value) { scroll.scrollTo(scroll.maxValue) }
    Row(
        modifier
            .focusRequester(focusRequester)
            .onFocusChanged { state ->
                focused = state.isFocused
                if (state.isFocused) host.open(session) else host.close(owner)
            }
            .focusable()
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { focusRequester.requestFocus() }
            .semantics {
                contentDescription = description
                text = AnnotatedString(value)
                setText { onValueChange(it.text); true }
            }
            .horizontalScroll(scroll),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        BasicText(value, style = textStyle, maxLines = 1, softWrap = false)
        Caret(active, cursorColor, textStyle)
    }
}

/** The caret at the end of the text: blinking while the field is being typed into. */
@Composable
private fun Caret(active: Boolean, color: Color, style: TextStyle) {
    if (!active) return
    val reduced = PodiumTheme.motion.reduced
    var on by remember { mutableStateOf(true) }
    if (!reduced) {
        LaunchedEffect(Unit) {
            while (true) {
                delay(CARET_BLINK_MS)
                on = !on
            }
        }
    }
    val height = with(androidx.compose.ui.platform.LocalDensity.current) { (style.lineHeight.takeIf { it.isSp } ?: style.fontSize).toDp() }
    Box(Modifier.padding(start = 1.dp).size(width = 2.dp, height = height * 0.85f).background(if (on || reduced) color else Color.Transparent))
}

/**
 * The Wheel's place on the body, which becomes the keyboard while [host] has a session (D-45). The
 * Wheel unwinds as it fades and shrinks; the piece it sits in opens from a circle into a wide
 * rounded panel the width of the body; the keys come up from its middle outwards. Closing runs it
 * backwards. Reduced motion: a short crossfade.
 */
@Composable
fun WheelKeyboard(host: KeyboardHost, diameter: Dp, palette: ShellPalette, wheel: @Composable () -> Unit) {
    val session = host.session
    val reduced = PodiumTheme.motion.reduced
    val progress = remember { Animatable(if (session != null) 1f else 0f) }
    // The last session stays on screen while the keyboard folds away.
    var shown by remember { mutableStateOf(session) }
    if (session != null) shown = session
    LaunchedEffect(session != null) {
        val target = if (session != null) 1f else 0f
        progress.animateTo(target, if (reduced) tween(160) else spring(dampingRatio = 0.82f, stiffness = 240f))
    }
    BoxWithConstraints(contentAlignment = Alignment.Center) {
        val fullWidth = (maxWidth - KeyboardMargin * 2).coerceAtLeast(diameter)
        SideEffect { host.fits = fullWidth >= MinKeyboardWidth }
        val p = progress.value.coerceIn(0f, 1.05f)
        val open = p.coerceIn(0f, 1f)
        val width = lerp(diameter, fullWidth, open)
        val corner = lerp(diameter / 2, if (palette.matte) 6.dp else KeyboardCorner, open)
        val shape = RoundedCornerShape(corner)
        Box(Modifier.size(width, diameter), contentAlignment = Alignment.Center) {
            if (open > 0.001f) {
                // The piece of the body that holds the Wheel, opening out: the ring's own material.
                Box(
                    Modifier
                        .fillMaxSize()
                        .graphicsLayer { alpha = (open * 3f).coerceAtMost(1f) }
                        .then(panelSurface(palette, shape)),
                )
            }
            if (open < 0.6f) {
                Box(
                    Modifier.graphicsLayer {
                        val fade = (1f - open / 0.5f).coerceIn(0f, 1f)
                        alpha = fade
                        rotationZ = open * 150f
                        val s = 1f - 0.22f * open
                        scaleX = s
                        scaleY = s
                    },
                ) { wheel() }
            }
            val current = shown
            if (open > 0.3f && current != null) {
                // Read by each key while drawing: the keys rise without recomposing every frame (D-65).
                val appear = remember(progress) { { ((progress.value.coerceIn(0f, 1f) - 0.3f) / 0.7f).coerceIn(0f, 1f) } }
                KeyboardPanel(current, palette, appear = appear, modifier = Modifier.fillMaxSize().padding(KeyboardInset))
            }
        }
    }
}

@Composable
private fun panelSurface(palette: ShellPalette, shape: RoundedCornerShape): Modifier =
    if (palette.isGlass) {
        Modifier.glass(GlassMaterial.Regular, shape)
    } else {
        Modifier
            .clip(shape)
            .background(Brush.verticalGradient(listOf(palette.ring, palette.ring)))
            .grain { palette.grain }
            .border(1.dp, palette.ringRim, shape)
    }

/**
 * The keys. Rows fill the panel; each key is a piece like the Wheel's centre button. [appear] (0–1)
 * brings them up from the middle outwards. Typing gives the Wheel's own click.
 */
@Composable
internal fun KeyboardPanel(session: KeyboardSession, palette: ShellPalette, appear: () -> Float, modifier: Modifier = Modifier) {
    val haptics = rememberPodiumHaptics()
    var state by remember(session.owner, session.layout) { mutableStateOf(KeyboardState(KeyboardLayouts.firstPage(session.layout))) }
    var lastShift by remember { mutableLongStateOf(Long.MIN_VALUE) }
    val rows = KeyboardLayouts.rows(state.page)
    fun press(key: Key) {
        val now = SystemClock.uptimeMillis()
        val result = KeyboardEditor.press(key, session.text(), state, session.maxLength, session.accept, now, lastShift)
        if (key == Key.Shift) lastShift = now
        if (result.text != session.text()) session.onText(result.text)
        state = result.state
        if (result.action) session.onAction()
        if (result.hide) session.onHide()
        haptics.detent()
    }
    Column(modifier, verticalArrangement = Arrangement.spacedBy(KeyGap)) {
        rows.forEachIndexed { r, row ->
            Row(Modifier.fillMaxWidth().weight(1f), horizontalArrangement = Arrangement.spacedBy(KeyGap)) {
                val total = row.sumOf { it.weight.toDouble() }.toFloat()
                row.forEachIndexed { c, spec ->
                    // Keys rise from the middle of the panel outwards.
                    val dx = ((c + 0.5f) / row.size - 0.5f) * 2f
                    val dy = ((r + 0.5f) / rows.size - 0.5f) * 2f
                    val distance = kotlin.math.sqrt(dx * dx + dy * dy) / 1.42f
                    val shown = { ((appear() - 0.45f * distance) / 0.55f).coerceIn(0f, 1f) }
                    Box(Modifier.weight(spec.weight / total).fillMaxHeight()) {
                        if (spec !== Gap) KeyCap(spec.key, state, session.action, palette, shown, onPress = { press(spec.key) })
                    }
                }
            }
        }
    }
}

@Composable
private fun KeyCap(key: Key, state: KeyboardState, action: KeyboardAction, palette: ShellPalette, shown: () -> Float, onPress: () -> Unit) {
    val colors = PodiumTheme.colors
    val type = PodiumTheme.type
    val glass = palette.isGlass
    var pressed by remember { mutableStateOf(false) }
    // TACTILE (D-65): down on the frame the finger lands, up with a whisper of spring.
    val travel = remember { KeyTravel(0.94f) }
    val scope = rememberCoroutineScope()
    val currentPress by rememberUpdatedState(onPress)
    val repeats = key == Key.Backspace
    val shape = RoundedCornerShape(if (palette.matte) 3.dp else 9.dp)
    val strong = key == Key.Action || (key == Key.Shift && state.shift != ShiftState.OFF)
    val face = when {
        glass -> colors.labelPrimary.copy(alpha = if (pressed) 0.22f else if (strong) 0.16f else 0.08f)
        pressed -> lerp(palette.center, Color.Black, 0.12f)
        strong -> lerp(palette.center, palette.legend, 0.18f)
        else -> palette.center
    }
    val ink = if (glass) colors.labelPrimary else palette.legend
    val description = KeyboardLayouts.description(key, state.shift, action)
    Box(
        Modifier
            .fillMaxSize()
            .graphicsLayer {
                val up = shown()
                alpha = up
                val s = (0.6f + 0.4f * up) * travel.value
                scaleX = s
                scaleY = s
                translationY = (1f - up) * 10.dp.toPx()
            }
            .clip(shape)
            .background(face)
            .then(if (glass) Modifier else Modifier.border(1.dp, palette.ringRim, shape))
            .semantics {
                role = Role.Button
                contentDescription = description
                onClick { currentPress(); true }
            }
            .pointerInput(key) {
                awaitEachGesture {
                    awaitFirstDown()
                    travel.press(scope)
                    pressed = true
                    if (repeats) {
                        // Delete repeats while held, and stops when the finger lifts or slides off.
                        currentPress()
                        var wait = REPEAT_DELAY_MS
                        while (withTimeoutOrNull(wait) { waitForUpOrCancellation(); true } == null) {
                            currentPress()
                            wait = REPEAT_EVERY_MS
                        }
                    } else {
                        val up = waitForUpOrCancellation()
                        if (up != null) currentPress()
                    }
                    travel.release(scope)
                    pressed = false
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        val label = KeyboardLayouts.label(key, state.shift, action)
        when {
            label != null -> BasicText(
                label,
                style = (if (key is Key.Char) type.title else type.footnote).copy(color = ink),
                maxLines = 1,
            )
            key == Key.Shift -> Symbol(if (state.shift == ShiftState.LOCKED) PodiumSymbol.CapsLock else PodiumSymbol.Shift, ink, size = 20.dp, weight = if (state.shift != ShiftState.OFF) 700 else 500)
            key == Key.Backspace -> Symbol(PodiumSymbol.Backspace, ink, size = 20.dp)
            key == Key.Hide -> Symbol(PodiumSymbol.KeyboardHide, ink, size = 20.dp)
        }
    }
}

private val KeyboardMargin = 12.dp
private val KeyboardInset = 10.dp
private val KeyGap = 6.dp
private val KeyboardCorner = 26.dp

/** Narrower than this (the Wheel's column in landscape), fields use the phone's keyboard. */
private val MinKeyboardWidth = 300.dp

private const val CARET_BLINK_MS = 530L
private const val REPEAT_DELAY_MS = 420L
private const val REPEAT_EVERY_MS = 60L
