"""Rating builds: how good does a structure look and work?

Each criterion is scored 0..1 from the blocks alone, so the same rater works
on generated blueprints and on real builds scanned from a Minecraft world.
The overall score (0..10) is a weighted mix; the weights are Xen's *taste*,
which it can learn from your own ratings (see taste.py).
"""
from collections import Counter, deque
from dataclasses import dataclass, field

from .blueprint import Blueprint, base_name

CRITERIA = ("shelter", "roof", "openings", "palette", "detail", "symmetry", "proportion",
            "lighting", "stability")

# Looks: weighted into an aesthetic mean.  Function (shelter, stability) multiplies it:
# a beautiful facade you can't stand in, or that floats, isn't a good build.
DEFAULT_WEIGHTS = {"roof": 1.5, "openings": 1.2, "palette": 1.2, "detail": 1.3,
                   "symmetry": 0.6, "proportion": 0.6, "lighting": 0.8}
FUNCTION = ("shelter", "stability")

NON_BLOCKING = ("air", "cave_air", "void_air", "water", "lava", "short_grass", "tall_grass", "fern")
DETAIL = ("_stairs", "_slab", "_fence", "_wall", "_trapdoor", "lantern", "torch", "_pane", "_door",
          "flower_pot", "_button", "_sign", "chain", "_carpet")
LIGHTS = ("lantern", "torch", "glowstone", "sea_lantern", "shroomlight", "redstone_lamp", "campfire")


def _solid(state):
    name = base_name(state)
    return name not in NON_BLOCKING and not name.endswith("air")


def _match(name, parts):
    return any(p in name for p in parts)


@dataclass
class Rating:
    score: float
    criteria: dict
    notes: list = field(default_factory=list)

    def __str__(self):
        lines = [f"score {self.score:.1f}/10"]
        lines += [f"  {k:<10} {'#' * int(round(v * 10)):<10} {v:.2f}" for k, v in self.criteria.items()]
        lines += [f"  - {n}" for n in self.notes]
        return "\n".join(lines)


def _peak(value, low, high, soft):
    """1 inside [low, high], falling off linearly over `soft` outside it."""
    if low <= value <= high:
        return 1.0
    gap = low - value if value < low else value - high
    return max(0.0, 1.0 - gap / soft)


def features(bp):
    """Criterion scores (0..1) for a blueprint."""
    blocks = {pos: s for pos, s in bp.blocks.items() if _solid(s)}
    if not blocks:
        return {k: 0.0 for k in CRITERIA}
    names = {pos: base_name(s) for pos, s in blocks.items()}
    (x0, y0, z0), (x1, y1, z1) = Blueprint(bp.name, blocks).bounds()
    width, height, depth = x1 - x0 + 1, y1 - y0 + 1, z1 - z0 + 1

    # Shelter: air enclosed so that outside air can't flow in (doors/panes block).
    outside = set()
    queue = deque([(x0 - 1, y0, z0 - 1)])
    lo, hi = (x0 - 1, y0, z0 - 1), (x1 + 1, y1 + 1, z1 + 1)
    while queue:
        p = queue.popleft()
        if p in outside or p in blocks:
            continue
        if not all(lo[i] <= p[i] <= hi[i] for i in range(3)):
            continue
        outside.add(p)
        x, y, z = p
        queue.extend([(x + 1, y, z), (x - 1, y, z), (x, y + 1, z), (x, y - 1, z), (x, y, z + 1), (x, y, z - 1)])
    interior = [(x, y, z) for x in range(x0, x1 + 1) for y in range(y0, y1 + 1) for z in range(z0, z1 + 1)
                if (x, y, z) not in blocks and (x, y, z) not in outside]
    floor_cells = {(x, z) for x, y, z in interior if (x, y - 1, z) in blocks}
    headroom = sum(1 for x, y, z in interior if (x, y + 1, z) not in blocks and (x, y - 1, z) in blocks)
    shelter = min(1.0, len(interior) / max(1.0, 0.25 * width * height * depth)) if interior else 0.0
    if interior and not headroom:
        shelter *= 0.3                                        # nobody fits inside

    # Roof: highest block of each column is shaped (stairs/slabs) + overhang beyond the walls.
    tops = {}
    for (x, y, z), n in names.items():
        if (x, z) not in tops or y > tops[(x, z)][0]:
            tops[(x, z)] = (y, n)
    shaped = sum(1 for _, n in tops.values() if n.endswith("_stairs") or n.endswith("_slab")) / len(tops)
    covered = sum(1 for x, z in floor_cells if (x, z) in tops) / max(1, len(floor_cells)) if floor_cells else 0.0
    ground = {(x, z) for (x, y, z) in blocks if y == y0}
    overhang = 1.0 if any(xz not in ground for xz in tops) else 0.0
    roof = 0.5 * shaped + 0.3 * covered + 0.2 * overhang

    # Openings: windows share of the outer wall, plus a door.
    shell = [p for p in blocks if any(q in outside for q in _around(p))]
    windows = sum(1 for p in shell if "glass" in names[p])
    ratio = windows / max(1, len(shell))
    door = any(n.endswith("_door") for n in names.values())
    openings = 0.7 * _peak(ratio, 0.06, 0.25, 0.15) + 0.3 * door

    # Palette: a handful of materials reads well, one is bland, ten is noise.
    materials = Counter(_material(n) for n in names.values())
    main = [m for m, c in materials.items() if c >= 0.03 * len(names)]
    palette = _peak(len(main), 3, 6, 3)

    # Detail: stairs, slabs, fences, lanterns... and a contrasting frame.
    detail_share = sum(1 for n in names.values() if _match(n, DETAIL)) / len(names)
    detail = _peak(detail_share, 0.12, 0.45, 0.2)
    corners = [names.get((x, y0 + 1, z)) for x in (x0, x1) for z in (z0, z1)]
    walls = Counter(names[p] for p in shell).most_common(1)[0][0] if shell else None
    if walls and any(c and c != walls for c in corners):
        detail = min(1.0, detail + 0.2)

    # Symmetry: mirror across the middle (east-west or north-south), best of both.
    def mirror_score(axis):
        hits = 0
        for (x, y, z), n in names.items():
            m = (x0 + x1 - x, y, z) if axis == 0 else (x, y, z0 + z1 - z)
            hits += names.get(m) is not None and _material(names[m]) == _material(n)
        return hits / len(names)
    symmetry = max(mirror_score(0), mirror_score(2))

    proportion = _peak(height / max(width, depth), 0.5, 2.2, 1.0)

    lights = sum(1 for n in names.values() if _match(n, LIGHTS))
    lighting = min(1.0, lights / max(1.0, len(floor_cells) / 30.0)) if floor_cells else min(1.0, lights / 2.0)

    # Stability: every block should connect to the ground (touching faces or edges,
    # so stepped stair roofs count as connected).
    grounded, queue = set(), deque(p for p in blocks if p[1] == y0)
    while queue:
        p = queue.popleft()
        if p in grounded:
            continue
        grounded.add(p)
        queue.extend(q for q in _touching(p) if q in blocks and q not in grounded)
    stability = len(grounded) / len(blocks)

    return {"shelter": shelter, "roof": roof, "openings": openings, "palette": palette, "detail": detail,
            "symmetry": symmetry, "proportion": proportion, "lighting": lighting, "stability": stability}


def _around(p):
    x, y, z = p
    return ((x + 1, y, z), (x - 1, y, z), (x, y + 1, z), (x, y - 1, z), (x, y, z + 1), (x, y, z - 1))


_EDGES = [(dx, dy, dz) for dx in (-1, 0, 1) for dy in (-1, 0, 1) for dz in (-1, 0, 1)
          if 0 < abs(dx) + abs(dy) + abs(dz) <= 2]


def _touching(p):
    x, y, z = p
    return ((x + dx, y + dy, z + dz) for dx, dy, dz in _EDGES)


def _material(name):
    for suffix in ("_stairs", "_slab", "_fence", "_wall", "_trapdoor", "_door", "_pane", "_button"):
        if name.endswith(suffix):
            name = name[: -len(suffix)]
    return name.replace("stripped_", "").replace("_planks", "").replace("_log", "").replace("_wood", "")


NOTES = {
    "shelter": "It doesn't enclose a room yet: close the walls and roof so you could shelter inside.",
    "roof": "Give it a shaped roof (stairs and slabs) with an overhang past the walls.",
    "openings": "Balance the openings: add windows (about 1 in 8 wall blocks) and a door.",
    "palette": "Use 3 to 6 materials: a wall block, a contrasting frame, a roof and a base.",
    "detail": "Add depth and detail: log pillars at the corners, stairs, slabs, fences, lanterns.",
    "symmetry": "Try making it symmetrical, or at least balanced.",
    "proportion": "Adjust the proportions: it is very flat or very tall for its footprint.",
    "lighting": "Light it up (lanterns or torches inside) so mobs can't spawn.",
    "stability": "Some blocks are floating; connect everything to the ground.",
}


def score(crit, weights=None, shelter=True):
    """Overall 0..10 from criterion scores. shelter=False for bridges, arches, statues..."""
    weights = weights or DEFAULT_WEIGHTS
    if not shelter:
        weights = {k: w for k, w in weights.items() if k not in ("roof", "openings", "lighting")}
    total = sum(max(0.0, w) for w in weights.values())
    looks = sum(max(0.0, weights.get(k, 0.0)) * crit[k] for k in weights) / max(total, 1e-9)
    function = (0.4 + 0.6 * crit["shelter"] if shelter else 1.0) * crit["stability"] ** 2
    return max(0.0, min(10.0, 10.0 * looks * function))


def rate(bp, weights=None, shelter=True):
    crit = features(bp)
    considered = [k for k in CRITERIA if shelter or k not in ("shelter", "roof", "openings", "lighting")]
    notes = [NOTES[k] for k in sorted(considered, key=lambda k: crit[k]) if crit[k] < 0.6][:3]
    return Rating(round(score(crit, weights, shelter), 2), crit, notes)


def from_scan(origin, size, states, name="scanned build"):
    """Blueprint from a bridge scan (states in x, y, z order)."""
    bp = Blueprint(name)
    sx, sy, sz = size
    i = 0
    for x in range(sx):
        for y in range(sy):
            for z in range(sz):
                state = states[i]
                i += 1
                if _solid(state) and state != "unknown":
                    bp.set(x, y, z, state)
    return bp
