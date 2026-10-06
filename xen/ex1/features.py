"""Xen Ex1's senses: what a recorded frame (the gameplay recorder's JSONL) and a live Xen (Ex1Senses.java) both know,
as the same 119 numbers. Keep this file and Ex1Senses.java in step: crossCheck compares them on fixtures.

  0-13    body: on ground, in water, sprinting, sneaking, health, food, speed, vertical speed, pitch, light, rain,
          dimension (overworld, nether, end)
  14-31   the blocks under its feet, at its feet and at its head: air, solid, water, lava, small, other (each)
  32-42   what it looks at: none/block/entity, how far, what kind of block, a monster or not
  43-49   creatures about: the nearest monster (closeness, which way, how high), monsters within 8 and 16, the nearest
          animal or player
  50-59   its hands: empty, sword, pickaxe, axe, shovel, food, block, other; a shield; using an item
  60-115  its view: 7 x 8 rays (of the recorder's 14 x 24, every other row and every third column), how far each
          goes (64 blocks, nothing: 1)
  116-118 lava in view, how much water in view, how open the view is
"""
import math

N = 119
ROWS, COLS, CELL_ROWS, CELL_COLS = 14, 24, 7, 8
AIR, SOLID, WATER, LAVA, SMALL, OTHER = range(6)
SMALL_ENDS = ("grass", "fern", "flower", "_tulip", "dandelion", "poppy", "orchid", "allium", "bluet", "daisy", "cornflower",
              "lily_of_the_valley", "_sapling", "litter", "lichen", "vine", "vines", "torch", "carpet", "_petals", "bush", "cane",
              "seagrass", "kelp", "kelp_plant", "mushroom", "roots", "sprouts", "lily_pad", "button", "rail", "pressure_plate",
              "redstone_wire", "snow", "lantern", "web", "wildflowers", "leaf_litter", "firefly_bush", "dripleaf")
OTHER_ENDS = ("_leaves", "crafting_table", "furnace", "chest", "barrel", "_bed", "_door", "_fence", "_fence_gate", "_slab", "_stairs",
              "_wall", "_trapdoor", "glass_pane", "ladder", "_sign", "smoker", "anvil", "bell", "campfire", "bookshelf", "cactus")
FOODS = {"apple", "bread", "beef", "porkchop", "mutton", "chicken", "rabbit", "cod", "salmon", "carrot", "potato", "baked_potato",
         "melon_slice", "sweet_berries", "glow_berries", "cookie", "pumpkin_pie", "golden_apple", "enchanted_golden_apple",
         "golden_carrot", "beetroot", "beetroot_soup", "mushroom_stew", "rabbit_stew", "dried_kelp", "tropical_fish", "honey_bottle"}
BLOCK_ENDS = ("_planks", "_log", "_wood", "_wool", "stone", "dirt", "cobblestone", "deepslate", "sand", "gravel", "_bricks", "bricks",
              "netherrack", "andesite", "diorite", "granite", "tuff", "_terracotta", "glass", "clay", "mud", "_block")


def name(block_id):
    return (block_id or "air").split(":")[-1]


def category(block_id):
    """The kind of block, by its name (the same rules as Ex1Senses.category)."""
    n = name(block_id)
    if n in ("air", "cave_air", "void_air", "empty", "") or n == "none":
        return AIR
    if n in ("water", "bubble_column"):
        return WATER
    if n == "lava":
        return LAVA
    if n == "short_grass" or n == "tall_grass" or any(n.endswith(e) for e in SMALL_ENDS) and not n.endswith("_block") and n != "snow_block":
        return SMALL
    if any(n.endswith(e) for e in OTHER_ENDS):
        return OTHER
    return SOLID


def hand(item_id):
    """0 empty, 1 sword, 2 pickaxe, 3 axe, 4 shovel, 5 food, 6 block, 7 other."""
    n = name(item_id)
    if n in ("empty", "air", ""):
        return 0
    if n.endswith("_sword"):
        return 1
    if n.endswith("_pickaxe"):
        return 2
    if n.endswith("_axe"):
        return 3
    if n.endswith("_shovel"):
        return 4
    if n in FOODS or n.startswith("cooked_"):
        return 5
    if any(n.endswith(e) for e in BLOCK_ENDS):
        return 6
    return 7


def wrap(deg):
    return (deg + 180.0) % 360.0 - 180.0


def clip(x, lo, hi):
    return max(lo, min(hi, x))


def features(r, vision):
    """One frame (a JSONL record) and the last view the recorder saw (it sees every other frame)."""
    f = [0.0] * N
    p = r["player"]
    f[0] = 1.0 if p.get("on_ground") else 0.0
    f[1] = 1.0 if p.get("in_water") else 0.0
    f[2] = 1.0 if p.get("sprinting") else 0.0
    f[3] = 1.0 if p.get("sneaking") else 0.0
    f[4] = p.get("health", 20) / 20.0
    f[5] = p.get("food", 20) / 20.0
    v = p.get("velocity", [0, 0, 0])
    f[6] = clip(math.hypot(v[0], v[2]) / 0.4, 0, 1.5)
    f[7] = clip(v[1], -1, 1)
    f[8] = clip(p.get("pitch", 0) / 90.0, -1, 1)
    f[9] = r.get("light", 15) / 15.0
    f[10] = 1.0 if r.get("raining") else 0.0
    dim = r.get("dimension", "minecraft:overworld")
    f[11 + (1 if "nether" in dim else 2 if "end" in dim else 0)] = 1.0
    b = r.get("blocks", {})
    for k, key in enumerate(("below", "feet", "head")):
        f[14 + 6 * k + category(b.get(key))] = 1.0
    la = r.get("looking_at", {"type": "none"})
    t = la.get("type", "none")
    f[32 + (1 if t == "block" else 2 if t == "entity" else 0)] = 1.0
    if t != "none":
        f[35] = clip(la.get("distance", 0) / 6.0, 0, 1)
    if t == "block":
        f[36 + category(la.get("id"))] = 1.0
    if t == "entity":
        f[42] = 1.0 if la.get("hostile") else 0.0
    yaw = p.get("yaw", 0.0)
    hostile = [n for n in r.get("nearby", []) if n.get("hostile")]
    others = [n for n in r.get("nearby", []) if not n.get("hostile")]
    if hostile:
        h = min(hostile, key=lambda n: n["dist"])
        f[43] = 1.0 / (1.0 + h["dist"])
        rel = h.get("rel", [0, 0, 0])
        a = math.radians(wrap(math.degrees(math.atan2(-rel[0], rel[2])) - yaw))
        f[46], f[47] = math.sin(a), math.cos(a)
        f[48] = clip(rel[1] / 8.0, -1, 1)
    f[44] = min(1.0, sum(1 for n in hostile if n["dist"] <= 8) / 4.0)
    f[45] = min(1.0, sum(1 for n in hostile if n["dist"] <= 16) / 6.0)
    if others:
        f[49] = 1.0 / (1.0 + min(n["dist"] for n in others))
    f[50 + hand(r.get("main_hand", {}).get("id"))] = 1.0
    f[58] = 1.0 if name(r.get("off_hand", {}).get("id")) == "shield" else 0.0
    f[59] = 1.0 if r.get("using_item") else 0.0
    if vision and vision.get("near"):
        near = vision["near"]
        rows, cols, dist, idx, pal = near["rows"], near["cols"], near["dist"], near["idx"], near.get("palette", [])
        for i in range(CELL_ROWS):
            for j in range(CELL_COLS):
                rr, cc = min(rows - 1, 2 * i + 1), min(cols - 1, 3 * j + 1)
                d = dist[rr * cols + cc]
                f[60 + i * CELL_COLS + j] = 1.0 if d is None or d < 0 else clip(d / 64.0, 0, 1)
        lava = water = 0
        for i in range(CELL_ROWS):
            for j in range(CELL_COLS):
                k = idx[min(rows - 1, 2 * i + 1) * cols + min(cols - 1, 3 * j + 1)]
                if 0 <= k < len(pal):
                    c = category(pal[k])
                    lava += c == LAVA
                    water += c == WATER
        f[116] = 1.0 if lava else 0.0
        f[117] = water / float(CELL_ROWS * CELL_COLS)
        f[118] = clip(vision.get("open_frac", 0.0), 0, 1)
    else:
        for k in range(60, 116):
            f[k] = 1.0
    return f


KEYS = ("forward", "back", "left", "right", "jump", "sneak", "sprint", "attack", "use")
YAW_BINS = (-30.0, -8.0, 8.0, 30.0)        # turn in the next quarter second: hard left, left, steady, right, hard right


def yaw_bin(d):
    for i, edge in enumerate(YAW_BINS):
        if d < edge:
            return i
    return len(YAW_BINS)


def frames(records, horizon_ms=2000, step_ms=250, gap_ms=200):
    """(features, keys, yaw bin, next pitch, hurt soon) for the frames of a recording.

    Works with both recorder formats: the first (every record a frame, 4 a second) and 1.2+ (type "state" frames, 4 a
    second, 20 a second in fights, and type "event" records: damage_taken, block_break, chat...). Frames come at most
    every gap_ms (so fights don't drown everything else), not while a screen is open (the keys mean nothing then).
    The turn and the next pitch are a quarter second on; hurt is a damage event or lost health within two seconds.
    """
    states, hurts = [], []
    for r in records:
        t = r.get("type")
        if t == "event":
            if r.get("kind") == "damage_taken":
                hurts.append(r.get("t_ms", 0))
            continue
        if "player" in r:
            states.append(r)
    for a, b in zip(states, states[1:]):
        if b["player"].get("health", 20) < a["player"].get("health", 20) - 0.5:
            hurts.append(b.get("t_ms", 0))
    hurts.sort()
    out = []
    vision = None
    last_kept = None
    n = len(states)
    j = 0
    for i in range(n - 1):
        r = states[i]
        if r.get("vision"):
            vision = r["vision"]
        t = r.get("t_ms", 0)
        if r.get("screen"):
            continue
        if last_kept is not None and t - last_kept < gap_ms:
            continue
        while j < n - 1 and (states[j].get("t_ms", 0) - t < step_ms or j <= i):
            j += 1
        nxt = states[min(j, n - 1)]["player"]
        last_kept = t
        x = features(r, vision)
        inp = r.get("input", {})
        keys = [1.0 if inp.get(k) else 0.0 for k in KEYS]
        yb = yaw_bin(wrap(nxt.get("yaw", 0) - r["player"].get("yaw", 0)))
        pitch = clip(nxt.get("pitch", 0) / 90.0, -1, 1)
        hurt = any(t < h <= t + horizon_ms for h in hurts[_first(hurts, t):_first(hurts, t) + 8])
        out.append((x, keys, yb, pitch, 1.0 if hurt else 0.0))
    return out


def _first(sorted_list, t):
    """Index of the first value above t (binary search)."""
    lo, hi = 0, len(sorted_list)
    while lo < hi:
        mid = (lo + hi) // 2
        if sorted_list[mid] <= t:
            lo = mid + 1
        else:
            hi = mid
    return lo
