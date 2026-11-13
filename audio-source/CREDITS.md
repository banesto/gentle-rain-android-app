# Audio provenance

## Active app sound

The bundled gentle_rain.wav is synthesized offline by tools/generate_rain_noise.py.
It uses deterministic white noise with fixed filtering to soften high frequencies
and remove low rumble. It contains no third-party recording, added droplets,
periodic modulation, environmental events, or baked-in start/end fades.

The 120-second mono 44.1 kHz PCM file has stationary level at -26 dBFS RMS. Filter
state is warmed with the loop tail to make the waveform cyclic without a crossfade
or a recurring level dip. The app applies its existing five-second Play/Stop fades.

## Earlier comparison candidate (not bundled)

The local candidates/light-rain-sheyvan.mp3 is the high-quality MP3 preview of
“Ambience: Light Rain” by Sheyvan (Philipp Grzemba), released under CC0:
https://freesound.org/people/Sheyvan/sounds/523653/
https://creativecommons.org/publicdomain/zero/1.0/

It was retrieved on 2026-11-13 to compare its 3:00–5:00 excerpt. The local candidate
is excluded from Git. tools/prepare_rain.py is retained to reproduce that earlier
recording-based comparison; the active synthetic sound uses generate_rain_noise.py.
