"""Thinking: deliberate before acting when it matters.

Most of the time Xen acts on instinct (its learned critics).  When it is
afraid, or its options look equally good, it stops and *thinks*: it imagines
each promising action a few steps into the future with its world model,
weighing imagined rewards against imagined harm, and picks the best future.
"""
import numpy as np


class Cortex:
    def __init__(self, depth=3, breadth=4, gamma=0.97, max_trust=0.5, trust_halflife=3000):
        self.depth = depth
        self.breadth = breadth
        self.gamma = gamma
        self.max_trust = max_trust
        self.trust_halflife = trust_halflife

    def trust(self, world_model):
        """How much imagination is believed; grows as the world model learns."""
        return self.max_trust * (1.0 - 0.5 ** (world_model.updates / self.trust_halflife))

    def deliberate(self, obs, utility, world_model, utility_fn, caution):
        """Return (candidates, imagined values, blended scores)."""
        candidates = np.argsort(-utility)[: self.breadth]
        states = np.repeat(np.asarray(obs, np.float32)[None], len(candidates), 0)
        actions = candidates
        imagined = np.zeros(len(candidates))
        discount = np.ones(len(candidates))
        alive = np.ones(len(candidates))
        for _ in range(self.depth):
            states, reward, harm, p_done = world_model.imagine(states, actions)
            imagined += discount * alive * (reward - caution * harm)
            alive *= 1.0 - p_done
            discount *= self.gamma
            future = utility_fn(states)
            actions = np.argmax(future, 1)
        imagined += discount * alive * future.max(1)
        trust = self.trust(world_model)
        blended = (1.0 - trust) * utility[candidates] + trust * imagined
        return candidates, imagined, blended
