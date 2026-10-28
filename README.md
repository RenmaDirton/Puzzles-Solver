# Puzzle solver 0.4

Floating overlay that solves **arrow mazes** (built and tested on screenshots from "Amaze Go!").
Target: Android 8.0+ (minSdk 26), arm64-v8a only.

## How it works

1. Screen capture (MediaProjection) grabs the current frame.
2. `BoardCore` finds the dot lattice, reads every line segment, and turns each connected line
   into one arrow. The head is the end whose surroundings are filled by the arrowhead triangle.
3. `Solver` marks an arrow as free when the straight line from its head to the edge crosses no
   other arrow still on the board.
4. Auto taps the solution in order (arrows with an open path first). An arrow that was already
   tapped does not hurt arrows behind it even while it is still sliding, so it does not wait for
   each round to leave. It taps a chunk, waits for the screen to settle, checks that everything
   tapped really left, and makes the next chunk bigger (or smaller after a missed tap).

Colours the game uses, and what the app does with them:

- **brown** = normal arrow
- **gold with a fading tail** = an arrow that is sliding off the board. The app does not rely on colour to know that something moves: it compares consecutive frames (an ink fingerprint) and waits until the picture stops changing.
- **red** = an arrow that hit something. Auto stops and marks it.
- faint tan dots = cleared cells (used to find the grid, never read as arrows)

## Panel buttons

- **Smart**: keeps re-reading the board and shows a green dot on each arrow that can leave right now. Dots hide while arrows slide and come back when the screen is still.
- **Auto**: waits for a still screen, scans twice (only the part of the plan both scans agree on is used), taps a chunk of the plan, waits for the screen to settle, repeats. It stops (and marks the arrow red) if a tapped arrow hits something or will not move. It never skips ahead.
- **Speed**: 1 / 2 / 3 = how fast Auto taps and how big a chunk may get.
- **Snap**: saves exactly what the app sees (without its own markers) to Pictures/PuzzleSolver, so a misread can be debugged from a real capture.
- **X**: close.

If the app ever crashes, the reason is saved and shown at the top of the app's main screen the next time you open it.

## Build

**Android Studio:** open this folder, let it sync, Run (or Build > Build APK).

**No PC tooling:** push this folder to a GitHub repo. The workflow in
`.github/workflows/build.yml` builds the debug APK; download it from the run's
Artifacts (`puzzle-solver-debug-apk`), copy to the phone, install.
Every build is signed with the key in `app/debug.keystore`, so a new APK installs over the old one and keeps its permissions
(only the very first install of a build signed with this key needs an uninstall of an older, differently signed one).
This is a throwaway key for a personal app; if the repo is public anyone can read it, so keep the repo private if that matters.

## Use

1. Open the app, tap **1** (allow draw over other apps), **2** (turn on the auto-tap service), **3** (Start, allow screen capture).
2. Switch to Amaze Go!, open a level, press **Smart** (or **Auto**) on the floating panel.

## Testing the board reader without a phone

`BoardCore.kt` and `Solver.kt` have no Android dependencies. `tools/jvmtest/CoreTest.kt` runs them on
screenshots on a normal JVM:

    kotlinc app/src/main/java/com/puzzlesolver/BoardCore.kt app/src/main/java/com/puzzlesolver/Solver.kt \
            tools/jvmtest/CoreTest.kt -include-runtime -d coretest.jar
    java -jar coretest.jar shot1.jpg shot2.jpg

(Screenshots taken while the overlay's green dots are showing are cleaned up by the test first.)
`tools/reference/` has the Python version of the same logic plus a sliding-arrows simulator.

## Known limits of 0.4

- The Android layer (overlay, capture, auto-tap) has not been compiled or run on a device by me. The board reader and solver are compiled and tested on 5 real screenshots.
- The board is looked for between 19% and 87% of the screen height; other layouts may need tuning.
- Calibrated on the default light theme; other themes are untested.
- Smart/Auto briefly hide the dots while capturing, so you may see a short flicker.
- A shape the reader cannot understand is treated as a permanent obstacle: the solver gets more careful, not reckless, but Auto may stop with "cannot move".
