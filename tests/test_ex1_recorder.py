import base64
import gzip
import json
import os
import tempfile
import unittest

from xen.ex1 import features as F
from xen.ex1 import recorder as R

W, H = 64, 36


def rle(v):
    out, i = [], 0
    while i < len(v):
        j = i
        while j < len(v) and v[j] == v[i]:
            j += 1
        out.append(f"{v[i]}x{j - i}")
        i = j
    return ",".join(out)


class RecorderSessionTest(unittest.TestCase):
    """A made-up recording in the mod's recorder format: Ex1 reads it as frames, with the creature in view and a hurt."""

    def make(self, d, n=40):
        with gzip.open(os.path.join(d, "log.jsonl.gz"), "wt") as f:
            f.write(json.dumps({"session": "test", "format": "xen-recorder 1", "labels_size": [W, H]}) + "\n")
            f.write(json.dumps({"labels": {"0": "sky", "1": "stone", "2": "mob:zombie"}}) + "\n")
            for k in range(n):
                what = [0] * (W * H // 2) + [1] * (W * H // 2)
                what[W * 20 + 32] = 2                               # (a zombie just below the middle)
                dist = [255] * (W * H // 2) + [12] * (W * H // 2)
                dist[W * 20 + 32] = 20
                lf = [(7 << 4) | 1] * (W * H)
                f.write(json.dumps({"t": k * 250, "frame": k, "pos": [k * 0.3, 64, 0], "dim": "overworld", "yaw": 0, "pitch": 10, "fov": 70,
                                    "keys": {"w": True, "a": False, "s": False, "d": False, "jump": False, "sneak": False, "sprint": True,
                                             "attack": k % 4 == 0, "use": False}, "hand": "iron_sword", "hp": 20 - (k > 30), "food": 18,
                                    "day_time": 14000, "on_ground": True, "in_water": False, "spectator": False, "looking_at": "mob:zombie",
                                    "what": rle(what), "dist": base64.b64encode(bytes(dist)).decode(),
                                    "light_face": base64.b64encode(bytes(lf)).decode()}) + "\n")
                if k == 31:
                    f.write(json.dumps({"t": k * 250 + 10, "event": "hurt", "amount": 1.0}) + "\n")

    def test_frames(self):
        with tempfile.TemporaryDirectory() as d:
            self.make(d)
            self.assertTrue(R.is_session(d))
            recs = R.records(d)
            fr = F.frames(recs)
            self.assertGreater(len(fr), 30)
            x, keys, _, _, _ = fr[5]
            self.assertEqual(len(x), F.N)
            self.assertEqual(x[32 + 2], 1.0)                        # (looking at a creature)
            self.assertEqual(x[42], 1.0)                            # (a monster)
            self.assertGreater(x[43], 0)                            # (the zombie in view is the nearest monster)
            self.assertAlmostEqual(x[9], 7 / 15, places=3)          # (the light of the ground in front)
            self.assertEqual(keys[F.KEYS.index("forward")], 1.0)
            self.assertEqual(keys[F.KEYS.index("sprint")], 0.0)     # (the sprint key held, at walking speed: toggle sprint, not sprinting)
            self.assertTrue(any(f[4] for f in fr))                  # (hurt within two seconds)

    def test_written_down_fields_win(self):
        with tempfile.TemporaryDirectory() as d:
            self.make(d, n=6)
            rec = R.records(d)[2]
            self.assertEqual(rec["blocks"]["below"], "minecraft:stone")
            self.assertEqual(rec["main_hand"]["id"], "minecraft:iron_sword")


if __name__ == "__main__":
    unittest.main()
