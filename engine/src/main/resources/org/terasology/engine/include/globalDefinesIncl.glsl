/*
 * Copyright 2012 Benjamin Glatzel <benjamin.glatzel@me.com>
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

#define OCEAN_OCTAVES 16

#define LIGHT_SHAFT_SAMPLES 64
#define MOTION_BLUR_SAMPLES 32

#define DAYLIGHT_AMBIENT_COLOR 1.0, 0.9, 0.9
#define MOONLIGHT_AMBIENT_COLOR 0.5, 0.5, 1.0

#define NIGHT_BRIGHTNESS 0.125

// The colour a torch or a glowbell lights the world with: near white, barely warmed.
#define BLOCK_LIGHT_TINT vec3(1.0, 0.95, 0.94)
// The colour lava lights it with. A grey body at 1400 K - flowing basalt - sits at CIE (0.590, 0.392), which is
// linear sRGB (1.0, 0.126, 0.0), all but pure red; multiplying rock albedo by that leaves the texture no green and
// no blue to show, so this is lifted toward white, further in green than in blue so the hue stays amber. The lift
// is a choice, not physics, and it is the first knob to turn if the caves read wrong.
#define LAVA_LIGHT_TINT vec3(1.0, 0.35, 0.10)
// How fast a block of lava breathes, and how far down it goes. Well under the 0.4375 of the world wide flicker:
// that one is uniform, so it costs nothing at a block boundary, whereas this is the difference in brightness
// between two faces one block apart, and past about a fifth it stops reading as firelight and starts reading as a
// checkerboard.
#define LAVA_FLICKER_RATE 0.09
#define LAVA_FLICKER_DEPTH 0.18

#define WATER_AMB 1.0
#define WATER_DIFF 2.0

#define BLOCK_AMB 1.0
#define BLOCK_DIFF 2.0

#define EPSILON 0.000001
#define PI 3.14159265359
#define PI_TIMES_8 25.13274122872

#define A 0.15
#define B 0.50
#define C 0.10
#define D 0.20
#define E 0.02
#define F 0.30
#define W 1

