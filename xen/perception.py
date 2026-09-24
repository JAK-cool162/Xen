"""What Xen perceives: senses up close, eyes further away, and beliefs.

Xen does not know the whole world:

* Within NEAR (6) blocks it knows everything around it, like a player feeling
  their surroundings (including the block under its feet or behind it).
* Beyond that it only knows what it *sees*: rays cast from its eyes inside a
  90 degree field of view, up to VIEW (128 blocks = 8 chunks). A ray stops at
  the first opaque block, so what is behind walls or buried underground stays
  unknown.
* What it has seen goes into a belief map with a *confidence* that fades with
  time (blocks slowly, mobs quickly: they move). Far-away knowledge is always
  a belief, never a certainty.

Perception is egocentric: "forward" is the direction Xen faces, so what it
learns in one place (lava ahead = bad) applies everywhere.  The same code
turns simulator and real-game senses into the same observation vector.
"""
import math
from dataclasses import dataclass, field

import numpy as np

from . import blocks as B

NEAR = 6                              # blocks Xen fully knows around itself
R = DOWN = UP = NEAR
CUBE_SHAPE = (2 * NEAR + 1,) * 3      # the near cube, indexed [dx, dy, dz]
VIEW = 128                            # how far it can see: 8 chunks
FOV = 90.0                            # field of view, degrees (both ways)
RAYS_H, RAYS_V = 16, 16               # rays across / up the field of view
EYE = 1.62                            # eye height above the feet
LOOK_PITCH = {-1: -math.pi / 2 + 0.01, 0: -0.7, 1: 0.0}   # radians for pitch -1/0/1 (bridge uses the same)
SAMPLES = np.concatenate([np.arange(0.5, 16.0, 0.5), np.arange(16.0, VIEW + 0.001, 1.0)])

# Yaw index -> facing direction (dx, dz): north, east, south, west.
# Turning right adds one, exactly like turning right in Minecraft.
DIRS = ((0, -1), (1, 0), (0, 1), (-1, 0))

LATERAL = range(-2, 3)    # left .. right
AHEAD = range(-2, 3)      # behind .. in front
VERTICAL = range(-2, 4)   # below the feet .. above the head
CHANNELS = 5              # solid, lava, water, value, mob
N_CELLS = len(LATERAL) * len(AHEAD) * len(VERTICAL)
SECTORS_H, SECTORS_V = 4, 4
N_NEAR_RADAR = 3 * 5
N_FAR_RADAR = 3 * 6
N_VISION = SECTORS_H * SECTORS_V * 3
N_COVERAGE = 4
N_BODY = 13
OBS_DIM = N_CELLS * CHANNELS + N_NEAR_RADAR + N_FAR_RADAR + N_VISION + N_COVERAGE + N_BODY

OPAQUE = B.SOLID | (np.arange(B.NUM_BLOCKS) == B.LAVA)     # what stops sight
_VALUE_NORM = (np.log1p(B.VALUE) / np.log1p(B.VALUE.max())).astype(np.float32)


@dataclass
class Body:
    """Proprioception: how Xen's own body feels."""
    health: float = 20.0
    hunger: float = 20.0
    night: bool = False
    burning: bool = False
    hurt: float = 0.0         # damage just taken, 0..1 (1 = 5 or more health points)
    pitch: int = 0            # -1 looking down, 0 ahead, 1 up
    blocks: int = 0           # placeable blocks carried
    food: int = 0             # edible items carried
    in_water: bool = False
    in_lava: bool = False


@dataclass
class Sight:
    """Everything Xen's senses deliver in one moment."""
    near: np.ndarray                      # CUBE_SHAPE block categories around the feet (full knowledge)
    yaw: int
    body: Body
    position: tuple = (0, 0, 0)           # feet block, absolute
    t: float = 0.0                        # game time in ticks
    near_mobs: list = field(default_factory=list)    # (dx, dy, dz) of mobs within NEAR
    ray_dist: np.ndarray = None           # (RAYS) distance to what each ray hit, inf = nothing
    ray_cat: np.ndarray = None            # (RAYS) category hit, -1 = nothing
    ray_hit: np.ndarray = None            # (RAYS, 3) absolute block hit
    far_mobs: list = field(default_factory=list)     # absolute positions of mobs it can see


def facing(yaw):
    """Forward and right unit vectors (dx, dz) for a yaw index."""
    return DIRS[yaw % 4], DIRS[(yaw + 1) % 4]


def to_world(yaw, lateral, ahead):
    """Egocentric (lateral, ahead) offset -> world (dx, dz) offset."""
    (fx, fz), (rx, rz) = facing(yaw)
    return lateral * rx + ahead * fx, lateral * rz + ahead * fz


def to_ego(yaw, dx, dz):
    """World (dx, dz) offset -> egocentric (lateral, ahead) offset."""
    (fx, fz), (rx, rz) = facing(yaw)
    return dx * rx + dz * rz, dx * fx + dz * fz


# ----------------------------------------------------------------------- vision
# Like eyes, the rays never hold still: each tick they shift by a quarter of their
# spacing (a 4-tick cycle), so over a few ticks Xen sees twice as finely both ways.
JITTER = ((-0.25, -0.25), (0.25, -0.25), (-0.25, 0.25), (0.25, 0.25))


def ray_angles(pitch, phase=0):
    """(vertical, horizontal) angles of every ray, radians, row by row from the bottom."""
    fov = math.radians(FOV)
    jh, jv = JITTER[phase % len(JITTER)]
    angles = []
    for i in range(RAYS_V):
        v = -fov / 2 + (i + 0.5 + jv) * fov / RAYS_V
        up = float(np.clip(LOOK_PITCH[pitch] + v, -math.pi / 2 + 1e-3, math.pi / 2 - 1e-3))
        for j in range(RAYS_H):
            angles.append((up, -fov / 2 + (j + 0.5 + jh) * fov / RAYS_H))
    return angles


def _ray_directions(yaw, pitch, phase=0):
    (fx, fz), (rx, rz) = facing(yaw)
    rays = []
    for up, h in ray_angles(pitch, phase):
        hx, hz = math.cos(h) * fx + math.sin(h) * rx, math.cos(h) * fz + math.sin(h) * rz
        rays.append((math.cos(up) * hx, math.sin(up), math.cos(up) * hz))
    return np.array(rays)


RAY_DIRS = {(yaw, pitch, phase): _ray_directions(yaw, pitch, phase)
            for yaw in range(4) for pitch in (-1, 0, 1) for phase in range(len(JITTER))}
N_RAYS = RAYS_H * RAYS_V


def cast_rays(lookup, eye, yaw, pitch, phase=0):
    """Cast Xen's view rays. lookup(int array (M, 3)) -> categories, -1 where unknown.

    Returns (dist, cat, hit): distance to the first opaque block (inf if none),
    its category (-1) and absolute position.
    """
    dirs = RAY_DIRS[(yaw, pitch, phase % len(JITTER))]
    points = np.asarray(eye)[None, None, :] + dirs[:, None, :] * SAMPLES[None, :, None]
    cells = np.floor(points).astype(np.int64)
    cats = lookup(cells.reshape(-1, 3)).reshape(len(dirs), len(SAMPLES))
    unknown = cats < 0
    stop = np.where(unknown, True, OPAQUE[np.clip(cats, 0, None)])
    first = np.argmax(stop, axis=1)
    rows = np.arange(len(dirs))
    hit = stop[rows, first] & ~unknown[rows, first]
    dist = np.where(hit, SAMPLES[first], np.inf)
    cat = np.where(hit, cats[rows, first], -1)
    return dist, cat, cells[rows, first]


_EYE_IN_BLOCK = np.array([0.5, EYE, 0.5])
RAY_CELLS = {key: np.floor(_EYE_IN_BLOCK[None, None, :] + d[:, None, :] * SAMPLES[None, :, None]).astype(np.int16)
             for key, d in RAY_DIRS.items()}


_CHUNKS = [(0, 31), (31, 63), (63, len(SAMPLES))]      # march near samples first


def cast_rays_grid(blocks, feet, yaw, pitch, phase=0):
    """Fast cast_rays for a dense block array, with the eye centred in its block.

    Rays are marched in chunks; a ray that has hit something is not marched further.
    """
    offsets = RAY_CELLS[(yaw, pitch, phase % len(JITTER))]
    feet = np.asarray(feet, np.int64)
    shape = np.array(blocks.shape)
    n = len(offsets)
    dist = np.full(n, np.inf)
    cat = np.full(n, -1, np.int64)
    hit_at = np.zeros((n, 3), np.int64)
    alive = np.arange(n)
    for lo, hi in _CHUNKS:
        if not len(alive):
            break
        cells = offsets[alive, lo:hi].astype(np.int64) + feet
        inside = ((cells >= 0) & (cells < shape)).all(-1)
        safe = np.where(inside[..., None], cells, 0)
        cats = np.where(inside, blocks[safe[..., 0], safe[..., 1], safe[..., 2]], -1)
        stop = ~inside | OPAQUE[np.clip(cats, 0, None)]
        stopped = stop.any(1)
        first = np.argmax(stop, axis=1)
        rows = np.arange(len(alive))
        hit = stopped & inside[rows, first]
        done = alive[hit]
        dist[done] = SAMPLES[lo + first[hit]]
        cat[done] = cats[rows, first][hit]
        hit_at[done] = cells[rows, first][hit]
        alive = alive[~stopped]
    return dist, cat, hit_at


def in_view(eye, yaw, pitch, target):
    """Is a point inside the field of view (ignoring walls)?"""
    d = np.asarray(target, float) - np.asarray(eye, float)
    lat, ahead = to_ego(yaw, d[0], d[2])
    flat = math.hypot(lat, ahead)
    if flat + abs(d[1]) > VIEW or ahead <= 0:
        return False
    half = math.radians(FOV / 2)
    return abs(math.atan2(lat, ahead)) <= half and abs(math.atan2(d[1], flat) - LOOK_PITCH[pitch]) <= half


def line_of_sight(lookup, eye, target):
    """True if no opaque block lies between the eye and the target."""
    eye, target = np.asarray(eye, float), np.asarray(target, float)
    length = float(np.linalg.norm(target - eye))
    steps = np.arange(0.5, max(length - 0.5, 0.5), 0.5)
    if len(steps) == 0:
        return True
    points = eye[None] + (target - eye)[None] * (steps / length)[:, None]
    cats = lookup(np.floor(points).astype(np.int64))
    return not (cats < 0).any() and not OPAQUE[cats].any()


# ---------------------------------------------------------------------- beliefs
class Beliefs:
    """What Xen has seen beyond its senses, with a confidence that fades."""

    def __init__(self, half_life=2400.0, mob_half_life=60.0, capacity=20_000):
        self.half_life = half_life
        self.mob_half_life = mob_half_life
        self.capacity = capacity
        self.pos = np.zeros((1024, 3), np.int64)
        self.cat = np.zeros(1024, np.int8)
        self.seen = np.zeros(1024, np.float64)
        self.n = 0
        self.index = {}
        self.mobs = []                      # [(position array, time seen)]

    def see(self, positions, cats, t):
        for p, c in zip(np.asarray(positions, np.int64), cats):
            key = (int(p[0]), int(p[1]), int(p[2]))
            i = self.index.get(key)
            if i is None:
                if self.n == len(self.cat):
                    self._grow()
                i = self.n
                self.n += 1
                self.index[key] = i
                self.pos[i] = p
            self.cat[i] = c
            self.seen[i] = t

    def _grow(self):
        size = len(self.cat) * 2
        self.pos = np.resize(self.pos, (size, 3))
        self.cat = np.resize(self.cat, size)
        self.seen = np.resize(self.seen, size)

    def see_mobs(self, positions, t):
        fresh = [(np.asarray(p, float), t) for p in positions]
        kept = [(p, s) for p, s in self.mobs if self._mob_conf(s, t) > 0.05
                and all(np.abs(p - q).sum() > 3 for q, _ in fresh)]
        self.mobs = fresh + kept

    def _mob_conf(self, seen, t):
        return 0.5 ** ((t - seen) / self.mob_half_life)

    def confidence(self, t):
        return 0.5 ** ((t - self.seen[:self.n]) / self.half_life)

    def correct(self, near, feet, t):
        """Up close Xen knows the truth: fix beliefs about blocks within reach of its senses."""
        if not self.n:
            return
        rel = self.pos[:self.n] - np.asarray(feet)[None]
        inside = np.all(np.abs(rel) <= NEAR, axis=1)
        if inside.any():
            idx = np.nonzero(inside)[0]
            r = rel[idx] + NEAR
            self.cat[idx] = near[r[:, 0], r[:, 1], r[:, 2]]
            self.seen[idx] = t

    def forget(self, t):
        """Drop the faintest memories when memory is full."""
        if self.n < self.capacity:
            return
        conf = self.confidence(t)
        keep = np.argsort(-conf)[: self.capacity // 2]
        self.pos[:len(keep)], self.cat[:len(keep)], self.seen[:len(keep)] = \
            self.pos[keep], self.cat[keep], self.seen[keep]
        self.n = len(keep)
        self.index = {tuple(int(v) for v in self.pos[i]): i for i in range(self.n)}

    def far_mobs(self, t):
        return [(p, self._mob_conf(s, t)) for p, s in self.mobs]

    def known(self, t, category=None):
        """(positions, categories, confidences) of what it believes."""
        conf = self.confidence(t)
        cats = self.cat[:self.n]
        mask = conf > 0.02 if category is None else (conf > 0.02) & (cats == category)
        return self.pos[:self.n][mask], cats[mask], conf[mask]


# --------------------------------------------------------------------- encoding
def _window_index():
    """Near-cube indices of every egocentric window cell, for each yaw."""
    index = []
    for yaw in range(4):
        cells = []
        for lat in LATERAL:
            for ahead in AHEAD:
                dx, dz = to_world(yaw, lat, ahead)
                for dy in VERTICAL:
                    cells.append((dx + NEAR, dy + NEAR, dz + NEAR))
        index.append(tuple(np.array(c) for c in zip(*cells)))
    return index


_WINDOW = _window_index()
_CUBE_OFFSETS = np.stack(np.meshgrid(*(np.arange(-NEAR, NEAR + 1),) * 3, indexing="ij"), -1)
_IN_REACH = np.linalg.norm(_CUBE_OFFSETS, axis=-1) <= NEAR


def _radar(yaw, offsets, weights=None, scale=NEAR):
    """Egocentric pointer to the most interesting offset in a list."""
    if len(offsets) == 0:
        return np.zeros(5, np.float32)
    offsets = np.asarray(offsets, np.float32)
    dist = np.linalg.norm(offsets, axis=1)
    score = -dist if weights is None else weights / (1.0 + dist)
    i = int(np.argmax(score))
    dx, dy, dz = offsets[i]
    lat, ahead = to_ego(yaw, dx, dz)
    extra = 1.0 - dist[i] / (scale * 1.8) if weights is None else weights[i]
    return np.array([1.0, lat / scale, ahead / scale, dy / scale, extra], np.float32)


def _belief_radar(yaw, rel, conf, score):
    """[found, lateral, ahead, up, closeness, confidence] of the best-scoring belief."""
    if len(rel) == 0:
        return np.zeros(6, np.float32)
    i = int(np.argmax(score))
    dx, dy, dz = rel[i]
    lat, ahead = to_ego(yaw, dx, dz)
    dist = float(np.linalg.norm(rel[i]))
    scale = 32.0
    return np.array([1.0, lat / scale, ahead / scale, dy / scale, 1.0 - dist / VIEW, conf[i]], np.float32)


def encode(sight, beliefs):
    """Build Xen's observation vector from its senses and beliefs."""
    yaw, body, near = sight.yaw % 4, sight.body, np.asarray(sight.near)
    cells = near[_WINDOW[yaw]]
    mob_cells = np.zeros(N_CELLS, np.float32)
    near_mobs = [tuple(int(v) for v in m) for m in sight.near_mobs]
    for dx, dy, dz in near_mobs:
        lat, ahead = to_ego(yaw, dx, dz)
        for part in (dy, dy + 1):                     # a mob is two blocks tall
            if lat in LATERAL and ahead in AHEAD and part in VERTICAL:
                i = ((lat - LATERAL.start) * len(AHEAD) + (ahead - AHEAD.start)) \
                    * len(VERTICAL) + (part - VERTICAL.start)
                mob_cells[i] = 1.0
    window = np.stack([B.SOLID[cells], cells == B.LAVA, cells == B.WATER,
                       _VALUE_NORM[cells], mob_cells], axis=1).astype(np.float32)

    # Up close: full knowledge.
    treasure = B.TREASURE[near] & _IN_REACH
    lava = (near == B.LAVA) & _IN_REACH
    near_radar = np.concatenate([
        _radar(yaw, _CUBE_OFFSETS[treasure], _VALUE_NORM[near[treasure]]),
        _radar(yaw, _CUBE_OFFSETS[lava]),
        _radar(yaw, near_mobs),
    ])

    # Further away: beliefs, weighed by how sure it is.
    t = sight.t
    feet = np.asarray(sight.position, np.int64)
    pos, cats, conf = beliefs.known(t)
    rel = pos - feet[None]
    dist = np.linalg.norm(rel, axis=1) if len(rel) else np.zeros(0)
    far = dist > NEAR
    far_treasure = far & B.TREASURE[cats] if len(cats) else far
    far_lava = far & (cats == B.LAVA) if len(cats) else far
    mobs = beliefs.far_mobs(t)
    mob_rel = np.array([p - feet for p, _ in mobs]) if mobs else np.zeros((0, 3))
    mob_conf = np.array([c for _, c in mobs]) if mobs else np.zeros(0)
    far_radar = np.concatenate([
        _belief_radar(yaw, rel[far_treasure], conf[far_treasure],
                      _VALUE_NORM[cats[far_treasure]] * conf[far_treasure] / (1 + dist[far_treasure] / 8)),
        _belief_radar(yaw, rel[far_lava], conf[far_lava], conf[far_lava] / (1 + dist[far_lava])),
        _belief_radar(yaw, mob_rel, mob_conf, mob_conf / (1 + np.linalg.norm(mob_rel, axis=1) / 8)
                      if len(mob_rel) else mob_conf),
    ])

    # What its eyes see right now, in a coarse grid over the field of view.
    if sight.ray_dist is not None and len(sight.ray_dist) == N_RAYS:
        depth = np.minimum(sight.ray_dist, VIEW).reshape(RAYS_V, RAYS_H) / VIEW
        cat = sight.ray_cat.reshape(RAYS_V, RAYS_H)
        valid = np.clip(cat, 0, None)
        treasure_seen = (cat >= 0) & B.TREASURE[valid]
        lava_seen = cat == B.LAVA
        grid = lambda a: a.reshape(SECTORS_V, RAYS_V // SECTORS_V, SECTORS_H, RAYS_H // SECTORS_H).mean((1, 3))
        vision = np.stack([grid(depth), grid(treasure_seen.astype(float)), grid(lava_seen.astype(float))], -1).ravel()
    else:
        vision = np.zeros(N_VISION)

    # How well it knows each direction around it.
    coverage = np.zeros(4, np.float32)
    if len(rel):
        lat, ahead = to_ego(yaw, rel[:, 0], rel[:, 2])
        close = np.hypot(lat, ahead) <= 32
        quadrant = np.where(np.abs(lat) <= np.abs(ahead), np.where(ahead > 0, 0, 2), np.where(lat > 0, 1, 3))
        for q in range(4):
            coverage[q] = min(1.0, conf[close & (quadrant == q)].sum() / 100.0)

    sky_blocked = B.SOLID[near[NEAR, NEAR + 2:, NEAR]].any()
    pitch = np.zeros(3, np.float32)
    pitch[int(np.clip(body.pitch, -1, 1)) + 1] = 1.0
    stats = np.array([
        body.health / 20.0, body.hunger / 20.0, body.night, body.burning,
        np.clip(body.hurt, 0, 1), *pitch, min(body.blocks, 32) / 32.0,
        min(body.food, 5) / 5.0, body.in_water, body.in_lava, sky_blocked,
    ], np.float32)
    obs = np.concatenate([window.ravel(), near_radar, far_radar, vision, coverage, stats])
    return np.clip(obs, -1.0, 1.0).astype(np.float32)


class Senses:
    """One body's senses and memory of the world."""

    def __init__(self):
        self.beliefs = Beliefs()
        self.last = None

    def reset(self):
        self.beliefs = Beliefs()

    def perceive(self, sight):
        b = self.beliefs
        b.correct(sight.near, sight.position, sight.t)
        if sight.ray_cat is not None:
            hits = sight.ray_cat >= 0
            b.see(sight.ray_hit[hits], sight.ray_cat[hits], sight.t)
        b.see_mobs(sight.far_mobs, sight.t)
        b.forget(sight.t)
        self.last = sight
        return encode(sight, b)

    def describe(self):
        """What Xen knows, in plain sentences (for talking). Only what its senses and beliefs hold."""
        s = self.last
        if s is None:
            return "You haven't looked around yet."
        feet = np.asarray(s.position)
        facts = []
        near = np.asarray(s.near)
        for block, name in ((B.DIAMOND, "diamond ore"), (B.GOLD, "gold ore"), (B.IRON, "iron ore"),
                            (B.COAL, "coal ore"), (B.LAVA, "lava"), (B.LOG, "a tree"), (B.WATER, "water")):
            mask = (near == block) & _IN_REACH
            if mask.any():
                d = float(np.linalg.norm(_CUBE_OFFSETS[mask], axis=1).min())
                facts.append(f"You know there is {name} {d:.0f} blocks from you.")
                continue
            pos, _, conf = self.beliefs.known(s.t, block)
            if len(pos):
                d = np.linalg.norm(pos - feet, axis=1)
                i = int(np.argmin(d / np.maximum(conf, 1e-3)))
                facts.append(f"You saw {name} about {d[i]:.0f} blocks away." if conf[i] > 0.6 else
                             f"You think there was {name} about {d[i]:.0f} blocks away, but you're not sure.")
        if s.near_mobs:
            facts.append(f"There are {len(s.near_mobs)} hostile mobs right next to you.")
        seen = [c for _, c in self.beliefs.far_mobs(s.t) if c > 0.3]
        if seen:
            facts.append(f"You saw {len(seen)} hostile mobs in the distance.")
        return " ".join(facts) if facts else "Nothing special is around you."
