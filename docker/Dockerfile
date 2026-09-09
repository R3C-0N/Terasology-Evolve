# Copyright 2026 R3C-0N
# SPDX-License-Identifier: Apache-2.0

# Java 17 exactly: build.gradle.kts asserts it and refuses any other version.
FROM eclipse-temurin:17-jdk

# There is no GPU here, so Mesa's software rasteriser does the work. It reports OpenGL 4.5 core,
# well above the 3.3 the engine asks for, and it covers the float textures and the vertex-stage
# texelFetch that the cube projection depends on.
RUN apt-get update && apt-get install -y --no-install-recommends \
        xvfb x11vnc novnc websockify xdotool \
        libgl1-mesa-dri libglx-mesa0 libgl1 \
        libx11-6 libxcursor1 libxrandr2 libxinerama1 libxi6 libxxf86vm1 \
        fontconfig fonts-dejavu-core \
        python3-minimal curl git ca-certificates imagemagick \
    && rm -rf /var/lib/apt/lists/*

# noVNC ships no landing page, so the bare domain would 404 behind the proxy.
RUN printf '%s\n' \
    '<!doctype html><meta charset="utf-8">' \
    '<title>Terasology</title>' \
    '<script>location.replace("vnc.html?path=websockify&autoconnect=1&resize=scale&reconnect=1"+location.hash)</script>' \
    > /usr/share/novnc/index.html

# The two directories below are created here on purpose: Docker only copies ownership into a
# named volume from an existing directory in the image, so without them the volumes would be
# root-owned and Gradle could not write its cache.
# uid 1000, so that the bind-mounted work tree keeps its ownership. The base image already has a
# user there, so reuse it rather than fight over the id.
RUN if id -u 1000 > /dev/null 2>&1; then \
        usermod --login game --home /home/game --move-home --shell /bin/bash "$(id -nu 1000)"; \
    else \
        useradd --uid 1000 --create-home --shell /bin/bash game; \
    fi \
    && mkdir -p /home/game/.gradle /home/game/terasology /tmp/.X11-unix \
    && chown -R 1000:1000 /home/game \
    && chmod 1777 /tmp/.X11-unix
COPY docker/entrypoint.sh /usr/local/bin/entrypoint.sh
RUN chmod +x /usr/local/bin/entrypoint.sh

USER game
WORKDIR /work

ENV HOME=/home/game \
    GRADLE_USER_HOME=/home/game/.gradle \
    GAME_HOME=/home/game/terasology \
    DISPLAY=:99 \
    SCREEN=1600x900x24 \
    LIBGL_ALWAYS_SOFTWARE=1 \
    GALLIUM_DRIVER=llvmpipe \
    NOVNC_PORT=6080

EXPOSE 6080
HEALTHCHECK --interval=30s --timeout=5s --start-period=180s --retries=5 \
    CMD curl -fsS "http://localhost:${NOVNC_PORT}/vnc.html" > /dev/null

ENTRYPOINT ["/usr/local/bin/entrypoint.sh"]
