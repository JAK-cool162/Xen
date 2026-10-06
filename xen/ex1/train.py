"""Train Xen Ex1 on recorded play: python -m xen.ex1.train recordings/*.jsonl --out mod/common/resources/assets/xen/ex1.json

One small network (119 senses -> 64 -> 64), four heads: the keys a player presses (forward, back, left, right, jump,
sneak, sprint, attack, use), how it turns in the next quarter second, where it looks next (pitch), and danger: will it
be hurt in the next two seconds. Numpy only (no GPU, no framework). The last fifth of each recording is held out to
see what it really learned. In the game the danger head keeps learning from each Xen's own hurts (Ex1.java).
"""
import argparse
import json
import math
import sys

import numpy as np

from xen.ex1 import features as F

H = 64
HEADS = {"keys": len(F.KEYS), "yaw": len(F.YAW_BINS) + 1, "pitch": 1, "danger": 1}


def load(paths):
    train, test = [], []
    for path in paths:
        with open(path) as fh:
            records = [json.loads(line) for line in fh if line.strip()]
        fr = F.frames(records)
        cut = int(len(fr) * 0.8)
        train += fr[:cut]
        test += fr[cut:]
    return train, test


def arrays(fr):
    X = np.array([f[0] for f in fr], dtype=np.float32)
    K = np.array([f[1] for f in fr], dtype=np.float32)
    Y = np.array([f[2] for f in fr], dtype=np.int64)
    P = np.array([[f[3]] for f in fr], dtype=np.float32)
    D = np.array([[f[4]] for f in fr], dtype=np.float32)
    return X, K, Y, P, D


def init(rng, n_in, n_out):
    return {"w": (rng.standard_normal((n_in, n_out)) * math.sqrt(2.0 / n_in)).astype(np.float32), "b": np.zeros(n_out, np.float32)}


def sigmoid(z):
    return 1.0 / (1.0 + np.exp(-np.clip(z, -30, 30)))


def forward(net, X):
    h1 = np.tanh(X @ net["l1"]["w"] + net["l1"]["b"])
    h2 = np.tanh(h1 @ net["l2"]["w"] + net["l2"]["b"])
    out = {k: h2 @ net[k]["w"] + net[k]["b"] for k in HEADS}
    return h1, h2, out


def train(paths, out, epochs=15, seed=7, lr=2e-3, wd=1e-2):
    tr, te = load(paths)
    X, K, Y, P, D = arrays(tr)
    Xt, Kt, Yt, Pt, Dt = arrays(te)
    mean, std = X.mean(0), X.std(0) + 1e-3
    norm = lambda a: (a - mean) / std
    Xn, Xtn = norm(X), norm(Xt)
    rng = np.random.default_rng(seed)
    net = {"l1": init(rng, F.N, H), "l2": init(rng, H, H)}
    for k, n in HEADS.items():
        net[k] = init(rng, H, n)
    params = [(layer, key) for layer in net for key in ("w", "b")]
    m = {(l, k): np.zeros_like(net[l][k]) for l, k in params}
    v = {(l, k): np.zeros_like(net[l][k]) for l, k in params}
    pos = max(1.0, D.sum())
    dw = (len(D) - pos) / pos                                         # (hurts are rare: each counts as much as all the calm frames)
    step = 0
    n = len(X)
    for ep in range(epochs):
        order = rng.permutation(n)
        for s in range(0, n, 128):
            b = order[s:s + 128]
            x = Xn[b]
            h1, h2, o = forward(net, x)
            g = {}
            pk = sigmoid(o["keys"])
            g["keys"] = (pk - K[b]) / len(b)
            ey = np.exp(o["yaw"] - o["yaw"].max(1, keepdims=True))
            py = ey / ey.sum(1, keepdims=True)
            oh = np.eye(HEADS["yaw"], dtype=np.float32)[Y[b]]
            g["yaw"] = (py - oh) / len(b)
            g["pitch"] = 2 * (np.tanh(o["pitch"]) - P[b]) * (1 - np.tanh(o["pitch"]) ** 2) / len(b)
            pd = sigmoid(o["danger"])
            wdg = np.where(D[b] > 0, dw, 1.0) / (1.0 + dw) * 2
            g["danger"] = (pd - D[b]) * wdg / len(b)
            grads = {}
            dh2 = np.zeros_like(h2)
            for k in HEADS:
                grads[(k, "w")] = h2.T @ g[k]
                grads[(k, "b")] = g[k].sum(0)
                dh2 += g[k] @ net[k]["w"].T
            dz2 = dh2 * (1 - h2 ** 2)
            grads[("l2", "w")] = h1.T @ dz2
            grads[("l2", "b")] = dz2.sum(0)
            dz1 = (dz2 @ net["l2"]["w"].T) * (1 - h1 ** 2)
            grads[("l1", "w")] = x.T @ dz1
            grads[("l1", "b")] = dz1.sum(0)
            step += 1
            for key in params:
                gr = grads[key] + (wd * net[key[0]][key[1]] if key[1] == "w" else 0)
                m[key] = 0.9 * m[key] + 0.1 * gr
                v[key] = 0.999 * v[key] + 0.001 * gr * gr
                mh, vh = m[key] / (1 - 0.9 ** step), v[key] / (1 - 0.999 ** step)
                net[key[0]][key[1]] -= lr * mh / (np.sqrt(vh) + 1e-8)
    report = evaluate(net, Xtn, Kt, Yt, Pt, Dt, K, Y)
    model = {"name": "Xen Ex1", "version": 1, "features": F.N, "hidden": H, "keys": list(F.KEYS), "yaw_bins": list(F.YAW_BINS),
             "trained_on": {"frames": int(n), "held_out": int(len(Xt)), "hurt_frames": int(D.sum())}, "report": report,
             "mean": [round(float(a), 6) for a in mean], "std": [round(float(a), 6) for a in std]}
    for layer in net:
        model[layer] = {"w": [[round(float(a), 6) for a in row] for row in net[layer]["w"]], "b": [round(float(a), 6) for a in net[layer]["b"]]}
    with open(out, "w") as fh:
        json.dump(model, fh, separators=(",", ":"))
    return model, net, mean, std


def auc(score, label):
    pos, neg = score[label > 0], score[label <= 0]
    if len(pos) == 0 or len(neg) == 0:
        return float("nan")
    order = np.argsort(np.concatenate([pos, neg]))
    ranks = np.empty(len(order))
    ranks[order] = np.arange(1, len(order) + 1)
    return float((ranks[:len(pos)].sum() - len(pos) * (len(pos) + 1) / 2) / (len(pos) * len(neg)))


def evaluate(net, X, K, Y, P, D, Ktrain, Ytrain):
    _, _, o = forward(net, X)
    pk = sigmoid(o["keys"]) > 0.5
    base = Ktrain.mean(0) > 0.5
    keys = {k: {"acc": round(float((pk[:, i] == (K[:, i] > 0)).mean()), 3), "always_same": round(float((base[i] == (K[:, i] > 0)).mean()), 3)}
            for i, k in enumerate(F.KEYS)}
    yaw_acc = float((o["yaw"].argmax(1) == Y).mean())
    yaw_base = float((np.bincount(Ytrain, minlength=HEADS["yaw"]).argmax() == Y).mean())
    pitch_err = float(np.abs(np.tanh(o["pitch"]) - P).mean() * 90)
    pitch_base = float(np.abs(P - P.mean()).mean() * 90)
    danger_auc = auc(sigmoid(o["danger"]).ravel(), D.ravel())
    return {"keys": keys, "yaw_acc": round(yaw_acc, 3), "yaw_always_same": round(yaw_base, 3), "pitch_err_deg": round(pitch_err, 1),
            "pitch_err_guess_deg": round(pitch_base, 1), "danger_auc": round(danger_auc, 3), "held_out_hurt_frames": int(D.sum())}


def fixture(net, mean, std, paths, out):
    """A few frames, their 119 senses and what Ex1 makes of them: Java must agree (crossCheck)."""
    with open(paths[0]) as fh:
        records = [json.loads(line) for line in fh if line.strip()]
    fr = F.frames(records)
    picks = [fr[i] for i in range(0, len(fr), max(1, len(fr) // 6))][:6]
    cases = []
    for x, *_ in picks:
        xn = (np.array([x], np.float32) - mean) / std
        _, _, o = forward(net, xn)
        cases.append({"x": [round(float(a), 6) for a in x], "keys": [round(float(a), 5) for a in sigmoid(o["keys"])[0]],
                      "danger": round(float(sigmoid(o["danger"])[0, 0]), 5), "pitch": round(float(np.tanh(o["pitch"])[0, 0]), 5)})
    names = ["minecraft:air", "minecraft:stone", "minecraft:water", "minecraft:lava", "minecraft:leaf_litter", "minecraft:glow_lichen",
             "minecraft:oak_leaves", "minecraft:short_grass", "minecraft:crafting_table", "minecraft:snow_block", "minecraft:torch",
             "minecraft:iron_ore", "minecraft:poppy", "minecraft:oak_log"]
    hands = ["minecraft:iron_sword", "minecraft:stone_pickaxe", "minecraft:iron_axe", "minecraft:wooden_shovel", "minecraft:bread",
             "minecraft:cooked_beef", "minecraft:cobblestone", "minecraft:oak_planks", "minecraft:shield", "empty", "minecraft:torch"]
    with open(out, "w") as fh:
        json.dump({"cases": cases, "category": {n: F.category(n) for n in names}, "hand": {n: F.hand(n) for n in hands}}, fh)


def main(argv=None):
    ap = argparse.ArgumentParser(description=__doc__.split("\n")[0])
    ap.add_argument("recordings", nargs="+")
    ap.add_argument("--out", default="mod/common/resources/assets/xen/ex1.json")
    ap.add_argument("--fixture", default="mod/common/test/fixtures/ex1.json")
    ap.add_argument("--epochs", type=int, default=15)
    a = ap.parse_args(argv)
    model, net, mean, std = train(a.recordings, a.out, a.epochs)
    fixture(net, mean, std, a.recordings, a.fixture)
    print(json.dumps({"trained_on": model["trained_on"], "report": model["report"]}, indent=1))


if __name__ == "__main__":
    main(sys.argv[1:])
