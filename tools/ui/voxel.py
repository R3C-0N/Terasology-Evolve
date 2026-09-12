#!/usr/bin/env python3
# Copyright 2026 The Terasology Foundation
# SPDX-License-Identifier: Apache-2.0
"""Moteur de textures voxel HeroCraft — port litteral de `voxel.js`.

`voxel.js` vit dans le design system publie sur Claude Design, a l'interieur de
`_ds/herocraft-design-system-.../_ds_bundle.js`. Il ne decrit pas une intention
graphique : il DESSINE les pixels de la maquette, de facon deterministe. Le
porter fonction pour fonction est ce qui fait que le jeu ressemble a la maquette
plutot qu'a une variation sur son theme.

Regles du portage, a ne pas defaire :

  - `Math.imul` est une multiplication 32 bits signee, et `>>>` un decalage
    NON signe. Les emuler exactement : tout le bruit en depend, donc tout le
    grain du bois et tout le relief des rondins.
  - Le `%` de JavaScript garde le signe du dividende, celui de Python non.
    `jmod` retablit la semantique JS la ou `perlin` replie ses coordonnees.
  - Les GRAINES sont des donnees, pas des reglages. `wood_face(21)`,
    `wood_wall(9)`, `pine_log('t', ..., 41)`, `sky_tex(4)` : les changer change
    la matiere.

Seul le palier narratif 0 (« aube ») est porte. Terasology n'a pas l'equivalent
du curseur narratif du design system, donc ni `P05` ni `P1` ne servent ici.
"""
from __future__ import annotations

import math

from PIL import Image

# --- arithmetique 32 bits a la JavaScript ---------------------------------


def _i32(x):
    x &= 0xFFFFFFFF
    return x - 0x100000000 if x >= 0x80000000 else x


def _u32(x):
    return x & 0xFFFFFFFF


def imul(a, b):
    return _i32(_u32(a) * _u32(b))


def jmod(a, b):
    """Reste a la JavaScript : le signe suit le dividende."""
    r = abs(a) % abs(b)
    return -r if a < 0 else r


# --- bruit deterministe ---------------------------------------------------


def hash3(x, y, sd):
    h = _i32(imul(int(x), 374761393) + imul(int(y), 668265263) + imul(int(sd), 1442695041))
    h = imul(_u32(h) ^ (_u32(h) >> 13), 1274126177)
    h = _i32(_u32(h) ^ (_u32(h) >> 16))
    return _u32(h) / 4294967296.0


def perlin(x, y, px_period, sd):
    x0 = math.floor(x)
    y0 = math.floor(y)
    fx = x - x0
    fy = y - y0

    def fade(t):
        return t * t * t * (t * (t * 6 - 15) + 10)

    def g(ix, iy, dx, dy):
        a = hash3((jmod(ix, px_period) + px_period) % px_period, iy, sd) * 6.2832
        return math.cos(a) * dx + math.sin(a) * dy

    n00 = g(x0, y0, fx, fy)
    n10 = g(x0 + 1, y0, fx - 1, fy)
    n01 = g(x0, y0 + 1, fx, fy - 1)
    n11 = g(x0 + 1, y0 + 1, fx - 1, fy - 1)
    u = fade(fx)
    v = fade(fy)
    return (n00 * (1 - u) + n10 * u) * (1 - v) + (n01 * (1 - u) + n11 * u) * v


def rng(seed):
    """mulberry32, exactement celui de voxel.js."""
    state = [_i32(seed)]

    def nxt():
        state[0] = _i32(state[0] + 0x6D2B79F5)
        s = state[0]
        t = imul(_i32(_u32(s) ^ (_u32(s) >> 15)), _i32(1 | _u32(s)))
        t = _i32(_i32(t + imul(_i32(_u32(t) ^ (_u32(t) >> 7)), _i32(61 | _u32(t)))) ^ t)
        return _u32(_i32(_u32(t) ^ (_u32(t) >> 14))) / 4294967296.0

    return nxt


# --- bitmap ---------------------------------------------------------------


def hex2rgb(h):
    return [int(h[1:3], 16), int(h[3:5], 16), int(h[5:7], 16)]


def rgb2hex(c):
    return "#" + "".join("%02x" % max(0, min(255, round(v))) for v in c)


class Bmp:
    __slots__ = ("w", "h", "d")

    def __init__(self, w, h):
        self.w = int(w)
        self.h = int(h)
        self.d = bytearray(self.w * self.h * 4)


def bmp(w, h):
    return Bmp(w, h)


def px(b, x, y, c, a=None):
    x = int(x)
    y = int(y)
    if x < 0 or y < 0 or x >= b.w or y >= b.h:
        return
    c = hex2rgb(c) if isinstance(c, str) else c
    i = (y * b.w + x) * 4
    b.d[i] = int(c[0])
    b.d[i + 1] = int(c[1])
    b.d[i + 2] = int(c[2])
    b.d[i + 3] = 255 if a is None else max(0, min(255, int(a)))


def mix(a, b, t):
    ca = hex2rgb(a) if isinstance(a, str) else a
    cb = hex2rgb(b) if isinstance(b, str) else b
    return [round(ca[i] + (cb[i] - ca[i]) * t) for i in range(3)]


def ramp(a, b, n):
    return [mix(a, b, 0 if n == 1 else i / (n - 1)) for i in range(n)]


def to_image(b):
    return Image.frombytes("RGBA", (b.w, b.h), bytes(b.d))


# --- grille de forme + ombrage metal --------------------------------------


class Grid:
    __slots__ = ("w", "h", "d")

    def __init__(self, w, h):
        self.w = int(w)
        self.h = int(h)
        self.d = [-1] * (self.w * self.h)


def grid(w, h):
    return Grid(w, h)


def gget(g, x, y):
    x = int(x)
    y = int(y)
    return -1 if (x < 0 or y < 0 or x >= g.w or y >= g.h) else g.d[y * g.w + x]


def gset(g, x, y, v=1):
    x = int(x)
    y = int(y)
    if x < 0 or y < 0 or x >= g.w or y >= g.h:
        return
    g.d[y * g.w + x] = v


def grect(g, x, y, w, h, v=1):
    for j in range(int(h)):
        for i in range(int(w)):
            gset(g, x + i, y + j, v)


STUD = [[3, 4, 1], [4, 3, 1], [1, 1, 1]]


def stud(g, x, y):
    for j in range(3):
        for i in range(3):
            gset(g, x + i, y + j, 10 + STUD[j][i])


def shade_metal(g):
    """Le relief de toute la ferronnerie.

    Il ne pose aucun degrade : il lit le VOISINAGE VIDE. Un pixel au bord est
    noir, un pixel qui a du vide deux crans plus haut ou a gauche s'eclaircit,
    un pixel qui en a en bas ou a droite s'assombrit. C'est ce qui donne aux
    equerres leur arete d'un texel plutot qu'un bord flou.
    """
    o = grid(g.w, g.h)
    for y in range(g.h):
        for x in range(g.w):
            v = gget(g, x, y)
            if v < 0:
                continue
            if v >= 10:
                gset(o, x, y, v - 10)
                continue

            def emp(dx, dy, _x=x, _y=y):
                return gget(g, _x + dx, _y + dy) < 0

            if emp(0, -1) or emp(0, 1) or emp(-1, 0) or emp(1, 0) or emp(-1, -1) or emp(1, 1):
                gset(o, x, y, 0)
                continue
            up = emp(0, -2) or emp(-1, -2)
            dn = emp(0, 2)
            lf = emp(-2, 0) or emp(-2, -1)
            rt = emp(2, 0)
            lv = 2
            if up or lf:
                lv = 3
            if dn or rt:
                lv = 2 if (up or lf) else 1
            if up and lf:
                lv = 4
            gset(o, x, y, lv)
    return o


def paint_grid(g, pal):
    b = bmp(g.w, g.h)
    for y in range(g.h):
        for x in range(g.w):
            v = gget(g, x, y)
            if v >= 0:
                px(b, x, y, pal[v - 10 if v >= 10 else v])
    return b


# --- primitives arrondies (tracees au disque -> gradins) ------------------


def disc(g, cx, cy, r, v=1):
    for y in range(math.floor(cy - r), math.ceil(cy + r) + 1):
        for x in range(math.floor(cx - r), math.ceil(cx + r) + 1):
            dx = x - cx
            dy = y - cy
            if dx * dx + dy * dy <= r * r + .25:
                gset(g, x, y, v)


def round_rect(g, x0, y0, w, h, rad, v=1):
    w = int(w)
    h = int(h)
    for j in range(h):
        for i in range(w):
            dx = max(rad - i, i - (w - 1 - rad), 0)
            dy = max(rad - j, j - (h - 1 - rad), 0)
            if dx * dx + dy * dy <= rad * rad + .3:
                gset(g, x0 + i, y0 + j, v)


def ring_line(g, cx, cy, r, v):
    n = math.ceil(r * 14)
    for i in range(n):
        a = i / n * 6.2832
        gset(g, round(cx + math.cos(a) * r), round(cy + math.sin(a) * r), v)


def volute(g, cx, cy, r0, r1, a0, a1, t):
    n = 260
    for i in range(n + 1):
        u = i / n
        ang = a0 + (a1 - a0) * u
        rad = r0 + (r1 - r0) * u
        disc(g, cx + math.cos(ang) * rad, cy + math.sin(ang) * rad, t / 2)


def mirror_into(src, dst, mode):
    for y in range(src.h):
        for x in range(src.w):
            if gget(src, x, y) >= 0:
                if mode == "diag":
                    gset(dst, y, x, 1)
                elif mode == "v":
                    gset(dst, x, src.h - 1 - y, 1)
                else:
                    gset(dst, src.w - 1 - x, y, 1)


def tube_into(s, draw, w_out, w_in):
    out = grid(s.w, s.h)
    inn = grid(s.w, s.h)
    draw(out, w_out)
    draw(inn, w_in)
    for y in range(s.h):
        for x in range(s.w):
            if gget(s, x, y) >= 0:
                continue
            if gget(inn, x, y) >= 0:
                gset(s, x, y, 12 if gget(inn, x, y - 1) >= 0 else 13)
            elif gget(out, x, y) >= 0:
                gset(s, x, y, 10)


# --- ferrures -------------------------------------------------------------
# `k` est un facteur d'echelle de la FERRURE : la geometrie est redessinee a la
# taille demandee, jamais agrandie depuis un bitmap, pour que les aretes restent
# d'un texel.


def iron_corner(pal, k=1.0):
    def R(v):
        return round(v * k)

    def S(v):
        return v * k

    n = R(30)
    g = grid(n, n)
    round_rect(g, 0, 0, R(16), R(16), max(2, R(4)))
    round_rect(g, 0, R(4), R(24), max(4, R(8)), max(2, R(3)))
    round_rect(g, R(4), 0, max(4, R(8)), R(24), max(2, R(3)))
    disc(g, S(24), S(7.5), 4.4 * k)
    disc(g, S(7.5), S(24), 4.4 * k)
    grect(g, n - 2, R(7), 2, 2)
    grect(g, R(7), n - 2, 2, 2)
    s = shade_metal(g)
    ring_line(s, S(7.5), S(7.5), 5.2 * k, 11)
    if k > .85:
        ring_line(s, S(7.5), S(7.5), 2.6 * k, 11)
    stud(s, R(6), R(6))
    stud(s, R(23), R(6))
    stud(s, R(6), R(23))

    def draw(dst, t):
        tmp = grid(n, n)
        volute(tmp, S(14), S(19), 6.5 * k, 1.6 * k, math.pi * 1.25, math.pi * -0.3, t)
        mirror_into(tmp, dst, "copy")
        mirror_into(tmp, dst, "diag")

    tube_into(s, draw, 5.4 * k, 2.6 * k)
    return paint_grid(s, pal)


def iron_strap(pal, vertical=False, k=1.0):
    def R(v):
        return round(v * k)

    w = R(22)
    h = max(6, R(10))
    g = grid(w, h)
    br = min(4.6 * k, (h - 2) / 2)
    round_rect(g, 0, 1, w, h - 2, max(2, R(3)))
    disc(g, (w - 1) / 2, (h - 1) / 2, br)
    s = shade_metal(g)
    ring_line(s, (w - 1) / 2, (h - 1) / 2, max(1.4, br - 1.4), 11)
    stud(s, R(9), R(4))
    stud(s, R(2), R(4))
    stud(s, R(17), R(4))
    b = paint_grid(s, pal)
    if not vertical:
        return b
    t = bmp(b.h, b.w)
    for y in range(b.h):
        for x in range(b.w):
            i = (y * b.w + x) * 4
            if b.d[i + 3]:
                px(t, b.h - 1 - y, x, [b.d[i], b.d[i + 1], b.d[i + 2]])
    return t


def iron_spike(pal):
    n = 16
    g = grid(n, n)
    grect(g, 12, 2, 3, 12)
    disc(g, 13, 7.5, 2.8)
    s = shade_metal(g)
    stud(s, 12, 6)

    def draw(dst, t):
        tmp = grid(n, n)
        volute(tmp, 7, 6, 6.4, 1.8, 0, math.pi * -1.35, t)
        mirror_into(tmp, dst, "copy")
        mirror_into(tmp, dst, "v")

    tube_into(s, draw, 5.2, 2.4)
    return paint_grid(s, pal)


STUD4 = [[3, 4, 4, 1], [4, 3, 3, 1], [4, 3, 3, 1], [1, 1, 1, 1]]


def iron_stud(pal):
    b = bmp(4, 4)
    for y in range(4):
        for x in range(4):
            px(b, x, y, pal[STUD4[y][x]])
    return b


def plaque_tex(pal):
    w, h = 16, 18
    g = grid(w, h)
    round_rect(g, 0, 0, w, h - 2, 4)
    s = shade_metal(g)
    for x in range(4, w - 4):
        gset(s, x, 3, 11)
        gset(s, x, h - 5, 11)
    for y in range(4, h - 5):
        gset(s, 3, y, 11)
        gset(s, w - 4, y, 11)
    for x in range(2, w - 2):
        gset(s, x, h - 2, 10)
        gset(s, x, h - 1, 10)
    return paint_grid(s, pal)


def slot_tex(pal, n=20):
    """Casier d'inventaire : cuvette creusee a bord biseaute.

    `n` redessine la geometrie a la taille demandee — ce n'est pas une mise a
    l'echelle du bitmap, les aretes restent d'un texel.
    """
    g = grid(n, n)
    round_rect(g, 0, 0, n, n, 3)
    s = shade_metal(g)
    for y in range(3, n - 3):
        for x in range(3, n - 3):
            gset(s, x, y, 10)
    for x in range(3, n - 3):
        gset(s, x, 3, 11)
        gset(s, x, n - 4, 13)
    for y in range(3, n - 3):
        gset(s, 3, y, 11)
        gset(s, n - 4, y, 13)
    stud(s, 1, 1)
    stud(s, n - 4, 1)
    stud(s, 1, n - 4)
    stud(s, n - 4, n - 4)
    return paint_grid(s, [pal[0], pal[1], pal[2], pal[3], pal[4],
                          pal[0], pal[0], pal[0], pal[0], pal[0],
                          pal[0], pal[1], pal[2], pal[0]])


# --- bois -----------------------------------------------------------------


def wood_face(pal, seed):
    """Tuile de bois 48x24 — la fibre est HORIZONTALE, et c'est structurant."""
    w, h = 48, 24
    b = bmp(w, h)
    r = rng(seed)
    base = pal[3]
    base2 = mix(pal[3], pal[4], .45)
    lite = mix(pal[3], pal[5], .38)
    lite2 = mix(pal[3], pal[5], .2)
    dark = mix(pal[3], pal[2], .42)
    dark2 = mix(pal[3], pal[2], .22)
    for y in range(h):
        c = base2 if r() < .3 else base
        for x in range(w):
            px(b, x, y, c)
    for _ in range(34):
        y = math.floor(r() * h)
        length = 5 + math.floor(r() * 15)
        q = r()
        c = dark if q < .3 else dark2 if q < .5 else lite2 if q < .8 else lite
        x = math.floor(r() * w)
        for i in range(length):
            px(b, (x + i) % w, y, c)
    for _ in range(2):
        cx = 3 + math.floor(r() * (w - 6))
        cy = 2 + math.floor(r() * (h - 4))
        px(b, cx, cy, dark)
        px(b, cx + 1, cy, dark2)
        px(b, cx, cy + 1, dark2)
        px(b, cx + 1, cy + 1, dark)
        px(b, cx - 1, cy, dark2)
        px(b, cx + 2, cy + 1, lite2)
    return b


def wood_wall(pal, seed):
    """Paroi 48x48 : trois lames de 16, joint decale, noeuds."""
    w, h, ph = 48, 48, 16
    b = bmp(w, h)
    r = rng(seed)
    joints = [11, 31, 21]
    for p in range(3):
        y0 = p * ph
        base = pal[2 if p == 1 else 3]
        lite = mix(base, pal[5], .34)
        lite2 = mix(base, pal[5], .17)
        dark = mix(base, pal[1], .38)
        dark2 = mix(base, pal[1], .2)
        crest = mix(base, pal[5], .5)
        for y in range(ph):
            for x in range(w):
                px(b, x, y0 + y, crest if y == 0 else pal[0] if y == ph - 1
                   else pal[1] if y == ph - 2 else base)
        for _ in range(34):
            y = 1 + math.floor(r() * (ph - 3))
            length = 5 + math.floor(r() * 18)
            q = r()
            c = dark if q < .3 else dark2 if q < .55 else lite2 if q < .82 else lite
            x = math.floor(r() * w)
            for i in range(length):
                px(b, (x + i) % w, y0 + y, c)
        j = joints[p]
        for y in range(1, ph - 1):
            px(b, j, y0 + y, pal[0])
            px(b, j + 1, y0 + y, dark2)
        for nx in (4, j + 6, w - 7):
            ny = y0 + 4 + math.floor(r() * 6)
            px(b, nx, ny, pal[1])
            px(b, nx + 1, ny, pal[0])
            px(b, nx, ny + 1, pal[0])
            px(b, nx + 1, ny + 1, dark)
    return b


# --- rondin de pin --------------------------------------------------------


def pine_log(side, pal, moss, sd, thick=12):
    """Rondin de pin : quatre faces, chacune sa texture.

    La tuile fait 64 texels de long et se repete SANS COUTURE le long de son
    axe : `perlin` y est appele avec une periode de 5 sur 64, donc le bruit se
    referme. C'est ce qui permet de la tuiler en 9 tranches.
    """
    L = 64
    T = thick
    horiz = side in ("t", "b")
    w = L if horiz else T
    h = T if horiz else L

    def uv(x, y):
        if side == "t":
            return x, y
        if side == "b":
            return x, T - 1 - y
        if side == "l":
            return y, x
        return y, T - 1 - x

    P = 5
    fu = P / L
    fv = .3

    def turb(u, v):
        return abs(perlin(u * fu, v * fv, P, sd)) + .5 * abs(perlin(u * fu * 2, v * fv * 2, P * 2, sd + 1))

    tv = []
    for u in range(L):
        for v in range(T):
            tv.append(turb(u, v))
    thr = sorted(tv)[math.floor(len(tv) * .2)]

    def edge(u):
        n = perlin(u * 8 / L, .5, 8, sd + 7)
        return 0 if n > .08 else 1 if n > -.22 else 2

    def cell(x, y):
        if x < 0 or y < 0 or x >= w or y >= h:
            return "o"
        u, v = uv(x, y)
        if v < edge(u):
            return "o"
        return "c" if turb(u, v) < thr else "p"

    gr = [[cell(x, y) for x in range(w)] for y in range(h)]

    def at(x, y):
        if y < 0 or y >= h:
            return "o"
        return gr[y][(x % w + w) % w]

    def at_y(x, y):
        if x < 0 or x >= w:
            return "o"
        if y < 0 or y >= h:
            return "o" if horiz else gr[(y % h + h) % h][x]
        return gr[y][x]

    nb = at if horiz else at_y
    b = bmp(w, h)
    for y in range(h):
        for x in range(w):
            k = gr[y][x]
            if k == "o":
                continue
            u, v = uv(x, y)
            if k == "c":
                lv = 1 if hash3(u, v, sd + 3) < .22 else 0
            else:
                up = nb(x, y - 1) != "p"
                dn = nb(x, y + 1) != "p"
                lf = nb(x - 1, y) != "p"
                rt = nb(x + 1, y) != "p"
                lv = 4
                if up and not dn:
                    lv = 6
                elif dn and not up:
                    lv = 2
                elif lf and not rt:
                    lv = 5
                elif rt and not lf:
                    lv = 3
                else:
                    st = hash3(math.floor(u / 3), v, sd + 5)
                    if st < .22:
                        lv = 3
                    elif st > .9:
                        lv = 5
                ww = y if horiz else x
                cyl = 1 if ww <= 1 else -2 if ww >= T - 2 else -1 if ww >= T - 4 else 0
                lv = max(1, min(6, lv + cyl))
            px(b, x, y, pal["bark"][lv])
    if moss and side == "t":
        for x in range(w):
            y0 = 0
            while y0 < h and gr[y0][x] == "o":
                y0 += 1
            n = perlin(x * 6 / L, .3, 6, sd + 11)
            d = 3 if n > .18 else 2 if n > -.05 else 1 if n > -.25 else 0
            for i in range(d):
                px(b, x, y0 + i, pal["moss"][2 if i == 0 else (1 if hash3(x, i, sd) < .5 else 0)])
            if d == 3 and y0 > 0:
                px(b, x, y0 - 1, pal["moss"][3])
    return b


# --- monde ----------------------------------------------------------------

BAYER = [[0, 8, 2, 10], [12, 4, 14, 6], [3, 11, 1, 9], [15, 7, 13, 5]]


ASTRE = (148, 24, 6)


def paint_astre(b, w, ax=148, ay=24, r=6):
    """Le soleil : un carre de Tchebychev, bord d'une teinte plus chaude."""
    for y in range(-r, r + 1):
        for x in range(-r, r + 1):
            d = max(abs(x), abs(y))
            px(b, ax + x, ay + y, w["astre"][1] if d == r else w["astre"][0])


def sky_tex(w, sd, astre=True):
    """Le ciel, tuile en x.

    `astre` peut etre coupe : le soleil vit a l'interieur de la tuile, donc
    tuiler la tuile le repete. Le poser a part est le seul moyen de n'en avoir
    qu'un sur un decor plus large que 192 texels.
    """
    W, H = 192, 200
    b = bmp(W, H)
    steps = ramp(w["skyTop"], w["skyBot"], 7)
    for y in range(H):
        t = y / (H - 1) * (len(steps) - 1)
        i = min(len(steps) - 2, math.floor(t))
        f = t - i
        for x in range(W):
            px(b, x, y, steps[i + 1 if BAYER[y & 3][x & 3] / 16 < f else i])
    if w["starOpacity"] > .04:
        for k in range(110):
            x = math.floor(hash3(k, 1, sd) * W)
            y = math.floor(hash3(k, 2, sd) * (H * .55))
            a = round(255 * w["starOpacity"])
            px(b, x, y, w["stars"][0], a)
            if hash3(k, 3, sd) < .2:
                px(b, x + 1, y, w["stars"][1], a)
                px(b, x - 1, y, w["stars"][1], a)
                px(b, x, y + 1, w["stars"][1], a)
                px(b, x, y - 1, w["stars"][1], a)
    ax, ay, R = ASTRE
    if astre:
        paint_astre(b, w, ax, ay)
    if astre and w["astreBite"] > .5:
        for cx, cy in ((-3, -2), (1, 1), (2, -3), (-1, 3)):
            px(b, ax + cx, ay + cy, w["astre"][1])
            px(b, ax + cx + 1, ay + cy, w["astre"][1])
            px(b, ax + cx, ay + cy + 1, w["astre"][1])

    def cloud(cx, cy, rows, sdd):  # noqa: E306
        cells = []
        for j, rw in enumerate(rows):
            for i in range(rw[1]):
                if hash3(i, j, sdd) > .12 or j == 1:
                    cells.append((rw[0] + i, j))
        cellset = set(cells)
        for bx, by in cells:
            for y in range(8):
                for x in range(8):
                    gx = ((cx + bx * 8 + x) % W + W) % W
                    gy = cy + by * 8 + y
                    top = (bx, by - 1) not in cellset and y < 2
                    bot = (bx, by + 1) not in cellset and y > 5
                    px(b, gx, gy, w["cloud"][0 if top else 2 if bot else 1])

    cloud(14, 40, [[1, 3], [0, 5], [1, 3]], 5)
    cloud(108, 16, [[1, 2], [0, 4]], 9)
    cloud(66, 78, [[0, 3], [1, 3]], 13)
    return b


def mountain_tex(w, sd):
    W, H = 192, 48
    b = bmp(W, H)
    for x in range(W):
        n = perlin(x * .042, .5, math.ceil(W * .042), sd) + .3 * perlin(x * .1, 2.5, math.ceil(W * .1), sd + 4)
        top = round(H * .5 - n * H * .62)
        for y in range(max(0, top), H):
            d = y - top
            r = hash3(x, y, sd + 2)
            if d < 4 - (1 if r < .4 else 0) and top < H * .22:
                c = w["snow"][1 if r < .4 else 0]
            elif d < 2:
                c = w["mount"][2 if r < .5 else 0]
            else:
                c = w["mount"][1 if r < .22 else 0 if r < .8 else 2]
            px(b, x, y, mix(c, w["skyTop"], .16))
    return b


def terrain_tex(w, sd):
    W, H, B = 192, 64, 8
    b = bmp(W, H)
    cols = W // B

    def clamp(v, a, z):
        return max(a, min(z, v))

    far = []
    near = []
    for i in range(cols):
        far.append(clamp(2 + round(perlin(i * .5, .5, cols, 3) * 2.4), 1, 3))
        near.append(clamp(4 + round(perlin(i * .38, 1.5, cols, 11) * 1.9), 4, 5))
    for i in range(cols):
        top = far[i] * B
        for y in range(top, H):
            for x in range(B):
                gx = i * B + x
                r = hash3(gx, y, sd + 9)
                if y < top + 3:
                    c = w["far"][2 if r < .45 else 0]
                elif y < top + B:
                    c = w["far"][1 if r < .3 else 0]
                else:
                    c = w["farDark"][1 if r < .4 else 0]
                if x == 0:
                    c = mix(c, "#000000", .08)
                px(b, gx, y, c)

    def pine(bx, ground_y, s):
        for y in range(1, 4):
            px(b, bx, ground_y - y, w["trunk"][0])
            px(b, bx + 1, ground_y - y, w["trunk"][1])
        base = ground_y - 2
        for t in range(s + 1):
            hw = max(1, s - t + 1)
            for r in range(2):
                y = base - t * 2 - r
                for x in range(-hw, hw + 2):
                    px(b, bx + x, y, w["leaf"][0] if (r == 0 or x == -hw or x == hw + 1) else w["leaf"][1])
        px(b, bx, base - s * 2 - 3, w["leaf"][1])
        px(b, bx + 1, base - s * 2 - 3, w["leaf"][0])

    for bx, s in ((22, 4), (52, 3), (86, 2), (124, 4), (156, 3)):
        pine(bx, far[bx // B] * B, s)

    for i in range(cols):
        top = near[i] * B
        for y in range(top, H):
            for x in range(B):
                gx = i * B + x
                r = hash3(gx, y, sd + 1)
                d = y - top
                if d < 5:
                    c = w["grass"][3 if r < .22 else 0 if r < .58 else 1 if r < .9 else 2]
                elif d == 5:
                    c = w["grass"][3] if r < .5 else w["dirt"][2]
                elif y < H - B:
                    c = w["dirt"][1 if r < .3 else 0 if r < .82 else 2]
                else:
                    c = w["stone"][1 if r < .35 else 0]
                if x == 0:
                    c = mix(c, "#000000", .1)
                if d == 0:
                    c = mix(c, "#ffffff", .12)
                px(b, gx, y, c)
        for x in range(B):
            if hash3(i * B + x, 77, sd) < .16:
                px(b, i * B + x, top - 1, w["grass"][2 if hash3(i, x, sd) < .5 else 0])
    return b


# --- ruban de magie et ornements -----------------------------------------


def flux_tex(pal, sd):
    W, H = 96, 40
    b = bmp(W, H)

    def wave(x, a, ph):
        return H / 2 + a * math.sin(2 * math.pi * x / W + ph)

    for x in range(W):
        y0 = wave(x, 12, 0)
        for dy in range(-10, 11):
            a = abs(dy)
            if a <= 2:
                continue
            y = round(y0 + dy)
            if BAYER[(y % 4 + 4) % 4][x & 3] / 16 < (10 - a) / 8 * .55:
                px(b, x, y, pal["glow"], round(88 - a * 6))
    for x in range(W):
        y = round(wave(x, 8, math.pi * .62))
        px(b, x, y, pal["mid"], 135)
        px(b, x, y + 1, pal["outer"], 95)
    for x in range(W):
        y0 = wave(x, 12, 0)
        for dy in range(-2, 3):
            y = round(y0 + dy)
            px(b, x, y, pal["mid"] if dy == -2 else pal["core2"] if dy == -1
               else pal["core"] if dy == 0 else pal["mid"] if dy == 1 else pal["outer"])
    for k in range(12):
        x = math.floor(hash3(k, 1, sd) * W)
        o = (-1 if hash3(k, 2, sd) < .5 else 1) * (4 + math.floor(hash3(k, 3, sd) * 7))
        px(b, x, round(wave(x, 12, 0)) + o, pal["core2"], 210)
    return b


def magic_curl(pal):
    n = 20
    s = grid(n, n)
    tube_into(s, lambda dst, t: volute(dst, 8, 10, 8, 1.6, math.pi * -.12, math.pi * 1.55, t), 5.2, 2.4)
    return paint_grid(s, pal)


def gem_crest(iron, gem):
    W, H = 16, 14
    g = grid(W, H)
    for y in range(H):
        for x in range(W):
            if abs(x - 7.5) / 1.18 + abs(y - 6.5) <= 6.6:
                gset(g, x, y, 1)
    s = shade_metal(g)
    b = paint_grid(s, iron)
    for y in range(H):
        for x in range(W):
            d = abs(x - 7.5) / 1.18 + abs(y - 6.5)
            if d <= 3.4:
                px(b, x, y, gem[0] if d > 2.6 else gem[2] if (x < 7 and y < 6) else gem[1])
    px(b, 6, 5, gem[3])
    px(b, 7, 4, gem[3])
    return b


# --- lettrage voxel -------------------------------------------------------


def mask_logo(mask, W, H, pal, sd=7):
    def at(m, x, y):
        return 0 if (x < 0 or y < 0 or x >= W or y >= H) else m[y * W + x]

    def dilate(m):
        o = bytearray(W * H)
        for y in range(H):
            for x in range(W):
                if (at(m, x, y) or at(m, x - 1, y) or at(m, x + 1, y) or at(m, x, y - 1)
                        or at(m, x, y + 1) or at(m, x - 1, y - 1) or at(m, x + 1, y - 1)
                        or at(m, x - 1, y + 1) or at(m, x + 1, y + 1)):
                    o[y * W + x] = 1
        return o

    def shift(m, dx, dy):
        o = bytearray(W * H)
        for y in range(H):
            for x in range(W):
                if at(m, x - dx, y - dy):
                    o[y * W + x] = 1
        return o

    def bor(a, b):
        return bytearray(a[i] | b[i] for i in range(W * H))

    body = bor(mask, shift(mask, 0, 2))
    dark = dilate(body)
    rim = dilate(dark)
    shadow = shift(dark, 1, 3)
    x0, x1, y0, y1 = W, -1, H, -1
    for y in range(H):
        for x in range(W):
            if mask[y * W + x]:
                x0 = min(x0, x)
                x1 = max(x1, x)
                y0 = min(y0, y)
                y1 = max(y1, y)
    if x1 < x0:
        return bmp(1, 1)
    L, R, T, B = 3, 4, 3, 9
    n = len(pal["fill"])
    b = bmp(x1 - x0 + 1 + L + R, y1 - y0 + 1 + T + B)
    for y in range(y0 - T, y1 + B + 1):
        for x in range(x0 - L, x1 + R + 1):
            bx = x - (x0 - L)
            by = y - (y0 - T)
            if at(mask, x, y):
                t = (y - y0) / max(1, y1 - y0)
                lv = min(n - 1, math.floor(t * n))
                g = hash3(x, y, sd)
                if g < .075:
                    lv = max(0, lv - 1)
                elif g > .945:
                    lv = min(n - 1, lv + 1)
                if not at(mask, x, y - 1):
                    lv = 0
                elif not at(mask, x, y + 1):
                    lv = n - 1
                px(b, bx, by, pal["fill"][lv])
            elif at(dark, x, y):
                px(b, bx, by, pal["face"] if at(body, x, y) else pal["edge"])
            elif at(rim, x, y):
                px(b, bx, by, pal["rimLo"] if at(shadow, x, y) else pal["rim"])
            elif at(shadow, x, y):
                px(b, bx, by, pal["shadow"][0:3], pal["shadow"][3])
    return b


def text_voxel(text, size, pal, ttf_path):
    """Rasterise le mot, puis le passe au moulin a voxels.

    `voxel.js` passe par un canvas ; ici c'est Pillow, sur le TTF deja mis en
    cache par `build_fonts.py`. Le masque est le meme : alpha > 100.
    """
    from PIL import ImageDraw, ImageFont

    font = ImageFont.truetype(str(ttf_path), size)
    asc, desc = font.getmetrics()
    probe = Image.new("L", (8, 8))
    width = math.ceil(ImageDraw.Draw(probe).textlength(text, font=font))
    W = width + 16
    H = asc + desc + 16
    img = Image.new("L", (W, H), 0)
    ImageDraw.Draw(img).text((8, asc + 7), text, font=font, fill=255, anchor="ls")
    src = img.load()
    mask = bytearray(W * H)
    for y in range(H):
        for x in range(W):
            if src[x, y] > 100:
                mask[y * W + x] = 1
    return mask_logo(mask, W, H, pal)


# --- palette, palier narratif 0 (« aube ») --------------------------------
# Recopiee telle quelle depuis P0 dans voxel.js. Ne pas « corriger » une teinte.

P0 = {
    "iron": ["#23282b", "#5c666c", "#9aa5ab", "#e3e9ec", "#ffffff"],
    "wood": ["#3d200d", "#5b3216", "#74411b", "#a8672f", "#c07f3d", "#d49a5c"],
    "woodWall": ["#20120a", "#33190c", "#4a2712", "#5e3318", "#764122", "#8d5029"],
    "woodQuit": ["#2e1008", "#4a1a0c", "#6b2a14", "#8e4231", "#a4553d", "#bb6d52"],
    "pine": {
        "bark": ["#26110a", "#4a2213", "#6a3219", "#874222", "#a4552c", "#c26d3a", "#dc8a50"],
        "moss": ["#3f7a24", "#58a034", "#7cc452", "#9ad866"],
    },
    "logo": {
        "edge": "#221f38",
        "face": "#4a2c15",
        "rim": "#e4ebf4",
        "rimLo": "#8c93a6",
        "shadow": [14, 12, 28, 120],
        "fill": ["#e6ad68", "#cf914c", "#b0733a", "#8e5628", "#6f411c"],
    },
    "flux": {
        "core": "#ffffff", "core2": "#cdf5ff", "mid": "#57d0ff",
        "outer": "#2192d6", "glow": "#7fe4ff",
    },
    "magic": ["#0d3a5c", "#1f7fc0", "#48c8f5", "#c8f2ff", "#ffffff"],
    "gem": ["#123f66", "#2f8fd6", "#7fdcff", "#ffffff"],
    "fire": ["#7a1f06", "#c24a10", "#f08a1c", "#ffd24a", "#fff2a8"],
    "cream": "#fff3dc",
    "world": {
        "mount": ["#8b9fb6", "#7a8da6", "#9db0c4"],
        "snow": ["#eaf2f8", "#cfdeea"],
        "skyTop": "#8ecdf5",
        "skyBot": "#f7ddaa",
        "cloud": ["#ffffff", "#e6f2fb", "#c6dcef"],
        "astre": ["#fff0a8", "#ffcf5c"],
        "astreBite": 0,
        "starOpacity": 0,
        "stars": ["#ffffff", "#8f86c9"],
        "grass": ["#4f9d2f", "#5cae37", "#7cc452", "#3f7a24"],
        "dirt": ["#8a5a33", "#754a29", "#9c6b3d"],
        "stone": ["#7f7f88", "#6b6b73"],
        "far": ["#6ba84a", "#5b9440", "#7db85a"],
        "farDark": ["#4a7a34", "#3f6a2c"],
        "trunk": ["#6a3219", "#4a2213"],
        "leaf": ["#2f6b25", "#3d8630"],
    },
}
