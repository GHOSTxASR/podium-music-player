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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
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
        PodiumText(label, illuminated(if (focused) type.rowFocused else type.row, focused), primary, Modifier.weight(1f).scrollsWhenFocused(focused))
        if (value != null) {
            // The label keeps its room; a long value gives way first.
            PodiumText(value, type.footnote, secondary, Modifier.padding(start = Spacing.s).widthIn(max = 132.dp))
        }
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
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun Modifier.scrollsWhenFocused(focused: Boolean): Modifier =
    if (focused && !PodiumTheme.motion.reduced) basicMarquee(initialDelayMillis = 900, repeatDelayMillis = 1_800) else this

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

