---
name: run-terasology
description: Build, launch, drive and screenshot the Terasology game on Windows - click through its menus, move the character, aim the mouse, send any key, run console commands, read the player position, and close it. Use for "run Terasology", "start the game", "screenshot the game", "test this in-game", "build Terasology".
---

# Running and driving Terasology

Terasology is a Java 17 / Gradle voxel game with an LWJGL (GLFW + OpenGL) window.
There is no headless-with-graphics mode: the game opens a real window on the
desktop and takes the foreground while you drive it.

Everything below goes through one harness,
`.claude/skills/run-terasology/driver.py` — a stateless Python CLI. Each
sub-command finds the running game by its window title, does one thing, and
exits. Keys and mouse go in through Win32 `SendInput`; screenshots are a
`BitBlt` of the window's client area; the player position comes back as text
through the in-game console and the log.

All paths below are relative to `Terasology-Evolve/`. Run every command from
there.

## Prerequisites

- **JDK 17, exactly.** `build.gradle.kts` asserts it and refuses 11 or 21. On
  this machine: `C:\Program Files\Microsoft\jdk-17.0.20.101-hotspot`. The driver
  sets `JAVA_HOME` itself; override with the `TERA_JAVA_HOME` environment
  variable if the JDK moves.
- **Python 3** (3.11 here). Standard library only — `ctypes` and `zlib`, no pip
  install.
- A desktop session. The window must be visible: `SendInput` goes to the
  foreground window and the screenshot reads back pixels from the screen.

## Build

```bash
python .claude/skills/run-terasology/driver.py build
```

Compiles the engine, the subsystems, the modules under `modules/`, extracts the
LWJGL natives into `natives/`, and writes the runtime classpath to
`build/run-classpath.txt`. Incremental and fast (~8 s when everything is
up to date); the first build of a fresh clone downloads dependencies and takes
much longer. `--clean` is accepted and forces a full rebuild.

The classpath dump comes from a Gradle init script kept next to the driver,
`dump-classpath.init.gradle`, which registers `:facades:PC:dumpRunSpec` without
touching any of the project's own build files.

To force one compilation unit to re-run (Gradle hashes content, so `touch` is
not enough):

```bash
JAVA_HOME='C:\Program Files\Microsoft\jdk-17.0.20.101-hotspot' ./gradlew.bat :facades:PC:compileJava --rerun --console=plain
```

## Run — the agent path

```bash
python .claude/skills/run-terasology/driver.py launch                    # main menu
python .claude/skills/run-terasology/driver.py launch --load-last-game   # straight into the newest save
python .claude/skills/run-terasology/driver.py launch --load-last-game --debug   # + F3 overlay
```

`launch` starts `java` directly off the dumped classpath — not through Gradle —
so the driver owns the PID and can close it cleanly. It returns once the window
exists *and* has had 8 s to paint. It also attaches to a game somebody else
started (`./gradlew.bat game`, an IDE): every other sub-command finds the window
by title.

Verify the whole chain in one command (~60 s, exit code 0 on success):

```bash
python .claude/skills/run-terasology/driver.py smoke
```

It launches on the last save, switches the player to ghost mode, checks the
position is stable while idle, holds `W`, checks the position changed, writes
`build/shots/smoke-1-loaded.png` and `smoke-2-moved.png`, and quits. **It moves
the character in the newest save and the game autosaves**, so it edits that
world; it restores walking mode on the way out. Pass `--keep` to leave the game
running.

### Driving

| What | Command |
|---|---|
| Where is the game | `driver.py status` |
| Click a menu button | `driver.py click 640 368` (client pixels) |
| Move the cursor only | `driver.py pos 640 368` |
| Right / double click | `driver.py click 640 368 --button right --count 2` |
| Mine (hold the button) | `driver.py click --press 3.0` |
| Tap keys | `driver.py key escape` · `driver.py key f3` · `driver.py key 0` |
| Walk | `driver.py hold w --ms 2500` · `driver.py hold shift w --ms 2000` |
| Type into a field | `driver.py type "SkillSmoke"` |
| Turn the camera | `driver.py move 600 0 --steps 30` (relative, in mickeys) |
| Toolbar wheel | `driver.py scroll -2` |
| Console command | `driver.py console ghost` · `driver.py console "teleport 20 55 20"` |
| Player position | `driver.py where` |
| Screenshot | `driver.py shot look.png` → `build/shots/look.png` |
| Just the F3 overlay | `driver.py shot hud.png --hud` (1000×80, cheap to read) |
| Read the log | `driver.py log --lines 20` · `driver.py log --grep CONSOLE` |
| Close | `driver.py quit` |

Key names are **physical US positions** — `w a s d`, `f1`…`f12`, `escape`,
`enter`, `space`, `shift`, `tab`, `backspace`, `end`, arrows, digits. `driver.py
key nosuchkey` prints the full list.

The default bindings that matter: `W A S D` move, `space` jumps, mouse turns,
left click attacks/mines, right click places, `1`-`0` pick a toolbar slot,
`escape` opens the pause menu, `i` the inventory, `e` interacts, `f1` (or
backtick) the console, `f3` the debug overlay, `f4` cycles its metrics.

### Reading state back

Two channels, in order of preference:

1. **The console, through the log.** `ConsoleImpl` mirrors every console message
   into `logs/<run>/Terasology-<world>.log`. `driver.py where` uses this:
   `showPosition` → `Your Position: ( 1,361E+1  2,141E+1  1,537E+1)`. Also
   useful: `debugTarget` (the block under the crosshair), `showMovement`,
   `help`. Numbers come out in the French locale, comma-separated.
2. **A screenshot.** `shot --hud` crops to the four F3 lines — FPS, active
   entities, current target, position, world time — which is usually all you
   need and much smaller to look at than the full 1280×800 frame.

The debug overlay is off by default. `launch --debug` turns it on; `driver.py
debug on|off` sets it for the next launch. Do not press `f3` blind — see below.

## Run — the human path

```bash
JAVA_HOME='C:\Program Files\Microsoft\jdk-17.0.20.101-hotspot' ./gradlew.bat game --console=plain
```

Builds and launches, and blocks the shell until the game window closes.
`driver.py quit` closes it and the Gradle task then reports `BUILD SUCCESSFUL`.
Other run targets exist in `facades/PC/build.gradle.kts`: `debug` (JDWP on 1044),
`profile` (Flight Recorder), `server` (headless).

## Gotchas

- **Keys are sent as scancodes, never virtual key codes.** GLFW maps the
  scancode out of `lParam` through a table hardcoded to the US physical layout.
  This machine has a French layout: a virtual-key `W` would arrive as
  `GLFW_KEY_Z` and the character would strafe instead of walking. Text (`type`)
  goes in as `KEYEVENTF_UNICODE` instead, which is layout-proof by construction
  and is what reaches the NUI text fields.
- **A synthetic `F12` does not reach the game.** The in-game screenshot binding
  never fired and no file appeared in `screenshots/`, while `F1`, `F3` and `H`
  sent the same way all worked — Windows filters that one key. Use
  `driver.py shot`; the game's own `ScreenGrabber` still works for its automatic
  save previews.
- **The window exists several seconds before it paints.** A screenshot taken
  when the window first appears is solid white. `launch` waits 8 s for this;
  after `Commencer à jouer`, world generation needs another 20-30 s.
- **`--no-save-games` is a trap, not a flag.** It persists as
  `writeSaveGamesEnabled: false` in
  `configs/engine/org.terasology.engine.config.SystemConfig.cfg`, and from then
  on *every* launch fails with `Failed to load game. There was an error during
  "Registering World Systems..."` — `StorageManager` is never bound so
  `LocalChunkProvider` cannot be injected. Nothing in the UI undoes it.
  `driver.py launch` detects and repairs it. Do not pass that flag.
- **`F3` is a toggle over a persisted value**, so pressing it blind turns the
  overlay *off* half the time. Set it with `driver.py debug on` before launching.
- **A character in a hole makes input look broken.** `hold w` moves nobody when
  the save left the player boxed in, standing against a slope, or inside rock —
  which is exactly what happens after a `teleport` into terrain. Send
  `console ghost` first: no collision, no gravity, and forward follows the gaze
  at about 5 blocks per second.
- **`teleport x 90 z` kills you.** Fall damage, then the death screen, and every
  later keystroke goes to that screen instead of the character. Teleport a
  couple of blocks above the surface, or ghost.
- **The UI is in French on this machine** (`Solo`, `Créer`, `Commencer à jouer`,
  `Retour`, `Quitter Terasology`). Click coordinates in the table below are for
  the 1280×800 window this config opens; take a `shot` first rather than
  trusting them after any resolution change.
- **Mouse motion is relative and unscaled.** `move 0 -180` is about 85° of pitch
  at the configured `mouseSensitivity: 0.25`. Windows pointer acceleration
  applies, so large jumps are not linear — `--steps` splits the motion.
- **The Gradle init script must check `rootProject.name`.** An init script also
  runs for the included build `build-logic`, where `:facades:PC` does not exist
  and the script fails the whole build.
- **The runtime classpath is 124 entries** and goes through a JVM `@argfile`
  (`build/run-args.txt`) — on the command line it overruns the Windows 32k
  limit. Backslashes are doubled inside that file.

### A worked menu path — new solo world

Coordinates for the 1280×800 window, verified in this session:

```bash
python .claude/skills/run-terasology/driver.py click 640 368   # Solo
python .claude/skills/run-terasology/driver.py click 486 688   # Créer
python .claude/skills/run-terasology/driver.py click 640 350   # the name field
python .claude/skills/run-terasology/driver.py key end
python .claude/skills/run-terasology/driver.py key backspace backspace backspace backspace backspace backspace backspace backspace backspace backspace backspace backspace
python .claude/skills/run-terasology/driver.py type "DriverCheck"
python .claude/skills/run-terasology/driver.py shot namecheck.png --crop 380,300,520,110   # read the field back
python .claude/skills/run-terasology/driver.py click 716 555   # Commencer à jouer
```

The field starts pre-filled (`Game 2`, `Game 3`, …), hence `end` plus a dozen
backspaces rather than a count that has to be right. `click 564 555` is
`Retour au menu principal` if you would rather not create the world.

## Troubleshooting

| Symptom | Cause and fix |
|---|---|
| `Failed to load game … "Registering World Systems…"` | `writeSaveGamesEnabled: false` in `configs/engine/…SystemConfig.cfg`. Delete the key, or just run `driver.py launch`, which repairs it. |
| `no Terasology window found` | Nothing is running, or it is still starting. `driver.py status`, then `driver.py log --lines 30`. |
| Screenshot is blank white | Taken before the first paint. Wait and shoot again. |
| Keys do nothing, position frozen | Look at a full `shot`: death screen, pause menu, or the character is stuck in terrain. `console ghost` for the last one. |
| `Unknown command 'showPosition'` | No world is loaded — those commands only exist in the game context, not at the main menu. |
| `driver.py quit` says "still up …, killing" | The game ignores `WM_CLOSE` while a modal error dialog is up. The fallback `taskkill` is fine; the save is written by autosave, not by the shutdown. |
| `assert JavaVersion.current().isCompatibleWith(VERSION_17)` | `JAVA_HOME` points at 11 or 21. Set `TERA_JAVA_HOME` or fix `JAVA_HOME`. |
| `java.io.FileNotFoundException: \\?\pipe\discord-ipc-0` in the log | Harmless. The DiscordRPC subsystem cannot find Discord. |

## Files

```
.claude/skills/run-terasology/
  SKILL.md                     this file
  driver.py                    the harness - build, launch, input, screenshots, quit
  dump-classpath.init.gradle   Gradle init script that dumps the runtime classpath
```

The driver writes only under `build/` (`run-classpath.txt`, `run-args.txt`,
`driver-state.json`, `game-stdout.log`, `game-stderr.log`, `shots/`), all of
which `.gitignore` already covers.
