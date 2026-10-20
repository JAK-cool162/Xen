import os
import struct
import tempfile
import unittest

import numpy as np

from xen.vision import glance as G


class GlanceTest(unittest.TestCase):
    def test_reads_what_the_mod_writes(self):
        with tempfile.TemporaryDirectory() as d:
            pic = (np.arange(G.C * G.H * G.W) % 256).astype(np.uint8)
            with open(os.path.join(d, "glance_Pip.bin"), "wb") as fh:
                for k in range(5):
                    fh.write(struct.pack(">qf", 1000 + k, 20.0) + bytes([k % 2, 1, 0, 1]) + pic.tobytes())
            X, Y = G.read(os.path.join(d, "glance_Pip.bin"))
            self.assertEqual(X.shape, (5, G.C, G.H, G.W))
            self.assertAlmostEqual(float(X[0, 0, 0, 1]), 1 / 255, places=6)
            self.assertEqual(Y[:, 0].tolist(), [0, 1, 0, 1, 0])
            tr, ytr, te, yte = G.load([d])
            self.assertEqual(len(tr) + len(te), 5)

    def test_auc(self):
        self.assertEqual(G.auc([0, 0, 1, 1], [0.1, 0.2, 0.8, 0.9]), 1.0)
        self.assertEqual(G.auc([1, 1, 0, 0], [0.1, 0.2, 0.8, 0.9]), 0.0)


if __name__ == "__main__":
    unittest.main()
