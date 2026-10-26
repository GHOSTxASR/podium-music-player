package app.podium.sources.addon

import app.podium.core.model.AudioQuality
import app.podium.core.model.Codec

/**
 * What an addon's stream answer says about the media, read conservatively (D-91). The quality is
 * the addon's claim and stays one (PlayableMedia.claimedQuality): Podium's labels come from what the
 * decoder measures, never from this.
 */
internal object StreamFacts {
    const val MIME_DASH = "application/dash+xml"
    const val MIME_HLS = "application/x-mpegURL"

    /** The container the player must be told about before it opens [stream], or null to let it sniff. */
    fun mimeType(stream: AddonStream): String? {
        stream.mimeType?.lowercase()?.let { m ->
            when {
                "dash" in m -> return MIME_DASH
                "mpegurl" in m -> return MIME_HLS
                m.startsWith("audio/") -> return stream.mimeType
            }
        }
        val path = stream.url.substringBefore('?').substringBefore('#').lowercase()
        return when {
            stream.format == "dash" || stream.format == "mpd" || path.endsWith(".mpd") -> MIME_DASH
            stream.format == "hls" || stream.format == "m3u8" || path.endsWith(".m3u8") -> MIME_HLS
            stream.format == "flac" || path.endsWith(".flac") -> "audio/flac"
            stream.format == "mp3" || path.endsWith(".mp3") -> "audio/mpeg"
            stream.format in setOf("aac", "m4a", "mp4", "alac") || path.endsWith(".m4a") || path.endsWith(".mp4") -> "audio/mp4"
            stream.format == "ogg" || stream.format == "opus" || path.endsWith(".ogg") || path.endsWith(".opus") -> "audio/ogg"
            else -> null
        }
    }

    /**
     * The quality the addon claims, from its free-text label ("Hi-Res Lossless · 24-bit / 44.1 kHz"),
     * keeping only what it states outright: a codec only when named, never inferred from "lossless".
     */
    fun claimedQuality(stream: AddonStream): AudioQuality? {
        val text = listOfNotNull(stream.quality, stream.format).joinToString(" ").lowercase()
        if (text.isBlank()) return null
        val codec = when {
            Regex("""\bflac\b""").containsMatchIn(text) -> Codec.FLAC
            Regex("""\balac\b""").containsMatchIn(text) -> Codec.ALAC
            Regex("""\baac\b""").containsMatchIn(text) -> Codec.AAC
            Regex("""\bmp3\b""").containsMatchIn(text) -> Codec.MP3
            Regex("""\bopus\b""").containsMatchIn(text) -> Codec.OPUS
            else -> null
        }
        val bitDepth = Regex("""\b(16|24|32)[- ]?bit\b""").find(text)?.groupValues?.get(1)?.toInt()
        val sampleRate = Regex("""\b(\d{2,3}(?:\.\d{1,2})?)\s?khz\b""").find(text)?.groupValues?.get(1)?.toDoubleOrNull()
            ?.let { (it * 1000).toInt() }?.takeIf { it in 8_000..768_000 }
        val bitrate = Regex("""\b(\d{2,5})\s?kbps\b""").find(text)?.groupValues?.get(1)?.toInt()
        if (codec == null && bitDepth == null && sampleRate == null && bitrate == null) return null
        return AudioQuality(codec = codec, bitrateKbps = bitrate, sampleRateHz = sampleRate, bitDepth = bitDepth)
    }
}
