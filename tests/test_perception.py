import math
import unittest

import numpy as np

from xen import blocks as B
from xen.perception import (AHEAD, CHANNELS, CUBE_SHAPE, DIRS, FOV, LATERAL, N_CELLS, N_NEAR_RADAR, NEAR,
                            OBS_DIM, RAYS_H, RAYS_V, VERTICAL, VIEW, Beliefs, Body, Senses, Sight, cast_rays,
                            encode, in_view, line_of_sight, to_ego, to_world)

FAR = N_CELLS * CHANNELS + N_NEAR_RADAR          # start of the far (belief) radars


def cell(lat, ahead, dy):
    i = ((lat - LATERAL.start) * len(AHEAD) + (ahead - AHEAD.start)) * len(VERTICAL) + (dy - VERTICAL.start)
    return i * CHANNELS


def open_world(size=300):
    """A lookup over an infinite stone floor at y < 0 (air above), plus extra blocks."""
    extra = {}

    def lookup(cells):
        cells = np.asarray(cells)
        out = np.where(cells[:, 1] < 0, B.STONE, B.AIR)
        for i, c in enumerate(map(tuple, cells)):
            if c in extra:
                out[i] = extra[c]
        return out
    return lookup, extra


class TestFrames(unittest.TestCase):
    def test_egocentric_frames(self):
        for yaw in range(4):
            self.assertEqual(to_world(yaw, 0, 1), DIRS[yaw])
            self.assertEqual(to_world(yaw, 1, 0), DIRS[(yaw + 1) % 4])   # right = next turn right
            self.assertEqual(to_ego(yaw, *to_world(yaw, 2, -1)), (2, -1))
        self.assertEqual(to_world(0, 1, 0), (1, 0))   # facing north, your right hand points east

    def test_senses_are_limited(self):
        self.assertEqual(NEAR, 6)
        self.assertEqual(VIEW, 128)                    # 8 chunks
        self.assertEqual(FOV, 90.0)


class TestNearSenses(unittest.TestCase):
    def test_lava_ahead_is_known_for_every_yaw(self):
        for yaw in range(4):
            cube = np.zeros(CUBE_SHAPE, np.int8)
            dx, dz = DIRS[yaw]
            cube[NEAR + dx, NEAR, NEAR + dz] = B.LAVA
            obs = encode(Sight(near=cube, yaw=yaw, body=Body()), Beliefs())
            self.assertEqual(obs.shape, (OBS_DIM,))
            self.assertEqual(obs[cell(0, 1, 0) + 1], 1.0)
            self.assertEqual(obs[cell(0, -1, 0) + 1], 0.0)

    def test_buried_treasure_is_known_only_up_close(self):
        cube = np.full(CUBE_SHAPE, B.STONE, np.int8)
        cube[NEAR, NEAR:, NEAR] = B.AIR
        cube[NEAR, NEAR - 5, NEAR] = B.DIAMOND                  # 5 blocks under its feet
        obs = encode(Sight(near=cube, yaw=0, body=Body()), Beliefs())
        self.assertEqual(obs[N_CELLS * CHANNELS], 1.0)          # near treasure radar found it
        cube[NEAR, NEAR - 5, NEAR] = B.STONE
        cube[0, 0, 0] = B.DIAMOND                                # corner of the cube: > 6 blocks away
        obs = encode(Sight(near=cube, yaw=0, body=Body()), Beliefs())
        self.assertEqual(obs[N_CELLS * CHANNELS], 0.0)

    def test_mobs(self):
        cube = np.zeros(CUBE_SHAPE, np.int8)
        obs = encode(Sight(near=cube, yaw=2, body=Body(), near_mobs=[(0, 0, 1)]), Beliefs())   # yaw 2 faces +z
        self.assertEqual(obs[cell(0, 1, 0) + 4], 1.0)
        self.assertEqual(obs[cell(0, 1, 1) + 4], 1.0)            # mobs are two blocks tall
        self.assertTrue(np.all(obs >= -1) and np.all(obs <= 1))


class TestEyes(unittest.TestCase):
    def test_sees_only_in_front_within_the_field_of_view(self):
        lookup, extra = open_world()
        eye = np.array([0.5, 1.62, 0.5])
        extra[(0, 1, -20)] = B.LOG          # 20 blocks north, in front when facing north
        extra[(0, 1, 20)] = B.LOG           # 20 blocks south: behind
        seen = set()
        for phase in range(4):              # a few ticks of looking
            dist, cat, hit = cast_rays(lookup, eye, 0, 1, phase)
            self.assertEqual(len(dist), RAYS_H * RAYS_V)
            seen |= {tuple(h) for h, c in zip(hit, cat) if c == B.LOG}
        self.assertIn((0, 1, -20), seen)
        self.assertNotIn((0, 1, 20), seen)
        self.assertTrue(in_view(eye, 0, 1, (0.5, 1.5, -20)))
        self.assertFalse(in_view(eye, 0, 1, (0.5, 1.5, 20)))
        self.assertFalse(in_view(eye, 0, 1, (20.5, 1.5, -1)))     # 87 degrees to the side
        self.assertFalse(in_view(eye, 0, 1, (0.5, 1.5, -200)))    # beyond 8 chunks

    def test_walls_hide_what_is_behind_them(self):
        lookup, extra = open_world()
        eye = np.array([0.5, 1.62, 0.5])
        for y in range(0, 12):
            for x in range(-12, 13):
                extra[(x, y, -8)] = B.STONE     # a wall 8 blocks ahead
        extra[(0, 1, -20)] = B.DIAMOND
        for phase in range(4):
            dist, cat, hit = cast_rays(lookup, eye, 0, 1, phase)
            self.assertNotIn(B.DIAMOND, set(cat.tolist()))
        self.assertFalse(line_of_sight(lookup, eye, (0.5, 1.5, -20.5)))
        self.assertTrue(line_of_sight(lookup, eye, (0.5, 1.5, -5.5)))


class TestBeliefs(unittest.TestCase):
    def test_confidence_fades_and_senses_correct_it(self):
        b = Beliefs(half_life=100)
        b.see([(20, 0, 0)], [B.DIAMOND], t=0)
        _, _, conf = b.known(0)
        self.assertAlmostEqual(conf[0], 1.0)
        _, _, conf = b.known(100)
        self.assertAlmostEqual(conf[0], 0.5)
        cube = np.zeros(CUBE_SHAPE, np.int8)                     # up close it's just air (someone mined it)
        b.correct(cube, (18, 0, 0), t=100)
        _, cats, conf = b.known(100)
        self.assertEqual(cats[0], B.AIR)
        self.assertAlmostEqual(conf[0], 1.0)

    def test_far_treasure_is_a_belief(self):
        senses = Senses()
        cube = np.zeros(CUBE_SHAPE, np.int8)
        n = RAYS_H * RAYS_V
        dist, cat, hit = np.full(n, np.inf), np.full(n, -1), np.zeros((n, 3), int)
        dist[0], cat[0], hit[0] = 30.0, B.DIAMOND, (0, 0, -30)
        sight = Sight(near=cube, yaw=0, body=Body(), position=(0, 0, 0), t=0, ray_dist=dist, ray_cat=cat, ray_hit=hit)
        obs = senses.perceive(sight)
        self.assertEqual(obs[FAR], 1.0)                          # it believes there's treasure...
        self.assertGreater(obs[FAR + 2], 0.5)                    # ...ahead of it
        self.assertAlmostEqual(obs[FAR + 5], 1.0)                # and it's sure: it just saw it
        later = Sight(near=cube, yaw=0, body=Body(), position=(0, 0, 0), t=4800)
        obs = senses.perceive(later)
        self.assertLess(obs[FAR + 5], 0.3)                       # a long time later it's not so sure
        self.assertIn("diamond ore", senses.describe())


if __name__ == "__main__":
    unittest.main()
