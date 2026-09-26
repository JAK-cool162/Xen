"""Xen's modern skin pack: original skins in today's style, drawn by this script (free to use: CC0).

    python scripts/make_modern_skins.py docs/skins-modern          # draws the skins (PNG) and a preview sheet
    python scripts/make_modern_skins.py docs/skins-modern --sign   # also signs them with mineskin.org: the mod's pack

The look players wear now (the kind at the top of the skin sites): soft shading instead of flat colour, hair with
volume on the outer layer and strands in three shades, bangs that fall over the forehead, eyes with a highlight and
often a little blush, and clothes in muted or pastel colours: hoodies with drawstrings and a pocket, oversized
tees, open jackets over a shirt, sweaters, cargo trousers and joggers, sneakers with white soles; sometimes
headphones, a beanie, a cap turned round, a scarf or a chain. Many have slim (3-pixel) arms. Every skin is 64x64 in the
modern layout. Signing uploads each PNG to mineskin.org (unlisted) for Mojang's signature, so every player sees it;
the result is mod/common/resources/assets/xen/skins/modern.json.
"""
import json
import os
import random
import subprocess
import sys
import time

from PIL import Image

PACK = os.path.join(os.path.dirname(__file__), "..", "mod", "common", "resources", "assets", "xen", "skins", "modern.json")


def hexc(h):
    h = h.lstrip("#")
    return tuple(int(h[i:i + 2], 16) for i in (0, 2, 4))


def clamp(v):
    return max(0, min(255, int(round(v))))


def shade(c, k):
    """Darker (k < 1) or lighter (k > 1), shifting the hue a little the way painters do (shadows cooler, lights warmer)."""
    r, g, b = c[:3]
    if k < 1:
        return (clamp(r * k * 0.97), clamp(g * k), clamp(b * k * 1.04 + 4 * (1 - k)))
    return (clamp(r + (255 - r) * (k - 1) * 1.05), clamp(g + (255 - g) * (k - 1)), clamp(b + (255 - b) * (k - 1) * 0.9))


def mix(a, b, t):
    return tuple(clamp(a[i] * (1 - t) + b[i] * t) for i in range(3))


# ------------------------------------------------------------------------------------ layout
def boxes(slim):
    """Each part: base (x, y) of its net, width, height, depth; and where its outer layer's net starts."""
    aw = 3 if slim else 4
    return {
        "head": ((0, 0), 8, 8, 8, (32, 0)),
        "body": ((16, 16), 8, 12, 4, (16, 32)),
        "rarm": ((40, 16), aw, 12, 4, (40, 32)),
        "larm": ((32, 48), aw, 12, 4, (48, 48)),
        "rleg": ((0, 16), 4, 12, 4, (0, 32)),
        "lleg": ((16, 48), 4, 12, 4, (0, 48)),
    }


def face_rect(box, name, outer=False):
    (x, y), w, h, d, o = box
    if outer:
        x, y = o
    return {"top": (x + d, y, w, d), "bottom": (x + d + w, y, w, d), "right": (x, y + d, d, h), "front": (x + d, y + d, w, h),
            "left": (x + d + w, y + d, d, h), "back": (x + 2 * d + w, y + d, w, h)}[name]


SIDES = ("front", "right", "back", "left")


class Skin:
    def __init__(self, rng, slim):
        self.img = Image.new("RGBA", (64, 64), (0, 0, 0, 0))
        self.rng = rng
        self.slim = slim
        self.b = boxes(slim)

    def put(self, x, y, c, noise=3, alpha=255):
        if c is None:
            return
        n = self.rng.randint(-noise, noise) if noise else 0
        self.img.putpixel((x, y), (clamp(c[0] + n), clamp(c[1] + n), clamp(c[2] + n), alpha))

    def get(self, x, y):
        return self.img.getpixel((x, y))

    def px(self, part, face, i, j, c, outer=False, noise=2):
        x, y, w, h = face_rect(self.b[part], face, outer)
        if 0 <= i < w and 0 <= j < h:
            self.put(x + i, y + j, c, noise)

    def paint(self, part, faces, fn, outer=False):
        """fn(face, i, j, w, h) -> colour or None, for every pixel of those faces."""
        for f in faces:
            x, y, w, h = face_rect(self.b[part], f, outer)
            for j in range(h):
                for i in range(w):
                    c = fn(f, i, j, w, h)
                    if c is not None:
                        self.put(x + i, y + j, c)

    def fill(self, part, c, faces=("top", "bottom") + SIDES, outer=False, light=True):
        """A colour with soft light: from above and the front; the sides and back and bottom a little darker."""
        def fn(f, i, j, w, h):
            k = {"front": 1.0, "right": 0.9, "left": 0.9, "back": 0.84, "top": 1.06, "bottom": 0.72}[f]
            if light and f in SIDES:
                k *= 1.04 - 0.1 * j / max(1, h - 1)                            # lighter at the top
            return shade(c, k)
        self.paint(part, faces, fn, outer)


# ----------------------------------------------------------------------------------- palettes
TONES = [hexc(h) for h in ("#f8dcc6", "#f2cdb0", "#e8b995", "#d9a27b", "#c48a62", "#a8704c", "#8a5638", "#6b3f28", "#f5d5c0", "#ecc3a4")]
HAIR = [hexc(h) for h in ("#141217", "#141217", "#2a211c", "#2a211c", "#3d2b22", "#3d2b22", "#5a3d2b", "#5a3d2b", "#7b5537",
                          "#a57a4f", "#d8bf94", "#efe6d2", "#bfbfc6", "#8a3f3f", "#c9a0b8", "#9fb6d9", "#b8a3d6", "#2f3a52", "#1d2233")]
IRIS = [hexc(h) for h in ("#5b8fd6", "#6fb3a8", "#7a5a3a", "#3a3a44", "#a58bd6", "#d98fa8", "#8fb86a", "#c9a64a")]
CLOTH = [hexc(h) for h in ("#1b1b1f", "#2d2d34", "#44444d", "#6b6b75", "#9a9aa3", "#ededee", "#e9dfcf", "#cbb89d", "#8a6f55",
                           "#5c6445", "#9aae8f", "#7d93b2", "#26324a", "#b7a6d9", "#e7b6c4", "#6b2737", "#c9a13b", "#a3c4c9",
                           "#d9d2e9", "#3f4f3a", "#c77f6a", "#f2e8e1")]
DARKS = [hexc(h) for h in ("#1b1b1f", "#2d2d34", "#26324a", "#3f4f3a", "#44444d")]
DENIM = [hexc(h) for h in ("#3e5277", "#56698f", "#2c3a55", "#7d8fb0", "#1f2533")]
PANTS = [hexc(h) for h in ("#1b1b1f", "#2d2d34", "#44444d", "#5c6445", "#8a6f55", "#cbb89d", "#6b6b75", "#e9dfcf")]


def pick(rng, a):
    return a[rng.randrange(len(a))]


# ------------------------------------------------------------------------------------- parts
def draw_skin(s, tone):
    for part in s.b:
        s.fill(part, tone)
    # the face: a touch of shadow under the hair and at the cheeks' edges
    s.paint("head", ["front"], lambda f, i, j, w, h: shade(tone, 0.94) if i in (0, 7) and j >= 3 else None)


def draw_eyes(s, tone, iris, hair, kind, blush):
    white = (246, 244, 242)
    dark = shade(hair, 0.55) if kind != "closed" else shade(tone, 0.6)
    y = 4
    for x0, inner in ((1, 2), (5, 5)):                                        # left eye at 1-2, right at 5-6
        if kind == "closed":
            s.px("head", "front", x0, y + 1, dark)
            s.px("head", "front", x0 + 1, y + 1, dark)
            continue
        s.px("head", "front", x0, y, dark)                                    # lashes, lid
        s.px("head", "front", x0 + 1, y, dark)
        outer_x = x0 if x0 == 1 else x0 + 1
        s.px("head", "front", outer_x, y + 1, white if kind == "wide" else shade(iris, 0.8))
        s.px("head", "front", inner if x0 == 1 else x0, y + 1, iris)
        if kind == "sparkle":
            s.px("head", "front", inner if x0 == 1 else x0, y, mix(iris, white, 0.6))
    if blush:
        pink = mix(tone, (232, 128, 140), 0.35)
        s.px("head", "front", 1, 6, pink)
        s.px("head", "front", 6, 6, pink)
    s.px("head", "front", 3, 6, shade(tone, 0.9))                            # a hint of a nose / mouth
    if s.rng.random() < 0.5:
        s.px("head", "front", 4, 7, shade(tone, 0.78))


def hair_tones(c):
    return shade(c, 1.25), c, shade(c, 0.78), shade(c, 0.6)


def draw_hair(s, style, c, tone):
    hi, mid, lo, deep = hair_tones(c)
    rng = s.rng

    def strand(i, j, w, h):
        v = (i * 7 + j * 3 + rng.randrange(3)) % 5
        return hi if v == 0 else lo if v == 3 else mid

    # the scalp (base layer): all of the top and the back, the sides' upper part
    s.paint("head", ["top"], lambda f, i, j, w, h: strand(i, j, w, h))
    s.paint("head", ["back"], lambda f, i, j, w, h: strand(i, j, w, h) if j < (8 if style in ("long", "wolf", "pony") else 6) else None)
    s.paint("head", ["left", "right"], lambda f, i, j, w, h: (lo if j > 3 else strand(i, j, w, h))
            if j < (7 if style in ("long", "wolf") else 4) else None)
    # bangs on the forehead: an uneven edge, strands with tips
    edge = [2, 3, 2, 1, 2, 3, 3, 2] if style in ("fringe", "wolf", "long") else [2, 2, 1, 1, 1, 2, 2, 2]
    if style == "part":
        edge = [3, 3, 2, 1, 1, 1, 2, 2]
    if style == "buzz":
        edge = [1] * 8
    for i in range(8):
        for j in range(edge[i]):
            s.px("head", "front", i, j, strand(i, j, 8, 8) if j < edge[i] - 1 else lo)
    # volume on the outer layer: sides, back, the top rim, locks falling in front for long styles
    if style != "buzz":
        s.paint("head", ["top"], lambda f, i, j, w, h: strand(i, j, w, h) if rng.random() < 0.85 else None, outer=True)
        s.paint("head", ["back"], lambda f, i, j, w, h: strand(i, j, w, h) if j < (8 if style in ("long", "wolf", "pony") else 5) else None, outer=True)
        s.paint("head", ["left", "right"], lambda f, i, j, w, h: (lo if j > 2 else strand(i, j, w, h))
                if j < (8 if style in ("long", "wolf") else 4 if style != "part" else 5) and (j < 3 or i > 1 or style in ("long", "wolf")) else None, outer=True)
        tips = [1, 2, 1, 0, 1, 2, 2, 1] if style in ("fringe", "wolf") else [1, 1, 0, 0, 0, 1, 1, 1]
        for i in range(8):
            for j in range(tips[i]):
                s.px("head", "front", i, j, strand(i, j, 8, 8), outer=True)
        if style in ("long", "wolf"):                                         # locks down the sides of the face
            for j in range(3, 8):
                s.px("head", "front", 0, j, mid if j < 6 else lo, outer=True)
                s.px("head", "front", 7, j, mid if j < 6 else lo, outer=True)
    if style == "long":                                                        # down the back
        s.paint("body", ["back"], lambda f, i, j, w, h: (strand(i, j, w, h) if j < 5 else lo if j < 7 else None), outer=True)
    if style == "pony":
        for j in range(0, 6):
            s.px("body", "back", 3, j, mid if j < 4 else lo, outer=True)
            s.px("body", "back", 4, j, hi if j < 2 else mid, outer=True)


def draw_headwear(s, kind, c):
    if kind == "beanie":
        s.fill("head", c, faces=("top",), outer=True)
        s.paint("head", SIDES, lambda f, i, j, w, h: shade(c, 0.85 if j == 2 else 1.0 - 0.04 * ((i + j) % 2)) if j < 3 else None, outer=True)
    elif kind == "cap":                                                        # turned round: the brim at the back
        s.fill("head", c, faces=("top",), outer=True)
        s.paint("head", SIDES, lambda f, i, j, w, h: shade(c, 0.92) if j < 2 else None, outer=True)
        s.paint("head", ["back"], lambda f, i, j, w, h: shade(c, 0.7) if j == 2 else None, outer=True)
    elif kind == "headphones":
        band, cup = c, shade(c, 0.7)
        s.paint("head", ["top"], lambda f, i, j, w, h: band if j in (3, 4) else None, outer=True)
        for side in ("left", "right"):
            s.paint("head", [side], lambda f, i, j, w, h: (cup if 3 <= j <= 5 and 2 <= i <= 5 else band if j < 3 and i in (3, 4) else None), outer=True)


def draw_top(s, kind, c, accent):
    rng = s.rng
    dark, light = shade(c, 0.75), shade(c, 1.12)
    # the body and arms in the cloth, with folds
    def cloth(f, i, j, w, h):
        k = 1.0
        if f in ("right", "left"):
            k = 0.9
        if f == "back":
            k = 0.86
        if (i + j * 2) % 7 == 0 and j > 2:
            k *= 0.93                                                          # folds
        return shade(c, k * (1.03 - 0.08 * j / 11))
    s.paint("body", ("front", "back", "left", "right", "top", "bottom"), cloth)
    sleeve = 12 if kind != "tee" else 5
    for arm in ("rarm", "larm"):
        s.paint(arm, SIDES, lambda f, i, j, w, h: cloth(f, i, j, w, h) if j < sleeve else None)
        s.paint(arm, ["top"], lambda f, i, j, w, h: cloth(f, i, j, w, h))
        if kind in ("hoodie", "sweater"):                                      # cuffs
            s.paint(arm, SIDES, lambda f, i, j, w, h: shade(c, 0.82) if j == 11 else None)
        if kind == "tee":
            s.paint(arm, SIDES, lambda f, i, j, w, h: shade(c, 0.85) if j == 4 else None)
    if kind == "hoodie":
        s.paint("body", ["front"], lambda f, i, j, w, h: shade(c, 0.8) if 7 <= j <= 9 and 2 <= i <= 5 else None)   # the pocket
        s.paint("body", ["front"], lambda f, i, j, w, h: shade(c, 0.68) if j == 7 and 2 <= i <= 5 else None)
        string = accent
        for j in range(1, 5):                                                  # drawstrings
            s.px("body", "front", 3, j, string if j < 4 else shade(string, 0.8))
            s.px("body", "front", 4, j, string if j < 3 else None)
        s.paint("body", ["front"], lambda f, i, j, w, h: shade(c, 0.78) if j == 0 else None)   # the neckline
        s.paint("body", ["back"], lambda f, i, j, w, h: shade(c, 0.9) if j < 3 else None, outer=True)   # the hood, down
        s.paint("body", ["top"], lambda f, i, j, w, h: shade(c, 0.95), outer=True)
        s.paint("body", ["front"], lambda f, i, j, w, h: shade(c, 0.95) if j == 0 and i in (0, 1, 6, 7) else None, outer=True)
    elif kind == "jacket":                                                     # open over a shirt
        inner = accent
        s.paint("body", ["front"], lambda f, i, j, w, h: (shade(inner, 1.0 - 0.05 * j / 11) if 2 <= i <= 5 else None))
        s.paint("body", ["front"], lambda f, i, j, w, h: shade(c, 0.7) if i in (2, 5) else None)
        s.paint("body", ["front", "back", "left", "right"], lambda f, i, j, w, h: cloth(f, i, j, w, h) if f != "front" or i < 2 or i > 5 else None, outer=True)
        for arm in ("rarm", "larm"):
            s.paint(arm, SIDES, lambda f, i, j, w, h: cloth(f, i, j, w, h) if j < 11 else None, outer=True)
        s.paint("body", ["front"], lambda f, i, j, w, h: shade(c, 1.1) if j < 2 and i in (1, 6) else None, outer=True)   # the collar
    elif kind == "sweater":
        band = accent
        s.paint("body", ["front", "back", "left", "right"], lambda f, i, j, w, h: shade(band, 0.95) if j in (4, 5) else None)
        for arm in ("rarm", "larm"):
            s.paint(arm, SIDES, lambda f, i, j, w, h: shade(band, 0.95) if j in (4, 5) else None)
        s.paint("body", ["front"], lambda f, i, j, w, h: shade(c, 0.8) if j == 0 and 2 <= i <= 5 else None)
    elif kind == "tee":                                                        # a little print
        mark = accent
        pattern = pick(rng, [[(3, 3), (4, 3), (3, 4), (4, 4)], [(2, 3), (3, 3), (4, 3), (5, 3), (3, 5), (4, 5)],
                             [(3, 2), (4, 3), (3, 4), (4, 5)], [(2, 4), (3, 4), (4, 4), (5, 4)]])
        for i, j in pattern:
            s.px("body", "front", i, j, mark)
        s.paint("body", ["front"], lambda f, i, j, w, h: shade(c, 0.82) if j == 0 and 2 <= i <= 5 else None)
    elif kind == "turtleneck":
        s.paint("body", ["front", "back", "left", "right"], lambda f, i, j, w, h: shade(c, 0.9) if j < 2 else None, outer=True)


def draw_legs(s, kind, c, shoe, sole):
    def fabric(f, i, j, w, h):
        k = {"front": 1.0, "back": 0.86}.get(f, 0.9)
        if kind == "jeans" and f == "front" and j in (5, 6):
            k *= 1.12                                                          # worn knees
        if kind == "cargo" and f in ("right", "left") and 4 <= j <= 6 and 1 <= i <= 2:
            k *= 0.8                                                           # side pockets
        return shade(c, k * (1.02 - 0.06 * j / 11))
    for leg in ("rleg", "lleg"):
        s.paint(leg, SIDES + ("top",), fabric)
        if kind == "joggers":
            s.paint(leg, SIDES, lambda f, i, j, w, h: shade(c, 0.78) if j == 9 else None)
        if kind == "shorts":
            s.paint(leg, SIDES, lambda f, i, j, w, h: None if j < 5 else shade(s.tone, {"front": 1.0, "back": 0.86}.get(f, 0.9)))
        # shoes: the upper, a white (or dark) sole, laces
        s.paint(leg, SIDES, lambda f, i, j, w, h: shade(shoe, 0.95 if f == "front" else 0.85) if j in (10,) else sole if j == 11 else None)
        s.paint(leg, ["bottom"], lambda f, i, j, w, h: shade(sole, 0.8))
        s.px(leg, "front", 1, 10, shade(shoe, 1.25))
        s.px(leg, "front", 2, 10, shade(shoe, 1.25))


def draw_extra(s, kind, c):
    if kind == "chain":
        gold = (214, 186, 110)
        for i, j in ((1, 1), (2, 2), (3, 3), (4, 3), (5, 2), (6, 1)):
            s.px("body", "front", i, j, gold, outer=True)
    elif kind == "scarf":
        s.paint("body", ["front", "back", "left", "right"], lambda f, i, j, w, h: shade(c, 1.0 if (i + j) % 3 else 0.85) if j < 2 else None, outer=True)
        for j in range(2, 6):
            s.px("body", "front", 5, j, shade(c, 0.9), outer=True)
    elif kind == "earring":
        s.px("head", "right", 3, 5, (220, 220, 225))


# -------------------------------------------------------------------------------------- whole
def make(seed):
    rng = random.Random(seed)
    slim = rng.random() < 0.55
    s = Skin(rng, slim)
    tone = pick(rng, TONES)
    s.tone = tone
    hair = pick(rng, HAIR)
    main = pick(rng, CLOTH)
    accent = pick(rng, [c for c in CLOTH if c != main])
    draw_skin(s, tone)
    top = pick(rng, ["hoodie", "hoodie", "jacket", "sweater", "tee", "turtleneck"])
    draw_top(s, top, main, accent)
    legs = pick(rng, ["jeans", "cargo", "joggers", "jeans", "shorts" if slim else "cargo"])
    pants = pick(rng, DENIM) if legs == "jeans" else pick(rng, PANTS)
    shoe = pick(rng, [(236, 236, 238), (32, 32, 36), pick(rng, CLOTH)])
    sole = (240, 240, 240) if shoe != (240, 240, 240) and rng.random() < 0.75 else (40, 40, 44)
    draw_legs(s, legs, pants, shoe, sole)
    style = pick(rng, ["fringe", "wolf", "part", "long" if slim else "fringe", "pony" if slim else "part", "buzz" if not slim else "long"])
    eyes = pick(rng, ["plain", "sparkle", "sparkle", "wide", "closed" if rng.random() < 0.2 else "plain"])
    draw_eyes(s, tone, pick(rng, IRIS), hair, eyes, blush=rng.random() < (0.6 if slim else 0.25))
    draw_hair(s, style, hair, tone)
    wear = rng.random()
    if wear < 0.14:
        draw_headwear(s, "beanie", pick(rng, CLOTH))
    elif wear < 0.24:
        draw_headwear(s, "cap", pick(rng, DARKS + CLOTH[:6]))
    elif wear < 0.36:
        draw_headwear(s, "headphones", pick(rng, DARKS + [(236, 236, 238)]))
    extra = rng.random()
    if extra < 0.15:
        draw_extra(s, "chain", None)
    elif extra < 0.27:
        draw_extra(s, "scarf", pick(rng, CLOTH))
    elif extra < 0.37:
        draw_extra(s, "earring", None)
    return s.img, ("slim" if slim else "classic"), f"{top}, {legs}, {style} hair"


def front_view(img, slim, scale=8):
    """The front of a skin as a player sees it (for the preview sheet)."""
    aw = 3 if slim else 4
    out = Image.new("RGBA", ((8 + 2 * aw) * scale, 32 * scale), (0, 0, 0, 0))

    def blit(sx, sy, w, h, dx, dy):
        for j in range(h):
            for i in range(w):
                p = img.getpixel((sx + i, sy + j))
                if p[3]:
                    for a in range(scale):
                        for b in range(scale):
                            out.putpixel(((dx + i) * scale + a, (dy + j) * scale + b), p)
    for (sx, sy), (ox, oy) in (((8, 8), (40, 8)),):
        blit(sx, sy, 8, 8, aw, 0)
        blit(ox, oy, 8, 8, aw, 0)
    blit(20, 20, 8, 12, aw, 8)
    blit(20, 36, 8, 12, aw, 8)
    blit(44, 20, aw, 12, 0, 8)
    blit(44, 36, aw, 12, 0, 8)
    blit(36, 52, aw, 12, aw + 8, 8)
    blit(52, 52, aw, 12, aw + 8, 8)
    blit(4, 20, 4, 12, aw, 20)
    blit(4, 36, 4, 12, aw, 20)
    blit(20, 52, 4, 12, aw + 4, 20)
    blit(4, 52, 4, 12, aw + 4, 20)
    return out


def sign(path, variant):
    """Upload a skin to mineskin.org (unlisted) and get its texture value and Mojang's signature."""
    for attempt in range(4):
        out = subprocess.run(["curl", "-s", "-m", "90", "-X", "POST", "https://api.mineskin.org/v2/generate",
                              "-H", "User-Agent: XenCompanion/0.7", "-F", f"file=@{path}", "-F", f"variant={variant}",
                              "-F", "visibility=unlisted", "-F", f"name={os.path.basename(path)[:-4]}"], capture_output=True, text=True)
        try:
            d = json.loads(out.stdout)
            t = d["skin"]["texture"]["data"]
            return {"value": t["value"], "signature": t["signature"], "variant": variant}
        except (KeyError, ValueError, TypeError):
            print("  retrying", os.path.basename(path), out.stdout[:160])
            time.sleep(8 * (attempt + 1))
    return None


def main():
    out = sys.argv[1] if len(sys.argv) > 1 else "docs/skins-modern"
    count = 48
    os.makedirs(out, exist_ok=True)
    made = []
    for n in range(count):
        img, variant, what = make(7000 + n)
        path = os.path.join(out, f"modern-{n + 1:02d}.png")
        img.save(path)
        made.append((path, variant, what))
    cols = 12
    views = [front_view(Image.open(p), v == "slim") for p, v, _ in made]
    w, h = 16 * 8 + 8, 32 * 8 + 8
    sheet = Image.new("RGBA", (cols * w, ((len(views) + cols - 1) // cols) * h), (38, 40, 46, 255))
    for i, v in enumerate(views):
        sheet.paste(v, ((i % cols) * w + (w - v.width) // 2, (i // cols) * h + 4), v)
    sheet.save(os.path.join(out, "preview.png"))
    print(f"{len(made)} skins in {out} (preview.png)")
    if "--sign" in sys.argv:
        pack = []
        for path, variant, what in made:
            t = sign(path, variant)
            if t:
                pack.append(t)
                print("signed", os.path.basename(path), variant, what)
            time.sleep(6)
        os.makedirs(os.path.dirname(PACK), exist_ok=True)
        with open(PACK, "w") as f:
            json.dump(pack, f)
        print(f"{len(pack)} signed skins in {PACK}")


if __name__ == "__main__":
    main()
