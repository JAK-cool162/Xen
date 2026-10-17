"""The mod's recorder (F8: xen_recordings/session_.../log.jsonl.gz) as Ex1's frames.

Ex1 learned from an older recorder whose frames said what's at your feet, every creature around you and the light
where you stand. The mod's recorder (format "xen-recorder 1") has your view instead: what each of 64 x 36 sight lines
meets, how far and how light. So for those, what Ex1 needs is worked out from the view: the creatures in it (not behind
you), the light of the ground in front of you, solid ground under you when you're on the ground, sprinting from how fast
you go (a sprint key held down says nothing with toggle sprint on). From "xen-recorder 2" on, the recorder writes all
of it down as it is, and this reads it as is.

    python -m xen.ex1.train recordings/*.jsonl xen_recordings/session_*     (session folders are read through this)
"""
import base64
import gzip
import json
import math
import os

HOSTILE = {"zombie", "zombie_villager", "husk", "drowned", "skeleton", "stray", "bogged", "wither_skeleton", "creeper", "spider",
           "cave_spider", "enderman", "endermite", "silverfish", "witch", "slime", "magma_cube", "phantom", "pillager",
           "vindicator", "evoker", "vex", "ravager", "illusioner", "blaze", "ghast", "piglin", "piglin_brute", "zombified_piglin",
           "hoglin", "zoglin", "guardian", "elder_guardian", "shulker", "warden", "breeze", "creaking", "wither", "ender_dragon"}
NOTHING = {"sky", "fog", ""}
OLD_FOV_V, OLD_FOV_H, OLD_ROWS, OLD_COLS = 90.0, 130.626, 14, 24


def is_session(path):
    return os.path.isdir(path) and os.path.exists(os.path.join(path, "log.jsonl.gz"))


def lines(path):
    with gzip.open(os.path.join(path, "log.jsonl.gz"), "rt", encoding="utf-8") as fh:
        for line in fh:
            line = line.strip()
            if line:
                try:
                    yield json.loads(line)
                except ValueError:                          # (the last line of a recording cut short)
                    return


def runs(rle):
    out = []
    for part in rle.split(","):
        if "x" in part:
            v, n = part.split("x")
            out += [int(v)] * int(n)
    return out


def ns(name):
    return "empty" if not name else name if ":" in name else "minecraft:" + name


def records(path):
    """The session's states as the older recorder's records (Ex1's features.frames reads them as they are)."""
    head, labels, out, shield, last = {}, {}, [], False, None
    raw = list(lines(path))
    for r in raw:
        if r.get("event") == "use" and r.get("item") == "shield":
            shield = True                                   # (a shield used: it's in the other hand)
    for r in raw:
        if "session" in r:
            head = r
            continue
        if "labels" in r:
            labels.update({int(k): v for k, v in r["labels"].items()})
            continue
        if r.get("event") in ("hurt", "death"):
            out.append({"type": "event", "kind": "damage_taken", "t_ms": r["t"]})
            continue
        if "frame" not in r:
            continue
        w, h = head.get("labels_size", [64, 36])
        what = runs(r["what"])
        if len(what) != w * h:
            continue
        dist = base64.b64decode(r["dist"])
        lf = base64.b64decode(r["light_face"])
        rec = convert(r, labels, what, dist, lf, w, h, last, shield)
        last = r
        out.append(rec)
    return out


def convert(r, labels, what, dist, lf, w, h, last, shield):
    keys = r["keys"]
    yaw, pitch = r["yaw"], r["pitch"]
    if "vel" in r:
        vel = r["vel"]
    elif last is not None and r["t"] > last["t"]:
        ticks = max(1.0, (r["t"] - last["t"]) / 50.0)
        vel = [(a - b) / ticks for a, b in zip(r["pos"], last["pos"])]
    else:
        vel = [0.0, 0.0, 0.0]
    speed = math.hypot(vel[0], vel[2])
    sprinting = r.get("sprinting", keys["w"] and speed > 0.25 and r["on_ground"])
    fov = r.get("fov", 70.0)
    tan_v = math.tan(math.radians(fov) / 2)
    tan_h = tan_v * w / h                                   # (the picture is 64 x 36: 16:9, as the game window mostly is)

    def at(dyaw, dpitch):
        """The pixel a sight line this far off the middle of your view lands on (the edge one, if it's off the picture)."""
        x = 0.5 + math.tan(math.radians(max(-80, min(80, dyaw)))) / tan_h / 2
        y = 0.5 + math.tan(math.radians(max(-80, min(80, dpitch)))) / tan_v / 2
        return min(h - 1, max(0, int(y * h))) * w + min(w - 1, max(0, int(x * w)))

    def far(i):
        name = labels.get(what[i], "")
        return name in NOTHING or dist[i] >= 252

    # the view, as the older recorder's 14 x 24 near grid
    palette, index, idx, dd = [], {}, [], []
    for row in range(OLD_ROWS):
        for col in range(OLD_COLS):
            i = at(-OLD_FOV_H / 2 + (col + 0.5) * OLD_FOV_H / OLD_COLS, -OLD_FOV_V / 2 + (row + 0.5) * OLD_FOV_V / OLD_ROWS)
            name = labels.get(what[i], "")
            if far(i):
                dd.append(None)
                idx.append(-1)
                continue
            dd.append(dist[i] / 4.0)
            key = "minecraft:" + name if not name.startswith("mob:") else "minecraft:" + name[4:]
            if key not in index:
                index[key] = len(palette)
                palette.append(key)
            idx.append(index[key])
    cells = [at(-OLD_FOV_H / 2 + (3 * j + 1.5) * OLD_FOV_H / OLD_COLS, -OLD_FOV_V / 2 + (2 * i + 1.5) * OLD_FOV_V / OLD_ROWS)
             for i in range(7) for j in range(8)]
    vision = {"open_frac": sum(1 for i in cells if far(i)) / len(cells),
              "near": {"rows": OLD_ROWS, "cols": OLD_COLS, "palette": palette, "idx": idx, "dist": dd}}

    # what you look at
    mid = at(0, 0)
    la = r.get("looking_at", "")
    if la.startswith("mob:"):
        looking = {"type": "entity", "id": ns(la[4:]), "distance": dist[mid] / 4.0, "hostile": la[4:] in HOSTILE}
    elif la:
        looking = {"type": "block", "id": ns(la), "distance": dist[mid] / 4.0}
    else:
        looking = {"type": "none"}

    # creatures: as written down (recorder 2), or the ones in view
    if "near" in r:
        nearby = [{"type": ns(n["k"]), "dist": n["d"], "rel": n["r"], "hostile": n["h"]} for n in r["near"]]
    else:
        seen = {}
        for i, v in enumerate(what):
            name = labels.get(v, "")
            if name.startswith("mob:") and (name not in seen or dist[i] < dist[seen[name]]):
                seen[name] = i
        nearby = []
        for name, i in seen.items():
            px, py = i % w, i // w
            dy = math.degrees(math.atan((px + 0.5 - w / 2) / (w / 2) * tan_h))
            dp = math.degrees(math.atan((py + 0.5 - h / 2) / (h / 2) * tan_v))
            d = dist[i] / 4.0
            a = math.radians(yaw + dy)
            nearby.append({"type": ns(name[4:]), "dist": d, "hostile": name[4:] in HOSTILE,
                           "rel": [-math.sin(a) * d * math.cos(math.radians(pitch + dp)), -math.sin(math.radians(pitch + dp)) * d,
                                   math.cos(a) * d * math.cos(math.radians(pitch + dp))]})

    # the light where you stand: as written down, or the ground just in front of you
    if "light" in r:
        light = r["light"]
    else:
        ground = [lf[i] >> 4 for i in range(w * (h * 2 // 3), w * h) if w // 3 <= i % w < 2 * w // 3 and not far(i) and dist[i] < 24]
        light = sorted(ground)[len(ground) // 2] if ground else 15

    if "below" in r:
        blocks = {"below": ns(r["below"]), "feet": ns(r["feet"]), "head": ns(r["head"])}
    else:
        wet = "minecraft:water" if r["in_water"] else "minecraft:air"
        blocks = {"below": "minecraft:stone" if r["on_ground"] else wet, "feet": wet, "head": wet}

    return {"t_ms": r["t"], "dimension": "minecraft:" + r.get("dim", "overworld"), "raining": r.get("rain", False), "light": light,
            "player": {"pos": r["pos"], "velocity": vel, "yaw": yaw, "pitch": pitch, "on_ground": r["on_ground"], "sprinting": sprinting,
                       "sneaking": r.get("sneaking", keys["sneak"]), "in_water": r["in_water"], "health": r["hp"], "food": r["food"]},
            "input": {"forward": keys["w"], "back": keys["s"], "left": keys["a"], "right": keys["d"], "jump": keys["jump"],
                      "sneak": keys["sneak"], "sprint": sprinting, "attack": keys["attack"], "use": keys["use"]},
            "main_hand": {"id": ns(r["hand"])}, "off_hand": {"id": ns(r.get("off", "shield" if shield else ""))},
            "using_item": r.get("using", keys["use"]), "looking_at": looking, "blocks": blocks, "nearby": nearby,
            "vision": vision, "screen": r.get("spectator", False)}
