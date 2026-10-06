package app.podium.core.designsystem.symbol

import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.podium.core.designsystem.R
import java.util.concurrent.ConcurrentHashMap

/** The subset of Material Symbols Rounded bundled with Podium (third_party/FONTS.md). */
enum class PodiumSymbol(val codePoint: Int) {
    Play(0xe037), Pause(0xe034), Next(0xe044), Previous(0xe045), FastForward(0xe01f), Rewind(0xe020),
    Shuffle(0xe043), Repeat(0xe040), RepeatOne(0xe041), ChevronRight(0xe5cc), ChevronLeft(0xe5cb),
    Search(0xef7a), More(0xe5d3), Queue(0xe03d), Equalizer(0xe1b8), VolumeUp(0xe050), VolumeDown(0xe04d),
    Note(0xe405), Album(0xe019), Person(0xf0d3), Library(0xe030), Lock(0xe899), Error(0xf8b6),
    Offline(0xe2c1), Check(0xe668), Close(0xe5cd), DragHandle(0xe25d), Settings(0xe8b8),
    ShuffleOn(0xe9e1), PlaylistPlay(0xe05f), Headphones(0xf01f), Speaker(0xe32d), Favorite(0xe87d), Power(0xe8ac),

    // Added 2026-10-06 (lyrics, customization): outline only — not in the filled instance, so never
    // drawn with filled = true.
    Lyrics(0xec0b), Palette(0xe40a), Image(0xe3f4), TextFields(0xe262), Sparkle(0xe65f), Contrast(0xeb37),
}

/** Symbols the filled instance doesn't carry (drawn outlined whatever is asked). */
internal val OutlineOnly = setOf(PodiumSymbol.Lyrics, PodiumSymbol.Palette, PodiumSymbol.Image, PodiumSymbol.TextFields, PodiumSymbol.Sparkle, PodiumSymbol.Contrast)

private val families = ConcurrentHashMap<Triple<Int, Boolean, Int>, FontFamily>()

/**
 * Filled symbols come from a static instance of the same font (FILL 1, weight 600, opsz 32) with
 * the variable font's coincident hole/fill contours removed: drawn from the variable font, those
 * overlapping contours left a hairline seam inside large filled glyphs (third_party/FONTS.md).
 */
private val filledFamily = FontFamily(Font(R.font.podium_symbols_filled, FontWeight.SemiBold))

/**
 * A symbol whose weight tracks adjacent text, with FILL for "on"/selected states and optical size
 * per point size — the SF Symbols model, with an open font (ADR-010).
 */
private fun symbolFamily(weight: Int, filled: Boolean, opsz: Int): FontFamily =
    if (filled) filledFamily else families.getOrPut(Triple(weight, false, opsz)) {
        FontFamily(
            Font(
                R.font.podium_symbols,
                FontWeight(weight),
                variationSettings = FontVariation.Settings(
                    FontVariation.weight(weight),
                    FontVariation.Setting("FILL", if (filled) 1f else 0f),
                    FontVariation.Setting("opsz", opsz.toFloat()),
                    FontVariation.Setting("GRAD", 0f),
                ),
            ),
        )
    }

@Composable
fun Symbol(
    symbol: PodiumSymbol,
    color: Color,
    modifier: Modifier = Modifier,
    size: Dp = 24.dp,
    weight: Int = 500,
    filled: Boolean = false,
) {
    val density = LocalDensity.current
    val opsz = size.value.toInt().coerceIn(20, 48)
    BasicText(
        text = String(Character.toChars(symbol.codePoint)),
        modifier = modifier,
        style = TextStyle(
            fontFamily = symbolFamily(weight, filled && symbol !in OutlineOnly, opsz),
            fontSize = with(density) { size.toSp() },
            lineHeight = with(density) { size.toSp() },
            color = color,
            textAlign = TextAlign.Center,
        ),
    )
}
