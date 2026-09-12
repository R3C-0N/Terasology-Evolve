#!/usr/bin/env python3
# Copyright 2026 The Terasology Foundation
# SPDX-License-Identifier: Apache-2.0
"""Cuit les fontes bitmap du design system HeroCraft.

Terasology ne lit que du BMFont (`.fnt` texte + pages PNG), jamais du TTF :
voir engine/src/main/java/org/terasology/engine/rendering/assets/font/FontFormat.java.
Ce script telecharge les TTF Google Fonts (OFL), rastérise le jeu de caracteres
latin et ecrit les `.fnt` + atlas dans assets/fonts/.

    python tools/ui/build_fonts.py

Les TTF sont mis en cache hors du depot (build/fontcache/).
"""
from __future__ import annotations

import argparse
import pathlib
import urllib.request

from PIL import Image, ImageDraw, ImageFont

ROOT = pathlib.Path(__file__).resolve().parents[2]
OUT = ROOT / "engine/src/main/resources/org/terasology/engine/assets/fonts"
CACHE = ROOT / "build/fontcache"

GF = "https://raw.githubusercontent.com/google/fonts/main/ofl"
SOURCES = {
    "UncialAntiqua-Regular.ttf": f"{GF}/uncialantiqua/UncialAntiqua-Regular.ttf",
    "Grenze[wght].ttf": f"{GF}/grenze/Grenze%5Bwght%5D.ttf",
    "UncialAntiqua-OFL.txt": f"{GF}/uncialantiqua/OFL.txt",
    "Grenze-OFL.txt": f"{GF}/grenze/OFL.txt",
}

PAGE = 512
PAD = 1

# Latin de base, puis ce dont le francais et les autres locales latines ont besoin.
CHARSET = (
    "".join(chr(c) for c in range(32, 127))
    + "\u00a0\u00a1\u00bf\u00ab\u00bb\u2018\u2019\u201c\u201d\u2013\u2014\u2026"
    + "\u00b7\u2022\u00b0\u00b1\u00d7\u00f7\u20ac\u00a3\u00a7\u00a9\u00ae\u2122"
    + "\u00c0\u00c1\u00c2\u00c3\u00c4\u00c5\u00c6\u00c7\u00c8\u00c9\u00ca\u00cb"
    + "\u00cc\u00cd\u00ce\u00cf\u00d1\u00d2\u00d3\u00d4\u00d5\u00d6\u00d8\u00d9"
    + "\u00da\u00db\u00dc\u00dd\u00df\u0152\u0178"
    + "\u00e0\u00e1\u00e2\u00e3\u00e4\u00e5\u00e6\u00e7\u00e8\u00e9\u00ea\u00eb"
    + "\u00ec\u00ed\u00ee\u00ef\u00f1\u00f2\u00f3\u00f4\u00f5\u00f6\u00f8\u00f9"
    + "\u00fa\u00fb\u00fc\u00fd\u00ff\u0153"
)

# nom genere, fichier source, corps, interligne, poids variable, interlettrage en em
FONTS = [
    ("Uncial-Title",   "UncialAntiqua-Regular.ttf", 44, 1.06, None, 0.0),
    ("Uncial-Display", "UncialAntiqua-Regular.ttf", 32, 1.06, None, 0.0),
    ("Uncial-Item",    "UncialAntiqua-Regular.ttf", 20, 1.20, None, 0.0),
    ("Grenze-Body",    "Grenze[wght].ttf",          18, 1.45, 400,  0.0),
    ("Grenze-Strong",  "Grenze[wght].ttf",          18, 1.45, 600,  0.0),
    ("Grenze-Small",   "Grenze[wght].ttf",          15, 1.45, 400,  0.0),
    ("Grenze-Caps",    "Grenze[wght].ttf",          13, 1.20, 600,  0.14),
]


def fetch(name: str) -> pathlib.Path:
    CACHE.mkdir(parents=True, exist_ok=True)
    dest = CACHE / name
    if not dest.exists():
        print(f"  telechargement {name}")
        dest.write_bytes(urllib.request.urlopen(SOURCES[name], timeout=60).read())
    return dest


def load(src: str, size: int, weight: int | None) -> ImageFont.FreeTypeFont:
    font = ImageFont.truetype(str(fetch(src)), size)
    if weight is not None:
        font.set_variation_by_axes([weight])
    return font


NOTDEF_PROBE = ""  # zone a usage prive : aucune fonte ne la couvre


def stamp(font: ImageFont.FreeTypeFont, ch: str, size: int) -> bytes:
    """Empreinte pixel d'un caractere, pour reconnaitre ce que la fonte ne couvre pas."""
    box = Image.new("L", (size * 3, size * 3), 0)
    ImageDraw.Draw(box).text((size, size), ch, font=font, fill=255, anchor="la")
    return box.tobytes()


class Shelf:
    """Empilement par etageres : suffisant pour quelques centaines de glyphes."""

    def __init__(self) -> None:
        self.pages: list[Image.Image] = []
        self._new_page()

    def _new_page(self) -> None:
        self.pages.append(Image.new("RGBA", (PAGE, PAGE), (255, 255, 255, 0)))
        self.x = PAD
        self.y = PAD
        self.shelf_h = 0

    def place(self, w: int, h: int) -> tuple[int, int, int]:
        if w > PAGE - 2 * PAD or h > PAGE - 2 * PAD:
            raise SystemExit(f"glyphe {w}x{h} trop grand pour une page de {PAGE}")
        if self.x + w + PAD > PAGE:
            self.x = PAD
            self.y += self.shelf_h + PAD
            self.shelf_h = 0
        if self.y + h + PAD > PAGE:
            self._new_page()
        x, y = self.x, self.y
        self.x += w + PAD
        self.shelf_h = max(self.shelf_h, h)
        return len(self.pages) - 1, x, y


def build(name: str, src: str, size: int, lh: float, weight: int | None, tracking: float) -> None:
    font = load(src, size, weight)
    ascent, descent = font.getmetrics()
    line_height = round(size * lh)
    leading = max(0, line_height - (ascent + descent))
    top = leading // 2
    base = ascent + top
    extra = round(tracking * size)
    blank = stamp(font, NOTDEF_PROBE, size)

    shelf = Shelf()
    chars: list[str] = []
    skipped: list[str] = []

    for ch in CHARSET:
        if ch not in "  " and stamp(font, ch, size) == blank:
            skipped.append(ch)
            continue
        advance = round(font.getlength(ch)) + extra
        x0, y0, x1, y1 = font.getbbox(ch)
        w, h = max(0, x1 - x0), max(0, y1 - y0)
        if w == 0 or h == 0:  # espace et compagnie : pas de pixel, juste une avance
            chars.append(f"char id={ord(ch)} x=0 y=0 width=0 height=0 "
                         f"xoffset=0 yoffset=0 xadvance={advance} page=0 chnl=15")
            continue
        page, px, py = shelf.place(w, h)
        ImageDraw.Draw(shelf.pages[page]).text(
            (px - x0, py - y0), ch, font=font, fill=(255, 255, 255, 255), anchor="la")
        chars.append(f"char id={ord(ch)} x={px} y={py} width={w} height={h} "
                     f"xoffset={x0} yoffset={y0 + top} xadvance={advance} page={page} chnl=15")

    OUT.mkdir(parents=True, exist_ok=True)
    pages = []
    for i, img in enumerate(shelf.pages):
        page_name = f"{name}_{i}.png"
        img.save(OUT / page_name)
        pages.append(f'page id={i} file="{page_name}"')

    face = "Uncial Antiqua" if src.startswith("Uncial") else "Grenze"
    lines = [
        f'info face="{face}" size={size} bold=0 italic=0 charset="" unicode=1 '
        f"stretchH=100 smooth=1 aa=1 padding=0,0,0,0 spacing={PAD},{PAD} outline=0",
        f"common lineHeight={line_height} base={base} scaleW={PAGE} scaleH={PAGE} "
        f"pages={len(pages)} packed=0 alphaChnl=0 redChnl=4 greenChnl=4 blueChnl=4",
        *pages,
        f"chars count={len(chars)}",
        *chars,
    ]
    (OUT / f"{name}.fnt").write_text("\n".join(lines) + "\n", encoding="utf-8")
    note = f", {len(skipped)} glyphes absents de la fonte" if skipped else ""
    print(f"  {name}: {len(chars)} caracteres, {len(pages)} page(s), "
          f"lineHeight={line_height} base={base}{note}")


def main() -> None:
    argparse.ArgumentParser(description=__doc__).parse_args()
    print("Fontes HeroCraft")
    for spec in FONTS:
        build(*spec)
    for lic in ("UncialAntiqua-OFL.txt", "Grenze-OFL.txt"):
        (OUT / lic).write_bytes(fetch(lic).read_bytes())
    print(f"  licences OFL copiees dans {OUT.relative_to(ROOT)}")


if __name__ == "__main__":
    main()
