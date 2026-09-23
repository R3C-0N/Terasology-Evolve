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


def cervide():
    """Le patron d'un cervide : corps, quatre pattes, cou coude, bois, queue.

    Le cou est incline de -25 degres et la tete de +25 : la tete revient droite,
    et c'est le coude du cou qui donne le port de tete. Sans rotation de piece
    les deux se confondraient en un seul tuyau.
    """
    y = -16
    return [
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
                 + boisCerf("bois"))]),
        P("queue", [2, 3, 1], [0, y - 3, -7.5], [0, 1.5, 0], m="blanc", r=[-20, 0, 0],
          a={"ax": "z", "amp": 10, "v": 1.2}),
    ]


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

CREATURES = [MANNEQUIN, CERF, MOUFLON, MOUFLON_TONDU, LAPIN, VACHE, FAISAN]
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
