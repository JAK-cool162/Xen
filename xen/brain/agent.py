"""Xen: a decision-making model that perceives, feels, thinks, acts and learns.

Every tick:
  1. perceive  - an observation of the world arrives;
  2. feel      - the amygdala predicts harm per action -> felt fear;
  3. think     - if afraid or unsure, imagine futures with the world model;
  4. decide    - pick the action with the best reward-minus-fear utility
                 (or explore: mostly cautiously, sometimes facing its fears so
                 that harmless things can stop being scary);
  5. learn     - remember what happened (pain gets burned into trauma memory)
                 and train the critics and the world model.
"""
import json
from dataclasses import dataclass, field, asdict

import numpy as np

from ..actions import NUM_ACTIONS, VERBS, Action
from .cortex import Cortex
from .critic import Critic
from .emotions import Emotions, Feelings
from .memory import Memory
from .world_model import WorldModel


@dataclass
class XenConfig:
    hidden: tuple = (256, 256)
    lr: float = 3e-4
    gamma: float = 0.95            # how far ahead rewards matter
    fear_gamma: float = 0.9        # fear is about the near future
    tau: float = 0.01
    batch_size: int = 64
    memory: int = 50_000
    trauma_memory: int = 5_000
    trauma_fraction: float = 0.2
    joy_fraction: float = 0.1
    n_step: int = 3
    warmup: int = 1_000
    train_every: int = 4
    explore_start: float = 1.0
    explore_end: float = 0.05
    explore_steps: int = 60_000
    base_caution: float = 4.0      # how much fear outweighs reward
    curiosity: float = 0.02        # weight of the curiosity bonus
    curiosity_scale: float = 20.0
    think_depth: int = 3
    think_breadth: int = 4
    think_fear: float = 0.3        # think when at least this afraid ...
    think_margin: float = 0.01     # ... or when the best options are this close
    think_trust: float = 0.3       # most weight imagination ever gets
    think_after: int = 5_000       # world model updates before imagination is trusted


@dataclass
class Thought:
    action: int
    feelings: Feelings
    mode: str                      # "instinct", "thought", "explore"
    text: str
    options: list = field(default_factory=list)   # (action, score) considered


class Xen:
    def __init__(self, obs_dim, n_actions=NUM_ACTIONS, config=None, seed=0):
        self.config = c = config or XenConfig()
        self.obs_dim = obs_dim
        self.n_actions = n_actions
        self.rng = np.random.default_rng(seed)
        self.striatum = Critic(obs_dim, n_actions, c.hidden, c.lr, c.gamma, c.tau, seed)
        self.amygdala = Critic(obs_dim, n_actions, c.hidden, c.lr, c.fear_gamma, c.tau, seed + 1)
        self.world_model = WorldModel(obs_dim, n_actions, c.hidden, c.lr, seed + 2)
        self.cortex = Cortex(c.think_depth, c.think_breadth, c.gamma, c.think_trust)
        self.emotions = Emotions(c.base_caution)
        self.memory = Memory(obs_dim, c.memory, c.trauma_memory, c.trauma_memory, n_step=c.n_step,
                             gamma=c.gamma, fear_gamma=c.fear_gamma, trauma_fraction=c.trauma_fraction,
                             joy_fraction=c.joy_fraction, seed=seed + 3)
        self.steps = 0
        self.updates = 0
        self.lives = 0

    # ------------------------------------------------------------------ feel
    def fears(self, obs):
        """Expected harm of each action (the amygdala's output)."""
        return np.maximum(self.amygdala.values(obs), 0.0)

    def utility(self, obs, caution=None):
        caution = self.emotions.base_caution if caution is None else caution
        return self.striatum.values(obs) - caution * self.fears(obs)

    @property
    def exploration(self):
        c = self.config
        frac = min(1.0, self.steps / max(1, c.explore_steps))
        return c.explore_start + frac * (c.explore_end - c.explore_start)

    # ---------------------------------------------------------------- decide
    def decide(self, obs, explore=True, emotions=None):
        """Choose an action. `emotions` lets many bodies share one brain."""
        c = self.config
        emotions = emotions or self.emotions
        obs = np.asarray(obs, np.float32)
        reward_q = self.striatum.values(obs[None])[0]
        fear_q = self.fears(obs[None])[0]
        caution = emotions.caution
        utility = reward_q - caution * fear_q
        best = int(np.argmax(utility))
        fear = emotions.anticipate(0.5 * (fear_q[best] + fear_q.mean()))
        boredom = emotions.notice(obs)
        feared = int(np.argmax(fear_q))
        options = []
        restless = self.rng.random() < 0.9 * boredom

        if restless or (explore and (self._warming_up or self.rng.random() < self.exploration * (1.0 - 0.8 * fear))):
            if self.rng.random() < 0.5:
                # Cautious curiosity: try something new, but shy away from feared actions.
                logits = -caution * fear_q * 3.0
                p = np.exp(logits - logits.max())
                action = int(self.rng.choice(self.n_actions, p=p / p.sum()))
            else:
                # Exposure: sometimes try anything, even what it fears. Without this,
                # avoidance would keep a fear alive forever (it could never unlearn it).
                action = int(self.rng.integers(self.n_actions))
            mode = "explore"
        else:
            ranked = np.sort(utility)
            unsure = ranked[-1] - ranked[-2] < c.think_margin
            if (fear >= c.think_fear or unsure) and self.world_model.updates >= c.think_after:
                candidates, _, scores = self.cortex.deliberate(
                    obs, utility, self.world_model,
                    lambda s: self.utility(s, caution), caution)
                options = [(int(a), float(s)) for a, s in zip(candidates, scores)]
                action = int(candidates[int(np.argmax(scores))])
                mode = "thought"
            else:
                action = best
                mode = "instinct"

        feelings = Feelings(**asdict(emotions.now))
        text = self._monologue(action, mode, feelings, feared, fear_q, options)
        return Thought(action, feelings, mode, text, options)

    def _monologue(self, action, mode, feelings, feared, fear_q, options):
        verb = VERBS[Action(action)]
        parts = []
        if feelings.pain > 0.15:
            parts.append("Ouch! That hurt.")
        if feelings.fear >= 0.2 and fear_q[feared] > 0.05:
            parts.append(f"I'm {feelings.mood}; I don't want to {VERBS[Action(feared)]}.")
        if mode == "thought":
            ideas = ", ".join(f"{VERBS[Action(a)]} {s:+.2f}" for a, s in options)
            parts.append(f"Thinking it through ({ideas}) -> I'll {verb}.")
        elif mode == "explore" and feelings.boredom > 0.5:
            parts.append(f"Nothing's happening... {verb}!")
        elif mode == "explore":
            parts.append(f"Let me try: {verb}.")
        else:
            parts.append(f"{verb.capitalize()}.")
        return " ".join(parts)

    # ----------------------------------------------------------------- learn
    def learn(self, obs, action, reward, harm, next_obs, terminal, train=True,
              emotions=None, stream=0, end=False):
        """Take in the outcome of an action and learn from it.

        terminal: this life ended (death). end: the life stops here anyway.
        """
        c = self.config
        surprise = self.world_model.surprise(obs, action, next_obs) * c.curiosity_scale \
            if self.world_model.updates else 0.0
        (emotions or self.emotions).experience(harm, reward, surprise)
        # Rewards are felt on a compressed scale: a diamond is great, not 100x a pebble.
        felt = float(np.sign(reward) * np.log1p(abs(reward)))
        self.memory.remember(obs, action, felt, harm, next_obs, terminal, stream, end=end or terminal)
        self.steps += 1
        if not train or self.steps % c.train_every:
            return None
        if self._warming_up or len(self.memory) < c.batch_size:
            return None
        return self.train_step()

    @property
    def _warming_up(self):
        """A newborn brain acts randomly until it has some memories."""
        return self.updates == 0 and len(self.memory) < self.config.warmup

    def train_step(self):
        c = self.config
        b = self.memory.sample(c.batch_size)
        obs, actions = b["obs"], b["action"]
        # Imagination and fear learn from single steps: what exactly follows what.
        surprise = self.world_model.learn(obs, actions, b["reward1"], b["harm"], b["next1"], b["done1"])
        fear_next = np.argmax(self.utility(b["next1"]), 1)
        fear_td = self.amygdala.learn(obs, actions, b["harm"], b["next1"], b["done1"], fear_next)
        # Reward learns from stretches of n steps, so a diamond credits the digging before it.
        bonus = c.curiosity * np.tanh(c.curiosity_scale * surprise)
        next_actions = np.argmax(self.utility(b["next_obs"]), 1)
        reward_td = self.striatum.learn(obs, actions, b["reward"] + bonus, b["next_obs"], b["done"],
                                        next_actions, b["steps"])
        self.updates += 1
        return {"reward_td": reward_td, "fear_td": fear_td, "surprise": float(surprise.mean())}

    def new_life(self, emotions=None):
        self.lives += 1
        (emotions or self.emotions).reset()

    def new_body(self):
        """Feelings for an extra body driven by this same brain."""
        body = Emotions(self.config.base_caution)
        body.load_state(self.emotions.state())
        return body

    # ------------------------------------------------------------ persistence
    def save(self, path, compact=False):
        """Save the brain. compact=True keeps only what's needed to carry on (half precision,
        no target networks): a small file to share."""
        state = {}
        state.update(self.striatum.net.state("striatum"))
        state.update(self.amygdala.net.state("amygdala"))
        state.update(self.world_model.net.state("world_model"))
        if compact:
            state = {k: v.astype(np.float16) for k, v in state.items()}
        else:
            state.update(self.striatum.target.state("striatum_target"))
            state.update(self.amygdala.target.state("amygdala_target"))
        meta = {"config": asdict(self.config), "obs_dim": self.obs_dim,
                "n_actions": self.n_actions, "steps": self.steps, "updates": self.updates,
                "lives": self.lives, "wm_updates": self.world_model.updates,
                "emotions": self.emotions.state()}
        state["meta"] = np.frombuffer(json.dumps(meta).encode(), np.uint8)
        with open(path, "wb") as f:
            np.savez_compressed(f, **state)

    @classmethod
    def load(cls, path, seed=0, **overrides):
        with np.load(path) as data:
            state = {k: data[k] for k in data.files}
        meta = json.loads(bytes(state.pop("meta")).decode())
        known = XenConfig.__dataclass_fields__
        config = {k: v for k, v in meta["config"].items() if k in known}
        config["hidden"] = tuple(config.get("hidden", XenConfig.hidden))
        config.update(overrides)
        xen = cls(meta["obs_dim"], meta["n_actions"], XenConfig(**config), seed)
        for critic, name in ((xen.striatum, "striatum"), (xen.amygdala, "amygdala")):
            critic.net.load_state(state, name)
            if f"{name}_target.0" in state:
                critic.target.load_state(state, f"{name}_target")
            else:
                critic.target = critic.net.copy()
        xen.world_model.net.load_state(state, "world_model")
        xen.steps, xen.updates, xen.lives = meta["steps"], meta["updates"], meta["lives"]
        xen.world_model.updates = meta["wm_updates"]
        xen.emotions.load_state(meta["emotions"])
        return xen
