#!/usr/bin/env python3
# Copyright 2026 The Terasology Foundation
# SPDX-License-Identifier: Apache-2.0
"""Construit une creature du bestiaire : un glTF squelette, sa peau, son icone.

    python tools/bestiaire/construire.py             # toutes les creatures
    python tools/bestiaire/construire.py mannequin   # une seule

Le modele et la peinture viennent de `modeles.py` et `peinture.py`, ports
litteraux de `bestiaire.js`. Ici on ne fait que traduire : l'arbre de boites
devient un maillage, les pieces animees deviennent des os, et l'atlas devient
un PNG.

CE QUE LE CHARGEUR glTF DE TERASOLOGY IMPOSE, ET QU'IL NE PARDONNE PAS. La
liste vient de `tools/heros/construire.py`, qui l'a payee :

  * un seul maillage, une seule primitive, `mode` 4 ;
  * exactement quatre poids par sommet, de somme 1,0 -- le chargeur ne
    normalise jamais, et quatre poids nuls effondrent le sommet a l'origine ;
  * `JOINTS_0` et les indices ne peuvent pas etre entrelaces ;
  * un `bufferView` par accessor, taille exacte : sans `byteStride`, le lecteur
    de flottants ignore `count` et lit tout le `bufferView` ;
  * exactement une racine dans `skin.joints` ;
  * les noeuds-os n'acceptent que `translation` / `rotation` / `scale`, jamais
    `matrix`, et doivent tous etre nommes ;
  * `interpolation` est obligatoire sur chaque sampler.

Les blocs `materials`, `textures` et `images` d'un glTF ne sont jamais lus par
Terasology : la texture vient du `.mat`, a cote.

DEUX REPERES, ET UNE SEULE BASCULE. La maquette compte en texels, y vers le
BAS, pieds a zero. Le jeu compte en blocs, y vers le HAUT, et une entite est
centree sur sa boite de collision -- sans quoi le maillage et le pave de
physique ne pourraient pas partager une origine. La bascule se fait ici, une
fois, dans `vers_jeu` pour les points et dans `bascule` pour les reperes.

UNE PIECE QUI TOURNE TOURNE TOUT CE QUI PEND D'ELLE. `r` est une rotation du
NOEUD, donc de la boite et de ses enfants : un bois de cerf est une chaine de
six pieces dont chacune tourne dans le repere de sa mere. Trois consequences,
et aucune n'est optionnelle :

  * les six faces d'une boite ne sont plus alignees sur les axes -- normale,
    `u` et `v` passent par la rotation du monde avant d'etre posees ;
  * un os porte desormais une ROTATION de repos en plus de sa translation, et
    sa matrice de liaison inverse cesse d'etre une simple translation ;
  * l'animation d'un os tourne dans son repere a lui, donc le quaternion ecrit
    dans le canal est celui du repos COMPOSE avec celui du balancement -- un
    canal `rotation` remplace la pose de repos, il ne s'y ajoute pas.

L'ICONE EST RENDUE, JAMAIS DESSINEE : c'est la regle du design system, et c'est
la meme projection orthographique que la vignette de la maquette (`rx = -18`,
`ry = -35`), rasterisee face par face dans l'ordre du peintre.
"""
from __future__ import annotations

import base64
import fractions
import json
import math
import pathlib
import struct
import sys
import zlib

import modeles
import peinture

RACINE = pathlib.Path(__file__).resolve().parents[2]
ASSETS = RACINE / "modules/Bestiaire/assets"

UNITE = modeles.TEXEL_M          # 0,05625 bloc par texel
MARGE = 0.01                     # cf. `tools/heros/construire.py` : jamais pile au bord
ICONE = 32                       # les icones d'equipement font 16 ; 32 rend un modele lisible

# --- les six faces --------------------------------------------------------
#
# Pour chaque face : sa normale sortante dans le repere LOCAL de la piece (y
# vers le bas, comme la maquette), le vecteur du `u` de la texture et celui de
# son `v`. Ces trois vecteurs sont ceux que `cube()` de la maquette construit
# par ses rotations CSS -- les recopier est ce qui garantit qu'une texture ne
# parte pas de travers sur les cotes.

FACES = {
    "devant":  {"n": (0, 0, 1),  "u": (1, 0, 0),  "v": (0, 1, 0)},
    "derriere": {"n": (0, 0, -1), "u": (-1, 0, 0), "v": (0, 1, 0)},
    "gauche":  {"n": (1, 0, 0),  "u": (0, 0, -1), "v": (0, 1, 0)},
    "droite":  {"n": (-1, 0, 0), "u": (0, 0, 1),  "v": (0, 1, 0)},
    "dessus":  {"n": (0, -1, 0), "u": (1, 0, 0),  "v": (0, 0, 1)},
    "dessous": {"n": (0, 1, 0),  "u": (1, 0, 0),  "v": (0, 0, -1)},
}


def dims_face(s, f):
    return peinture.dims_face(s, f)


# --- parcours de l'arbre --------------------------------------------------


def aplatir(creature):
    """Rend la liste des pieces, chacune avec son repere absolu, et les os.

    Une piece porte l'os de la piece animee la plus proche au-dessus d'elle --
    c'est ce qui fait qu'une tete posee sur un poteau suit le poteau.

    Tout est rendu dans le repere de la MAQUETTE : une piece porte `R`, la
    matrice qui oriente sa boite, et `centre`, le milieu de cette boite. Un os
    porte de meme `R` et le point `t` de son noeud.
    """
    pieces = []
    os = [{"nom": "racine", "parent": -1, "R": modeles.IDENTITE,
           "t": (0.0, 0.0, 0.0), "a": None}]

    def marcher(parts, base, repere, index_os):
        for p in parts:
            local = modeles.produit(repere, modeles.rotation(p.get("r")))
            decale = modeles.appliquer(repere, p["p"])
            noeud = (base[0] + decale[0], base[1] + decale[1], base[2] + decale[2])
            mien = index_os
            if p.get("a"):
                os.append({"nom": p["n"], "parent": index_os, "R": local,
                           "t": noeud, "a": p["a"]})
                mien = len(os) - 1
            ecart = modeles.appliquer(local, p["o"])
            centre = (noeud[0] + ecart[0], noeud[1] + ecart[1], noeud[2] + ecart[2])
            pieces.append({"n": p["n"], "s": p["s"], "centre": centre, "R": local,
                           "os": mien, "part": p})
            if p.get("c"):
                marcher(p["c"], noeud, local, mien)

    marcher(creature["parts"], (0.0, 0.0, 0.0), modeles.IDENTITE, 0)
    return pieces, os


def mesurer(creature):
    """Le pave d'une creature : sa taille en texels et le centre a soustraire.

    C'est `boite()` de la maquette, dans ses deux clauses : la hauteur se compte
    du sommet au PLAN DES PIEDS, jamais d'un extreme a l'autre — une patte qui
    depasserait sous zero ne grandirait pas la bete —, et le pivot se recentre
    en x et en z, jamais en y. Le mannequin est symetrique, donc ce recentrage
    ne le bouge pas ; le mouflon a la tete en avant, et sans lui son pave de
    physique deborderait de la croupe et lui manquerait le museau.
    """
    mn, mx = modeles.boite(creature["parts"])
    hauteur = max(-mn[1], 1.0)
    return {
        "largeur": mx[0] - mn[0],
        "hauteur": hauteur,
        "profondeur": mx[2] - mn[2],
        "cx": (mn[0] + mx[0]) / 2.0,
        "cz": (mn[2] + mx[2]) / 2.0,
        "demi": hauteur / 2.0,
    }


# --- la bascule de repere -------------------------------------------------
#
# Un point passe par `vers_jeu`. Un REPERE, lui, se conjugue : la maquette
# compte y vers le bas, donc une rotation autour de x ou de z change de sens en
# passant dans le jeu, et celle autour de y non. C'est la meme observation qui
# donne le signe des angles d'animation, et elle n'est ecrite qu'ici.

MIROIR = (1.0, -1.0, 1.0)


def bascule(R):
    """La matrice `R` de la maquette, vue depuis le repere du jeu."""
    return tuple(tuple(MIROIR[i] * R[i][j] * MIROIR[j] for j in range(3)) for i in range(3))


def quaternion_de(R):
    """Le quaternion `(x, y, z, w)` d'une matrice de rotation orthonormee."""
    trace = R[0][0] + R[1][1] + R[2][2]
    if trace > 0:
        s = math.sqrt(trace + 1.0) * 2
        return ((R[2][1] - R[1][2]) / s, (R[0][2] - R[2][0]) / s,
                (R[1][0] - R[0][1]) / s, 0.25 * s)
    if R[0][0] > R[1][1] and R[0][0] > R[2][2]:
        s = math.sqrt(1.0 + R[0][0] - R[1][1] - R[2][2]) * 2
        return (0.25 * s, (R[0][1] + R[1][0]) / s, (R[0][2] + R[2][0]) / s,
                (R[2][1] - R[1][2]) / s)
    if R[1][1] > R[2][2]:
        s = math.sqrt(1.0 + R[1][1] - R[0][0] - R[2][2]) * 2
        return ((R[0][1] + R[1][0]) / s, 0.25 * s, (R[1][2] + R[2][1]) / s,
                (R[0][2] - R[2][0]) / s)
    s = math.sqrt(1.0 + R[2][2] - R[0][0] - R[1][1]) * 2
    return ((R[0][2] + R[2][0]) / s, (R[1][2] + R[2][1]) / s, 0.25 * s,
            (R[1][0] - R[0][1]) / s)


def quaternion_produit(a, b):
    """`a` puis `b` dans le repere tourne par `a` -- l'ordre de CSS."""
    ax, ay, az, aw = a
    bx, by, bz, bw = b
    return (aw * bx + ax * bw + ay * bz - az * by,
            aw * by - ax * bz + ay * bw + az * bx,
            aw * bz + ax * by - ay * bx + az * bw,
            aw * bw - ax * bx - ay * by - az * bz)


# --- geometrie ------------------------------------------------------------


def construire_geometrie(pieces, rects, atlas_w, atlas_h, pave):
    """Les six faces de chaque boite, en blocs, avec leurs UV dans l'atlas."""

    def vers_jeu(x, y, z):
        """Maquette (texels, y vers le bas, pieds a zero) -> jeu (blocs, y vers
        le haut, modele centre sur son pave)."""
        return ((x - pave["cx"]) * UNITE,
                (-y - pave["demi"]) * UNITE,
                (z - pave["cz"]) * UNITE)

    positions, normales, uvs, joints, poids, indices = [], [], [], [], [], []
    for piece in pieces:
        cx, cy, cz = piece["centre"]
        w, h, d = piece["s"]
        R = piece["R"]
        for nom, base in FACES.items():
            fw, fh = dims_face(piece["s"], nom)
            rx, ry, rw, rh = rects[(id(piece["part"]), nom)]
            u0, v0 = (rx + MARGE) / atlas_w, (ry + MARGE) / atlas_h
            u1, v1 = (rx + rw - MARGE) / atlas_w, (ry + rh - MARGE) / atlas_h

            # La piece tourne, donc ses trois vecteurs de face tournent avec
            # elle : ils sont lus dans le repere local puis portes dans le
            # monde. Sans rotation, `R` vaut l'identite et rien ne change.
            n = modeles.appliquer(R, base["n"])
            u = modeles.appliquer(R, base["u"])
            v = modeles.appliquer(R, base["v"])
            # Le centre de la face, puis son coin (u = 0, v = 0). La demi-taille
            # se prend sur la normale LOCALE, la boite restant un pave dans son
            # propre repere.
            demi = (base["n"][0] * w / 2.0, base["n"][1] * h / 2.0, base["n"][2] * d / 2.0)
            ecart = modeles.appliquer(R, demi)
            centre = (cx + ecart[0], cy + ecart[1], cz + ecart[2])
            coin = tuple(centre[i] - u[i] * fw / 2.0 - v[i] * fh / 2.0 for i in range(3))
            sommets = [
                coin,
                tuple(coin[i] + u[i] * fw for i in range(3)),
                tuple(coin[i] + u[i] * fw + v[i] * fh for i in range(3)),
                tuple(coin[i] + v[i] * fh for i in range(3)),
            ]
            coins_uv = [(u0, v0), (u1, v0), (u1, v1), (u0, v1)]
            # La normale ne subit que la bascule de l'axe y, ni l'echelle ni le
            # recentrage : c'est une direction, pas un point.
            normale = (float(n[0]), float(-n[1]), float(n[2]))

            base_index = len(positions)
            for sommet, uv in zip(sommets, coins_uv):
                positions.append(vers_jeu(*sommet))
                normales.append(normale)
                uvs.append(uv)
                joints.append((piece["os"], 0, 0, 0))
                poids.append((1.0, 0.0, 0.0, 0.0))

            # L'enroulement se decide sur la geometrie, jamais de tete : la
            # bascule de repere retourne les faces, et une face retournee
            # disparait sous le back-face culling sans rien dire.
            a = positions[base_index]
            b = positions[base_index + 1]
            c = positions[base_index + 2]
            ab = tuple(b[i] - a[i] for i in range(3))
            ac = tuple(c[i] - a[i] for i in range(3))
            croix = (ab[1] * ac[2] - ab[2] * ac[1],
                     ab[2] * ac[0] - ab[0] * ac[2],
                     ab[0] * ac[1] - ab[1] * ac[0])
            direct = sum(croix[i] * normale[i] for i in range(3)) > 0
            if direct:
                indices.extend([base_index, base_index + 1, base_index + 2,
                                base_index, base_index + 2, base_index + 3])
            else:
                indices.extend([base_index + 2, base_index + 1, base_index,
                                base_index + 3, base_index + 2, base_index])
    return positions, normales, uvs, joints, poids, indices


# --- animation ------------------------------------------------------------
#
# La maquette anime par `sin(t * 2pi * 0,9 * v + ph)`, en degres d'amplitude
# `amp`, autour de l'axe `ax`. Un glTF n'interpole pas une formule : on
# echantillonne la periode.
#
# LA DUREE EST CELLE OU TOUT RETOMBE JUSTE. Une piece dont la periode ne divise
# pas la duree ferait un saut a la boucle -- un cerf balance la queue a 1,2 et
# la tete a 0,4, dont les periodes ne sont pas dans un rapport entier. La duree
# est donc le plus petit commun multiple : `0,9 D v` doit etre entier pour
# chaque `v`, donc `0,9 D` est le PPCM des denominateurs des `v`.
#
# Et le nombre de pas suit le nombre de cycles, jamais la duree : seize pas
# repartis sur six cycles de patte ne dessineraient plus une foulee.

PAS_PAR_CYCLE = 16
PAS_MAX = 512


def quaternion(axe, degres):
    a = math.radians(degres) / 2.0
    s, c = math.sin(a), math.cos(a)
    return {"x": (s, 0.0, 0.0, c), "y": (0.0, s, 0.0, c), "z": (0.0, 0.0, s, c)}[axe]


def animation(os):
    """Le repos : une seule animation, tous les os animes dedans."""
    animes = [(i, o) for i, o in enumerate(os) if o["a"]]
    if not animes:
        return None

    vitesses = [fractions.Fraction(o["a"].get("v") or 1).limit_denominator(1000)
                for _, o in animes]
    tours = 1
    for v in vitesses:
        d = v.denominator
        tours = tours * d // math.gcd(tours, d)
    duree = tours / 0.9
    cycles = max(int(tours * v) for v in vitesses)
    pas = PAS_PAR_CYCLE * cycles
    if pas > PAS_MAX:
        raise NotImplementedError("%d pas d'animation : les vitesses ne retombent "
                                  "juste que trop tard" % pas)

    temps = [duree * i / pas for i in range(pas + 1)]
    canaux = []
    for index, o in animes:
        a = o["a"]
        axe = a.get("ax", "x")
        if axe == "s":
            raise NotImplementedError("l'animation par etirement n'est pas portee")
        # Le canal REMPLACE la rotation de repos du noeud, il ne s'y ajoute
        # pas : on compose donc a la main, repos d'abord, balancement ensuite —
        # c'est l'ordre de la maquette, ou l'animation s'ecrit au bout de la
        # transformation de la piece.
        repos = o["repos"]
        quats = []
        for t in temps:
            v = math.sin(t * 2 * math.pi * 0.9 * (a.get("v") or 1) + (a.get("ph") or 0))
            # y descend dans la maquette : une rotation autour de x ou de z
            # change de sens en passant dans le repere du jeu, pas celle
            # autour de y.
            signe = 1.0 if axe == "y" else -1.0
            quats.append(quaternion_produit(repos, quaternion(axe, signe * a["amp"] * v)))
        canaux.append((index, quats))
    return {"duree": duree, "temps": temps, "canaux": canaux, "pas": pas}


# --- ecriture des fichiers ------------------------------------------------


class Tampon:
    """Accumule les octets et rend des `bufferView` deja alignes."""

    def __init__(self):
        self.octets = bytearray()
        self.vues = []

    def ajouter(self, donnees):
        while len(self.octets) % 4:
            self.octets.append(0)
        decalage = len(self.octets)
        self.octets.extend(donnees)
        self.vues.append({"buffer": 0, "byteOffset": decalage, "byteLength": len(donnees)})
        return len(self.vues) - 1


def ecrire_png(chemin, largeur, hauteur, pixels):
    brut = bytearray()
    for y in range(hauteur):
        brut.append(0)  # filtre « aucun »
        debut = y * largeur * 4
        brut.extend(pixels[debut:debut + largeur * 4])

    def morceau(nom, donnees):
        bloc = nom + donnees
        return struct.pack(">I", len(donnees)) + bloc + struct.pack(">I", zlib.crc32(bloc))

    png = b"\x89PNG\r\n\x1a\n"
    png += morceau(b"IHDR", struct.pack(">IIBBBBB", largeur, hauteur, 8, 6, 0, 0, 0))
    png += morceau(b"IDAT", zlib.compress(bytes(brut), 9))
    png += morceau(b"IEND", b"")
    chemin.parent.mkdir(parents=True, exist_ok=True)
    chemin.write_bytes(png)


def ecrire_gltf(chemin, nom, positions, normales, uvs, joints, poids, indices, os, anim):
    n = len(positions)
    tampon = Tampon()
    accessors = []

    def accessor(donnees, type_composant, type_gltf, compte, extras=None):
        vue = tampon.ajouter(donnees)
        a = {"bufferView": vue, "componentType": type_composant, "count": compte, "type": type_gltf}
        if extras:
            a.update(extras)
        accessors.append(a)
        return len(accessors) - 1

    mn = [min(p[i] for p in positions) for i in range(3)]
    mx = [max(p[i] for p in positions) for i in range(3)]
    a_pos = accessor(struct.pack("<%df" % (n * 3), *[c for p in positions for c in p]),
                     5126, "VEC3", n, {"min": mn, "max": mx})
    a_nor = accessor(struct.pack("<%df" % (n * 3), *[c for p in normales for c in p]), 5126, "VEC3", n)
    a_uv = accessor(struct.pack("<%df" % (n * 2), *[c for p in uvs for c in p]), 5126, "VEC2", n)
    a_joi = accessor(struct.pack("<%dH" % (n * 4), *[c for p in joints for c in p]), 5123, "VEC4", n)
    a_poi = accessor(struct.pack("<%df" % (n * 4), *[c for p in poids for c in p]), 5126, "VEC4", n)
    a_ind = accessor(struct.pack("<%dH" % len(indices), *indices), 5123, "SCALAR", len(indices))

    # La matrice de liaison inverse est l'inverse COMPLET du repere de repos de
    # l'os -- rotation transposee et translation ramenee avec elle. Elle etait
    # une simple translation tant qu'aucun os ne tournait ; elle ne l'est plus.
    inverses = []
    for o in os:
        R, p = o["monde"], o["point"]
        colonnes = [R[j] for j in range(3)]
        t = [-sum(R[k][i] * p[k] for k in range(3)) for i in range(3)]
        for col in colonnes:
            inverses.extend([col[0], col[1], col[2], 0.0])
        inverses.extend([t[0], t[1], t[2], 1.0])
    a_inv = accessor(struct.pack("<%df" % len(inverses), *inverses), 5126, "MAT4", len(os))

    animations = []
    if anim:
        a_temps = accessor(struct.pack("<%df" % len(anim["temps"]), *anim["temps"]),
                           5126, "SCALAR", len(anim["temps"]),
                           {"min": [anim["temps"][0]], "max": [anim["temps"][-1]]})
        samplers, canaux = [], []
        for index, quats in anim["canaux"]:
            plats = [c for q in quats for c in q]
            a_rot = accessor(struct.pack("<%df" % len(plats), *plats), 5126, "VEC4", len(quats))
            samplers.append({"input": a_temps, "interpolation": "LINEAR", "output": a_rot})
            canaux.append({"sampler": len(samplers) - 1, "target": {"node": 1 + index, "path": "rotation"}})
        animations.append({"name": "repos", "samplers": samplers, "channels": canaux})

    noeuds = [{"name": nom, "mesh": 0, "skin": 0, "children": [1]}]
    for i, o in enumerate(os):
        noeud = {"name": o["nom"], "translation": list(o["translation"])}
        if any(abs(c) > 1e-9 for c in o["rotation"][:3]):
            noeud["rotation"] = list(o["rotation"])
        enfants = [1 + j for j, e in enumerate(os) if e["parent"] == i]
        if enfants:
            noeud["children"] = enfants
        noeuds.append(noeud)

    gltf = {
        "asset": {"version": "2.0", "generator": "Evolve tools/bestiaire/construire.py"},
        "scene": 0,
        "scenes": [{"nodes": [0]}],
        "nodes": noeuds,
        "meshes": [{"name": nom, "primitives": [{
            "attributes": {"POSITION": a_pos, "NORMAL": a_nor, "TEXCOORD_0": a_uv,
                           "JOINTS_0": a_joi, "WEIGHTS_0": a_poi},
            "indices": a_ind, "mode": 4}]}],
        "skins": [{"name": "squelette", "joints": [1 + i for i in range(len(os))],
                   "inverseBindMatrices": a_inv}],
        "accessors": accessors,
        "bufferViews": tampon.vues,
        "buffers": [{"byteLength": len(tampon.octets),
                     "uri": "data:application/octet-stream;base64,"
                            + base64.b64encode(bytes(tampon.octets)).decode("ascii")}],
    }
    if animations:
        gltf["animations"] = animations
    chemin.parent.mkdir(parents=True, exist_ok=True)
    chemin.write_text(json.dumps(gltf, indent=2), encoding="utf-8")
    return len(tampon.octets)


# --- icone ----------------------------------------------------------------


def _rot(rx, ry):
    """La matrice de vue de la vignette : rotateX puis rotateY, comme en CSS."""
    cx, sx = math.cos(math.radians(rx)), math.sin(math.radians(rx))
    cy, sy = math.cos(math.radians(ry)), math.sin(math.radians(ry))
    mx = ((1, 0, 0), (0, cx, -sx), (0, sx, cx))
    my = ((cy, 0, sy), (0, 1, 0), (-sy, 0, cy))
    return tuple(tuple(sum(mx[i][k] * my[k][j] for k in range(3)) for j in range(3)) for i in range(3))


def _appliquer(m, p):
    return tuple(sum(m[i][j] * p[j] for j in range(3)) for i in range(3))


def rendre_icone(pieces, rects, atlas_w, atlas_h, pixels, taille, rx=-18, ry=-35):
    """Projection orthographique, une face a la fois, dans l'ordre du peintre.

    C'est la `vignette()` de la maquette, rasterisee a la main : pour chaque
    pixel de l'image on remonte au texel, au lieu de laisser un canvas poser une
    transformation affine. Le plus proche voisin, jamais de lissage.
    """
    M = _rot(rx, ry)
    faces = []
    for piece in pieces:
        cx, cy, cz = piece["centre"]
        w, h, d = piece["s"]
        R = piece["R"]
        for nom, base in FACES.items():
            fw, fh = dims_face(piece["s"], nom)
            n = modeles.appliquer(R, base["n"])
            u = modeles.appliquer(R, base["u"])
            v = modeles.appliquer(R, base["v"])
            demi = (base["n"][0] * w / 2.0, base["n"][1] * h / 2.0, base["n"][2] * d / 2.0)
            ecart = modeles.appliquer(R, demi)
            centre = (cx + ecart[0], cy + ecart[1], cz + ecart[2])
            coin = tuple(centre[i] - u[i] * fw / 2.0 - v[i] * fh / 2.0 for i in range(3))
            o = _appliquer(M, coin)
            eu = _appliquer(M, tuple(coin[i] + u[i] for i in range(3)))
            ev = _appliquer(M, tuple(coin[i] + v[i] for i in range(3)))
            ex = tuple(eu[i] - o[i] for i in range(3))
            ey = tuple(ev[i] - o[i] for i in range(3))
            # Face vue de dos : le repere de la face s'inverse a l'ecran.
            if ex[0] * ey[1] - ex[1] * ey[0] <= 0.0001:
                continue
            faces.append({"o": o, "ex": ex, "ey": ey, "fw": fw, "fh": fh,
                          "r": rects[(id(piece["part"]), nom)],
                          "z": _appliquer(M, centre)[2]})

    xs, ys = [], []
    for f in faces:
        for a in (0, f["fw"]):
            for b in (0, f["fh"]):
                xs.append(f["o"][0] + f["ex"][0] * a + f["ey"][0] * b)
                ys.append(f["o"][1] + f["ex"][1] * a + f["ey"][1] * b)
    x0, x1, y0, y1 = min(xs), max(xs), min(ys), max(ys)

    marge = 1
    S = min((taille - 2 * marge) / (x1 - x0), (taille - 2 * marge) / (y1 - y0))
    tx = (taille - (x1 - x0) * S) / 2.0 - x0 * S
    ty = (taille - (y1 - y0) * S) / 2.0 - y0 * S

    image = bytearray(taille * taille * 4)
    for f in sorted(faces, key=lambda f: f["z"]):
        # Le systeme (ex, ey) -> ecran, inverse une fois par face.
        ax, ay = f["ex"][0] * S, f["ex"][1] * S
        bx, by = f["ey"][0] * S, f["ey"][1] * S
        det = ax * by - ay * bx
        if abs(det) < 1e-9:
            continue
        ox, oy = f["o"][0] * S + tx, f["o"][1] * S + ty
        coins = [(ox, oy), (ox + ax * f["fw"], oy + ay * f["fw"]),
                 (ox + bx * f["fh"], oy + by * f["fh"]),
                 (ox + ax * f["fw"] + bx * f["fh"], oy + ay * f["fw"] + by * f["fh"])]
        px0 = max(0, int(math.floor(min(c[0] for c in coins))))
        px1 = min(taille, int(math.ceil(max(c[0] for c in coins))) + 1)
        py0 = max(0, int(math.floor(min(c[1] for c in coins))))
        py1 = min(taille, int(math.ceil(max(c[1] for c in coins))) + 1)
        rx0, ry0, rw, rh = f["r"]
        for py in range(py0, py1):
            for px in range(px0, px1):
                dx, dy = px + 0.5 - ox, py + 0.5 - oy
                su = (dx * by - dy * bx) / det
                sv = (ax * dy - ay * dx) / det
                if su < 0 or sv < 0 or su >= f["fw"] or sv >= f["fh"]:
                    continue
                tu = min(rw - 1, int(su * rw / f["fw"]))
                tv = min(rh - 1, int(sv * rh / f["fh"]))
                i = ((ry0 + tv) * atlas_w + (rx0 + tu)) * 4
                if pixels[i + 3] == 0:
                    continue
                j = (py * taille + px) * 4
                image[j:j + 4] = pixels[i:i + 4]
    return image


# --- entree ---------------------------------------------------------------


def icone_objet(creature):
    """L'icone de l'objet qui fait apparaitre la creature, ou `None`.

    La maquette derive cet objet au lieu de le modeler (`totem()`), et son atlas
    a son propre identifiant — `mouflon-totem`, `mannequin-objet` — donc son
    propre grain. Cet atlas ne sert qu'a l'icone : rien ne le pose dans le
    monde, et rien ne l'ecrit sur le disque.

    Une creature peut refuser l'objet (`"objet": False`) : le mouflon tondu ne
    s'invoque pas, il sortira d'une tonte. Peindre son icone quand meme
    laisserait dans les assets une image que plus rien ne reclame.
    """
    if creature.get("objet") is False:
        return None, None
    objet = modeles.totem(creature)
    pieces, _ = aplatir(objet)
    atlas_w, atlas_h, pixels, rects = peinture.atlas(objet)
    return objet, rendre_icone(pieces, rects, atlas_w, atlas_h, pixels, ICONE)


def squelette(os_bruts, pave):
    """Porte les os dans le repere du jeu et en deduit leur pose locale.

    Chaque os garde son repere de repos ABSOLU (`monde`, `point`) — c'est de
    lui que sort la matrice de liaison inverse — et la pose relative a son
    parent, qui est ce que le noeud glTF porte.
    """
    demi = pave["demi"]
    os = []
    for brut in os_bruts:
        t = brut["t"]
        os.append({
            "nom": brut["nom"],
            "parent": brut["parent"],
            "a": brut["a"],
            "monde": bascule(brut["R"]),
            "point": ((t[0] - pave["cx"]) * UNITE,
                      (-t[1] - demi) * UNITE,
                      (t[2] - pave["cz"]) * UNITE),
        })
    for o in os:
        parent = os[o["parent"]] if o["parent"] >= 0 else None
        if parent is None:
            R, p = modeles.IDENTITE, (0.0, 0.0, 0.0)
        else:
            R, p = parent["monde"], parent["point"]
        ecart = tuple(o["point"][k] - p[k] for k in range(3))
        o["translation"] = tuple(sum(R[k][i] * ecart[k] for k in range(3)) for i in range(3))
        locale = tuple(tuple(sum(R[k][i] * o["monde"][k][j] for k in range(3))
                             for j in range(3)) for i in range(3))
        o["rotation"] = quaternion_de(locale)
        o["repos"] = o["rotation"]
    return os


def construire(creature):
    pieces, os_bruts = aplatir(creature)
    pave = mesurer(creature)
    os = squelette(os_bruts, pave)

    atlas_w, atlas_h, pixels, rects = peinture.atlas(creature)
    geo = construire_geometrie(pieces, rects, atlas_w, atlas_h, pave)
    anim = animation(os)
    objet, image = icone_objet(creature)

    cle = creature["id"]
    gltf = ASSETS / "skeletalMesh" / (cle + ".gltf")
    png = ASSETS / "textures" / (cle + ".png")
    icone = ASSETS / "textures" / (cle + "Icone.png")

    octets = ecrire_gltf(gltf, cle, *geo, os, anim)
    ecrire_png(png, atlas_w, atlas_h, pixels)
    if image is not None:
        ecrire_png(icone, ICONE, ICONE, image)

    print("%s : %d sommets, %d triangles, %d boites, %d os, %d o de tampon"
          % (gltf.name, len(geo[0]), len(geo[5]) // 3, len(pieces), len(os), octets))
    print("%s : atlas %dx%d" % (png.name, atlas_w, atlas_h))
    if image is None:
        print("  pas d'objet d'apparition : aucune icone")
    else:
        print("%s : icone %dx%d de « %s »" % (icone.name, ICONE, ICONE, objet["nomObjet"]))
    print("  boite : %.4f x %.4f x %.4f bloc (%g x %g x %g texels)"
          % (pave["largeur"] * UNITE, pave["hauteur"] * UNITE, pave["profondeur"] * UNITE,
             pave["largeur"], pave["hauteur"], pave["profondeur"]))
    print("  pivot : recentre de %g et %g texels en x et z" % (pave["cx"], pave["cz"]))
    if anim:
        print("  repos : %.4f s, %d os animes, %d pas"
              % (anim["duree"], len(anim["canaux"]), anim["pas"]))
    return pave


if __name__ == "__main__":
    demandees = [a for a in sys.argv[1:] if not a.startswith("--")]
    for c in modeles.CREATURES:
        if not demandees or c["id"] in demandees:
            construire(c)
