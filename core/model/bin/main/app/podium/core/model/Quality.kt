package app.podium.core.model

import java.util.Locale

enum class Codec(val displayName: String, val isLossless: Boolean) {
    FLAC("FLAC", true),
    ALAC("ALAC", true),
    PCM("PCM", true),
    AAC("AAC", false),
    OPUS("Opus", false),
    MP3("MP3", false),
    VORBIS("Vorbis", false),
    AC3("AC-3", false),
    EAC3("E-AC-3", false),
    OTHER("Other", false),
}

/**
 * Facts about one audio stream. Every field is nullable: null means "not stated / not measured",
 * never a guess. Bit depth is only meaningful for lossless/PCM.
 */
data class AudioQuality(
    val codec: Codec? = null,
    val container: String? = null,
    val bitrateKbps: Int? = null,
    val sampleRateHz: Int? = null,
    val bitDepth: Int? = null,
    val channels: Int? = null,
)

/** Where a quality statement came from. */
enum class QualityProvenance {
    /** What the source says this stream is. A claim, not a measurement. */
    SOURCE_CLAIMED_QUALITY,

    /** What the decoder measured from the actual media. */
    ACTUAL_MEDIA_QUALITY,

    /** Neither is known. */
    UNKNOWN,
}

/**
 * The two facts about a playing stream, kept separate (PLAYBACK_TARGETS.md §4).
 * Nothing here is ever merged silently: a claim never overwrites a measurement.
 */
data class QualityReport(
    val sourceClaimed: AudioQuality? = null,
    val actualMedia: AudioQuality? = null,
) {
    /** Which fact the UI label is based on. Labels use the actual media only. */
    val provenance: QualityProvenance
        get() = when {
            actualMedia != null -> QualityProvenance.ACTUAL_MEDIA_QUALITY
            sourceClaimed != null -> QualityProvenance.SOURCE_CLAIMED_QUALITY
            else -> QualityProvenance.UNKNOWN
        }

    /** True when the source claimed a codec and the decoder found a different one. */
    val codecMismatch: Boolean
        get() {
            val claimed = sourceClaimed?.codec ?: return false
            val actual = actualMedia?.codec ?: return false
            return claimed != actual
        }

    /** The only legitimate basis for calling the stream lossless: a measured lossless codec. */
    val isVerifiedLossless: Boolean get() = actualMedia?.codec?.isLossless == true
}

/**
 * The single formatter for quality labels. Natural case, no all-caps, no dot-joined meta strings
 * (design-system.md §6.10). Returns null when there is nothing honest to say.
 */
object QualityLabel {
    /** Label for the Now Playing screen: based on the actual media only. */
    fun forNowPlaying(report: QualityReport): String? = report.actualMedia?.let(::format)

    fun format(q: AudioQuality): String? {
        val codec = q.codec ?: return null
        val name = codec.displayName
        return if (codec.isLossless) {
            val depth = q.bitDepth
            val rate = q.sampleRateHz?.let(::kiloHertz)
            when {
                depth != null && rate != null -> "$name $depth-bit/$rate kHz"
                rate != null -> "$name $rate kHz"
                else -> name
            }
        } else {
            q.bitrateKbps?.let { "$name $it kbps" } ?: name
        }
    }

    /** 44100 → "44.1", 48000 → "48", 96000 → "96", 176400 → "176.4". */
    fun kiloHertz(hz: Int): String {
        val khz = hz / 1000.0
        return if (hz % 1000 == 0) (hz / 1000).toString() else String.format(Locale.ROOT, "%.1f", khz)
    }
}
