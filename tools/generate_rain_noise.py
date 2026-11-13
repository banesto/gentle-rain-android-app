#!/usr/bin/env python3
"""Create a stationary, rain-like noise bed using only the standard library.

White noise is softened with a fixed blend of low-pass filters, then gently
high-passed to remove rumble. No droplets, tones, modulation, limiter pumping,
or start/end fades. Filter state is warmed with the loop tail for a cyclic seam.
The WAV is generated offline; the phone only plays the bundled audio.
"""
import array
import math
from pathlib import Path
import random
import sys
import wave

RATE = 44100
SECONDS = 120
TARGET_DBFS = -26
rng = random.Random(130112026)
noise = array.array('f', (rng.uniform(-1, 1) for _ in range(RATE * SECONDS)))
# A warm broad wash, retaining rain-like texture without white-noise brightness.
a_warm = 1 - math.exp(-2 * math.pi * 300 / RATE)
a_air = 1 - math.exp(-2 * math.pi * 1000 / RATE)
a_smooth = 1 - math.exp(-2 * math.pi * 1400 / RATE)
a_bass = 1 - math.exp(-2 * math.pi * 55 / RATE)
warm = air = smooth = softened = bass = 0.0

def process(value):
    global warm, air, smooth, softened, bass
    warm += a_warm * (value - warm)
    air += a_air * (value - air)
    blended = 0.85 * warm + 0.15 * air
    smooth += a_smooth * (blended - smooth)
    softened += a_smooth * (smooth - softened)
    bass += a_bass * (softened - bass)
    return softened - bass

# One second is much longer than the slowest filter's settling time. Using the
# actual loop tail, rather than silence, prevents a startup transient in the file.
for value in noise[-RATE:]:
    process(value)
filtered = array.array('f', (process(value) for value in noise))
rms = math.sqrt(sum(value*value for value in filtered) / len(filtered))
gain = 32767 * 10 ** (TARGET_DBFS / 20) / rms
samples = array.array('h', (round(value * gain) for value in filtered))
assert max(abs(value) for value in samples) < 32767, 'Unexpected clipping'
if sys.byteorder != 'little':
    samples.byteswap()
output = Path(__file__).resolve().parents[1] / 'app/src/main/res/raw/gentle_rain.wav'
with wave.open(str(output), 'wb') as audio:
    audio.setnchannels(1)
    audio.setsampwidth(2)
    audio.setframerate(RATE)
    audio.writeframes(samples.tobytes())
print(output)
print(f'{SECONDS}s stationary filtered noise; {TARGET_DBFS} dBFS RMS; no baked-in fades.')
