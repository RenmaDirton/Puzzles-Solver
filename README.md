# Ring Tapper

Android app (Kotlin, no external dependencies) that watches the screen and taps when the
ball is on the target bar of the purple-ring timing game.

## Build
Push this folder to GitHub. The workflow in `.github/workflows/build.yml` builds the APK:
Actions tab -> latest run -> Artifacts -> `RingTapper-debug-apk`.

## Install and use (Samsung A16)
1. Copy `app-debug.apk` to the phone and install it (allow "install unknown apps").
2. Settings -> Apps -> Ring Tapper -> three dots -> **Allow restricted settings**
   (Android blocks accessibility for sideloaded apps until you do this).
3. Open Ring Tapper -> "1. Enable accessibility service" -> Installed apps -> Ring Tapper -> On.
4. Tap "2. Start", allow notifications, and when asked what to share choose **Entire screen**.
5. Open the game. The status line shows `ring found`, then the ball angle, speed and target arcs.
6. Stop from the notification or the app.

## How it works
- The ring (purple, fixed colour) is found automatically from its pixels.
- The ball angle comes from the dark rod joining the hub to the ball.
- Bars = anything on the ring that is not purple (any colour). The pure-white score text
  that overlaps the ring is ignored.
- The bars form one cluster, split into colour groups. The MIDDLE group is the target.
- Bar positions are re-read continuously and wiped after every tap (the game moves them).
- Tap time = predicted ball angle (angle + speed x latency) reaching the middle of the arc.
- The tap is a gesture at the ring centre.

## Tuning
Sliders in the app: latency (default 90 ms), bar colour split (how different two
neighbouring bars must look to count as separate bars), cooldown after tap.
Detection thresholds (purple range, rod darkness) are constants at the top of
`RingSolver.kt`.

## Adding another game
Implement `GameSolver` (`analyze(frame) -> TapRequest?`) and swap it in `CaptureService`.
