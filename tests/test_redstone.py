import os
import tempfile
import unittest

from xen.redstone.learn import TASKS, Library, RedstoneLearner, _program_for, compose
from xen.redstone.sim import BLOCK, DUST, REPEATER, TORCH, Circuit, Part, Redstone, truth_table


def outputs(circuit):
    return [out[0] for _, out, _ in truth_table(circuit)]


def line(n, extra=None):
    parts = {(x, 0): Part(DUST) for x in range(1, n + 1)}
    parts.update(extra or {})
    return Circuit(n + 2, 1, parts, inputs=[(0, 0)], outputs=[(n + 1, 0)])


class TestSimulator(unittest.TestCase):
    def test_dust_fades_after_15_blocks_and_repeaters_refresh_it(self):
        self.assertEqual(outputs(line(15)), [False, True])
        self.assertEqual(outputs(line(16)), [False, False])
        self.assertEqual(outputs(line(25, {(12, 0): Part(REPEATER, facing=1)})), [False, True])
        levels = Redstone(line(5))
        levels.settle((True,))
        self.assertEqual([levels.dust_levels()[(x, 0)] for x in range(1, 6)], [15, 14, 13, 12, 11])

    def test_torch_is_a_not_gate(self):
        c = Circuit(6, 1, {(1, 0): Part(DUST), (2, 0): Part(BLOCK), (3, 0): Part(TORCH, 1), (4, 0): Part(DUST)},
                    [(0, 0)], [(5, 0)])
        self.assertEqual(outputs(c), [True, False])

    def test_dust_only_powers_what_it_points_into(self):
        # A straight line running past a lamp does not light it (like the real game).
        past = Circuit(3, 3, {(1, 0): Part(DUST), (1, 1): Part(DUST), (1, 2): Part(DUST)}, [(0, 0), (0, 2)], [(2, 1)])
        self.assertEqual(outputs(past), [False] * 4)
        into = Circuit(3, 3, {(1, 0): Part(DUST), (1, 1): Part(DUST)}, [(0, 0), (2, 0)], [(1, 2)])
        self.assertEqual(outputs(into), [False, True, True, True])

    def test_clock_never_settles(self):
        clock = Circuit(4, 3, {(1, 0): Part(BLOCK), (2, 0): Part(TORCH, 1), (3, 0): Part(DUST), (3, 1): Part(DUST),
                               (3, 2): Part(DUST), (2, 2): Part(DUST), (1, 2): Part(DUST), (1, 1): Part(REPEATER, 0)},
                        [], [])
        self.assertFalse(Redstone(clock).settle(())[1])

    def test_export_uses_real_block_states(self):
        c = line(3, {(2, 0): Part(REPEATER, facing=1)})
        states = set(c.to_blueprint().blocks.values())
        self.assertIn("repeater[delay=1,facing=west,locked=false,powered=false]", states)   # input side is west
        self.assertIn("lever[face=floor,facing=north,powered=false]", states)
        self.assertIn("redstone_lamp[lit=false]", states)


class TestLearning(unittest.TestCase):
    def test_works_out_gates(self):
        with tempfile.TemporaryDirectory() as d:
            path = os.path.join(d, "redstone.json")
            learner = RedstoneLearner(Library(path), seed=0)
            for name in ("not", "or", "and", "nand", "nor", "wire"):
                result = learner.solve(name, generations=150)
                self.assertTrue(result.solved, name)
                want = [row[0] for row in TASKS[name].table]
                self.assertEqual(outputs(result.circuit), want, name)
            learner.library.save()
            again = RedstoneLearner(Library(path)).solve("and")          # remembered, no experiments
            self.assertTrue(again.solved)
            self.assertEqual(again.experiments, 0)

    def test_composition(self):
        self.assertEqual(_program_for(TASKS["and"].table), (True, True, True))
        self.assertEqual(_program_for(TASKS["or"].table), (False, False, False))
        self.assertIsNone(_program_for(TASKS["xor"].table))             # needs more than one OR
        not_row = Circuit(5, 1, {(1, 0): Part(DUST), (2, 0): Part(BLOCK), (3, 0): Part(TORCH, 1)}, [(0, 0)], [(4, 0)])
        or_gate = Circuit(5, 3, {(0, 1): Part(DUST), (1, 1): Part(DUST), (2, 1): Part(DUST), (3, 1): Part(DUST)},
                          [(0, 0), (0, 2)], [(4, 1)])
        for program, want in (((True, True, True), [False, False, False, True]),
                              ((False, False, True), [True, False, False, False]),
                              ((True, True, False), [True, True, True, False])):
            self.assertEqual(outputs(compose(program, not_row, or_gate)), want)


if __name__ == "__main__":
    unittest.main()
