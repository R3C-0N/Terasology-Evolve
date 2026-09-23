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
fois, dans `vers_jeu`.

L'ICONE EST RENDUE, JAMAIS DESSINEE : c'est la regle du design system, et c'est
la meme projection orthographique que la vignette de la maquette (`rx = -18`,
`ry = -35`), rasterisee face par face dans l'ordre du peintre.
"""
from __future__ import annotations

import base64
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
# Pour chaque face : sa normale sortante dans le repere de la MAQUETTE (y vers
# le bas), le vecteur du `u` de la texture et celui de son `v`. Ces trois
# vecteurs sont ceux que `cube()` de la maquette construit par ses rotations
# CSS -- les recopier est ce qui garantit qu'une texture ne parte pas de
# travers sur les cotes.

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
    """Rend la liste des pieces, chacune avec sa position absolue et son os.

    Une piece porte l'os de la piece animee la plus proche au-dessus d'elle --
    c'est ce qui fait qu'une tete posee sur un poteau suit le poteau.
    """
    pieces = []
    os = [("racine", -1, (0.0, 0.0, 0.0))]

    def marcher(parts, base, index_os):
        for p in parts:
            if p.get("r"):
                raise NotImplementedError(
                    "la piece « %s » tourne : le generateur ne porte pas encore les "
                    "rotations de piece (aucune creature du monde d'entrainement n'en a)" % p["n"])
            noeud = (base[0] + p["p"][0], base[1] + p["p"][1], base[2] + p["p"][2])
            mien = index_os
            if p.get("a"):
                os.append((p["n"], index_os, noeud))
                mien = len(os) - 1
            centre = (noeud[0] + p["o"][0], noeud[1] + p["o"][1], noeud[2] + p["o"][2])
            pieces.append({"n": p["n"], "s": p["s"], "centre": centre, "os": mien, "a": p.get("a")})
            if p.get("c"):
                marcher(p["c"], noeud, mien)

    marcher(creature["parts"], (0.0, 0.0, 0.0), 0)
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


# --- geometrie ------------------------------------------------------------


def construire_geometrie(creature, pieces, rects, atlas_w, atlas_h, pave):
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
        for nom, base in FACES.items():
            fw, fh = dims_face(piece["s"], nom)
            rx, ry, rw, rh = rects[(piece["n"], nom)]
            u0, v0 = (rx + MARGE) / atlas_w, (ry + MARGE) / atlas_h
            u1, v1 = (rx + rw - MARGE) / atlas_w, (ry + rh - MARGE) / atlas_h

            n, u, v = base["n"], base["u"], base["v"]
            # Le centre de la face, puis son coin (u = 0, v = 0).
            centre = (cx + n[0] * w / 2.0, cy + n[1] * h / 2.0, cz + n[2] * d / 2.0)
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
# echantillonne la periode. Seize pas suffisent pour un balancement de trois
# degres -- l'erreur d'une interpolation lineaire y est sous le vingtieme de
# degre.

PAS = 16


def quaternion(axe, degres):
    a = math.radians(degres) / 2.0
    s, c = math.sin(a), math.cos(a)
    return {"x": (s, 0.0, 0.0, c), "y": (0.0, s, 0.0, c), "z": (0.0, 0.0, s, c)}[axe]


def animation(os):
    """Le repos : une seule animation, tous les os animes dedans."""
    animes = [(i, o) for i, o in enumerate(os) if o[3]]
    if not animes:
        return None
    periodes = [1.0 / (0.9 * (o[3].get("v") or 1)) for _, o in animes]
    duree = max(periodes)
    for p in periodes:
        # Une piece dont la periode ne divise pas la duree ferait un saut a la
        # boucle. Aucune creature n'est dans ce cas ; le jour ou, il faudra
        # prendre le plus petit commun multiple.
        rapport = duree / p
        if abs(rapport - round(rapport)) > 1e-9:
            raise NotImplementedError("periodes incommensurables : %.4f et %.4f" % (duree, p))

    temps = [duree * i / PAS for i in range(PAS + 1)]
    canaux = []
    for index, o in animes:
        a = o[3]
        axe = a.get("ax", "x")
        if axe == "s":
            raise NotImplementedError("l'animation par etirement n'est pas portee")
        angles = []
        for t in temps:
            v = math.sin(t * 2 * math.pi * 0.9 * (a.get("v") or 1) + (a.get("ph") or 0))
            # y descend dans la maquette : une rotation autour de x ou de z
            # change de sens en passant dans le repere du jeu, pas celle
            # autour de y.
            signe = 1.0 if axe == "y" else -1.0
            angles.append(signe * a["amp"] * v)
        canaux.append((index, axe, angles))
    return {"duree": duree, "temps": temps, "canaux": canaux}


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

    inverses = []
    for o in os:
        g = o[2]
        inverses.extend([1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1, 0, -g[0], -g[1], -g[2], 1])
    a_inv = accessor(struct.pack("<%df" % len(inverses), *inverses), 5126, "MAT4", len(os))

    animations = []
    if anim:
        a_temps = accessor(struct.pack("<%df" % len(anim["temps"]), *anim["temps"]),
                           5126, "SCALAR", len(anim["temps"]),
                           {"min": [anim["temps"][0]], "max": [anim["temps"][-1]]})
        samplers, canaux = [], []
        for index, axe, angles in anim["canaux"]:
            quats = [c for d in angles for c in quaternion(axe, d)]
            a_rot = accessor(struct.pack("<%df" % len(quats), *quats), 5126, "VEC4", len(angles))
            samplers.append({"input": a_temps, "interpolation": "LINEAR", "output": a_rot})
            canaux.append({"sampler": len(samplers) - 1, "target": {"node": 1 + index, "path": "rotation"}})
        animations.append({"name": "repos", "samplers": samplers, "channels": canaux})

    noeuds = [{"name": nom, "mesh": 0, "skin": 0, "children": [1]}]
    for i, o in enumerate(os):
        noeud = {"name": o[0], "translation": list(o[4])}
        enfants = [1 + j for j, o in enumerate(os) if o[1] == i]
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
        for nom, base in FACES.items():
            fw, fh = dims_face(piece["s"], nom)
            n, u, v = base["n"], base["u"], base["v"]
            centre = (cx + n[0] * w / 2.0, cy + n[1] * h / 2.0, cz + n[2] * d / 2.0)
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
                          "r": rects[(piece["n"], nom)], "z": _appliquer(M, centre)[2]})

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
    """L'icone de l'objet qui fait apparaitre la creature.

    La maquette derive cet objet au lieu de le modeler (`totem()`), et son atlas
    a son propre identifiant — `mouflon-totem`, `mannequin-objet` — donc son
    propre grain. Cet atlas ne sert qu'a l'icone : rien ne le pose dans le
    monde, et rien ne l'ecrit sur le disque.
    """
    objet = modeles.totem(creature)
    pieces, _ = aplatir(objet)
    atlas_w, atlas_h, pixels, rects = peinture.atlas(objet)
    return objet, rendre_icone(pieces, rects, atlas_w, atlas_h, pixels, ICONE)


def construire(creature):
    pieces, os_bruts = aplatir(creature)
    pave = mesurer(creature)
    demi = pave["demi"]

    # Les os portent leur pivot global (en blocs) et leur translation locale.
    os = []
    for nom, parent, noeud in os_bruts:
        global_jeu = ((noeud[0] - pave["cx"]) * UNITE,
                      (-noeud[1] - demi) * UNITE,
                      (noeud[2] - pave["cz"]) * UNITE)
        anim = None
        for p in pieces:
            if p["n"] == nom and p["a"]:
                anim = p["a"]
        os.append([nom, parent, global_jeu, anim, (0.0, 0.0, 0.0)])
    for i, o in enumerate(os):
        parent = o[1]
        base = os[parent][2] if parent >= 0 else (0.0, 0.0, 0.0)
        o[4] = tuple(o[2][k] - base[k] for k in range(3))
    os = [tuple(o) for o in os]

    atlas_w, atlas_h, pixels, rects = peinture.atlas(creature)
    geo = construire_geometrie(creature, pieces, rects, atlas_w, atlas_h, pave)
    anim = animation(os)
    objet, image = icone_objet(creature)

    cle = creature["id"]
    gltf = ASSETS / "skeletalMesh" / (cle + ".gltf")
    png = ASSETS / "textures" / (cle + ".png")
    icone = ASSETS / "textures" / (cle + "Icone.png")

    octets = ecrire_gltf(gltf, cle, *geo, os, anim)
    ecrire_png(png, atlas_w, atlas_h, pixels)
    ecrire_png(icone, ICONE, ICONE, image)

    print("%s : %d sommets, %d triangles, %d boites, %d os, %d o de tampon"
          % (gltf.name, len(geo[0]), len(geo[5]) // 3, len(pieces), len(os), octets))
    print("%s : atlas %dx%d" % (png.name, atlas_w, atlas_h))
    print("%s : icone %dx%d de « %s »" % (icone.name, ICONE, ICONE, objet["nomObjet"]))
    print("  boite : %.4f x %.4f x %.4f bloc (%g x %g x %g texels)"
          % (pave["largeur"] * UNITE, pave["hauteur"] * UNITE, pave["profondeur"] * UNITE,
             pave["largeur"], pave["hauteur"], pave["profondeur"]))
    print("  pivot : recentre de %g et %g texels en x et z" % (pave["cx"], pave["cz"]))
    if anim:
        print("  repos : %.4f s, %d os animes, %d pas" % (anim["duree"], len(anim["canaux"]), PAS))


if __name__ == "__main__":
    demandees = [a for a in sys.argv[1:] if not a.startswith("--")]
    for c in modeles.CREATURES:
        if not demandees or c["id"] in demandees:
            construire(c)
