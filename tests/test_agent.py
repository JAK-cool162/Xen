import os
import tempfile
import unittest

import numpy as np

from xen import Xen, XenConfig
from xen.actions import Action, NUM_ACTIONS

SMALL = dict(hidden=(32, 32), warmup=100, batch_size=32, explore_steps=1500, train_every=2,
             think_after=50, lr=1e-3)


class LavaRoom:
    """obs[0] = 1 means lava ahead: walking FORWARD then burns (and ends the life).
    Otherwise FORWARD finds a little treasure. Everything else is safe and dull."""
    obs_dim = 8

    def __init__(self, seed=0):
        self.rng = np.random.default_rng(seed)

    def reset(self):
        self.t = 0
        self.obs = self._new()
        return self.obs

    def _new(self):
        o = np.zeros(self.obs_dim, np.float32)
        o[0] = float(self.rng.random() < 0.5)
        o[1 + self.rng.integers(self.obs_dim - 1)] = 1.0
        return o

    def step(self, action):
        self.t += 1
        lava = self.obs[0] > 0.5
        reward, harm, terminal = 0.0, 0.0, False
        if action == Action.FORWARD:
            if lava:
                harm, terminal = 1.0, True
            else:
                reward = 1.0
        self.obs = self._new()
        done = terminal or self.t >= 50
        return self.obs, reward, harm, done, {"terminal": terminal}


def train(xen, world, steps):
    obs = world.reset()
    while xen.steps < steps:
        thought = xen.decide(obs)
        nxt, r, h, done, info = world.step(thought.action)
        xen.learn(obs, thought.action, r, h, nxt, info["terminal"], end=done)
        obs = world.reset() if done else nxt


class TestXen(unittest.TestCase):
    def test_decide_and_learn(self):
        xen = Xen(8, NUM_ACTIONS, XenConfig(**SMALL), seed=0)
        t = xen.decide(np.zeros(8, np.float32))
        self.assertIn(t.action, range(NUM_ACTIONS))
        self.assertIsInstance(t.text, str)
        train(xen, LavaRoom(), 300)
        self.assertGreater(xen.updates, 0)

    def test_learns_to_fear_lava(self):
        """Fear conditioning: after getting burned, Xen fears walking into lava (and only into lava)."""
        xen = Xen(8, NUM_ACTIONS, XenConfig(**SMALL), seed=1)
        train(xen, LavaRoom(seed=1), 4000)
        lava = np.zeros(8, np.float32)
        lava[0], lava[3] = 1.0, 1.0
        safe = np.zeros(8, np.float32)
        safe[3] = 1.0
        fear_lava = xen.fears(lava[None])[0]
        fear_safe = xen.fears(safe[None])[0]
        self.assertGreater(fear_lava[Action.FORWARD], 0.5)
        self.assertGreater(fear_lava[Action.FORWARD], 3 * fear_lava[Action.BACK] + 0.1)
        self.assertLess(fear_safe[Action.FORWARD], 0.25)
        # ...and it acts on it: it walks forward for treasure only when there's no lava.
        emotions = xen.new_body()
        self.assertNotEqual(xen.decide(lava, explore=False, emotions=emotions).action, Action.FORWARD)
        self.assertEqual(xen.decide(safe, explore=False, emotions=xen.new_body()).action, Action.FORWARD)
        # Feeling it: facing lava is scarier than facing nothing.
        scared = xen.new_body()
        xen.decide(lava, explore=False, emotions=scared)
        calm = xen.new_body()
        xen.decide(safe, explore=False, emotions=calm)
        self.assertGreater(scared.now.fear, calm.now.fear)

    def test_pain_sensitises_and_boredom_builds(self):
        xen = Xen(8, NUM_ACTIONS, XenConfig(**SMALL), seed=2)
        before = xen.emotions.caution
        xen.learn(np.zeros(8), 1, 0.0, 0.5, np.zeros(8), False)
        self.assertGreater(xen.emotions.caution, before)
        self.assertGreater(xen.emotions.now.pain, 0.4)
        obs = np.ones(8, np.float32)
        for _ in range(10):
            xen.decide(obs, explore=False)
        self.assertGreater(xen.emotions.now.boredom, 0.9)

    def test_save_and_load(self):
        xen = Xen(8, NUM_ACTIONS, XenConfig(**SMALL), seed=3)
        train(xen, LavaRoom(), 400)
        with tempfile.TemporaryDirectory() as d:
            path = os.path.join(d, "brain.npz")
            xen.save(path)
            twin = Xen.load(path)
        obs = np.random.default_rng(0).random((5, 8)).astype(np.float32)
        np.testing.assert_allclose(twin.utility(obs), xen.utility(obs), rtol=1e-5, atol=1e-6)
        self.assertEqual((twin.steps, twin.updates), (xen.steps, xen.updates))
        # A loaded brain keeps its skills: no random warm-up phase.
        self.assertFalse(twin._warming_up)

    def test_its_fears_travel_with_the_brain(self):
        # The mod's brain file carries the most vivid memories, so a brain that goes on learning somewhere safe
        # still replays what hurt it (and doesn't forget to fear lava).
        xen = Xen(8, NUM_ACTIONS, XenConfig(**SMALL), seed=3)
        train(xen, LavaRoom(), 400)
        self.assertGreater(len(xen.memory.trauma), 0)
        with tempfile.TemporaryDirectory() as d:
            path = os.path.join(d, "brain.bin")
            xen.export(path)
            twin = Xen.load_exported(path)
        for name in ("trauma", "joy"):
            mine, its = getattr(xen.memory, name), getattr(twin.memory, name)
            self.assertEqual(len(its), min(len(mine), 1000))
            np.testing.assert_array_equal(its.obs[its.recent(1000)], mine.obs[mine.recent(1000)])
            np.testing.assert_allclose(its.harm[its.recent(1000)], mine.harm[mine.recent(1000)])
        obs = np.random.default_rng(0).random((5, 8)).astype(np.float32)
        np.testing.assert_allclose(twin.fears(obs), xen.fears(obs), rtol=1e-5, atol=1e-6)

    def test_many_bodies_share_one_brain(self):
        xen = Xen(8, NUM_ACTIONS, XenConfig(**SMALL), seed=4)
        a, b = xen.new_body(), xen.new_body()
        xen.learn(np.zeros(8), 1, 0.0, 0.8, np.zeros(8), False, emotions=a, stream="a")
        self.assertGreater(a.now.pain, 0.5)
        self.assertEqual(b.now.pain, 0.0)                 # feelings are per body
        self.assertEqual(len(xen.memory.trauma), 0)        # still inside a's short stretch
        xen.learn(np.zeros(8), 2, 0.0, 0.0, np.zeros(8), True, emotions=a, stream="a")
        self.assertGreater(len(xen.memory.trauma), 0)      # a's pain became a shared memory


if __name__ == "__main__":
    unittest.main()
