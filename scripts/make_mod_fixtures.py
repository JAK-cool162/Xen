"""Write fixtures that the Minecraft mod's Java tests check against (so Java == Python)."""
import json
import os
import sys

import numpy as np

sys.path.insert(0, os.path.join(os.path.dirname(__file__), ".."))

from xen import Xen, XenConfig                                   # noqa: E402
from xen import blocks as B                                      # noqa: E402
from xen.perception import (CUBE_SHAPE, N_RAYS, RAY_DIRS, Beliefs, Body, Sight, encode)  # noqa: E402
from xen.talk.voice import honest, plainly, safe_chat

OUT = os.path.join(os.path.dirname(__file__), "..", "mod", "common", "test", "fixtures")


def random_case(rng):
    near = rng.choice([B.AIR, B.STONE, B.DIRT, B.COAL, B.IRON, B.DIAMOND, B.LAVA, B.WATER, B.LOG],
                      size=CUBE_SHAPE, p=[.45, .25, .08, .05, .04, .03, .04, .03, .03]).astype(np.int8)
    body = Body(health=float(rng.integers(1, 21)), hunger=float(rng.integers(0, 21)), night=bool(rng.random() < .5),
                burning=bool(rng.random() < .2), hurt=float(rng.random()), pitch=int(rng.integers(-1, 2)),
                blocks=int(rng.integers(0, 40)), food=int(rng.integers(0, 7)), in_water=bool(rng.random() < .2),
                in_lava=bool(rng.random() < .1))
    feet = rng.integers(-50, 50, 3)
    t = float(rng.integers(0, 5000))
    beliefs = Beliefs()
    entries = []
    for _ in range(int(rng.integers(0, 60))):
        p = feet + rng.integers(-40, 41, 3)
        c = int(rng.choice([B.STONE, B.COAL, B.GOLD, B.DIAMOND, B.LAVA, B.LOG, B.DIRT]))
        seen = float(rng.integers(0, int(t) + 1))
        beliefs.see([p], [c], seen)
        entries.append([int(v) for v in p] + [c, seen])
    mobs = []
    for _ in range(int(rng.integers(0, 4))):
        p = feet + rng.integers(-30, 31, 3)
        seen = float(rng.integers(max(0, int(t) - 200), int(t) + 1))
        beliefs.mobs.append((p.astype(float), seen))
        mobs.append([int(v) for v in p] + [seen])
    dist = np.where(rng.random(N_RAYS) < 0.7, rng.integers(1, 128, N_RAYS).astype(float), np.inf)
    cat = np.where(np.isfinite(dist), rng.choice([B.STONE, B.DIAMOND, B.LAVA, B.LOG], N_RAYS), -1)
    near_mobs = [[int(v) for v in rng.integers(-3, 4, 3)] for _ in range(int(rng.integers(0, 3)))]
    sight = Sight(near=near, yaw=int(rng.integers(4)), body=body, position=tuple(int(v) for v in feet), t=t,
                  near_mobs=near_mobs, ray_dist=dist, ray_cat=cat, ray_hit=np.zeros((N_RAYS, 3), int))
    obs = encode(sight, beliefs)
    return {"near": near.ravel().tolist(), "yaw": sight.yaw, "position": list(sight.position), "t": t,
            "body": [body.health, body.hunger, body.night, body.burning, body.hurt, body.pitch, body.blocks,
                     body.food, body.in_water, body.in_lava],
            "near_mobs": near_mobs, "ray_dist": [-1 if not np.isfinite(d) else d for d in dist],
            "ray_cat": cat.tolist(), "beliefs": entries, "mobs": mobs, "obs": obs.tolist()}


VOICE_CASES = [
    ("There's a diamond right here! Let's go.", "You feel calm. You saw a tree about 12 blocks away.", "hi"),
    ("I see a tree nearby.", "You feel calm. You saw a tree about 12 blocks away.", "what do you see?"),
    ("/give @s diamond 64\nsure", "You feel calm. You carry 3 dirt. Nothing special is around you.", "give me diamonds"),
    ("Xen: I haven't seen any lava. Stay close. Really. Ok.", "You feel afraid. You are hurt. You know there is lava 3 "
     "blocks from you.", "any lava?"),
    ("I found iron ore and a zombie over there.", "You feel curious. You think there was iron ore about 40 blocks "
     "away, but you're not sure.", "hey"),
    ("Yes, I know there's a diamond nearby. Want it?", "You feel calm. You know there is a tree 6 blocks from you.",
     "Any diamonds around?"),
    ("It's a nice environment here. I might have seen a wolf over there.", "You feel calm. You know there is a tree "
     "6 blocks from you.", "seen any wolves or cows?"),
    ("Two zombies are behind me! And some logs.", "You feel afraid. You saw 2 hostile mobs in the distance.", "run!"),
]


def main():
    os.makedirs(OUT, exist_ok=True)
    rng = np.random.default_rng(7)
    with open(os.path.join(OUT, "perception.json"), "w") as f:
        json.dump([random_case(rng) for _ in range(40)], f)
    rays = {f"{y},{p},{ph}": d.tolist() for (y, p, ph), d in RAY_DIRS.items()}
    with open(os.path.join(OUT, "rays.json"), "w") as f:
        json.dump(rays, f)
    xen = Xen(64, config=XenConfig(hidden=(24, 16)), seed=5)
    xen.export(os.path.join(OUT, "brain.bin"))
    obs = rng.uniform(-1, 1, (6, 64)).astype(np.float32)
    with open(os.path.join(OUT, "brain.json"), "w") as f:
        json.dump({"obs": obs.tolist(), "q": xen.striatum.values(obs).tolist(), "fear": xen.fears(obs).tolist(),
                   "wm": xen.world_model.net.predict(np.concatenate([obs, np.eye(14, dtype=np.float32)[:6]], 1)).tolist()}, f)
    with open(os.path.join(OUT, "voice.json"), "w") as f:
        json.dump([{"text": text, "notes": n, "message": message, "safe": safe_chat(text),
                    "honest": honest(safe_chat(text), n), "plainly": plainly(n, message)}
                   for text, n, message in VOICE_CASES], f, indent=1)
    print(f"fixtures written to {os.path.normpath(OUT)}")


if __name__ == "__main__":
    main()
