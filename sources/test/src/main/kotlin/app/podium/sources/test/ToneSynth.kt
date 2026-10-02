package app.podium.sources.test

import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.min
import kotlin.math.sin

/**
 * Deterministic tone synthesis for the debug Test source: the same spec always produces the same
 * bytes. 16-bit PCM WAV, so the decoder path is exercised without any external codec or service.
 */
object ToneSynth {
    const val SAMPLE_RATE = 44_100
    const val BITS = 16
    const val CHANNELS = 1

    /** One chord held for [seconds]. Frequencies in Hz. */
    data class Chord(val frequencies: List<Double>, val seconds: Double)

    fun writeWav(file: File, chords: List<Chord>, amplitude: Double = 0.22) {
        file.parentFile?.mkdirs()
        val totalFrames = chords.sumOf { (it.seconds * SAMPLE_RATE).toInt() }
        val dataBytes = totalFrames * CHANNELS * (BITS / 8)
        RandomAccessFile(file, "rw").use { out ->
            out.setLength(0)
            out.write(header(dataBytes))
            val buffer = ByteBuffer.allocate(SAMPLE_RATE * 2).order(ByteOrder.LITTLE_ENDIAN)
            var globalFrame = 0
            for (chord in chords) {
                val frames = (chord.seconds * SAMPLE_RATE).toInt()
                val fade = min(SAMPLE_RATE / 20, frames / 4) // 50 ms in/out: no clicks between chords
                for (i in 0 until frames) {
                    val t = (globalFrame + i).toDouble() / SAMPLE_RATE
                    var v = 0.0
                    chord.frequencies.forEachIndexed { voice, f ->
                        // Fundamental plus two soft harmonics; voices slightly detuned for warmth.
                        val detune = 1.0 + voice * 0.0007
                        v += sin(2 * PI * f * detune * t) + 0.35 * sin(4 * PI * f * t) + 0.12 * sin(6 * PI * f * t)
                    }
                    v /= chord.frequencies.size.coerceAtLeast(1) * 1.47
                    val envelope = when {
                        i < fade -> i.toDouble() / fade
                        i > frames - fade -> (frames - i).toDouble() / fade
                        else -> 1.0
                    }
                    buffer.putShort((v * envelope * amplitude * Short.MAX_VALUE).toInt().toShort())
                    if (!buffer.hasRemaining()) {
                        out.write(buffer.array(), 0, buffer.position())
                        buffer.clear()
                    }
                }
                globalFrame += frames
            }
            out.write(buffer.array(), 0, buffer.position())
        }
    }

    /** A file that looks like a WAV but is truncated garbage — for the "broken media" path. */
    fun writeCorrupt(file: File) {
        file.parentFile?.mkdirs()
        file.writeBytes(header(1_000_000).copyOf(30) + ByteArray(64) { (it * 37).toByte() })
    }

    fun durationMs(chords: List<Chord>): Long = (chords.sumOf { (it.seconds * SAMPLE_RATE).toInt() } * 1000L) / SAMPLE_RATE

    private fun header(dataBytes: Int): ByteArray {
        val byteRate = SAMPLE_RATE * CHANNELS * BITS / 8
        return ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN).apply {
            put("RIFF".toByteArray()); putInt(36 + dataBytes); put("WAVE".toByteArray())
            put("fmt ".toByteArray()); putInt(16); putShort(1); putShort(CHANNELS.toShort())
            putInt(SAMPLE_RATE); putInt(byteRate); putShort((CHANNELS * BITS / 8).toShort()); putShort(BITS.toShort())
            put("data".toByteArray()); putInt(dataBytes)
        }.array()
    }

    // Equal-tempered note frequencies used by the catalog.
    const val C3 = 130.81; const val D3 = 146.83; const val E3 = 164.81; const val F3 = 174.61
    const val G3 = 196.00; const val A3 = 220.00; const val B3 = 246.94
    const val C4 = 261.63; const val D4 = 293.66; const val E4 = 329.63; const val F4 = 349.23
    const val G4 = 392.00; const val A4 = 440.00; const val B4 = 493.88; const val C5 = 523.25
}
