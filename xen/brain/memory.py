"""Xen's memories: everyday experience plus vivid memories of what hurt and what delighted.

Experience is remembered as short stretches (n steps), so the harm or reward
at the end of a stretch is linked to the choices at its start: the cue before
the shock.  Painful stretches go into *trauma* memory and very rewarding ones
into *joy* memory; both are replayed far more often than ordinary moments,
which is how a single burn teaches Xen to fear lava (fear conditioning), and a
single diamond makes digging deep feel worth it.
"""
from collections import defaultdict, deque

import numpy as np


class Replay:
    """Fixed-size ring buffer of transitions (observations stored as int8).

    Each entry holds both views of a moment: what happened one step later
    (reward1, harm, next1, done1) and the discounted stretch of n steps
    (reward, next_obs, done, steps).
    """

    FIELDS = ("action", "reward", "done", "steps", "reward1", "harm", "done1")

    def __init__(self, capacity, obs_dim):
        self.capacity = int(capacity)
        self.obs = np.zeros((self.capacity, obs_dim), np.int8)
        self.next_obs = np.zeros((self.capacity, obs_dim), np.int8)
        self.next1 = np.zeros((self.capacity, obs_dim), np.int8)
        for name in self.FIELDS:
            setattr(self, name, np.zeros(self.capacity, np.int64 if name == "action" else np.float32))
        self.size = 0
        self._next = 0

    @staticmethod
    def _pack(obs):
        return np.round(np.clip(obs, -1, 1) * 127).astype(np.int8)

    def add(self, obs, action, reward, next_obs, done, steps, reward1, harm, next1, done1):
        i = self._next
        self.obs[i] = self._pack(obs)
        self.next_obs[i] = self._pack(next_obs)
        self.next1[i] = self._pack(next1)
        for name, value in zip(self.FIELDS, (action, reward, float(done), steps, reward1, harm, float(done1))):
            getattr(self, name)[i] = value
        self._next = (i + 1) % self.capacity
        self.size = min(self.size + 1, self.capacity)

    def take(self, idx):
        batch = {name: getattr(self, name)[idx] for name in self.FIELDS}
        batch["obs"] = self.obs[idx].astype(np.float32) / 127.0
        batch["next_obs"] = self.next_obs[idx].astype(np.float32) / 127.0
        batch["next1"] = self.next1[idx].astype(np.float32) / 127.0
        return batch

    def __len__(self):
        return self.size


class Memory:
    """Ordinary experience + trauma + joy, sampled together."""

    def __init__(self, obs_dim, capacity=50_000, trauma_capacity=5_000, joy_capacity=5_000,
                 n_step=3, gamma=0.97, fear_gamma=0.9, trauma_fraction=0.2, joy_fraction=0.1,
                 joy_threshold=0.5, seed=0):
        self.experience = Replay(capacity, obs_dim)
        self.trauma = Replay(trauma_capacity, obs_dim)
        self.joy = Replay(joy_capacity, obs_dim)
        self.n_step = n_step
        self.gamma, self.fear_gamma = gamma, fear_gamma
        self.trauma_fraction, self.joy_fraction = trauma_fraction, joy_fraction
        self.joy_threshold = joy_threshold
        # One short-term memory per body, so a swarm of Xens sharing a brain
        # don't mix up each other's moments.
        self._recent = defaultdict(lambda: deque(maxlen=n_step))
        self._rng = np.random.default_rng(seed)

    def remember(self, obs, action, reward, harm, next_obs, done, stream=0, end=False):
        """done: the life ended (death); end: the stretch stops here anyway (time's up)."""
        recent = self._recent[stream]
        recent.append((obs, action, reward, harm, next_obs, done))
        if len(recent) == self.n_step:
            self._commit(recent, next_obs, done)
        if done or end:
            while recent:
                self._commit(recent, next_obs, done)
            del self._recent[stream]

    def _commit(self, recent, next_obs, done):
        obs, action, reward1, harm1, next1, done1 = recent[0]
        reward = sum(self.gamma ** i * t[2] for i, t in enumerate(recent))
        harm_ahead = sum(t[3] for t in recent)
        entry = (obs, action, reward, next_obs, done, len(recent), reward1, harm1, next1, done1)
        self.experience.add(*entry)
        if harm_ahead > 0:
            self.trauma.add(*entry)          # the cue before the shock, and the shock
        if reward >= self.joy_threshold:
            self.joy.add(*entry)
        recent.popleft()

    def sample(self, n):
        n_trauma = min(int(round(n * self.trauma_fraction)), len(self.trauma))
        n_joy = min(int(round(n * self.joy_fraction)), len(self.joy))
        parts = [self.experience.take(self._rng.integers(0, len(self.experience), n - n_trauma - n_joy))]
        if n_trauma:
            parts.append(self.trauma.take(self._rng.integers(0, len(self.trauma), n_trauma)))
        if n_joy:
            parts.append(self.joy.take(self._rng.integers(0, len(self.joy), n_joy)))
        return {k: np.concatenate([p[k] for p in parts]) for k in parts[0]}

    def __len__(self):
        return len(self.experience)
