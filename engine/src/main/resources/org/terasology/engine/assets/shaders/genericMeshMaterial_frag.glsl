#version 330 core
// Copyright 2021 The Terasology Foundation
// SPDX-License-Identifier: Apache-2.0

uniform sampler2D diffuse;

// TODO: Add normal mapping support
//uniform sampler2D normalMap;

uniform float blockLight = 1.0;
uniform float sunlight = 1.0;

uniform vec3 colorOffset;
uniform bool textured;

// Alpha cutout threshold. A texel below it is discarded outright, which is how a mesh gets holes here: this is the
// opaque deferred pass, so there is no GL_BLEND to fade anything, and the alpha channel of the opaque G-buffer means
// something else entirely (see below). Defaults to 0.0, which discards nothing - every material that does not ask for
// cutout renders exactly as it did. Set it from the .mat, e.g. "alphaThreshold": 0.5.
uniform float alphaThreshold = 0.0;

in vec3 v_normal;
in vec2 v_uv0;
in vec4 v_color0;

layout(location = 0) out vec4 outColor;
layout(location = 1) out vec4 outNormal;
layout(location = 2) out vec4 outLight;

void main(){
    vec4 color;

    if (textured) {
        color = texture(diffuse, v_uv0);
        if (color.a < alphaThreshold) {
            discard;
        }
        color.rgb *= colorOffset.rgb;
    } else {
        color = vec4(colorOffset.rgb, 1.0);
    }

    // The alpha channel of the opaque G-buffer is NOT transparency: lightBufferPass_frag.glsl reads it as an ambient
    // occlusion factor and multiplies the colour by it whenever SSAO is off (the low and medium presets). Handing it a
    // texture's alpha would blacken the mesh instead of piercing it, so anything that survives the cutout above writes
    // a neutral 1.0.
    outColor.rgba = vec4(color.rgb, 1.0);

    outNormal.rgba = vec4(v_normal.x / 2.0 + 0.5, v_normal.y / 2.0 + 0.5, v_normal.z / 2.0 + 0.5, 0.0);
    outLight.rgba = vec4(blockLight, sunlight, 0.0, 0.0);
}
