/*
 * The ALAC bitstream decoder (D-87): a Kotlin port of Apple's reference decoder (ALACDecoder.cpp,
 * ag_dec.c, dp_dec.c, matrix_dec.c), Copyright (c) 2011 Apple Inc., Apache License 2.0
 * (third_party/CODE.md, third_party/licenses/Apache-2.0-ALAC.txt). Changes from the original:
 * Kotlin, one bit reader over absolute positions, multichannel output in Android's channel order,
 * and bad input reported as AlacException instead of status codes.
 */
package app.podium.player.service.alac

import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Malformed ALAC data: the frame can't be decoded. */
internal class AlacException(message: String) : Exception(message)

/**
 * ALAC's own description of a stream (its "magic cookie": ALACSpecificConfig). [bitDepth] is 16, 20,
 * 24 or 32; [frameLength] samples per channel in a full frame.
 */
internal data class AlacConfig(
    val frameLength: Int,
    val bitDepth: Int,
    val pb: Int,
    val mb: Int,
    val kb: Int,
    val channels: Int,
    val maxRun: Int,
    val maxFrameBytes: Int,
    val sampleRate: Int,
) {
    /** Bytes per sample of the PCM this decoder writes: 20-bit samples are written as 24-bit. */
    val bytesPerSample: Int get() = when (bitDepth) {
        16 -> 2
        20, 24 -> 3
        else -> 4
    }

    companion object {
        private const val SIZE = 24

        /**
         * The config from a cookie as containers carry it: bare (MP4's `alac` box payload), or behind
         * QuickTime's `frma` and `alac` atom headers. Null when it isn't one Podium can decode.
         */
        fun parse(cookie: ByteArray): AlacConfig? {
            var at = 0
            fun atomAt(name: String) = cookie.size >= at + 8 && String(cookie, at + 4, 4, Charsets.US_ASCII) == name
            if (atomAt("frma")) at += 12
            if (atomAt("alac")) at += 12
            if (cookie.size - at < SIZE) return null
            val b = ByteBuffer.wrap(cookie, at, SIZE).order(ByteOrder.BIG_ENDIAN)
            val frameLength = b.int
            b.get() // compatible version
            val bitDepth = b.get().toInt() and 0xff
            val pb = b.get().toInt() and 0xff
            val mb = b.get().toInt() and 0xff
            val kb = b.get().toInt() and 0xff
            val channels = b.get().toInt() and 0xff
            val maxRun = b.short.toInt() and 0xffff
            val maxFrameBytes = b.int
            b.int // average bit rate
            val sampleRate = b.int
            if (bitDepth !in setOf(16, 20, 24, 32) || channels !in 1..MAX_CHANNELS || frameLength !in 1..MAX_FRAME_LENGTH) return null
            return AlacConfig(frameLength, bitDepth, pb, mb, kb, channels, maxRun, maxFrameBytes, sampleRate)
        }

        const val MAX_CHANNELS = 8
        private const val MAX_FRAME_LENGTH = 1 shl 16
    }
}

/**
 * Decodes ALAC frames to interleaved little-endian PCM ([AlacConfig.bytesPerSample] per sample), in
 * Android's channel order. One instance per stream; not thread-safe.
 */
internal class AlacFrameDecoder(private val config: AlacConfig) {

    private val n = config.frameLength
    private val predictor = IntArray(n)
    private val mixU = IntArray(n)
    private val mixV = IntArray(n)
    private val shiftBuffer = IntArray(n * 2)
    private val coefsU = ShortArray(32)
    private val coefsV = ShortArray(32)

    /** Where each decoded channel goes in Android's order (ALAC puts the centre first). */
    private val placement = CHANNEL_PLACEMENT[config.channels - 1]

    /**
     * Decodes one frame of [length] bytes from [frame] into [out] (which needs room for
     * [AlacConfig.frameLength] samples of every channel). Returns the samples per channel written.
     */
    fun decode(frame: ByteArray, length: Int, out: ByteBuffer): Int {
        val bits = BitReader(frame, length)
        val channels = config.channels
        val start = out.position()
        var samples = n
        var channel = 0
        out.order(ByteOrder.LITTLE_ENDIAN)
        loop@ while (channel < channels) {
            when (bits.read(3)) {
                ID_SCE, ID_LFE -> {
                    samples = singleChannel(bits, samples, out, start, placement[channel])
                    channel += 1
                }
                ID_CPE -> {
                    if (channel + 2 > channels) break@loop
                    samples = channelPair(bits, samples, out, start, placement[channel], placement[channel + 1])
                    channel += 2
                }
                ID_DSE -> dataStream(bits)
                ID_FIL -> fill(bits)
                ID_END -> break@loop
                else -> throw AlacException("unsupported element") // CCE, PCE
            }
        }
        // Channels the frame didn't carry are silent.
        while (channel < channels) {
            for (i in 0 until samples) write(out, start, placement[channel], i, 0)
            channel++
        }
        out.position(start + samples * channels * config.bytesPerSample)
        return samples
    }

    private class Header(val samples: Int, val bytesShifted: Int, val compressed: Boolean)

    private fun header(bits: BitReader, samples: Int): Header {
        bits.read(4) // element instance tag
        if (bits.read(12) != 0) throw AlacException("bad element header")
        val flags = bits.read(4)
        val partial = flags ushr 3
        val bytesShifted = (flags ushr 1) and 0x3
        if (bytesShifted == 3) throw AlacException("bad shift")
        val count = if (partial != 0) bits.read(32) else samples
        if (count < 0 || count > n) throw AlacException("too many samples")
        return Header(count, bytesShifted, compressed = flags and 0x1 == 0)
    }

    private fun singleChannel(bits: BitReader, requested: Int, out: ByteBuffer, start: Int, slot: Int): Int {
        val h = header(bits, requested)
        val samples = h.samples
        var bytesShifted = h.bytesShifted
        val chanBits = config.bitDepth - bytesShifted * 8
        if (h.compressed) {
            bits.read(8) // mix bits: no mixing for one channel
            bits.read(8) // mix res
            val (mode, denShift) = bits.peekHeader()
            val (pbFactor, num) = bits.predictorHeader()
            for (i in 0 until num) coefsU[i] = bits.read(16).toShort()
            var shiftAt = 0L
            if (bytesShifted != 0) {
                shiftAt = bits.position
                bits.skip(bytesShifted * 8L * samples)
            }
            decompress(bits, samples, chanBits, (config.pb * pbFactor) / 4)
            predict(mode, samples, coefsU, num, chanBits, denShift, mixU)
            if (bytesShifted != 0) {
                val shiftBits = BitReader(bits, shiftAt)
                val width = bytesShifted * 8
                for (i in 0 until samples) shiftBuffer[i] = shiftBits.read(width)
            }
        } else {
            readVerbatim(bits, samples, config.bitDepth, mixU, null)
            bytesShifted = 0
        }
        val shift = bytesShifted * 8
        for (i in 0 until samples) {
            val v = if (shift != 0) (mixU[i] shl shift) or shiftBuffer[i] else mixU[i]
            write(out, start, slot, i, v)
        }
        return samples
    }

    private fun channelPair(bits: BitReader, requested: Int, out: ByteBuffer, start: Int, slotL: Int, slotR: Int): Int {
        val h = header(bits, requested)
        val samples = h.samples
        var bytesShifted = h.bytesShifted
        val chanBits = config.bitDepth - bytesShifted * 8 + 1
        var mixBits = 0
        var mixRes = 0
        if (h.compressed) {
            mixBits = bits.read(8)
            mixRes = bits.read(8).toByte().toInt()
            val (modeU, denShiftU) = bits.peekHeader()
            val (pbFactorU, numU) = bits.predictorHeader()
            for (i in 0 until numU) coefsU[i] = bits.read(16).toShort()
            val (modeV, denShiftV) = bits.peekHeader()
            val (pbFactorV, numV) = bits.predictorHeader()
            for (i in 0 until numV) coefsV[i] = bits.read(16).toShort()
            var shiftAt = 0L
            if (bytesShifted != 0) {
                shiftAt = bits.position
                bits.skip(bytesShifted * 8L * 2 * samples)
            }
            decompress(bits, samples, chanBits, (config.pb * pbFactorU) / 4)
            predict(modeU, samples, coefsU, numU, chanBits, denShiftU, mixU)
            decompress(bits, samples, chanBits, (config.pb * pbFactorV) / 4)
            predict(modeV, samples, coefsV, numV, chanBits, denShiftV, mixV)
            if (bytesShifted != 0) {
                val shiftBits = BitReader(bits, shiftAt)
                val width = bytesShifted * 8
                for (i in 0 until samples) {
                    shiftBuffer[i * 2] = shiftBits.read(width)
                    shiftBuffer[i * 2 + 1] = shiftBits.read(width)
                }
            }
        } else {
            readVerbatim(bits, samples, config.bitDepth, mixU, mixV)
            bytesShifted = 0
        }
        // Unmix: mixRes 0 is plain left and right.
        val shift = bytesShifted * 8
        for (i in 0 until samples) {
            var l: Int
            var r: Int
            if (mixRes != 0) {
                l = mixU[i] + mixV[i] - ((mixRes * mixV[i]) shr mixBits)
                r = l - mixV[i]
            } else {
                l = mixU[i]
                r = mixV[i]
            }
            if (shift != 0) {
                l = (l shl shift) or shiftBuffer[i * 2]
                r = (r shl shift) or shiftBuffer[i * 2 + 1]
            }
            write(out, start, slotL, i, l)
            write(out, start, slotR, i, r)
        }
        return samples
    }

    /** Uncompressed ("escape") samples, [depth] bits each, sign-extended; interleaved when [v] is given. */
    private fun readVerbatim(bits: BitReader, samples: Int, depth: Int, u: IntArray, v: IntArray?) {
        val ext = 32 - depth
        for (i in 0 until samples) {
            u[i] = (bits.read(depth) shl ext) shr ext
            if (v != null) v[i] = (bits.read(depth) shl ext) shr ext
        }
    }

    /** One sample of [value] for channel [slot], at the stream's sample size. */
    private fun write(out: ByteBuffer, start: Int, slot: Int, index: Int, value: Int) {
        val bytes = config.bytesPerSample
        val at = start + (index * config.channels + slot) * bytes
        when (config.bitDepth) {
            16 -> out.putShort(at, value.toShort())
            20 -> put24(out, at, value shl 4)
            24 -> put24(out, at, value)
            else -> out.putInt(at, value)
        }
    }

    private fun put24(out: ByteBuffer, at: Int, v: Int) {
        out.put(at, v.toByte())
        out.put(at + 1, (v shr 8).toByte())
        out.put(at + 2, (v shr 16).toByte())
    }

    // --- Elements that carry no audio --------------------------------------------------------------

    private fun dataStream(bits: BitReader) {
        bits.read(4) // element instance tag
        val align = bits.read(1)
        var count = bits.read(8)
        if (count == 255) count += bits.read(8)
        if (align != 0) bits.byteAlign()
        bits.skip(count * 8L)
    }

    private fun fill(bits: BitReader) {
        var count = bits.read(4)
        if (count == 15) count += bits.read(8) - 1
        bits.skip(count * 8L)
    }

    // --- Adaptive Golomb decoding (ag_dec.c) ---------------------------------------------------------

    /** Decodes [samples] prediction residuals of up to [maxBits] bits into [predictor]. */
    private fun decompress(bits: BitReader, samples: Int, maxBits: Int, pb: Int) {
        if (maxBits > 32) throw AlacException("samples too wide")
        val kb = config.kb
        val wb = (1 shl kb) - 1
        var mb = config.mb.toLong()
        var zmode = 0L
        var c = 0
        while (c < samples) {
            if (bits.exhausted) throw AlacException("ran off the end of the frame")
            var m = (mb ushr QB_SHIFT).toInt()
            var k = minOf(lg3a(m), kb)
            m = (1 shl k) - 1
            val n = dynGet32(bits, m, k, maxBits)
            // The least significant bit is the sign.
            val nd = (n + zmode) and MASK32
            val del = ((nd + 1) ushr 1).toInt().let { if (nd and 1L != 0L) -it else it }
            predictor[c++] = del
            mb = (pb * ((n + zmode) and MASK32) + mb - ((pb * mb) ushr QB_SHIFT)) and MASK32
            if (n > N_MAX_MEAN_CLAMP) mb = N_MEAN_CLAMP_VAL
            zmode = 0
            if (((mb shl M_MUL_SHIFT) and MASK32) < QB && c < samples) {
                zmode = 1
                k = Integer.numberOfLeadingZeros(mb.toInt()) - BIT_OFF + ((mb.toInt() + M_OFF) shr M_DEN_SHIFT)
                val mz = ((1 shl k) - 1) and wb
                val run = dynGet(bits, mz, k)
                if (c + run > samples) throw AlacException("run past the end of the frame")
                for (j in 0 until run) predictor[c++] = 0
                if (run >= 65535) zmode = 0
                mb = 0
            }
        }
    }

    /** A residual: unary prefix, then k bits — or, after 9 ones, the value itself in [maxBits] bits. */
    private fun dynGet32(bits: BitReader, m: Int, k: Int, maxBits: Int): Long {
        val pre = bits.leadingOnes(MAX_PREFIX_32)
        if (pre >= MAX_PREFIX_32) {
            bits.skip(MAX_PREFIX_32.toLong())
            return bits.read(maxBits).toLong() and MASK32
        }
        bits.skip(pre + 1L)
        if (k == 1) return pre.toLong()
        val v = bits.peek(k).toLong() and MASK32
        bits.skip(k - 1L)
        var result = pre.toLong() * m
        if (v >= 2) {
            result += v - 1
            bits.skip(1)
        }
        return result and MASK32
    }

    /** A run of zeros' length: as [dynGet32], escaping to 16 bits. */
    private fun dynGet(bits: BitReader, m: Int, k: Int): Int {
        val pre = bits.leadingOnes(MAX_PREFIX_16)
        if (pre >= MAX_PREFIX_16) {
            bits.skip(MAX_PREFIX_16.toLong())
            return bits.read(16)
        }
        bits.skip(pre + 1L)
        val v = if (k == 0) 0 else bits.peek(k)
        bits.skip(k.toLong())
        var result = pre * m + v - 1
        if (v < 2) {
            result -= v - 1
            bits.skip(-1)
        }
        return result
    }

    private fun lg3a(x: Int): Int = 31 - Integer.numberOfLeadingZeros(x + 3)

    // --- Prediction (dp_dec.c) -----------------------------------------------------------------------

    /** Runs the predictor over [predictor] into [out]; mode 1 first undoes a first-order difference. */
    private fun predict(mode: Int, samples: Int, coefs: ShortArray, num: Int, chanBits: Int, denShift: Int, out: IntArray) {
        if (mode != 0) unpcBlock(predictor, predictor, samples, coefs, 31, chanBits, 0)
        unpcBlock(predictor, out, samples, coefs, num, chanBits, denShift)
    }

    private fun unpcBlock(pc: IntArray, out: IntArray, num: Int, coefs: ShortArray, numActive: Int, chanBits: Int, denShift: Int) {
        if (num == 0) return
        val chanShift = 32 - chanBits
        val denHalf = if (denShift > 0) 1 shl (denShift - 1) else 0
        out[0] = pc[0]
        if (numActive == 0) {
            if (pc !== out) System.arraycopy(pc, 1, out, 1, num - 1)
            return
        }
        if (numActive == 31) {
            var prev = out[0]
            for (j in 1 until num) {
                val del = pc[j] + prev
                prev = (del shl chanShift) shr chanShift
                out[j] = prev
            }
            return
        }
        for (j in 1..minOf(numActive, num - 1)) {
            val del = pc[j] + out[j - 1]
            out[j] = (del shl chanShift) shr chanShift
        }
        val lim = numActive + 1
        for (j in lim until num) {
            var sum = 0
            val top = out[j - lim]
            for (k in 0 until numActive) sum += coefs[k] * (out[j - 1 - k] - top)
            var del = pc[j]
            var del0 = del
            val sg = sign(del)
            del += top + ((sum + denHalf) shr denShift)
            out[j] = (del shl chanShift) shr chanShift
            if (sg > 0) {
                for (k in numActive - 1 downTo 0) {
                    val dd = top - out[j - 1 - k]
                    val sgn = sign(dd)
                    coefs[k] = (coefs[k] - sgn).toShort()
                    del0 -= (numActive - k) * ((sgn * dd) shr denShift)
                    if (del0 <= 0) break
                }
            } else if (sg < 0) {
                for (k in numActive - 1 downTo 0) {
                    val dd = top - out[j - 1 - k]
                    val sgn = sign(dd)
                    coefs[k] = (coefs[k] + sgn).toShort()
                    del0 -= (numActive - k) * ((-sgn * dd) shr denShift)
                    if (del0 >= 0) break
                }
            }
        }
    }

    private fun sign(i: Int): Int = if (i > 0) 1 else if (i < 0) -1 else 0

    // --- Bits ----------------------------------------------------------------------------------------

    /** Big-endian bits over a frame; reads past its end read zeros (and [exhausted] says so). */
    private class BitReader(private val data: ByteArray, length: Int) {
        private val limit = length.toLong() * 8
        var position = 0L
            private set

        constructor(other: BitReader, at: Long) : this(other.data, (other.limit / 8).toInt()) {
            position = at
        }

        val exhausted: Boolean get() = position >= limit

        fun skip(count: Long) {
            position += count
        }

        fun byteAlign() {
            position = (position + 7) and 7L.inv()
        }

        /** [count] bits (0..32) without moving. */
        fun peek(count: Int): Int {
            if (count == 0) return 0
            var v = 0L
            var p = position
            var left = count
            while (left > 0) {
                val byteIndex = (p ushr 3).toInt()
                val b = if (p < limit && byteIndex < data.size) data[byteIndex].toInt() and 0xff else 0
                val bitInByte = (p and 7).toInt()
                val take = minOf(8 - bitInByte, left)
                val chunk = (b ushr (8 - bitInByte - take)) and ((1 shl take) - 1)
                v = (v shl take) or chunk.toLong()
                left -= take
                p += take
            }
            return v.toInt()
        }

        fun read(count: Int): Int = peek(count).also { position += count }

        /** How many 1 bits come next, counting at most [max]. */
        fun leadingOnes(max: Int): Int {
            val window = peek(max)
            val zeros = Integer.numberOfLeadingZeros(window.inv() shl (32 - max))
            return minOf(zeros, max)
        }

        /** The mode and denominator shift (4 bits each). */
        fun peekHeader(): Pair<Int, Int> = read(8).let { (it ushr 4) to (it and 0xf) }

        /** The pb factor (3 bits) and the number of coefficients (5 bits). */
        fun predictorHeader(): Pair<Int, Int> = read(8).let { (it ushr 5) to (it and 0x1f) }
    }

    private companion object {
        const val ID_SCE = 0
        const val ID_CPE = 1
        const val ID_LFE = 3
        const val ID_DSE = 4
        const val ID_FIL = 6
        const val ID_END = 7

        const val QB_SHIFT = 9
        const val QB = 1L shl QB_SHIFT
        const val M_MUL_SHIFT = 2
        const val M_DEN_SHIFT = QB_SHIFT - M_MUL_SHIFT - 1
        const val M_OFF = 1 shl (M_DEN_SHIFT - 2)
        const val BIT_OFF = 24
        const val N_MAX_MEAN_CLAMP = 0xffffL
        const val N_MEAN_CLAMP_VAL = 0xffffL
        const val MAX_PREFIX_16 = 9
        const val MAX_PREFIX_32 = 9
        const val MASK32 = 0xffffffffL

        /**
         * ALAC's channel order for 1–8 channels (mono; stereo; then the centre first, and the LFE
         * last) placed in Android's (WAVE) order — the same table FFmpeg's decoder uses.
         */
        val CHANNEL_PLACEMENT = arrayOf(
            intArrayOf(0),
            intArrayOf(0, 1),
            intArrayOf(2, 0, 1),
            intArrayOf(2, 0, 1, 3),
            intArrayOf(2, 0, 1, 3, 4),
            intArrayOf(2, 0, 1, 4, 5, 3),
            intArrayOf(2, 0, 1, 4, 5, 6, 3),
            intArrayOf(2, 6, 7, 0, 1, 4, 5, 3),
        )
    }
}
