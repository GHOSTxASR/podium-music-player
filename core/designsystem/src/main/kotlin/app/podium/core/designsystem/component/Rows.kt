package app.podium.core.designsystem.component

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
import androidx.compose.ui.graphics.Color
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
    val primary = rowColor(focused, if (enabled) colors.labelPrimary else colors.labelTertiary)
    val secondary = rowColor(focused, colors.labelSecondary)
    Row(
        modifier
            .fillMaxWidth()
            .heightIn(min = Spacing.menuRow)
            .padding(horizontal = Spacing.gutter + Spacing.xs),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (leading != null) {
            Symbol(leading, secondary, size = 20.dp, weight = if (focused) 600 else 500)
            Spacer(Modifier.width(Spacing.m))
        } else if (leadingContent != null) {
            leadingContent()
            Spacer(Modifier.width(Spacing.m))
        }
        PodiumText(label, if (focused) type.rowFocused else type.row, primary, Modifier.weight(1f))
        if (value != null) {
            PodiumText(value, type.footnote, secondary, Modifier.padding(start = Spacing.s))
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
) {
    val colors = PodiumTheme.colors
    val type = PodiumTheme.type
    Row(
        modifier
            .fillMaxWidth()
            .heightIn(min = Spacing.trackRow)
            .padding(horizontal = Spacing.gutter + Spacing.xs, vertical = Spacing.s),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ArtworkImage(artworkUri, 44.dp, fallbackText = title)
        Spacer(Modifier.width(Spacing.m))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.Center) {
            PodiumText(
                title,
                if (focused || active) type.rowFocused else type.row,
                rowColor(focused, if (active) colors.highlightText else colors.labelPrimary),
            )
            PodiumText(subtitle, type.rowSecondary, rowColor(focused, colors.labelSecondary))
            if (note != null) PodiumText(note, type.footnote, rowColor(focused, colors.labelTertiary))
        }
        if (active) {
            Spacer(Modifier.width(Spacing.s))
            Symbol(PodiumSymbol.Equalizer, rowColor(focused, colors.highlightText), size = 20.dp, weight = 600)
        } else if (trailing != null) {
            PodiumText(trailing, type.caption, rowColor(focused, colors.labelSecondary), Modifier.padding(start = Spacing.s))
        }
    }
}

/** List section header, title case, never all caps. */
@Composable
fun SectionHeader(text: String, modifier: Modifier = Modifier) {
    Box(modifier.fillMaxWidth().padding(start = Spacing.gutter + Spacing.xs, end = Spacing.gutter, top = Spacing.xl, bottom = Spacing.xs)) {
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
