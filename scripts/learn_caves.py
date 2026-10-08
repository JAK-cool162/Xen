"""Learn what a cave looks like from Build Axe captures (data/buildaxe/cave.jsonl; terrain.jsonl as what isn't one).

Xen notices a cave by a window round an air block it sees under the ground (Caves.rocky: 9 x 5 x 9 blocks, how much
of it is air, and how much of what's solid is rock). Here that same window is measured at every air block with a roof
over it in the caves it was shown (caves), and at roofed air in the terrain plus open air under the sky (not caves),
and the thresholds that tell them apart best are written to assets/xen/taught/caves.json for the mod to use.

    python scripts/learn_caves.py
"""
import json
import random
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
DATA = ROOT / "data" / "buildaxe"
OUT = ROOT / "mod" / "common" / "resources" / "assets" / "xen" / "taught" / "caves.json"

ROCK = ("stone", "deepslate", "granite", "diorite", "andesite", "tuff", "calcite", "dripstone", "gravel", "dirt", "coarse_dirt",
        "sandstone", "sand", "clay", "terracotta", "basalt", "blackstone", "netherrack", "obsidian", "moss_block", "mud", "_ore",
        "bedrock", "smooth_basalt", "amethyst", "raw_")
SOFT = ("air", "cave_air", "water", "lava", "short_grass", "tall_grass", "fern", "glow_lichen", "leaf_litter", "vine", "snow",
        "torch", "dead_bush", "moss_carpet", "pointed_dripstone", "hanging_roots", "spore_blossom", "seagrass", "kelp")


def load(path):
    out = []
    if not path.exists():
        return out
    for line in path.read_text(encoding="utf-8").splitlines():
        if line.strip():
            out.append(json.loads(line))
    return out


def grid(o):
    sx, sy, sz = o["size"]
    names = [p.split("[")[0] for p in o["palette"]]
    cells = o["blocks"]
    air = [n in ("air", "cave_air") for n in names]
    solid = [not any(n == s or n.endswith(s) for s in SOFT) for n in names]
    rock = [any(r in n for r in ROCK) and s for n, s in zip(names, solid)]
    return sx, sy, sz, cells, air, solid, rock


def windows(o, roofed, cap=4000, seed=1):
    """At air blocks with (or without) a roof: air in the window, rock share of its solids, air in the 3x3x3 round it,
    and how far up its roof is (None: open sky)."""
    sx, sy, sz, cells, air, solid, rock = grid(o)
    at = lambda x, y, z: cells[(y * sz + z) * sx + x]
    picks = []
    for y in range(1, sy - 3):
        for z in range(4, sz - 4):
            for x in range(4, sx - 4):
                if not air[at(x, y, z)]:
                    continue
                up = next((k - y for k in range(y + 1, sy) if solid[at(x, k, z)]), None)
                if (up is not None and rock[at(x, y + up, z)]) == roofed:
                    picks.append((x, y, z, up))
    random.Random(seed).shuffle(picks)
    out = []
    for x, y, z, up in picks[:cap]:
        a = s = r = 0
        for dy in range(-1, 4):
            for dz in range(-4, 5):
                for dx in range(-4, 5):
                    c = at(x + dx, y + dy, z + dz)
                    if air[c]:
                        a += 1
                    elif solid[c]:
                        s += 1
                        r += rock[c]
        near = sum(air[at(x + dx, y + dy, z + dz)] for dx in (-1, 0, 1) for dy in (0, 1, 2) for dz in (-1, 0, 1) if y + dy < sy)
        if s > 0:
            out.append((a, r / s, near, up))
    return out


def pct(v, q):
    v = sorted(v)
    return v[min(len(v) - 1, max(0, int(q * len(v))))]


def main():
    caves, terrain = load(DATA / "cave.jsonl"), load(DATA / "terrain.jsonl")
    if not caves:
        sys.exit("no caves in data/buildaxe/cave.jsonl")
    pos, neg = [], []
    for o in caves:
        pos += windows(o, True, cap=500)                                         # (air under a rock roof: inside a cave; each cave counts the same)
        neg += windows(o, False, cap=800)                                        # (the open air over a cave's mouth)
    for o in terrain:
        neg += windows(o, True, cap=2000) + windows(o, False, cap=2000)
    print(f"{len(caves)} caves, {len(terrain)} terrain: {len(pos)} cave spots, {len(neg)} other spots")
    # what a cave is, most of the time (9 in 10 of the spots it was shown): the least of each, and its roof at most so high
    air_min, share, near = pct([w[0] for w in pos], 0.1), round(pct([w[1] for w in pos], 0.1), 2), pct([w[2] for w in pos], 0.1)
    roof = pct([w[3] for w in pos], 0.95)
    ok = lambda w: w[0] >= air_min and w[1] >= share and w[2] >= near and w[3] is not None and w[3] <= roof
    tpr, fpr = sum(map(ok, pos)) / len(pos), sum(map(ok, neg)) / max(1, len(neg))
    result = {"windowAir": air_min, "rockShare": share, "pocketAir": near, "roofWithin": roof, "caveSpots": len(pos), "otherSpots": len(neg),
              "caughtCaves": round(tpr, 3), "falseCaves": round(fpr, 3), "names": [o.get("name", "") for o in caves]}
    OUT.write_text(json.dumps(result, indent=1) + "\n", encoding="utf-8")
    print(f"learned: >= {air_min} air in the window, rock >= {share:.0%} of its solids, >= {near} air close round, a rock roof within "
          f"{roof}: {tpr:.0%} of cave spots, {fpr:.0%} of the others -> {OUT.relative_to(ROOT)}")


if __name__ == "__main__":
    main()
