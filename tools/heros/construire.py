#!/usr/bin/env python3
"""Construit le corps du heros : un glTF squelette et sa texture.

Le depot n'avait aucun modele de personnage -- le seul visuel existant etait un
cube flottant de 0,25 bloc. Ce script produit un humanoide en six boites,
sept os, deux animations, et l'atlas de couleurs qui va avec :

    engine/src/main/resources/org/terasology/engine/assets/skeletalMesh/heros.gltf
    engine/src/main/resources/org/terasology/engine/assets/textures/heros.png

Pourquoi un script et non un fichier ecrit a la main : le glTF est un format
binaire enrobe de JSON, ou chaque `byteLength` doit tomber juste et ou une
retouche de proportion redistribue tous les decalages. Le regenerer coute une
seconde, le corriger a la main coute une seance.

Ce que le chargeur de Terasology impose, et qu'il ne pardonne pas :

  * un seul maillage, une seule primitive, `mode` 4 -- tout le corps est donc
    une seule primitive et une seule texture ;
  * exactement quatre poids par sommet, de somme 1,0 : le chargeur ne
    normalise jamais, et quatre poids nuls effondrent le sommet a l'origine ;
  * `JOINTS_0` et les indices ne peuvent pas etre entrelaces -- le lecteur
    d'entiers ignore `byteStride` et le `byteOffset` de l'accessor ;
  * sans `byteStride`, le lecteur de flottants ignore `count` et lit tout le
    `bufferView` : un `bufferView` par accessor, taille exacte ;
  * exactement une racine dans `skin.joints` ;
  * les noeuds-os n'acceptent que `translation` / `rotation` / `scale`, jamais
    `matrix`, et doivent tous etre nommes -- le nom est la seule cle de
    correspondance entre le maillage et ses animations ;
  * `interpolation` est obligatoire sur chaque sampler : le champ n'a pas de
    defaut et son absence donne un NullPointerException.

Les blocs `materials`, `textures` et `images` d'un glTF ne sont jamais lus par
Terasology : la texture vient du `.mat`, a cote.

Usage : python tools/heros/construire.py
"""

import base64
import json
import math
import pathlib
import struct
import zlib

RACINE = pathlib.Path(__file__).resolve().parents[2]
ASSETS = RACINE / "engine/src/main/resources/org/terasology/engine/assets"
GLTF = ASSETS / "skeletalMesh/heros.gltf"
PNG = ASSETS / "textures/heros.png"

# ---------------------------------------------------------------- proportions
#
# L'origine du personnage est au CENTRE de sa capsule de 1,8 bloc : les yeux
# sont poses par GazeAuthoritySystem a `hauteur/7,5*7 - hauteur/2`, et ce terme
# en -h/2 trahit un centre. Le modele va donc de -0,90 (pieds) a +0,90 (crane).

TORSE_DEMI_LARGEUR = 0.225
TORSE_DEMI_PROFONDEUR = 0.11
HANCHE = -0.15          # sommet des jambes, base du torse
EPAULE = 0.45           # sommet du torse, base du crane
PIED = -0.90
CRANE = 0.90

BRAS_DEMI = 0.075
BRAS_X = 0.30           # centre du bras : 0,225 du torse + 0,075 du bras
JAMBE_DEMI = 0.10
JAMBE_X = 0.1125

# ------------------------------------------------------------------- squelette
#
# (nom, parent, translation locale). Chaque os est pose sur son pivot : les bras
# a l'epaule, les jambes a la hanche -- c'est ce qui fait qu'une rotation les
# fait balancer au lieu de les deplacer.

OS = [
    ("bassin", -1, (0.0, HANCHE, 0.0)),
    ("torse", 0, (0.0, 0.0, 0.0)),
    ("tete", 1, (0.0, EPAULE - HANCHE, 0.0)),
    ("brasG", 1, (BRAS_X, EPAULE - HANCHE, 0.0)),
    ("brasD", 1, (-BRAS_X, EPAULE - HANCHE, 0.0)),
    ("jambeG", 0, (JAMBE_X, 0.0, 0.0)),
    ("jambeD", 0, (-JAMBE_X, 0.0, 0.0)),
]
INDEX_OS = {nom: i for i, (nom, _, _) in enumerate(OS)}

# ---------------------------------------------------------------------- atlas
#
# Seize cases de 16 pixels dans une image de 64. Une boite prend une case par
# face, ce qui permet de donner au crane un visage devant et des cheveux
# derriere sans couper le maillage en plusieurs primitives.

CASES = {
    "peau": (0, 0, (224, 172, 105)),
    "visage": (1, 0, (224, 172, 105)),
    "cheveux": (2, 0, (74, 49, 33)),
    "tunique": (3, 0, (62, 122, 78)),
    "manche": (0, 1, (51, 99, 65)),
    "pantalon": (1, 1, (49, 66, 94)),
    "botte": (2, 1, (74, 49, 33)),
    "main": (3, 1, (212, 154, 92)),
}

# (boite, os, min, max, {face: case}). Les faces sont nommees par leur normale.
BOITES = [
    ("tete", "tete",
     (-0.225, EPAULE, -0.225), (0.225, CRANE, 0.225),
     {"+z": "visage", "-z": "cheveux", "+y": "cheveux",
      "-y": "peau", "+x": "peau", "-x": "peau"}),
    ("torse", "torse",
     (-TORSE_DEMI_LARGEUR, HANCHE, -TORSE_DEMI_PROFONDEUR),
     (TORSE_DEMI_LARGEUR, EPAULE, TORSE_DEMI_PROFONDEUR),
     {f: "tunique" for f in ("+x", "-x", "+y", "-y", "+z", "-z")}),
    ("brasG", "brasG",
     (BRAS_X - BRAS_DEMI, HANCHE, -BRAS_DEMI),
     (BRAS_X + BRAS_DEMI, EPAULE, BRAS_DEMI),
     {"-y": "main", "+x": "manche", "-x": "manche",
      "+y": "manche", "+z": "manche", "-z": "manche"}),
    ("brasD", "brasD",
     (-BRAS_X - BRAS_DEMI, HANCHE, -BRAS_DEMI),
     (-BRAS_X + BRAS_DEMI, EPAULE, BRAS_DEMI),
     {"-y": "main", "+x": "manche", "-x": "manche",
      "+y": "manche", "+z": "manche", "-z": "manche"}),
    ("jambeG", "jambeG",
     (JAMBE_X - JAMBE_DEMI, PIED, -JAMBE_DEMI),
     (JAMBE_X + JAMBE_DEMI, HANCHE, JAMBE_DEMI),
     {"-y": "botte", "+x": "pantalon", "-x": "pantalon",
      "+y": "pantalon", "+z": "pantalon", "-z": "pantalon"}),
    ("jambeD", "jambeD",
     (-JAMBE_X - JAMBE_DEMI, PIED, -JAMBE_DEMI),
     (-JAMBE_X + JAMBE_DEMI, HANCHE, JAMBE_DEMI),
     {"-y": "botte", "+x": "pantalon", "-x": "pantalon",
      "+y": "pantalon", "+z": "pantalon", "-z": "pantalon"}),
]


def faces(mn, mx):
    """Les six faces d'une boite, sommets dans l'ordre bas-gauche -> haut-gauche
    vus de l'exterieur, ce qui donne un enroulement direct."""
    x0, y0, z0 = mn
    x1, y1, z1 = mx
    return {
        "+z": ([(x0, y0, z1), (x1, y0, z1), (x1, y1, z1), (x0, y1, z1)], (0, 0, 1)),
        "-z": ([(x1, y0, z0), (x0, y0, z0), (x0, y1, z0), (x1, y1, z0)], (0, 0, -1)),
        "+x": ([(x1, y0, z1), (x1, y0, z0), (x1, y1, z0), (x1, y1, z1)], (1, 0, 0)),
        "-x": ([(x0, y0, z0), (x0, y0, z1), (x0, y1, z1), (x0, y1, z0)], (-1, 0, 0)),
        "+y": ([(x0, y1, z1), (x1, y1, z1), (x1, y1, z0), (x0, y1, z0)], (0, 1, 0)),
        "-y": ([(x0, y0, z0), (x1, y0, z0), (x1, y0, z1), (x0, y0, z1)], (0, -1, 0)),
    }


def uv_case(nom):
    """Coin haut-gauche et bas-droit d'une case, rentres d'un demi-pixel pour
    qu'aucun filtrage ne morde sur la case voisine."""
    col, ligne, _ = CASES[nom]
    u0 = (col * 16 + 0.5) / 64.0
    u1 = ((col + 1) * 16 - 0.5) / 64.0
    v0 = (ligne * 16 + 0.5) / 64.0
    v1 = ((ligne + 1) * 16 - 0.5) / 64.0
    return u0, v0, u1, v1


def construire_geometrie():
    positions, normales, uvs, joints, poids, indices = [], [], [], [], [], []
    for _, os_nom, mn, mx, habillage in BOITES:
        j = INDEX_OS[os_nom]
        for face, (sommets, normale) in faces(mn, mx).items():
            u0, v0, u1, v1 = uv_case(habillage[face])
            base = len(positions)
            # v du glTF descend : le bas de la case est vmax.
            for sommet, (u, v) in zip(sommets, [(u0, v1), (u1, v1), (u1, v0), (u0, v0)]):
                positions.append(sommet)
                normales.append(normale)
                uvs.append((u, v))
                joints.append((j, 0, 0, 0))
                poids.append((1.0, 0.0, 0.0, 0.0))
            indices.extend([base, base + 1, base + 2, base, base + 2, base + 3])
    return positions, normales, uvs, joints, poids, indices


def matrices_inverses():
    """L'inverse de la transformation globale de chaque os dans la pose de
    repos. Tous les os y sont sans rotation : c'est donc une translation."""
    globales = []
    for _, parent, t in OS:
        px, py, pz = globales[parent] if parent >= 0 else (0.0, 0.0, 0.0)
        globales.append((px + t[0], py + t[1], pz + t[2]))
    plat = []
    for gx, gy, gz in globales:
        # glTF range les matrices en colonnes.
        plat.extend([1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1, 0, -gx, -gy, -gz, 1])
    return plat


def quat_x(degres):
    a = math.radians(degres) / 2.0
    return (math.sin(a), 0.0, 0.0, math.cos(a))


# Marche : un cycle d'une seconde, bras et jambes en opposition -- le bras
# gauche part avec la jambe droite, comme une vraie demarche.
MARCHE = {
    "duree": 1.0,
    "temps": [0.0, 0.25, 0.5, 0.75, 1.0],
    "canaux": {
        "jambeG": [35, 0, -35, 0, 35],
        "jambeD": [-35, 0, 35, 0, -35],
        "brasG": [-28, 0, 28, 0, -28],
        "brasD": [28, 0, -28, 0, 28],
    },
}

# Repos : une respiration de deux secondes, assez ample pour qu'on voie que le
# personnage est vivant, assez faible pour qu'on ne la remarque pas.
REPOS = {
    "duree": 2.0,
    "temps": [0.0, 0.5, 1.0, 1.5, 2.0],
    "canaux": {
        "brasG": [0, -4, 0, 4, 0],
        "brasD": [0, 4, 0, -4, 0],
        "torse": [0, 1.5, 0, -1.5, 0],
    },
}


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


def ecrire_png(chemin):
    largeur = hauteur = 64
    pixels = [[(0, 0, 0, 0)] * largeur for _ in range(hauteur)]
    for nom, (col, ligne, couleur) in CASES.items():
        for y in range(ligne * 16, (ligne + 1) * 16):
            for x in range(col * 16, (col + 1) * 16):
                pixels[y][x] = couleur + (255,)
    # Le visage, seul detail dessine : deux yeux et une bouche.
    cx, cy, _ = CASES["visage"]
    ox, oy = cx * 16, cy * 16
    encre = (36, 26, 20, 255)
    for x in range(3, 6):
        for y in range(5, 8):
            pixels[oy + y][ox + x] = encre
    for x in range(10, 13):
        for y in range(5, 8):
            pixels[oy + y][ox + x] = encre
    for x in range(5, 11):
        pixels[oy + 11][ox + x] = (150, 88, 80, 255)

    brut = bytearray()
    for ligne_pixels in pixels:
        brut.append(0)  # filtre "aucun"
        for r, v, b, a in ligne_pixels:
            brut.extend((r, v, b, a))

    def morceau(nom, donnees):
        bloc = nom + donnees
        return struct.pack(">I", len(donnees)) + bloc + struct.pack(">I", zlib.crc32(bloc))

    png = b"\x89PNG\r\n\x1a\n"
    png += morceau(b"IHDR", struct.pack(">IIBBBBB", largeur, hauteur, 8, 6, 0, 0, 0))
    png += morceau(b"IDAT", zlib.compress(bytes(brut), 9))
    png += morceau(b"IEND", b"")
    chemin.write_bytes(png)
    return largeur, hauteur


def construire():
    positions, normales, uvs, joints, poids, indices = construire_geometrie()
    n = len(positions)

    tampon = Tampon()
    accessors = []

    def accessor(donnees, type_composant, type_glTF, compte, extras=None):
        vue = tampon.ajouter(donnees)
        a = {"bufferView": vue, "componentType": type_composant,
             "count": compte, "type": type_glTF}
        if extras:
            a.update(extras)
        accessors.append(a)
        return len(accessors) - 1

    mn = [min(p[i] for p in positions) for i in range(3)]
    mx = [max(p[i] for p in positions) for i in range(3)]

    a_pos = accessor(struct.pack("<%df" % (n * 3), *[c for p in positions for c in p]),
                     5126, "VEC3", n, {"min": mn, "max": mx})
    a_nor = accessor(struct.pack("<%df" % (n * 3), *[c for p in normales for c in p]),
                     5126, "VEC3", n)
    a_uv = accessor(struct.pack("<%df" % (n * 2), *[c for p in uvs for c in p]),
                    5126, "VEC2", n)
    a_joi = accessor(struct.pack("<%dH" % (n * 4), *[c for p in joints for c in p]),
                     5123, "VEC4", n)
    a_poi = accessor(struct.pack("<%df" % (n * 4), *[c for p in poids for c in p]),
                     5126, "VEC4", n)
    a_ind = accessor(struct.pack("<%dH" % len(indices), *indices),
                     5123, "SCALAR", len(indices))
    inverses = matrices_inverses()
    a_inv = accessor(struct.pack("<%df" % len(inverses), *inverses),
                     5126, "MAT4", len(OS))

    animations = []
    for nom, spec in (("marche", MARCHE), ("repos", REPOS)):
        temps = spec["temps"]
        a_temps = accessor(struct.pack("<%df" % len(temps), *temps),
                           5126, "SCALAR", len(temps),
                           {"min": [temps[0]], "max": [temps[-1]]})
        samplers, canaux = [], []
        for os_nom, angles in spec["canaux"].items():
            quats = [c for d in angles for c in quat_x(d)]
            a_rot = accessor(struct.pack("<%df" % len(quats), *quats),
                             5126, "VEC4", len(angles))
            samplers.append({"input": a_temps, "interpolation": "LINEAR", "output": a_rot})
            canaux.append({"sampler": len(samplers) - 1,
                           "target": {"node": 1 + INDEX_OS[os_nom], "path": "rotation"}})
        animations.append({"name": nom, "samplers": samplers, "channels": canaux})

    noeuds = [{"name": "heros", "mesh": 0, "skin": 0, "children": [1]}]
    for i, (nom, _, t) in enumerate(OS):
        noeud = {"name": nom, "translation": list(t)}
        enfants = [1 + j for j, (_, parent, _) in enumerate(OS) if parent == i]
        if enfants:
            noeud["children"] = enfants
        noeuds.append(noeud)

    gltf = {
        "asset": {"version": "2.0", "generator": "Evolve tools/heros/construire.py"},
        "scene": 0,
        "scenes": [{"nodes": [0]}],
        "nodes": noeuds,
        "meshes": [{
            "name": "heros",
            "primitives": [{
                "attributes": {"POSITION": a_pos, "NORMAL": a_nor, "TEXCOORD_0": a_uv,
                               "JOINTS_0": a_joi, "WEIGHTS_0": a_poi},
                "indices": a_ind,
                "mode": 4,
            }],
        }],
        "skins": [{"name": "squelette",
                   "joints": [1 + i for i in range(len(OS))],
                   "inverseBindMatrices": a_inv}],
        "accessors": accessors,
        "bufferViews": tampon.vues,
        "buffers": [{
            "byteLength": len(tampon.octets),
            "uri": "data:application/octet-stream;base64,"
                   + base64.b64encode(bytes(tampon.octets)).decode("ascii"),
        }],
        "animations": animations,
    }

    GLTF.parent.mkdir(parents=True, exist_ok=True)
    GLTF.write_text(json.dumps(gltf, indent=2), encoding="utf-8")
    largeur, hauteur = ecrire_png(PNG)

    print("%s : %d sommets, %d triangles, %d os, %d animations, %d o de tampon"
          % (GLTF.name, n, len(indices) // 3, len(OS), len(animations), len(tampon.octets)))
    print("%s : %dx%d" % (PNG.name, largeur, hauteur))


if __name__ == "__main__":
    construire()
