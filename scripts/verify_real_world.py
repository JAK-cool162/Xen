"""Check Xen against a real Minecraft server, end to end.

Needs a running server (online-mode=false), the bridge, and Xen as operator:

    node bridge/xen_bridge.js --host localhost --port 25565
    /op Xen                      (in the server console)
    python scripts/verify_real_world.py

It builds every redstone circuit Xen has worked out, flips the real levers by
hand and compares the real lamps with Xen's simulator; builds a house and
scans it back; and brings a few more Xens into the world.
"""
import os
import sys
import time

sys.path.insert(0, os.path.join(os.path.dirname(__file__), ".."))

from xen.building.rating import from_scan, rate          # noqa: E402
from xen.building.templates import HouseSpec, house      # noqa: E402
from xen.redstone.learn import Library, RedstoneLearner  # noqa: E402
from xen.redstone.sim import truth_table                 # noqa: E402
from xen.worlds.mineflayer import Bridge, MineflayerWorld  # noqa: E402


def lever_on(world, pos):
    return "powered=true" in world.scan(pos, pos)["states"][0]


def set_lever(world, pos, on, attempts=4):
    for _ in range(attempts):
        if lever_on(world, pos) == on:
            return True
        world.use(pos)
        time.sleep(0.3)
    return lever_on(world, pos) == on


def check_circuits(world, base, tasks=("not", "or", "and", "nand", "nor", "wire")):
    learner = RedstoneLearner(Library(), seed=0)
    ok = True
    for k, name in enumerate(tasks):
        result = learner.solve(name)
        circuit = result.circuit
        origin = [base[0], base[1], base[2] + 8 * k]
        world.build(circuit.to_blueprint(name), origin=origin)
        time.sleep(2.0)
        world.say(f"/tp @s {origin[0] - 2} {origin[1] + 1} {origin[2] + 1}", force=True)
        time.sleep(3.0)
        world.step(0)
        lamp = [origin[0] + circuit.outputs[0][0], origin[1] + 1, origin[2] + circuit.outputs[0][1]]
        real = []
        for inputs, _, _ in truth_table(circuit):
            for (x, z), on in zip(circuit.inputs, inputs):
                set_lever(world, [origin[0] + x, origin[1] + 1, origin[2] + z], on)
            time.sleep(1.0)
            real.append("lit=true" in world.scan(lamp, lamp)["states"][0])
        sim = [outputs[0] for _, outputs, _ in truth_table(circuit)]
        same = sim == real
        ok &= same
        print(f"  {name:5s} simulator {[int(v) for v in sim]}  real game {[int(v) for v in real]}  "
              f"{'OK' if same else 'MISMATCH'}", flush=True)
    return ok


def check_house(world, origin):
    bp = house(HouseSpec(roof="gambrel", palette="spruce"))
    world.build(bp, origin=origin)
    time.sleep(2.0)
    (lx, ly, lz), (hx, hy, hz) = bp.bounds()
    scan = world.scan([origin[0] + lx, origin[1] + ly, origin[2] + lz], [origin[0] + hx, origin[1] + hy, origin[2] + hz])
    real = from_scan(scan["origin"], scan["size"], scan["states"])
    want = bp.normalized().blocks
    match = sum(1 for p, s in want.items() if real.blocks.get(p, "air").split("[")[0] == s.split("[")[0])
    print(f"  house: {match}/{len(want)} blocks as designed; the real build rates {rate(real).score}/10")
    return match >= 0.98 * len(want)


def main():
    bridge = Bridge()
    world = MineflayerWorld(name="Xen", bridge=bridge)
    world.reset()
    x, y, z = world.position
    print("Redstone: learned circuits, real levers, real lamps")
    circuits = check_circuits(world, [x + 20, y, z])
    print("Building")
    built = check_house(world, [x - 20, y, z])
    print("Swarm")
    names = []
    for i in range(2, 5):
        reply = bridge.call({"op": "spawn", "name": f"Xen_{i}"}, check=False)
        print(f"  Xen_{i}: {'joined' if reply.get('ok') else reply.get('error')}")
        names.append(reply.get("ok"))
    world.close()
    print("\nALL GOOD" if circuits and built and all(names) else "\nSOME CHECKS FAILED")
    sys.exit(0 if circuits and built and all(names) else 1)


if __name__ == "__main__":
    main()
