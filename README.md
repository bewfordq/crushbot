# This project is fully ai generated!!!!!!!!!!!

# Crushbot

A bot that plays the **Crown Crush** Android game (`com.gl.crowncrush`) for you. Crown Crush is a
tower smasher: you hold to drop the ball through spinning plates, and let go before it lands on a
cursed stone. Crushbot holds and lets go at the right moments, moves on to the next level, restarts
a tower it lost, and gets through the level-start cards and the daily-streak screens.

It runs as an app on the same phone or emulator as the game. It watches the screen, works out where
every cursed stone is and how fast the tower turns, and times each hold to the game's frames.

> **Unofficial.** Crushbot has no connection to the makers of Crown Crush. Botting probably breaks
> the game's terms of service, so use it at your own risk. It never presses the video-ad buttons
> (see [Safety](#safety)).

---

## Phone app

The simplest way to get it: download [`Crushbot.apk`](Crushbot.apk) from this repo.

### What you need

- An Android phone or emulator on **Android 11 or newer**.
- Crown Crush installed.
- A screen **1080 pixels wide**. The bot reads the tower in screen pixels; it was built and tested
  on a 1080 x 2424 emulator. Other sizes will need the numbers at the top of `Eye.java` changed.

### Install

1. Copy **Crushbot.apk** to the phone and open it. Allow installing from that app when Android
   asks. Because the APK isn't from the Play Store, Play Protect may warn you about it.
2. Open **Crushbot** and tap **Turn on the Crushbot player**, then switch on **Crushbot player** in
   the Accessibility list. This is how the bot touches the screen.
   - If Android says **"Restricted setting"** (Android 13 and newer, for apps installed from a
     file), go to Settings > Apps > Crushbot, tap **⋮** (top right) > **Allow restricted
     settings**, then try again.
3. Allow notifications when asked (the screen capture needs one while it runs).

### Play

1. Press **Start** in the app. Set *Stop after this many levels* first if you want it to stop by
   itself (0 = keep going).
2. Android asks to share the screen. Choose **Entire screen**, then **Start**. The bot has to see
   every frame to time its moves.
3. The bot brings Crown Crush to the front and plays.

A small **⏸** pill at the top of the screen pauses or resumes the bot (tap) and stops it (hold).
The app shows the log and how many levels it won.

### Optional: skip the screen-share question (needs a PC once)

With USB debugging on and the phone plugged into a computer that has `adb`:

```
adb shell appops set com.crushbot.app PROJECT_MEDIA allow
```

Over adb you can also start and stop it:

```
adb shell am start -n com.crushbot.app/.MainActivity --ez start true [--ei levels N]
adb shell am start -n com.crushbot.app/.MainActivity --ez stop true
```

---

## How it plays

The rules come from the game's own code (its scripts, decompiled to read them; none of it is
included here):

- A tower is a stack of plates, each made of 12 segments of 30°. Some segments are cursed (dark).
  The tower spins at a speed set per level and stage. While you hold, the ball smashes the top
  plate, and if a cursed segment is under the ball you lose. The ball covers the front of the
  plate (81° to 99°). The first cursed hit in 5 seconds is forgiven, but the game drops the hold.

What the bot does with that:

- **Reading.** The tower is drawn 1:1 on screen: centre x = 540, radius 400, plates 68 px apart.
  The top plate's cap is a flat ellipse, and its edges give the tower's height. The two
  alternating segment shades give the rotation (modulo 30°). Each visible plate's segments read as
  cursed (very dark) or not. Odd plates are drawn 12% darker, so the top plate's shade shows when
  one was smashed.
- **Remembering.** The bot keeps a map of all 12 segments of every plate, built from many frames
  as the plate comes up the tower. It tracks the rotation by lining each new frame up with the map,
  because one frame can turn the tower further than a segment. That way pieces of smashed plates
  flying across the front, or curses hidden until they're near the top (the *Night Curse*
  decree), don't fool it.
- **Timing.** A touch sent right after a frame takes effect two game frames later (measured). So
  after each frame the bot decides about the frame after next. It holds only if the plate that
  will be on top then is safe at every angle the tower can have turned to by then, with 4° to
  spare. On a slow device the game moves on at most 8/60 s per frame (the Godot engine's limit),
  so every frame turns the tower by the same angle.
- **Fever.** A full fever bar makes the ball unstoppable for 3.2 s. The bot holds straight through
  it and stops before the bar runs out. It only trusts a fever once the bar has been full and has
  started to drain, because a bar that stops just short of full is still charging.
- **Stages.** A level can have up to five stages. A new stage slides in from the left; the bot
  then forgets the old stage's plates and works out the new speed. The gold base under the last
  plate shows how many plates are left. The last six plates go in slow motion, and the bot allows
  for both speeds there.
- **When unsure, let go.** A frame where flying pieces hide the cap is skipped with the finger up.
  A hold that should have smashed but didn't (an armoured boss plate, or a forgiven cursed stone)
  gets a fresh press.

## How well it does

On the emulator it was tested on, the game only drew 3 to 12 frames a second (other apps running
in the background took much of the CPU). In about two hours of testing, from level 1 to level 68,
it won every level once the timing was right. That covered boss towers, multi-stage towers, the
decree levels and fevers. It plays about 15 to 35 levels an hour, fewer on the long late levels. Most of the time goes to the game's frame rate: the bot can only smash
one plate per frame.

## Screens

| Screen | What the bot does |
|---|---|
| Title | presses the orange **Level N** button |
| Won (*TOWER SMASHED!*) | presses **Level N**, never **▶ 2× Coins** |
| Lost (*Smashed by the curse!*) | presses **Restart tower**, never **▶ Keep smashing!** |
| Royal decree and song cards | presses **SMASH!** |
| Daily streak | stamps the seal, then **Continue**; a broken streak gets **Start fresh**, not the video |
| System dialogs | **Wait** on "isn't responding", **Don't allow** on permission prompts |
| Anything else | waits, then presses back now and then |

## Safety

- It never presses a button with ▶ on it: those play a rewarded video ad.
- It never opens the shop, and never touches anything outside the game except the system dialogs
  above.
- **Nothing leaves your device.** The app has no internet permission. The screen capture is only
  read in memory, and only while the bot runs.
- **Playout.** The game reports play to Playout (`io.playout`), a rewards app that pays users for
  time spent in partner games, out of the ad revenue those games earn. Playout checks for
  emulators. Bot play is not the "playtime" it pays for, so expect it to count for nothing, or to
  get an account flagged. Crushbot doesn't touch Playout or ads in any way.

---

## Building the APK

No Gradle: `build.sh` uses the Android SDK's own tools (aapt2, javac, d8, apksigner).

You need JDK 17 or newer and the Android SDK (a platform and build-tools), with `ANDROID_HOME` set
(or the SDK at `~/Android/Sdk`).

```
./build.sh            # makes Crushbot.apk
./build.sh install    # makes it and installs it with adb
```

The first build makes a signing key, `debug.keystore`. It stays on your machine and isn't in the
repo. An APK you build is signed with your own key, so uninstall the downloaded one first.

## Testing

`tools/run.sh` runs the bot over adb for a while and prints its log:

```
tools/run.sh 300                    # 5 minutes
tools/run.sh 300 "verbose=true"     # with a line for every frame
```

Settings for testing go in a tune string (`--es tune "a=1,b=2"` on the start command, kept for
later runs; `""` resets them):

| Setting | What it does |
|---|---|
| `margin` (4) | degrees of safety either side of the ball |
| `delay` (2) | game frames between sending a touch and the game acting on it |
| `verbose` | log every frame: rotation, plates smashed, what it read, hold or not |
| `saveScreens` | keep pictures of the screens it pressed on, and of odd frames, in `/sdcard/Android/data/com.crushbot.app/files/debug/` |

`--ef capScale 0.5` sets the capture size (half the screen by default).

On the PC, `Dev look` reads screenshots the way the bot does:

```
mkdir -p build/dev && javac -d build/dev $(find src/core src/desktop -name '*.java')
java -cp build/dev com.crushbot.desktop.Dev look screenshot.png
java -cp build/dev com.crushbot.desktop.Dev screen screenshot.png
```

## Files

```
build.sh                  builds the APK
AndroidManifest.xml, res/ the app's manifest and resources
src/core/                 plain Java, shared by the app and the PC tools
  Eye.java                reads a frame: cap, rotation, cursed segments, fever bar, plates left
  Player.java             tracks one tower from frame to frame and decides when to hold
  Screens.java            buttons and screens around the tower
  Bot.java                the main loop, the screens, the timing probe
src/android/              the app
  BotService.java         accessibility service: holds and lets go, taps, the pause pill
  CaptureService.java     screen capture (MediaProjection) at half size
  MainActivity.java       setup, start/stop, log
src/desktop/Dev.java      PC tool: read screenshots like the bot
tools/run.sh              run the bot over adb and print its log
Crushbot.apk              the built app
```

## Troubleshooting

- **"Restricted setting" when turning on the accessibility service**: Settings > Apps > Crushbot >
  **⋮** > **Allow restricted settings**, then turn it on again.
- **Start does nothing**: the screen share was declined. Press Start again and choose **Entire
  screen**.
- **The bot never holds**: check the screen is 1080 pixels wide (`adb shell wm size`).
- **The bot is stuck on a screen**: turn on `saveScreens` and look at the newest picture in the
  debug folder. Anything it doesn't recognise, it waits on, then presses back.
- **It's slow**: the bot is as fast as the game's frame rate. Close apps running in the background.

## Credits

Crown Crush and its artwork belong to its developers. Crushbot includes none of the game's code or
assets.
