"""Xen 2.0's blob skins: simple skins, drawn by this script (CC0).

    python scripts/make_blob_skins.py docs/skins-blob           # draws the skins (PNG) and a preview sheet
    python scripts/make_blob_skins.py docs/skins-blob --sign    # also signs them with mineskin.org: the mod's pack

The blob look is simple: flat colour (any colour: red, mint, navy, peach, black...), a soft shade on the sides, two
plain eyes, and at most one simple touch: a second colour for the shirt, a lighter belly, shoes, a mouth, a blush. No
hair, no detail. Half have slim arms. The result is mod/common/resources/assets/xen/skins/blob.json.
"""
import json
import os
import random
import sys
import time

from PIL import Image

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import make_modern_skins as mm  # noqa: E402  (the layout and signing are the same)

PACK = os.path.join(os.path.dirname(__file__), "..", "mod", "common", "resources", "assets", "xen", "skins", "blob.json")
COLORS = [mm.hexc(h) for h in (
    "#e84a4a", "#f2873a", "#f5c842", "#9bd34a", "#4cbf6b", "#3fbfa8", "#36c6e0", "#58a6f0", "#4a6fe0", "#6a55d9",
    "#a45ad9", "#e86fb8", "#f29bb0", "#8a5a3c", "#d9b48a", "#f4f1ea", "#c8ccd2", "#8c939c", "#4a4f57", "#17191d",
    "#9fe3c9", "#f7c4a5", "#c4b5f0", "#26355e")]
TOUCHES = ("plain", "shirt", "belly", "shoes", "plain", "shirt")


def flat(s, part, c, faces=("top", "bottom") + mm.SIDES):
    """One colour, the sides a shade darker, the bottom darker still: simple, no noise."""
    for f in faces:
        k = {"front": 1.0, "back": 0.9, "right": 0.93, "left": 0.93, "top": 1.05, "bottom": 0.8}[f]
        x, y, w, h = mm.face_rect(s.b[part], f)
        for j in range(h):
            for i in range(w):
                s.put(x + i, y + j, mm.shade(c, k), noise=0)


def rows(s, part, c, j0, j1):
    """Rows j0..j1 of a part's four sides in another colour (a shirt's bottom edge, shoes)."""
    for f in mm.SIDES:
        x, y, w, h = mm.face_rect(s.b[part], f)
        k = {"front": 1.0, "back": 0.9, "right": 0.93, "left": 0.93}[f]
        for j in range(max(0, j0), min(h, j1 + 1)):
            for i in range(w):
                s.put(x + i, y + j, mm.shade(c, k), noise=0)


def light(c):
    return sum(c) / 3 > 140


def draw(seed, slim):
    rng = random.Random(seed)
    s = mm.Skin(rng, slim)
    body = rng.choice(COLORS)
    touch = rng.choice(TOUCHES)
    for part in s.b:
        flat(s, part, body)
    other = rng.choice([c for c in COLORS if abs(sum(c) - sum(body)) > 120] or COLORS)
    if touch == "shirt":
        flat(s, "body", other)
        for arm in ("rarm", "larm"):
            rows(s, arm, other, 0, 3)                                   # short sleeves
    elif touch == "belly":
        pale = mm.mix(body, (255, 255, 255), 0.45) if not light(body) else mm.mix(body, (255, 255, 255), 0.6)
        for i in range(2, 6):
            for j in range(3, 11):
                if not ((i in (2, 5)) and j in (3, 10)):
                    s.px("body", "front", i, j, pale, noise=0)
    elif touch == "shoes":
        for leg in ("rleg", "lleg"):
            rows(s, leg, other, 10, 11)
    eye = (24, 24, 28) if light(body) else (245, 245, 245)
    kind = rng.choice(("tall", "tall", "square", "dot"))

    def face(i, j, c):
        s.px("head", "front", i, j, c, noise=0)

    if kind == "tall":
        for i in (2, 5):
            face(i, 3, eye)
            face(i, 4, eye)
    elif kind == "square":
        for i, j in ((1, 3), (2, 3), (1, 4), (2, 4), (5, 3), (6, 3), (5, 4), (6, 4)):
            face(i, j, eye)
    else:
        face(2, 4, eye)
        face(5, 4, eye)
    extra = rng.random()
    if extra < 0.3:                                                     # a little mouth
        for i in (3, 4):
            face(i, 6, mm.shade(body, 0.6) if light(body) else mm.mix(body, (255, 255, 255), 0.5))
    elif extra < 0.5:                                                   # a blush
        pink = mm.mix(body, (255, 120, 150), 0.5)
        face(1, 5, pink)
        face(6, 5, pink)
    return s.img, f"{touch}, {kind} eyes"


def main():
    out = sys.argv[1] if len(sys.argv) > 1 else "docs/skins-blob"
    os.makedirs(out, exist_ok=True)
    for old in os.listdir(out):
        if old.startswith("blob-") and old.endswith(".png"):
            os.remove(os.path.join(out, old))
    made = []
    for n in range(24):
        slim = n % 2 == 1
        img, what = draw(9300 + n, slim)
        path = os.path.join(out, f"blob-{n + 1:02d}.png")
        img.save(path)
        made.append((path, "slim" if slim else "classic", what))
    cols = 12
    views = [mm.front_view(Image.open(p), v == "slim") for p, v, _ in made]
    w, h = 16 * 8 + 8, 32 * 8 + 8
    sheet = Image.new("RGBA", (cols * w, ((len(views) + cols - 1) // cols) * h), (226, 228, 232, 255))
    for i, v in enumerate(views):
        sheet.paste(v, ((i % cols) * w + (w - v.width) // 2, (i // cols) * h + 4), v)
    sheet.save(os.path.join(out, "preview.png"))
    print(f"{len(made)} skins in {out} (preview.png)")
    if "--sign" in sys.argv:
        pack = []
        for path, variant, what in made:
            t = mm.sign(path, variant)
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
