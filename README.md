# Puzzle solver 0.6

Overlay app (Android, arm64) that solves the arrow mazes of "Amaze Go!" on a Samsung A16 (1080x2340).

## Panel
Smart | Auto | Speed | gear | X
- **Smart** (green when on): shows a dot on every arrow that can leave right now.
- **Auto** (green while running, red when it got stuck): taps the solution in order, in chunks, checks that every tapped arrow left.
- **Speed**: 1 slow, 3 fast.
- **Gear**: the routine and the utilities (below).

## The routine (gear menu)
Steps the player builds: press a word (found through accessibility labels first, then by reading the screenshot with on-device
text recognition), tap a spot (picked on a dimmed screen), wait, Back. Whenever the board is gone while Auto is on (level finished, ads,
loading), the routine runs again and again until the board is back. Tap/Back steps never run while a board is on screen.
No board for 2 minutes = Auto stops and the Auto button turns red. There is NO automatic menu or ad detection any more.

Utilities: test the routine once, stop after N levels, Snap (picture to Gallery > PuzzleSolver), the Auto log, pictures saved when stuck on/off.
Custom words for the "press a word" step are typed on the start screen.

## Board reading (BoardCore.kt, tested on the JVM, see tools/)
Grid sizes 10-170 px, thick-line boards, a grid kept for the whole level, head test relative to the line thickness.
tools/jvmtest: CoreTest.kt (boards), ButtonTest.kt (ink share); tools/synth: gen.py + score.py draw small boards with known answers and score the parser.

## Build
GitHub Actions workflow in .github/workflows/build.yml (Gradle, Java 17, Android platform 34). Fixed debug key in app/debug.keystore so updates install over each other.
Needs internet for Gradle (the text recognition library comes from Google's Maven repository).
