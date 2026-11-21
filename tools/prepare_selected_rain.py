#!/usr/bin/env python3
"""Prepare the audio extracted from the supplied video as a seamless loop.
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
# Repeated waveform detected at a lag of 3,515,776 frames (79.722812s).
# Start away from the video boundary and retain exactly one full cycle.
start = 5 * rate
length = 3515776
assert len(samples) >= start + length, 'Source is too short for the detected loop'
mixed = array.array('d', samples[start:start+length])
# Keep this quiet capture close to the previous app level, with peak headroom.
rms = math.sqrt(sum(x*x for x in mixed) / len(mixed))
peak = max(abs(x) for x in mixed)
gain = min(32767 * 10**(-27/20) / rms, 32767 * 10**(-1/20) / peak)
output = array.array('h', (round(x*gain) for x in mixed))
assert max(abs(x) for x in output) < 32767
if sys.byteorder != 'little':
    output.byteswap()
path = Path(__file__).resolve().parents[1]/'app/src/main/res/raw/gentle_rain.wav'
with wave.open(str(path), 'wb') as w:
    w.setparams((1, 2, rate, 0, 'NONE', 'not compressed'))
    w.writeframes(output.tobytes())
print(f'{path}: {length/rate:.3f}s; gain {20*math.log10(gain):.2f} dB; no clipping or baked start/stop fades')
