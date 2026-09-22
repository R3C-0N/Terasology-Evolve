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

**There is now a second, much faster way in:** launch with `--inspect-port` and
query the game over HTTP — no keystrokes, no stolen foreground, ~20 ms instead of
~5 s. See «The inspection port» below, and prefer it for anything that only reads
state or runs a console command.

All paths below are relative to `Terasology-Evolve/`. Run every command from
there.

## Prerequisites

- **JDK 17.** `build.gradle.kts` asserts at least 17 and warns on anything that
  is not exactly 17; the default `JAVA_HOME` here is 11, which fails the assert.
  On this machine: `C:\Program Files\Microsoft\jdk-17.0.20.101-hotspot`. The driver
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
`build/run-classpath.txt`. It also rebuilds `engine/build/libs/engine-*.jar`: the
classpath names that jar, not the engine's classes, so a build that skipped it
would launch the previous engine. Incremental: 8 s here with everything already built,
and expect much longer the first time, when Gradle still has to fetch
dependencies and compile. `--clean` is accepted and forces a full rebuild.

The classpath dump comes from a Gradle init script kept next to the driver,
`dump-classpath.init.gradle`, which registers `:facades:PC:dumpRunSpec` without
touching any of the project's own build files. That task depends on
`sourceSets.main.runtimeClasspath` rather than on `:facades:PC:classes`, and the
difference is load-bearing: the classpath names one jar per project dependency —
the engine and every `:subsystems:*` — and only the FileCollection carries the
tasks that build them. With `classes` alone, a newly added subsystem was written
into the classpath as a jar path that had never been built, and the game died at
startup with `NoClassDefFoundError`.

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
| Walk | `driver.py hold z --ms 2500` · `driver.py hold shift z --ms 2000` — **`z`, not `w`**, see below |
| Type into a field | `driver.py type "SkillSmoke"` |
| Turn the camera | `driver.py move 600 0 --steps 30` (relative, in mickeys) |
| Toolbar wheel | `driver.py scroll -2` |
| Console command | `driver.py console ghost` — **slow (~5 s); prefer `curl -X POST -d ghost http://127.0.0.1:17888/console` when the port is open** |
| **Aim, absolutely** | `driver.py console "look 180 30"` — yaw then pitch, in degrees |
| **Read the whole view** | `driver.py console showView` → one `key=value` line |
| Player position | `driver.py where` |
| Screenshot | `driver.py shot look.png` → `build/shots/look.png` |
| Just the F3 overlay | `driver.py shot hud.png --hud` (1000×80, cheap to read) |
| Read the log | `driver.py log --lines 20` · `driver.py log --grep CONSOLE` |
| Close | `driver.py quit` |

Key names are **physical US positions** — `w a s d`, `f1`…`f12`, `escape`,
`enter`, `space`, `shift`, `tab`, `backspace`, `end`, arrows, digits. `driver.py
key nosuchkey` prints the full list.

**Walking is `Z Q S D`, not `W A S D`.** This machine's `config.cfg` was written
on a French layout and stores `"forwards": "key_z"`, `"left": "key_q"`; the
driver sends physical scancodes, so `hold w` presses a key nothing is bound to
and the character never moves. The failure reads exactly like a broken harness
— console commands answer, `teleport` works, `F3`/`F5` work — because
`LocalPlayerSystem.processInput` is the only consumer of the movement binds.
The cheap discriminator is `driver.py move 400 0`: if the view turns, input and
focus are fine and the key is simply wrong. Read the `binds` block of
`config.cfg` rather than trusting any table, this one included.

The other default bindings that matter: `space` jumps, mouse turns,
left click attacks/mines, right click places, `1`-`0` pick a toolbar slot,
`escape` opens the pause menu, `i` the inventory, `e` interacts, `f1` (or
backtick) the console, `f3` the debug overlay, `f4` cycles its metrics, `f5`
cycles the camera view (first person → behind the hero → in front of it), `f7`
the behaviour tree editor.

`f5` has a console twin, `driver.py console cycleCameraView`, which **echoes the
mode it lands on** into the log. Prefer it whenever the current view matters:
the key is a blind toggle over state the screenshot does not always settle, and
one miscounted press leaves every later observation off by a mode.

### Aiming and reading the view, without the mouse

`look <yaw> <pitch>` and `showView` were added to the engine for this harness,
and they replace the guesswork that `move` used to be.

```bash
python .claude/skills/run-terasology/driver.py console "look 180 30"
python .claude/skills/run-terasology/driver.py console showView
# pos=4928.00,44.41,4950.00 dir=0.0000,-0.5000,-0.8660 yaw=180.00 pitch=30.00 block=4928,42,4945 uri=CoreAssets:Sand
```

`look` is **absolute**, where `move` sends relative mouse motion that Windows
pointer acceleration then bends. Degrees; yaw wraps at 360 and pitch is clamped
to ±89 — and **pitch counts positive downwards**, which is not the obvious
convention and was established by round trip, not by reading the source.
`showView` reports the same convention back, so a value it prints can be fed
straight to `look`. One caveat that is not a bug: it reports yaw in (−180, 180],
so `look 270` reads back as `yaw=-90.00`.

Prefer these to `move` for anything that has to be reproducible — two
screenshots of the same view, a before and after. `move` remains for sweeping
the camera when the exact angle does not matter.

**`console screenshot` exists but the image is black**, and not because of the
command: the buffer read-back is broken in this build, and the game's own save
previews — same call, written long before — are black too, which is why the
thumbnail beside a save is an empty rectangle. Use `driver.py shot`.

### Reading state back

Three channels, in order of preference:

1. **The inspection port — use this.** See the next section. It answers in
   ~20 ms instead of ~5 s, does not touch the keyboard, and does not steal the
   foreground window.
2. **The console, through the log.** The fallback for a game launched without
   `--inspect-port`. `ConsoleImpl` mirrors every console message into
   `logs/<run>/Terasology-<world>.log`. `driver.py where` uses this:
   `showPosition` → `Your Position: ( 1,361E+1  2,141E+1  1,537E+1)`. Also
   useful: `debugTarget` (the block under the crosshair), `showMovement`,
   `help`. Numbers come out in the French locale, comma-separated.
3. **A screenshot.** Still the only way to answer anything about what the game
   *looks* like. `shot --hud` crops to the four F3 lines — FPS, active entities,
   current target, position, world time — which is usually all you need and much
   smaller to look at than the full 1280×800 frame.

## The inspection port — the fast channel

`subsystems/Inspect/` opens a small HTTP server on the loopback when the game is
launched with `--inspect-port`. It answers with plain text, from the game thread,
without a single keystroke.

```bash
python .claude/skills/run-terasology/driver.py launch --load-last-game -- \
    --inspect-port=17888 --inspect-allow-console

curl http://127.0.0.1:17888/health
curl http://127.0.0.1:17888/view
curl "http://127.0.0.1:17888/commands?q=look"
curl -X POST -d "look 42 10" http://127.0.0.1:17888/console
```

| Route | What it answers |
|---|---|
| `GET /health` | `ok state=… last_tick_ms=… queue=… uptime_s=…`. Answered on the HTTP thread, so it still replies when the game thread does not — this is the liveness probe |
| `GET /view` | The `showView` line: position, direction, yaw, pitch, targeted block |
| `GET /commands` | Every registered console command with its usage and description; `?q=` filters. 121 commands unfiltered overrun the 8 KiB cap, so filter |
| `POST /console` | Runs one command and returns its output. Also `GET /console?cmd=…` |
| `GET /slice` | A cross-section of the world as characters — `axis=x\|y\|z`, `w`, `h`. **Use this instead of a screenshot** for anything about block layout, caves, or liquids |
| `GET /cube` | The dense neighbourhood, layers in descending Y. `r` ≤ 8 |
| `GET /surface` | Top-down map: material and height per column, heights in base 36. `r` ≤ 24 |
| `GET /block` | One cell, in the exact vocabulary of the `liquidFlow` command |
| `GET /stats` | FPS, heap, entity count, chunks, world time — **and `paused=true`**, which otherwise only shows as two identical screenshots |
| `GET /entities` | One line per entity: id, prefab, position, component **names**. `r=`, `with=`, `limit=` |
| `GET /entity/…` | Every component value. An id, or the aliases `player`, `client`, `target` |

**The grids centre on the player by default, never on the aimed block** — water
and lava are `targetable: false`, so the crosshair ray goes straight through a
pool and lands on the solid behind it. `at=target` opts into the aim.

Three characters are reserved and the distinction carries the whole point: `.`
empty, `?` **unreadable** (no ready chunk covers it), `~` liquid. Liquid flow
comes back as a **second grid**, cropped to the rows that hold liquid and omitted
when there is none — `0` is a permanent source, and a fall resets the count to 1.
Measured cost: `/slice` 24×16 is 0.40 ms, 48×32 is 0.80 ms, `/cube?r=4` 0.90 ms,
`/surface?r=24` 2.20 ms for 82,793 probes.

A cap that would be exceeded is **refused with 400, never silently clamped**.

**There is also an MCP server** — `.claude/mcp/terasology_inspect.py`, declared
in `.mcp.json` — exposing all of the above as eleven `tera_*` tools. Use those
when they are available; `curl` stays valid and equivalent. Standard library
only, so there is nothing to install.

**Prefer `curl …/console` to `driver.py console` whenever the port is open.**
Measured on `showView`: **5177 ms through the keyboard, 23 ms through the port**,
byte-identical output. The keyboard path also carries every gotcha below — a
console left open inverting later calls, a NUI screen swallowing the key — and
the port carries none of them.

Three things to know:

- **`/console` needs `--inspect-allow-console` and returns 404 without it.** The
  route resolves the command and calls it directly, which **bypasses the
  permission check**; the command registry is open, so `teleport`, `giveBlock`
  and `ghost` are all reachable. Never add this flag to the `smoke` path.
- **The refusals are distinct on purpose:** `503` the game thread did not drain
  in time (busy, loading, shutting down), `409` it drained but there is no world
  or no player, `400` bad parameter, `404` unknown command or closed route.
  A `409 no-player` while a world loads is normal — poll it.
- **Losing focus costs about 80 ms per call.** `HibernationSubsystem` sleeps the
  main loop 100 ms/frame when the window is not focused, and the channel drains
  once per frame. Measured: `last_tick_ms` 18 focused, 93 unfocused. Still sixty
  times faster than the keyboard, and it is the normal case — the whole point is
  not to steal the foreground.

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
- **A synthetic right click only places a block when it is held.** `click
  --button right` on its own does nothing perhaps four times out of five, and
  the failure is silent — the block count never drops. `click --button right
  --press 0.4` places every time. The same holds for `e` on a workstation: aim
  first, verify with a screenshot, then send the key.
- **A station recipe is invisible until the character has touched the station.**
  `StationRecipe.isAvailableTo` wants an `AtStationComponent`, so the crafting
  panel lists only the stationless recipes — three of them — until you press `e`
  on a workbench in reach. Place one, aim at it, `key e`: the same press opens
  the character screen with the list already filled.
- **To find an item, use creative mode, not the recipe list.** `console creative`
  then `i` opens the catalogue, whose **search field** (top right, `click 1050 145`
  then `type`) filters the whole tab at once and prints the count — one command
  against a dozen scroll-and-stitch rounds. It is also the fastest way to check
  that a new prefab loads and its icon resolves: a missing icon shows as `?`.
  What it does *not* show is the detail panel, so the weapon-type label and the
  ingredient list still need the workbench.
- **Hunting a recipe by scrolling costs more than you think**, when you must.
  The list shows three rows, and one wheel notch moves it a *third* of a row —
  nine notches per screenful. Stitch the crops rather than reading them one by
  one: `shot --crop 930,175,290,180` after every `scroll -3` ×3, then paste ten
  of them into one image. And the order is craftable-first over an arbitrary
  prefab walk, so it changes under you the moment you craft something.
- **A workbench does not have to be placed by hand.** `click --button right`
  fails silently when the crosshair is on the block under your own feet, which is
  where it lands by default. `console replaceBlock CoreSampleGameplay:Workbench`
  turns that very block into the station, in reach and under the crosshair, ready
  for `key e`. And `console debugTarget` names the aimed block, which is the only
  reliable way to know what you are pointing at.
- **Ghost mode blocks placing.** `console ghost` is the cure for a character
  stuck in terrain, but while it is on, right click places nothing and gives no
  message — the stack count simply never drops. Toggle it back off (`ghost`
  again) before building anything. And a character boxed in by blocks *you*
  placed at its feet reads exactly like a broken driver: keys, mouse and console
  all answer, only the position never changes. Check `shot --hud` against the
  previous position before blaming the input.
- **`ghost` is a blind toggle, and toggling it in mid-air over lava kills
  you.** Nothing reports which way it went, so a second `ghost` sent "to be
  sure" turns it *off* — and if you were hovering, you fall. Over lava that is
  eight damage a second against twenty health: two deaths in one session, both
  from exactly this. Only ever toggle it while standing on the ground, and
  teleport *after*, never before.
- **`replaceBlock` cannot remove a liquid — it will dig the ground instead.**
  Water and lava are `targetable: false`, so the crosshair ray goes straight
  through them and lands on the solid block underneath; `replaceBlock
  engine:air` then deletes *that*, and the liquid pours into the hole you just
  made. What works is the opposite move: liquids are `replacementAllowed`, so
  **placing** a block lands *in* the liquid and removes it. `give
  CoreAssets:Grass`, then right click with `--press 0.4`.
  Two things make it practical. Only the **source** (`liquidFlow` says
  `flow=0`) has to go — the rest drains itself, and a source is almost always
  the block you originally converted, so putting the original terrain back is
  exact. And because the ray ignores liquids, you can aim *through* a pool at
  a solid face on its far side: `showView` names the block, and the placement
  lands on the near side of it. That is how to reach a source you cannot walk
  up to without burning. The floor is what limits you, not distance — a ray
  cast from standing height grazes the ground about three blocks out, so aim
  from slightly above, or place one block at the pool's edge, stand on it, and
  shoot from there.
- **`console` is swallowed while a NUI screen is open.** The inventory and the
  crafting window take the key that opens the console, so `driver.py console`
  looks like it ran and nothing happens — check the log for the echo rather
  than trusting the exit code. Send `escape` first.
- **A console left open inverts every later `console` call.** The sub-command
  opens with F1 and closes with F1, so if the console was *already* up — the
  usual cause is a `where` that timed out while the world was still loading —
  the first press closes it, the text goes to the character, and the closing
  press opens it again, ready to swallow the next call too. It looks exactly
  like a driver that types too fast. `driver.py key escape` before the next
  `console`, and check the `[CONSOLE]` echo in the log, never the exit code.
- **`where` answers nothing until the world is up**, and world generation for a
  *new* world runs well past the 30 s of an existing save. Poll it in a loop
  rather than reading one failure as a broken game — and send `escape` after a
  failed poll, per the gotcha above.
- **`escape` with no screen open opens the pause menu, and the pause menu stops
  the simulation.** Nothing else betrays it: `console` still answers and its
  echo still lands in the log, `key` and `click` still go through, the HUD still
  draws — only the position never changes, `hold w` does nothing, and two
  screenshots three seconds apart differ by *zero* pixels. That last one is the
  tell, and it is the cheapest test: a live world always moves a few pixels.
  This is the trap set by the previous two gotchas, which both prescribe
  `escape` — send it only when a screen is actually open, and take a full `shot`
  before diagnosing anything as frozen. `click 640 291` is `Retour`.
- **The menu coordinates below are the ones that count; the old ones are two
  redesigns out of date.** Main menu: `Solo` at `640 320`. The solo screen is a
  save list — `Nouvelle partie` at `289 680`, `Jouer` at `1090 680`. The
  new-game screen carries a *game mode* list: `Core Gameplay` at `640 303`,
  `Créatif` at `640 368`, the name field at `640 211`, `Commencer à jouer` at
  `896 684`. Picking `Créatif` gives a character whose `i` opens the block
  catalogue instead of the crafting screen; `console creative` toggles that off.
  Take a `shot` of every menu anyway — these move.
- **`--no-save-games` is a trap, not a flag.** It persists as
  `writeSaveGamesEnabled: false` in
  `configs/engine/org.terasology.engine.config.SystemConfig.cfg`, and from then
  on *every* launch fails with `Failed to load game. There was an error during
  "Registering World Systems..."` — `StorageManager` is never bound so
  `LocalChunkProvider` cannot be injected. Nothing in the UI undoes it.
  `driver.py launch` detects and repairs it. Do not pass that flag.
- **`F3` is a toggle over a persisted value**, so pressing it blind turns the
  overlay *off* half the time. Set it with `driver.py debug on` before launching.
- **The HUD may be switched off, and nothing says so.** `config.cfg` persists
  `"hudHidden": true` once somebody has pressed `H`, and from then on every
  screenshot shows the world and the F3 text but no hotbar, no health orbs, no
  crosshair — and no HUD element any module adds. A new overlay then looks
  broken when it is drawing perfectly: its `onDraw` runs, its region is the
  whole screen, and not one pixel reaches the glass. Check that line of
  `config.cfg` before debugging a widget. `H` toggles it back, but **only once
  F3 is off** — with the debug overlay up, `H` opens the debug documentation
  instead.
- **Two `driver.py` calls at once deadlock each other.** Each brings the window
  to the foreground, so a second one launched while the first is still working
  leaves both spinning until their timeouts. Run them one at a time; if
  everything starts timing out, `tasklist | grep python` and kill the strays
  before blaming the game.
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
