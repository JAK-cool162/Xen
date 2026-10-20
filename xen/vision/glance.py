"""Xen Glance: train the small picture network on Xens' own pictures (what their eyes drew) against what was really there.

Pictures come from a server run with -Dxen.visionDump=<folder> (one glance_<name>.bin per Xen). Each record: game time
(8 bytes), health (4), four labels (monster close, monster near, drop ahead, cave mouth) and the picture halved to 32 by
18: red, green, blue and distance, a byte each. The net sees only the picture.

    python -m xen.vision.glance <folder>... [--out mod/common/resources/assets/xen/glance.json]

The last fifth of each Xen's pictures is held out; it prints how well each answer does on those (AUC), and writes the
weights the mod reads (core/Glance.java) and a fixture the mod's crossCheck runs the same pictures through.
"""
import argparse
import glob
import json
import os
import struct
import sys

import numpy as np

W, H, C = 32, 18, 4
HEADS = ["monster close", "monster near", "drop ahead", "cave mouth"]
REC = 8 + 4 + 4 + C * H * W


def read(path):
    data = open(path, "rb").read()
    n = len(data) // REC
    X = np.zeros((n, C, H, W), np.float32)
    Y = np.zeros((n, len(HEADS)), np.float32)
    for i in range(n):
        r = data[i * REC:(i + 1) * REC]
        Y[i] = np.frombuffer(r[12:16], np.uint8)
        X[i] = np.frombuffer(r[16:], np.uint8).reshape(C, H, W) / 255.0
    return X, Y


def load(folders):
    tr, te = [], []
    folders = [folders] if isinstance(folders, str) else folders
    for p in sorted(q for f in folders for q in glob.glob(os.path.join(f, "glance_*.bin"))):
        X, Y = read(p)
        cut = int(len(X) * 0.8)
        tr.append((X[:cut], Y[:cut]))
        te.append((X[cut:], Y[cut:]))
    cat = lambda parts, k: np.concatenate([p[k] for p in parts]) if parts else np.zeros((0,))
    return cat(tr, 0), cat(tr, 1), cat(te, 0), cat(te, 1)


def auc(y, s):
    y = np.asarray(y, bool)
    if y.all() or not y.any():
        return float("nan")
    order = np.argsort(s)
    ranks = np.empty(len(s))
    ranks[order] = np.arange(1, len(s) + 1)
    pos = y.sum()
    return float((ranks[y].sum() - pos * (pos + 1) / 2) / (pos * (len(y) - pos)))


def model():
    import torch.nn as nn

    class Net(nn.Module):
        def __init__(self):
            super().__init__()
            self.c1, self.c2, self.c3 = nn.Conv2d(C, 8, 3, padding=1), nn.Conv2d(8, 16, 3, padding=1), nn.Conv2d(16, 16, 3, padding=1)
            self.pool = nn.MaxPool2d(2)
            self.head = nn.Linear(32, len(HEADS))

        def forward(self, x):
            import torch
            x = self.pool(torch.relu(self.c1(x)))
            x = self.pool(torch.relu(self.c2(x)))
            x = torch.relu(self.c3(x))
            f = torch.cat([x.mean(dim=(2, 3)), x.amax(dim=(2, 3))], 1)
            return self.head(f)

    return Net()


def train(folders, out, fixture=None, epochs=30, seed=7):
    import torch
    torch.manual_seed(seed)
    X, Y, Xt, Yt = load(folders)
    if len(X) == 0:
        sys.exit(f"no pictures in {folders}")
    net = model()
    pos = Y.mean(0).clip(0.01, 0.99)
    loss_fn = torch.nn.BCEWithLogitsLoss(pos_weight=torch.tensor((1 - pos) / pos).clamp(max=20))
    opt = torch.optim.AdamW(net.parameters(), lr=3e-3, weight_decay=1e-3)
    Xs, Ys = torch.tensor(X), torch.tensor(Y)
    for ep in range(epochs):
        perm = torch.randperm(len(Xs))
        net.train()
        total = 0.0
        for i in range(0, len(Xs), 128):
            idx = perm[i:i + 128]
            xb = Xs[idx]
            if torch.rand(1).item() < 0.5:                       # (a mirrored view is the same view)
                xb = xb.flip(3)
            opt.zero_grad()
            loss = loss_fn(net(xb), Ys[idx])
            loss.backward()
            opt.step()
            total += loss.item() * len(idx)
        if ep % 5 == 4 or ep == epochs - 1:
            print(f"epoch {ep + 1}: loss {total / len(Xs):.4f}", file=sys.stderr)
    with torch.no_grad():                                        # (balanced classes in training: back to real chances, shift each answer by its weight)
        net.head.bias -= torch.log(loss_fn.pos_weight)
    net.eval()
    with torch.no_grad():
        st = torch.sigmoid(net(torch.tensor(Xt))).numpy() if len(Xt) else np.zeros((0, len(HEADS)))
    scores = {h: {"auc": round(auc(Yt[:, i], st[:, i]), 3) if len(Xt) else None, "positives": int(Yt[:, i].sum()), "held_out": int(len(Xt)),
                  "mean_if_there": round(float(st[Yt[:, i] > 0, i].mean()), 3) if Yt[:, i].any() else None,
                  "mean_if_not": round(float(st[Yt[:, i] == 0, i].mean()), 3) if len(Xt) else None}
              for i, h in enumerate(HEADS)}
    sd = {k: v.detach().numpy() for k, v in net.state_dict().items()}
    js = {"conv": [{"w": sd[f"c{l}.weight"].reshape(sd[f"c{l}.weight"].shape[0], sd[f"c{l}.weight"].shape[1], 9).round(6).tolist(),
                    "b": sd[f"c{l}.bias"].round(6).tolist()} for l in (1, 2, 3)],
          "head": {"w": sd["head.weight"].round(6).tolist(), "b": sd["head.bias"].round(6).tolist()},
          "heads": HEADS, "trained_on": int(len(X)), "held_out": scores}
    with open(out, "w") as fh:
        json.dump(js, fh)
    if fixture:                                                  # the same pictures through the rounded weights: Java must agree
        net2 = model()
        sd2 = {k: torch.tensor(np.round(v, 6)) for k, v in sd.items()}
        net2.load_state_dict(sd2)
        picks = Xt[:: max(1, len(Xt) // 5)][:5] if len(Xt) else X[:5]
        with torch.no_grad():
            o = torch.sigmoid(net2(torch.tensor(picks))).numpy()
        with open(fixture, "w") as fh:
            json.dump({"cases": [{"x": (np.round(p * 255) / 255).astype(np.float32).round(6).reshape(C, -1).tolist(), "out": oo.round(5).tolist()}
                                 for p, oo in zip(picks, o)]}, fh)
    print(json.dumps({"trained_on": int(len(X)), "held_out": scores}, indent=1))
    return js


def main(argv=None):
    ap = argparse.ArgumentParser(description=__doc__.split("\n")[0])
    ap.add_argument("folders", nargs="+")
    ap.add_argument("--out", default="mod/common/resources/assets/xen/glance.json")
    ap.add_argument("--fixture", default="mod/common/test/fixtures/glance.json")
    ap.add_argument("--epochs", type=int, default=30)
    a = ap.parse_args(argv)
    train(a.folders, a.out, a.fixture, a.epochs)


if __name__ == "__main__":
    main()
