# Puzzle solver 0.9

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
- **Auto** (0.6: keeps going level after level - presses Next Level on the win screen, waits through ads and presses clearly labelled Close/Skip buttons; needs the accessibility service switched off and on once after this update because it now may read button labels): scans twice (only the part of the plan both scans agree on is used), taps a chunk of the plan (one tap at a time, so Android never cancels one), pauses briefly, repeats. There is no 'still screen' wait any more. It stops (and marks the arrow red) if a tapped arrow hits something or will not move. It never skips ahead.
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

## Known limits of 0.9

- The Android layer (overlay, capture, auto-tap) has not been compiled or run on a device by me. The board reader and solver are compiled and tested on 5 real screenshots.
- The board is looked for between 19% and 87% of the screen height; other layouts may need tuning.
- Calibrated on the default light theme; other themes are untested.
- Smart/Auto briefly hide the dots while capturing, so you may see a short flicker.
- A shape the reader cannot understand is treated as a permanent obstacle: the solver gets more careful, not reckless, but Auto may stop with "cannot move".

## 0.7 notes
- Menu screens (Next Level, Continue, ...) are found by their shape (a wide centred button), not by colour. The top button is pressed.
- The app notes which screen (activity) the game is in. A different screen in front = an ad: Auto waits and looks for a Close/Skip/X. The game's own screens are never treated as ads, so nothing waits for an ad that is not there.
- Pitch range of the board scan raised from 100 to 170 px for boards with big cells. Small easy boards are only lightly tested: send a Snap picture if one fails.
- The accessibility service must be switched off and on once after installing this version (new event type).
- tools/jvmtest/ButtonTest.kt checks the button finder on screenshots.

## 0.8 notes
- Boards with big cells (easy levels, up to 170 px) and tiny cells (level 135, 15 px) are read: the grid search sizes its filter from the line thickness, thick lines are reduced to a sharp centre line, a doubled grid is detected and halved, and arrow heads are judged relative to the line thickness of the board instead of fixed pixel sizes. tools/jvmtest + tools/synth (gen.py, score.py) draw small boards with known answers and score the parser.
- Ads: if an ad opens another app (Google Play), Auto presses Back (at most 4 times); in an ad it presses the top-right corner (the usual ">>" or X) when it looks like a button.

## 0.9 notes (smarter, not just more rules)
- Every press outside the board is checked: if the screen did not change, that spot is never pressed again (no more endless pressing of a button that is not there). If it threw the game out of the foreground, it is marked bad.
- ScreenMemory: the app remembers screens (tiny pictures) where a press was followed by the board coming back, and presses that first next time (needs 2 successes). Stored in the app's private files, survives updates.
- A board is only accepted as a board when it reads as one (>=3 arrows and more arrows than unreadable shapes) when a big button is on screen; our own panel is excluded from button detection.
- Calibration remembers how well the grid explained the ink when it was fitted, so sparse boards with big arrows no longer refit on every scan (scan 300 ms -> 30 ms).
- Auto log (start screen) and automatic pictures (Gallery > PuzzleSolver, auto_*.png) are written whenever Auto gets stuck.
