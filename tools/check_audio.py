#!/usr/bin/env python3
"""Validate the loop asset and export a short preview crossing its boundary."""
import array
import math
from pathlib import Path
import sys
import wave

root = Path(__file__).resolve().parents[1]
rate = 44100
with wave.open(str(root / 'app/src/main/res/raw/gentle_rain.wav'), 'rb') as file:
    assert (file.getnchannels(), file.getsampwidth(), file.getframerate()) == (1, 2, rate)
    samples = array.array('h', file.readframes(file.getnframes()))
if sys.byteorder != 'little':
    samples.byteswap()
assert len(samples) == rate * 54
assert max(abs(value) for value in samples) < 32767
step_rms = math.sqrt(sum((samples[i] - samples[i-1])**2 for i in range(1, len(samples))) / (len(samples)-1))
assert abs(samples[0] - samples[-1]) < 4 * step_rms, 'Loop boundary click risk'
levels = [math.sqrt(sum(value*value for value in samples[i:i+rate]) / rate) for i in range(0, len(samples), rate)]
assert min(levels) > 0
assert max(levels) / min(levels) < 3.0, 'Unexpected level dip or surge'

# Twelve seconds before the join, twelve seconds after; no fades in the file.
preview = samples[-rate*12:] + samples[:rate*12]
if sys.byteorder != 'little':
    preview.byteswap()
output = root / 'previews/rain_preview.wav'
output.parent.mkdir(exist_ok=True)
with wave.open(str(output), 'wb') as file:
    file.setnchannels(1)
    file.setsampwidth(2)
    file.setframerate(rate)
    file.writeframes(preview.tobytes())
print(f'PASS: PCM format, duration, clipping, boundary continuity, and steady loop level. Preview: {output}')
print(f'Loop join is at 12 seconds in the preview; no start/end fades are baked into either WAV.')
