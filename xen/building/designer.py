"""Xen designs buildings: it proposes, rates and evolves designs.

Designing is a search: start from random house specs, keep the ones its taste
scores highest, mutate them (wider, another roof, a new palette...) and repeat.
The best designs go into its portfolio.
"""
import random
from dataclasses import asdict, replace

from .rating import rate
from .taste import Taste
from .templates import PALETTES, ROOF_STYLES, HouseSpec, house


def tags_for(spec):
    return (f"roof:{spec.roof}", f"palette:{spec.palette}", "template:house")


def random_spec(rng):
    return HouseSpec(width=rng.randint(7, 15), length=rng.randint(5, 11), wall_height=rng.randint(3, 6),
                     roof=rng.choice(ROOF_STYLES), palette=rng.choice(sorted(PALETTES)),
                     overhang=rng.choice((0, 1, 1, 2)), window_spacing=rng.choice((0, 2, 3, 4)),
                     porch=rng.random() < 0.3)


def mutate(spec, rng):
    field = rng.choice(("width", "length", "wall_height", "roof", "palette", "overhang", "window_spacing", "porch"))
    if field == "roof":
        return replace(spec, roof=rng.choice(ROOF_STYLES))
    if field == "palette":
        return replace(spec, palette=rng.choice(sorted(PALETTES)))
    if field == "porch":
        return replace(spec, porch=not spec.porch)
    limits = {"width": (5, 21), "length": (5, 17), "wall_height": (3, 8), "overhang": (0, 2), "window_spacing": (0, 5)}
    lo, hi = limits[field]
    return replace(spec, **{field: max(lo, min(hi, getattr(spec, field) + rng.choice((-2, -1, 1, 2))))})


class Designer:
    def __init__(self, taste=None, seed=None):
        self.taste = taste or Taste()
        self.rng = random.Random(seed)

    def evaluate(self, spec):
        bp = house(spec)
        rating = rate(bp)
        return self.taste.predict(rating, tags_for(spec)), rating, bp

    def design(self, generations=15, population=16, keep=4, start=None, on_generation=None):
        """Evolve house specs; returns (score, spec, rating, blueprint) of the best."""
        rng = self.rng
        pool = [start] if start else []
        # Start from what it knows you like, then explore around it.
        roof, palette = self.taste.favourite("roof:"), self.taste.favourite("palette:")
        for _ in range(population // 4 if roof or palette else 0):
            spec = random_spec(rng)
            pool.append(replace(spec, roof=roof[len("roof:"):] if roof else spec.roof,
                                palette=palette[len("palette:"):] if palette else spec.palette))
        pool += [random_spec(rng) for _ in range(population - len(pool))]
        scored = []
        for gen in range(generations):
            scored = sorted((self.evaluate(s) + (s,) for s in pool), key=lambda t: -t[0])
            if on_generation:
                on_generation(gen, scored[0][0], scored[0][3])
            parents = [t[3] for t in scored[:keep]]
            pool = parents + [mutate(rng.choice(parents), rng) for _ in range(population - keep - 2)]
            pool += [random_spec(rng) for _ in range(2)]          # fresh ideas
        best_score, rating, bp, spec = scored[0]
        self.taste.remember({"score": round(best_score, 2), "spec": asdict(spec)})
        return best_score, spec, rating, bp
