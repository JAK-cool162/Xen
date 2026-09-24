"""Xen learns redstone by experimenting.

For a task (a truth table: which lamps should light for which levers), Xen
tries out circuits on a small floor, keeps the ones that work best and learns
where which parts belong (the cross-entropy method: a probability for every
part on every cell, pulled towards the best experiments each round).

Solved circuits go into a library, and the parts that worked become a prior
for the next task, so Xen gets quicker at redstone the more it learns.
"""
import json
import os
from dataclasses import dataclass

import numpy as np

from .sim import BLOCK, DUST, KIND_NAMES, LAMP, LEVER, REPEATER, TORCH, Circuit, Part, Redstone, truth_table

OPTIONS = [None, Part(DUST), Part(BLOCK)] + [Part(TORCH, d) for d in range(4)] + [Part(REPEATER, d) for d in range(4)]


@dataclass(frozen=True)
class Task:
    name: str
    width: int
    depth: int
    inputs: tuple
    outputs: tuple
    table: tuple               # expected outputs for each input combination, in counting order
    description: str = ""

    def rows(self):
        n = len(self.inputs)
        return [tuple(bool((i >> (n - 1 - b)) & 1) for b in range(n)) for i in range(2 ** n)]

    def circuit(self, parts=None):
        return Circuit(self.width, self.depth, dict(parts or {}), list(self.inputs), list(self.outputs))


def _gate(name, fn, description, width=9, depth=3):
    rows = [(a, b) for a in (False, True) for b in (False, True)]
    return Task(name, width, depth, ((0, 0), (0, depth - 1)), ((width - 1, depth // 2),),
                tuple((fn(a, b),) for a, b in rows), description)


# Building blocks Xen works out first, so it can combine them later.
PRIMITIVES = {
    "not_row": Task("not_row", 5, 1, ((0, 0),), ((4, 0),), ((True,), (False,)), "a NOT gate one block wide"),
    "or": _gate("or", lambda a, b: a or b, "either lever lights the lamp", width=5),
}

TASKS = {
    **PRIMITIVES,
    "wire": Task("wire", 20, 1, ((0, 0),), ((19, 0),), ((False,), (True,)),
                 "carry a signal 18 blocks (dust alone fades after 15)"),
    "not": Task("not", 6, 3, ((0, 1),), ((5, 1),), ((True,), (False,)), "lamp on when the lever is off"),
    "and": _gate("and", lambda a, b: a and b, "both levers are needed"),
    "nand": _gate("nand", lambda a, b: not (a and b), "off only when both levers are on"),
    "nor": _gate("nor", lambda a, b: not (a or b), "on only when both levers are off"),
    "xor": _gate("xor", lambda a, b: a != b, "exactly one lever", width=11, depth=5),
    "xnor": _gate("xnor", lambda a, b: a == b, "both levers the same", width=11, depth=5),
}


def fitness(task, circuit):
    """How good an experiment was: (score, solved).

    Mostly the share of truth-table rows it gets right (unstable = wrong).  To
    learn from near misses, it also counts how close the signal the levers
    control gets to the lamp, and a little less is better (fewer parts).
    """
    sim = Redstone(circuit)
    right, active = 0, []
    for inputs, want in zip(task.rows(), task.table):
        lamps, stable, _ = sim.settle(inputs, max_ticks=40)
        right += stable and tuple(lamps[p] for p in circuit.outputs) == want
        active.append(sim.active())
    solved = right == len(task.table)
    score = right / len(task.table)
    if not solved:
        reacting = set().union(*(a ^ active[0] for a in active[1:]))
        span = task.width + task.depth
        if reacting:
            gap = min(abs(x - ox) + abs(z - oz) for x, z in reacting for ox, oz in circuit.outputs)
            score += 0.15 * (1 - gap / span)
    return score - 0.002 * circuit.components(), solved


class Library:
    """Circuits Xen has worked out, plus what it learned about which parts help."""

    def __init__(self, path=None):
        self.path = path
        self.circuits = {}
        self.part_counts = np.ones(len(OPTIONS))
        if path and os.path.exists(path):
            with open(path) as f:
                data = json.load(f)
            self.circuits = data.get("circuits", {})
            self.part_counts = np.asarray(data.get("part_counts", self.part_counts), float)

    def prior(self):
        """How likely each part is to be useful on a cell, from experience."""
        learned = self.part_counts / self.part_counts.sum()
        innate = np.array([0.4, 0.25, 0.1] + [0.03] * 4 + [0.0125] * 4)
        p = 0.5 * learned + 0.5 * innate / innate.sum()
        return p / p.sum()

    def store(self, task, circuit, score, how="experiment"):
        parts = [[x, z, p.kind, p.facing, p.delay] for (x, z), p in sorted(circuit.parts.items())
                 if (x, z) not in circuit.inputs and (x, z) not in circuit.outputs]
        old = self.circuits.get(task.name)
        if old is None or score > old["score"]:
            self.circuits[task.name] = {"score": round(score, 4), "parts": parts, "how": how,
                                        "width": circuit.width, "depth": circuit.depth,
                                        "inputs": [list(p) for p in circuit.inputs],
                                        "outputs": [list(p) for p in circuit.outputs]}
        for x, z, kind, facing, delay in parts:
            for i, opt in enumerate(OPTIONS):
                if opt and opt.kind == kind and (opt.kind not in (TORCH, REPEATER) or opt.facing == facing):
                    self.part_counts[i] += 1
        self.part_counts[0] += task.width * task.depth - len(parts)

    def circuit(self, task):
        entry = self.circuits.get(task.name if isinstance(task, Task) else task)
        if not entry:
            return None
        parts = {(x, z): Part(kind, facing, delay) for x, z, kind, facing, delay in entry["parts"]}
        return Circuit(entry["width"], entry["depth"], parts, [tuple(p) for p in entry["inputs"]],
                       [tuple(p) for p in entry["outputs"]])

    def save(self, path=None):
        path = path or self.path
        if path:
            with open(path, "w") as f:
                json.dump({"circuits": self.circuits, "part_counts": self.part_counts.tolist()}, f)


@dataclass
class Result:
    task: Task
    circuit: Circuit
    score: float
    solved: bool
    experiments: int
    generations: int


class RedstoneLearner:
    def __init__(self, library=None, seed=0, population=150, elite=15, smoothing=0.35):
        self.library = library or Library()
        self.rng = np.random.default_rng(seed)
        self.population = population
        self.elite = elite
        self.smoothing = smoothing

    def learn(self, task, generations=120, polish=8, patience=25, on_generation=None):
        """Experiment until the truth table is right (then tidy up a few rounds)."""
        if isinstance(task, str):
            task = TASKS[task]
        rng = self.rng
        cells = [(x, z) for x in range(task.width) for z in range(task.depth)
                 if (x, z) not in task.inputs and (x, z) not in task.outputs]
        probs = np.tile(self.library.prior(), (len(cells), 1))
        known = self.library.circuit(task)
        same_shape = known and (known.width, known.depth, tuple(known.inputs), tuple(known.outputs)) == \
            (task.width, task.depth, task.inputs, task.outputs)
        seeds = [known] if same_shape else []

        best, best_score, best_solved = None, -1.0, False
        elite_genomes, elite_scored = None, []
        improved_at = 0
        experiments, solved_at = 0, None
        gen = 0
        for gen in range(1, generations + 1):
            cumulative = np.cumsum(probs, 1)
            cumulative[:, -1] = 1.0
            draws = rng.random((self.population, len(cells), 1))
            genomes = list((draws > cumulative[None]).sum(-1))
            batch = []
            for genome in genomes:
                parts = {cell: OPTIONS[g] for cell, g in zip(cells, genome) if OPTIONS[g] is not None}
                batch.append((genome, task.circuit(parts)))
            for circuit in seeds:
                genome = np.array([next(i for i, o in enumerate(OPTIONS) if o == circuit.parts.get(c)) for c in cells])
                batch.append((genome, circuit))
            # Also try small changes to the best experiments so far.
            for _ in range(self.population // 3 if elite_genomes is not None else 0):
                genome = elite_genomes[rng.integers(len(elite_genomes))].copy()
                for _ in range(rng.integers(1, 4)):
                    genome[rng.integers(len(cells))] = rng.choice(len(OPTIONS), p=self.library.prior())
                parts = {cell: OPTIONS[g] for cell, g in zip(cells, genome) if OPTIONS[g] is not None}
                batch.append((genome, task.circuit(parts)))
            seeds = []
            scored = list(elite_scored)
            for genome, circuit in batch:
                score, solved = fitness(task, circuit)
                scored.append((score, solved, genome, circuit))
            experiments += len(batch)
            scored.sort(key=lambda t: -t[0])
            if scored[0][0] > best_score + 1e-9:
                best_score, best_solved, _, best = scored[0]
                improved_at = gen
            if best_solved and solved_at is None:
                solved_at = gen
            if on_generation:
                on_generation(gen, best_score, best_solved)
            elite_scored = scored[: self.elite]
            elite = np.array([g for _, _, g, _ in elite_scored])
            elite_genomes = elite
            freq = np.zeros_like(probs)
            for i in range(len(OPTIONS)):
                freq[:, i] = (elite == i).mean(0)
            probs = (1 - self.smoothing) * probs + self.smoothing * freq
            probs = np.maximum(probs, 0.004)
            probs /= probs.sum(1, keepdims=True)
            if not best_solved and gen - improved_at >= patience:
                # Stuck: start the experiments afresh (keeping the best idea).
                probs = np.tile(self.library.prior(), (len(cells), 1))
                elite_genomes, elite_scored = None, []
                improved_at = gen
            if solved_at is not None and gen - solved_at >= polish:
                break
        if best_solved:
            self.library.store(task, best, best_score)
        return Result(task, best, best_score, best_solved, experiments, gen)

    def solve(self, task, generations=120, on_generation=None):
        """Use what Xen knows: remembered circuit, else combine known gates, else experiment."""
        if isinstance(task, str):
            task = TASKS[task]
        known = self.library.circuit(task)
        if known and _matches(task, known):
            return Result(task, known, self.library.circuits[task.name]["score"], True, 0, 0)
        if len(task.inputs) == 1 and task.table == PRIMITIVES["not_row"].table and task.name != "not_row":
            module = self.library.circuit("not_row")
            if module is None or not _matches(PRIMITIVES["not_row"], module):
                learned = self.learn(PRIMITIVES["not_row"], generations, on_generation=on_generation)
                module = learned.circuit if learned.solved else None
            if module is not None:
                self.library.store(task, module, 1.0 - 0.002 * module.components(), how="composed")
                return Result(task, module, 1.0, True, 0, 0)
        if len(task.inputs) == 2 and task.name not in PRIMITIVES:
            program = _program_for(task.table)
            if program:
                modules = {}
                for name in ("not_row", "or"):
                    modules[name] = self.library.circuit(name)
                    if modules[name] is None or not _matches(PRIMITIVES[name], modules[name]):
                        learned = self.learn(PRIMITIVES[name], generations, on_generation=on_generation)
                        if not learned.solved:
                            break
                        modules[name] = learned.circuit
                else:
                    circuit = compose(program, modules["not_row"], modules["or"])
                    if _matches(task, circuit):
                        score = 1.0 - 0.002 * circuit.components()
                        self.library.store(task, circuit, score, how="composed")
                        return Result(task, circuit, score, True, 0, 0)
        return self.learn(task, generations, on_generation=on_generation)


def _matches(task, circuit):
    table = truth_table(circuit, max_ticks=80)
    return all(stable and out == want for (_, out, stable), want in zip(table, task.table))


def _program_for(table):
    """Find (not A?, not B?, not out?) with out = [not](A' or B') matching a 2-input truth table."""
    rows = [(a, b) for a in (False, True) for b in (False, True)]
    options = [(na, nb, no) for no in (False, True) for na in (False, True) for nb in (False, True)]
    for na, nb, no in sorted(options, key=sum):
        if all((((a != na) or (b != nb)) != no) == want[0] for (a, b), want in zip(rows, table)):
            return na, nb, no
    return None


def compose(program, not_row, or_gate):
    """Lay out [NOT] A, [NOT] B -> OR -> [NOT] as modules side by side on three rows.

    Each module's lamp becomes a repeater that hands the signal to the next
    module, whose lever becomes dust: repeaters read any output (dust, torch or
    powered block) and give a fresh full-strength signal.
    """
    neg_a, neg_b, neg_out = program
    parts = {}

    def place(module, ox, oz):
        for (x, z), part in module.parts.items():
            if part.kind not in (LEVER, LAMP):
                parts[(x + ox, z + oz)] = part
        for (x, z) in module.inputs:
            if (x + ox, z + oz) not in parts:
                parts[(x + ox, z + oz)] = Part(DUST)

    span = not_row.width - 1                          # lever column -> lamp column
    for row, negate in ((0, neg_a), (2, neg_b)):
        if negate:
            place(not_row, 0, row)
            parts.pop((0, row), None)                 # the real lever goes here
        else:
            for x in range(1, span):
                parts[(x, row)] = Part(DUST)
        parts[(span, row)] = Part(REPEATER, facing=1)     # hand over, eastwards
    x_or = span + 1
    place(or_gate, x_or, 0)
    x_out = x_or + or_gate.outputs[0][0]
    parts[(x_out, 1)] = Part(REPEATER, facing=1)
    if neg_out:
        place(not_row, x_out + 1, 1)
        out = (x_out + 1 + span, 1)
    else:
        for x in range(x_out + 1, x_out + span):
            parts[(x, 1)] = Part(DUST)
        out = (x_out + span, 1)
    parts.pop(out, None)
    return Circuit(out[0] + 1, 3, parts, [(0, 0), (0, 2)], [out])


def describe(result):
    how = "remembered" if result.solved and not result.experiments else "worked out"
    status = f"solved ({how})" if result.solved else "not solved yet"
    lines = [f"{result.task.name}: {status} after {result.experiments} experiments, "
             f"{result.circuit.components()} parts"]
    lines.append(result.circuit.ascii())
    names = "ABCDEFGH"
    for inputs, outputs, stable in truth_table(result.circuit):
        ins = " ".join(f"{names[i]}={int(v)}" for i, v in enumerate(inputs))
        lines.append(f"  {ins} -> lamp {'ON ' if outputs[0] else 'off'}{'' if stable else ' (unstable)'}")
    parts = {}
    for p in result.circuit.parts.values():
        parts[KIND_NAMES[p.kind]] = parts.get(KIND_NAMES[p.kind], 0) + 1
    lines.append("  parts: " + ", ".join(f"{k} x{v}" for k, v in sorted(parts.items())))
    return "\n".join(lines)
