import unittest

import numpy as np

from xen import blocks as B
from xen.actions import Action
from xen.perception import CHANNELS, N_CELLS, N_NEAR_RADAR, OBS_DIM
from xen.worlds.simcraft import Mob, SimCraft


def flat_world(seed=0):
    """A predictable world: a stone floor with the player standing on it."""
    w = SimCraft(seed=seed)
    b = w.blocks
    x, y, z = w.pos
    b[x - 5:x + 6, y:y + 6, z - 5:z + 6] = B.AIR
    b[x - 5:x + 6, y - 3:y, z - 5:z + 6] = B.STONE
    w.mobs = []
    w.yaw = 2                                    # facing +z
    w.t = 1                                      # daytime
    return w


class TestSimCraft(unittest.TestCase):
    def test_reset_is_deterministic(self):
        a, b = SimCraft(seed=5), SimCraft(seed=5)
        np.testing.assert_array_equal(a.blocks, b.blocks)
        self.assertEqual(a.pos, b.pos)
        self.assertEqual(a.observe().shape, (OBS_DIM,))

    def test_mining_gives_items_and_reward(self):
        w = flat_world()
        x, y, z = w.pos
        w.blocks[x, y, z + 1] = B.DIAMOND
        _, reward, harm, done, info = w.step(Action.MINE)
        self.assertEqual(w.inventory["diamond"], 1)
        self.assertEqual(reward, B.ITEM_VALUE["diamond"])
        self.assertEqual(w.blocks[x, y, z + 1], B.AIR)
        self.assertEqual(w.t, 1 + B.HARDNESS[B.DIAMOND])       # holding the button takes time
        self.assertIn("got diamond", info["events"])

    def test_walking_into_lava_hurts_and_kills(self):
        w = flat_world()
        x, y, z = w.pos
        w.blocks[x, y - 1, z + 1] = B.LAVA
        w.blocks[x, y, z + 1] = B.LAVA
        _, _, harm, done, info = w.step(Action.FORWARD)
        self.assertGreater(harm, 0)
        self.assertLess(w.health, 20)
        total, steps = harm, 0
        while not done and steps < 20:
            _, _, harm, done, info = w.step(Action.IDLE)
            total += harm
            steps += 1
        self.assertTrue(info["terminal"])
        self.assertGreaterEqual(total, 1.0)
        self.assertIn("died (lava)", info["events"])

    def test_digging_down_and_falling(self):
        w = flat_world()
        x, y, z = w.pos
        w.blocks[x, y - 8:y - 1, z] = B.AIR
        w.blocks[x, y - 9, z] = B.STONE
        w.step(Action.LOOK_DOWN)
        _, _, harm, _, info = w.step(Action.MINE)
        self.assertEqual(w.pos[1], y - 8)
        self.assertGreater(harm, 0)
        self.assertTrue(any(e.startswith("fell") for e in info["events"]))

    def test_turning_rotates_perception(self):
        w = flat_world()
        before = w.observe()
        w.step(Action.TURN_RIGHT)
        self.assertEqual(w.yaw, 3)
        w.step(Action.TURN_LEFT)
        near = N_CELLS * CHANNELS + N_NEAR_RADAR           # what it senses up close
        np.testing.assert_array_equal(w.observe()[:near], before[:near])

    def test_it_remembers_what_it_saw(self):
        w = flat_world()
        x, y, z = w.pos
        w.blocks[x, y, z + 20] = B.DIAMOND                   # 20 blocks ahead, in plain sight
        w.pitch = 1
        for _ in range(4):                                   # look for a few ticks
            w.step(Action.IDLE)
        pos, cats, conf = w.senses.beliefs.known(w.t, B.DIAMOND)
        self.assertIn((x, y, z + 20), {tuple(p) for p in pos})
        w.step(Action.TURN_LEFT)
        w.step(Action.TURN_LEFT)                              # now facing away
        pos, cats, conf = w.senses.beliefs.known(w.t, B.DIAMOND)
        self.assertEqual(len(pos), 1)                         # still remembered
        self.assertLessEqual(conf[0], 1.0)

    def test_place_eat_and_fight(self):
        w = flat_world()
        x, y, z = w.pos
        w.inventory["cobblestone"] = 1
        w.step(Action.PLACE)
        self.assertEqual(w.blocks[x, y, z + 1], B.STONE)
        w.hunger, w.inventory["food"] = 10, 1
        _, reward, _, _, _ = w.step(Action.EAT)
        self.assertEqual(w.hunger, 16)
        self.assertGreater(reward, 0)
        w.blocks[x, y, z + 1] = B.AIR
        w.blocks[x - 2:x + 3, y + 3, z - 2:z + 4] = B.STONE     # shade, so it doesn't burn in daylight
        mob = Mob(x, y, z + 1)
        w.mobs = [mob]
        rewards = [w.step(Action.ATTACK)[1] for _ in range(3)]
        self.assertNotIn(mob, w.mobs)
        self.assertEqual(rewards[-1], 1.0)

    def test_zombies_attack(self):
        w = flat_world()
        x, y, z = w.pos
        w.mobs = [Mob(x, y, z + 1)]
        _, _, harm, _, info = w.step(Action.IDLE)
        self.assertIn("zombie attack", info["events"])
        self.assertAlmostEqual(harm, 0.1)

    def test_random_play_is_stable(self):
        w = SimCraft(seed=3, max_steps=400)
        rng = np.random.default_rng(0)
        for _ in range(3):
            w.reset()
            done = False
            while not done:
                obs, reward, harm, done, info = w.step(rng.integers(len(Action)))
                self.assertEqual(obs.shape, (OBS_DIM,))
                self.assertGreaterEqual(harm, 0.0)
        self.assertIsInstance(w.render(), str)


if __name__ == "__main__":
    unittest.main()
