"""A redstone simulator for circuits built flat on a floor (one layer).

It follows Java Edition rules for the parts it models:
  * dust carries signal strength 15 and loses 1 per block (so a line dies
    after 15 blocks); it connects to dust, torches, levers, comparators and
    to repeaters along their axis, and only powers the blocks it points into;
  * a solid block is weakly powered by dust pointing into it and strongly
    powered by a repeater/comparator facing into it; only strongly powered
    blocks power dust next to them;
  * a redstone torch (on the side of a block) is on unless its block is
    powered - it is the NOT gate - and turns on one tick later;
  * a repeater takes input from behind, outputs 15 in front after 1-4 ticks;
  * a comparator compares (or subtracts) its rear input with its side inputs;
  * levers are inputs, redstone lamps are outputs.

Circuits export to real block states (see Circuit.to_blueprint) on a smooth
stone floor, so what works here can be built and switched in a real world.
"""
from dataclasses import dataclass, field

from ..building.blueprint import Blueprint

EMPTY, DUST, BLOCK, TORCH, REPEATER, COMPARATOR, LEVER, LAMP = range(8)
KIND_NAMES = ("empty", "dust", "block", "torch", "repeater", "comparator", "lever", "lamp")
DIRS = ((0, -1), (1, 0), (0, 1), (-1, 0))          # north, east, south, west (x, z)
DIR_NAMES = ("north", "east", "south", "west")


def opposite(d):
    return (d + 2) % 4


@dataclass(frozen=True)
class Part:
    kind: int
    facing: int = 0          # torch: points this way; repeater/comparator: outputs this way
    delay: int = 1           # repeater ticks (1-4)
    mode: str = "compare"    # comparator: "compare" or "subtract"


@dataclass
class Circuit:
    width: int
    depth: int
    parts: dict = field(default_factory=dict)        # (x, z) -> Part
    inputs: list = field(default_factory=list)       # lever positions
    outputs: list = field(default_factory=list)      # lamp positions

    def __post_init__(self):
        for p in self.inputs:
            self.parts[p] = Part(LEVER)
        for p in self.outputs:
            self.parts[p] = Part(LAMP)

    def kind(self, p):
        part = self.parts.get(p)
        return part.kind if part else EMPTY

    def components(self):
        return sum(1 for p in self.parts.values() if p.kind not in (LEVER, LAMP, EMPTY))

    # ---------------------------------------------------------------- export
    def to_blueprint(self, name="circuit"):
        bp = Blueprint(name)
        sim = Redstone(self)
        for x in range(-1, self.width + 1):
            for z in range(-1, self.depth + 1):
                bp.set(x, 0, z, "smooth_stone")
        for (x, z), part in self.parts.items():
            if (x, z) not in sim.valid:
                continue
            k, d = part.kind, part.facing
            if k == DUST:
                sides = sim.pointing[(x, z)]
                props = ",".join(f"{DIR_NAMES[i]}={'side' if i in sides else 'none'}" for i in range(4))
                state = f"redstone_wire[{props},power=0]"
            elif k == BLOCK:
                state = "white_concrete"
            elif k == TORCH:
                state = f"redstone_wall_torch[facing={DIR_NAMES[d]},lit=true]"
            elif k == REPEATER:
                # A repeater's block-state "facing" names its input side.
                state = f"repeater[delay={part.delay},facing={DIR_NAMES[opposite(d)]},locked=false,powered=false]"
            elif k == COMPARATOR:
                state = f"comparator[facing={DIR_NAMES[opposite(d)]},mode={part.mode},powered=false]"
            elif k == LEVER:
                state = "lever[face=floor,facing=north,powered=false]"
            elif k == LAMP:
                state = "redstone_lamp[lit=false]"
            else:
                continue
            bp.set(x, 1, z, state)
        return bp

    def ascii(self, levers=None, lamps=None):
        """Top view. Dust '+', block '#', torch/repeater/comparator arrows, lever L/l, lamp O/o."""
        sim = Redstone(self)
        rows = []
        for z in range(self.depth):
            row = ""
            for x in range(self.width):
                p = (x, z)
                part = self.parts.get(p)
                if not part or p not in sim.valid:
                    row += "."
                elif part.kind == DUST:
                    row += "+"
                elif part.kind == BLOCK:
                    row += "#"
                elif part.kind == TORCH:
                    row += "^>v<"[part.facing]
                elif part.kind == REPEATER:
                    row += "A)V("[part.facing]
                elif part.kind == COMPARATOR:
                    row += "n}u{"[part.facing]
                elif part.kind == LEVER:
                    on = levers and levers.get(p)
                    row += "L" if on else "l"
                elif part.kind == LAMP:
                    row += "O" if lamps and lamps.get(p) else "o"
            rows.append(row)
        return "\n".join(rows)


class Redstone:
    """Simulates one circuit tick by tick."""

    def __init__(self, circuit):
        self.c = circuit
        parts = circuit.parts
        # Torches must hang on a solid block; otherwise they pop off.
        self.valid = {p for p, part in parts.items()
                      if part.kind != TORCH or circuit.kind(self._step(p, opposite(part.facing))) == BLOCK}
        self.pointing = {p: self._dust_shape(p) for p, part in parts.items() if part.kind == DUST}
        self.reset()

    @staticmethod
    def _step(p, d):
        return p[0] + DIRS[d][0], p[1] + DIRS[d][1]

    def _kind(self, p):
        return self.c.kind(p) if p in self.valid else EMPTY

    def _dust_shape(self, p):
        connected = []
        for d in range(4):
            q = self._step(p, d)
            k = self._kind(q)
            if k in (DUST, LEVER, TORCH, COMPARATOR) or (k == REPEATER and self.c.parts[q].facing % 2 == d % 2):
                connected.append(d)
        if not connected:
            return {0, 1, 2, 3}                        # a lone dot is a cross: points everywhere
        if len(connected) == 1:
            return {connected[0], opposite(connected[0])}
        return set(connected)

    def reset(self):
        parts = self.c.parts
        self.torch = {p: True for p in self.valid if parts[p].kind == TORCH}
        self.pipe = {p: [False] * max(1, min(4, parts[p].delay)) for p in self.valid if parts[p].kind == REPEATER}
        self.comp = {p: 0 for p in self.valid if parts[p].kind == COMPARATOR}
        self.levers = {p: False for p in self.c.inputs}

    # ------------------------------------------------------------- one tick
    def _emits(self, q, p, strong_blocks, powered_blocks, dust_level, to_dust=False):
        """Signal strength the part at q sends into the neighbour p."""
        k = self._kind(q)
        e = next(d for d in range(4) if self._step(q, d) == p)
        if k == DUST:
            return dust_level.get(q, 0) if e in self.pointing[q] else 0
        if k == LEVER:
            return 15 if self.levers.get(q) else 0
        if k == TORCH:
            attached = self._step(q, opposite(self.c.parts[q].facing))
            return 15 if self.torch[q] and attached != p else 0
        if k == REPEATER:
            return 15 if self.pipe[q][-1] and self.c.parts[q].facing == e else 0
        if k == COMPARATOR:
            return self.comp[q] if self.c.parts[q].facing == e else 0
        if k == BLOCK:
            return 15 if (q in strong_blocks if to_dust else q in powered_blocks) else 0
        return 0

    def _network(self):
        parts = self.c.parts
        strong = set()
        for p in self.pipe:
            q = self._step(p, parts[p].facing)
            if self.pipe[p][-1] and self._kind(q) == BLOCK:
                strong.add(q)
        for p, level in self.comp.items():
            q = self._step(p, parts[p].facing)
            if level > 0 and self._kind(q) == BLOCK:
                strong.add(q)
        # Dust: sources, then spread losing one per block.
        level = {p: 0 for p in self.pointing}
        for p in self.pointing:
            for d in range(4):
                q = self._step(p, d)
                if self._kind(q) not in (DUST, EMPTY):
                    level[p] = max(level[p], self._emits(q, p, strong, strong, level, to_dust=True))
        frontier = sorted(level, key=level.get, reverse=True)
        while frontier:
            p = frontier.pop(0)
            for d in range(4):
                q = self._step(p, d)
                if q in level and level[p] - 1 > level[q]:
                    level[q] = level[p] - 1
                    frontier.append(q)
        powered = set(strong)
        for p in self.pointing:
            if level[p] > 0:
                for d in self.pointing[p]:
                    q = self._step(p, d)
                    if self._kind(q) == BLOCK:
                        powered.add(q)
        return strong, powered, level

    def tick(self):
        """Advance one redstone tick; returns True if nothing changed (stable)."""
        parts = self.c.parts
        strong, powered, level = self._network()
        self._last = (strong, powered, level)
        torch = {p: self._step(p, opposite(parts[p].facing)) not in powered for p in self.torch}
        pipe = {}
        for p, buf in self.pipe.items():
            back = self._step(p, opposite(parts[p].facing))
            signal = self._kind(back) != EMPTY and self._emits(back, p, strong, powered, level) > 0
            pipe[p] = [signal] + buf[:-1]
        comp = {}
        for p in self.comp:
            f = parts[p].facing
            rear = self._emits(self._step(p, opposite(f)), p, strong, powered, level) \
                if self._kind(self._step(p, opposite(f))) != EMPTY else 0
            side = 0
            for s in ((f + 1) % 4, (f + 3) % 4):
                q = self._step(p, s)
                if self._kind(q) in (DUST, REPEATER, COMPARATOR):
                    side = max(side, self._emits(q, p, strong, powered, level))
            if parts[p].mode == "subtract":
                comp[p] = max(0, rear - side)
            else:
                comp[p] = rear if rear >= side else 0
        stable = torch == self.torch and pipe == self.pipe and comp == self.comp
        self.torch, self.pipe, self.comp = torch, pipe, comp
        return stable

    def lamps(self):
        strong, powered, level = self._network()
        lit = {}
        for p in self.c.outputs:
            lit[p] = any(self._kind(self._step(p, d)) != EMPTY
                         and self._emits(self._step(p, d), p, strong, powered, level) > 0 for d in range(4))
        return lit

    def active(self):
        """Every cell carrying signal right now (for seeing where power reaches)."""
        strong, powered, level = self._network()
        cells = {p for p, v in level.items() if v > 0} | powered
        cells |= {p for p, on in self.torch.items() if on}
        cells |= {p for p, buf in self.pipe.items() if buf[-1]}
        cells |= {p for p, v in self.comp.items() if v > 0}
        return cells

    def dust_levels(self):
        return self._network()[2]

    def settle(self, inputs, max_ticks=64):
        """Set the levers, run until stable. Returns (lamp states, stable, ticks)."""
        self.reset()
        for p, on in zip(self.c.inputs, inputs):
            self.levers[p] = bool(on)
        for t in range(1, max_ticks + 1):
            if self.tick():
                return self.lamps(), True, t
        return self.lamps(), False, max_ticks


def truth_table(circuit, max_ticks=64):
    """[(inputs, outputs, stable)] for every lever combination."""
    sim = Redstone(circuit)
    n = len(circuit.inputs)
    rows = []
    for i in range(2 ** n):
        inputs = tuple(bool((i >> (n - 1 - b)) & 1) for b in range(n))
        lamps, stable, _ = sim.settle(inputs, max_ticks)
        rows.append((inputs, tuple(lamps[p] for p in circuit.outputs), stable))
    return rows
