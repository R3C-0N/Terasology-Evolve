#!/usr/bin/env python3
# Copyright 2026 The Terasology Foundation
# SPDX-License-Identifier: Apache-2.0
"""Compose les textures d'interface a partir du moteur voxel de la maquette.

    python tools/ui/build_textures.py

Ce script ne DESSINE rien. Toute la matiere vient de `voxel.py`, port litteral
du `voxel.js` embarque dans le design system HeroCraft : le bois, la paroi, les
rondins, la ferronnerie, le ciel, le terrain et le lettrage du titre y sont
generes, pas decrits. Ici on ne fait que les assembler en planches 9 tranches et
les agrandir d'un facteur U = 3 (le `--u` de la maquette), au plus proche voisin.

DECOUPE 9 TRANCHES, EN MODE TUILE. `CanvasImpl.drawBackground` passe
`tile = (background-scale-mode == TILED)` a `drawTextureRawBordered`, et
`LwjglCanvasRenderer.drawTextureBordered` tuile alors les quatre bords ET le
centre. D'ou les deux regles qui commandent toutes les tailles ci-dessous :

  - le CENTRE devient lui-meme la tuile : sa decoupe doit donc etre une tuile
    sans couture a part entiere. Le bois se referme sur 48x24, la paroi sur
    48x48, le rondin sur 64 : une largeur de centre de 48 se repete proprement,
    une largeur de 42 non.
  - les quatre COINS sont toujours ETIRES, jamais tuiles. Sans consequence tant
    que la bordure vaut exactement la taille de l'art du coin — d'ou les 30
    texels du panneau, qui sont la taille d'`iron_corner`.

Les textures existantes sont reecrites SOUS LEUR NOM ACTUEL : les skins de
modules/Inventory, /Health et /Minimap — depots git distincts — pointent sur
`engine:area`, `engine:box` et `engine:boxActive`, et suivent donc la nouvelle
matiere sans qu'on aille les modifier.
"""
from __future__ import annotations

import json
import pathlib

from PIL import Image, ImageDraw, ImageEnhance

import voxel as v

ROOT = pathlib.Path(__file__).resolve().parents[2]
TEX = ROOT / "engine/src/main/resources/org/terasology/engine/assets/textures"
UI = TEX / "ui"
FONTCACHE = ROOT / "build/fontcache"

U = 3  # 1 texel = 3 pixels

P = v.P0
IRON = P["iron"]
WOOD = P["wood"]
WALL = P["woodWall"]
QUIT = P["woodQuit"]

# Semantiques reprises de tokens/semantic.css au palier 0.
HAIRLINE = "#20120a"   # --border-hairline = wood-800
INSET = "#3d200d"      # --surface-inset   = wood-700
SUNKEN = "#20120a"     # --surface-sunken  = wood-800
RAISED = "#a8672f"     # --surface-raised  = wood-300
ACCENT = "#f5c542"
ACCENT_STRONG = "#ffe08c"
ACCENT_DIM = "#b4802a"
MUTED = "#b49e7c"

# --bevel-raised / --bevel-sunken, en RGBA d'un texel.
BEV_HI_TOP = (255, 240, 210, 107)     # .42
BEV_HI_LEFT = (255, 240, 210, 56)     # .22
BEV_LO_BOT = (0, 0, 0, 102)           # .40
BEV_LO_RIGHT = (0, 0, 0, 66)          # .26
SUNK_TOP = (0, 0, 0, 140)             # .55
SUNK_LEFT = (0, 0, 0, 89)             # .35
SUNK_BOT = (255, 240, 210, 41)        # .16


# --- toile en texels ------------------------------------------------------


def canvas(w, h):
    return Image.new("RGBA", (int(w), int(h)), (0, 0, 0, 0))


def as_img(b):
    return v.to_image(b)


def tiled_layer(w, h, src, ox=0, oy=0):
    """Une couche pleine taille, tuilee, calee sur (ox, oy)."""
    layer = canvas(w, h)
    sw, sh = src.size
    y = oy - ((oy % sh) + sh) % sh - sh
    while y < h:
        x = ox - ((ox % sw) + sw) % sw - sw
        while x < w:
            layer.alpha_composite(src, (x, y))
            x += sw
        y += sh
    return layer.crop((0, 0, w, h))


def rect(dst, box, rgba):
    lay = canvas(dst.width, dst.height)
    ImageDraw.Draw(lay).rectangle(box, fill=rgba)
    dst.alpha_composite(lay)


def frame(dst, box, rgba, t=1):
    x0, y0, x1, y1 = box
    for i in range(t):
        lay = canvas(dst.width, dst.height)
        ImageDraw.Draw(lay).rectangle([x0 + i, y0 + i, x1 - i, y1 - i], outline=rgba)
        dst.alpha_composite(lay)


def solid(c, a=255):
    r, g, b = v.hex2rgb(c) if isinstance(c, str) else c[:3]
    return (r, g, b, a)


def bevel_raised(dst, box):
    x0, y0, x1, y1 = box
    rect(dst, [x0, y0, x1, y0], BEV_HI_TOP)
    rect(dst, [x0, y0, x0, y1], BEV_HI_LEFT)
    rect(dst, [x0, y1, x1, y1], BEV_LO_BOT)
    rect(dst, [x1, y0, x1, y1], BEV_LO_RIGHT)


def bevel_sunken(dst, box):
    x0, y0, x1, y1 = box
    rect(dst, [x0, y0, x1, y0], SUNK_TOP)
    rect(dst, [x0, y0, x0, y1], SUNK_LEFT)
    rect(dst, [x0, y1, x1, y1], SUNK_BOT)


def brightness(im, f):
    if f == 1.0:
        return im
    r, g, b, a = im.split()
    rgb = ImageEnhance.Brightness(Image.merge("RGB", (r, g, b))).enhance(f)
    return Image.merge("RGBA", rgb.split() + (a,))


def mirror(im, x=False, y=False):
    if x:
        im = im.transpose(Image.FLIP_LEFT_RIGHT)
    if y:
        im = im.transpose(Image.FLIP_TOP_BOTTOM)
    return im


def save(im, rel, scale=U):
    """Agrandit au plus proche voisin et ecrit le PNG plus son `.texinfo`."""
    path = (TEX / rel) if "/" in rel else (UI / rel)
    path.parent.mkdir(parents=True, exist_ok=True)
    if scale != 1:
        im = im.resize((im.width * scale, im.height * scale), Image.NEAREST)
    im.save(path)
    path.with_suffix(".texinfo").write_text(
        json.dumps({"filterMode": "Nearest"}, indent=4) + "\n", encoding="utf-8")
    return im


# --- matiere de base, generee une fois ------------------------------------

WOOD_TILE = as_img(v.wood_face(WOOD, 21))          # 48x24
WOOD_ALT = as_img(v.wood_face(QUIT, 33))           # 48x24
WALL_TILE = as_img(v.wood_wall(WALL, 9))           # 48x48
CORNER = as_img(v.iron_corner(IRON))               # 30x30
CORNER_SM = as_img(v.iron_corner(IRON, 2 / 3))     # 20x20
STRAP_H = as_img(v.iron_strap(IRON, False))        # 22x10
STRAP_V = as_img(v.iron_strap(IRON, True))         # 10x22
SPIKE = as_img(v.iron_spike(IRON))                 # 16x16
PLAQUE = as_img(v.plaque_tex(IRON))                # 16x18
SLOT = as_img(v.slot_tex(IRON))                    # 20x20
FIELD = as_img(v.slot_tex(IRON, 12))               # 12x12, pour les champs
CREST = as_img(v.gem_crest(IRON, P["gem"]))        # 16x14
CURL = as_img(v.magic_curl(P["magic"]))            # 20x20
LOG = {s: as_img(v.pine_log(s, P["pine"], s == "t", sd))
       for s, sd in (("t", 41), ("b", 53), ("l", 67), ("r", 79))}
LOG_SLIM = {s: as_img(v.pine_log(s, P["pine"], s == "t", sd, 8))
            for s, sd in (("t", 41), ("b", 53), ("l", 67), ("r", 79))}


# --- panneau --------------------------------------------------------------


def panel(border, thick, logs, corner, keyline, cap, span=192):
    """Le panneau de la maquette, replie dans un rectangle.

    Dans le design system les rondins DEBORDENT du panneau et les equerres sont
    posees plus loin encore ; une decoupe en neuf tranches ne sort pas du
    rectangle du widget, donc tout est ramene vers l'interieur. L'ordre de pose
    est celui de `Panel.jsx` : paroi, cadre sombre, rondins, equerres.
    """
    n = border * 2 + span
    im = canvas(n, n)
    inner = keyline + thick            # ou commence la paroi
    # Paroi de fond, bornee a la boite interieure. Tout ce qui est plus au bord
    # reste transparent : le contour dechiquete du rondin doit porter sur le
    # decor, pas sur un aplat. Le calage de la tuile se prend quand meme sur
    # `border`, sinon la decoupe du centre ne tomberait plus sur la tuile.
    wall = tiled_layer(n, n, WALL_TILE, border, border)
    im.alpha_composite(wall.crop((inner, inner, n - inner, n - inner)), (inner, inner))
    # liseré creusé de la boite interieure
    frame(im, [inner, inner, n - 1 - inner, n - 1 - inner], (0, 0, 0, 115))
    rect(im, [inner + 1, inner + 1, n - 2 - inner, inner + 2], (0, 0, 0, 71))
    # Rondins : horizontaux tuiles en x, verticaux tuiles en y, tous retenus de
    # `cap` texels a chaque bout pour que leur about tombe SOUS le galbe de la
    # ferrure au lieu d'en ressortir. On recadre le calque deja tuile : re-tuiler
    # a partir du retrait decalerait la phase, et la couture reviendrait.
    top = tiled_layer(n, thick, logs["t"], 0, 0).crop((cap, 0, n - cap, thick))
    bot = tiled_layer(n, thick, logs["b"], -19, 0).crop((cap, 0, n - cap, thick))
    im.alpha_composite(top, (cap, keyline))
    im.alpha_composite(bot, (cap, n - keyline - thick))
    left = tiled_layer(thick, n, logs["l"], 0, 0).crop((0, cap, thick, n - cap))
    right = tiled_layer(thick, n, logs["r"], 0, -30).crop((0, cap, thick, n - cap))
    im.alpha_composite(left, (keyline, cap))
    im.alpha_composite(right, (n - keyline - thick, cap))
    # equerres aux quatre angles
    cw = corner.size[0]
    im.alpha_composite(corner, (0, 0))
    im.alpha_composite(mirror(corner, x=True), (n - cw, 0))
    im.alpha_composite(mirror(corner, y=True), (0, n - cw))
    im.alpha_composite(mirror(corner, x=True, y=True), (n - cw, n - cw))
    return im


# --- bouton ---------------------------------------------------------------


def button(wood, w=54, h=32, bl=3, plank_top=0, drop=2, socket=False, lit=True):
    """Planche de bois biseautee, posee sur son arete.

    Le centre decoupe fait exactement 48 texels de large — la periode de la
    tuile de bois — sinon la repetition laisserait une couture par bouton large.
    """
    im = canvas(w, h)
    im.alpha_composite(tiled_layer(w, h, wood, bl, plank_top + 2))
    pb = h - 1 - drop * 2          # derniere ligne de la planche
    # ce qui depasse de la planche est transparent
    if plank_top:
        ImageDraw.Draw(im).rectangle([0, 0, w - 1, plank_top - 1], fill=(0, 0, 0, 0))
    ImageDraw.Draw(im).rectangle([0, pb + 1, w - 1, h - 1], fill=(0, 0, 0, 0))
    bevel_raised(im, [1, plank_top + 1, w - 2, pb - 1])
    frame(im, [0, plank_top, w - 1, pb], solid(HAIRLINE))
    # arete : la tranche de la planche, puis son ombre portee
    rect(im, [0, pb + 1, w - 1, pb + drop], solid(HAIRLINE))
    rect(im, [0, pb + 1 + drop, w - 1, pb + drop * 2], (0, 0, 0, 56))
    if socket:
        cy = (plank_top + pb) // 2
        sx, sy = 2, cy - 3
        rect(im, [sx, sy, sx + 6, sy + 6], solid(IRON[1]))
        rect(im, [sx, sy, sx + 6, sy], solid(IRON[3]))
        frame(im, [sx, sy, sx + 6, sy + 6], solid(HAIRLINE))
        rect(im, [sx + 2, sy + 2, sx + 4, sy + 4],
             solid(ACCENT if lit else HAIRLINE))
    return im


# --- cuvettes -------------------------------------------------------------


def well(face, w=20, h=20, accent=None):
    """Cuvette creusee : le casier de la maquette, etire ou aplani."""
    im = canvas(w, h)
    rect(im, [0, 0, w - 1, h - 1], solid(face))
    frame(im, [0, 0, w - 1, h - 1], solid(HAIRLINE))
    bevel_sunken(im, [1, 1, w - 2, h - 2])
    if accent:
        frame(im, [1, 1, w - 2, h - 2], solid(accent))
    return im


def slot_field(accent=None, disabled=False):
    """Le champ de saisie : la cuvette de 12 texels, centre uni."""
    im = FIELD.copy()
    if disabled:
        im = brightness(im, .72)
    if accent:
        frame(im, [1, 1, im.width - 2, im.height - 2], solid(accent))
    return im


# --- lignes de liste ------------------------------------------------------


def list_row(selected=False, hover=False, disabled=False):
    w, h, rail = 24, 24, 2
    im = canvas(w, h)
    if selected:
        rect(im, [0, 0, w - 1, h - 1], solid(RAISED))
        bevel_raised(im, [0, 0, w - 1, h - 1])
        rect(im, [0, 0, rail - 1, h - 1], solid(ACCENT))
        rect(im, [rail, 0, rail, h - 1], solid(HAIRLINE))
    else:
        rect(im, [0, 0, w - 1, h - 1], solid(INSET))
        bevel_sunken(im, [0, 0, w - 1, h - 1])
        if hover:
            im = brightness(im, 1.18)
        if disabled:
            im = brightness(im, .8)
    return im


# --- onglets --------------------------------------------------------------


def tab(active=False):
    w, h, bt = 56, 24, 6
    im = canvas(w, h)
    src = WOOD_TILE if active else WALL_TILE
    im.alpha_composite(tiled_layer(w, h, src, 4, bt))
    if active:
        bevel_raised(im, [1, 1, w - 2, h - 1])
        rect(im, [1, 1, w - 2, 2], solid(ACCENT))
    else:
        bevel_sunken(im, [1, 1, w - 2, h - 1])
        rect(im, [1, h - 2, w - 2, h - 1], solid(HAIRLINE))
    rect(im, [0, 0, w - 1, 0], solid(HAIRLINE))
    rect(im, [0, 0, 0, h - 1], solid(HAIRLINE))
    rect(im, [w - 1, 0, w - 1, h - 1], solid(HAIRLINE))
    return im


# --- plaque de titre ------------------------------------------------------


def plaque():
    """Cartouche de fer : plaque 9 tranches, une pointe repliee dans chaque
    bordure laterale. Un morceau de bordure est trace une fois, donc la pointe
    y survit intacte — elle ne serait jamais tuilable autrement."""
    ph = 20
    w = 16 * 3
    im = canvas(w, ph)
    body = canvas(16, ph)
    body.alpha_composite(PLAQUE, (0, (ph - PLAQUE.height) // 2))
    im.alpha_composite(body, (16, 0))
    im.alpha_composite(SPIKE, (0, (ph - 16) // 2))
    im.alpha_composite(mirror(SPIKE, x=True), (32, (ph - 16) // 2))
    return im


# --- champs a plaque de fer (liste deroulante, champ reinitialisable) -----


def iron_field(arrow=True, disabled=False, face=None):
    w, h, plate = 44, 12, 14
    im = canvas(w, h)
    rect(im, [0, 0, w - 1, h - 1], solid(face or SUNKEN))
    frame(im, [0, 0, w - 1, h - 1], solid(HAIRLINE))
    bevel_sunken(im, [1, 1, w - 2, h - 2])
    px0 = w - plate
    rect(im, [px0, 1, w - 2, h - 2], solid(IRON[1]))
    rect(im, [px0, 1, w - 2, 1], solid(IRON[2]))
    rect(im, [px0, h - 2, w - 2, h - 2], solid(IRON[0]))
    rect(im, [px0, 1, px0, h - 2], solid(IRON[0]))
    if arrow:
        cx, cy = px0 + plate // 2 - 1, h // 2
        col = solid(IRON[3] if not disabled else IRON[2])
        for i in range(3):
            rect(im, [cx - 2 + i, cy - 1 + i, cx + 2 - i, cy - 1 + i], col)
    if disabled:
        im = brightness(im, .78)
    return im


# --- decor ----------------------------------------------------------------


def backdrop(dim=False):
    """Le fond de l'artboard 2a : ciel tuile en x cale en bas, chaine de
    montagnes a 40 texels du sol, terrain en bas."""
    w, h = 1920 // U, 1080 // U          # 640 x 360 texels
    world = P["world"]
    sky = as_img(v.sky_tex(world, 4, astre=False))
    rng_ = as_img(v.mountain_tex(world, 17))
    ground = as_img(v.terrain_tex(world, 7))
    im = canvas(w, h)
    rect(im, [0, 0, w - 1, h - 1], solid(world["skyTop"]))
    sky_y = h - sky.height
    im.alpha_composite(tiled_layer(w, sky.height, sky), (0, sky_y))
    # un seul soleil, pose apres le ciel : dans la tuile il se repeterait
    ax, ay, _ = v.ASTRE
    disc = v.bmp(w, h)
    v.paint_astre(disc, world, ax, sky_y + ay)
    im.alpha_composite(as_img(disc))
    im.alpha_composite(tiled_layer(w, rng_.height, rng_), (0, h - 40 - rng_.height))
    im.alpha_composite(tiled_layer(w, ground.height, ground), (0, h - ground.height))
    if dim:
        rect(im, [0, 0, w - 1, h - 1], (32, 18, 10, 219))   # --scrim, .86
    return im


def title_mark():
    """Le cartouche de titre : le mot en voxels, le ruban de magie derriere,
    une volute a chaque bout, et le filet pointe-cimier-pointe dessous."""
    ttf = FONTCACHE / "UncialAntiqua-Regular.ttf"
    if not ttf.exists():
        raise SystemExit("fonte absente : lancer d'abord tools/ui/build_fonts.py")
    mark = as_img(v.text_voxel("Terasology", 38, P["logo"], ttf))
    flux = as_img(v.flux_tex(P["flux"], 7))
    rule_w = min(200, mark.width)
    w = mark.width + 2 * 20
    band = 40
    h = band + 4 + 16
    im = canvas(w, h)
    # ruban, une passe pleine derriere le mot
    im.alpha_composite(tiled_layer(w, band, flux), (0, 0))
    im.alpha_composite(mark, ((w - mark.width) // 2, (band - mark.height) // 2))
    im.alpha_composite(mirror(CURL, x=True), (0, (band - 20) // 2))
    im.alpha_composite(CURL, (w - 20, (band - 20) // 2))
    # filet : pointe — barre — cimier — barre — pointe
    y = band + 4
    cx = w // 2
    im.alpha_composite(SPIKE, (cx - rule_w // 2 - 16, y + 1))
    im.alpha_composite(mirror(SPIKE, x=True), (cx + rule_w // 2, y + 1))
    im.alpha_composite(CREST, (cx - 8, y + 1))
    for x0, x1 in ((cx - rule_w // 2, cx - 9), (cx + 8, cx + rule_w // 2 - 1)):
        rect(im, [x0, y + 6, x1, y + 6], solid(HAIRLINE))
        rect(im, [x0, y + 7, x1, y + 7], solid(IRON[3]))
        rect(im, [x0, y + 8, x1, y + 8], solid(IRON[2]))
        rect(im, [x0, y + 9, x1, y + 9], solid(HAIRLINE))
    return im


# --- assemblage -----------------------------------------------------------


def main():
    UI.mkdir(parents=True, exist_ok=True)

    # panneaux
    save(panel(border=30, thick=12, logs=LOG, corner=CORNER, keyline=3, cap=7),
         "panel.png")
    save(panel(border=20, thick=8, logs=LOG_SLIM, corner=CORNER_SM, keyline=2, cap=5),
         "panelSlim.png")

    # boutons : primaire, secondaire, danger, plus la variante du menu a socle
    for name, wood, f in (("button", WOOD_TILE, 1.0),
                          ("buttonSecondary", WOOD_TILE, .88),
                          ("buttonDanger", WOOD_ALT, 1.0)):
        save(brightness(button(wood), f), name + ".png")
        save(brightness(button(wood), f * 1.10), name + "Over.png")
        save(brightness(button(wood, plank_top=2, drop=1), f * .94), name + "Down.png")
    save(brightness(button(WOOD_TILE), .55), "buttonDisabled.png")

    for name, wood, lit, f in (("buttonMenu", WOOD_TILE, True, 1.0),
                               ("buttonMenuOff", WOOD_TILE, False, .88),
                               ("buttonMenuDanger", WOOD_ALT, False, 1.0)):
        base = dict(w=63, h=16, bl=12, socket=True, lit=lit)
        save(brightness(button(wood, **base), f), name + ".png")
        save(brightness(button(wood, **base), f * 1.10), name + "Over.png")
        save(brightness(button(wood, plank_top=2, drop=1, **base), f * .94), name + "Down.png")

    # champs et cuvettes
    save(slot_field(), "box.png")
    save(slot_field(accent=ACCENT), "boxActive.png")
    save(slot_field(disabled=True), "boxDisabled.png")
    save(well(INSET, 20, 20), "area.png")
    save(well(SUNKEN, 20, 20), "panelSunken.png")
    save(well(SUNKEN, 8, 8), "sliderTrack.png")

    # lignes de liste
    save(list_row(), "listItem.png")
    save(list_row(hover=True), "listItemHover.png")
    save(list_row(selected=True), "listItemSelected.png")
    save(list_row(selected=True), "dropdownListItemActive.png")
    save(well(SUNKEN, 20, 20), "dropdownList.png")

    # onglets
    save(tab(False), "tab.png")
    save(tab(True), "tabActive.png")

    # plaque de titre
    save(plaque(), "plaque.png")

    # listes deroulantes et champs reinitialisables
    save(iron_field(True), "dropdown.png")
    save(iron_field(True, face=INSET), "dropdownActive.png")
    save(iron_field(True, disabled=True), "dropdownDisabled.png")
    save(iron_field(False), "resetBox.png")
    save(iron_field(False, disabled=True), "resetBoxDisabled.png")

    # cases a cocher : une cuvette de 12 texels, plus une coche d'accent.
    # `slot_tex` fait 20 texels : agrandi trois fois, la case ferait 60 px.
    for name, checked, f in (("checkbox", False, 1.0), ("checkboxHover", False, 1.14),
                             ("checkboxChecked", True, 1.0), ("checkboxCheckedHover", True, 1.14),
                             ("checkboxDisabled", False, .7), ("checkboxCheckedDisabled", True, .7)):
        im = well(SUNKEN, 12, 12)
        for x, y in ((1, 1), (10, 1), (1, 10), (10, 10)):
            im.alpha_composite(as_img(v.iron_stud(IRON)).crop((0, 0, 2, 2)), (x, y))
        if checked:
            col = solid(ACCENT if f > .8 else MUTED)
            for x, y in ((3, 6), (4, 7), (5, 8), (6, 7), (7, 6), (8, 5)):
                rect(im, [x, y, x, y + 1], col)
        save(brightness(im, f), name + ".png")

    # poignee d'ascenseur : un bossage de fer, 6 texels
    handle = well(SUNKEN, 6, 6)
    rect(handle, [1, 1, 4, 4], solid(IRON[1]))
    rect(handle, [1, 1, 4, 1], solid(IRON[2]))
    rect(handle, [1, 4, 4, 4], solid(IRON[0]))
    save(handle, "handle.png")

    # barre d'etat
    bar = canvas(44, 6)
    rect(bar, [0, 0, 43, 5], solid(SUNKEN))
    frame(bar, [0, 0, 43, 5], solid(HAIRLINE))
    bevel_sunken(bar, [1, 1, 42, 4])
    save(bar, "statusBar.png")

    # decor
    save(backdrop(False), "menuBackdrop.png")
    save(backdrop(True), "menuBackdropDim.png")
    save(title_mark(), "titleMark.png")

    print("textures ecrites dans", UI)


if __name__ == "__main__":
    main()
