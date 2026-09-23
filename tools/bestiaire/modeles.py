#!/usr/bin/env python3
# Copyright 2026 The Terasology Foundation
# SPDX-License-Identifier: Apache-2.0
"""Les creatures du bestiaire, transcrites depuis `bestiaire.js`.

Tout est en TEXELS, l'unite des heros : un texel vaut 0,05625 bloc, soit 32 par
1,8 bloc de personnage. C'est la meme constante que `tools/heros/construire.py`,
et c'est ce qui fait qu'une creature et un heros posent a la meme echelle.

Le repere est celui de la maquette, donc **y descend** : une piece a `y = -20`
est vingt texels AU-DESSUS du sol, et les pieds sont a `y = 0`.
`tools/bestiaire/construire.py` fait la bascule vers le repere du jeu.

Une piece :

    P(nom, [largeur, hauteur, profondeur], position, origine, ...)

`position` place le NOEUD de la piece ; `origine` decale la boite par rapport a
ce noeud, et c'est ce qui donne un pivot a une piece qui tourne. Les enfants
(`c`) pendent du noeud, jamais de l'origine. `a` anime le noeud, `f` peint des
decalques, `m` nomme la matiere, `e` dit que la piece s'eclaire elle-meme.
"""
from __future__ import annotations

import math

TEXEL_M = 0.05625
PI = math.pi


def P(n, s, p, o=None, **kw):
    piece = {"n": n, "s": s, "p": p, "o": o or [0, 0, 0]}
    piece.update(kw)
    return piece


# --- rotations de piece ---------------------------------------------------
#
# `r` est le triplet de degres que la maquette passe a CSS, et l'ordre de ses
# trois rotations est celui de `rot()` dans `bestiaire.js` :
#
#     rotateY(r[1]) rotateX(r[0]) rotateZ(r[2])
#
# donc la matrice est `Ry . Rx . Rz`, appliquee au repere de la MAQUETTE — x a
# droite, y vers le BAS, z vers le spectateur. Les matrices ci-dessous sont
# celles que la specification CSS ecrit, sans reinterpretation : c'est ce qui
# fait qu'un bois de cerf pointe du meme cote en jeu et dans la maquette.


def rotation(r):
    """La matrice 3x3 d'un `r` de la maquette. `None` ou nul rend l'identite."""
    if not r:
        return IDENTITE
    ax, ay, az = (math.radians(v) for v in (r[0], r[1], r[2]))
    cx, sx = math.cos(ax), math.sin(ax)
    cy, sy = math.cos(ay), math.sin(ay)
    cz, sz = math.cos(az), math.sin(az)
    mx = ((1, 0, 0), (0, cx, -sx), (0, sx, cx))
    my = ((cy, 0, sy), (0, 1, 0), (-sy, 0, cy))
    mz = ((cz, -sz, 0), (sz, cz, 0), (0, 0, 1))
    return produit(produit(my, mx), mz)


IDENTITE = ((1.0, 0.0, 0.0), (0.0, 1.0, 0.0), (0.0, 0.0, 1.0))


def produit(a, b):
    return tuple(tuple(sum(a[i][k] * b[k][j] for k in range(3)) for j in range(3))
                 for i in range(3))


def appliquer(m, p):
    return tuple(sum(m[i][j] * p[j] for j in range(3)) for i in range(3))


def pattes(o):
    """Les quatre pattes d'un quadrupede, telles que `pattes()` de la maquette.

    Le noeud d'une patte est en HAUT (`y = -hh`) et l'origine la redescend d'une
    demi-hauteur : c'est ce qui met le pivot a l'epaule et non au sabot. Les
    diagonales sont en opposition de phase, d'ou la marche.
    """
    w = o["w"]
    d = o.get("d") or w
    h = o["h"]
    hb = o.get("hb") or h
    amp = 20 if o.get("amp") is None else o["amp"]
    sh = o.get("sh") or 2

    def decalques(hh):
        sabots = []
        if o.get("sabot"):
            sabots = [["tour", 0, hh - sh, 99, sh, o["sabot"]],
                      ["dessous", 0, 0, 99, 99, o["sabot"]]]
        return sabots + list(o.get("f") or [])

    def patte(n, x, z, ph, hh):
        return P(n, [w, hh, d], [x, -hh, z], [0, hh / 2, 0], m=o["m"], f=decalques(hh),
                 a={"ax": "x", "amp": amp, "ph": ph, "v": o.get("v")})

    return [patte("patteAVD", -o["x"], o["zf"], 0, h),
            patte("patteAVG", o["x"], o["zf"], PI, h),
            patte("patteARD", -o["x"], o["zb"], PI, hb),
            patte("patteARG", o["x"], o["zb"], 0, hb)]


def oeilD(x, y, w=None, c=None):
    return ["devant", x, y, w or 1, 1, c or "oeil"]


def boisCerf(m):
    """Les deux bois d'un cervide, tels que `boisCerf()` de la maquette.

    Six pieces par bois, chacune tournee dans le repere de sa mere : c'est le
    premier modele du bestiaire qui ne tient pas sans les rotations de piece.
    Les deux cotes portent les memes noms d'andouiller — la maquette les range
    par objet et non par nom, et l'atlas d'ici fait de meme.
    """
    return [P("bois%d" % sg, [1, 5, 1], [sg * 1.5, -3.5, 0.5], [0, -2.5, 0], m=m,
              r=[10, 0, sg * 25],
              c=[P("andouiller1", [1, 3, 1], [0, -2, 0], [0, -1.5, 0], m=m, r=[-60, 0, 0]),
                 P("merrain", [1, 5, 1], [0, -5, 0], [0, -2.5, 0], m=m, r=[0, 0, sg * 18],
                   c=[P("andouiller2", [1, 3, 1], [0, -2, 0], [0, -1.5, 0], m=m, r=[-50, 0, 0]),
                      P("andouiller3", [1, 3, 1], [0, -4, 0], [0, -1.5, 0], m=m, r=[0, 0, sg * 45]),
                      P("pointe", [1, 3, 1], [0, -5, 0], [0, -1.5, 0], m=m, r=[18, 0, sg * -15])])])
            for sg in (-1, 1)]



def flamme(n, t, p, ph=None, r=None):
    """Une flamme : une boite ouverte vers le haut, peinte par le motif `feu`.

    `ph` est la phase que la maquette passe et que sa propre aide n'emploie
    pas — une flamme y bouge par IMAGES DE TEXTURE, pas par un os. L'argument
    reste pour que les appels se lisent comme la-bas ; ici la flamme est fixe
    et c'est son grain qui la fait brûler.
    """
    return P(n, [t, t * 2, t], p, [0, -t, 0], m="flamme", e=True, r=r)


def boisCristal(m):
    """Les bois du cerf de cristal : meme charpente que `boisCerf`, six eclats.

    Toutes les pieces sont `e` — elles s'eclairent elles-memes, donc leurs six
    faces sortent de l'atlas a la meme valeur au lieu de suivre `LUM`. Un
    cristal qui aurait un dessous sombre ne serait pas un cristal.
    """
    return [P("cristal%d" % sg, [2, 6, 2], [sg * 1.5, -3.5, 0.5], [0, -3, 0], m=m, e=True,
              r=[8, 0, sg * 28],
              c=[P("eclat1", [1, 4, 1], [0, -2, 0], [0, -2, 0], m=m, e=True, r=[-60, 0, 0]),
                 P("eclat2", [2, 6, 2], [0, -6, 0], [0, -3, 0], m=m, e=True,
                   r=[0, 0, sg * 16],
                   c=[P("eclat3", [1, 5, 1], [0, -2, 0], [0, -2.5, 0], m=m, e=True,
                        r=[-45, 0, sg * 20]),
                      P("eclat4", [1, 4, 1], [0, -4, 0], [0, -2, 0], m=m, e=True,
                        r=[0, 0, sg * 50]),
                      P("eclat5", [1, 5, 1], [0, -6, 0], [0, -2.5, 0], m=m, e=True,
                        r=[20, 0, sg * -10]),
                      P("eclat6", [1, 3, 1], [0, -3, 0], [0, -1.5, 0], m=m, e=True,
                        r=[45, 0, 0])])])
            for sg in (-1, 1)]


def cervide(cristal=False):
    """Le patron d'un cervide : corps, quatre pattes, cou coude, bois, queue.

    Le cou est incline de -25 degres et la tete de +25 : la tete revient droite,
    et c'est le coude du cou qui donne le port de tete. Sans rotation de piece
    les deux se confondraient en un seul tuyau.

    `cristal` echange les bois contre des eclats et seme cinq esquilles sur le
    dos : le cerf de cristal est le meme animal, et la maquette le dit en une
    ligne plutot qu'en un second patron.
    """
    y = -16
    parts = [
        P("corps", [8, 8, 14], [0, y, 0], None, m="poil",
          f=[["cotes", 0, 6, 99, 2, "ventre"]]),
        *pattes({"x": 2.5, "zf": 5, "zb": -5, "w": 2, "h": 12, "m": "poil",
                 "sabot": "sabot", "amp": 20}),
        P("cou", [4, 9, 4], [0, y - 2, 6], [0, -4.5, 0], m="poil", r=[-25, 0, 0],
          f=[["devant", 0, 3, 99, 99, "ventre"]],
          c=[P("tete", [5, 5, 6], [0, -9, 0], [0, -1, 2], m="poil", r=[25, 0, 0],
               a={"ax": "x", "amp": 5, "v": 0.4}, f=[["cotes", 2, 1, 1, 1, "oeil"]],
               c=[P("museau", [3, 3, 3], [0, 0.5, 5], [0, 0, 1.5], m="museau",
                    f=[["devant", 0, 0, 3, 1, "truffe"], ["dessus", 0, 2, 3, 1, "truffe"]]),
                  P("oreilleD", [3, 2, 1], [-2.5, -3, 1], [-1.5, 0, 0], m="poil",
                    r=[0, 0, -15], f=[["devant", 1, 0, 2, 1, "ventre"]]),
                  P("oreilleG", [3, 2, 1], [2.5, -3, 1], [1.5, 0, 0], m="poil",
                    r=[0, 0, 15], f=[["devant", 0, 0, 2, 1, "ventre"]])]
                 + (boisCristal("cristal") if cristal else boisCerf("bois")))]),
        P("queue", [2, 3, 1], [0, y - 3, -7.5], [0, 1.5, 0], m="blanc", r=[-20, 0, 0],
          a={"ax": "z", "amp": 10, "v": 1.2}),
    ]
    if cristal:
        for i, e in enumerate([[-2, -3, 0, -20], [1.5, 1, 15, 25], [-1, 4, -10, -10],
                               [2.5, -5, 20, 20], [0, -1, -25, 5]]):
            h = 3 + (i % 3)
            parts.append(P("eclatDos%d" % i, [2, h, 2], [e[0], y - 4, e[1]], [0, -h / 2.0, 0],
                           m="cristal", e=True, r=[e[2], 0, e[3]]))
    return parts


MANNEQUIN = {
    "id": "mannequin",
    "nom": "Mannequin d’entraînement",
    "monde": "entrainement",
    "hostile": False,
    "statut": "Inerte",
    "desc": "Encaisse les coups sans riposter ; affiche les dégâts infligés à chaque frappe.",
    "mat": {
        "bois": {"motif": "ecorce", "pal": ["#4a3220", "#654630", "#7e5a3e"],
                 "haut": ["#8a6a44", "#a88454", "#bc9a66"]},
        "toile": {"motif": "laine", "pal": ["#9a8458", "#b49c6e", "#c8b284"]},
        "paille": {"motif": "ecorce", "pal": ["#9a7c30", "#c0a042", "#dcbe62"]},
    },
    "c": {"corde": "#5a4028", "rouge": "#a8322a", "blanc": "#e8e0cc", "fil": "#2a1e14"},
    "parts": [
        P("socle", [12, 2, 12], [0, -1, 0], None, m="bois"),
        P("poteau", [3, 14, 3], [0, -2, 0], [0, -7, 0], m="bois",
          a={"ax": "z", "amp": 3, "v": 0.5},
          c=[
              P("corps", [9, 12, 5], [0, -19, 0], None, m="toile",
                f=[["tour", 0, 1, 99, 1, "corde"], ["tour", 0, 10, 99, 1, "corde"],
                   ["devant", 2, 3, 5, 5, "rouge"], ["devant", 3, 4, 3, 3, "blanc"],
                   ["devant", 4, 5, 1, 1, "rouge"]]),
              P("traverse", [20, 3, 3], [0, -22, 0], None, m="bois"),
              P("pailleD", [2, 4, 4], [-11, -22, 0], None, m="paille"),
              P("pailleG", [2, 4, 4], [11, -22, 0], None, m="paille"),
              P("tete", [7, 7, 7], [0, -28.5, 0], None, m="toile",
                a={"ax": "z", "amp": 4, "ph": 0.6, "v": 0.5},
                f=[["devant", 1, 2, 1, 2, "fil"], ["devant", 5, 2, 1, 2, "fil"],
                   ["devant", 1, 5, 1, 1, "fil"], ["devant", 3, 5, 1, 1, "fil"],
                   ["devant", 5, 5, 1, 1, "fil"], ["tour", 0, 6, 99, 1, "corde"]]),
              P("touffe", [3, 2, 3], [0, -33, 0], None, m="paille"),
          ]),
    ],
}

MOUFLON = {
    "id": "mouflon",
    "nom": "Mouflon laineux",
    "monde": "paisible",
    "hostile": False,
    "desc": "Vit sur les pentes rocheuses ; se tond pour sa laine au retour de l’été.",
    "mat": {
        "laine": {"motif": "laine", "pal": ["#b0a38a", "#d0c4aa", "#e8dfca"]},
        "face": {"motif": "poil", "pal": ["#3a2a1e", "#4e3a2a", "#64503c"]},
        "corne": {"motif": "corne", "pal": ["#7a6a4e", "#a09070", "#c4b690"]},
    },
    "c": {"oeil": "#c8a040", "truffe": "#1e1612", "museau": "#6a5646", "sabot": "#1e1814"},
    "parts": [
        P("toison", [10, 9, 13], [0, -11.5, 0], None, m="laine"),
        *pattes({"x": 3, "zf": 4, "zb": -4, "w": 2, "h": 7, "m": "face",
                 "sabot": "sabot", "amp": 18}),
        P("tete", [5, 6, 6], [0, -13, 6.5], [0, 0, 3], m="face",
          a={"ax": "x", "amp": 6, "v": 0.5},
          f=[oeilD(0, 2), oeilD(4, 2), ["devant", 1, 4, 3, 2, "museau"],
             oeilD(2, 4, 1, "truffe")],
          c=[P("meche", [6, 2, 4], [0, -3.5, 2], [0, -0.5, 0], m="laine")]
            + [piece for sg in (-1, 1) for piece in (
                P("corneA%d" % sg, [2, 3, 4], [sg * 3.5, -3, 2], None, m="corne"),
                P("corneB%d" % sg, [2, 4, 3], [sg * 3.5, -2, -1], None, m="corne"),
                P("corneC%d" % sg, [2, 3, 3], [sg * 3.5, 1.5, -0.5], None, m="corne"),
                P("corneD%d" % sg, [2, 2, 3], [sg * 3.8, 3, 2], None, m="corne"),
            )]),
        P("queue", [2, 2, 1], [0, -14, -7], None, m="laine"),
    ],
}

CERF = {
    "id": "cerf",
    "nom": "Cerf",
    "monde": "paisible",
    "hostile": False,
    "desc": "Fuit au moindre bruit ; on l’approche en restant accroupi.",
    "mat": {
        "poil": {"motif": "poil", "pal": ["#6a4428", "#865834", "#a47048"],
                 "bas": ["#c8b490", "#dccaa6", "#ece0c0"]},
        "museau": {"motif": "poil", "pal": ["#4a3020", "#5e3e2a", "#745036"]},
        "bois": {"motif": "corne", "pal": ["#a09070", "#c4b48e", "#ddd0ae"]},
        "blanc": {"motif": "uni", "pal": ["#dcd4c4", "#ece6d8", "#f8f4ec"]},
    },
    "c": {"ventre": "#d8c6a2", "oeil": "#141010", "truffe": "#1a1210", "sabot": "#2a2018"},
    "parts": cervide(),
}

MOUFLON_TONDU = {
    "id": "mouflonTondu",
    "nom": "Mouflon tondu",
    "monde": "paisible",
    "hostile": False,
    # Pas de totem : un mouflon tondu ne s'invoque pas, il sort d'une tonte. Le
    # jour ou la tonte existera, c'est elle qui le fabriquera a partir d'un
    # mouflon laineux — un objet d'apparition ferait un second chemin vers le
    # meme animal, et rendrait la tonte facultative avant qu'elle soit ecrite.
    "objet": False,
    "desc": "Sa laine repousse en quelques jours ; il reste plus frileux en attendant.",
    "mat": {
        "tondu": {"motif": "laine", "pal": ["#b4a48a", "#c8baa0", "#d8ccb4"]},
        "face": {"motif": "poil", "pal": ["#3a2a1e", "#4e3a2a", "#64503c"]},
        "corne": {"motif": "corne", "pal": ["#7a6a4e", "#a09070", "#c4b690"]},
    },
    "c": {"oeil": "#c8a040", "truffe": "#1e1612", "museau": "#6a5646", "sabot": "#1e1814"},
    "parts": [
        P("corps", [7, 6, 11], [0, -10, 0], None, m="tondu"),
        *pattes({"x": 2.5, "zf": 3.5, "zb": -3.5, "w": 2, "h": 7, "m": "face",
                 "sabot": "sabot", "amp": 18}),
        P("tete", [5, 6, 6], [0, -12, 5.5], [0, 0, 3], m="face",
          a={"ax": "x", "amp": 6, "v": 0.5},
          f=[oeilD(0, 2), oeilD(4, 2), ["devant", 1, 4, 3, 2, "museau"],
             oeilD(2, 4, 1, "truffe")],
          c=[piece for sg in (-1, 1) for piece in (
              P("corneA%d" % sg, [2, 3, 4], [sg * 3.5, -3, 2], None, m="corne"),
              P("corneB%d" % sg, [2, 4, 3], [sg * 3.5, -2, -1], None, m="corne"),
              P("corneC%d" % sg, [2, 3, 3], [sg * 3.5, 1.5, -0.5], None, m="corne"),
              P("corneD%d" % sg, [2, 2, 3], [sg * 3.8, 3, 2], None, m="corne"),
          )]),
        P("queue", [2, 2, 1], [0, -13, -6], None, m="tondu"),
    ],
}

LAPIN = {
    "id": "lapin",
    "nom": "Lapin",
    "monde": "paisible",
    "hostile": False,
    "saut": True,
    "desc": "Détale en zigzag et se nourrit de trèfle au bord des champs.",
    "mat": {
        "poil": {"motif": "poil", "pal": ["#6e5640", "#8a6e52", "#a68a6a"],
                 "bas": ["#c8bca8", "#dcd2c0", "#ece6d8"]},
        "blanc": {"motif": "uni", "pal": ["#d8d0c0", "#ece6d8", "#f8f4ec"]},
    },
    "c": {"oeil": "#1a1210", "nez": "#d89090", "rose": "#d8a0a0"},
    "parts": [
        P("corps", [5, 5, 7], [0, -4.5, -0.5], None, m="poil"),
        P("patteAVD", [1, 3, 1], [-1.5, -3, 2.5], [0, 1.5, 0], m="poil",
          a={"ax": "x", "amp": 10, "v": 1.4}),
        P("patteAVG", [1, 3, 1], [1.5, -3, 2.5], [0, 1.5, 0], m="poil",
          a={"ax": "x", "amp": 10, "v": 1.4}),
        P("cuisseD", [2, 3, 4], [-2, -3, -2], [0, 1.5, 0], m="poil"),
        P("cuisseG", [2, 3, 4], [2, -3, -2], [0, 1.5, 0], m="poil"),
        P("tete", [4, 4, 4], [0, -6, 3], [0, -1, 2], m="poil",
          f=[["cotes", 1, 1, 1, 1, "oeil"], ["devant", 1, 2, 2, 1, "nez"],
             ["devant", 1, 3, 2, 1, "oeil"]],
          c=[P("oreilleD", [1, 5, 2], [-1, -3, 0.5], [0, -2.5, 0], m="poil",
               r=[20, 0, -8], a={"ax": "x", "amp": 6, "v": 0.9},
               f=[["devant", 0, 1, 1, 3, "rose"]]),
             P("oreilleG", [1, 5, 2], [1, -3, 0.5], [0, -2.5, 0], m="poil",
               r=[20, 0, 8], a={"ax": "x", "amp": 6, "ph": 1, "v": 0.9},
               f=[["devant", 0, 1, 1, 3, "rose"]])]),
        P("queue", [2, 2, 2], [0, -5, -4.5], None, m="blanc"),
    ],
}

VACHE = {
    "id": "vache",
    "nom": "Vache",
    "monde": "paisible",
    "hostile": False,
    "desc": "Se trait avec un seau et suit quiconque tient une gerbe de blé.",
    "mat": {
        "taches": {"motif": "taches", "pal": ["#221e1c", "#e6e0d4", "#cfc7b8", "#34302c"]},
        "rose": {"motif": "peau", "pal": ["#c07a74", "#d8948c", "#e8aea6"]},
        "noir": {"motif": "poil", "pal": ["#1a1614", "#2a2624", "#3a3432"]},
        "corne": {"motif": "corne", "pal": ["#b0a68c", "#d0c8b0", "#e8e2d0"]},
    },
    "c": {"oeil": "#141010", "narine": "#6a3a34", "sabot": "#2a2420"},
    "parts": [
        P("corps", [12, 10, 18], [0, -16, 0], None, m="taches"),
        P("pis", [4, 2, 5], [0, -10, -4], None, m="rose"),
        *pattes({"x": 4, "zf": 6.5, "zb": -6.5, "w": 4, "h": 11, "m": "taches",
                 "sabot": "sabot", "amp": 14, "v": 0.7}),
        P("tete", [8, 8, 6], [0, -19, 9], [0, 0, 3], m="taches",
          a={"ax": "x", "amp": 4, "v": 0.4}, f=[oeilD(1, 3), oeilD(6, 3)],
          c=[P("mufle", [6, 4, 2], [0, 2, 6], [0, 0, 1], m="rose",
               f=[oeilD(1, 1, 1, "narine"), oeilD(4, 1, 1, "narine")]),
             P("corneD", [1, 3, 1], [-4, -3.5, 2], [0, -1.5, 0], m="corne", r=[0, 0, -30]),
             P("corneG", [1, 3, 1], [4, -3.5, 2], [0, -1.5, 0], m="corne", r=[0, 0, 30]),
             P("oreilleD", [2, 2, 1], [-4, -1.5, 2], [-1, 0, 0], m="noir"),
             P("oreilleG", [2, 2, 1], [4, -1.5, 2], [1, 0, 0], m="noir")]),
        P("queue", [1, 10, 1], [0, -20, -9], [0, 5, 0], m="noir", r=[-8, 0, 0],
          a={"ax": "z", "amp": 12, "v": 0.8},
          c=[P("touffe", [2, 3, 2], [0, 10, 0], [0, 1.5, 0], m="noir")]),
    ],
}

FAISAN = {
    "id": "faisan",
    "nom": "Faisan",
    "monde": "paisible",
    "hostile": False,
    "desc": "S’envole bruyamment des hautes herbes quand on s’en approche.",
    "mat": {
        "plume": {"motif": "plume", "pal": ["#5a2410", "#9a4a1c", "#c8742e"]},
        "tete": {"motif": "peau", "pal": ["#0e2a24", "#16443a", "#236656"]},
        "queue": {"motif": "uni", "pal": ["#8a6a3a", "#b08a50", "#c8a468"]},
        "aile": {"motif": "plume", "pal": ["#4a3a2a", "#7a6446", "#a08660"]},
        "patte": {"motif": "uni", "pal": ["#6a6458", "#8a8478", "#a49e92"]},
        "bec": {"motif": "uni", "pal": ["#b0a278", "#cfc298", "#e2d8b4"]},
    },
    "c": {"rouge": "#c42a24", "blanc": "#ece8e0", "noir": "#0e0c0a", "barre": "#2a1a0e"},
    "parts": [
        P("patteD", [1, 4, 1], [-1, -4, 0.5], [0, 2, 0], m="patte",
          a={"ax": "x", "amp": 18, "ph": 0, "v": 1.6}),
        P("patteG", [1, 4, 1], [1, -4, 0.5], [0, 2, 0], m="patte",
          a={"ax": "x", "amp": 18, "ph": PI, "v": 1.6}),
        P("corps", [5, 5, 7], [0, -6.5, 0], None, m="plume"),
        P("aileD", [1, 4, 6], [-3, -7, -0.5], None, m="aile"),
        P("aileG", [1, 4, 6], [3, -7, -0.5], None, m="aile"),
        P("cou", [3, 5, 3], [0, -8.5, 3], [0, -2.5, 0], m="tete", r=[-15, 0, 0],
          a={"ax": "x", "amp": 8, "v": 1.2}, f=[["tour", 0, 4, 99, 1, "blanc"]],
          c=[P("tete", [3, 3, 4], [0, -5, 0], [0, -1.5, 1], m="tete", r=[15, 0, 0],
               f=[["cotes", 1, 0, 2, 2, "rouge"], ["cotes", 2, 1, 1, 1, "noir"]],
               c=[P("bec", [1, 1, 2], [0, -1.5, 3], [0, 0, 1], m="bec")])]),
        P("queue", [2, 1, 13], [0, -8, -3.5], [0, 0, -6.5], m="queue", r=[-20, 0, 0],
          a={"ax": "y", "amp": 5, "v": 0.6},
          f=[["dessus", 0, yy, 2, 1, "barre"] for yy in (1, 3, 5, 7, 9, 11)]
            + [["dessous", 0, yy, 2, 1, "barre"] for yy in (1, 3, 5, 7, 9, 11)]),
    ],
}

LOUP = {
    "id": "loup",
    "nom": "Loup",
    "monde": "base",
    "hostile": True,
    "desc": "Chasse en meute à la lisière des forêts et attaque quand il se sent en nombre.",
    "mat": {
        "poil": {"motif": "poil", "pal": ["#55565b", "#76777c", "#9a9b9e"],
                 "bas": ["#a8a39a", "#c2bdb2", "#d6d1c6"]},
        "crin": {"motif": "poil", "pal": ["#66676b", "#8a8b8e", "#b0b1b2"],
                 "bas": ["#a8a39a", "#c2bdb2", "#d6d1c6"]},
        "museau": {"motif": "poil", "pal": ["#9a958c", "#b3aea4", "#cbc6bc"]},
    },
    "c": {"oeil": "#e0a63c", "truffe": "#1c1818", "sombre": "#3a3a3e",
          "bouche": "#2a2224", "clair": "#c8c4bc"},
    "parts": [
        P("corps", [6, 6, 9], [0, -11, -2.5], None, m="poil"),
        P("criniere", [8, 8, 7], [0, -12, 4], None, m="crin"),
        *pattes({"x": 2, "zf": 4.5, "zb": -5.5, "w": 2, "h": 8, "m": "poil", "amp": 22}),
        P("tete", [6, 6, 5], [0, -13, 7.5], [0, 0, 2.5], m="crin",
          a={"ax": "x", "amp": 3, "v": 0.5},
          f=[oeilD(1, 2), oeilD(4, 2), oeilD(1, 1, 1, "sombre"), oeilD(4, 1, 1, "sombre")],
          c=[P("museau", [3, 3, 4], [0, 1.5, 5], [0, 0, 2], m="museau",
               f=[["devant", 1, 0, 1, 1, "truffe"], ["dessus", 1, 3, 1, 1, "truffe"],
                  ["devant", 0, 2, 3, 1, "bouche"], ["cotes", 0, 2, 4, 1, "bouche"]]),
             P("oreilleD", [2, 2, 1], [-2, -3, 1], [0, -1, 0], m="crin",
               f=[["devant", 0, 1, 2, 1, "sombre"]]),
             P("oreilleG", [2, 2, 1], [2, -3, 1], [0, -1, 0], m="crin",
               f=[["devant", 0, 1, 2, 1, "sombre"]])]),
        P("queue", [2, 8, 2], [0, -13, -7], [0, 4, 0], m="crin", r=[-55, 0, 0],
          a={"ax": "z", "amp": 14, "v": 1.4}, f=[["tour", 0, 6, 99, 2, "clair"]]),
    ],
}

SANGLIER = {
    "id": "sanglier",
    "nom": "Sanglier",
    "monde": "base",
    "hostile": True,
    "desc": "Charge en ligne droite, tête baissée, sur tout ce qui bouge près de sa bauge.",
    "mat": {
        "poil": {"motif": "poil", "pal": ["#3a2a20", "#54402f", "#6f5540"],
                 "bas": ["#2e2119", "#3e2d22", "#4e3a2c"]},
        "crin": {"motif": "poil", "pal": ["#1e1510", "#2a1e16", "#3a2a1e"]},
        "groin": {"motif": "peau", "pal": ["#6a4a3a", "#8a6450", "#a07a64"]},
        "ivoire": {"motif": "uni", "pal": ["#cfc6ae", "#e8e0cc", "#f6f0e2"]},
    },
    "c": {"nez": "#9a6e5a", "narine": "#2a1a14", "oeil": "#140c08", "sabot": "#1e1612"},
    "parts": [
        P("corps", [10, 9, 14], [0, -9.5, -1], None, m="poil"),
        P("garrot", [11, 10, 6], [0, -10.5, 4], None, m="poil"),
        P("creteAvant", [2, 1, 6], [0, -16, 4], None, m="crin"),
        P("creteArriere", [2, 1, 8], [0, -14.5, -3], None, m="crin"),
        *pattes({"x": 3.5, "zf": 4, "zb": -6, "w": 3, "h": 5, "m": "poil",
                 "sabot": "sabot", "amp": 18}),
        P("tete", [7, 7, 5], [0, -10.5, 7], [0, 0, 2.5], m="poil", r=[-24, 0, 0],
          a={"ax": "x", "amp": 4, "v": 0.6},
          f=[["cotes", 1, 2, 1, 1, "oeil"], ["dessus", 2, 0, 3, 5, "crin"]],
          c=[P("chanfrein", [5, 5, 4], [0, 1, 5], [0, 0, 2], m="poil",
               f=[["dessus", 1, 0, 3, 2, "crin"]],
               c=[P("groin", [4, 4, 3], [0, 0.5, 4], [0, 0, 1.5], m="groin",
                    f=[["devant", 0, 0, 4, 4, "nez"], ["devant", 1, 1, 1, 2, "narine"],
                       ["devant", 2, 1, 1, 2, "narine"]],
                    c=[P("defenseD", [1, 3, 1], [-2.5, 1.5, 1], [0, -1.5, 0], m="ivoire",
                         r=[35, 0, -15]),
                       P("defenseG", [1, 3, 1], [2.5, 1.5, 1], [0, -1.5, 0], m="ivoire",
                         r=[35, 0, 15])])]),
             P("oreilleD", [2, 3, 1], [-2.5, -3.5, 0.5], [0, -1.5, 0], m="crin",
               r=[20, 0, -15]),
             P("oreilleG", [2, 3, 1], [2.5, -3.5, 0.5], [0, -1.5, 0], m="crin",
               r=[20, 0, 15])]),
        P("queue", [1, 5, 1], [0, -12, -8], [0, 2.5, 0], m="crin", r=[-20, 0, 0],
          a={"ax": "z", "amp": 18, "v": 1.6}),
    ],
}

OURS = {
    "id": "ours",
    "nom": "Ours",
    "monde": "base",
    "hostile": True,
    "desc": "Lent à s’énerver, redoutable une fois dressé sur ses pattes arrière.",
    "mat": {
        "poil": {"motif": "poil", "pal": ["#3b2616", "#553722", "#704a30"],
                 "bas": ["#2e1d11", "#3e2818", "#4e3420"]},
        "museau": {"motif": "poil", "pal": ["#7a5638", "#94704c", "#ac8a64"]},
    },
    "c": {"oeil": "#1a120c", "truffe": "#1a1210", "griffe": "#d8ccb0"},
    "parts": [
        P("corps", [14, 12, 20], [0, -15, 0], None, m="poil"),
        P("bosse", [12, 4, 8], [0, -22, 5], None, m="poil"),
        *pattes({"x": 4.5, "zf": 7, "zb": -7, "w": 5, "h": 9, "m": "poil", "amp": 14,
                 "v": 0.7, "f": [["devant", 0, 8, 99, 1, "griffe"]]}),
        P("tete", [9, 8, 7], [0, -17, 10], [0, 0, 3.5], m="poil",
          a={"ax": "x", "amp": 4, "v": 0.35}, f=[oeilD(2, 3), oeilD(6, 3)],
          c=[P("museau", [5, 4, 4], [0, 2, 7], [0, 0, 2], m="museau",
               f=[["devant", 1, 0, 3, 1, "truffe"], ["dessus", 1, 3, 3, 1, "truffe"]]),
             P("oreilleD", [2, 2, 1], [-3.5, -4, 2], [0, -1, 0], m="poil"),
             P("oreilleG", [2, 2, 1], [3.5, -4, 2], [0, -1, 0], m="poil")]),
        P("queue", [3, 3, 2], [0, -17, -10.5], None, m="poil"),
    ],
}

LEZARD = {
    "id": "lezard",
    "nom": "Lézard cavernicole",
    "monde": "profondeurs",
    "hostile": True,
    "desc": "Se repère aux vibrations du sol et luit faiblement dans l’obscurité.",
    "mat": {
        "ecaille": {"motif": "ecaille", "pal": ["#28332f", "#3e4e48", "#58706a"],
                    "bas": ["#6a6e60", "#8a8c7a", "#a4a690"]},
        "crete": {"motif": "uni", "pal": ["#1e2826", "#2c3a36", "#3e504a"]},
    },
    "c": {"oeil": "#9ee6d0", "bouche": "#141c1a", "lueur": "#7fd2e8", "griffe": "#c8c0a8"},
    "parts": [
        P("corps", [7, 4, 14], [0, -4, 0], None, m="ecaille",
          f=[["dessus", 1, 2, 1, 1, "lueur"], ["dessus", 5, 5, 1, 1, "lueur"],
             ["dessus", 2, 9, 1, 1, "lueur"], ["dessus", 4, 12, 1, 1, "lueur"],
             ["cotes", 3, 1, 1, 1, "lueur"], ["cotes", 9, 2, 1, 1, "lueur"]]),
        P("patteAVD", [2, 4, 2], [-3.5, -3, 4.5], [0, 2, 0], m="ecaille", r=[0, 0, 40],
          a={"ax": "x", "amp": 25, "ph": 0}, f=[["devant", 0, 3, 2, 1, "griffe"]]),
        P("patteAVG", [2, 4, 2], [3.5, -3, 4.5], [0, 2, 0], m="ecaille", r=[0, 0, -40],
          a={"ax": "x", "amp": 25, "ph": PI}, f=[["devant", 0, 3, 2, 1, "griffe"]]),
        P("patteARD", [2, 4, 2], [-3.5, -3, -4.5], [0, 2, 0], m="ecaille", r=[0, 0, 40],
          a={"ax": "x", "amp": 25, "ph": PI}, f=[["devant", 0, 3, 2, 1, "griffe"]]),
        P("patteARG", [2, 4, 2], [3.5, -3, -4.5], [0, 2, 0], m="ecaille", r=[0, 0, -40],
          a={"ax": "x", "amp": 25, "ph": 0}, f=[["devant", 0, 3, 2, 1, "griffe"]]),
        P("crete1", [1, 2, 2], [0, -6, 4], [0, -1, 0], m="crete"),
        P("crete2", [1, 2, 2], [0, -6, 0], [0, -1, 0], m="crete"),
        P("crete3", [1, 2, 2], [0, -6, -4], [0, -1, 0], m="crete"),
        P("tete", [6, 3, 6], [0, -4, 7], [0, 0, 3], m="ecaille",
          a={"ax": "y", "amp": 10, "v": 0.4},
          f=[["cotes", 1, 0, 2, 1, "oeil"], ["cotes", 0, 2, 6, 1, "bouche"],
             ["devant", 0, 2, 6, 1, "bouche"], ["devant", 1, 0, 1, 1, "bouche"],
             ["devant", 4, 0, 1, 1, "bouche"]]),
        P("queue1", [5, 3, 6], [0, -4.5, -7], [0, 0, -3], m="ecaille",
          a={"ax": "y", "amp": 12, "ph": 0, "v": 0.6}, f=[["dessus", 2, 2, 1, 1, "lueur"]],
          c=[P("queue2", [3, 2, 6], [0, 0.5, -6], [0, 0, -3], m="ecaille",
               a={"ax": "y", "amp": 16, "ph": -1, "v": 0.6},
               f=[["dessus", 1, 3, 1, 1, "lueur"]],
               c=[P("queue3", [2, 2, 6], [0, 0, -6], [0, 0, -3], m="ecaille",
                    a={"ax": "y", "amp": 20, "ph": -2, "v": 0.6})])]),
    ],
}


def _millepattes():
    """Les dix anneaux du mille-pattes, chacun enfant du précédent.

    Le segment est récursif dans la maquette et il le reste ici : c'est une
    chaîne d'os de dix maillons, et l'ondulation vient du déphasage régulier
    (`-i * 0,7`) d'un anneau au suivant, jamais d'une courbe calculée.
    """
    n = 10

    def segment(i):
        enfants = [P("patte%d" % sg, [1, 7, 1], [sg * 3, 0, -2], [0, 3.5, 0], m="patte",
                     r=[0, 0, -sg * 55],
                     a={"ax": "x", "amp": 28, "ph": i * 1.1 + (PI if sg > 0 else 0), "v": 1.6})
                   for sg in (-1, 1)]
        if i < n - 1:
            enfants.append(segment(i + 1))
        else:
            enfants.append(P("cerqueD", [1, 1, 5], [-2, 0, -4], [0, 0, -2.5], m="patte",
                             r=[0, -20, 0]))
            enfants.append(P("cerqueG", [1, 1, 5], [2, 0, -4], [0, 0, -2.5], m="patte",
                             r=[0, 20, 0]))
        return P("segment%d" % i, [6 - (1 if i > 7 else 0), 4, 4],
                 [0, 0, 0 if i == 0 else -4], [0, 0, -2], m="chitine",
                 a={"ax": "y", "amp": 6, "ph": -i * 0.7, "v": 0.8}, c=enfants)

    tete = segment(0)
    tete["p"] = [0, -4, 4]
    tete["c"].append(
        P("tete", [7, 5, 4], [0, 0, 0], [0, -0.5, 2], m="chitine", r=[15, 0, 0],
          f=[oeilD(1, 1), oeilD(5, 1), oeilD(2, 1, 1, "reflet"), oeilD(4, 1, 1, "reflet")],
          c=[P("mandibuleD", [1, 1, 4], [-2.5, 1.5, 4], [0, 0, 2], m="mandibule",
               r=[0, 25, 0], a={"ax": "y", "amp": 10, "v": 1.5}),
             P("mandibuleG", [1, 1, 4], [2.5, 1.5, 4], [0, 0, 2], m="mandibule",
               r=[0, -25, 0], a={"ax": "y", "amp": -10, "v": 1.5}),
             P("antenneD", [1, 1, 8], [-2, -2, 4], [0, 0, 4], m="patte", r=[35, -20, 0],
               a={"ax": "y", "amp": 8, "v": 0.9}),
             P("antenneG", [1, 1, 8], [2, -2, 4], [0, 0, 4], m="patte", r=[35, 20, 0],
               a={"ax": "y", "amp": 8, "ph": 1.5, "v": 0.9})]))
    return [tete]


MILLEPATTES = {
    "id": "millepattes",
    "nom": "Mille-pattes géant",
    "monde": "profondeurs",
    "hostile": True,
    "desc": "Court sur les parois des galeries et mord d’un venin qui paralyse.",
    "mat": {
        "chitine": {"motif": "chitine", "pal": ["#2a1712", "#4a2518", "#6e3a22"],
                    "bas": ["#8a5a32", "#a87040", "#c08850"]},
        "patte": {"motif": "uni", "pal": ["#8a4a20", "#b0662a", "#cc8440"]},
        "mandibule": {"motif": "uni", "pal": ["#1a0e0a", "#2a1812", "#3e2418"]},
    },
    "c": {"oeil": "#0e0a08", "reflet": "#c8a078"},
    "parts": _millepattes(),
}

RAMPANT = {
    "id": "rampant",
    "nom": "Rampant aveugle",
    "monde": "profondeurs",
    "hostile": True,
    "desc": "N’a pas d’yeux : il chasse au bruit et s’immobilise quand le silence revient.",
    "mat": {
        "peau": {"motif": "peau", "pal": ["#9a9284", "#b4ac9e", "#ccc5b7"],
                 "bas": ["#8a8274", "#a09888", "#b4ac9e"]},
    },
    "c": {"creux": "#8a8274", "gueule": "#2e1010", "dent": "#ece4d0", "os": "#ddd6c6",
          "veine": "#7a6a78", "cote": "#958c7e", "griffe": "#3a3230"},
    "parts": [
        P("torse", [8, 7, 11], [0, -13, 0], None, m="peau", r=[15, 0, 0],
          f=[["dessus", 3, 1, 2, 1, "os"], ["dessus", 3, 4, 2, 1, "os"],
             ["dessus", 3, 7, 2, 1, "os"], ["dessus", 3, 10, 2, 1, "os"],
             ["cotes", 2, 2, 7, 1, "cote"], ["cotes", 2, 4, 7, 1, "cote"],
             ["cotes", 3, 1, 1, 4, "veine"]]),
        P("bassin", [7, 6, 6], [0, -11, -6], None, m="peau",
          f=[["dessus", 3, 1, 1, 1, "os"], ["dessus", 3, 4, 1, 1, "os"]]),
        P("tete", [7, 6, 7], [0, -15, 6], [0, 0, 3.5], m="peau", r=[-10, 0, 0],
          a={"ax": "y", "amp": 14, "v": 0.3},
          f=[["devant", 1, 1, 2, 1, "creux"], ["devant", 4, 1, 2, 1, "creux"],
             ["devant", 1, 3, 5, 3, "gueule"], ["devant", 1, 3, 1, 1, "dent"],
             ["devant", 3, 3, 1, 1, "dent"], ["devant", 5, 3, 1, 1, "dent"],
             ["devant", 2, 5, 1, 1, "dent"], ["devant", 4, 5, 1, 1, "dent"],
             ["cotes", 0, 4, 3, 1, "gueule"]]),
        P("brasD", [2, 15, 2], [-4.5, -15, 4], [0, 7.5, 0], m="peau", r=[15, 0, 8],
          a={"ax": "x", "amp": 16, "ph": 0, "v": 0.7},
          c=[P("mainD", [3, 1, 4], [0, 15, 0], [0, 0, 1], m="peau",
               f=[["devant", 0, 0, 3, 1, "griffe"], ["dessus", 0, 3, 3, 1, "griffe"]])]),
        P("brasG", [2, 15, 2], [4.5, -15, 4], [0, 7.5, 0], m="peau", r=[15, 0, -8],
          a={"ax": "x", "amp": 16, "ph": PI, "v": 0.7},
          c=[P("mainG", [3, 1, 4], [0, 15, 0], [0, 0, 1], m="peau",
               f=[["devant", 0, 0, 3, 1, "griffe"], ["dessus", 0, 3, 3, 1, "griffe"]])]),
        P("cuisseD", [3, 7, 3], [-3.5, -11.5, -7], [0, 3.5, 0], m="peau", r=[-40, 0, 0],
          a={"ax": "x", "amp": 10, "ph": PI, "v": 0.7},
          c=[P("tibiaD", [2, 7, 2], [0, 7, 0], [0, 3.5, 0], m="peau", r=[70, 0, 0],
               f=[["devant", 0, 6, 2, 1, "griffe"]])]),
        P("cuisseG", [3, 7, 3], [3.5, -11.5, -7], [0, 3.5, 0], m="peau", r=[-40, 0, 0],
          a={"ax": "x", "amp": 10, "ph": 0, "v": 0.7},
          c=[P("tibiaG", [2, 7, 2], [0, 7, 0], [0, 3.5, 0], m="peau", r=[70, 0, 0],
               f=[["devant", 0, 6, 2, 1, "griffe"]])]),
    ],
}

CHIEN = {
    "id": "chien",
    "nom": "Chien des enfers",
    "monde": "demoniaque",
    "hostile": True,
    "desc": "Son souffle enflamme l’herbe sèche ; il chasse toujours par deux.",
    "mat": {
        "lave": {"motif": "lave",
                 "pal": ["#141012", "#211918", "#302220", "#f08a2a", "#8a2a14"]},
        "corne": {"motif": "corne", "pal": ["#1a1414", "#2a2222", "#3e3232"]},
        "flamme": {"motif": "feu", "anim": True,
                   "pal": ["#b8402a", "#e8862a", "#f7c850", "#fcecb0"]},
    },
    "c": {"braise": "#f5a03a", "dent": "#e8dcc0", "noir": "#0a0808"},
    "parts": [
        P("corps", [8, 8, 11], [0, -14, -3], None, m="lave"),
        P("poitrail", [10, 10, 8], [0, -15, 4.5], None, m="lave"),
        *pattes({"x": 2.5, "zf": 5, "zb": -6.5, "w": 3, "h": 10, "m": "lave", "amp": 22,
                 "sabot": "noir", "sh": 1}),
        P("pique1", [1, 3, 2], [0, -20, 6], [0, -1.5, 0], m="corne", r=[35, 0, 0]),
        P("pique2", [1, 3, 2], [0, -20, 2.5], [0, -1.5, 0], m="corne", r=[35, 0, 0]),
        P("pique3", [1, 3, 2], [0, -18, -1.5], [0, -1.5, 0], m="corne", r=[35, 0, 0]),
        P("pique4", [1, 3, 2], [0, -18, -5.5], [0, -1.5, 0], m="corne", r=[35, 0, 0]),
        P("tete", [8, 7, 6], [0, -17, 8.5], [0, 0, 3], m="lave",
          a={"ax": "x", "amp": 4, "v": 0.6},
          f=[oeilD(1, 2, 2, "braise"), oeilD(5, 2, 2, "braise"), oeilD(1, 1, 2, "noir"),
             oeilD(5, 1, 2, "noir")],
          c=[P("museau", [4, 4, 5], [0, 2, 6], [0, 0, 2.5], m="lave",
               f=[["devant", 1, 0, 2, 1, "noir"], ["devant", 0, 2, 4, 1, "braise"],
                  ["cotes", 0, 2, 5, 1, "braise"], ["cotes", 1, 1, 1, 1, "dent"],
                  ["cotes", 3, 1, 1, 1, "dent"], ["cotes", 2, 3, 1, 1, "dent"]]),
             P("corneD", [1, 4, 1], [-3, -3, 1], [0, -2, 0], m="corne", r=[35, 0, -15],
               c=[P("pointeD", [1, 3, 1], [0, -4, 0], [0, -1.5, 0], m="corne",
                    r=[35, 0, 0])]),
             P("corneG", [1, 4, 1], [3, -3, 1], [0, -2, 0], m="corne", r=[35, 0, 15],
               c=[P("pointeG", [1, 3, 1], [0, -4, 0], [0, -1.5, 0], m="corne",
                    r=[35, 0, 0])])]),
        P("queue", [2, 9, 2], [0, -16, -8.5], [0, 4.5, 0], m="lave", r=[-60, 0, 0],
          a={"ax": "z", "amp": 16, "v": 1.2},
          c=[flamme("flammeQueue", 3, [0, 9, 0], 0, [120, 0, 0])]),
    ],
}

BRUTE = {
    "id": "brute",
    "nom": "Brute cornue",
    "monde": "demoniaque",
    "hostile": True,
    "desc": "Charge cornes en avant et renverse les boucliers levés.",
    "mat": {
        "peau": {"motif": "peau", "pal": ["#5e1a16", "#7e2620", "#9a3428"],
                 "bas": ["#4a1612", "#5a1c16", "#6e241c"]},
        "pagne": {"motif": "poil", "pal": ["#2a1e16", "#3a2a1e", "#4a3626"]},
        "corne": {"motif": "corne", "pal": ["#5a4a3a", "#8a7a60", "#b0a080"]},
        "fer": {"motif": "uni", "pal": ["#3a3c40", "#5a5c62", "#7a7c82"]},
    },
    "c": {"braise": "#f5c040", "sombre": "#3e0e0c", "gueule": "#1e0808", "dent": "#e8dcc0",
          "rivet": "#a4a6ac", "ceinture": "#1a120c", "boucle": "#b08a3a", "sabot": "#1e1614"},
    "parts": [
        P("jambeD", [6, 14, 6], [-4.5, -14, 0], [0, 7, 0], m="peau",
          a={"ax": "x", "amp": 10, "ph": 0, "v": 0.7},
          f=[["tour", 0, 11, 99, 3, "sabot"], ["dessous", 0, 0, 99, 99, "sabot"]]),
        P("jambeG", [6, 14, 6], [4.5, -14, 0], [0, 7, 0], m="peau",
          a={"ax": "x", "amp": 10, "ph": PI, "v": 0.7},
          f=[["tour", 0, 11, 99, 3, "sabot"], ["dessous", 0, 0, 99, 99, "sabot"]]),
        P("bassin", [14, 6, 8], [0, -17, 0], None, m="pagne",
          f=[["tour", 0, 0, 99, 1, "ceinture"], ["devant", 6, 0, 2, 1, "boucle"]]),
        P("torse", [18, 16, 10], [0, -28, 1], None, m="peau", r=[-12, 0, 0],
          f=[["devant", 2, 4, 6, 1, "sombre"], ["devant", 10, 4, 6, 1, "sombre"],
             ["devant", 8, 5, 2, 9, "sombre"], ["devant", 5, 9, 8, 1, "sombre"],
             ["devant", 5, 12, 8, 1, "sombre"]]),
        P("tete", [9, 9, 9], [0, -36, 4], [0, -3, 3], m="peau",
          a={"ax": "y", "amp": 6, "v": 0.3},
          f=[["devant", 0, 3, 9, 1, "sombre"], oeilD(1, 4, 2, "braise"),
             oeilD(6, 4, 2, "braise"), ["devant", 2, 7, 5, 1, "gueule"],
             oeilD(2, 6, 1, "dent"), oeilD(6, 6, 1, "dent")],
          c=[P("corne1%d" % sg, [4, 3, 3], [sg * 4.5, -6, 1], [sg * 2, 0, 0], m="corne",
               c=[P("corne2%d" % sg, [3, 5, 3], [sg * 4, 0, 0], [0, -2.5, 0], m="corne",
                    r=[0, 0, sg * 20],
                    c=[P("corne3%d" % sg, [2, 4, 2], [0, -5, 0], [0, -2, 0], m="corne",
                         r=[-50, 0, 0])])])
             for sg in (-1, 1)]),
        P("brasD", [6, 16, 6], [-12, -33, 2], [0, 8, 0], m="peau", r=[10, 0, 8],
          a={"ax": "x", "amp": 10, "ph": PI, "v": 0.7},
          f=[["tour", 0, 12, 99, 2, "fer"]],
          c=[P("poingD", [8, 7, 8], [0, 16, 0], [0, 3.5, 0], m="peau")]),
        P("brasG", [6, 16, 6], [12, -33, 2], [0, 8, 0], m="peau", r=[10, 0, -8],
          a={"ax": "x", "amp": 10, "ph": 0, "v": 0.7},
          c=[P("epauliere", [8, 4, 8], [0, 1, 0], [0, -1, 0], m="fer",
               f=[["dessus", 1, 1, 1, 1, "rivet"], ["dessus", 6, 1, 1, 1, "rivet"],
                  ["dessus", 1, 6, 1, 1, "rivet"], ["dessus", 6, 6, 1, 1, "rivet"]]),
             P("poingG", [8, 7, 8], [0, 16, 0], [0, 3.5, 0], m="peau")]),
        P("queue", [2, 12, 2], [0, -18, -4], [0, 6, 0], m="peau", r=[-45, 0, 0],
          a={"ax": "z", "amp": 14, "v": 0.9},
          c=[P("pique", [4, 4, 1], [0, 12, 0], [0, 1.5, 0], m="peau",
               f=[["deux", 0, 3, 1, 1, "vide"], ["deux", 3, 3, 1, 1, "vide"]])]),
    ],
}


def _champion():
    """Le champion démon : deux jambes, deux ailes membranées, une lame, un fouet.

    Le fouet est une chaîne de six anneaux de flamme, chacun enfant du
    précédent et déphasé d'un neuvième de tour : c'est le même procédé que les
    anneaux du mille-pattes, et la seule façon qu'une pièce rigide a d'onduler.
    """
    parts = []
    for sg in (-1, 1):
        parts.append(
            P("cuisse%d" % sg, [8, 13, 8], [sg * 6, -29, 0], [0, 6.5, 0], m="lave",
              r=[15, 0, 0],
              a={"ax": "x", "amp": 8, "ph": PI if sg > 0 else 0, "v": 0.5},
              c=[P("tibia%d" % sg, [6, 14, 6], [0, 13, 0], [0, 7, 0], m="lave",
                   r=[-30, 0, 0],
                   c=[P("sabot%d" % sg, [7, 3, 8], [0, 14, 0], [0, 1.5, 1], m="corne",
                        r=[15, 0, 0])])]))
        parts.append(
            P("aile%d" % sg, [26, 3, 3], [sg * 6, -52, -5.5], [sg * 13, 0, 0], m="corne",
              r=[0, sg * 20, -sg * 25], a={"ax": "z", "amp": 9, "ph": 0, "v": 0.45},
              c=[P("membrane%d" % sg, [24, 24, 1], [sg * 1, 1, 0], [sg * 12, 12, 0],
                   m="membrane"),
                 P("griffeAile%d" % sg, [2, 4, 2], [sg * 26, 0, 0], [0, -2, 0], m="corne",
                   r=[0, 0, sg * 30])]))
        parts.append(flamme("flammeEpaule%d" % sg, 4, [sg * 9, -56, -2], sg * 1.3))

    def fouet(i):
        return P("fouet%d" % i, [1, 7, 1], [0, 5 if i == 0 else 7, 0], [0, 3.5, 0],
                 m="flamme2", e=True, r=[0 if i == 0 else 10, 0, 0],
                 a={"ax": "x", "amp": 12, "ph": -i * 0.9, "v": 0.8},
                 c=[fouet(i + 1)] if i < 5 else [])

    parts += [
        P("bassin", [16, 8, 10], [0, -31, 0], None, m="lave"),
        P("torse", [22, 22, 12], [0, -45, 1], None, m="lave", r=[-8, 0, 0],
          f=[["devant", 9, 5, 4, 4, "coeur"], ["devant", 10, 6, 2, 2, "braise"]]),
        P("tete", [11, 11, 10], [0, -56, 4], [0, -4.5, 2], m="lave",
          a={"ax": "y", "amp": 8, "v": 0.25},
          f=[["devant", 1, 3, 9, 1, "noir"], oeilD(2, 4, 3, "braise"),
             oeilD(6, 4, 3, "braise"), ["devant", 2, 7, 7, 2, "braise"],
             oeilD(2, 7, 1, "dent"), oeilD(4, 7, 1, "dent"), oeilD(6, 7, 1, "dent"),
             oeilD(8, 7, 1, "dent")],
          c=[P("corne%d" % sg, [3, 3, 9], [sg * 4.5, -8, 2], [0, 0, 4.5], m="corne",
               r=[30, sg * 25, 0],
               c=[P("pointe%d" % sg, [2, 2, 7], [0, 0, 9], [0, 0, 3.5], m="corne",
                    r=[35, 0, 0])])
             for sg in (-1, 1)]
            + [flamme("criniere%d" % i, 3, [q[0], -10, q[1]], i * 1.3)
               for i, q in enumerate([[-3, 0], [0, -1.5], [3, 0], [-1.5, 2.5], [1.5, -3.5]])]),
        P("brasD", [7, 22, 7], [-14.5, -53, 1], [0, 11, 0], m="lave", r=[20, 0, 10],
          a={"ax": "x", "amp": 7, "ph": 0, "v": 0.5},
          c=[P("mainD", [8, 6, 8], [0, 22, 0], [0, 3, 0], m="lave",
               c=[P("garde", [8, 2, 2], [0, 3, 4], None, m="fer"),
                  P("lame", [2, 5, 26], [0, 3, 5], [0, 0, 13], m="flamme2", e=True)])]),
        P("brasG", [7, 22, 7], [14.5, -53, 1], [0, 11, 0], m="lave", r=[10, 0, -10],
          a={"ax": "x", "amp": 7, "ph": PI, "v": 0.5},
          c=[P("mainG", [8, 6, 8], [0, 22, 0], [0, 3, 0], m="lave", c=[fouet(0)])]),
        P("queue", [3, 16, 3], [0, -32, -5], [0, 8, 0], m="lave", r=[-45, 0, 0],
          a={"ax": "z", "amp": 10, "v": 0.6},
          c=[flamme("flammeQueue", 4, [0, 16, 0], 0.8, [135, 0, 0])]),
    ]
    return parts


CHAMPION = {
    "id": "champion",
    "nom": "Champion démon",
    "monde": "demoniaque",
    "hostile": True,
    "desc": "Garde les portes du monde démoniaque, armé d’une lame et d’un fouet de flammes.",
    "mat": {
        "lave": {"motif": "lave",
                 "pal": ["#161012", "#241a1a", "#342524", "#f5a03a", "#9a3214"]},
        "corne": {"motif": "corne", "pal": ["#1a1414", "#2e2626", "#443838"]},
        "membrane": {"motif": "membrane", "pal": ["#1a1214", "#2a1c1c", "#3a2624"]},
        "flamme": {"motif": "feu", "anim": True,
                   "pal": ["#b8402a", "#e8862a", "#f7c850", "#fcecb0"]},
        "flamme2": {"motif": "flamme", "pal": ["#e0702a", "#f0a038", "#f8d460"]},
        "flamme3": {"motif": "uni", "pal": ["#f5c040", "#f8d868", "#fcecb0"]},
        "fer": {"motif": "uni", "pal": ["#2a2a2e", "#3e3e44", "#56565e"]},
    },
    "c": {"braise": "#f7b03a", "dent": "#e8dcc0", "noir": "#0a0808", "coeur": "#e8662a"},
    "parts": _champion(),
}

TREANT = {
    "id": "treant",
    "nom": "Tréant gardien",
    "monde": "feerique",
    "hostile": True,
    "desc": "Reste immobile tant qu’on n’abat aucun arbre de son bosquet.",
    "mat": {
        "ecorce": {"motif": "ecorce", "pal": ["#3a2a1c", "#553d28", "#6e5236"],
                   "haut": ["#6a5034", "#8a6a44", "#a88654"]},
        "feuilles": {"motif": "feuille", "pal": ["#2f5a24", "#44782e", "#5f9a3a"]},
        "mousse": {"motif": "feuille", "pal": ["#4a6a2a", "#6a8a3a", "#8aaa4a"]},
        "champi": {"motif": "uni", "pal": ["#8a2a20", "#b03a2a", "#c85040"]},
    },
    "c": {"creux": "#1e140c", "lueur": "#f0d67a", "mousse": "#6a8a3a", "fleur": "#e8c0d8",
          "fleur2": "#f0e080", "point": "#f0e6d0"},
    "parts": [
        P("racineD", [6, 14, 6], [-4.5, -14, 0], [0, 7, 0], m="ecorce",
          a={"ax": "x", "amp": 6, "ph": 0, "v": 0.35},
          c=[P("orteilD", [7, 2, 4], [0, 13, 3], [0, 0, 1.5], m="ecorce"),
             P("orteilDx", [3, 2, 5], [-3, 13, -1], [-1.5, 0, 0], m="ecorce")]),
        P("racineG", [6, 14, 6], [4.5, -14, 0], [0, 7, 0], m="ecorce",
          a={"ax": "x", "amp": 6, "ph": PI, "v": 0.35},
          c=[P("orteilG", [7, 2, 4], [0, 13, 3], [0, 0, 1.5], m="ecorce"),
             P("orteilGx", [3, 2, 5], [3, 13, -1], [1.5, 0, 0], m="ecorce")]),
        P("tronc", [14, 24, 10], [0, -26, 0], None, m="ecorce",
          f=[["devant", 2, 3, 4, 3, "creux"], ["devant", 8, 3, 4, 3, "creux"],
             ["devant", 3, 4, 2, 1, "lueur"], ["devant", 9, 4, 2, 1, "lueur"],
             ["devant", 4, 10, 6, 2, "creux"], ["dessus", 0, 0, 4, 3, "mousse"],
             ["dessus", 10, 6, 4, 4, "mousse"], ["cotes", 2, 14, 3, 4, "mousse"]]),
        P("barbe", [10, 8, 1], [0, -22, 5.5], None, m="mousse",
          f=[["deux", 0, 5, 2, 3, "vide"], ["deux", 3, 7, 1, 1, "vide"],
             ["deux", 5, 6, 1, 2, "vide"], ["deux", 8, 4, 2, 4, "vide"],
             ["deux", 2, 2, 1, 1, "fleur"]]),
        P("champignon", [3, 1, 3], [6, -38.5, 3], None, m="champi",
          f=[["dessus", 1, 1, 1, 1, "point"]]),
        P("brancheD", [3, 8, 3], [-4, -38, 0], [0, -4, 0], m="ecorce", r=[0, 0, -25]),
        P("brancheG", [3, 8, 3], [4, -38, 0], [0, -4, 0], m="ecorce", r=[0, 0, 25]),
        P("feuillage1", [16, 9, 12], [0, -42, 0], None, m="feuilles",
          f=[["dessus", 3, 4, 1, 1, "fleur"], ["dessus", 11, 2, 1, 1, "fleur2"],
             ["devant", 5, 6, 1, 1, "fleur"], ["devant", 12, 3, 1, 1, "fleur2"]]),
        P("feuillage2", [9, 7, 9], [-8, -40, 3], None, m="feuilles",
          f=[["devant", 2, 2, 1, 1, "fleur2"]]),
        P("feuillage3", [9, 8, 9], [8, -41, -2], None, m="feuilles",
          f=[["dessus", 4, 4, 1, 1, "fleur"]]),
        P("feuillage4", [9, 6, 8], [1, -49, -1], None, m="feuilles",
          f=[["dessus", 2, 3, 1, 1, "fleur2"]]),
        P("brasD", [5, 22, 5], [-9.5, -36, 0], [0, 11, 0], m="ecorce", r=[8, 0, 12],
          a={"ax": "x", "amp": 6, "ph": PI, "v": 0.35},
          c=[P("rameauD", [2, 7, 2], [-1, 9, 0], [0, -3.5, 0], m="ecorce", r=[0, 0, -45],
               c=[P("toufeD", [5, 4, 5], [0, -7, 0], [0, -2, 0], m="feuilles")]),
             P("doigtD1", [1, 5, 1], [-1.5, 22, 0], [0, 2.5, 0], m="ecorce", r=[0, 0, 20]),
             P("doigtD2", [1, 5, 1], [0, 22, 1], [0, 2.5, 0], m="ecorce", r=[15, 0, 0]),
             P("doigtD3", [1, 5, 1], [1.5, 22, 0], [0, 2.5, 0], m="ecorce",
               r=[0, 0, -20])]),
        P("brasG", [5, 22, 5], [9.5, -36, 0], [0, 11, 0], m="ecorce", r=[8, 0, -12],
          a={"ax": "x", "amp": 6, "ph": 0, "v": 0.35},
          c=[P("rameauG", [2, 7, 2], [1, 7, 0], [0, -3.5, 0], m="ecorce", r=[0, 0, 45],
               c=[P("toufeG", [5, 4, 5], [0, -7, 0], [0, -2, 0], m="feuilles")]),
             P("doigtG1", [1, 5, 1], [-1.5, 22, 0], [0, 2.5, 0], m="ecorce", r=[0, 0, 20]),
             P("doigtG2", [1, 5, 1], [0, 22, 1], [0, 2.5, 0], m="ecorce", r=[15, 0, 0]),
             P("doigtG3", [1, 5, 1], [1.5, 22, 0], [0, 2.5, 0], m="ecorce",
               r=[0, 0, -20])]),
    ],
}

GORGONE = {
    "id": "gorgone",
    "nom": "Gorgone florale",
    "monde": "feerique",
    "hostile": True,
    "desc": "Son regard change la chair en bois ; son pollen endort ceux qui approchent.",
    "mat": {
        "peau": {"motif": "peau", "pal": ["#4a7044", "#628a58", "#7ea470"]},
        "liane": {"motif": "ecaille", "pal": ["#1e3a1c", "#2c5226", "#3e6a32"],
                  "bas": ["#5a7a3a", "#6e8e48", "#84a458"]},
        "petale": {"motif": "uni", "pal": ["#8a2456", "#b03a70", "#d05a8e"]},
        "bouton": {"motif": "uni", "pal": ["#5a1e4a", "#7a2e64", "#9a4a82"]},
    },
    "c": {"oeil": "#f0e060", "bouche": "#2a1020", "feuille": "#2c5226", "liane": "#1e3a1c",
          "pistil": "#f0d060"},
    "parts": [
        P("souche", [10, 8, 10], [0, -4, 0], None, m="liane"),
        P("queue1", [7, 5, 8], [-3, -2.5, -4], [0, 0, -4], m="liane", r=[0, -50, 0],
          a={"ax": "y", "amp": 6, "v": 0.5},
          c=[P("queue2", [5, 4, 8], [0, 0.5, -8], [0, 0, -4], m="liane", r=[0, -60, 0],
               a={"ax": "y", "amp": 8, "ph": -1, "v": 0.5},
               c=[P("queue3", [3, 3, 7], [0, 0.5, -8], [0, 0, -3.5], m="liane",
                    r=[0, -60, 0], a={"ax": "y", "amp": 12, "ph": -2, "v": 0.5})])]),
        P("torse", [8, 12, 5], [0, -14, 1], None, m="peau",
          f=[["devant", 2, 1, 1, 3, "feuille"], ["devant", 5, 1, 1, 3, "feuille"],
             ["tour", 0, 7, 99, 2, "feuille"]]),
        P("brasD", [3, 12, 3], [-5.5, -19.5, 1], [0, 6, 0], m="peau", r=[25, 0, 10],
          a={"ax": "x", "amp": 8, "ph": 0, "v": 0.5}, f=[["tour", 0, 8, 99, 1, "liane"]]),
        P("brasG", [3, 12, 3], [5.5, -19.5, 1], [0, 6, 0], m="peau", r=[25, 0, -10],
          a={"ax": "x", "amp": 8, "ph": PI, "v": 0.5}, f=[["tour", 0, 8, 99, 1, "liane"]]),
        P("tete", [7, 7, 7], [0, -20, 1], [0, -3.5, 0], m="peau",
          a={"ax": "y", "amp": 7, "v": 0.35},
          f=[oeilD(1, 3, 2), oeilD(4, 3, 2), oeilD(3, 5, 1, "bouche")],
          c=[P("petale%d" % i, [4, 8, 1], [0, -3.5, -4], [0, -8, 0], m="petale",
               r=[0, 0, ang],
               f=[["deux", 1, 6, 2, 2, "pistil"], ["deux", 0, 0, 1, 1, "vide"],
                  ["deux", 3, 0, 1, 1, "vide"]])
             for i, ang in enumerate([0, 60, 120, 180, 240, 300])]
            + [P("liane%d" % i, [2, 5, 2], [v[0], -7, v[1]], [0, -2.5, 0], m="liane",
                 r=[v[3], 0, v[2]],
                 a={"ax": "z", "amp": 12, "ph": i * 1.3, "v": 0.7},
                 c=[P("lianeB%d" % i, [2, 5, 2], [0, -5, 0], [0, -2.5, 0], m="liane",
                      r=[-35, 0, 0], a={"ax": "x", "amp": 10, "ph": i, "v": 0.7},
                      c=[P("bouton%d" % i, [3, 3, 4], [0, -5, 0], [0, -1.5, 1],
                           m="bouton", r=[-30, 0, 0],
                           f=[oeilD(0, 1), oeilD(2, 1), oeilD(1, 2, 1, "bouche")])])])
               for i, v in enumerate([[-2.5, -1, -25, 0], [2.5, -1, 25, 0],
                                      [-1, -2.5, -8, 20], [1, -2.5, 8, 20]])]),
    ],
}

CERF_CRISTAL = {
    "id": "cerfCristal",
    "nom": "Cerf de cristal",
    "monde": "feerique",
    "hostile": True,
    "desc": "Ses bois diffractent la lumière et révèlent les passages cachés.",
    "mat": {
        "poil": {"motif": "poil", "pal": ["#8a92b4", "#aab2d2", "#cad0ea"],
                 "bas": ["#c4cae4", "#d8dcf0", "#eaecf8"]},
        "museau": {"motif": "poil", "pal": ["#6a7296", "#848cb0", "#9ea6c8"]},
        "cristal": {"motif": "cristal", "pal": ["#3f8fbc", "#6cc4e2", "#c4eefa"]},
        "blanc": {"motif": "uni", "pal": ["#d8dcf0", "#eceef8", "#f8f8fc"]},
    },
    "c": {"ventre": "#dfe4f4", "oeil": "#7fe8ff", "truffe": "#4a5070", "sabot": "#6cc4e2"},
    "parts": cervide(cristal=True),
}

CREATURES = [MANNEQUIN, CERF, MOUFLON, MOUFLON_TONDU, LAPIN, VACHE, FAISAN,
             LOUP, SANGLIER, OURS, LEZARD, MILLEPATTES, RAMPANT, CHIEN, BRUTE,
             CHAMPION, TREANT, GORGONE, CERF_CRISTAL]
PAR_ID = {c["id"]: c for c in CREATURES}


# --- totems d'apparition --------------------------------------------------
#
# La maquette ne modele pas l'objet d'une creature : elle le DERIVE. Socle et
# pilier a la matiere de son monde, surmontes de la propre tete de la bete —
# d'ou un objet reconnaissable sans une ligne de modelage par creature. Le
# monde d'entrainement est l'exception qu'elle pose explicitement : la, l'objet
# EST la creature, et c'est ce qui fait que le mannequin se ramasse a la main.

APPARITION = {
    "loup": "un loup", "sanglier": "un sanglier", "ours": "un ours",
    "lezard": "un lézard cavernicole", "millepattes": "un mille-pattes géant",
    "rampant": "un rampant aveugle", "chien": "un chien des enfers",
    "brute": "une brute cornue", "champion": "un champion démon",
    "treant": "un tréant gardien", "gorgone": "une gorgone florale",
    "cerfCristal": "un cerf de cristal", "cerf": "un cerf",
    "mouflon": "un mouflon laineux", "mouflonTondu": "un mouflon tondu",
    "lapin": "un lapin", "vache": "une vache", "faisan": "un faisan",
}

TOTEM_MAT = {
    "base": [{"motif": "ecorce", "pal": ["#4a3220", "#654630", "#7e5a3e"],
              "haut": ["#8a6a44", "#a88454", "#bc9a66"]}, "#e0a63c"],
    "profondeurs": [{"motif": "laine", "pal": ["#3a3a3e", "#4e4e54", "#66666c"]}, "#7fd2e8"],
    "demoniaque": [{"motif": "lave",
                    "pal": ["#141012", "#1e1820", "#2a2230", "#f08a2a", "#8a2a14"]}, "#f5a03a"],
    "feerique": [{"motif": "cristal", "pal": ["#8a92b4", "#aab2d2", "#cad0ea"]}, "#d05a8e"],
    "paisible": [{"motif": "ecorce", "pal": ["#7a5a3a", "#94704a", "#ae885a"],
                  "haut": ["#a8845a", "#c09c6c", "#d4b280"]}, "#7fb04a"],
}


def cloner(p):
    """Copie une piece sans son animation : un totem ne respire pas."""
    q = dict(p)
    q.pop("a", None)
    if p.get("c"):
        q["c"] = [cloner(e) for e in p["c"]]
    return q


def trouver(parts, n):
    for p in parts:
        if p["n"] == n:
            return p
        if p.get("c"):
            r = trouver(p["c"], n)
            if r:
                return r
    return None


def boite(parts):
    """Boite englobante d'un arbre de pieces, en texels. Rend `(min, max)`.

    Une piece qui tourne n'est plus alignee sur les axes : ce sont ses HUIT
    COINS qu'il faut mesurer, jamais son centre et sa taille. Une andouiller
    couche a 60 degres deborde d'un tiers de plus que sa hauteur ne le dit.
    """
    mn = [float("inf")] * 3
    mx = [float("-inf")] * 3

    def marcher(ps, base, repere):
        for p in ps:
            local = produit(repere, rotation(p.get("r")))
            noeud = [base[i] + appliquer(repere, p["p"])[i] for i in range(3)]
            centre = [noeud[i] + appliquer(local, p["o"])[i] for i in range(3)]
            demi = [p["s"][i] / 2.0 for i in range(3)]
            for sx in (-1, 1):
                for sy in (-1, 1):
                    for sz in (-1, 1):
                        coin = appliquer(local, (sx * demi[0], sy * demi[1], sz * demi[2]))
                        for i in range(3):
                            mn[i] = min(mn[i], centre[i] + coin[i])
                            mx[i] = max(mx[i], centre[i] + coin[i])
            if p.get("c"):
                marcher(p["c"], noeud, local)

    marcher(parts, [0.0, 0.0, 0.0], IDENTITE)
    return mn, mx


def totem(creature):
    """L'objet d'apparition d'une creature, derive comme dans la maquette."""
    if creature["monde"] == "entrainement":
        objet = dict(creature)
        objet["id"] = creature["id"] + "-objet"
        objet["parts"] = [cloner(p) for p in creature["parts"]]
        objet["nomObjet"] = creature["nom"]
        objet["descObjet"] = "Se pose au sol comme un bloc et se ramasse à la main."
        return objet

    source = trouver(creature["parts"], "tete")
    premiere = next(iter(creature["mat"]))
    if source is not None:
        tete = cloner(source)
        tete["p"] = [0, 0, 0]
        tete["r"] = None
    else:
        tete = P("groupe", [0, 0, 0], [0, 0, 0], None, m=premiere,
                 c=[cloner(p) for p in creature["parts"]])
    mn, mx = boite([tete])
    hw = max(mx[0] - mn[0], mx[2] - mn[2])
    pw = max(4, min(10, round(hw * 0.55)))
    ph = round(pw * 1.6)
    sw = pw + 3
    # La tete se pose sur le pilier : recentree en x et z, son DESSOUS a la
    # hauteur du sommet du pilier. `mx[1]` est le point le plus bas, y
    # descendant dans la maquette.
    tete["p"] = [-(mn[0] + mx[0]) / 2.0, -(2 + ph) - mx[1], -(mn[2] + mx[2]) / 2.0]

    matiere = TOTEM_MAT.get(creature["monde"], TOTEM_MAT["base"])
    bande = (creature["mat"].get((source or {}).get("m") or premiere)
             or creature["mat"][premiere])["pal"][1]
    g = pw // 2 - 1
    mat = dict(creature["mat"])
    mat["totem"] = matiere[0]
    return {
        "id": creature["id"] + "-totem",
        "mat": mat,
        "c": creature.get("c", {}),
        "nomObjet": "Totem : " + creature["nom"].lower(),
        "descObjet": "Posé au sol, fait apparaître "
                     + APPARITION.get(creature["id"], "la créature") + ".",
        "parts": [
            P("socle", [sw, 2, sw], [0, -1, 0], None, m="totem"),
            P("pilier", [pw, ph, pw], [0, -2 - ph / 2.0, 0], None, m="totem",
              f=[["tour", 0, 1, 99, 1, bande], ["tour", 0, ph - 2, 99, 1, bande],
                 ["tour", g, ph // 2 - 1, 2, 2, matiere[1]]]),
            tete,
        ],
    }
