#version 330 core
// Copyright 2021 The Terasology Foundation
// SPDX-License-Identifier: Apache-2.0


in vec4 color_gs;
in vec2 uv;

uniform bool use_texture = false;
uniform sampler2D texture_sampler;

layout(location = 0) out vec4 outColor;

void main() {
    if (use_texture) {
        // Tint the sprite rather than replace it. Taking the texture alone threw the per-particle colour
        // away the moment an effect had a sprite, which made colorRangeGenerator mean nothing for every
        // textured effect - a smoke sprite asked to burn orange came out grey. A particle starts out
        // opaque white, so every effect that sets no colour is unchanged by the multiply.
        outColor = texture(texture_sampler, uv) * color_gs;
    } else {
        outColor = color_gs;
    }

    if (outColor.a < 0.01) {
        discard;
    }
}
