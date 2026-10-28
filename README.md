# Puzzle solver 0.2

Floating overlay that solves **arrow mazes** (built and tested on screenshots from "Amaze Go!").
Target: Android 8.0+ (minSdk 26), arm64-v8a only.

## How it works

1. Screen capture (MediaProjection) grabs the current frame.
2. `BoardCore` finds the dot lattice, reads every line segment, and turns each connected line
   into one arrow. The head is the end whose surroundings are filled by the arrowhead triangle.
3. `Solver` marks an arrow as free when the straight line from its head to the edge crosses no
   other arrow still on the board.
4. `Planner` picks which free arrows may be tapped together: only arrows whose exit paths do not
   cross. (Two arrows that are both free can still crash into each other while sliding if their
   exit paths cross. Simulated on real boards this was ~10-15% of taps; with the rule it is 0.)

Colours the game uses, and what the app does with them:

- **brown** = normal arrow
- **gold with a fading tail** = an arrow that is sliding off the board. The app waits until no gold is left before it reads the board again.
- **red** = an arrow that hit something. Auto stops and marks it.
- faint tan dots = cleared cells (used to find the grid, never read as arrows)

## Panel buttons

- **Scan**: one-off numbered plan for the whole level (static).
- **Smart**: keeps re-reading the board and shows a green dot on each arrow that can leave right now. Dots hide while arrows slide and come back when the screen is still.
- **Auto**: waits for a still screen, scans twice (only arrows both scans agree on are used), taps a safe batch, waits for the sliding to finish, repeats. It stops (and marks the arrow red) if a tapped arrow hits something or will not move. It never skips ahead.
- **X**: close.

## Build

**Android Studio:** open this folder, let it sync, Run (or Build > Build APK).

**No PC tooling:** push this folder to a GitHub repo. The workflow in
`.github/workflows/build.yml` builds the debug APK; download it from the run's
Artifacts (`puzzle-solver-debug-apk`), copy to the phone, install.
Debug APKs from CI are signed with a different key every run, so uninstall the old version before installing a new one.

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

## Known limits of 0.2

- The Android layer (overlay, capture, auto-tap) has not been compiled or run on a device by me. The board reader and solver are compiled and tested on 5 real screenshots.
- The board is looked for between 19% and 87% of the screen height; other layouts may need tuning.
- Calibrated on the default light theme; other themes are untested.
- Smart/Auto briefly hide the dots while capturing, so you may see a short flicker.
- A shape the reader cannot understand is treated as a permanent obstacle: the solver gets more careful, not reckless, but Auto may stop with "cannot move".
