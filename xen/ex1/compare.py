"""A Xen's play next to a player's: python -m xen.ex1.compare config/xen/gameplay_logs/*.jsonl [recordings/*.jsonl]

Reads gameplay logs (the recorder's, or Xen's own gameplay logs of Xens and players) and puts the numbers side by side,
Xens in one column and players in the other (--each: one column for every log):

  moving   how much of the time it moves, sprints (of the moving), jumps a minute, sneaks
  fights   hits a minute, the charge it hits with (median, and how often fully charged), sprint hits, how fast it
           turns to whoever hit it
  hurt     damage taken a minute and from what, deaths
  mining   blocks a minute, what, how long a block takes
  Ex1      how well Xen Ex1 (assets/xen/ex1.json) predicts its keys and looks: for a Xen, how much it plays like the
           people Ex1 learned from
"""
import argparse
import collections
import json
import math
import statistics
import sys

import numpy as np

from xen.ex1 import features as F

MODEL = "mod/common/resources/assets/xen/ex1.json"


def read(path):
    with open(path) as fh:
        return [json.loads(line) for line in fh if line.strip()]


def _bearing(rel):
    return math.degrees(math.atan2(-rel[0], rel[2]))


def turn_times(records):
    """For each hit by a creature it wasn't facing: ms until it faces it (within 30 degrees), if within 1.5 s."""
    states = [r for r in records if r.get("type", "state") == "state" and "player" in r]
    hits = [r for r in records if r.get("kind") == "damage_taken" and r.get("source_entity")]
    out = []
    j = 0
    for h in hits:
        t, kind = h.get("t_ms", 0), h["source_entity"]
        while j < len(states) and states[j].get("t_ms", 0) < t:
            j += 1
        first = None
        for s in states[max(0, j - 1):j + 40]:
            dt = s.get("t_ms", 0) - t
            if dt > 1500:
                break
            them = [n for n in s.get("nearby", []) if n.get("type") == kind]
            if not them:
                continue
            n = min(them, key=lambda n: n.get("dist", 99))
            off = abs(F.wrap(_bearing(n.get("rel", [0, 0, 1])) - s["player"].get("yaw", 0)))
            if first is None:
                first = off
                if off < 30:                                            # (already facing it: nothing to time)
                    break
            elif off < 30:
                out.append(max(0, dt))
                break
    return out


def raw(records):
    """The counts and lists of one log (summed over logs before the numbers are worked out)."""
    states = [r for r in records if r.get("type", "state") == "state" and "player" in r]
    events = [r for r in records if r.get("type") == "event"]
    if len(states) < 2:
        return None
    keys = [s.get("input", {}) for s in states]
    moving = [k for k in keys if k.get("forward") or k.get("back") or k.get("left") or k.get("right")]
    hits = [e for e in events if e.get("kind") == "swing" and e.get("target") not in (None, "block", "none")]
    hurt = [e for e in events if e.get("kind") == "damage_taken"]
    broke = [e for e in events if e.get("kind") == "block_break"]
    by = collections.Counter()
    for e in hurt:
        by[(e.get("source_entity") or e.get("source") or "?").split(":")[-1]] += e.get("amount", 0)
    if not hurt:                                                       # (the first recorder format: health drops)
        for x, y in zip(states, states[1:]):
            lost = x["player"].get("health", 20) - y["player"].get("health", 20)
            if lost > 0.5:
                by["?"] += lost
    return {
        "minutes": max(1e-6, (states[-1].get("t_ms", 0) - states[0].get("t_ms", 0)) / 60000),
        "states": len(keys), "moving": len(moving),
        "sprinting": sum(1 for st, k in zip(states, keys) if k in moving and st["player"].get("sprinting")),
        "jumps": sum(1 for a, b in zip(keys, keys[1:]) if b.get("jump") and not a.get("jump")),
        "sneaking": sum(1 for k in keys if k.get("sneak")),
        "charge": [e.get("cooldown", 0) for e in hits], "sprint_hits": sum(1 for e in hits if e.get("sprinting")),
        "turns": turn_times(records), "damage": sum(by.values()), "by": by,
        "deaths": sum(1 for e in events if e.get("kind") == "death"),
        "blocks": collections.Counter(e.get("block", "?").split(":")[-1] for e in broke),
        "ticks": [e["ticks"] for e in broke if e.get("ticks") is not None],
    }


def combine(raws):
    raws = [r for r in raws if r]
    if not raws:
        return None
    out = {}
    for k in raws[0]:
        v = raws[0][k]
        if isinstance(v, list):
            out[k] = [x for r in raws for x in r[k]]
        elif isinstance(v, collections.Counter):
            out[k] = sum((r[k] for r in raws), collections.Counter())
        else:
            out[k] = sum(r[k] for r in raws)
    return out


def summary(r):
    m, n = r["minutes"], len(r["charge"])
    med = lambda xs: statistics.median(xs) if xs else None
    return {
        "minutes": round(m, 1),
        "moving": round(r["moving"] / r["states"], 2),
        "sprinting (of moving)": round(r["sprinting"] / max(1, r["moving"]), 2),
        "jumps a minute": round(r["jumps"] / m, 1),
        "sneaking": round(r["sneaking"] / r["states"], 2),
        "hits a minute": round(n / m, 1),
        "charge at a hit (median)": round(med(r["charge"]), 2) if n else None,
        "fully charged hits": round(sum(1 for c in r["charge"] if c >= 0.9) / n, 2) if n else None,
        "sprint hits": round(r["sprint_hits"] / n, 2) if n else None,
        "turns to a hit (median ms)": int(med(r["turns"])) if r["turns"] else None,
        "damage a minute": round(r["damage"] / m, 2),
        "damage from": ", ".join(f"{k} {v:.0f}" for k, v in r["by"].most_common(4)) or "-",
        "deaths": r["deaths"],
        "blocks a minute": round(sum(r["blocks"].values()) / m, 1),
        "blocks": ", ".join(f"{k} {v}" for k, v in r["blocks"].most_common(3)) or "-",
        "ticks a block (median)": int(med(r["ticks"])) if r["ticks"] else None,
    }


def ex1_scores(fr, model):
    """How well Ex1 predicts this play (its frames): forward, sprint and jump keys right, pitch error, danger AUC."""
    if len(fr) < 20:
        return {}
    X = np.array([f[0] for f in fr], np.float32)
    K = np.array([f[1] for f in fr], np.float32)
    P = np.array([f[3] for f in fr], np.float32)
    D = np.array([f[4] for f in fr], np.float32)
    x = (X - np.array(model["mean"], np.float32)) / np.array(model["std"], np.float32)
    layer = lambda a, n: a @ np.array(model[n]["w"], np.float32) + np.array(model[n]["b"], np.float32)
    h = np.tanh(layer(np.tanh(layer(x, "l1")), "l2"))
    keys = 1 / (1 + np.exp(-np.clip(layer(h, "keys"), -30, 30))) > 0.5
    pitch = np.tanh(layer(h, "pitch")).ravel()
    danger = 1 / (1 + np.exp(-np.clip(layer(h, "danger"), -30, 30))).ravel()
    out = {f"Ex1 gets {k} right": round(float((keys[:, F.KEYS.index(k)] == (K[:, F.KEYS.index(k)] > 0)).mean()), 2)
           for k in ("forward", "sprint", "jump")}
    out["Ex1 pitch error (deg)"] = round(float(np.abs(pitch - P).mean() * 90), 1)
    pos, neg = danger[D > 0], danger[D <= 0]
    if len(pos) and len(neg):
        order = np.argsort(np.concatenate([pos, neg]))
        ranks = np.empty(len(order))
        ranks[order] = np.arange(1, len(order) + 1)
        out["Ex1 danger AUC"] = round(float((ranks[:len(pos)].sum() - len(pos) * (len(pos) + 1) / 2) / (len(pos) * len(neg))), 2)
    return out


def main(argv=None):
    ap = argparse.ArgumentParser(description=__doc__.split("\n")[0])
    ap.add_argument("logs", nargs="+")
    ap.add_argument("--each", action="store_true", help="one column for every log")
    ap.add_argument("--model", default=MODEL)
    ap.add_argument("--json", action="store_true")
    a = ap.parse_args(argv)
    try:
        with open(a.model) as fh:
            model = json.load(fh)
    except OSError:
        model = None
    columns = collections.OrderedDict()
    groups = collections.defaultdict(list)
    for path in a.logs:
        records = read(path)
        r = raw(records)
        if r is None:
            continue
        src = F.source(records)
        name = next((x.get("who") for x in records[:3] if x.get("who")), path.rsplit("/", 1)[-1])
        key = f"{src}: {name}" if a.each else f"{'Xens' if src == 'xen' else 'players'}"
        groups[key].append((r, F.frames(records) if model else []))
    for key in sorted(groups, key=lambda k: (not k.lower().startswith("xen"), k)):
        logs = groups[key]
        row = summary(combine([r for r, _ in logs]))
        if model:
            row.update(ex1_scores([f for _, fr in logs for f in fr], model))
        columns[key if a.each else f"{key} ({len(logs)} logs)"] = row
    if a.json:
        print(json.dumps(columns, indent=1))
        return
    if not columns:
        print("no logs with play in them")
        return
    keys = list(next(iter(columns.values())).keys())
    for c in columns.values():
        keys += [k for k in c if k not in keys]
    width = max(len(k) for k in keys) + 2
    colw = [max(len(n), 12) + 2 for n in columns]
    print("".ljust(width) + "".join(n.ljust(w) for n, w in zip(columns, colw)))
    for k in keys:
        print(k.ljust(width) + "".join(("-" if c.get(k) is None else str(c.get(k))).ljust(w) for c, w in zip(columns.values(), colw)))


if __name__ == "__main__":
    main(sys.argv[1:])
