#!/usr/bin/env python3
"""Podium's startup chord (D-71): app/src/main/res/raw/podium_boot.ogg.

Original sound, synthesized here from scratch with NumPy (no samples, no recordings). Layers:

  1. The chord: a strummed C major 9 (C2 to E5), warm additive partials, as before.
  2. Depth: a soft impact at the start (a falling 90 -> 40 Hz sine and a low thump), a sub-octave C1
     under the chord, and gentle saturation on the low strings so their harmonics carry the bass
     even on a phone speaker that can't reproduce it.
  3. A slight electric guitar: a Cadd9 strum (x32033) on plucked strings (Karplus-Strong), lightly
     overdriven and filtered like a guitar cabinet, a little behind the chord, spread in stereo.
  4. Room: a dark, wide synthetic reverb tail.

Usage:  python3 tools/sounds/boot_chord.py OUT.wav   (then encode, see tools/sounds/README.md)
"""
import sys
import wave

import numpy as np

SR = 48_000
LENGTH = 5.0
rng = np.random.default_rng(71)  # fixed: the same chord every build
t = np.arange(int(SR * LENGTH)) / SR


def env(attack, decay, start=0.0):
    """Attack/exponential-decay envelope starting at [start] seconds."""
    x = np.clip(t - start, 0, None)
    a = np.clip(x / max(attack, 1e-4), 0, 1)
    return np.where(t >= start, a * np.exp(-x / decay), 0.0)


def one_pole_lowpass(x, cutoff):
    a = np.exp(-2 * np.pi * cutoff / SR)
    y = np.empty_like(x)
    acc = 0.0
    for i, v in enumerate(x):  # short signals only
        acc = (1 - a) * v + a * acc
        y[i] = acc
    return y


def fft_filter(x, low=None, high=None, order=2):
    """Zero-phase band shaping in the frequency domain (gentle Butterworth-like slopes)."""
    n = len(x)
    spec = np.fft.rfft(x, 2 * n)
    f = np.fft.rfftfreq(2 * n, 1 / SR)
    g = np.ones_like(f)
    if high:
        g *= 1 / np.sqrt(1 + (f / high) ** (2 * order))
    if low:
        g *= 1 / np.sqrt(1 + (low / np.maximum(f, 1e-3)) ** (2 * order))
    return np.fft.irfft(spec * g)[:n]


def note(freq, start, decay, partials=6, bright=1.4, attack=0.006, detune=0.0):
    """A warm, piano-ish voice: harmonics fall off and die faster the higher they are."""
    out = np.zeros_like(t)
    for k in range(1, partials + 1):
        f = freq * k * (1 + detune * (k - 1) * 0.001)
        if f > SR / 2.2:
            break
        amp = 1 / k ** bright
        out += amp * np.sin(2 * np.pi * f * (t - start) + rng.uniform(0, 2 * np.pi)) * env(attack, decay / (1 + 0.55 * (k - 1)), start)
    return out


# 1. The chord: C major 9, strummed low to high.
CHORD = [65.41, 98.00, 130.81, 196.00, 261.63, 329.63, 392.00, 493.88, 587.33, 659.26]
chord = np.zeros_like(t)
for i, f in enumerate(CHORD):
    start = 0.012 + i * 0.014
    decay = 2.9 - i * 0.12
    level = 1.0 if f < 300 else 0.75
    chord += level * note(f, start, decay)

# 2. Depth.
#    A soft impact: a sine falling from 90 to 40 Hz, and a short low-passed noise thump.
sweep_f = 40 + 50 * np.exp(-t / 0.05)
phase = 2 * np.pi * np.cumsum(sweep_f) / SR
impact = np.sin(phase) * env(0.002, 0.32)
thump = fft_filter(rng.standard_normal(len(t)) * env(0.001, 0.045), high=180, order=3) * 2.2
#    A sub-octave under the chord, slow to bloom.
sub = (np.sin(2 * np.pi * 32.70 * t) * 0.6 + np.sin(2 * np.pi * 65.41 * t) * 0.9) * env(0.04, 2.6, 0.01)
#    Saturated low strings: harmonics a small speaker can play, so the bass is heard.
lows = note(65.41, 0.012, 2.6, partials=3) + note(98.0, 0.026, 2.4, partials=3) + note(130.81, 0.04, 2.3, partials=3)
body = np.tanh(lows * 2.4) * 0.55
depth = impact * 1.3 + thump * 0.7 + sub * 0.55 + body


# 3. A slight electric guitar: Cadd9 (x32033), strummed down, lightly driven.
def pluck(freq, start, decay_s=3.2):
    """Karplus-Strong plucked string, vectorized per period."""
    period = SR / freq
    n0 = int(period)
    frac = period - n0
    total = len(t) - int(start * SR)
    y = np.zeros(total + n0 + 2)
    burst = rng.uniform(-1, 1, n0 + 1)
    y[: n0 + 1] = fft_filter(burst, high=4500) if n0 > 64 else burst
    loss = np.exp(-1 / (decay_s * freq))  # per-period energy loss
    for s in range(n0 + 1, len(y), n0):
        e = min(s + n0, len(y))
        a = y[s - n0 : e - n0]
        b = y[s - n0 - 1 : e - n0 - 1]
        y[s:e] = loss * ((1 - frac) * a + frac * b + a) / 2 * 0.996 + (loss * b / 2) * 0.004
    out = np.zeros_like(t)
    out[int(start * SR) :] = y[:total]
    return out


GUITAR = [130.81, 164.81, 196.00, 293.66, 392.00]
guitar = sum(pluck(f * (1 + rng.uniform(-0.0012, 0.0012)), 0.055 + i * 0.019) for i, f in enumerate(GUITAR))
guitar = guitar / (np.abs(guitar).max() + 1e-9)
drive = 3.2
guitar = np.tanh(guitar * drive) / np.tanh(drive)                 # light crunch
guitar = fft_filter(guitar, low=95, high=3800, order=2)            # the cabinet
guitar *= env(0.004, 3.4, 0.05)

# Mix the dry layers, guitar spread a little across the stereo field.
dry_l = chord * 0.5 + depth * 0.62
dry_r = dry_l.copy()
g_l = np.roll(guitar, int(0.008 * SR))
g_r = np.roll(guitar, int(0.013 * SR))
dry_l += g_l * 0.16
dry_r += g_r * 0.16


# 4. Room: dark, wide, about 2.8 s, a short pre-delay.
def impulse(seed):
    r = np.random.default_rng(seed)
    n = int(2.8 * SR)
    tt = np.arange(n) / SR
    ir = r.standard_normal(n) * np.exp(-tt / 0.62)
    ir = fft_filter(ir, low=120, high=5200, order=1)
    ir[: int(0.025 * SR)] = 0
    return ir / np.sqrt((ir ** 2).sum())


def convolve(x, ir):
    n = len(x) + len(ir)
    return np.fft.irfft(np.fft.rfft(x, n) * np.fft.rfft(ir, n))[: len(x)]


wet = 0.32
left = dry_l + wet * convolve(dry_l, impulse(1))
right = dry_r + wet * convolve(dry_r, impulse(2))

# Master: tame the very low end for small speakers, fade the tail, peak at -1 dBFS.
left = fft_filter(left, low=24, order=2)
right = fft_filter(right, low=24, order=2)
fade = np.clip((LENGTH - t) / 0.45, 0, 1) ** 2
left *= fade
right *= fade
peak = max(np.abs(left).max(), np.abs(right).max())
knee = 1.0  # a gentle soft clip so the bloom sits a little louder
left, right = (np.tanh(knee * c / peak) / np.tanh(knee) for c in (left, right))
stereo = np.stack([left, right], axis=1) * 0.891

out = sys.argv[1] if len(sys.argv) > 1 else "podium_boot.wav"
with wave.open(out, "wb") as w:
    w.setnchannels(2)
    w.setsampwidth(2)
    w.setframerate(SR)
    w.writeframes((stereo * 32767).astype(np.int16).tobytes())
print(f"wrote {out}: {LENGTH:.1f} s, {SR} Hz stereo")
