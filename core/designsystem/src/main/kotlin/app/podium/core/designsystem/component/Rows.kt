package app.podium.core.designsystem.component

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.layout
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.offset
import app.podium.core.designsystem.artwork.ArtworkImage
import app.podium.core.designsystem.symbol.PodiumSymbol
import app.podium.core.designsystem.symbol.Symbol
import app.podium.core.designsystem.theme.PodiumTheme
import app.podium.core.designsystem.theme.Spacing
import app.podium.core.designsystem.type.PodiumText

/** Text colour for a row: white on the Solid-tier focus bar, otherwise the given colour. */
@Composable
private fun rowColor(focused: Boolean, normal: Color): Color =
    if (focused && isSolidFocus()) PodiumTheme.colors.onHighlight else normal

/**
 * Industrial rows (D-29) are lit rather than highlighted: the focused row's text is bright, the
 * rest steps back a level. Glass rows keep their colours and rely on the lens.
 */
@Composable
private fun primaryText(focused: Boolean, normal: Color): Color {
    val colors = PodiumTheme.colors
    return if (colors.isIndustrial) (if (focused) colors.labelPrimary else colors.labelSecondary) else rowColor(focused, normal)
}

@Composable
private fun secondaryText(focused: Boolean, normal: Color): Color {
    val colors = PodiumTheme.colors
    return if (colors.isIndustrial) (if (focused) colors.labelSecondary else colors.labelTertiary) else rowColor(focused, normal)
}

/** On Carbon the focused text glows faintly, like an illuminated legend. */
@Composable
fun illuminated(style: TextStyle, focused: Boolean): TextStyle {
    val colors = PodiumTheme.colors
    return if (focused && colors.isIndustrial && colors.isDark) {
        style.copy(shadow = Shadow(color = colors.labelPrimary.copy(alpha = 0.42f), offset = Offset.Zero, blurRadius = 18f))
    } else style
}

/** A menu row (design-system.md §6.3): label, optional value, chevron. */
@Composable
fun MenuRow(
    label: String,
    focused: Boolean,
    modifier: Modifier = Modifier,
    value: String? = null,
    showChevron: Boolean = true,
    enabled: Boolean = true,
    leading: PodiumSymbol? = null,
    /** Custom leading content (e.g. a colour swatch); used when [leading] is null. */
    leadingContent: (@Composable () -> Unit)? = null,
    /** The current choice in a picker: a check replaces the chevron. */
    selected: Boolean = false,
) {
    val colors = PodiumTheme.colors
    val type = PodiumTheme.type
    val primary = if (enabled) primaryText(focused, colors.labelPrimary) else colors.labelTertiary
    val secondary = secondaryText(focused, colors.labelSecondary)
    Row(
        modifier
            .fillMaxWidth()
            .heightIn(min = Spacing.menuRow)
            .padding(horizontal = LocalRowPadding.current),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (leading != null) {
            Symbol(leading, secondary, size = 20.dp, weight = if (focused) 600 else 500)
            Spacer(Modifier.width(Spacing.m))
        } else if (leadingContent != null) {
            leadingContent()
            Spacer(Modifier.width(Spacing.m))
        }
        LabelAndValue(
            label = { PodiumText(label, illuminated(if (focused) type.rowFocused else type.row, focused), primary, Modifier.scrollsWhenFocused(focused)) },
            value = value?.let { v -> { PodiumText(v, type.footnote, secondary) } },
            modifier = Modifier.weight(1f),
        )
        if (selected) {
            Spacer(Modifier.width(Spacing.s))
            Symbol(PodiumSymbol.Check, if (focused) primary else colors.highlightText, size = 20.dp, weight = 600)
        } else if (showChevron) {
            Spacer(Modifier.width(Spacing.s))
            Symbol(
                PodiumSymbol.ChevronRight,
                if (focused) primary else colors.labelTertiary,
                size = 18.dp,
                weight = if (focused) 600 else 500,
            )
        }
    }
}

/**
 * A row's label and its value, side by side. The label keeps its room and a long value gives way
 * first: the value gets what the label leaves (up to [ValueMax]) and ellipsizes; only when the
 * label alone would crowd the value below [ValueMin] does the label give way too. (A plain Row
 * measures the fixed-width value first, which squeezed labels to "Vi…" in the narrow paper column.)
 */
@Composable
private fun LabelAndValue(label: @Composable () -> Unit, value: (@Composable () -> Unit)?, modifier: Modifier = Modifier) {
    Layout(content = { label(); value?.invoke() }, modifier = modifier) { measurables, constraints ->
        val width = constraints.maxWidth
        val labelM = measurables[0]
        val valueM = measurables.getOrNull(1)
        if (valueM == null || width == Constraints.Infinity) {
            val l = labelM.measure(constraints.copy(minWidth = 0))
            val v = valueM?.measure(Constraints())
            val h = maxOf(l.height, v?.height ?: 0)
            return@Layout layout(if (width == Constraints.Infinity) l.width + (v?.width ?: 0) else width, h) {
                l.placeRelative(0, (h - l.height) / 2)
                v?.placeRelative(l.width, (h - v.height) / 2)
            }
        }
        val gap = Spacing.s.roundToPx()
        val valueWant = minOf(valueM.maxIntrinsicWidth(constraints.maxHeight), ValueMax.roundToPx())
        val labelWant = labelM.maxIntrinsicWidth(constraints.maxHeight)
        val valueFloor = minOf(valueWant, ValueMin.roundToPx())
        val labelWidth = minOf(labelWant, (width - gap - valueFloor).coerceAtLeast(0))
        val valueWidth = minOf(valueWant, (width - gap - labelWidth).coerceAtLeast(0))
        // The label takes all the room the value doesn't need, so a focused title can scroll in it.
        val labelBox = (width - (if (valueWidth > 0) gap + valueWidth else 0)).coerceAtLeast(0)
        val l = labelM.measure(Constraints.fixedWidth(labelBox).copy(minHeight = 0, maxHeight = constraints.maxHeight))
        val v = valueM.measure(Constraints(maxWidth = valueWidth, maxHeight = constraints.maxHeight))
        val h = maxOf(l.height, v.height).coerceIn(constraints.minHeight, constraints.maxHeight)
        layout(width, h) {
            l.placeRelative(0, (h - l.height) / 2)
            if (valueWidth > 0) v.placeRelative(width - v.width, (h - v.height) / 2)
        }
    }
}

/** A row's value never takes more than this… */
private val ValueMax = 132.dp

/** …and keeps at least this much (or all it needs, if less) before the label gives way. */
private val ValueMin = 56.dp

/** A track row: artwork, title over artist, trailing duration. */
@Composable
fun TrackRow(
    title: String,
    subtitle: String,
    focused: Boolean,
    artworkUri: String?,
    modifier: Modifier = Modifier,
    trailing: String? = null,
    note: String? = null,
    /** The song that's playing: accent title and a playing glyph in place of [trailing]. */
    active: Boolean = false,
    /** Custom trailing content (e.g. a drag handle), drawn after [trailing]. */
    trailingContent: (@Composable () -> Unit)? = null,
    /** Album track lists show the track number instead of repeating the same artwork. */
    number: Int? = null,
    roundArtwork: Boolean = false,
) {
    val colors = PodiumTheme.colors
    val type = PodiumTheme.type
    Row(
        modifier
            .fillMaxWidth()
            .heightIn(min = Spacing.trackRow)
            .padding(horizontal = LocalRowPadding.current, vertical = Spacing.s),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (number != null) {
            Box(Modifier.width(28.dp), contentAlignment = Alignment.CenterStart) {
                PodiumText(number.toString(), type.caption, secondaryText(focused, colors.labelTertiary))
            }
        } else {
            ArtworkImage(artworkUri, 44.dp, fallbackText = title, round = roundArtwork)
        }
        Spacer(Modifier.width(Spacing.m))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.Center) {
            PodiumText(
                title,
                illuminated(if (focused || active) type.rowFocused else type.row, focused),
                if (active && !focused) colors.highlightText else primaryText(focused, colors.labelPrimary),
                Modifier.scrollsWhenFocused(focused),
            )
            PodiumText(subtitle, type.rowSecondary, secondaryText(focused, colors.labelSecondary))
            if (note != null) PodiumText(note, type.footnote, secondaryText(focused, colors.labelTertiary))
        }
        if (active) {
            Spacer(Modifier.width(Spacing.s))
            Symbol(PodiumSymbol.Equalizer, rowColor(focused, colors.highlightText), size = 20.dp, weight = 600)
        } else if (trailing != null) {
            PodiumText(trailing, type.caption, secondaryText(focused, colors.labelSecondary), Modifier.padding(start = Spacing.s))
        }
        trailingContent?.invoke()
    }
}

/** List section header, title case, never all caps. */
@Composable
fun SectionHeader(text: String, modifier: Modifier = Modifier) {
    Box(modifier.fillMaxWidth().padding(start = LocalRowPadding.current, end = LocalRowPadding.current, top = Spacing.xl, bottom = Spacing.xs)) {
        BasicText(text, style = PodiumTheme.type.sectionHeader.copy(color = PodiumTheme.colors.labelSecondary))
    }
}

/** "3:07" / "1:02:15" with tabular figures handled by the caption style. */
fun formatDuration(ms: Long?): String? {
    if (ms == null || ms < 0) return null
    val totalSeconds = ms / 1000
    val h = totalSeconds / 3600
    val m = (totalSeconds % 3600) / 60
    val s = totalSeconds % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
}

/**
 * The focused row's text scrolls slowly when it doesn't fit, as on the original iPod, so long
 * titles are readable without widening the column. Other rows ellipsize. Off with reduced motion.
 *
 * A marquee clips to its own bounds. Clipped at the text's box, that cut the focused text's glow
 * (Carbon) into a hard rectangle and could shave a glyph that reaches outside its line box (an
 * accent, a tall script, a display font with deep descenders), and a long title stopped mid-letter
 * at the right edge. So the scrolling text borrows [TextBleed] of room on every side — for drawing
 * only, the layout keeps the text's own size — and its ends fade instead of cutting.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun Modifier.scrollsWhenFocused(focused: Boolean): Modifier =
    if (focused && !PodiumTheme.motion.reduced) {
        this
            .layout { measurable, constraints ->
                val bleed = TextBleed.roundToPx()
                val placeable = measurable.measure(constraints.offset(horizontal = bleed * 2, vertical = bleed * 2))
                layout((placeable.width - bleed * 2).coerceAtLeast(0), (placeable.height - bleed * 2).coerceAtLeast(0)) {
                    placeable.place(-bleed, -bleed)
                }
            }
            .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
            .drawWithContent {
                drawContent()
                val bleed = TextBleed.toPx()
                val fade = bleed + TextEdgeFade.toPx()
                drawRect(
                    Brush.horizontalGradient(0f to Color.Transparent, 1f to Color.Black, startX = 0f, endX = bleed),
                    size = Size(bleed, size.height),
                    blendMode = BlendMode.DstIn,
                )
                drawRect(
                    Brush.horizontalGradient(0f to Color.Black, 1f to Color.Transparent, startX = size.width - fade, endX = size.width),
                    topLeft = Offset(size.width - fade, 0f),
                    size = Size(fade, size.height),
                    blendMode = BlendMode.DstIn,
                )
            }
            .basicMarquee(initialDelayMillis = 900, repeatDelayMillis = 1_800)
            .padding(TextBleed)
    } else this

/** Room the scrolling title may draw beyond its box (covers Carbon's glow, ~7 dp). */
private val TextBleed = 8.dp

/** How far into the box the scrolling title's right end fades. */
private val TextEdgeFade = 4.dp


/** Cover, title and quiet details at the top of an album, artist or playlist page. Not focusable. */
@Composable
fun DetailHeader(artworkUri: String?, title: String, subtitle: String, details: List<String>, round: Boolean = false) {
    val colors = PodiumTheme.colors
    val type = PodiumTheme.type
    // Sized for the paper's column (D-30): a long title wraps at words, never mid-word.
    Row(
        Modifier.fillMaxWidth().padding(horizontal = LocalRowPadding.current, vertical = Spacing.m),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ArtworkImage(artworkUri, 64.dp, fallbackText = title, round = round)
        Spacer(Modifier.width(Spacing.m))
        Column(Modifier.weight(1f)) {
            PodiumText(title, type.rowFocused, colors.labelPrimary, Modifier.semantics { heading() }, maxLines = 2)
            PodiumText(subtitle, type.rowSecondary, colors.labelSecondary)
            details.forEach { PodiumText(it, type.footnote, colors.labelTertiary) }
        }
    }
}

