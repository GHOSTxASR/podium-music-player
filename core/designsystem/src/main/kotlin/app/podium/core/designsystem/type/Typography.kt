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

private fun static(res: Int, weight: Int = 400) = Font(res, FontWeight(weight))

/** Instrument Sans Italic (OFL): Podium's own face, slanted. */
private val InstrumentSansItalic = FontFamily((400..700 step 100).map { variable(R.font.instrument_sans_italic, it) })

/** Tinos (OFL, Times New Roman's metric twin): the Times display font. */
private val Tinos = FontFamily(static(R.font.tinos_regular, 400), static(R.font.tinos_bold, 700))

/**
 * Tinos Italic, alone in its family: declared upright so it's chosen as it is (never slanted
 * twice); the scale's heavier weights are drawn from it.
 */
private val TinosItalic = FontFamily(static(R.font.tinos_italic))

/** Playfair Display (OFL), high contrast: the Elegant display font, and its italic. */
private val Playfair = FontFamily((400..700 step 100).map { variable(R.font.playfair_display, it) })
private val PlayfairItalic = FontFamily((400..700 step 100).map { variable(R.font.playfair_display_italic, it) })

/** Courier Prime (OFL): the Typewriter font, and its italic (lyrics). */
private val CourierPrime = FontFamily(static(R.font.courier_prime_regular, 400), static(R.font.courier_prime_bold, 700))
private val CourierPrimeItalic = FontFamily(static(R.font.courier_prime_italic))

/** Nunito (OFL), rounded: the Rounded display font. */
private val Nunito = FontFamily((400..700 step 100).map { variable(R.font.nunito, it) })

/** Caveat (OFL), handwriting: the Handwritten display font. */
private val Caveat = FontFamily((400..700 step 100).map { variable(R.font.caveat, it) })

/** Dancing Script (OFL), a bouncy cursive: the Script display font. */
private val DancingScript = FontFamily((400..700 step 100).map { variable(R.font.dancing_script, it) })

/** Pacifico (OFL), round and bubbly cursive: the Bubbly display font. */
private val Pacifico = FontFamily(static(R.font.pacifico))

/** Grenze Gotisch (OFL), a readable blackletter: the Gothic display font. */
private val GrenzeGotisch = FontFamily((400..700 step 100).map { variable(R.font.grenze_gotisch, it) })

// Lyrics only: faces that read at the lyric's size but not in a list's rows.
private val Unifraktur = FontFamily(static(R.font.unifraktur_maguntia))
private val Pirata = FontFamily(static(R.font.pirata_one))
private val Jacquard = FontFamily(static(R.font.jacquard_24))
private val GreatVibes = FontFamily(static(R.font.great_vibes))
private val Parisienne = FontFamily(static(R.font.parisienne))
private val Sacramento = FontFamily(static(R.font.sacramento))

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
    // D-50: faces that look different.
    ITALIC("Italic", "Instrument Sans, slanted"),
    TIMES("Times", "Tinos, the newspaper serif"),
    TIMES_ITALIC("Times italic", "Tinos, slanted"),
    ELEGANT("Elegant", "Playfair Display, high contrast"),
    ELEGANT_ITALIC("Elegant italic", "Playfair Display, slanted"),
    TYPEWRITER("Typewriter", "Courier Prime, typed"),
    ROUNDED("Rounded", "Nunito, soft corners"),
    HANDWRITTEN("Handwritten", "Caveat, a quick hand"),
    SCRIPT("Script", "Dancing Script, cursive"),
    BUBBLY("Bubbly", "Pacifico, round cursive"),
    GOTHIC("Gothic", "Grenze Gotisch, blackletter"),
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
    /** False for a lyrics face set as a specimen in Settings ([LyricsTypeface.specimen]): no display font of its own. */
    private val isDisplayFont: Boolean = true,
) {
    /** The family for a whole string in this face, or Inter when the face can't set all of it. */
    fun familyFor(text: String): FontFamily {
        if (isDisplayFont && font == DisplayFont.CLASSIC) return app.podium.core.designsystem.type.familyFor(text)
        if (isDisplayFont && font == DisplayFont.CLEAN) return Inter
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
        if (isDisplayFont && font == DisplayFont.CLASSIC) return@lazy DefaultType
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
                DisplayFont.ITALIC -> TypographyPreset(font, InstrumentSansItalic, InstrumentSansItalicCoverage::covers, 1f, 0f)
                // Serifs set a little smaller on the body: a touch larger keeps the x-height.
                DisplayFont.TIMES -> TypographyPreset(font, Tinos, TinosCoverage::covers, 1.08f, 0f)
                DisplayFont.TIMES_ITALIC -> TypographyPreset(font, TinosItalic, TinosCoverage::covers, 1.08f, 0f)
                DisplayFont.ELEGANT -> TypographyPreset(font, Playfair, PlayfairCoverage::covers, 1.02f, 0f)
                DisplayFont.ELEGANT_ITALIC -> TypographyPreset(font, PlayfairItalic, PlayfairCoverage::covers, 1.04f, 0f)
                DisplayFont.TYPEWRITER -> TypographyPreset(font, CourierPrime, CourierPrimeCoverage::covers, 0.95f, 0f)
                DisplayFont.ROUNDED -> TypographyPreset(font, Nunito, NunitoCoverage::covers, 1f, 0f)
                // Handwriting and scripts are small for their size: larger, with a little air.
                DisplayFont.HANDWRITTEN -> TypographyPreset(font, Caveat, CaveatCoverage::covers, 1.28f, 0.1f)
                DisplayFont.SCRIPT -> TypographyPreset(font, DancingScript, DancingScriptCoverage::covers, 1.2f, 0.1f)
                DisplayFont.BUBBLY -> TypographyPreset(font, Pacifico, PacificoCoverage::covers, 0.92f, 0.2f)
                DisplayFont.GOTHIC -> TypographyPreset(font, GrenzeGotisch, GrenzeGotischCoverage::covers, 1.1f, 0.1f)
            }
        }

        fun of(font: DisplayFont): TypographyPreset = presets.getValue(font)

        /** A lyrics-only face as a type scale, so a list can set a name in it (Settings ▸ Lyrics font). */
        internal fun specimen(family: FontFamily, covers: (Int) -> Boolean, scale: Float): TypographyPreset =
            TypographyPreset(DisplayFont.CLASSIC, family, covers, scale, 0f, isDisplayFont = false)
    }
}

/**
 * The lyrics screen's typeface (D-50; Settings ▸ Appearance ▸ Virtual display ▸ Lyrics font): the
 * display's own font, any display font, or a face that only reads at a lyric's size — calligraphy,
 * thin scripts, blackletter. Every face is bundled and SIL OFL 1.1 (third_party/FONTS.md).
 */
enum class LyricsFont(val label: String, val description: String, internal val display: DisplayFont? = null) {
    SAME_AS_DISPLAY("Same as the display", "Whatever the display uses"),
    CLASSIC("Classic", "Instrument Sans, Podium's own", DisplayFont.CLASSIC),
    ITALIC("Italic", "Instrument Sans, slanted", DisplayFont.ITALIC),
    TIMES("Times", "Tinos, the newspaper serif", DisplayFont.TIMES),
    TIMES_ITALIC("Times italic", "Tinos, slanted", DisplayFont.TIMES_ITALIC),
    ELEGANT("Elegant", "Playfair Display, high contrast", DisplayFont.ELEGANT),
    ELEGANT_ITALIC("Elegant italic", "Playfair Display, slanted", DisplayFont.ELEGANT_ITALIC),
    TYPEWRITER("Typewriter", "Courier Prime, typed", DisplayFont.TYPEWRITER),
    TYPEWRITER_ITALIC("Typewriter italic", "Courier Prime, slanted"),
    ROUNDED("Rounded", "Nunito, soft corners", DisplayFont.ROUNDED),
    MONO("Mono", "JetBrains Mono, fixed width", DisplayFont.MONO),
    PIXEL("Pixel", "Pixelify Sans, on a pixel grid", DisplayFont.PIXEL),
    HANDWRITTEN("Handwritten", "Caveat, a quick hand", DisplayFont.HANDWRITTEN),
    SCRIPT("Script", "Dancing Script, cursive", DisplayFont.SCRIPT),
    BUBBLY("Bubbly", "Pacifico, round cursive", DisplayFont.BUBBLY),
    CALLIGRAPHY("Calligraphy", "Great Vibes, formal script"),
    FRENCH_SCRIPT("French script", "Parisienne, light and slanted"),
    LOOPY("Loopy", "Sacramento, thin and looped"),
    GOTHIC("Gothic", "Grenze Gotisch, blackletter", DisplayFont.GOTHIC),
    FRAKTUR("Fraktur", "UnifrakturMaguntia, old German blackletter"),
    PIRATA("Pirata", "Pirata One, sharp blackletter"),
    JACQUARD("Jacquard", "Jacquard 24, blackletter on a pixel grid"),
}

/** How a [LyricsFont] sets the lyric: its family per string, falling back to Inter like the display's. */
@Immutable
class LyricsTypeface private constructor(val font: LyricsFont, private val display: TypographyPreset) {
    /** This face as a type scale: the display font's own, or (lyrics-only faces) a specimen of it. */
    val specimen: TypographyPreset by lazy {
        font.display?.let(TypographyPreset::of) ?: lyricsOnly(font)?.let { (family, coverage) ->
            TypographyPreset.specimen(family, coverage::covers, LYRIC_FACE_SCALE)
        } ?: display
    }

    fun familyFor(text: String): FontFamily = specimen.familyFor(text)

    companion object {
        fun of(font: LyricsFont, display: TypographyPreset) = LyricsTypeface(font, display)
    }
}

/** The lyrics-only faces: no display font of their own. */
private fun lyricsOnly(font: LyricsFont): Pair<FontFamily, FontCoverage>? = when (font) {
    LyricsFont.TYPEWRITER_ITALIC -> CourierPrimeItalic to CourierPrimeCoverage
    LyricsFont.CALLIGRAPHY -> GreatVibes to GreatVibesCoverage
    LyricsFont.FRENCH_SCRIPT -> Parisienne to ParisienneCoverage
    LyricsFont.LOOPY -> Sacramento to SacramentoCoverage
    LyricsFont.FRAKTUR -> Unifraktur to UnifrakturCoverage
    LyricsFont.PIRATA -> Pirata to PirataCoverage
    LyricsFont.JACQUARD -> Jacquard to JacquardCoverage
    else -> null
}

/** Scripts and blackletter are small for their size: their specimens are set a little larger. */
private const val LYRIC_FACE_SCALE = 1.2f

/** The lyrics typeface in use (provided by PodiumTheme). */
val LocalLyricsTypeface = staticCompositionLocalOf { LyricsTypeface.of(LyricsFont.SAME_AS_DISPLAY, TypographyPreset.of(DisplayFont.CLASSIC)) }

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
