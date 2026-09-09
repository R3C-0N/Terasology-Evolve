#!/usr/bin/env python3
"""Drive Terasology from the outside, on Windows.

Stateless CLI: every sub-command finds the running game by its window and its
saved PID, does one thing, and exits. No REPL to keep alive, no tmux.

Input goes in through SendInput. Keys are sent as *scancodes*, never as virtual
key codes: GLFW reads the scancode out of lParam and maps it through a table
hardcoded to the US physical layout, so a virtual-key press of 'W' on an AZERTY
machine would arrive as GLFW_KEY_Z. Text goes in as KEYEVENTF_UNICODE, which is
layout-independent by construction and reaches the NUI text fields.

Screenshots are a BitBlt of the screen region the window occupies, so the window
has to be visible and on top. The game's own F12 binding is not an alternative:
a synthetic F12 never reaches it, while F1, F3 and H sent the same way do.
"""
import argparse
import ctypes
import ctypes.wintypes as w
import json
import os
import struct
import subprocess
import sys
import time
import zlib
from pathlib import Path

# <repo>/.claude/skills/run-terasology/driver.py -> <repo>
ROOT = Path(__file__).resolve().parents[3]
SKILL_DIR = Path(__file__).resolve().parent
STATE = ROOT / "build" / "driver-state.json"
CLASSPATH_FILE = ROOT / "build" / "run-classpath.txt"
INIT_SCRIPT = SKILL_DIR / "dump-classpath.init.gradle"
WINDOW_TITLE = "Terasology Alpha"
MAIN_CLASS = "org.terasology.engine.Terasology"

JDK17 = os.environ.get("TERA_JAVA_HOME",
                       r"C:\Program Files\Microsoft\jdk-17.0.20.101-hotspot")

user32 = ctypes.WinDLL("user32", use_last_error=True)
gdi32 = ctypes.WinDLL("gdi32", use_last_error=True)
kernel32 = ctypes.WinDLL("kernel32", use_last_error=True)

# ---------------------------------------------------------------- SendInput

INPUT_MOUSE, INPUT_KEYBOARD = 0, 1
KEYEVENTF_EXTENDEDKEY, KEYEVENTF_KEYUP = 0x0001, 0x0002
KEYEVENTF_UNICODE, KEYEVENTF_SCANCODE = 0x0004, 0x0008
MOUSEEVENTF_MOVE = 0x0001
MOUSEEVENTF_LEFTDOWN, MOUSEEVENTF_LEFTUP = 0x0002, 0x0004
MOUSEEVENTF_RIGHTDOWN, MOUSEEVENTF_RIGHTUP = 0x0008, 0x0010
MOUSEEVENTF_MIDDLEDOWN, MOUSEEVENTF_MIDDLEUP = 0x0020, 0x0040
MOUSEEVENTF_WHEEL = 0x0800

ULONG_PTR = ctypes.c_ulonglong if ctypes.sizeof(ctypes.c_void_p) == 8 else ctypes.c_ulong


class MOUSEINPUT(ctypes.Structure):
    _fields_ = [("dx", w.LONG), ("dy", w.LONG), ("mouseData", w.DWORD),
                ("dwFlags", w.DWORD), ("time", w.DWORD), ("dwExtraInfo", ULONG_PTR)]


class KEYBDINPUT(ctypes.Structure):
    _fields_ = [("wVk", w.WORD), ("wScan", w.WORD), ("dwFlags", w.DWORD),
                ("time", w.DWORD), ("dwExtraInfo", ULONG_PTR)]


class _INPUTUNION(ctypes.Union):
    _fields_ = [("mi", MOUSEINPUT), ("ki", KEYBDINPUT), ("pad", ctypes.c_byte * 32)]


class INPUT(ctypes.Structure):
    _anonymous_ = ("u",)
    _fields_ = [("type", w.DWORD), ("u", _INPUTUNION)]


def send(*inputs):
    arr = (INPUT * len(inputs))(*inputs)
    n = user32.SendInput(len(inputs), ctypes.byref(arr), ctypes.sizeof(INPUT))
    if n != len(inputs):
        raise OSError("SendInput sent %d/%d: %d" % (n, len(inputs), ctypes.get_last_error()))


def key_input(scan, up=False, extended=False):
    flags = KEYEVENTF_SCANCODE | (KEYEVENTF_KEYUP if up else 0)
    if extended:
        flags |= KEYEVENTF_EXTENDEDKEY
    return INPUT(type=INPUT_KEYBOARD,
                 ki=KEYBDINPUT(wVk=0, wScan=scan, dwFlags=flags, time=0, dwExtraInfo=0))


def char_input(ch, up=False):
    flags = KEYEVENTF_UNICODE | (KEYEVENTF_KEYUP if up else 0)
    return INPUT(type=INPUT_KEYBOARD,
                 ki=KEYBDINPUT(wVk=0, wScan=ord(ch), dwFlags=flags, time=0, dwExtraInfo=0))


def mouse_input(flags, dx=0, dy=0, data=0):
    return INPUT(type=INPUT_MOUSE,
                 mi=MOUSEINPUT(dx=dx, dy=dy, mouseData=data, dwFlags=flags,
                               time=0, dwExtraInfo=0))


# Set-1 scancodes for the US physical layout. GLFW's win32 backend maps these to
# GLFW_KEY_* with a hardcoded table, which is what makes them layout-proof.
SCAN = {
    "escape": 0x01, "esc": 0x01,
    "1": 0x02, "2": 0x03, "3": 0x04, "4": 0x05, "5": 0x06,
    "6": 0x07, "7": 0x08, "8": 0x09, "9": 0x0A, "0": 0x0B,
    "minus": 0x0C, "equals": 0x0D, "backspace": 0x0E, "tab": 0x0F,
    "q": 0x10, "w": 0x11, "e": 0x12, "r": 0x13, "t": 0x14, "y": 0x15,
    "u": 0x16, "i": 0x17, "o": 0x18, "p": 0x19,
    "lbracket": 0x1A, "rbracket": 0x1B, "enter": 0x1C, "return": 0x1C,
    "lctrl": 0x1D, "ctrl": 0x1D,
    "a": 0x1E, "s": 0x1F, "d": 0x20, "f": 0x21, "g": 0x22, "h": 0x23,
    "j": 0x24, "k": 0x25, "l": 0x26,
    "semicolon": 0x27, "apostrophe": 0x28, "grave": 0x29,
    "lshift": 0x2A, "shift": 0x2A, "backslash": 0x2B,
    "z": 0x2C, "x": 0x2D, "c": 0x2E, "v": 0x2F, "b": 0x30, "n": 0x31, "m": 0x32,
    "comma": 0x33, "period": 0x34, "slash": 0x35, "rshift": 0x36,
    "lalt": 0x38, "alt": 0x38, "space": 0x39, "capslock": 0x3A,
    "f1": 0x3B, "f2": 0x3C, "f3": 0x3D, "f4": 0x3E, "f5": 0x3F, "f6": 0x40,
    "f7": 0x41, "f8": 0x42, "f9": 0x43, "f10": 0x44, "f11": 0x57, "f12": 0x58,
}
EXTENDED = {
    "up": 0x48, "down": 0x50, "left": 0x4B, "right": 0x4D,
    "home": 0x47, "end": 0x4F, "pageup": 0x49, "pagedown": 0x51,
    "insert": 0x52, "delete": 0x53, "rctrl": 0x1D, "ralt": 0x38,
}


def resolve_key(name):
    n = name.lower()
    if n in EXTENDED:
        return EXTENDED[n], True
    if n in SCAN:
        return SCAN[n], False
    known = ", ".join(sorted(set(SCAN) | set(EXTENDED)))
    raise SystemExit("unknown key %r. Known: %s" % (name, known))


# ---------------------------------------------------------------- window

user32.SetProcessDPIAware()

WNDENUMPROC = ctypes.WINFUNCTYPE(w.BOOL, w.HWND, w.LPARAM)


def find_window(pid=None):
    """The game's top-level window, matched on title and (when known) on PID."""
    found = []

    def cb(hwnd, _):
        if not user32.IsWindowVisible(hwnd):
            return True
        length = user32.GetWindowTextLengthW(hwnd)
        if length == 0:
            return True
        buf = ctypes.create_unicode_buffer(length + 1)
        user32.GetWindowTextW(hwnd, buf, length + 1)
        if WINDOW_TITLE not in buf.value:
            return True
        wpid = w.DWORD()
        user32.GetWindowThreadProcessId(hwnd, ctypes.byref(wpid))
        if pid is None or wpid.value == pid:
            found.append(hwnd)
        return True

    user32.EnumWindows(WNDENUMPROC(cb), 0)
    return found[0] if found else None


def window_pid(hwnd):
    pid = w.DWORD()
    user32.GetWindowThreadProcessId(hwnd, ctypes.byref(pid))
    return pid.value


def client_rect(hwnd):
    """(left, top, width, height) of the client area, in screen pixels."""
    r = w.RECT()
    user32.GetClientRect(hwnd, ctypes.byref(r))
    pt = w.POINT(0, 0)
    user32.ClientToScreen(hwnd, ctypes.byref(pt))
    return pt.x, pt.y, r.right, r.bottom


def focus(hwnd):
    """Foreground the window.

    SetForegroundWindow is refused when the caller does not own the current
    foreground, so borrow that thread's input queue for the duration.
    """
    if user32.GetForegroundWindow() == hwnd:
        return
    user32.ShowWindow(hwnd, 9)  # SW_RESTORE
    fg = user32.GetForegroundWindow()
    cur = kernel32.GetCurrentThreadId()
    other = user32.GetWindowThreadProcessId(fg, None)
    user32.AttachThreadInput(cur, other, True)
    user32.BringWindowToTop(hwnd)
    user32.SetForegroundWindow(hwnd)
    user32.AttachThreadInput(cur, other, False)
    time.sleep(0.15)


def require_window():
    st = load_state()
    hwnd = find_window(st.get("pid")) or find_window(None)
    if hwnd is None:
        raise SystemExit("no Terasology window found - launch it first "
                         "(python driver.py launch)")
    focus(hwnd)
    return hwnd


# ---------------------------------------------------------------- state

def load_state():
    try:
        return json.loads(STATE.read_text())
    except Exception:
        return {}


def save_state(**kw):
    st = load_state()
    st.update(kw)
    STATE.parent.mkdir(parents=True, exist_ok=True)
    STATE.write_text(json.dumps(st, indent=2))


def alive(pid):
    if not pid:
        return False
    out = subprocess.run(["tasklist", "/FI", "PID eq %d" % pid, "/NH"],
                         capture_output=True, text=True).stdout
    return str(pid) in out


# ---------------------------------------------------------------- png

def write_png(path, width, height, rgb_rows):
    raw = b"".join(b"\x00" + row for row in rgb_rows)

    def chunk(tag, data):
        c = tag + data
        return struct.pack(">I", len(data)) + c + struct.pack(">I", zlib.crc32(c) & 0xFFFFFFFF)

    png = (b"\x89PNG\r\n\x1a\n"
           + chunk(b"IHDR", struct.pack(">IIBBBBB", width, height, 8, 2, 0, 0, 0))
           + chunk(b"IDAT", zlib.compress(raw, 6))
           + chunk(b"IEND", b""))
    Path(path).write_bytes(png)


class BITMAPINFOHEADER(ctypes.Structure):
    _fields_ = [("biSize", w.DWORD), ("biWidth", w.LONG), ("biHeight", w.LONG),
                ("biPlanes", w.WORD), ("biBitCount", w.WORD), ("biCompression", w.DWORD),
                ("biSizeImage", w.DWORD), ("biXPelsPerMeter", w.LONG),
                ("biYPelsPerMeter", w.LONG), ("biClrUsed", w.DWORD),
                ("biClrImportant", w.DWORD)]


class BITMAPINFO(ctypes.Structure):
    _fields_ = [("bmiHeader", BITMAPINFOHEADER), ("bmiColors", w.DWORD * 3)]


def grab(hwnd, path, crop=None):
    x, y, cw, ch = client_rect(hwnd)
    if crop:
        cx, cy, cwid, chei = crop
        x, y = x + cx, y + cy
        cw, ch = min(cwid, cw - cx), min(chei, ch - cy)
    screen_dc = user32.GetDC(0)
    mem_dc = gdi32.CreateCompatibleDC(screen_dc)
    bmp = gdi32.CreateCompatibleBitmap(screen_dc, cw, ch)
    gdi32.SelectObject(mem_dc, bmp)
    # SRCCOPY | CAPTUREBLT - the second flag is what makes composited surfaces
    # come out instead of black.
    if not gdi32.BitBlt(mem_dc, 0, 0, cw, ch, screen_dc, x, y, 0x00CC0020 | 0x40000000):
        raise OSError("BitBlt failed")

    bi = BITMAPINFO()
    bi.bmiHeader.biSize = ctypes.sizeof(BITMAPINFOHEADER)
    bi.bmiHeader.biWidth = cw
    bi.bmiHeader.biHeight = -ch  # negative height: top-down rows
    bi.bmiHeader.biPlanes = 1
    bi.bmiHeader.biBitCount = 32
    bi.bmiHeader.biCompression = 0
    buf = ctypes.create_string_buffer(cw * ch * 4)
    gdi32.GetDIBits(mem_dc, bmp, 0, ch, buf, ctypes.byref(bi), 0)

    raw = buf.raw
    rows = []
    stride = cw * 4
    for r in range(ch):
        line = raw[r * stride:(r + 1) * stride]
        rows.append(bytes(b for i in range(0, stride, 4)
                          for b in (line[i + 2], line[i + 1], line[i])))
    write_png(path, cw, ch, rows)

    gdi32.DeleteObject(bmp)
    gdi32.DeleteDC(mem_dc)
    user32.ReleaseDC(0, screen_dc)
    return cw, ch


# ---------------------------------------------------------------- commands

def gradlew(args, cwd=ROOT):
    env = dict(os.environ, JAVA_HOME=JDK17)
    cmd = [str(ROOT / "gradlew.bat"), "--console=plain"] + list(args)
    print("+ " + " ".join(cmd))
    return subprocess.run(cmd, cwd=str(cwd), env=env).returncode


def cmd_build(a):
    tasks = ["-I", str(INIT_SCRIPT), ":facades:PC:dumpRunSpec", ":extractNatives"]
    if getattr(a, "clean", False):
        tasks.insert(0, "clean")
    rc = gradlew(tasks)
    if rc == 0 and CLASSPATH_FILE.exists():
        n = len(CLASSPATH_FILE.read_text().split(os.pathsep))
        print("build ok - runtime classpath: %d entries -> %s" % (n, CLASSPATH_FILE))
    return rc


SYSTEM_CONFIG = (ROOT / "configs" / "engine"
                 / "org.terasology.engine.config.SystemConfig.cfg")


def set_debug_overlay(on):
    """Force the F3 debug overlay on or off, before the game starts.

    F3 is a toggle over a value that survives the run (AutoConfig writes it at
    shutdown, storing only non-defaults - an empty {} means off). Pressing F3
    blind therefore turns the overlay *off* half the time. Writing the file is
    the only way to know which state you get.
    """
    SYSTEM_CONFIG.parent.mkdir(parents=True, exist_ok=True)
    try:
        cfg = json.loads(SYSTEM_CONFIG.read_text() or "{}")
    except Exception:
        cfg = {}
    if on:
        cfg["debugEnabled"] = True
    else:
        cfg.pop("debugEnabled", None)
    SYSTEM_CONFIG.write_text(json.dumps(cfg))
    return cfg


def cmd_debug(a):
    cfg = set_debug_overlay(a.state == "on")
    print("%s -> %s" % (SYSTEM_CONFIG.relative_to(ROOT), cfg))
    print("takes effect at the next launch")
    return 0


def repair_save_games():
    """Undo a stuck `--no-save-games`.

    That flag is not a per-run switch: SystemConfig persists it as
    writeSaveGamesEnabled=false, and from then on *every* launch dies in
    "Registering World Systems..." because StorageManager is never bound and
    LocalChunkProvider cannot be injected. Nothing in the UI puts it back.
    """
    try:
        cfg = json.loads(SYSTEM_CONFIG.read_text() or "{}")
    except Exception:
        return
    if cfg.get("writeSaveGamesEnabled") is False:
        cfg.pop("writeSaveGamesEnabled")
        SYSTEM_CONFIG.write_text(json.dumps(cfg))
        print("repaired: writeSaveGamesEnabled was false, no world could load")


def cmd_launch(a):
    repair_save_games()
    if getattr(a, "debug", False):
        set_debug_overlay(True)
    st = load_state()
    if alive(st.get("pid")) and find_window(st["pid"]):
        print("already running (pid %s)" % st["pid"])
        return 0
    if not CLASSPATH_FILE.exists():
        print("no classpath yet, building first")
        if cmd_build(argparse.Namespace(clean=False)) != 0:
            return 1
    cp = CLASSPATH_FILE.read_text().strip()
    argfile = ROOT / "build" / "run-args.txt"
    # An @argfile keeps the 124-entry classpath off the command line, which
    # would otherwise overrun the Windows 32k limit. Backslashes must be doubled:
    # inside an argfile the JVM treats them as escapes.
    argfile.write_text('-cp "' + cp.replace("\\", "\\\\") + '"\n')
    java = str(Path(JDK17) / "bin" / "java.exe")
    args = [java, "-Xmx768M", "-XX:MaxDirectMemorySize=512M", "@" + str(argfile),
            MAIN_CLASS, "--homedir=.", "--no-splash", "--no-crash-report"]
    if a.load_last_game:
        args.append("--load-last-game")
    args.extend(a.extra)
    out = (ROOT / "build" / "game-stdout.log").open("w")
    err = (ROOT / "build" / "game-stderr.log").open("w")
    p = subprocess.Popen(args, cwd=str(ROOT), stdout=out, stderr=err)
    save_state(pid=p.pid, started=time.time())
    deadline = time.time() + a.timeout
    while time.time() < deadline:
        hwnd = find_window(p.pid)
        if hwnd:
            time.sleep(a.settle)
            x, y, cw, ch = client_rect(hwnd)
            print("pid %d  hwnd %d  client %dx%d at (%d,%d)" % (p.pid, hwnd, cw, ch, x, y))
            print("logs: build/game-stdout.log  build/game-stderr.log")
            return 0
        if p.poll() is not None:
            print("game exited during startup, stderr tail:")
            print((ROOT / "build" / "game-stderr.log").read_text(errors="replace")[-2000:])
            return 1
        time.sleep(0.5)
    print("no window after %ss" % a.timeout)
    return 1


def cmd_status(a):
    st = load_state()
    hwnd = find_window(st.get("pid")) or find_window(None)
    # A game started by hand (gradlew game, an IDE) has no state file - take the
    # pid off the window instead, so status and quit still address it.
    pid = st.get("pid") or (window_pid(hwnd) if hwnd else None)
    print("pid          %s (%s)" % (pid, "alive" if alive(pid) else "gone"))
    print("window       %s" % hwnd)
    if hwnd:
        x, y, cw, ch = client_rect(hwnd)
        print("client area  %dx%d at (%d,%d)" % (cw, ch, x, y))
        print("foreground   %s" % (user32.GetForegroundWindow() == hwnd))
    return 0 if hwnd else 1


def cmd_key(a):
    require_window()
    for name in a.keys:
        scan, ext = resolve_key(name)
        send(key_input(scan, extended=ext))
        time.sleep(a.press)
        send(key_input(scan, up=True, extended=ext))
        time.sleep(a.gap)
    print("sent: " + " ".join(a.keys))
    return 0


def cmd_hold(a):
    require_window()
    keys = [resolve_key(k) for k in a.key]
    for scan, ext in keys:
        send(key_input(scan, extended=ext))
    time.sleep(a.ms / 1000.0)
    for scan, ext in reversed(keys):
        send(key_input(scan, up=True, extended=ext))
    print("held %s for %d ms" % ("+".join(a.key), a.ms))
    return 0


def cmd_type(a):
    require_window()
    for ch in a.text:
        send(char_input(ch))
        send(char_input(ch, up=True))
        time.sleep(a.delay)
    print("typed %d chars" % len(a.text))
    return 0


def cmd_move(a):
    """Relative mouse motion - this is what turns the camera in game."""
    require_window()
    steps = max(1, a.steps)
    dx, dy = a.dx / steps, a.dy / steps
    ax = ay = 0.0
    for _ in range(steps):
        ax += dx
        ay += dy
        ix, iy = int(round(ax)), int(round(ay))
        if ix or iy:
            send(mouse_input(MOUSEEVENTF_MOVE, dx=ix, dy=iy))
            ax -= ix
            ay -= iy
        time.sleep(a.interval)
    print("moved %d,%d in %d steps" % (a.dx, a.dy, steps))
    return 0


def cmd_pos(a):
    """Absolute cursor placement, in client pixels - this is what hits menus."""
    hwnd = require_window()
    x, y, cw, ch = client_rect(hwnd)
    user32.SetCursorPos(x + a.x, y + a.y)
    time.sleep(0.05)
    print("cursor at client (%d,%d) = screen (%d,%d)" % (a.x, a.y, x + a.x, y + a.y))
    return 0


def cmd_click(a):
    hwnd = require_window()
    if a.x is not None:
        x, y, cw, ch = client_rect(hwnd)
        user32.SetCursorPos(x + a.x, y + a.y)
        time.sleep(0.08)
    down, up = {"left": (MOUSEEVENTF_LEFTDOWN, MOUSEEVENTF_LEFTUP),
                "right": (MOUSEEVENTF_RIGHTDOWN, MOUSEEVENTF_RIGHTUP),
                "middle": (MOUSEEVENTF_MIDDLEDOWN, MOUSEEVENTF_MIDDLEUP)}[a.button]
    for _ in range(a.count):
        send(mouse_input(down))
        time.sleep(a.press)
        send(mouse_input(up))
        time.sleep(0.06)
    where = " at (%d,%d)" % (a.x, a.y) if a.x is not None else ""
    print("%s click x%d%s" % (a.button, a.count, where))
    return 0


def cmd_scroll(a):
    require_window()
    send(mouse_input(MOUSEEVENTF_WHEEL, data=a.notches * 120))
    print("scrolled %d" % a.notches)
    return 0


def cmd_shot(a):
    hwnd = require_window()
    time.sleep(a.delay)
    path = Path(a.path)
    if not path.is_absolute():
        path = ROOT / "build" / "shots" / path
    path.parent.mkdir(parents=True, exist_ok=True)
    crop = None
    if getattr(a, "hud", False):
        crop = (0, 0, 1000, 80)  # the F3 overlay's four lines
    elif getattr(a, "crop", None):
        crop = tuple(int(v) for v in a.crop.split(","))
    cw, ch = grab(hwnd, path, crop)
    print("%s  %dx%d" % (path, cw, ch))
    return 0


def cmd_console(a):
    """Open the in-game console, run one command, close it again."""
    require_window()
    scan, ext = resolve_key(a.key)
    send(key_input(scan, extended=ext))
    time.sleep(0.05)
    send(key_input(scan, up=True, extended=ext))
    time.sleep(a.open_wait)
    for ch in " ".join(a.command):
        send(char_input(ch))
        send(char_input(ch, up=True))
        time.sleep(0.012)
    time.sleep(0.15)
    send(key_input(SCAN["enter"]))
    time.sleep(0.05)
    send(key_input(SCAN["enter"], up=True))
    time.sleep(a.run_wait)
    if not a.keep_open:
        send(key_input(scan, extended=ext))
        time.sleep(0.05)
        send(key_input(scan, up=True, extended=ext))
    print("console: " + " ".join(a.command))
    return 0


def newest_log():
    logs = ROOT / "logs"
    dirs = [p for p in logs.iterdir() if p.is_dir() and p.name[0].isdigit()] \
        if logs.exists() else []
    if not dirs:
        return None
    run = max(dirs, key=lambda p: p.stat().st_mtime)
    files = sorted(run.glob("*.log"), key=lambda p: p.stat().st_mtime)
    return files[-1] if files else None


def console_output(command, keyname="f1", wait=1.0):
    """Run a console command and return the lines it wrote to the log.

    ConsoleImpl mirrors every console message into logs/<run>/Terasology-<world>.log,
    which is the only text channel out of a running game - everything else needs
    a screenshot to read.
    """
    log = newest_log()
    offset = log.stat().st_size if log else 0
    cmd_console(argparse.Namespace(command=[command], key=keyname,
                                   open_wait=0.6, run_wait=wait, keep_open=False))
    time.sleep(0.4)
    log = newest_log()
    if log is None:
        return []
    with log.open("r", errors="replace") as fh:
        fh.seek(offset)
        return [ln.rstrip("\n") for ln in fh if ln.strip()]


def cmd_where(a):
    """Ask the game where the player is, and read the answer out of the log."""
    lines = [ln for ln in console_output("showPosition") if "Position" in ln]
    if not lines:
        print("no answer - is a world actually loaded?")
        return 1
    print(lines[-1].split("[CONSOLE] ")[-1])
    return 0


def cmd_log(a):
    logs = ROOT / "logs"
    if not logs.exists():
        print("no logs/ directory yet")
        return 1
    dirs = [p for p in logs.iterdir() if p.is_dir() and p.name[0].isdigit()]
    if not dirs:
        print("no run directory under logs/")
        return 1
    latest = max(dirs, key=lambda p: p.stat().st_mtime)
    # Each run splits its log per game state: Terasology-init.log, -menu.log and
    # Terasology-<world name>.log. The newest file is the one being written now.
    files = sorted(latest.glob("*.log"), key=lambda p: p.stat().st_mtime)
    if a.file:
        files = [latest / a.file]
    elif not a.grep:
        # No pattern: tail the file being written right now.
        files = files[-1:]
    if not files or not files[0].exists():
        print("%s holds: %s" % (latest.name, ", ".join(p.name for p in latest.iterdir())))
        return 1
    hits = []
    for f in files:
        for line in f.read_text(errors="replace").splitlines():
            if a.grep and a.grep.lower() not in line.lower():
                continue
            hits.append(line if len(files) == 1 else "%s | %s" % (f.name, line))
    print("--- %s" % (files[0] if len(files) == 1 else latest))
    print("\n".join(hits[-a.lines:]))
    return 0


def cmd_quit(a):
    st = load_state()
    hwnd = find_window(st.get("pid")) or find_window(None)
    pid = st.get("pid") or (window_pid(hwnd) if hwnd else None)
    if hwnd and not a.force:
        user32.PostMessageW(hwnd, 0x0010, 0, 0)  # WM_CLOSE
        deadline = time.time() + a.timeout
        while time.time() < deadline:
            if not alive(pid) and find_window(None) is None:
                print("closed cleanly")
                save_state(pid=None)
                return 0
            time.sleep(0.5)
        print("still up after %ss, killing" % a.timeout)
    if alive(pid):
        subprocess.run(["taskkill", "/F", "/T", "/PID", str(pid)], capture_output=True)
        print("killed pid %s" % pid)
    else:
        print("nothing running")
    save_state(pid=None)
    return 0


def cmd_smoke(a):
    """End to end: start the game on the last save, prove input reaches it, quit.

    The two position readouts in the debug overlay are the evidence that the
    keystrokes landed - a screenshot alone only proves the window is painting.
    """
    ns = argparse.Namespace(load_last_game=True, timeout=180, settle=5.0,
                            extra=[], debug=True)
    if cmd_launch(ns) != 0:
        return 1
    print("waiting for the world to finish loading")
    time.sleep(30)
    require_window()
    cmd_shot(argparse.Namespace(path="smoke-1-loaded.png", delay=0.4,
                                hud=False, crop=None))

    def position():
        lines = [ln for ln in console_output("showPosition") if "Your Position" in ln]
        return lines[-1].split("Your Position: ")[-1] if lines else None

    # Ghosting: no collision, no gravity, no fall damage. Without it the check
    # is worthless - a save that left the player in a hole or against a wall
    # does not move on W, and the smoke would report a failure that is not one.
    console_output("ghost")
    idle_a = position()
    time.sleep(1.5)
    idle_b = position()
    cmd_move(argparse.Namespace(dx=300, dy=0, steps=20, interval=0.016))
    cmd_hold(argparse.Namespace(key=["w"], ms=2500))
    time.sleep(0.5)
    after = position()
    cmd_shot(argparse.Namespace(path="smoke-2-moved.png", delay=0.4,
                                hud=False, crop=None))
    console_output("ghost")  # toggles back to walking - the save keeps this
    print("idle       : %s" % idle_a)
    print("idle again : %s   (must be identical - nothing moves on its own)" % idle_b)
    print("after W    : %s" % after)
    still = idle_a is not None and idle_a == idle_b
    moved = after is not None and after != idle_b
    print("SMOKE %s - keys and mouse reached the game"
          % ("PASS" if (still and moved) else "FAIL"))
    rc = 0 if (still and moved) else 1
    if not a.keep:
        cmd_quit(argparse.Namespace(force=False, timeout=30))
    return rc


def main():
    p = argparse.ArgumentParser(prog="driver.py", description=__doc__,
                                formatter_class=argparse.RawDescriptionHelpFormatter)
    sub = p.add_subparsers(dest="cmd", required=True)

    b = sub.add_parser("build", help="compile engine, modules and natives")
    b.add_argument("--clean", action="store_true")
    b.set_defaults(func=cmd_build)

    lc = sub.add_parser("launch", help="start the game and wait for its window")
    lc.add_argument("--load-last-game", action="store_true", help="skip the menus")
    lc.add_argument("--debug", action="store_true",
                    help="force the F3 overlay on (position readout) before starting")
    lc.add_argument("--timeout", type=float, default=120)
    lc.add_argument("--settle", type=float, default=8.0,
                    help="seconds to wait after the window appears")
    lc.add_argument("extra", nargs="*", help="extra engine args, after --")
    lc.set_defaults(func=cmd_launch)

    sub.add_parser("status", help="pid, window, client rect").set_defaults(func=cmd_status)

    k = sub.add_parser("key", help="tap keys, named by US physical position")
    k.add_argument("keys", nargs="+")
    k.add_argument("--press", type=float, default=0.05)
    k.add_argument("--gap", type=float, default=0.08)
    k.set_defaults(func=cmd_key)

    h = sub.add_parser("hold", help="hold keys down for a duration (walking)")
    h.add_argument("key", nargs="+")
    h.add_argument("--ms", type=int, default=1000)
    h.set_defaults(func=cmd_hold)

    t = sub.add_parser("type", help="type text as unicode (chat, console, name fields)")
    t.add_argument("text")
    t.add_argument("--delay", type=float, default=0.015)
    t.set_defaults(func=cmd_type)

    m = sub.add_parser("move", help="relative mouse motion - turns the camera")
    m.add_argument("dx", type=int)
    m.add_argument("dy", type=int)
    m.add_argument("--steps", type=int, default=10)
    m.add_argument("--interval", type=float, default=0.016)
    m.set_defaults(func=cmd_move)

    ps = sub.add_parser("pos", help="place the cursor at client pixel x,y")
    ps.add_argument("x", type=int)
    ps.add_argument("y", type=int)
    ps.set_defaults(func=cmd_pos)

    c = sub.add_parser("click", help="click, optionally after moving to x,y")
    c.add_argument("x", type=int, nargs="?")
    c.add_argument("y", type=int, nargs="?")
    c.add_argument("--button", choices=["left", "right", "middle"], default="left")
    c.add_argument("--count", type=int, default=1)
    c.add_argument("--press", type=float, default=0.05)
    c.set_defaults(func=cmd_click)

    s = sub.add_parser("scroll", help="mouse wheel notches (toolbar slots)")
    s.add_argument("notches", type=int)
    s.set_defaults(func=cmd_scroll)

    sh = sub.add_parser("shot", help="PNG of the client area")
    sh.add_argument("path", nargs="?", default="shot.png")
    sh.add_argument("--delay", type=float, default=0.4)
    sh.add_argument("--hud", action="store_true",
                    help="crop to the F3 overlay - position, target, FPS")
    sh.add_argument("--crop", default=None, metavar="X,Y,W,H")
    sh.set_defaults(func=cmd_shot)

    co = sub.add_parser("console", help="run one in-game console command")
    co.add_argument("command", nargs="+")
    co.add_argument("--key", default="f1", help="console binding (f1 or grave)")
    co.add_argument("--open-wait", type=float, default=0.6)
    co.add_argument("--run-wait", type=float, default=0.8)
    co.add_argument("--keep-open", action="store_true")
    co.set_defaults(func=cmd_console)

    sub.add_parser("where", help="player position, via console showPosition"
                   ).set_defaults(func=cmd_where)

    lg = sub.add_parser("log", help="tail the newest run's log")
    lg.add_argument("--file", default=None, help="pick one file instead of the newest")
    lg.add_argument("--lines", type=int, default=40)
    lg.add_argument("--grep", default=None, help="keep matching lines only")
    lg.set_defaults(func=cmd_log)

    dbg = sub.add_parser("debug", help="force the F3 overlay state for the next launch")
    dbg.add_argument("state", choices=["on", "off"])
    dbg.set_defaults(func=cmd_debug)

    sm = sub.add_parser("smoke", help="launch, load, look, walk, shoot, quit")
    sm.add_argument("--keep", action="store_true", help="leave the game running")
    sm.set_defaults(func=cmd_smoke)

    q = sub.add_parser("quit", help="close the window, then kill if it hangs")
    q.add_argument("--force", action="store_true")
    q.add_argument("--timeout", type=float, default=20)
    q.set_defaults(func=cmd_quit)

    a = p.parse_args()
    sys.exit(a.func(a))


if __name__ == "__main__":
    main()
