import unittest

import numpy as np

from xen.brain.nn import MLP


class TestMLP(unittest.TestCase):
    def test_gradients_match_finite_differences(self):
        net = MLP([4, 6, 3], seed=1)
        x = np.random.default_rng(0).normal(size=(5, 4)).astype(np.float32)
        target = np.random.default_rng(1).normal(size=(5, 3)).astype(np.float32)

        def loss():
            return float(((net.predict(x) - target) ** 2).sum() / 2)

        out = net.forward(x)
        grads = [g.copy() for g in net.gradients(out - target)]
        for p, g in zip(net.params, grads):
            idx = tuple(np.unravel_index(np.argmax(np.abs(g)), g.shape))
            old = p[idx]
            p[idx] = old + 1e-2
            up = loss()
            p[idx] = old - 1e-2
            down = loss()
            p[idx] = old
            self.assertAlmostEqual((up - down) / 2e-2, g[idx], delta=1e-2 * max(1, abs(g[idx])))

    def test_learns_a_function(self):
        rng = np.random.default_rng(0)
        net = MLP([2, 32, 1], lr=1e-2, seed=0)
        x = rng.uniform(-1, 1, (256, 2)).astype(np.float32)
        y = (x[:, :1] * x[:, 1:]).astype(np.float32)
        first = None
        for _ in range(600):
            err = net.forward(x) - y
            first = first if first is not None else float((err ** 2).mean())
            net.backward(2 * err / len(x))
        final = float(((net.predict(x) - y) ** 2).mean())
        self.assertLess(final, first * 0.2)

    def test_copy_soft_update_and_state(self):
        a = MLP([3, 5, 2], seed=0)
        b = MLP([3, 5, 2], seed=1)
        c = a.copy()
        a.flat += 1.0
        self.assertFalse(np.allclose(a.flat, c.flat))       # copies are independent
        b.soft_update(a, 1.0)
        np.testing.assert_allclose(b.flat, a.flat)
        d = MLP([3, 5, 2], seed=7)
        d.load_state(a.state("net"), "net")
        x = np.ones((1, 3), np.float32)
        np.testing.assert_allclose(d.predict(x), a.predict(x), rtol=1e-6)


if __name__ == "__main__":
    unittest.main()
