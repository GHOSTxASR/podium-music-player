package app.podium.core.designsystem.type

import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.podium.core.designsystem.R
import app.podium.core.designsystem.theme.LocalDisplaySurface
import app.podium.core.designsystem.theme.LocalPodiumColors

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

private fun instrumentCondensed(weight: Int) = Font(
    R.font.instrument_sans,
    FontWeight(weight),
    variationSettings = FontVariation.Settings(FontVariation.weight(weight), FontVariation.width(75f)),
)

private fun variable(res: Int, weight: Int) = Font(
    res,
    FontWeight(weight),
    variationSettings = FontVariation.Settings(FontVariation.weight(weight)),
)

/** Instrument Sans on its narrowest width (the font's own `wdth` 75): the Condensed display font. */
private val InstrumentSansCondensed = FontFamily(instrumentCondensed(400), instrumentCondensed(500), instrumentCondensed(600), instrumentCondensed(700))

/** Space Grotesk (OFL): the Industrial display font. */
private val SpaceGrotesk = FontFamily((400..700 step 100).map { variable(R.font.space_grotesk, it) })

/** JetBrains Mono (OFL): the Mono display font. */
private val JetBrainsMono = FontFamily((400..700 step 100).map { variable(R.font.jetbrains_mono, it) })

/** Pixelify Sans (OFL): the Pixel display font, drawn on a pixel grid. */
private val PixelifySans = FontFamily((400..700 step 100).map { variable(R.font.pixelify_sans, it) })

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

/** The display font in use (provided by PodiumTheme). */
val LocalTypographyPreset = staticCompositionLocalOf { TypographyPreset.of(DisplayFont.CLASSIC) }

/**
 * The virtual display's typeface (PODIUM_CUSTOMIZATION.md §3; Settings ▸ Appearance ▸ Virtual
 * display ▸ Font). Applies to everything on the display, never to the body (the Wheel's MENU legend
 * stays Instrument Sans). Every face is bundled and SIL OFL 1.1 (third_party/FONTS.md).
 */
enum class DisplayFont(val label: String, val description: String) {
    CLASSIC("Classic", "Instrument Sans, Podium's own"),
    CLEAN("Clean", "Inter, neutral and even"),
    INDUSTRIAL("Industrial", "Space Grotesk, technical"),
    MONO("Mono", "JetBrains Mono, fixed width"),
    PIXEL("Pixel", "Pixelify Sans, on a pixel grid"),
    CONDENSED("Condensed", "Instrument Sans, narrow"),
}

/**
 * How a [DisplayFont] sets Podium's type scale: the family, which characters it can set (a string
 * it can't set entirely falls back to Inter, never mixing faces inside a word), and a size factor
 * so x-heights stay close to Classic's (the rhythm of rows and the paper stay the same).
 */
@Immutable
class TypographyPreset private constructor(
    val font: DisplayFont,
    private val family: FontFamily,
    private val covers: (Int) -> Boolean,
    /** Applied to every size and line height of the scale. */
    val sizeScale: Float,
    /** Added to every style's tracking, in sp. */
    private val trackingDelta: Float,
) {
    /** The family for a whole string in this face, or Inter when the face can't set all of it. */
    fun familyFor(text: String): FontFamily {
        if (font == DisplayFont.CLASSIC) return app.podium.core.designsystem.type.familyFor(text)
        if (font == DisplayFont.CLEAN) return Inter
        var i = 0
        while (i < text.length) {
            val cp = text.codePointAt(i)
            if (cp >= 0x20 && !covers(cp)) return Inter
            i += Character.charCount(cp)
        }
        return family
    }

    /** Podium's type scale in this face. The Wheel's legend is on the body and stays Classic. */
    val type: PodiumType by lazy {
        if (font == DisplayFont.CLASSIC) return@lazy DefaultType
        fun TextStyle.inFace() = copy(
            fontFamily = family,
            fontSize = fontSize * sizeScale,
            lineHeight = lineHeight * sizeScale,
            letterSpacing = (letterSpacing.value + trackingDelta).sp,
        )
        with(DefaultType) {
            PodiumType(
                largeTitle = largeTitle.inFace(),
                title = title.inFace(),
                nowPlayingTitle = nowPlayingTitle.inFace(),
                nowPlayingSubtitle = nowPlayingSubtitle.inFace(),
                row = row.inFace(),
                rowFocused = rowFocused.inFace(),
                rowSecondary = rowSecondary.inFace(),
                body = body.inFace(),
                sectionHeader = sectionHeader.inFace(),
                caption = caption.inFace(),
                footnote = footnote.inFace(),
                indexGlyph = indexGlyph.inFace(),
                wheelLegend = wheelLegend,
            )
        }
    }

    companion object {
        private val presets = DisplayFont.entries.associateWith { font ->
            when (font) {
                DisplayFont.CLASSIC -> TypographyPreset(font, InstrumentSans, InstrumentSansCoverage::covers, 1f, 0f)
                DisplayFont.CLEAN -> TypographyPreset(font, Inter, { true }, 0.95f, 0f)
                DisplayFont.INDUSTRIAL -> TypographyPreset(font, SpaceGrotesk, SpaceGroteskCoverage::covers, 1.03f, 0f)
                // Fixed width runs long: a touch smaller, and no negative tracking.
                DisplayFont.MONO -> TypographyPreset(font, JetBrainsMono, JetBrainsMonoCoverage::covers, 0.9f, 0.1f)
                // Pixel faces read best a little larger, with air between the letters.
                DisplayFont.PIXEL -> TypographyPreset(font, PixelifySans, PixelifySansCoverage::covers, 1.1f, 0.3f)
                DisplayFont.CONDENSED -> TypographyPreset(font, InstrumentSansCondensed, InstrumentSansCoverage::covers, 1.04f, 0.1f)
            }
        }

        fun of(font: DisplayFont): TypographyPreset = presets.getValue(font)
    }
}

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
    val preset = LocalTypographyPreset.current
    val family = remember(text, preset) { preset.familyFor(text) }
    // Over a background picture (D-41), text carries a soft halo of the display's colour.
    val halo = if (LocalDisplaySurface.current?.image != null && style.shadow == null) {
        Shadow(LocalPodiumColors.current.canvas.copy(alpha = 0.9f), Offset.Zero, blurRadius = with(LocalDensity.current) { 4.dp.toPx() })
    } else style.shadow
    BasicText(
        text = text,
        modifier = modifier,
        style = style.copy(
            shadow = halo,
            color = color,
            fontFamily = family,
            textAlign = textAlign ?: style.textAlign,
            fontSize = if (fontSize != TextUnit.Unspecified) fontSize else style.fontSize,
        ),
        maxLines = maxLines,
        overflow = TextOverflow.Ellipsis,
    )
}
