"""Learn to tell what it's looking at (a cave entrance, the inside of a cave, a ravine, a river, a frozen river, or just
ground) from Build Axe captures: data/buildaxe/cave.jsonl and terrain.jsonl. They're for seeing, not building.

What it measures is what a Xen can see of a spot from above, column by column over 9 x 9 columns: what the top of the
ground is (water, ice or snow, sand or gravel, bare rock), how much air there is under it (a cave), how high the
tallest hollow is, how steep the drops are between columns (a ravine's walls), and how far the ground's height ranges.
Each kind (from the names the captures were given) gets its mean and spread; Sights.java measures the same in the game
and takes the nearest. Written to assets/xen/taught/sights.json.

    python scripts/learn_sights.py
"""
import json
import math
import random
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
DATA = ROOT / "data" / "buildaxe"
OUT = ROOT / "mod" / "common" / "resources" / "assets" / "xen" / "taught" / "sights.json"
FEATURES = ["water", "ice", "sand", "rock", "hollow", "tallest hollow", "steep", "range"]

PLANT = ("short_grass", "tall_grass", "fern", "large_fern", "dead_bush", "leaf_litter", "vine", "glow_lichen", "moss_carpet", "seagrass",
         "tall_seagrass", "kelp", "kelp_plant", "sugar_cane", "lily_pad", "dandelion", "poppy", "torch", "snow_layer_ignored", "bush",
         "firefly_bush", "short_dry_grass", "tall_dry_grass", "hanging_roots", "pointed_dripstone", "spore_blossom", "sweet_berry_bush")
ROCK = ("stone", "deepslate", "granite", "diorite", "andesite", "tuff", "calcite", "dripstone_block", "cobblestone", "sandstone", "_ore",
        "basalt", "blackstone", "obsidian", "bedrock", "smooth_basalt", "terracotta")


def skip(n):
    """Not ground: air, a plant, a tree."""
    return n in ("air", "cave_air", "void_air") or n in PLANT or n.endswith("_leaves") or n.endswith("_log") or n.endswith("_flower") \
        or n.endswith("_tulip") or n.endswith("_sapling") or n.endswith("_mushroom")


def kind_of(name):
    n = name.lower()
    if "ravine" in n:
        return "ravine"
    if "inside" in n:
        return "cave"
    if "frozen" in n:
        return "frozen river"
    if "entrance" in n or "surface cave" in n:
        return "cave entrance"
    if "river" in n:
        return "river"
    return None


def column(names, cells, sx, sz, top_y, x, z):
    """(height of the top, what it is, air under it, the tallest run of air under it) for one column, from the top down."""
    first, what = None, None
    hollow = run = best = 0
    for y in range(top_y, -1, -1):
        n = names[cells[(y * sz + z) * sx + x]]
        if first is None:
            if skip(n):
                continue
            first, what = y, n
            continue
        if n in ("air", "cave_air"):
            hollow += 1
            run += 1
            best = max(best, run)
        else:
            run = 0
    return first, what, hollow, best


def features(cols):
    """The 8 numbers for a 9 x 9 block of columns ({(dx, dz): (height, top, hollow, tallest)})."""
    got = [c for c in cols.values() if c[0] is not None]
    if len(got) < 20:
        return None
    n = len(got)
    water = sum(c[1] in ("water", "bubble_column") for c in got) / n
    ice = sum("ice" in c[1] or c[1] in ("snow", "snow_block", "powder_snow") for c in got) / n
    sand = sum(c[1] in ("sand", "red_sand", "gravel", "clay", "suspicious_sand") for c in got) / n
    rock = sum(any(r in c[1] for r in ROCK) for c in got) / n
    hollow = sum(min(c[2], 10) for c in got) / n / 10
    tallest = sum(min(c[3], 10) for c in got) / n / 10
    steps = []
    for (dx, dz), c in cols.items():
        if c[0] is None:
            continue
        for ox, oz in ((1, 0), (0, 1)):
            o = cols.get((dx + ox, dz + oz))
            if o and o[0] is not None:
                steps.append(abs(c[0] - o[0]))
    steep = min(1.0, (sum(steps) / len(steps)) / 5) if steps else 0
    hs = [c[0] for c in got]
    rng = min(1.0, (max(hs) - min(hs)) / 16)
    return [water, ice, sand, rock, hollow, tallest, steep, rng]


def regions(o, per=150, seed=7):
    sx, sy, sz = o["size"]
    names = [p.split("[")[0] for p in o["palette"]]
    cells = o["blocks"]
    if sx < 9 or sz < 9:
        return []
    colcache = {}

    def col(x, z):
        if (x, z) not in colcache:
            colcache[(x, z)] = column(names, cells, sx, sz, sy - 1, x, z)
        return colcache[(x, z)]

    rnd = random.Random(seed)
    out = []
    for _ in range(per):
        cx, cz = rnd.randint(4, sx - 5), rnd.randint(4, sz - 5)
        cols = {(dx, dz): col(cx + dx, cz + dz) for dx in range(-4, 5) for dz in range(-4, 5)}
        f = features(cols)
        if f:
            out.append(f)
    return out


def main():
    caves = [json.loads(l) for l in (DATA / "cave.jsonl").read_text(encoding="utf-8").splitlines() if l.strip()]
    terrain = [json.loads(l) for l in (DATA / "terrain.jsonl").read_text(encoding="utf-8").splitlines() if l.strip()] if (DATA / "terrain.jsonl").exists() else []
    samples = {}
    for o in caves + terrain:
        k = kind_of(o.get("name", ""))
        if not k:
            continue
        for f in regions(o):
            water, ice, sand, rock, hollow, tallest, steep, rng = f
            # only where the region shows what makes it that kind; elsewhere in the capture it's just ground
            shows = {"cave": hollow > 0.25, "cave entrance": hollow > 0.08, "ravine": steep > 0.25 or rng > 0.5,
                     "river": water > 0.25, "frozen river": ice > 0.25}[k]
            samples.setdefault(k if shows else "ground", []).append(f)
    allf = [f for v in samples.values() for f in v]
    mean = [sum(f[i] for f in allf) / len(allf) for i in range(8)]
    std = [max(0.05, math.sqrt(sum((f[i] - mean[i]) ** 2 for f in allf) / len(allf))) for i in range(8)]
    z = lambda f: [(f[i] - mean[i]) / std[i] for i in range(8)]
    kinds = []
    for k, fs in samples.items():
        zs = [z(f) for f in fs]
        c = [sum(v[i] for v in zs) / len(zs) for i in range(8)]
        d = sorted(math.dist(v, c) for v in zs)
        kinds.append({"kind": k, "centre": [round(v, 4) for v in c], "reach": round(max(1.5, d[int(0.9 * (len(d) - 1))]), 4), "samples": len(fs)})   # (at least 1.5: one capture can be all alike)
    # how well the nearest centre names the regions it learned from
    right = total = 0
    for k, fs in samples.items():
        for f in fs:
            v = z(f)
            best = min(kinds, key=lambda e: math.dist(v, e["centre"]))
            right += best["kind"] == k
            total += 1
    result = {"features": FEATURES, "mean": [round(v, 4) for v in mean], "std": [round(v, 4) for v in std], "kinds": kinds,
              "correct": round(right / total, 3), "regions": total}
    OUT.write_text(json.dumps(result, indent=1) + "\n", encoding="utf-8")
    print(f"{total} regions: " + ", ".join(f"{e['kind']} {e['samples']}" for e in kinds) + f"; named right {right / total:.0%} -> {OUT.relative_to(ROOT)}")


if __name__ == "__main__":
    main()
