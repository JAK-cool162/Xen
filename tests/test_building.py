import os
import tempfile
import unittest

from xen.building.blueprint import Blueprint, is_attachment, parse_state
from xen.building.designer import Designer, tags_for
from xen.building.rating import from_scan, rate
from xen.building.taste import Taste
from xen.building.templates import (ARCH_STYLES, PALETTES, ROOF_STYLES, HouseSpec, arch, bridge, house,
                                    profile, tower)


class TestBlueprint(unittest.TestCase):
    def test_states_rotation_and_commands(self):
        self.assertEqual(parse_state("oak_stairs[facing=east,half=bottom]"),
                         ("oak_stairs", {"facing": "east", "half": "bottom"}))
        bp = Blueprint("t")
        bp.set(1, 0, 0, "oak_stairs[facing=north]")
        bp.set(0, 0, 0, "stone")
        turned = bp.rotated(1)
        self.assertIn("oak_stairs[facing=east]", turned.blocks.values())
        self.assertEqual(turned.size, (1, 1, 2))
        self.assertIn("oak_stairs[facing=north]", bp.mirrored().blocks.values())   # unchanged
        bp.set(2, 0, 0, "oak_stairs[facing=east]")
        self.assertIn("oak_stairs[facing=west]", bp.mirrored().blocks.values())
        cmds = bp.to_commands()
        self.assertTrue(cmds[0].startswith("fill "))            # clears the space first
        self.assertTrue(any(c.startswith("setblock ~") for c in cmds))

    def test_build_order_puts_supports_first(self):
        bp = Blueprint("t")
        bp.set(0, 1, 0, "redstone_wall_torch[facing=east]")
        bp.set(-1, 1, 0, "white_concrete")
        order = [s for _, _, _, s in bp.to_list()]
        self.assertLess(order.index("white_concrete"), order.index("redstone_wall_torch[facing=east]"))
        self.assertTrue(is_attachment("oak_door[half=lower]") and not is_attachment("stone"))

    def test_runs_compress_rows(self):
        bp = Blueprint("t")
        bp.fill(0, 0, 0, 9, 0, 0, "stone")
        self.assertEqual(bp.to_runs(), [[0, 0, 0, "stone", 10]])
        with tempfile.TemporaryDirectory() as d:
            path = os.path.join(d, "b.mcfunction")
            bp.to_mcfunction(path)
            with open(path) as f:
                self.assertIn("fill ~2 ~0 ~2 ~11 ~0 ~2 stone", f.read())


class TestTemplates(unittest.TestCase):
    def test_every_roof_and_palette_builds_a_good_house(self):
        for style in ROOF_STYLES:
            heights = profile(style, 11)
            self.assertEqual(len(heights), 11)
            r = rate(house(HouseSpec(roof=style)))
            self.assertGreater(r.score, 7.0, style)
            self.assertGreater(r.criteria["shelter"], 0.5, style)
            self.assertGreater(r.criteria["stability"], 0.95, style)
        for palette in PALETTES:
            self.assertGreater(rate(house(HouseSpec(palette=palette))).score, 7.0, palette)

    def test_roof_shapes(self):
        self.assertEqual(profile("gable", 5), [0, 1, 2, 1, 0])
        self.assertEqual(profile("a_frame", 5), [0, 2, 4, 2, 0])
        mono = profile("mono", 5)
        self.assertEqual(mono, sorted(mono))                       # only goes up
        butterfly = profile("butterfly", 5)
        self.assertLess(butterfly[2], butterfly[0])                 # dips in the middle

    def test_other_templates(self):
        for style in ARCH_STYLES:
            a = arch(style, 7, 6)
            self.assertGreater(len(a), 10)
            self.assertNotIn((3, 0, 0), a.blocks)                   # you can walk through
        self.assertGreater(rate(tower()).score, 5.0)
        self.assertGreater(rate(bridge(), shelter=False).score, 7.0)

    def test_rating_prefers_good_builds(self):
        box = Blueprint("box")
        box.fill(0, 0, 0, 6, 4, 6, "dirt", hollow=True)
        self.assertLess(rate(box).score, rate(house(HouseSpec())).score - 3)
        floating = house(HouseSpec())
        floating.set(0, 30, 0, "stone")
        self.assertLess(rate(floating).criteria["stability"], 1.0)
        self.assertTrue(rate(box).notes)

    def test_rating_scans(self):
        bp = house(HouseSpec()).normalized()
        sx, sy, sz = bp.size
        states = [bp.blocks.get((x, y, z), "air") for x in range(sx) for y in range(sy) for z in range(sz)]
        self.assertEqual(from_scan((0, 0, 0), (sx, sy, sz), states).blocks, bp.blocks)


class TestTaste(unittest.TestCase):
    def test_learns_what_you_like(self):
        taste = Taste()
        for _ in range(8):
            for roof, score in (("a_frame", 10), ("flat", 1)):
                spec = HouseSpec(roof=roof)
                taste.learn(rate(house(spec)), tags_for(spec), score)
        self.assertEqual(taste.favourite("roof:"), "roof:a_frame")
        a = HouseSpec(roof="a_frame")
        f = HouseSpec(roof="flat")
        self.assertGreater(taste.predict(rate(house(a)), tags_for(a)), taste.predict(rate(house(f)), tags_for(f)) + 4)
        with tempfile.TemporaryDirectory() as d:
            path = os.path.join(d, "taste.json")
            taste.save(path)
            self.assertEqual(Taste(path).tags, taste.tags)

    def test_designer_follows_taste(self):
        taste = Taste()
        for _ in range(10):
            for roof in ("gambrel", "m_shaped", "mansard", "curved", "saltbox", "gable"):
                spec = HouseSpec(roof=roof, palette="birch")
                taste.learn(rate(house(spec)), tags_for(spec), 10 if roof == "gambrel" else 5)
        score, spec, rating, bp = Designer(taste, seed=0).design(generations=8)
        self.assertEqual(spec.roof, "gambrel")
        self.assertTrue(taste.portfolio)


if __name__ == "__main__":
    unittest.main()
