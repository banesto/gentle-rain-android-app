#!/usr/bin/env python3
"""Prepare the licensed field recording for offline static-buffer playback.

First decode on macOS:
afconvert -f WAVE -d LEI16@44100 -c 1 audio-source/candidates/light-rain-sheyvan.mp3 /tmp/quiet-rain-sheyvan-decoded.wav
Then run: python3 tools/prepare_rain.py /tmp/quiet-rain-sheyvan-decoded.wav
"""
import array
import math
from pathlib import Path
import sys
import wave

rate = 44100
with wave.open(sys.argv[1], 'rb') as file:
    assert (file.getnchannels(), file.getsampwidth(), file.getframerate()) == (1, 2, rate)
    file.setpos(180 * rate)  # Use exactly 3:00–5:00 of the selected recording.
    samples = array.array('h', file.readframes(120 * rate))
if sys.byteorder != 'little':
    samples.byteswap()
assert len(samples) == 120 * rate
length, overlap = 114 * rate, 6 * rate
mixed = array.array('f', samples)
for i in range(overlap):
    weight = 0.5 - 0.5 * math.cos(math.pi * i / (overlap - 1))
    mixed[i] = samples[length + i] * math.sqrt(1-weight) + samples[i] * math.sqrt(weight)
del mixed[length:]
# Normalize the quiet field recording to a useful average level. A smooth,
# offline look-ahead limiter controls occasional loud drops without clipping.
# Nothing is generated or processed continuously on the phone.
target_rms = 32767 * 10 ** (-26 / 20)
ceiling = 32767 * 10 ** (-3 / 20)
rms = math.sqrt(sum(x*x for x in mixed) / len(mixed))
assert rms > 0
gain = target_rms / rms
# Three repeats allow attack/release envelopes to settle across the loop join.
# Use the middle repeat so the limiter itself does not create a loop seam.
levels = array.array('f', [1.0]) * (length * 3)
release = 1 - math.exp(-1 / (rate * 0.12))
level = 1.0
for i in range(len(levels)):
    amplitude = abs(mixed[i % length] * gain)
    required = min(1.0, ceiling / amplitude) if amplitude else 1.0
    level = min(required, level + (1-level) * release)
    levels[i] = level
attack = 1 - math.exp(-1 / (rate * 0.005))
level = 1.0
for i in range(len(levels)-1, -1, -1):
    level = min(levels[i], level + (1-level) * attack)
    levels[i] = level
output = array.array('h', (round(x * gain * levels[length+i]) for i, x in enumerate(mixed)))
assert max(abs(x) for x in output) <= math.ceil(ceiling)
print(f"RMS normalization gain: {20*math.log10(gain):.1f} dB; peak ceiling: -3 dBFS")
if sys.byteorder != 'little':
    output.byteswap()
path = Path(__file__).resolve().parents[1] / 'app/src/main/res/raw/gentle_rain.wav'
with wave.open(str(path), 'wb') as file:
    file.setnchannels(1)
    file.setsampwidth(2)
    file.setframerate(rate)
    file.writeframes(output.tobytes())
print(path)
