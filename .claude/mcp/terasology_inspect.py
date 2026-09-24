#!/usr/bin/env python3
"""MCP server exposing the running game's inspection port as tools.

The game opens an HTTP port on the loopback when launched with --inspect-port
(see subsystems/Inspect/). This is a thin stdio bridge to it: one tool per
route, no state of its own.

Standard library only, on purpose. driver.py already holds that property, and
giving it up would mean a pip install on a machine where nothing guarantees one.
So this speaks JSON-RPC 2.0 over stdin/stdout directly rather than through the
`mcp` package.

The game must be running with --inspect-port for any tool to answer; without it
every call returns a plain "not running" line rather than an error, because a
dead game is a normal state, not a fault.
"""

import json
import os
import sys
import urllib.error
import urllib.parse
import urllib.request

PORT = os.environ.get("TERA_INSPECT_PORT", "17888")
BASE = "http://127.0.0.1:%s" % PORT
PROTOCOL = "2024-11-05"

INT = {"type": "integer"}
STR = {"type": "string"}


def tool(name, description, properties=None, required=None):
    return {
        "name": name,
        "description": description,
        "inputSchema": {
            "type": "object",
            "properties": properties or {},
            "required": required or [],
        },
    }


# The descriptions carry the real value: they are what steers a reader to the
# right sampling shape without having to read the README first.
TOOLS = [
    tool("tera_health",
         "Is the game up, what state is it in, and is its main thread ticking. "
         "Answered off the game thread, so it still replies when the game is "
         "wedged. Call this first when anything else times out."),
    tool("tera_view",
         "Player position, view direction, yaw/pitch, and the block under the "
         "crosshair. The cheapest question; ~20 ms."),
    tool("tera_stats",
         "FPS, heap, active entity count, chunk counts, world time, and "
         "performance means. Also reports paused=true, which otherwise only "
         "shows up as two identical screenshots.",
         {"perf": {"type": "string",
                   "description": "'on' turns the performance monitor on; it is off by "
                                  "default and returns nothing until it is."}}),
    tool("tera_slice",
         "A vertical cross-section of the world as an ASCII grid: the shape of "
         "terrain, caves, walls, and where water ends. THE tool for anything "
         "about block layout — prefer it over a screenshot. Liquid flow levels "
         "come back as a second aligned grid (0 = a permanent source; a fall "
         "resets the count to 1). '.' is air, '?' is NOT LOADED, '~' is liquid; "
         "'?' and '.' mean different things, never conflate them. Centres on "
         "the player by default.",
         {"axis": {"type": "string", "enum": ["x", "y", "z"],
                   "description": "Plane normal. z (default) and x give vertical "
                                  "sections; y gives a horizontal plane."},
          "w": {"type": "integer", "description": "Width in blocks, max 64."},
          "h": {"type": "integer", "description": "Height in blocks, max 48."},
          "x": INT, "y": INT, "z": INT,
          "at": {"type": "string", "enum": ["target"],
                 "description": "Centre on the aimed block instead of the player. Note "
                                "water is not targetable, so the ray passes through pools."},
          "flow": {"type": "string", "enum": ["0", "1"],
                   "description": "'0' suppresses the liquid-flow grid."}}),
    tool("tera_surface",
         "Top-down map around a point: the first surface of every column, plus "
         "a height grid in base 36 so slopes read directly. Use for terrain "
         "shape over an area, biome/material spread, and finding where the "
         "ground is. Much cheaper than it looks — it stops per column.",
         {"r": {"type": "integer", "description": "Radius in blocks, max 24."},
          "x": INT, "z": INT,
          "from": {"type": "integer",
                   "description": "Ceiling to scan down from. Defaults to min(loaded "
                                  "top, anchor y + 48)."},
          "depth": {"type": "integer", "description": "Levels probed below `from`, max 256."}}),
    tool("tera_cube",
         "The dense neighbourhood around a point, as layers in descending Y. "
         "Use when you need the exact blocks immediately around something — "
         "mining, placement, collisions. Uniform layers collapse to one line.",
         {"r": {"type": "integer", "description": "Radius, max 8. r=8 is refused on "
                                                  "response size; use 6 or less."},
          "x": INT, "y": INT, "z": INT,
          "at": {"type": "string", "enum": ["target"]}}),
    tool("tera_block",
         "Everything about one cell: URI, physical flags, light, and for a "
         "liquid its flow level and all six neighbours. The drill-down after a "
         "grid shows something odd. Same vocabulary as the liquidFlow command.",
         {"x": INT, "y": INT, "z": INT,
          "at": {"type": "string", "enum": ["target"]}}),
    tool("tera_entities",
         "One line per entity: id, prefab, position, and component NAMES only. "
         "Use it to find an entity, then tera_entity for its values.",
         {"r": {"type": "integer", "description": "Only entities within this many blocks "
                                                  "of the player."},
          "with": {"type": "string",
                   "description": "Only entities carrying this component, by short name "
                                  "(e.g. 'Health', 'Inventory' — no 'Component' suffix)."},
          "limit": {"type": "integer", "description": "Max lines, default 50, cap 200."}}),
    tool("tera_entity",
         "Every component of one entity with all field values as JSON. Shows "
         "fields equal to their prefab defaults, which the engine's own "
         "dumpEntities does not.",
         {"ref": {"type": "string",
                  "description": "A numeric entity id, or one of the aliases 'player', "
                                 "'client', 'target'."}},
         ["ref"]),
    tool("tera_commands",
         "List the game's registered console commands with usage and "
         "description. Filter — there are over a hundred and the unfiltered "
         "list is truncated.",
         {"q": {"type": "string", "description": "Substring filter on the usage line."}}),
    tool("tera_console",
         "Run one in-game console command and return its output. Replaces the "
         "old keystroke path: ~20 ms instead of ~5 s. Needs the game launched "
         "with --inspect-allow-console, and can change the world (teleport, "
         "giveBlock, replaceBlock all work).",
         {"command": {"type": "string",
                      "description": "The full command line, e.g. 'look 90 20' or "
                                     "'replaceBlock CoreAssets:Water'."}},
         ["command"]),
    tool("tera_record_start",
         "Arm the tape: sample the position of the player and of chosen entities "
         "every frame, and derive events from what changes. THE tool for anything "
         "that happens too fast to poll — knockback, a fall, a chase, a cooldown. "
         "Events come from differences, not listeners: an item use (past the "
         "cooldown gate), a numeric probe moving (health), a component appearing "
         "or vanishing, an entity gone. Arming replaces any previous tape.",
         {"with": {"type": "string",
                   "description": "Track every entity carrying this component, by short "
                                  "name (e.g. 'Creature'). Frozen at start — a beast that "
                                  "dies keeps its line, because the carcass IS the creature."},
          "ids": {"type": "string", "description": "Comma-separated entity ids, instead of `with`."},
          "r": {"type": "number", "description": "Radius around the player for `with`, default 32."},
          "hz": {"type": "number", "description": "Position samples per second, default 20, max 120. "
                                                  "Events are caught every tick regardless."},
          "flags": {"type": "string",
                    "description": "Comma-separated short component names to watch as on/off, "
                                   "e.g. 'Recul,Cadavre'. Each change is an event."},
          "probes": {"type": "string",
                     "description": "Comma-separated 'Component.field' numeric probes, default "
                                    "'Health.currentHealth'. Read by reflection, so any public "
                                    "numeric field of any module works."}}),
    tool("tera_record_mark",
         "Stamp an action into the tape as the driver performs it — a click, a key, "
         "a teleport. Nothing in the world state records a player input (a click "
         "that misses leaves no trace), so this is the only channel for it. Stamped "
         "on the next tick, so up to one frame late.",
         {"label": {"type": "string", "description": "Short label, e.g. 'clic-gauche'."}},
         ["label"]),
    tool("tera_record_stop",
         "Stop the tape and return its header (frames, events, duration)."),
    tool("tera_record_dump",
         "Read the tape back: header, every event, then position frames from `from` "
         "onwards until the page budget is spent. The footer gives `next=` when "
         "there is more. For a long tape prefer tera_record_save.",
         {"from": {"type": "integer", "description": "First frame index of the page, default 0."}}),
    tool("tera_record_save",
         "Write the whole tape to a file under the game home directory and return "
         "its path. The way to get a long recording out in one piece — the HTTP "
         "response cap would cut it mid-line.",
         {"name": {"type": "string", "description": "File name, sanitised; default 'bande'."}}),
    tool("tera_place",
         "Place blocks directly in the world, with no player involved: no aiming, "
         "no reach, no inventory consumed. For building test scenery — an arena, a "
         "flat floor under a beast, a marker at a measured spot. Needs the game "
         "launched with --inspect-allow-write. An unknown block name is refused "
         "rather than silently placed as air, and blocks outside loaded chunks are "
         "counted as refused.",
         {"uri": {"type": "string", "description": "Block name, e.g. 'CoreAssets:Stone'."},
          "x": INT, "y": INT, "z": INT,
          "x2": INT, "y2": INT, "z2": INT,
          "rel": {"type": "string", "enum": ["player"],
                  "description": "Count the coordinates from the block the player stands on."},
          "lines": {"type": "string",
                    "description": "Instead of a box: one placement per line, 'x y z [uri]'. "
                                   "Up to 4096 blocks per call."}}),
]

ROUTES = {
    "tera_health": "/health", "tera_view": "/view", "tera_stats": "/stats",
    "tera_slice": "/slice", "tera_surface": "/surface", "tera_cube": "/cube",
    "tera_block": "/block", "tera_entities": "/entities", "tera_commands": "/commands",
    "tera_record_start": "/record/start", "tera_record_stop": "/record/stop",
    "tera_record_dump": "/record/dump", "tera_record_save": "/record/save",
}


def fetch(path, data=None):
    url = BASE + path
    try:
        request = urllib.request.Request(url, data=data,
                                         method="POST" if data else "GET")
        with urllib.request.urlopen(request, timeout=15) as response:
            return response.read().decode("utf-8", "replace")
    except urllib.error.HTTPError as e:
        # The refusals are informative by design - 409 no world, 400 bad param,
        # 404 closed route - so hand the body back rather than an error string.
        return "HTTP %d\n%s" % (e.code, e.read().decode("utf-8", "replace"))
    except urllib.error.URLError as e:
        return ("Le jeu ne repond pas sur %s (%s).\n"
                "Lancer : python .claude/skills/run-terasology/driver.py launch "
                "--load-last-game -- --inspect-port=%s --inspect-allow-console --inspect-allow-write"
                % (BASE, e.reason, PORT))


def call(name, arguments):
    if name == "tera_entity":
        return fetch("/entity/" + urllib.parse.quote(str(arguments.get("ref", ""))))
    if name == "tera_console":
        return fetch("/console", data=str(arguments.get("command", "")).encode("utf-8"))
    if name == "tera_record_mark":
        return fetch("/record/mark", data=str(arguments.get("label", "")).encode("utf-8"))
    if name == "tera_place":
        # The block list travels in the body; everything else stays in the query, so a
        # single-block call needs no body at all.
        lines = arguments.pop("lines", None)
        query = {k: str(v) for k, v in arguments.items() if v is not None and v != ""}
        path = "/place" + ("?" + urllib.parse.urlencode(query) if query else "")
        return fetch(path, data=(lines or " ").encode("utf-8"))
    path = ROUTES.get(name)
    if path is None:
        return "Outil inconnu : %s" % name
    query = {k: str(v) for k, v in arguments.items() if v is not None and v != ""}
    if query:
        path += "?" + urllib.parse.urlencode(query)
    return fetch(path)


def handle(message):
    method = message.get("method")
    if method == "initialize":
        version = message.get("params", {}).get("protocolVersion", PROTOCOL)
        return {"protocolVersion": version,
                "capabilities": {"tools": {}},
                "serverInfo": {"name": "terasology-inspect", "version": "1.0.0"}}
    if method == "tools/list":
        return {"tools": TOOLS}
    if method == "tools/call":
        params = message.get("params", {})
        text = call(params.get("name", ""), params.get("arguments") or {})
        return {"content": [{"type": "text", "text": text}]}
    if method == "ping":
        return {}
    return None


def main():
    # Obligatoire sur Windows : sys.stdout prend l'encodage de la console, cp1252 ici, et le jeu
    # renvoie des noms de blocs francais. Sans cela « Acier ouvrage » perdait son accent entre le
    # serveur, qui envoie pourtant de l'UTF-8 correct, et le client. JSON-RPC est de l'UTF-8.
    for stream in (sys.stdin, sys.stdout):
        if hasattr(stream, "reconfigure"):
            stream.reconfigure(encoding="utf-8")

    for line in sys.stdin:
        line = line.strip()
        if not line:
            continue
        try:
            message = json.loads(line)
        except ValueError:
            continue
        # A notification carries no id and must never be answered - replying to
        # one is a protocol violation, not a harmless extra.
        if "id" not in message:
            continue
        try:
            result = handle(message)
            if result is None:
                reply = {"jsonrpc": "2.0", "id": message["id"],
                         "error": {"code": -32601, "message": "Method not found"}}
            else:
                reply = {"jsonrpc": "2.0", "id": message["id"], "result": result}
        except Exception as e:                      # noqa: BLE001 - never kill the server
            reply = {"jsonrpc": "2.0", "id": message["id"],
                     "error": {"code": -32603, "message": repr(e)}}
        sys.stdout.write(json.dumps(reply) + "\n")
        sys.stdout.flush()


if __name__ == "__main__":
    main()
