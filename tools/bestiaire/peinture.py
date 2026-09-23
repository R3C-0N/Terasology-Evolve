#!/usr/bin/env python3
# Copyright 2026 The Terasology Foundation
# SPDX-License-Identifier: Apache-2.0
"""Peinture des creatures du bestiaire — port litteral de `bestiaire.js`.

`bestiaire.js` vit dans le projet de conception publie sur Claude Design. Il ne
decrit pas une intention graphique : il PEINT les pixels de la maquette, de
facon deterministe, face par face. Le porter fonction pour fonction est ce qui
fait que la creature en jeu est celle de la maquette, et non une variation sur
son theme. C'est la meme regle que `tools/ui/voxel.py` suit pour l'interface.

Regles du portage, a ne pas defaire :

  - `Math.imul` est une multiplication 32 bits signee et `>>>` un decalage NON
    signe : `hache` en depend, donc la graine de chaque face, donc tout le grain.
  - Le `^=` de JavaScript convertit d'abord en entier 32 bits SIGNE. Sans ce
    passage, la graine derive des le premier caractere accentue.
  - `bruit` est un sinus tronque : la valeur ne veut rien dire, seule compte son
    egalite avec celle de la maquette.

Les quinze motifs sont portes, pas seulement les deux dont le mannequin se sert
(`ecorce`, `laine`) : ils sont des one-liners, et les seize creatures suivantes
les reclameront toutes. Seuls `ecorce` et `laine` sont donc eprouves a ce jour.

L'atlas est range en etageres, largeur 256 comme dans la maquette. La largeur ne
change aucun pixel — les graines dependent du nom de la piece et de la face,
jamais de la position dans l'image — mais la garder identique permet de
comparer deux atlas a l'oeil.
"""
from __future__ import annotations

import math

# --- arithmetique 32 bits a la JavaScript ---------------------------------


def _i32(x):
    x &= 0xFFFFFFFF
    return x - 0x100000000 if x >= 0x80000000 else x


def _u32(x):
    return x & 0xFFFFFFFF


def imul(a, b):
    return _i32(_u32(a) * _u32(b))


# --- bruit et graines -----------------------------------------------------


def bruit(x, y, s):
    v = math.sin((x + 1) * 12.9898 + (y + 1) * 78.233 + s * 3.77) * 43758.5453
    return v - math.floor(v)


def hache(chaine):
    x = 2166136261
    for c in chaine:
        x = _i32(_i32(x) ^ ord(c))
        x = imul(x, 16777619)
    return _u32(x) % 997


# --- motifs ---------------------------------------------------------------
#
# Chaque motif rend une couleur `#rrggbb` prise dans la palette `p`, ou None
# pour un pixel laisse transparent. Les arguments sont ceux de la maquette :
# (x, y, palette, graine, largeur de face, hauteur de face, image d'animation).


def _uni(x, y, p, s, fw=0, fh=0, fr=0):
    r = bruit(x, y, s)
    return p[0] if r < 0.12 else p[2] if r > 0.9 else p[1]


def _peau(x, y, p, s, fw=0, fh=0, fr=0):
    r = bruit(x, y, s)
    return p[0] if r < 0.1 else p[2] if r > 0.92 else p[1]


def _poil(x, y, p, s, fw=0, fh=0, fr=0):
    r = bruit(x, math.floor(y / 2) * 7 + (x % 2), s) * 0.6 + bruit(x, y, s + 1) * 0.4
    return p[0] if r < 0.3 else p[2] if r > 0.72 else p[1]


def _ecaille(x, y, p, s, fw=0, fh=0, fr=0):
    if y % 2 == 0 and (x + (math.floor(y / 2) % 2)) % 2 == 0:
        return p[0]
    return p[2] if bruit(x, y, s) > 0.8 else p[1]


def _chitine(x, y, p, s, fw=0, fh=0, fr=0):
    if y == 0:
        return p[2]
    if y == fh - 1:
        return p[0]
    r = bruit(x, y, s)
    return p[0] if r < 0.15 else p[2] if r > 0.85 else p[1]


def _ecorce(x, y, p, s, fw=0, fh=0, fr=0):
    c = bruit(x, 0, s)
    r = bruit(x, y, s + 2)
    if c < 0.3 and r < 0.8:
        return p[0]
    return p[2] if r > 0.85 else p[1]


def _feuille(x, y, p, s, fw=0, fh=0, fr=0):
    r = bruit(math.floor(x / 2) * 3 + (y % 2), math.floor(y / 2), s) * 0.5 + bruit(x, y, s + 3) * 0.5
    return p[0] if r < 0.33 else p[2] if r > 0.66 else p[1]


def _laine(x, y, p, s, fw=0, fh=0, fr=0):
    r = bruit(math.floor(x / 2), math.floor(y / 2), s)
    q = bruit(x, y, s + 5)
    if r > 0.55:
        return p[2] if q > 0.3 else p[1]
    return p[0] if q < 0.25 else p[1]


def _plume(x, y, p, s, fw=0, fh=0, fr=0):
    if y % 2 == 1 and (x + (0 if y % 4 < 2 else 1)) % 2 == 0:
        return p[0]
    return p[2] if bruit(x, y, s) > 0.8 else p[1]


def _lave(x, y, p, s, fw=0, fh=0, fr=0):
    r = bruit(x, y, s)
    c = bruit(math.floor(x / 3), math.floor(y / 3), s + 7)
    if r > 0.975:
        return p[3]
    if c > 0.84 and r > 0.6:
        return p[4]
    return p[0] if r < 0.3 else p[2] if r > 0.8 else p[1]


def _cristal(x, y, p, s, fw=0, fh=0, fr=0):
    if (x + y + s) % 5 == 0:
        return p[2]
    if (x - y + 70 + s) % 7 == 0:
        return p[0]
    return p[2] if bruit(x, y, s) > 0.85 else p[1]


def _taches(x, y, p, s, fw=0, fh=0, fr=0):
    t = bruit(math.floor((x + (s % 5)) / 3), math.floor(y / 3), s) > 0.62
    r = bruit(x, y, s + 1)
    if t:
        return p[3] if r > 0.8 else p[0]
    return p[2] if r < 0.15 else p[1]


def _flamme(x, y, p, s, fw=0, fh=0, fr=0):
    r = bruit(x, y, s) * 0.35 + (1 - y / fh) * 0.8
    return p[2] if r > 0.78 else p[1] if r > 0.45 else p[0]


def _feu(x, y, p, s, fw=0, fh=0, fr=0):
    t = 1 - (y + 0.5) / fh
    lang = bruit(x, 3 + fr * 5, s + 11) * 0.55 + bruit(x, y + fr * 2, s + fr) * 0.45
    if t > 0.35 and lang < (t - 0.35) * 1.5:
        return None
    v = (1 - t) * 0.55 + bruit(x, y + fr * 3, s + 2) * 0.5
    return p[3] if v > 0.82 else p[2] if v > 0.6 else p[1] if v > 0.35 else p[0]


def _corne(x, y, p, s, fw=0, fh=0, fr=0):
    if y % 3 == 0:
        return p[0]
    return p[2] if bruit(x, y, s) > 0.8 else p[1]


def _membrane(x, y, p, s, fw=0, fh=0, fr=0):
    cell = x % 8
    n = 3 - abs(cell - 4)
    if n > 0 and y >= fh - n:
        return None
    if cell == 0:
        return p[0]
    return p[2] if bruit(x, y, s) > 0.85 else p[1]


MOTIFS = {
    "uni": _uni, "peau": _peau, "poil": _poil, "ecaille": _ecaille,
    "chitine": _chitine, "ecorce": _ecorce, "feuille": _feuille, "laine": _laine,
    "plume": _plume, "lave": _lave, "cristal": _cristal, "taches": _taches,
    "flamme": _flamme, "feu": _feu, "corne": _corne, "membrane": _membrane,
}

# --- faces ----------------------------------------------------------------

FACES = ("devant", "derriere", "gauche", "droite", "dessus", "dessous")

# L'eclairage est cuit dans la texture, comme dans la maquette : c'est ce qui
# donne du relief a un modele dont toutes les faces sont planes.
LUM = {"devant": 1.0, "derriere": 0.78, "gauche": 0.88, "droite": 0.88,
       "dessus": 1.12, "dessous": 0.7}

LARGEUR_ATLAS = 256


def dims_face(s, f):
    largeur, hauteur, profondeur = s
    if f in ("devant", "derriere"):
        r = (largeur, hauteur)
    elif f in ("gauche", "droite"):
        r = (profondeur, hauteur)
    else:
        r = (largeur, profondeur)
    return (max(1, math.ceil(r[0])), max(1, math.ceil(r[1])))


def marche(parts):
    """Parcours prefixe de l'arbre des pieces, dans l'ordre de la maquette."""
    for p in parts:
        yield p
        if p.get("c"):
            yield from marche(p["c"])


def teinte(creature, nom):
    """La couleur d'un decalque : une entree de `c`, une MATIERE, ou un hexa.

    Le renvoi a une matiere est ce que la maquette voulait dire sans le dire :
    la crete d'un sanglier y est peinte « crin », qui n'est pas une couleur mais
    le poil dur de sa nuque, et le brassard de la brute est peint « fer ». Le
    canevas du navigateur ignore une `fillStyle` invalide et garde la
    precedente, donc la faute ne s'y voit pas la-bas ; ici le nom prend le ton
    median de la matiere, qui est ce qu'on lit a l'ecran.
    """
    couleurs = creature.get("c", {})
    if nom in couleurs:
        return couleurs[nom]
    matiere = creature["mat"].get(nom)
    if matiere:
        return matiere["pal"][1]
    return nom


def _couleur(hexa):
    h = hexa.lstrip("#")
    return (int(h[0:2], 16), int(h[2:4], 16), int(h[4:6], 16), 255)


def atlas(creature):
    """Peint l'atlas d'une creature.

    Rend `(largeur, hauteur, pixels, rects)` : `pixels` est un bytearray RGBA
    ligne par ligne, `rects` donne `(x, y, w, h)` par `(piece, face)`.

    Les pieces sont reperees par IDENTITE, comme la `Map` de la maquette, et
    non par leur nom : les deux bois d'un cerf portent les memes noms
    d'andouiller, et une cle par nom n'en garderait qu'un sur deux.
    """
    cases = []
    for part in marche(creature["parts"]):
        for f in FACES:
            w, h = dims_face(part["s"], f)
            cases.append({"part": part, "f": f, "w": w, "h": h})

    # Tri par hauteur decroissante, STABLE : `sorted` de Python et `Array.sort`
    # de JavaScript le sont tous les deux, donc les egalites gardent l'ordre du
    # parcours et les deux atlas se rangent pareil.
    x = y = rh = 0
    for o in sorted(cases, key=lambda o: -o["h"]):
        if x + o["w"] > LARGEUR_ATLAS:
            x = 0
            y += rh
            rh = 0
        o["x"] = x
        o["y"] = y
        x += o["w"]
        rh = max(rh, o["h"])
    largeur, hauteur = LARGEUR_ATLAS, y + rh

    pixels = bytearray(largeur * hauteur * 4)

    def poser(px, py, c):
        i = (py * largeur + px) * 4
        pixels[i:i + 4] = bytes(c)

    def lire(px, py):
        i = (py * largeur + px) * 4
        return pixels[i:i + 4]

    rects = {}
    for o in cases:
        part, f = o["part"], o["f"]
        mat = creature["mat"].get(part["m"], {"motif": "uni", "pal": ["#555555", "#777777", "#999999"]})
        pal = mat.get("haut") if (f == "dessus" and mat.get("haut")) else \
            mat.get("bas") if (f == "dessous" and mat.get("bas")) else mat["pal"]
        motif = MOTIFS.get(mat["motif"], MOTIFS["uni"])
        graine = hache(creature["id"] + part["n"] + f)

        for j in range(o["h"]):
            for i in range(o["w"]):
                c = motif(i, j, pal, graine, o["w"], o["h"], 0)
                if c is not None:
                    poser(o["x"] + i, o["y"] + j, _couleur(c))

        for ft in part.get("f", []):
            k = ft[0]
            ok = False
            miroir = False
            if k == f:
                ok = True
            elif k == "cotes" and f in ("gauche", "droite"):
                ok, miroir = True, f == "droite"
            elif k == "deux" and f in ("devant", "derriere"):
                ok, miroir = True, f == "derriere"
            elif k == "tour" and f not in ("dessus", "dessous"):
                ok = True
            if not ok:
                continue
            fx, fw = ft[1], ft[3]
            if miroir:
                fx = o["w"] - fx - fw
            x0, x1 = max(0, fx), min(o["w"], fx + fw)
            y0, y1 = max(0, ft[2]), min(o["h"], ft[2] + ft[4])
            if x1 <= x0 or y1 <= y0:
                continue
            vide = ft[5] == "vide"
            c = (0, 0, 0, 0) if vide else _couleur(teinte(creature, ft[5]))
            for j in range(y0, y1):
                for i in range(x0, x1):
                    poser(o["x"] + i, o["y"] + j, c)

        # L'eclairage vient en dernier, decalques compris : c'est l'ordre de la
        # maquette, et c'est ce qui evite qu'une cible peinte reste plate.
        lum = LUM[f]
        if part.get("e"):
            lum = max(0.9, min(1.05, lum))
        if lum != 1.0:
            for j in range(o["h"]):
                for i in range(o["w"]):
                    r, v, b, a = lire(o["x"] + i, o["y"] + j)
                    # `Uint8ClampedArray` arrondit au plus proche, les moities
                    # vers le pair : c'est exactement ce que fait `round` ici.
                    poser(o["x"] + i, o["y"] + j,
                          (min(255, round(r * lum)), min(255, round(v * lum)), min(255, round(b * lum)), a))

        rects[(id(part), f)] = (o["x"], o["y"], o["w"], o["h"])

    return largeur, hauteur, pixels, rects
