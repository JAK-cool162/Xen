"""Learn to tell what a Xen is looking at from the world itself: spots in land Minecraft generated, each labelled from all
its blocks by WorldTruth (/xen terrain sample: the inside of a cave, a cave entrance, a ravine, a river, a frozen river,
or ground), with the same 8 numbers a Xen measures (Sights.measure). The game's own world generator is the simulator:
generate more land, get more examples.

Each kind gets a few centres (k-means: a cave can be a narrow tunnel or a great hall), each with its reach; Sights.java
takes the nearest. It's tested fairly: on squares of land (256 x 256) it never learned from.

    python scripts/learn_terrain.py <xen-terrain folder or .jsonl files...>   (-> assets/xen/taught/sights.json)
"""
import json
import math
import random
import sys
from collections import Counter, defaultdict
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
OUT = ROOT / "mod" / "common" / "resources" / "assets" / "xen" / "taught" / "sights.json"
FEATURES = ["water", "ice", "sand", "rock", "hollow", "tallest hollow", "steep", "range"]
SHOWS = {"cave": lambda f: f[4] > 0.25, "cave entrance": lambda f: f[4] > 0.08, "ravine": lambda f: f[6] > 0.25 or f[7] > 0.5,
         "river": lambda f: f[0] > 0.25, "frozen river": lambda f: f[1] > 0.25, "ground": lambda f: True}   # (as Sights.name)


def load(paths):
    rows = []
    for p in paths:
        p = Path(p)
        for f in sorted(p.glob("*.jsonl")) if p.is_dir() else [p]:
            if f.name.startswith("caves-"):
                continue
            for line in f.read_text(encoding="utf-8").splitlines():
                if line.strip():
                    o = json.loads(line)
                    rows.append((o["kind"], o["features"], (o["x"] // 256, o["z"] // 256, f.stem)))
    return rows


def kmeans(points, k, rnd, rounds=25):
    centres = [list(c) for c in rnd.sample(points, k)]
    for _ in range(rounds):
        groups = [[] for _ in centres]
        for v in points:
            groups[min(range(len(centres)), key=lambda i: math.dist(v, centres[i]))].append(v)
        centres = [[sum(v[i] for v in g) / len(g) for i in range(8)] if g else centres[j] for j, g in enumerate(groups)]
    return centres, groups


def fit(rows, rnd):
    allf = [f for _, f, _ in rows]
    mean = [sum(f[i] for f in allf) / len(allf) for i in range(8)]
    std = [max(0.05, math.sqrt(sum((f[i] - mean[i]) ** 2 for f in allf) / len(allf))) for i in range(8)]
    z = lambda f: [(f[i] - mean[i]) / std[i] for i in range(8)]
    by = defaultdict(list)
    for k, f, _ in rows:
        by[k].append(z(f))
    kinds = []
    for k, zs in by.items():
        n = max(1, min(8, len(zs) // 40))
        centres, groups = kmeans(zs, n, rnd)
        for c, g in zip(centres, groups):
            if len(g) < 5:
                continue
            d = sorted(math.dist(v, c) for v in g)
            kinds.append({"kind": k, "centre": [round(v, 4) for v in c], "reach": round(max(1.0, d[int(0.9 * (len(d) - 1))]), 4), "samples": len(g)})
    return mean, std, kinds


def name(mean, std, kinds, f):
    v = [(f[i] - mean[i]) / std[i] for i in range(8)]
    best = min(kinds, key=lambda e: math.dist(v, e["centre"]))
    if best["kind"] == "ground" or math.dist(v, best["centre"]) > best["reach"] or not SHOWS[best["kind"]](f):
        return "ground"
    return best["kind"]


def main():
    paths = sys.argv[1:] or [ROOT / "server-chat" / "xen-terrain"]
    rows = load(paths)
    rnd = random.Random(7)
    print(f"{len(rows)} spots: {dict(Counter(k for k, _, _ in rows))}")
    # held out: every fourth square of land (by its place), never learned from
    squares = sorted({sq for _, _, sq in rows})
    rnd.shuffle(squares)
    test = set(squares[::4])
    train = [r for r in rows if r[2] not in test]
    held = [r for r in rows if r[2] in test]
    mean, std, kinds = fit(train, rnd)
    conf, tot = Counter(), Counter()
    for k, f, _ in held:
        conf[(k, name(mean, std, kinds, f))] += 1
        tot[k] += 1
    print(f"held out ({len(held)} spots on {len(test)} squares of land it never saw):")
    for k in sorted(tot):
        got = {p: n for (t, p), n in conf.items() if t == k}
        print(f"  {k:14s} {tot[k]:5d}: right {conf[(k, k)] / tot[k]:4.0%}  {dict(sorted(got.items(), key=lambda e: -e[1]))}")
    named = sum(n for (t, p), n in conf.items() if p != "ground")
    rightly = sum(n for (t, p), n in conf.items() if p != "ground" and p == t)
    print(f"  when it names something (not ground): right {rightly / max(1, named):.0%} of {named}")
    held_right = sum(conf[(k, k)] for k in tot) / max(1, len(held))
    mean, std, kinds = fit(rows, rnd)                                   # (then from all of it, for the game)
    OUT.write_text(json.dumps({"features": FEATURES, "mean": [round(v, 4) for v in mean], "std": [round(v, 4) for v in std], "kinds": kinds,
                               "correct": round(held_right, 3), "regions": len(rows), "from": "WorldTruth (generated land)"}, indent=1) + "\n",
                   encoding="utf-8")
    print(f"{len(kinds)} centres -> {OUT.relative_to(ROOT)} (held-out right {held_right:.0%})")


if __name__ == "__main__":
    main()
