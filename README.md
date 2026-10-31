# Tap timer
Shows the time (ms) between your own taps on top of any app, to practise timing games. It never taps.
Build: upload to a GitHub repo; Actions builds app-debug.apk (same workflow as before).
Use: allow "draw over other apps", press Start, open the game, tap. Finished runs are listed in the app.
How it works: a 1-pixel overlay window receives ACTION_OUTSIDE for every touch outside itself, with a timestamp.
