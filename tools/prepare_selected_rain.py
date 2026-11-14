#!/usr/bin/env python3
"""Prepare soundrecorder7's CC0 Light Rain HQ preview: seamless loop, 3x amplitude.
Decode to mono PCM16/44100 first; pass the decoded WAV as the only argument.
"""
import array
import math
from pathlib import Path
import sys
import wave

with wave.open(sys.argv[1], 'rb') as w:
    assert (w.getnchannels(), w.getsampwidth(), w.getframerate()) == (1, 2, 44100)
    samples = array.array('h', w.readframes(w.getnframes()))
if sys.byteorder != 'little':
    samples.byteswap()
rate = 44100
overlap = 2 * rate
length = len(samples) - overlap
mixed = array.array('d', samples[:length])
# Constant-power overlap preserves rain energy, rather than fading into silence.
for i in range(overlap):
    angle = math.pi / 2 * i / (overlap - 1)
    mixed[i] = samples[length+i]*math.cos(angle) + samples[i]*math.sin(angle)
output = array.array('h', (round(x*3) for x in mixed))
assert max(abs(x) for x in output) < 32767
if sys.byteorder != 'little':
    output.byteswap()
path = Path(__file__).resolve().parents[1]/'app/src/main/res/raw/gentle_rain.wav'
with wave.open(str(path), 'wb') as w:
    w.setparams((1, 2, rate, 0, 'NONE', 'not compressed'))
    w.writeframes(output.tobytes())
print(f'{path}: {length/rate:.3f}s; amplitude x3 (+9.54 dB); no clipping or baked start/stop fades')
