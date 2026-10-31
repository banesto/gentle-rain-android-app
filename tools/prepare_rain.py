#!/usr/bin/env python3
"""Prepare the licensed field recording for offline static-buffer playback.

First decode on macOS:
afconvert -f WAVE -d LEI16@44100 -c 1 audio-source/soft-rain-barkenov.mp3 /tmp/quiet-rain-decoded.wav
Then run: python3 tools/prepare_rain.py /tmp/quiet-rain-decoded.wav
"""
import array
import math
from pathlib import Path
import sys
import wave

rate = 44100
with wave.open(sys.argv[1], 'rb') as file:
    assert (file.getnchannels(), file.getsampwidth(), file.getframerate()) == (1, 2, rate)
    file.setpos(rate // 2)
    samples = array.array('h', file.readframes(60 * rate))
if sys.byteorder != 'little':
    samples.byteswap()
assert len(samples) == 60 * rate
length, overlap = 54 * rate, 6 * rate
mixed = array.array('f', samples)
for i in range(overlap):
    weight = 0.5 - 0.5 * math.cos(math.pi * i / (overlap - 1))
    mixed[i] = samples[length + i] * math.sqrt(1-weight) + samples[i] * math.sqrt(weight)
del mixed[length:]
# Double amplitude (+6.02 dB), preserving dynamics with headroom against clipping.
gain = min(2.0, 0.55 * 32767 / max(abs(x) for x in mixed))
output = array.array('h', (round(x * gain) for x in mixed))
if sys.byteorder != 'little':
    output.byteswap()
path = Path(__file__).resolve().parents[1] / 'app/src/main/res/raw/gentle_rain.wav'
with wave.open(str(path), 'wb') as file:
    file.setnchannels(1)
    file.setsampwidth(2)
    file.setframerate(rate)
    file.writeframes(output.tobytes())
print(path)
