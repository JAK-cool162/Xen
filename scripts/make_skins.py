"""Xen's skin pack: original Minecraft skins drawn by this script (free to use: CC0).

    python scripts/make_skins.py docs/skins           # draws the skins (PNG) and a preview sheet
    python scripts/make_skins.py docs/skins --sign    # also signs them with mineskin.org and writes the mod's pack

Every skin is 64x64 in the modern layout (a base layer and an overlay layer). Each is built from parts: a skin tone,
eyes and a mouth, a hair style and colour (or a hat, a hood, a helmet), a top (a shirt, a hoodie, a jacket, overalls,
armour...), trousers and shoes, sometimes glasses, a beard, a scarf or a backpack; some are themed (a miner, a knight,
a chef, an astronaut, a ninja...). A little noise and darker sides keep them from looking flat. Signing uploads each
PNG to mineskin.org (unlisted), which returns the texture value and Mojang's signature: with those, every player sees
the skin, with or without the mod. They go into mod/common/resources/assets/xen/skins/pack.json.
"""
import json
import os
import random
import sys
import time

from PIL import Image

PACK = os.path.join(os.path.dirname(__file__), "..", "mod", "common", "resources", "assets", "xen", "skins", "pack.json")

# -------------------------------------------------------------------------------- the layout
# Each box part: (x, y, width, height, depth) of the net; faces are laid out the usual way around it.
HEAD, BODY = (0, 0, 8, 8, 8), (16, 16, 8, 12, 4)
R_ARM, L_ARM = (40, 16, 4, 12, 4), (32, 48, 4, 12, 4)
R_LEG, L_LEG = (0, 16, 4, 12, 4), (16, 48, 4, 12, 4)
OVER = {HEAD: (32, 0), BODY: (16, 32), R_ARM: (40, 32), L_ARM: (48, 48), R_LEG: (0, 32), L_LEG: (0, 48)}


def faces(part, overlay=False):
    """name -> (x, y, w, h) of each face of a part in the net."""
    x, y, w, h, d = part
    if overlay:
        x, y = OVER[part]
    return {"top": (x + d, y, w, d), "bottom": (x + d + w, y, w, d), "right": (x, y + d, d, h), "front": (x + d, y + d, w, h),
            "left": (x + d + w, y + d, d, h), "back": (x + 2 * d + w, y + d, w, h)}


class Skin:
    def __init__(self, rng):
        self.img = Image.new("RGBA", (64, 64), (0, 0, 0, 0))
        self.rng = rng

    def put(self, x, y, c, noise=6):
        if c is None:
            return
        r, g, b = c[:3]
        a = c[3] if len(c) > 3 else 255
        n = self.rng.randint(-noise, noise) if noise else 0
        self.img.putpixel((x, y), (clamp(r + n), clamp(g + n), clamp(b + n), a))

    def fill(self, part, color, overlay=False, which=None, shade=True, noise=6):
        for name, (x, y, w, h) in faces(part, overlay).items():
            if which and name not in which:
                continue
            k = 0.85 if shade and name in ("right", "left") else 0.78 if shade and name == "bottom" else 0.92 if shade and name == "back" else 1.0
            for i in range(w):
                for j in range(h):
                    self.put(x + i, y + j, dim(color, k), noise)

    def rows(self, part, face, r0, r1, color, overlay=False, noise=6):
        """Paint rows r0..r1-1 (from the top) of one face, or of the four sides when face is 'around'."""
        names = ["front", "back", "left", "right"] if face == "around" else [face]
        for name in names:
            x, y, w, h = faces(part, overlay)[name]
            k = 0.85 if name in ("left", "right") else 0.92 if name == "back" else 1.0
            for j in range(max(0, r0), min(h, r1)):
                for i in range(w):
                    self.put(x + i, y + j, dim(color, k), noise)

    def px(self, part, face, i, j, color, overlay=False, noise=3):
        x, y, w, h = faces(part, overlay)[face]
        if 0 <= i < w and 0 <= j < h:
            self.put(x + i, y + j, color, noise)


def clamp(v):
    return max(0, min(255, int(v)))


def dim(c, k):
    return tuple(clamp(v * k) for v in c[:3]) + tuple(c[3:])


def hexc(h):
    h = h.lstrip("#")
    return tuple(int(h[i:i + 2], 16) for i in (0, 2, 4))


TONES = [hexc(h) for h in ("#f6d2b3", "#eec09a", "#e0ac84", "#c98e64", "#b27650", "#94603c", "#7a4a2c", "#5c3620", "#f3c7a5", "#d9a077")]
HAIR = [hexc(h) for h in ("#2a1a10", "#4a2c17", "#6b4423", "#8d5a2b", "#b5823c", "#d9b25f", "#e8d08a", "#1c1c1c", "#6e6e6e",
                          "#c23b22", "#e06a2b", "#7a2e8e", "#2e6fb5", "#3aa36b", "#e86fa6", "#f2f2f2")]
EYES = [hexc(h) for h in ("#3b6fd1", "#2f8f4e", "#5a3a1e", "#2b2b2b", "#7aa7c7", "#8a5a2b", "#6b3fa0")]
CLOTH = [hexc(h) for h in ("#c0392b", "#e67e22", "#f1c40f", "#27ae60", "#16a085", "#2980b9", "#8e44ad", "#2c3e50", "#7f8c8d",
                           "#ecf0f1", "#d35400", "#1abc9c", "#e84393", "#6c5ce7", "#00b894", "#fdcb6e", "#b33939", "#3d3d3d",
                           "#a0522d", "#556b2f", "#4682b4", "#800020", "#ff7675", "#74b9ff")]
PANTS = [hexc(h) for h in ("#2f4f7f", "#3b5998", "#1f2a44", "#2b2b2b", "#555555", "#7a6a4f", "#4b5320", "#6d4c41", "#2d3436")]
SHOES = [hexc(h) for h in ("#2b2b2b", "#4a3526", "#f0f0f0", "#8b0000", "#1e3a5f", "#6b4f2a")]


# ------------------------------------------------------------------------------------- parts
def face(s, tone, eyes, hair, mouth_style):
    white = (245, 245, 245)
    s.px(HEAD, "front", 1, 4, white)
    s.px(HEAD, "front", 2, 4, eyes)
    s.px(HEAD, "front", 5, 4, eyes)
    s.px(HEAD, "front", 6, 4, white)
    s.px(HEAD, "front", 3, 5, dim(tone, 0.9))                      # the nose, a shade darker
    s.px(HEAD, "front", 4, 5, dim(tone, 0.9))
    lip = dim(tone, 0.72)
    if mouth_style == "smile":
        s.px(HEAD, "front", 2, 6, lip)
        s.px(HEAD, "front", 5, 6, lip)
        s.px(HEAD, "front", 3, 7, lip)
        s.px(HEAD, "front", 4, 7, lip)
    elif mouth_style == "open":
        s.px(HEAD, "front", 3, 6, (120, 40, 40))
        s.px(HEAD, "front", 4, 6, (120, 40, 40))
    else:
        s.px(HEAD, "front", 3, 6, lip)
        s.px(HEAD, "front", 4, 6, lip)
    if hair is not None:                                            # eyebrows
        s.px(HEAD, "front", 1, 3, dim(hair, 0.9))
        s.px(HEAD, "front", 2, 3, dim(hair, 0.9))
        s.px(HEAD, "front", 5, 3, dim(hair, 0.9))
        s.px(HEAD, "front", 6, 3, dim(hair, 0.9))


def hair_style(s, style, c, rng):
    if style == "bald":
        return
    s.fill(HEAD, c, which=["top"])
    if style in ("short", "spiky", "bob", "long", "ponytail", "curly", "bun"):
        s.rows(HEAD, "front", 0, 2, c)
        s.rows(HEAD, "left", 0, 3, c)
        s.rows(HEAD, "right", 0, 3, c)
        s.rows(HEAD, "back", 0, 4, c)
    if style == "spiky":
        for i in range(0, 8, 2):
            s.px(HEAD, "front", i, 2, c)
        for part_face in ("top",):
            for i in range(8):
                if rng.random() < 0.5:
                    s.px(HEAD, part_face, i, rng.randint(0, 7), dim(c, 1.25), overlay=True)
    if style in ("bob", "long", "curly"):
        depth = 6 if style == "bob" else 8
        s.rows(HEAD, "left", 0, depth, c)
        s.rows(HEAD, "right", 0, depth, c)
        s.rows(HEAD, "back", 0, 8, c)
        s.px(HEAD, "front", 0, 2, c)
        s.px(HEAD, "front", 7, 2, c)
        s.px(HEAD, "front", 0, 3, c)
        s.px(HEAD, "front", 7, 3, c)
    if style == "long":
        s.rows(BODY, "back", 0, 4, c)                                # down the back
    if style == "curly":
        for name in ("left", "right", "back", "top"):
            x, y, w, h = faces(HEAD, True)[name]
            for i in range(w):
                for j in range(h if name != "top" else 8):
                    if rng.random() < 0.35:
                        s.put(x + i, y + j, dim(c, rng.choice([0.85, 1.1])), 0)
    if style == "ponytail":
        s.rows(HEAD, "back", 0, 8, c)
        for j in range(4):
            s.px(BODY, "back", 3, j, c)
            s.px(BODY, "back", 4, j, c)
    if style == "bun":
        s.fill(HEAD, dim(c, 1.1), overlay=True, which=["top"], shade=False)
    if style == "mohawk":
        x, y, w, h = faces(HEAD)["top"]
        for j in range(8):
            s.put(x + 3, y + j, c)
            s.put(x + 4, y + j, c)
        s.px(HEAD, "front", 3, 0, c)
        s.px(HEAD, "front", 4, 0, c)
        s.px(HEAD, "back", 3, 0, c)
        s.px(HEAD, "back", 4, 0, c)


def beard(s, c):
    for i in range(8):
        s.px(HEAD, "front", i, 7, c)
    for i in (0, 1, 6, 7):
        s.px(HEAD, "front", i, 6, c)
    s.rows(HEAD, "left", 5, 8, c)
    s.rows(HEAD, "right", 5, 8, c)
    s.px(HEAD, "front", 3, 6, dim(c, 0.8))
    s.px(HEAD, "front", 4, 6, dim(c, 0.8))


def cap(s, c, rng):
    s.fill(HEAD, c, overlay=True, which=["top"], shade=False)
    s.rows(HEAD, "around", 0, 2, c, overlay=True)
    x, y, w, h = faces(HEAD, True)["front"]
    for i in range(w):
        s.put(x + i, y + 2, dim(c, 0.8))                            # the brim
    s.px(HEAD, "front", 3, 0, (240, 240, 240), overlay=True)        # a little logo
    s.px(HEAD, "front", 4, 0, (240, 240, 240), overlay=True)


def beanie(s, c):
    s.fill(HEAD, c, overlay=True, which=["top"], shade=False)
    s.rows(HEAD, "around", 0, 3, c, overlay=True)
    x, y, w, h = faces(HEAD, True)["front"]
    for i in range(w):
        s.put(x + i, y + 2, dim(c, 0.75))


def hood(s, c):
    s.fill(HEAD, c, overlay=True, which=["top", "back", "left", "right"], shade=True)
    s.rows(HEAD, "front", 0, 1, c, overlay=True)
    s.px(HEAD, "front", 0, 1, c, overlay=True)
    s.px(HEAD, "front", 7, 1, c, overlay=True)
    for j in range(1, 8):
        s.px(HEAD, "front", 0, j, dim(c, 0.85), overlay=True)
        s.px(HEAD, "front", 7, j, dim(c, 0.85), overlay=True)


def glasses(s, c=(30, 30, 30), lens=None):
    for i in range(1, 7):
        s.px(HEAD, "front", i, 3, c, overlay=True)
    for i in (1, 2, 5, 6):
        s.px(HEAD, "front", i, 4, lens or (200, 230, 255, 140), overlay=True)
    s.px(HEAD, "front", 0, 4, c, overlay=True)
    s.px(HEAD, "front", 7, 4, c, overlay=True)


def sleeves(s, top, tone, long_sleeves, arm_parts=(R_ARM, L_ARM)):
    for arm in arm_parts:
        s.fill(arm, tone)
        if long_sleeves:
            s.fill(arm, top, which=["top", "front", "back", "left", "right"])
            s.rows(arm, "around", 11, 12, tone)                    # hands
            s.fill(arm, tone, which=["bottom"])
        else:
            s.fill(arm, top, which=["top"])
            s.rows(arm, "around", 0, 4, top)


def legs(s, pants, shoes, belt=None):
    for leg in (R_LEG, L_LEG):
        s.fill(leg, pants)
        s.rows(leg, "around", 10, 12, shoes)
        s.fill(leg, dim(shoes, 0.8), which=["bottom"])
    if belt:
        s.rows(BODY, "around", 10, 11, belt)


def top_style(s, style, c, c2, rng):
    s.fill(BODY, c)
    if style == "stripes":
        for j in range(0, 12, 3):
            s.rows(BODY, "around", j, j + 1, c2)
    elif style == "vneck":
        s.px(BODY, "front", 3, 0, s.tone)
        s.px(BODY, "front", 4, 0, s.tone)
        s.px(BODY, "front", 3, 1, s.tone)
        s.px(BODY, "front", 4, 1, s.tone)
    elif style == "hoodie":
        s.rows(BODY, "front", 7, 10, dim(c, 0.85))                  # the pocket
        s.px(BODY, "front", 3, 1, (235, 235, 235))                 # strings
        s.px(BODY, "front", 4, 1, (235, 235, 235))
        s.px(BODY, "front", 3, 2, (235, 235, 235))
        s.px(BODY, "front", 4, 2, (235, 235, 235))
    elif style == "jacket":
        x, y, w, h = faces(BODY)["front"]
        for j in range(12):
            s.put(x + 3, y + j, c2)
            s.put(x + 4, y + j, c2)
        s.fill(BODY, c, overlay=True, which=["left", "right", "back"])
        for j in range(12):
            s.px(BODY, "front", 0, j, c, overlay=True)
            s.px(BODY, "front", 1, j, c, overlay=True)
            s.px(BODY, "front", 6, j, c, overlay=True)
            s.px(BODY, "front", 7, j, c, overlay=True)
    elif style == "overalls":
        s.fill(BODY, c2)
        s.rows(BODY, "around", 6, 12, c)
        for j in range(6):
            s.px(BODY, "front", 1, j, c)
            s.px(BODY, "front", 6, j, c)
            s.px(BODY, "back", 1, j, c)
            s.px(BODY, "back", 6, j, c)
        s.px(BODY, "front", 1, 5, (220, 200, 90))
        s.px(BODY, "front", 6, 5, (220, 200, 90))
    elif style == "tie":
        for j in range(1, 9):
            s.px(BODY, "front", 3, j, c2)
            s.px(BODY, "front", 4, j, c2)
    elif style == "number":
        n = rng.randint(0, 9)
        digits = {0: ["111", "101", "101", "101", "111"], 1: ["010", "110", "010", "010", "111"], 2: ["111", "001", "111", "100", "111"],
                  3: ["111", "001", "111", "001", "111"], 4: ["101", "101", "111", "001", "001"], 5: ["111", "100", "111", "001", "111"],
                  6: ["111", "100", "111", "101", "111"], 7: ["111", "001", "010", "010", "010"], 8: ["111", "101", "111", "101", "111"],
                  9: ["111", "101", "111", "001", "111"]}[n]
        for j, row in enumerate(digits):
            for i, bit in enumerate(row):
                if bit == "1":
                    s.px(BODY, "back", 2 + i, 3 + j, c2)
                    s.px(BODY, "front", 2 + i + 1, 3 + j, c2)
    elif style == "scarf":
        s.rows(BODY, "around", 0, 2, c2)
        for j in range(2, 6):
            s.px(BODY, "front", 5, j, c2)


def backpack(s, c):
    x, y, w, h = faces(BODY, True)["back"]
    for i in range(1, 7):
        for j in range(2, 11):
            s.put(x + i, y + j, dim(c, 1.0 if 2 < j < 10 else 0.8))
    for j in range(0, 10):
        s.px(BODY, "front", 1, j, dim(c, 0.7), overlay=True)
        s.px(BODY, "front", 6, j, dim(c, 0.7), overlay=True)


# ------------------------------------------------------------------------------------- themes
def armor(s, metal, trim):
    for part in (BODY, R_ARM, L_ARM, R_LEG, L_LEG):
        s.fill(part, metal, overlay=True)
    s.rows(BODY, "around", 11, 12, trim, overlay=True)
    s.rows(BODY, "front", 0, 1, trim, overlay=True)
    for leg in (R_LEG, L_LEG):
        s.rows(leg, "around", 10, 12, dim(metal, 0.7), overlay=True)


def helmet(s, metal, visor=True):
    s.fill(HEAD, metal, overlay=True, which=["top", "back", "left", "right"])
    s.rows(HEAD, "front", 0, 3, metal, overlay=True)
    if visor:
        s.rows(HEAD, "front", 5, 8, metal, overlay=True)
        for i in (0, 7):
            s.px(HEAD, "front", i, 3, metal, overlay=True)
            s.px(HEAD, "front", i, 4, metal, overlay=True)


def make(seed, theme=None):
    rng = random.Random(seed)
    s = Skin(rng)
    tone = rng.choice(TONES)
    s.tone = tone
    hair_c = rng.choice(HAIR)
    eyes = rng.choice(EYES)
    s.fill(HEAD, tone)
    style = rng.choice(["short", "short", "spiky", "bob", "long", "ponytail", "curly", "bun", "mohawk", "bald"])
    hair_style(s, style, hair_c, rng)
    face(s, tone, eyes, None if style == "bald" and rng.random() < 0.5 else hair_c, rng.choice(["smile", "smile", "line", "open"]))
    top, top2 = rng.sample(CLOTH, 2)
    pants, shoes = rng.choice(PANTS), rng.choice(SHOES)
    long_sleeves = rng.random() < 0.55
    top_style(s, rng.choice(["plain", "stripes", "vneck", "hoodie", "jacket", "tie", "number", "scarf", "overalls"]), top, top2, rng)
    sleeves(s, top, tone, long_sleeves)
    legs(s, pants, shoes, belt=rng.choice([None, None, (60, 40, 25), (30, 30, 30)]))
    name = f"{style} {'long' if long_sleeves else 'short'}"
    if theme is None:
        if rng.random() < 0.25:
            beard(s, hair_c) if style != "bald" or rng.random() < 0.5 else None
        extra = rng.random()
        if extra < 0.18:
            cap(s, rng.choice(CLOTH), rng)
        elif extra < 0.3:
            beanie(s, rng.choice(CLOTH))
        elif extra < 0.4:
            hood(s, top)
        if rng.random() < 0.15:
            glasses(s)
        if rng.random() < 0.15:
            backpack(s, rng.choice(CLOTH))
        return s.img, name
    # themed outfits on top of a person
    if theme == "miner":
        top_style(s, "overalls", (70, 90, 140), (230, 120, 30), rng)
        cap(s, (240, 200, 30), rng)
        s.px(HEAD, "front", 3, 1, (255, 255, 200), overlay=True)
        s.px(HEAD, "front", 4, 1, (255, 255, 200), overlay=True)
    elif theme == "knight":
        armor(s, (170, 175, 185), (200, 160, 40))
        helmet(s, (160, 165, 175))
    elif theme == "gold knight":
        armor(s, (230, 190, 60), (170, 40, 40))
        helmet(s, (225, 185, 55))
    elif theme == "farmer":
        top_style(s, "overalls", (60, 100, 170), (190, 60, 50), rng)
        sleeves(s, (190, 60, 50), tone, False)
        s.fill(HEAD, (225, 200, 120), overlay=True, which=["top"], shade=False)
        s.rows(HEAD, "around", 0, 2, (225, 200, 120), overlay=True)
    elif theme == "chef":
        s.fill(BODY, (245, 245, 245))
        for j in range(2, 11, 3):
            s.px(BODY, "front", 2, j, (60, 60, 60))
            s.px(BODY, "front", 5, j, (60, 60, 60))
        sleeves(s, (245, 245, 245), tone, True)
        s.fill(HEAD, (250, 250, 250), overlay=True, which=["top"], shade=False)
        s.rows(HEAD, "around", 0, 3, (250, 250, 250), overlay=True)
    elif theme == "astronaut":
        white = (235, 235, 240)
        for part in (BODY, R_ARM, L_ARM, R_LEG, L_LEG):
            s.fill(part, white)
        s.rows(BODY, "front", 4, 7, (60, 120, 200))
        backpack(s, (200, 200, 205))
        s.fill(HEAD, white, overlay=True, which=["top", "back", "left", "right"])
        s.rows(HEAD, "front", 0, 2, white, overlay=True)
        s.rows(HEAD, "front", 7, 8, white, overlay=True)
        for j in range(2, 7):
            s.px(HEAD, "front", 0, j, white, overlay=True)
            s.px(HEAD, "front", 7, j, white, overlay=True)
            for i in range(1, 7):
                s.px(HEAD, "front", i, j, (240, 170, 40, 110), overlay=True)
    elif theme == "ninja":
        black = (28, 28, 34)
        for part in (BODY, R_ARM, L_ARM, R_LEG, L_LEG):
            s.fill(part, black)
        s.rows(BODY, "around", 7, 8, (170, 30, 30))
        s.fill(HEAD, black, overlay=True, which=["top", "back", "left", "right"])
        s.rows(HEAD, "front", 0, 3, black, overlay=True)
        s.rows(HEAD, "front", 5, 8, black, overlay=True)
    elif theme == "pirate":
        s.fill(HEAD, (170, 30, 30), overlay=True, which=["top"], shade=False)
        s.rows(HEAD, "around", 0, 2, (170, 30, 30), overlay=True)
        s.px(HEAD, "front", 5, 4, (20, 20, 20), overlay=True)
        s.px(HEAD, "front", 6, 4, (20, 20, 20), overlay=True)
        for i in range(8):
            s.px(HEAD, "front", i, 3 if i < 5 else 3, (20, 20, 20), overlay=True) if i in (4, 7) else None
        top_style(s, "stripes", (235, 235, 235), (30, 30, 30), rng)
        beard(s, hair_c)
    elif theme == "scientist":
        s.fill(BODY, (240, 240, 240))
        s.rows(BODY, "front", 0, 12, (240, 240, 240))
        for j in range(12):
            s.px(BODY, "front", 3, j, (90, 140, 200))
            s.px(BODY, "front", 4, j, (90, 140, 200))
        sleeves(s, (240, 240, 240), tone, True)
        glasses(s, (40, 40, 40), (120, 220, 200, 170))
    elif theme == "robot":
        metal, dark = (150, 160, 170), (70, 80, 90)
        for part in (HEAD, BODY, R_ARM, L_ARM, R_LEG, L_LEG):
            s.fill(part, metal)
        for i in range(1, 7):
            s.px(HEAD, "front", i, 3, dark)
            s.px(HEAD, "front", i, 4, (60, 220, 255))
        s.rows(HEAD, "front", 6, 7, dark)
        s.rows(BODY, "front", 3, 8, dark)
        s.px(BODY, "front", 2, 5, (255, 80, 80))
        s.px(BODY, "front", 5, 5, (80, 255, 120))
    elif theme == "wizard":
        robe = rng.choice([(90, 50, 150), (40, 60, 140), (130, 30, 60)])
        for part in (BODY, R_LEG, L_LEG):
            s.fill(part, robe)
        sleeves(s, robe, tone, True)
        s.rows(BODY, "around", 6, 7, (220, 190, 60))
        beard(s, (225, 225, 225))
        s.fill(HEAD, robe, overlay=True, which=["top"], shade=False)
        s.rows(HEAD, "around", 0, 2, robe, overlay=True)
    elif theme == "explorer":
        khaki = (190, 165, 110)
        top_style(s, "plain", khaki, khaki, rng)
        sleeves(s, khaki, tone, False)
        legs(s, (120, 100, 70), (80, 55, 35), belt=(60, 40, 25))
        backpack(s, (120, 80, 45))
        cap(s, (150, 125, 80), rng)
    elif theme == "royal":
        top_style(s, "plain", (150, 20, 40), (230, 190, 60), rng)
        s.rows(BODY, "around", 0, 1, (240, 240, 240))
        sleeves(s, (150, 20, 40), tone, True)
        for i in range(8):
            s.px(HEAD, "front", i, 0, (230, 190, 60), overlay=True)
            s.px(HEAD, "back", i, 0, (230, 190, 60), overlay=True)
        for i in (0, 2, 5, 7):
            s.px(HEAD, "front", i, 1, (230, 190, 60), overlay=True) if False else None
        s.rows(HEAD, "around", 0, 1, (230, 190, 60), overlay=True)
        s.px(HEAD, "front", 3, 0, (200, 40, 60), overlay=True)
    return s.img, theme


THEMES = ["miner", "knight", "gold knight", "farmer", "chef", "astronaut", "ninja", "pirate", "scientist", "robot",
          "wizard", "explorer", "royal"]


def preview(img, scale=6):
    """The front of a skin (head, body, arms, legs), with the overlay on top."""
    out = Image.new("RGBA", (16, 32), (0, 0, 0, 0))

    def paste(part, face_name, dx, dy):
        for layer in (False, True):
            x, y, w, h = faces(part, layer)[face_name]
            crop = img.crop((x, y, x + w, y + h))
            out.alpha_composite(crop, (dx, dy))

    paste(HEAD, "front", 4, 0)
    paste(BODY, "front", 4, 8)
    paste(R_ARM, "front", 0, 8)
    paste(L_ARM, "front", 12, 8)
    paste(R_LEG, "front", 4, 20)
    paste(L_LEG, "front", 8, 20)
    return out.resize((16 * scale, 32 * scale), Image.NEAREST)


def sign(path, name):
    """Upload a skin to mineskin.org (unlisted) and get its texture value and Mojang's signature."""
    import subprocess
    for attempt in range(6):
        out = subprocess.run(["curl", "-s", "-m", "90", "-X", "POST", "https://api.mineskin.org/v2/generate",
                              "-H", "User-Agent: XenCompanion-skinpack/1.0", "-F", f"file=@{path};type=image/png",
                              "-F", "visibility=unlisted", "-F", f"name={name}"], capture_output=True, text=True).stdout
        try:
            d = json.loads(out)
            t = d["skin"]["texture"]["data"]
            return {"value": t["value"], "signature": t["signature"], "variant": d["skin"].get("variant", "classic")}
        except Exception:                                            # busy: wait and try again
            print(f"  {name}: {out[:200]}; waiting", flush=True)
            time.sleep(10 * (attempt + 1))
    raise SystemExit(f"could not sign {name}")


def main():
    out = sys.argv[1] if len(sys.argv) > 1 else "docs/skins"
    os.makedirs(out, exist_ok=True)
    skins = []
    for i in range(48):
        img, name = make(1000 + i)
        skins.append((f"xen-{i + 1:02d}", img, name))
    for i, theme in enumerate(THEMES):
        img, name = make(5000 + i, theme)
        skins.append((f"xen-{48 + i + 1:02d}", img, name))
    cols = 12
    sheet = Image.new("RGBA", (cols * 100, ((len(skins) + cols - 1) // cols) * 200), (40, 44, 52, 255))
    for k, (sid, img, name) in enumerate(skins):
        img.save(os.path.join(out, sid + ".png"))
        sheet.alpha_composite(preview(img), ((k % cols) * 100 + 2, (k // cols) * 200 + 4))
    sheet.save(os.path.join(out, "preview.png"))
    print(f"{len(skins)} skins in {out} (preview.png)")
    if "--sign" in sys.argv:
        pack = json.load(open(PACK)) if os.path.exists(PACK) else []
        done = {p["id"] for p in pack}
        for sid, img, name in skins:
            if sid in done:
                continue
            signed = sign(os.path.join(out, sid + ".png"), sid)
            pack.append({"id": sid, "look": name, **signed})
            os.makedirs(os.path.dirname(PACK), exist_ok=True)
            json.dump(pack, open(PACK, "w"), indent=1)                  # saved after each, so it can resume
            print(f"signed {sid} ({len(pack)}/{len(skins)})", flush=True)
            time.sleep(4)


if __name__ == "__main__":
    main()
