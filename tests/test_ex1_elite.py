import os
import tempfile
import unittest

from xen.ex1 import elite, train


class EliteTest(unittest.TestCase):
    def test_best_xens_picked_and_deaths_count(self):
        with tempfile.TemporaryDirectory() as d:
            track = os.path.join(d, "track.tsv")
            with open(track, "w") as fh:
                fh.write("1000\tamy\t2\t1\t0\n9000\tamy\t3\t3\t1\n1000\tbob\t1\t0\t0\n20000\tbob\t2\t0\t0\n1000\tcat\t2\t1\t0\n")
            log = os.path.join(d, "server.log")
            with open(log, "w") as fh:
                fh.write("[1] [Server thread/INFO]: cat was slain by Zombie (local death message)\n"
                         "[2] [Server thread/INFO]: cat drowned (local death message)\n")
            names, sc = elite.elite(track, log)
            self.assertEqual(names[0], "amy")
            self.assertNotIn("bob", names)
            self.assertLess(sc["cat"], sc["amy"])

    def test_moments_before_a_death_left_out(self):
        recs = [{"type": "state", "t_ms": t, "player": {}} for t in range(0, 30000, 1000)]
        recs.append({"type": "event", "kind": "death", "t_ms": 20000})
        kept = train.good_moments(recs)
        times = [r["t_ms"] for r in kept if r.get("type") == "state"]
        self.assertIn(5000, times)
        self.assertNotIn(15000, times)
        self.assertIn(25000, times)


if __name__ == "__main__":
    unittest.main()
