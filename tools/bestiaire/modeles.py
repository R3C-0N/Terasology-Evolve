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

CREATURES = [MANNEQUIN, MOUFLON]
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
    """Boite englobante d'un arbre de pieces, en texels. Rend `(min, max)`."""
    mn = [float("inf")] * 3
    mx = [float("-inf")] * 3

    def marcher(ps, base):
        for p in ps:
            if p.get("r"):
                raise NotImplementedError("boite() ne porte pas les rotations de piece")
            noeud = [base[i] + p["p"][i] for i in range(3)]
            centre = [noeud[i] + p["o"][i] for i in range(3)]
            for i in range(3):
                mn[i] = min(mn[i], centre[i] - p["s"][i] / 2.0)
                mx[i] = max(mx[i], centre[i] + p["s"][i] / 2.0)
            if p.get("c"):
                marcher(p["c"], noeud)

    marcher(parts, [0.0, 0.0, 0.0])
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
