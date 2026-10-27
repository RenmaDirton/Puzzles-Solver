# Puzzle solver 0.1

Floating overlay that solves **arrow mazes** (tested on a screenshot from "Amaze Go!").
Target: Android 8.0+ (minSdk 26), arm64-v8a only.

## How it solves a level

1. Screen capture (MediaProjection) grabs the current frame.
2. `BoardParser` finds the dot lattice (pitch/offset auto-detected), reads every line segment,
   and turns each connected line into one arrow. The head is the end with arrowhead wings.
3. `Solver` repeatedly removes every arrow whose straight path from head to screen edge
   crosses no other remaining arrow. Removing an arrow never blocks another, so this
   order never costs a life.
4. `MarkerView` draws the tap order over the game (green = can be tapped right now).
5. Optional **Auto** taps the arrows for you through an Accessibility service.

On your sample screenshot the parser found 125 arrows on a 29x44 lattice and the solver
cleared all 125 (see `tools/proto_parser.py`, the Python reference the Kotlin code was ported from).

## Build

**Android Studio:** open this folder, let it sync, Run (or Build > Build APK).

**No PC tooling:** push this folder to a GitHub repo. The workflow in
`.github/workflows/build.yml` builds the debug APK; download it from the run's
Artifacts (`puzzle-solver-debug-apk`), copy to the phone, install.

## Panel buttons

- **Smart**: keeps re-reading the board (every ~3 s, and right after you touch the screen) and shows a green dot on each arrow that can leave right now. Dots vanish as arrows go.
- **Auto**: scan, tap every free arrow, wait, scan again, repeat. Never taps an arrow that the latest scan says is blocked. If a tapped arrow is still there after 3 s it stops and marks it red (no skipping ahead).
- **Scan**: one-off numbered plan for the whole level (static).
- **Speed**: how long Auto waits for arrows to slide off before rescanning.

## Use

1. Open the app, tap **1** (allow draw over other apps), **2** (turn on the auto-tap service), **3** (Start, allow screen capture).
2. Switch to Amaze Go!, open a level, press **Smart** (or **Auto**) on the floating panel.
3. Raise **Speed** if Auto rescans before arrows finish sliding off.

## Known limits of 0.1

- Not compiled or run on a device yet. Expect a round of fixes after the first real test.
- Board is looked for in the middle band of the screen (20%-86% of height); other layouts may need tuning.
- Calibrated on the default light theme. Other themes should work (it keys on contrast with the background) but are untested.
- Smart/Auto briefly hide the dots while capturing, so you may see a short flicker.
- Shapes the parser cannot read are treated as permanent blockers: the solver gets more careful, not reckless, but it may report arrows as stuck.
