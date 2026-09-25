"""Export the redstone circuits Xen learned (xen/redstone) for the mod, which builds them by hand in the world.

    python scripts/export_circuits.py [xen_redstone.json]

Writes mod/common/resources/assets/xen/circuits.json: for each circuit its size and parts
[x, z, kind, facing, delay] (x across, z forward; facing 0-3 = north, east, south, west as in sim.py).
"""
import json
import os
import sys

sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))

from xen.redstone.learn import TASKS, Library, RedstoneLearner   # noqa: E402
from xen.redstone.sim import KIND_NAMES, Redstone, truth_table    # noqa: E402

OUT = os.path.join(os.path.dirname(__file__), "..", "mod", "common", "resources", "assets", "xen", "circuits.json")
NAMES = {"not": "NOT gate", "or": "OR gate", "and": "AND gate", "wire": "long wire"}


def main():
    library = Library(sys.argv[1] if len(sys.argv) > 1 else None)
    learner = RedstoneLearner(library)
    out = {}
    for task in ("not", "or", "and", "wire"):
        result = learner.solve(TASKS[task])
        if not result.solved:
            print(f"{task}: not solved, skipped")
            continue
        c = result.circuit
        valid = Redstone(c).valid
        parts = [[x, z, KIND_NAMES[p.kind], p.facing, p.delay] for (x, z), p in sorted(c.parts.items()) if (x, z) in valid]
        out[task] = {"name": NAMES[task], "width": c.width, "depth": c.depth, "parts": parts,
                     "inputs": [list(p) for p in c.inputs], "outputs": [list(p) for p in c.outputs],
                     "truth": [[list(map(int, ins)), list(map(int, outs))] for ins, outs, _ in truth_table(c)]}
        print(f"{task}: {len(parts)} parts, {c.width}x{c.depth}")
        print(c.ascii())
    with open(OUT, "w") as f:
        json.dump(out, f, indent=1)
    print("wrote", os.path.normpath(OUT))


if __name__ == "__main__":
    main()
