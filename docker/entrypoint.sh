#!/usr/bin/env bash
# Copyright 2026 R3C-0N
# SPDX-License-Identifier: Apache-2.0
set -euo pipefail

# The proxy in front of this is public. Refuse to hand out a desktop without a password rather
# than fall back to a default one.
if [[ -z "${VNC_PASSWORD:-}" ]]; then
    echo "VNC_PASSWORD is not set. Refusing to start an unauthenticated remote desktop." >&2
    exit 1
fi

# Fail with the reason rather than with a missing file: a container that restarts forever on
# "./gradlew: No such file or directory" says nothing about why the tree is not there.
if [[ ! -x ./gradlew ]]; then
    echo "no work tree in $(pwd): gradlew is missing." >&2
    echo "The source is copied into the image at build time; a volume mounted over /work hides it." >&2
    exit 1
fi

WORLD_GENERATOR="${WORLD_GENERATOR:-CubeWorlds:cubeworld}"
GAME_ARGS="${GAME_ARGS:-}"
SCREEN="${SCREEN:-1600x900x24}"
NOVNC_PORT="${NOVNC_PORT:-6080}"
: "${GAME_HOME:=$HOME/terasology}"

geometry="${SCREEN%x*}"
width="${geometry%x*}"
height="${geometry#*x}"

mkdir -p "$GAME_HOME"

# The module repositories do not track their build.gradle: upstream generates it from templates/
# when a module is fetched. Without it Gradle silently does not see the module as a subproject.
if [[ -f templates/build.gradle ]]; then
    for module in modules/*/; do
        if [[ -f "${module}module.txt" && ! -f "${module}build.gradle" ]]; then
            cp templates/build.gradle "${module}build.gradle"
            echo "deposited build.gradle in ${module}"
        fi
    done
fi

# The source modules declare dependencies that are not source modules themselves — BiomesAPI, for
# one — and those arrive as jars. Gradle syncs them into the repository's cachedModules, but the
# game reads <homedir>/modules and never looks there, so without this copy it reports
# "Could not resolve dependencies for module: CoreWorlds" and offers no world generator at all.
echo "fetching the jar dependencies of the source modules"
./gradlew --console=plain --no-daemon :modules:fetchModuleDependencies > /dev/null 2>&1 || true
mkdir -p "$GAME_HOME/modules"
if compgen -G "/work/cachedModules/*.jar" > /dev/null; then
    cp -f /work/cachedModules/*.jar "$GAME_HOME/modules/"
    echo "placed $(ls -1 /work/cachedModules/*.jar | wc -l) jar module(s) in $GAME_HOME/modules"
else
    echo "warning: no jar module was fetched; the world generators will not resolve" >&2
fi

# Match the window to the screen and drop the passes a software rasteriser cannot afford. Only
# touched once the game itself has written a config, so nothing here invents a schema.
patch_config() {
    local config="$GAME_HOME/config.cfg"
    [[ -f "$config" ]] || return 0
    python3 - "$config" "$width" "$height" "$WORLD_GENERATOR" <<'PY'
import json, sys
path, width, height, generator = sys.argv[1], int(sys.argv[2]), int(sys.argv[3]), sys.argv[4]
config = json.load(open(path))
rendering = config.setdefault("rendering", {})
rendering.update({
    "windowPosX": 0, "windowPosY": 0,
    "windowWidth": width, "windowHeight": height,
    "vSync": False, "frameLimit": 15,
    "ssao": False, "bloom": False, "lightShafts": False, "motionBlur": False,
    "filmGrain": False, "eyeAdaptation": False, "volumetricFog": False,
    # Kept on purpose: the shadow pass is one of the things that has to be judged on screen.
    "dynamicShadows": True,
})
if generator:
    config.setdefault("worldGeneration", {})["defaultGenerator"] = generator
json.dump(config, open(path, "w"), indent=2)
print("patched", path)
PY
}
# The game writes its config on first startup, so on a fresh volume there is nothing to patch yet
# and the window would come up at its default size. A throwaway headless pass writes the file;
# it is allowed to fail, because writing the config happens well before anything else can.
if [[ ! -f "$GAME_HOME/config.cfg" ]]; then
    echo "no config yet, taking one headless pass to have the game write it"
    timeout 120 ./gradlew --console=plain --no-daemon --offline :facades:PC:game \
        --args="--headless --homedir=${GAME_HOME} --crash-report=false" > /dev/null 2>&1 || true
fi
patch_config

cleanup() {
    trap - TERM INT EXIT
    kill ${game_pid:-} ${websockify_pid:-} ${x11vnc_pid:-} ${xvfb_pid:-} 2>/dev/null || true
    wait 2>/dev/null || true
}
trap cleanup TERM INT EXIT

echo "starting Xvfb on ${DISPLAY} at ${SCREEN}"
Xvfb "$DISPLAY" -screen 0 "$SCREEN" -nolisten tcp &
xvfb_pid=$!
for _ in $(seq 1 40); do
    xdotool getdisplaygeometry > /dev/null 2>&1 && break
    sleep 0.25
done

x11vnc -storepasswd "$VNC_PASSWORD" "$HOME/.vncpasswd" > /dev/null 2>&1
echo "starting x11vnc on 5900, loopback only"
x11vnc -display "$DISPLAY" -rfbauth "$HOME/.vncpasswd" -localhost -rfbport 5900 \
       -forever -shared -noxdamage -repeat -quiet &
x11vnc_pid=$!

echo "serving noVNC on ${NOVNC_PORT}"
websockify --web=/usr/share/novnc "$NOVNC_PORT" localhost:5900 &
websockify_pid=$!

echo "starting the game, generator ${WORLD_GENERATOR}"
./gradlew --console=plain --no-daemon :facades:PC:game \
    --args="--homedir=${GAME_HOME} --crash-report=false --splash=false --sound=false ${GAME_ARGS}" &
game_pid=$!

# Whichever of the four falls over, take the whole container down: a black desktop behind a live
# port would look healthy and be useless.
wait -n
echo "one of the processes exited, shutting down" >&2
