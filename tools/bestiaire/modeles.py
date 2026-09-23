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

TEXEL_M = 0.05625


def P(n, s, p, o=None, **kw):
    piece = {"n": n, "s": s, "p": p, "o": o or [0, 0, 0]}
    piece.update(kw)
    return piece


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

CREATURES = [MANNEQUIN]
PAR_ID = {c["id"]: c for c in CREATURES}
