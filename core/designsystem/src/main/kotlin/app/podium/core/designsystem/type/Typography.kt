package app.podium.core.designsystem.type

import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.sp
import app.podium.core.designsystem.R

private fun instrument(weight: Int) = Font(
    R.font.instrument_sans,
    FontWeight(weight),
    variationSettings = FontVariation.Settings(FontVariation.weight(weight)),
)

private fun inter(weight: Int) = Font(
    R.font.inter,
    FontWeight(weight),
    variationSettings = FontVariation.Settings(FontVariation.weight(weight)),
)

/** Podium's voice (ADR-010). Variable font, weights 400–700, tabular numerals via `tnum`. */
val InstrumentSans = FontFamily(instrument(400), instrument(500), instrument(600), instrument(700))

/** Coverage fallback for metadata Instrument Sans can't render (Greek, Cyrillic, full Vietnamese…). */
val Inter = FontFamily(inter(400), inter(500), inter(600), inter(700))

/**
 * Picks the family for a whole string: Instrument Sans when it covers every character, otherwise
 * Inter (whose own gaps fall to the system Noto chain). Never mixes families inside a word.
 */
fun familyFor(text: String): FontFamily {
    var i = 0
    while (i < text.length) {
        val cp = text.codePointAt(i)
        if (cp >= 0x80 && !InstrumentSansCoverage.covers(cp)) return Inter
        i += Character.charCount(cp)
    }
    return InstrumentSans
}

/** The type scale (design-system.md §3.2). */
@Immutable
data class PodiumType(
    val largeTitle: TextStyle,
    val title: TextStyle,
    val nowPlayingTitle: TextStyle,
    val nowPlayingSubtitle: TextStyle,
    val row: TextStyle,
    val rowFocused: TextStyle,
    val rowSecondary: TextStyle,
    val body: TextStyle,
    val sectionHeader: TextStyle,
    val caption: TextStyle,
    val footnote: TextStyle,
    val indexGlyph: TextStyle,
    val wheelLegend: TextStyle,
)

private fun style(size: Int, lineHeight: Int, weight: Int, tracking: Double = 0.0, tabular: Boolean = false) = TextStyle(
    fontFamily = InstrumentSans,
    fontSize = size.sp,
    lineHeight = lineHeight.sp,
    fontWeight = FontWeight(weight),
    letterSpacing = tracking.sp,
    fontFeatureSettings = if (tabular) "tnum" else null,
)

val DefaultType = PodiumType(
    largeTitle = style(30, 36, 700, -0.3),
    title = style(17, 22, 600, -0.1),
    nowPlayingTitle = style(22, 28, 600, -0.2),
    nowPlayingSubtitle = style(17, 22, 400),
    row = style(17, 22, 500, -0.1),
    rowFocused = style(17, 22, 600, -0.1),
    rowSecondary = style(14, 18, 400),
    body = style(15, 22, 400),
    sectionHeader = style(15, 20, 600),
    caption = style(13, 16, 500, tabular = true),
    footnote = style(13, 18, 500, tabular = true),
    indexGlyph = style(56, 56, 700),
    wheelLegend = style(12, 14, 600, 1.0),
)

/**
 * Text with Podium's per-string family selection. Use for anything that may contain metadata
 * (titles, artists); for Podium-authored strings plain BasicText with a scale style is enough.
 */
@Composable
fun PodiumText(
    text: String,
    style: TextStyle,
    color: Color,
    modifier: Modifier = Modifier,
    maxLines: Int = 1,
    textAlign: TextAlign? = null,
    fontSize: TextUnit = TextUnit.Unspecified,
) {
    val family = remember(text) { familyFor(text) }
    BasicText(
        text = text,
        modifier = modifier,
        style = style.copy(
            color = color,
            fontFamily = family,
            textAlign = textAlign ?: style.textAlign,
            fontSize = if (fontSize != TextUnit.Unspecified) fontSize else style.fontSize,
        ),
        maxLines = maxLines,
        overflow = TextOverflow.Ellipsis,
    )
}
