import os
import subprocess
import sys
import tempfile
import unittest

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))


def xen(*args, cwd):
    env = dict(os.environ, PYTHONPATH=ROOT)
    return subprocess.run([sys.executable, "-m", "xen", *args], cwd=cwd, env=env, capture_output=True,
                          text=True, timeout=600)


class TestCommandLine(unittest.TestCase):
    def test_commands_run(self):
        with tempfile.TemporaryDirectory() as d:
            out = xen("train", "--lives", "1", "--ticks", "60", "--brain", "b.npz", cwd=d)
            self.assertEqual(out.returncode, 0, out.stderr)
            self.assertTrue(os.path.exists(os.path.join(d, "b.npz")))
            out = xen("watch", "--brain", "b.npz", "--ticks", "20", "--delay", "0", cwd=d)
            self.assertEqual(out.returncode, 0, out.stderr)
            self.assertIn("Xen:", out.stdout)
            out = xen("info", "--brain", "b.npz", cwd=d)
            self.assertIn("steps", out.stdout)
            out = xen("build", "house", "--roof", "saltbox", "--out", "house.mcfunction", cwd=d)
            self.assertEqual(out.returncode, 0, out.stderr)
            self.assertIn("Xen rates it", out.stdout)
            self.assertTrue(os.path.exists(os.path.join(d, "house.mcfunction")))
            out = xen("rate", "house", "--roof", "mansard", "--score", "9", cwd=d)
            self.assertIn("Taste updated", out.stdout)
            out = xen("build", "--design", "--generations", "3", cwd=d)
            self.assertIn("Xen's design", out.stdout)
            out = xen("redstone", "not", "--out", "not.mcfunction", cwd=d)
            self.assertEqual(out.returncode, 0, out.stderr)
            self.assertIn("solved", out.stdout)


if __name__ == "__main__":
    unittest.main()
