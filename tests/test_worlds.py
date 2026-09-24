import json
import os
import shutil
import subprocess
import tempfile
import time
import unittest

import numpy as np

from xen import Xen, XenConfig, live
from xen import blocks as B
from xen.actions import KEYMAP, NUM_ACTIONS, Action
from xen.building.taste import Taste
from xen.perception import DOWN, OBS_DIM, R, UP
from xen.redstone.learn import Library
from xen.skills import Skills
from xen.swarm import Swarm
from xen.worlds.mineflayer import Bridge, MineflayerWorld
from xen.worlds.screen import OBS_DIM as SCREEN_DIM
from xen.worlds.screen import ScreenConfig, ScreenWorld, downscale, red_fraction

from .fake_bridge import FakeMinecraft, serve

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
SMALL = XenConfig(hidden=(32, 32), warmup=50, batch_size=16, think_after=10)


class TestMineflayerWorld(unittest.TestCase):
    def setUp(self):
        self.mc = FakeMinecraft(max_players=3)
        self.server, self.port = serve(self.mc)

    def tearDown(self):
        self.server.shutdown()
        self.server.server_close()

    def test_one_xen_plays_and_learns(self):
        world = MineflayerWorld(port=self.port, name="Xen")
        obs = world.reset()
        self.assertEqual(obs.shape, (OBS_DIM,))
        _, reward, harm, done, info = world.step(Action.MINE)
        self.assertAlmostEqual(reward, B.ITEM_VALUE["cobblestone"])
        self.assertIn("got cobblestone", info["events"])
        _, _, harm, _, _ = world.step(Action.JUMP)
        self.assertAlmostEqual(harm, 5 / 20)
        for _ in range(3):
            _, _, harm, done, info = world.step(Action.JUMP)
        self.assertTrue(done and info["terminal"])
        self.assertGreaterEqual(harm, 1.0)
        xen = Xen(OBS_DIM, NUM_ACTIONS, SMALL, seed=0)
        live(world, xen, lives=1)
        self.assertGreater(xen.steps, 0)
        world.close()

    def test_building_and_skills(self):
        world = MineflayerWorld(port=self.port, name="Xen")
        world.reset()
        with tempfile.TemporaryDirectory() as d:
            skills = Skills(Taste(os.path.join(d, "taste.json")), Library(os.path.join(d, "rs.json")))
            answer = skills.handle("xen build house gambrel spruce", world)
            self.assertIn("gambrel", answer)
            build = self.mc.built[-1]
            self.assertEqual(build["mode"], "commands")
            self.assertTrue(build["clear"])
            self.assertIn("Learning your taste", skills.handle("xen rate 8", world))
            self.assertTrue(os.path.exists(os.path.join(d, "taste.json")))
            self.assertIn("NOT gate", skills.handle("xen redstone not", world))
            self.assertIsNone(skills.handle("hello everyone", world))
        world.close()

    def test_swarm_fills_the_server_and_shares_one_brain(self):
        xen = Xen(OBS_DIM, NUM_ACTIONS, SMALL, seed=0)
        bridge = Bridge(port=self.port)
        logs = []
        swarm = Swarm(xen, bridge, count=None, spawn_every=0.0, log=logs.append)
        for _ in range(8):
            swarm.step()
        self.assertEqual(sorted(swarm.bodies), ["Xen", "Xen_2", "Xen_3"])   # as many as the server takes
        self.assertTrue(any("could not join" in line for line in logs))
        self.assertGreater(xen.steps, 10)
        feelings = {id(m.emotions) for m in swarm.bodies.values()}
        self.assertEqual(len(feelings), 3)                                  # one mind, three bodies
        # A player asks for something in chat: the builder answers.
        self.mc.bots["Xen_2"]["heard"] = [{"from": "Steve", "text": "xen count"}]
        swarm.skills = Skills()
        swarm.step()
        deadline = time.time() + 5
        while not self.mc.chat and time.time() < deadline:
            time.sleep(0.05)
        self.assertEqual(self.mc.chat[-1], ("Xen", "3 Xens online."))
        bridge.close()


class FakeScreen:
    def __init__(self):
        self.hearts = 1.0

    def grab(self):
        img = np.full((360, 640, 3), 90, np.uint8)
        h, w = img.shape[:2]
        l, t, r, b = ScreenConfig().health_box
        x0, x1 = int(l * w), int(r * w)
        img[int(t * h):int(b * h), x0:x0 + int((x1 - x0) * self.hearts)] = (220, 20, 20)
        return img


class FakeHands:
    def __init__(self):
        self.done = []

    def perform(self, action):
        self.done.append(Action(action))

    def click(self, x, y):
        self.done.append("click")

    def release_all(self):
        pass


class TestScreenWorld(unittest.TestCase):
    def test_sees_pixels_feels_health_and_presses_keys(self):
        screen, hands = FakeScreen(), FakeHands()
        world = ScreenWorld(capture=screen, controller=hands, countdown=0)
        obs = world.reset()
        self.assertEqual(obs.shape, (SCREEN_DIM,))
        screen.hearts = 0.5
        _, _, harm, done, _ = world.step(Action.FORWARD)
        self.assertAlmostEqual(harm, 0.5, delta=0.05)
        self.assertFalse(done)
        self.assertEqual(hands.done, [Action.FORWARD])
        self.assertEqual(downscale(np.ones((36, 64))).shape, (18, 32))
        self.assertGreater(red_fraction(np.full((2, 2, 3), (200, 10, 10), np.uint8)), 0.9)

    def test_every_action_is_a_real_input(self):
        for action in Action:
            press = KEYMAP[action]
            self.assertTrue(press.keys or press.button or press.turn != (0.0, 0.0) or action == Action.IDLE)


@unittest.skipUnless(shutil.which("node"), "node is not installed")
class TestBridgeScript(unittest.TestCase):
    def test_bridge_matches_python(self):
        script = (
            "const b = require('./bridge/xen_bridge.js');"
            "console.log(JSON.stringify({B: b.B, R: b.R, DOWN: b.DOWN, UP: b.UP,"
            " diamond: b.category({name: 'deepslate_diamond_ore'}), log: b.category({name: 'dark_oak_log'}),"
            " flower: b.category({name: 'poppy', boundingBox: 'empty'}), stone: b.category({name: 'andesite', boundingBox: 'block'}),"
            " unloaded: b.category(null), state: b.parseState('oak_stairs[facing=east,half=top]')}))")
        subprocess.run(["node", "--check", "bridge/xen_bridge.js"], cwd=ROOT, check=True)
        out = subprocess.run(["node", "-e", script], cwd=ROOT, capture_output=True, text=True, check=True).stdout
        data = json.loads(out.strip().splitlines()[-1])
        self.assertEqual((data["R"], data["DOWN"], data["UP"]), (R, DOWN, UP))
        for i, name in enumerate(("AIR", "GRASS", "DIRT", "STONE", "LOG", "LEAVES", "COAL", "IRON", "GOLD",
                                  "DIAMOND", "LAVA", "WATER", "BEDROCK")):
            self.assertEqual(data["B"][name], getattr(B, name))
            self.assertEqual(i, getattr(B, name))
        self.assertEqual(data["diamond"], B.DIAMOND)
        self.assertEqual(data["log"], B.LOG)
        self.assertEqual(data["flower"], B.AIR)
        self.assertEqual(data["stone"], B.STONE)
        self.assertEqual(data["unloaded"], B.STONE)
        self.assertEqual(data["state"], {"name": "oak_stairs", "props": {"facing": "east", "half": "top"}})


if __name__ == "__main__":
    unittest.main()
