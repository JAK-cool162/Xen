"""Turns raw surroundings into what Xen actually perceives.

Every world (simulator or real Minecraft) hands Xen a *local cube* of block
categories centred on its feet.  Perception is egocentric: the cube is rotated
so "forward" always means the direction Xen is facing, which lets knowledge
learned in one place (lava ahead = bad) apply everywhere.
"""
from dataclasses import dataclass

import numpy as np

from . import blocks as B

R = 8                 # horizontal radius of the local cube
DOWN, UP = 6, 6       # vertical reach below / above the feet
CUBE_SHAPE = (2 * R + 1, DOWN + UP + 1, 2 * R + 1)   # indexed [dx, dy, dz]

# Yaw index -> facing direction (dx, dz): north, east, south, west.
# Turning right adds one, exactly like turning right in Minecraft.
DIRS = ((0, -1), (1, 0), (0, 1), (-1, 0))

LATERAL = range(-2, 3)    # left .. right
AHEAD = range(-2, 3)      # behind .. in front
VERTICAL = range(-2, 4)   # below the feet .. above the head
CHANNELS = 5              # solid, lava, water, value, mob
N_CELLS = len(LATERAL) * len(AHEAD) * len(VERTICAL)
N_RADAR = 3 * 5
N_BODY = 13
OBS_DIM = N_CELLS * CHANNELS + N_RADAR + N_BODY

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


def _window_index():
    """Cube indices of every egocentric window cell, for each yaw."""
    index = []
    for yaw in range(4):
        cells = []
        for lat in LATERAL:
            for ahead in AHEAD:
                dx, dz = to_world(yaw, lat, ahead)
                for dy in VERTICAL:
                    cells.append((dx + R, dy + DOWN, dz + R))
        index.append(tuple(np.array(c) for c in zip(*cells)))
    return index


_WINDOW = _window_index()
_CUBE_OFFSETS = np.stack(np.meshgrid(np.arange(-R, R + 1), np.arange(-DOWN, UP + 1),
                                     np.arange(-R, R + 1), indexing="ij"), -1)


def _radar(yaw, offsets, weights=None):
    """Egocentric pointer to the most interesting offset in a list."""
    if len(offsets) == 0:
        return np.zeros(5, np.float32)
    offsets = np.asarray(offsets, np.float32)
    dist = np.linalg.norm(offsets, axis=1)
    score = -dist if weights is None else weights / (1.0 + dist)
    i = int(np.argmax(score))
    dx, dy, dz = offsets[i]
    lat, ahead = to_ego(yaw, dx, dz)
    extra = 1.0 - dist[i] / (R * 1.8) if weights is None else weights[i]
    return np.array([1.0, lat / R, ahead / R, dy / max(UP, DOWN), extra], np.float32)


def encode(cube, yaw, body, mobs=()):
    """Build Xen's observation vector.

    cube: int array of block categories, shape CUBE_SHAPE, cube[R, DOWN, R] is
          the block at Xen's feet.
    yaw:  facing index into DIRS.
    body: Body.
    mobs: iterable of (dx, dy, dz) offsets of hostile mobs' feet.
    """
    cube = np.asarray(cube)
    cells = cube[_WINDOW[yaw % 4]]
    mob_cells = np.zeros(N_CELLS, np.float32)
    mobs = [tuple(int(v) for v in m) for m in mobs]
    for dx, dy, dz in mobs:
        lat, ahead = to_ego(yaw, dx, dz)
        for part in (dy, dy + 1):                     # a mob is two blocks tall
            if lat in LATERAL and ahead in AHEAD and part in VERTICAL:
                i = ((lat - LATERAL.start) * len(AHEAD) + (ahead - AHEAD.start)) \
                    * len(VERTICAL) + (part - VERTICAL.start)
                mob_cells[i] = 1.0
    window = np.stack([B.SOLID[cells], cells == B.LAVA, cells == B.WATER,
                       _VALUE_NORM[cells], mob_cells], axis=1).astype(np.float32)

    treasure = B.TREASURE[cube]
    lava = cube == B.LAVA
    radar = np.concatenate([
        _radar(yaw, _CUBE_OFFSETS[treasure], _VALUE_NORM[cube[treasure]]),
        _radar(yaw, _CUBE_OFFSETS[lava]),
        _radar(yaw, mobs),
    ])

    sky_blocked = B.SOLID[cube[R, DOWN + 2:, R]].any()
    pitch = np.zeros(3, np.float32)
    pitch[int(np.clip(body.pitch, -1, 1)) + 1] = 1.0
    stats = np.array([
        body.health / 20.0, body.hunger / 20.0, body.night, body.burning,
        np.clip(body.hurt, 0, 1), *pitch, min(body.blocks, 32) / 32.0,
        min(body.food, 5) / 5.0, body.in_water, body.in_lava, sky_blocked,
    ], np.float32)
    obs = np.concatenate([window.ravel(), radar, stats])
    return np.clip(obs, -1.0, 1.0)
