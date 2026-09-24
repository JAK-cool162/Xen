import unittest

import numpy as np

from xen import blocks as B
from xen.perception import (AHEAD, CHANNELS, CUBE_SHAPE, DIRS, DOWN, LATERAL, OBS_DIM, R, VERTICAL,
                            Body, encode, to_ego, to_world)


def cell(lat, ahead, dy):
    i = ((lat - LATERAL.start) * len(AHEAD) + (ahead - AHEAD.start)) * len(VERTICAL) + (dy - VERTICAL.start)
    return i * CHANNELS


class TestPerception(unittest.TestCase):
    def test_egocentric_frames(self):
        for yaw in range(4):
            self.assertEqual(to_world(yaw, 0, 1), DIRS[yaw])
            self.assertEqual(to_world(yaw, 1, 0), DIRS[(yaw + 1) % 4])   # right = next turn right
            self.assertEqual(to_ego(yaw, *to_world(yaw, 2, -1)), (2, -1))
        # Facing north (-z) in Minecraft, your right hand points east (+x).
        self.assertEqual(to_world(0, 1, 0), (1, 0))

    def test_lava_ahead_is_seen_ahead_for_every_yaw(self):
        for yaw in range(4):
            cube = np.zeros(CUBE_SHAPE, np.int8)
            dx, dz = DIRS[yaw]
            cube[R + dx, DOWN, R + dz] = B.LAVA
            obs = encode(cube, yaw, Body())
            self.assertEqual(obs.shape, (OBS_DIM,))
            self.assertEqual(obs[cell(0, 1, 0) + 1], 1.0)     # lava channel, one block ahead
            self.assertEqual(obs[cell(0, -1, 0) + 1], 0.0)

    def test_mobs_and_bounds(self):
        cube = np.full(CUBE_SHAPE, B.STONE, np.int8)
        cube[R, DOWN:, R] = B.AIR
        obs = encode(cube, 2, Body(health=10), mobs=[(0, 0, 1)])   # yaw 2 faces +z
        self.assertEqual(obs[cell(0, 1, 0) + 4], 1.0)
        self.assertEqual(obs[cell(0, 1, 1) + 4], 1.0)              # mobs are two blocks tall
        self.assertTrue(np.all(obs >= -1) and np.all(obs <= 1))


if __name__ == "__main__":
    unittest.main()
