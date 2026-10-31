# Quiet Noise — Gentle rain

Audio amplitude is doubled (+6.02 dB), with no clipping in the bundled asset.
Volume initializes to 60% on first launch after this update; subsequent slider
changes are remembered.

Minimal native Android app (Android 8.0+): black screen, large Play/Stop button,
volume slider, offline rain, media notification, background playback, and a large
hours/minutes playback timer. Play starts a new timer; Stop freezes it immediately
while audio fades out. The result is saved across closing and reopening the app.
The media notification requests a black background and black artwork, displays
elapsed hours/minutes, and provides a single Play/Stop action. Stop freezes the
timer and fades out; Play begins a new session with a fresh timer. The card remains
available after Stop and when leaving the app with Home or locking the phone.
Removing the app from Recents fades out and closes its service/card; force-stop
also closes it (the OS cannot provide an app-controlled fade on force-stop).
The stopped service holds no AudioTrack, wake lock, or timer callback. Rapid Play
during fade-out joins the current native gain on the same AudioTrack, avoiding
overlapping playback or an abrupt jump to full volume. Notification
colors and individual control surfaces remain subject to Android/Xiaomi styling.
Notification elapsed text refreshes once per minute while playing and on state changes.

Reset timer clears the result; during playback it starts counting again from zero.
The timer uses elapsed real time (including sleep). The app screen refreshes at
most once per minute while visible; the media notification also refreshes once per
minute while playing. Neither uses second-by-second timer updates.

The bundled sound is a real garden-rain recording by barkenov, released under
CC0. See `audio-source/CREDITS.md` for provenance and licensing. To rebuild it,
decode the included MP3 as described in `tools/prepare_rain.py`, then run that script. Run `python3 tools/check_audio.py` to validate the
PCM file and export `previews/rain_preview.wav`: a 24-second demonstration with
the loop join at 12 seconds. Neither WAV contains a start/end fade.

Playback loads the 54-second mono PCM loop into an AudioTrack static buffer once.
Android repeats the sample buffer without reopening a file or running Java loop
timers. A six-second equal-power overlap is built into the audio. Fades are applied
by Android VolumeShaper only when starting/stopping, never at each loop boundary.
Play ramps linearly from silence to the selected volume over five seconds.
Stop ramps linearly from the current level to silence over five seconds, including
when pressed during fade-in. Volume changes preserve the fade envelope;
audio-focus loss and headphone disconnection stop immediately. A partial wake lock
is held only during playback/fading and released with the audio resources. Power
saving performance mode is requested; device support and battery use vary.

## Build and test

Open this folder in Android Studio. Install SDK 35 when prompted and use JDK 17
or a compatible bundled JDK. Let Gradle sync, then select a USB-debugging-enabled
Android phone or an emulator and press Run. First-time build setup requires
internet; the installed app has no internet permission or runtime dependencies.

Command-line build with a configured JDK and Android SDK:

```
./gradlew :app:assembleDebug
```

The debug APK is generated at `app/build/outputs/apk/debug/app-debug.apk`.

PCM format, clipping, boundary continuity, and level consistency were checked
locally. The debug APK builds and Android lint passes (non-blocking warnings remain).
Timer logic checks cover minute/hour boundaries, start/reset, freezing on Stop,
and saved-value restoration. The timer layout was inspected in the running emulator. A background-playback
check reached one displayed minute; Stop saved the elapsed result, and restarting
the stopped app retained it.
The preview contains the unaltered loop boundary, without button fades; it is not
a recording of the Android app. Real-phone audio, fades, locked-screen reliability,
and battery usage still need verification.

Timer logic checks can run without Android using a JDK:

```
javac -d /tmp/quiet-noise-timer-check app/src/main/java/lv/quietnoise/PlaybackTimer.java tools/PlaybackTimerCheck.java
java -Duser.language=en -cp /tmp/quiet-noise-timer-check lv.quietnoise.PlaybackTimerCheck
```

Single-button notification lifecycle and rapid Play/Stop transitions were checked
in the emulator. Native five-second linear fades remain in use. Actual audio
listening and Xiaomi notification rendering require checking on the phone.
