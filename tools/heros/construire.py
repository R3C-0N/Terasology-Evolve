#!/usr/bin/env python3
"""Construit le corps du heros : un glTF squelette et sa texture.

Le depot n'avait aucun modele de personnage -- le seul visuel existant etait un
cube flottant de 0,25 bloc. Ce script produit un humanoide en boites, sept os,
deux animations, et la peau qui va avec :

    engine/src/main/resources/org/terasology/engine/assets/skeletalMesh/heros.gltf
    engine/src/main/resources/org/terasology/engine/assets/textures/heros.png

Pourquoi un script et non un fichier ecrit a la main : le glTF est un format
binaire enrobe de JSON, ou chaque `byteLength` doit tomber juste et ou une
retouche de proportion redistribue tous les decalages. Le regenerer coute une
seconde, le corriger a la main coute une seance.

LE PATRON EST CELUI DE MINECRAFT, A LA LETTRE. Une peau 64x64 telechargee
n'importe ou se pose donc telle quelle sur ce modele. Cela impose les
proportions -- tete 8x8x8, torse 8x12x4, membres 4x4x12, soit 32 unites de haut
pour 1,8 bloc -- et le decoupage du patron :

           0        16       32       48       64
        0  +--------+--------+--------+--------+
           | tete   couche 1 | tete   couche 2 |
       16  +--------+--------+--------+--------+
           |jambe D | torse           |bras D  |
       32  +--------+--------+--------+--------+
           |jambe D2| torse 2         |bras D2 |
       48  +--------+--------+--------+--------+
           |jambe G2|jambe G |bras G  |bras G2 |
       64  +--------+--------+--------+--------+

Chaque bloc de 16 est le depliage d'une boite, dans l'ordre canonique :
en haut `[dessus][dessous]`, en dessous `[droite][devant][gauche][derriere]`.

DEUX COUCHES. Les six boites de la couche 2 reprennent les memes os et les
memes pivots que celles de la couche 1, gonflees de 0,5 unite pour le crane et
0,25 pour le reste -- exactement les valeurs de Minecraft. Ce qui est
transparent dans la couche 2 laisse voir la couche 1 : c'est de la vraie
profondeur, pas un trompe-l'oeil, et le back-face culling (actif, `GL_BACK`)
fait disparaitre l'interieur de la coque creuse.

La decoupe passe par un `discard` dans genericMeshMaterial_frag.glsl, arme par
le parametre `alphaThreshold` de herosPeau.mat. Elle ne peut PAS passer par un
melange alpha : la passe opaque est rendue sans `GL_BLEND`, et surtout le canal
alpha du G-buffer opaque n'est pas une transparence -- `lightBufferPass` le lit
comme un facteur d'occlusion et multiplie le RGB par lui. Un alpha de texture
laisse tel quel noircirait le heros au lieu de le percer.

Ce que le chargeur de Terasology impose, et qu'il ne pardonne pas :

  * un seul maillage, une seule primitive, `mode` 4 -- tout le corps est donc
    une seule primitive et une seule texture, couche 2 comprise ;
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

Usage :
    python tools/heros/construire.py             # le maillage, et la peau si absente
    python tools/heros/construire.py --texture   # reecrit AUSSI la peau
"""

import base64
import json
import math
import pathlib
import struct
import sys
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
#
# Tout le reste se compte en UNITES de patron, dont il y a 32 sur la hauteur du
# personnage. C'est la seule facon de rester fidele au patron : une peau
# Minecraft suppose que la tete fait 8 unites de cote, pas 0,45 bloc.

HAUTEUR = 1.8
UNITE = HAUTEUR / 32.0          # 0,05625 bloc
PIED = -HAUTEUR / 2.0           # -0,90

HANCHE_U = 12                   # sommet des jambes, base du torse
EPAULE_U = 24                   # sommet du torse, base du crane


def bloc(unites):
    return unites * UNITE


def hauteur_de(unites):
    """Ordonnee du modele pour une hauteur comptee depuis les pieds."""
    return PIED + bloc(unites)


# ------------------------------------------------------------------- squelette
#
# (nom, parent, translation locale). Chaque os est pose sur son pivot : les bras
# a l'epaule, les jambes a la hanche -- c'est ce qui fait qu'une rotation les
# fait balancer au lieu de les deplacer.
#
# Le heros regarde vers +z, donc sa main DROITE est du cote -x.

BRAS_X_U = 6                    # 4 de demi-torse + 2 de demi-bras
JAMBE_X_U = 2                   # les deux jambes se touchent au milieu

OS = [
    ("bassin", -1, (0.0, hauteur_de(HANCHE_U), 0.0)),
    ("torse", 0, (0.0, 0.0, 0.0)),
    ("tete", 1, (0.0, bloc(EPAULE_U - HANCHE_U), 0.0)),
    ("brasG", 1, (bloc(BRAS_X_U), bloc(EPAULE_U - HANCHE_U), 0.0)),
    ("brasD", 1, (-bloc(BRAS_X_U), bloc(EPAULE_U - HANCHE_U), 0.0)),
    ("jambeG", 0, (bloc(JAMBE_X_U), 0.0, 0.0)),
    ("jambeD", 0, (-bloc(JAMBE_X_U), 0.0, 0.0)),
]
INDEX_OS = {nom: i for i, (nom, _, _) in enumerate(OS)}

# ----------------------------------------------------------------- les boites
#
# (nom, os, centre x, bas, largeur, hauteur, profondeur, coin du patron,
#  gonflement). Tout en unites de patron. Le gonflement est ce qui ecarte la
# couche 2 de la couche 1 : 0,5 pour le crane, 0,25 partout ailleurs, comme
# Minecraft.

TETE = (8, 8, 8)                # largeur, hauteur, profondeur
TORSE = (8, 12, 4)
MEMBRE = (4, 12, 4)

BOITES = [
    # -- couche 1, le corps
    ("tete", "tete", 0, EPAULE_U, TETE, (0, 0), 0.0),
    ("torse", "torse", 0, HANCHE_U, TORSE, (16, 16), 0.0),
    ("brasD", "brasD", -BRAS_X_U, HANCHE_U, MEMBRE, (40, 16), 0.0),
    ("brasG", "brasG", BRAS_X_U, HANCHE_U, MEMBRE, (32, 48), 0.0),
    ("jambeD", "jambeD", -JAMBE_X_U, 0, MEMBRE, (0, 16), 0.0),
    ("jambeG", "jambeG", JAMBE_X_U, 0, MEMBRE, (16, 48), 0.0),
    # -- couche 2, ce qu'on porte par-dessus
    ("tete2", "tete", 0, EPAULE_U, TETE, (32, 0), 0.5),
    ("torse2", "torse", 0, HANCHE_U, TORSE, (16, 32), 0.25),
    ("brasD2", "brasD", -BRAS_X_U, HANCHE_U, MEMBRE, (40, 32), 0.25),
    ("brasG2", "brasG", BRAS_X_U, HANCHE_U, MEMBRE, (48, 48), 0.25),
    ("jambeD2", "jambeD", -JAMBE_X_U, 0, MEMBRE, (0, 32), 0.25),
    ("jambeG2", "jambeG", JAMBE_X_U, 0, MEMBRE, (0, 48), 0.25),
]

TAILLE = 64                     # le patron est carre, 64 de cote


def etendue(centre_x, bas, dims, gonflement):
    """Les coins min et max d'une boite, en blocs."""
    largeur, haut, profondeur = dims
    g = bloc(gonflement)
    return (
        (bloc(centre_x - largeur / 2.0) - g, hauteur_de(bas) - g, bloc(-profondeur / 2.0) - g),
        (bloc(centre_x + largeur / 2.0) + g, hauteur_de(bas + haut) + g, bloc(profondeur / 2.0) + g),
    )


def rect_face(coin, dims, face):
    """Le rectangle du patron, en pixels, ou se lit une face.

    C'est le depliage canonique de Minecraft : la rangee du haut porte le
    dessus puis le dessous, la rangee du bas fait le tour de la boite --
    droite, devant, gauche, derriere -- dans cet ordre.
    """
    u, v = coin
    w, h, d = dims
    return {
        "+y": (u + d, v, w, d),                 # dessus
        "-y": (u + d + w, v, w, d),             # dessous
        "-x": (u, v + d, d, h),                 # la droite du heros
        "+z": (u + d, v + d, w, h),             # devant
        "+x": (u + d + w, v + d, d, h),         # sa gauche
        "-z": (u + d + w + d, v + d, w, h),     # derriere
    }[face]


# Le filtrage des textures de `assets/textures/` est NEAREST (LwjglGraphicsManager),
# donc aucune couleur ne bave d'une case sur l'autre et on peut coller aux bords
# du rectangle. Le centieme de pixel n'est la que pour qu'un flottant ne tombe
# jamais pile sur la frontiere, ou le texel choisi serait indecidable.
MARGE = 0.01


def uv_face(coin, dims, face):
    x, y, w, h = rect_face(coin, dims, face)
    return ((x + MARGE) / TAILLE, (y + MARGE) / TAILLE,
            (x + w - MARGE) / TAILLE, (y + h - MARGE) / TAILLE)


def faces(mn, mx):
    """Les six faces d'une boite. Les quatre sommets sont donnes dans l'ordre
    bas-gauche, bas-droite, haut-droite, haut-gauche VUS DE L'EXTERIEUR, ce qui
    donne un enroulement direct -- et, cadeau, c'est exactement l'ordre qu'il
    faut pour poser dessus les coins (0,1) (1,1) (1,0) (0,0) du rectangle du
    patron, sur les six faces sans exception."""
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


def construire_geometrie():
    positions, normales, uvs, joints, poids, indices = [], [], [], [], [], []
    for _, os_nom, centre_x, bas, dims, coin, gonflement in BOITES:
        j = INDEX_OS[os_nom]
        mn, mx = etendue(centre_x, bas, dims, gonflement)
        for face, (sommets, normale) in faces(mn, mx).items():
            u0, v0, u1, v1 = uv_face(coin, dims, face)
            base = len(positions)
            # v du glTF descend : le bas du rectangle est vmax.
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


# ------------------------------------------------------------------- la peau
#
# Une peau de depart, pas une oeuvre : elle est la pour montrer que les deux
# couches se superposent vraiment, et pour servir de gabarit a qui voudra
# peindre par-dessus. Couche 1, le corps ; couche 2, ce qu'il porte.

PEAU = (224, 172, 105)
BLANC = (238, 238, 238)
ENCRE = (36, 26, 20)
BOUCHE = (150, 88, 80)
CHEVEUX = (74, 49, 33)
TUNIQUE = (62, 122, 78)
MANCHE = (51, 99, 65)
PANTALON = (49, 66, 94)
CUIR = (92, 60, 38)
CUIR_CLAIR = (126, 86, 54)

COTES = ("-x", "+z", "+x", "-z")

# (boite, face, couleur, zone). La zone est un rectangle en coordonnees locales
# a la face -- (x, y, largeur, hauteur), y compte depuis le HAUT de la boite --
# ou None pour la face entiere.
PEINTURE = [
    # -- couche 1 : la tete, nue, avec un visage devant
    ("tete", "+y", PEAU, None), ("tete", "-y", PEAU, None),
    ("tete", "-x", PEAU, None), ("tete", "+x", PEAU, None),
    ("tete", "+z", PEAU, None), ("tete", "-z", PEAU, None),
    ("tete", "+z", BLANC, (1, 3, 2, 1)), ("tete", "+z", BLANC, (5, 3, 2, 1)),
    ("tete", "+z", ENCRE, (2, 3, 1, 1)), ("tete", "+z", ENCRE, (5, 3, 1, 1)),
    ("tete", "+z", BOUCHE, (3, 5, 2, 1)),
    # -- couche 1 : la tunique, ceinturee
    ("torse", "+y", TUNIQUE, None), ("torse", "-y", TUNIQUE, None),
    ("torse", "-x", TUNIQUE, None), ("torse", "+x", TUNIQUE, None),
    ("torse", "+z", TUNIQUE, None), ("torse", "-z", TUNIQUE, None),
    ("torse", "-x", CUIR, (0, 10, 4, 2)), ("torse", "+x", CUIR, (0, 10, 4, 2)),
    ("torse", "+z", CUIR, (0, 10, 8, 2)), ("torse", "-z", CUIR, (0, 10, 8, 2)),
    # -- couche 1 : les bras, manche puis avant-bras nu, main en dessous
    ("bras", "+y", MANCHE, None), ("bras", "-y", PEAU, None),
    ("bras", "-x", MANCHE, None), ("bras", "+x", MANCHE, None),
    ("bras", "+z", MANCHE, None), ("bras", "-z", MANCHE, None),
    ("bras", "-x", PEAU, (0, 8, 4, 4)), ("bras", "+x", PEAU, (0, 8, 4, 4)),
    ("bras", "+z", PEAU, (0, 8, 4, 4)), ("bras", "-z", PEAU, (0, 8, 4, 4)),
    # -- couche 1 : les jambes, en pantalon jusqu'en bas
    ("jambe", "+y", PANTALON, None), ("jambe", "-y", PANTALON, None),
    ("jambe", "-x", PANTALON, None), ("jambe", "+x", PANTALON, None),
    ("jambe", "+z", PANTALON, None), ("jambe", "-z", PANTALON, None),
]

# La couche 2, ou tout ce qui n'est pas peint reste transparent -- et laisse
# donc voir la couche 1, en retrait.
PEINTURE_COUCHE2 = [
    # -- les cheveux : la calotte entiere, et une frange devant
    ("tete2", "+y", CHEVEUX, None), ("tete2", "-z", CHEVEUX, None),
    ("tete2", "-x", CHEVEUX, None), ("tete2", "+x", CHEVEUX, None),
    ("tete2", "+z", CHEVEUX, (0, 0, 8, 2)),
    # -- un manteau ouvert : ferme derriere et sur les flancs, deux pans devant
    ("torse2", "+y", CUIR, None), ("torse2", "-z", CUIR, None),
    ("torse2", "-x", CUIR, None), ("torse2", "+x", CUIR, None),
    ("torse2", "+z", CUIR, (0, 0, 2, 12)), ("torse2", "+z", CUIR, (6, 0, 2, 12)),
    ("torse2", "+z", CUIR_CLAIR, (2, 0, 4, 2)),
    # -- une spalliere sur le haut du bras
    ("bras2", "+y", CUIR_CLAIR, None),
    ("bras2", "-x", CUIR_CLAIR, (0, 0, 4, 4)), ("bras2", "+x", CUIR_CLAIR, (0, 0, 4, 4)),
    ("bras2", "+z", CUIR_CLAIR, (0, 0, 4, 4)), ("bras2", "-z", CUIR_CLAIR, (0, 0, 4, 4)),
    # -- une botte montante
    ("jambe2", "-y", CUIR, None),
    ("jambe2", "-x", CUIR, (0, 7, 4, 5)), ("jambe2", "+x", CUIR, (0, 7, 4, 5)),
    ("jambe2", "+z", CUIR, (0, 7, 4, 5)), ("jambe2", "-z", CUIR, (0, 7, 4, 5)),
    ("jambe2", "+z", CUIR_CLAIR, (0, 7, 4, 1)), ("jambe2", "-z", CUIR_CLAIR, (0, 7, 4, 1)),
]

# `bras` et `jambe` valent pour les deux cotes : une peau symetrique est ce
# qu'on veut par defaut, et qui voudra les differencier a deux blocs distincts.
SYMETRIQUES = {
    "bras": ("brasD", "brasG"), "jambe": ("jambeD", "jambeG"),
    "bras2": ("brasD2", "brasG2"), "jambe2": ("jambeD2", "jambeG2"),
}

PAR_NOM = {nom: (dims, coin) for nom, _, _, _, dims, coin, _ in BOITES}


def ecrire_png(chemin):
    pixels = [[(0, 0, 0, 0)] * TAILLE for _ in range(TAILLE)]

    for boite, face, couleur, zone in PEINTURE + PEINTURE_COUCHE2:
        for nom in SYMETRIQUES.get(boite, (boite,)):
            dims, coin = PAR_NOM[nom]
            x0, y0, w, h = rect_face(coin, dims, face)
            zx, zy, zw, zh = zone if zone else (0, 0, w, h)
            for y in range(y0 + zy, y0 + zy + zh):
                for x in range(x0 + zx, x0 + zx + zw):
                    pixels[y][x] = couleur + (255,)

    brut = bytearray()
    for ligne_pixels in pixels:
        brut.append(0)  # filtre "aucun"
        for r, v, b, a in ligne_pixels:
            brut.extend((r, v, b, a))

    def morceau(nom, donnees):
        bloc_png = nom + donnees
        return struct.pack(">I", len(donnees)) + bloc_png + struct.pack(">I", zlib.crc32(bloc_png))

    png = b"\x89PNG\r\n\x1a\n"
    png += morceau(b"IHDR", struct.pack(">IIBBBBB", TAILLE, TAILLE, 8, 6, 0, 0, 0))
    png += morceau(b"IDAT", zlib.compress(bytes(brut), 9))
    png += morceau(b"IEND", b"")
    chemin.write_bytes(png)


def construire(reecrire_texture):
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

    print("%s : %d sommets, %d triangles, %d boites, %d os, %d animations, %d o de tampon"
          % (GLTF.name, n, len(indices) // 3, len(BOITES), len(OS), len(animations),
             len(tampon.octets)))

    # La peau, elle, se peint a la main : on ne l'ecrase jamais sans le dire.
    if reecrire_texture or not PNG.exists():
        PNG.parent.mkdir(parents=True, exist_ok=True)
        ecrire_png(PNG)
        print("%s : %dx%d, patron Minecraft, deux couches" % (PNG.name, TAILLE, TAILLE))
    else:
        print("%s : laissee telle quelle (--texture pour la reecrire)" % PNG.name)


if __name__ == "__main__":
    construire("--texture" in sys.argv[1:])
