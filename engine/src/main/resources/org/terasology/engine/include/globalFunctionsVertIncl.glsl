// Copyright 2021 The Terasology Foundation
// SPDX-License-Identifier: Apache-2.0

float timeToTick(float time, float speed) {
    return time * 4000.0 * speed;
}

float smoothCurve(float x) {
  return x * x * (3.0 - 2.0 * x);
}

float triangleWave(float x) {
  return abs(fract(x + 0.5) * 2.0 - 1.0);
}

float smoothTriangleWave(float x) {
  return smoothCurve(triangleWave(x)) * 2.0 - 1.0;
}


// -- Curved worlds ------------------------------------------------------------------------------
//
// The projection table holds, per texel, the gnomonic pair (a, b) of the tangent plane and the
// tangential scale factor. The bilinear read below is written out by hand and must stay identical
// to the one on the CPU: that identity is the only reason a position computed there and a vertex
// placed here end up in the same spot.

#if __VERSION__ >= 130

const int SPHERE_FACES = 6;
const vec3 SPHERE_RIGHT[SPHERE_FACES] = vec3[SPHERE_FACES](
    vec3( 0.0,  1.0,  0.0), vec3(-1.0,  0.0,  0.0), vec3( 0.0, -1.0,  0.0),
    vec3( 1.0,  0.0,  0.0), vec3(-1.0,  0.0,  0.0), vec3(-1.0,  0.0,  0.0));
const vec3 SPHERE_UP[SPHERE_FACES] = vec3[SPHERE_FACES](
    vec3( 0.0,  0.0,  1.0), vec3( 0.0,  0.0,  1.0), vec3( 0.0,  0.0,  1.0),
    vec3( 0.0,  0.0,  1.0), vec3( 0.0, -1.0,  0.0), vec3( 0.0,  1.0,  0.0));
const vec3 SPHERE_NORMAL[SPHERE_FACES] = vec3[SPHERE_FACES](
    vec3( 1.0,  0.0,  0.0), vec3( 0.0,  1.0,  0.0), vec3(-1.0,  0.0,  0.0),
    vec3( 0.0, -1.0,  0.0), vec3( 0.0,  0.0,  1.0), vec3( 0.0,  0.0, -1.0));
// Slot of each face in the four by four net, as (column, row). Ten slots carry nothing.
const vec2 SPHERE_PATTERN[SPHERE_FACES] = vec2[SPHERE_FACES](
    vec2(0.0, 1.0), vec2(1.0, 1.0), vec2(2.0, 1.0), vec2(3.0, 1.0), vec2(1.0, 2.0), vec2(1.0, 0.0));

// Which face a world column belongs to, and where inside it. Returns -1 for a dead slot.
int sphereFaceOf(vec2 column, out vec2 local) {
    vec2 slot = floor(column / sphereFaceEdge);
    local = column - slot * sphereFaceEdge;
    for (int f = 0; f < SPHERE_FACES; f++) {
        if (slot == SPHERE_PATTERN[f]) {
            return f;
        }
    }
    return -1;
}

vec3 sphereTableAt(vec2 st) {
    float n = float(sphereTableSize - 1);
    float x = clamp((st.x + 1.0) * 0.5 * n, 0.0, n - 0.0001);
    float y = clamp((st.y + 1.0) * 0.5 * n, 0.0, n - 0.0001);
    ivec2 i = ivec2(int(x), int(y));
    vec2 f = vec2(x - float(i.x), y - float(i.y));
    vec3 a = texelFetch(sphereTable, i, 0).rgb;
    vec3 b = texelFetch(sphereTable, i + ivec2(1, 0), 0).rgb;
    vec3 c = texelFetch(sphereTable, i + ivec2(0, 1), 0).rgb;
    vec3 d = texelFetch(sphereTable, i + ivec2(1, 1), 0).rgb;
    return mix(mix(a, b, f.x), mix(c, d, f.x), f.y);
}

// The unit direction of a world column, in planet space, plus its scale factor in w.
vec4 sphereDirection(vec2 column) {
    vec2 local;
    int face = sphereFaceOf(column, local);
    if (face < 0) {
        return vec4(0.0, 0.0, 1.0, 1.0);
    }
    vec2 st = 2.0 * local / sphereFaceEdge - 1.0;
    vec3 ab = sphereTableAt(st);
    vec3 d = normalize(SPHERE_NORMAL[face] + SPHERE_RIGHT[face] * ab.x + SPHERE_UP[face] * ab.y);
    return vec4(d, ab.z);
}

// Where a world position ends up once the world is bent, relative to the focus.
//
// The height is multiplied by the scale factor, not added raw. A conformal map stretches both
// tangential directions by that factor while leaving the radial one alone, and a block would stop
// being cubic; scaling the height makes the deformation a local similarity instead.
vec3 sphereCurvedPos(vec3 worldPos) {
    vec4 here = sphereDirection(worldPos.xz);
    vec4 origin = sphereDirection(sphereFocus.xz);
    float r = sphereRadius + here.w * (worldPos.y - sphereReferenceHeight);
    float r0 = sphereRadius + origin.w * (sphereFocus.y - sphereReferenceHeight);
    return here.xyz * r - origin.xyz * r0;
}

// The frame that turns a flat normal into the curved one. Without it the distant ground is lit as
// though it were flat: the local frame turns by d / R, which is eleven degrees at a thousand blocks.
mat3 sphereFrame(vec3 worldPos) {
    float step = sphereFaceEdge / 256.0;
    vec3 up = sphereDirection(worldPos.xz).xyz;
    vec3 alongX = sphereDirection(worldPos.xz + vec2(step, 0.0)).xyz
                - sphereDirection(worldPos.xz - vec2(step, 0.0)).xyz;
    vec3 alongZ = sphereDirection(worldPos.xz + vec2(0.0, step)).xyz
                - sphereDirection(worldPos.xz - vec2(0.0, step)).xyz;
    vec3 east = normalize(alongX - up * dot(alongX, up));
    vec3 north = normalize(alongZ - up * dot(alongZ, up));
    // world X, Y, Z map onto east, up, north
    return mat3(east, up, north);
}

// The whole point of the exercise, in one call.
//
// Every node that draws world geometry builds its model-view as V * T(origin - reference), a pure
// translation, so mat3(mv) is the linear part of the view and the translation can be recovered
// exactly. That is what lets the deformation happen here without touching a single matrix, and it
// keeps the head bob, the reflected camera and the orthographic light camera all working.
vec4 sphereViewPos(vec3 localVert, vec3 originWorld, mat4 mv) {
    if (sphereEnabled == 0) {
        return mv * vec4(localVert, 1.0);
    }
    mat3 linear = mat3(mv);
    vec3 translation = mv[3].xyz - linear * (originWorld - sphereModelOrigin);
    return vec4(linear * sphereCurvedPos(localVert + originWorld) + translation, 1.0);
}

#else

// texelFetch needs GLSL 130. Every shader in the tree asks for 330, but a shader with no #version
// at all falls back to 120, and one that cannot read the table must still compile.
vec4 sphereViewPos(vec3 localVert, vec3 originWorld, mat4 mv) {
    return mv * vec4(localVert, 1.0);
}

mat3 sphereFrame(vec3 worldPos) {
    return mat3(1.0);
}

#endif
