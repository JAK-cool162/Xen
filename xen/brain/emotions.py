"""Xen's feelings.

Fear is not decoration: it is the signal that makes Xen learn to survive.

* Pain     - harm happening right now.  It is the teacher.
* Fear     - harm Xen *anticipates*, predicted by the amygdala from what it
             sees.  It rises instantly and fades slowly.
* Caution  - how much fear weighs on decisions.  Pain sensitises Xen (it gets
             more careful after being hurt); safe time habituates it back.
* Curiosity - surprise of the world model; new things are interesting.
* Satisfaction - the glow of recent rewards.
* Boredom  - nothing is changing; Xen gets restless and tries something else
             instead of repeating a pointless action forever.
"""
from dataclasses import dataclass, asdict

import numpy as np


@dataclass
class Feelings:
    pain: float = 0.0
    fear: float = 0.0
    caution: float = 0.0
    curiosity: float = 0.0
    satisfaction: float = 0.0
    boredom: float = 0.0

    @property
    def mood(self):
        if self.pain > 0.15:
            return "hurt"
        if self.fear > 0.7:
            return "terrified"
        if self.fear > 0.4:
            return "afraid"
        if self.fear > 0.2:
            return "uneasy"
        if self.satisfaction > 0.5:
            return "pleased"
        if self.boredom > 0.5:
            return "bored"
        if self.curiosity > 0.5:
            return "curious"
        return "calm"

    def as_dict(self):
        return {**asdict(self), "mood": self.mood}


class Emotions:
    def __init__(self, base_caution=4.0, sensitization_gain=3.0,
                 habituation=0.997, max_sensitization=2.0, fear_gain=3.0):
        self.base_caution = base_caution
        self.sensitization_gain = sensitization_gain
        self.habituation = habituation
        self.max_sensitization = max_sensitization
        self.fear_gain = fear_gain
        self.sensitization = 0.0
        self.now = Feelings(caution=base_caution)
        self._last_view = None
        self._same = 0

    @property
    def caution(self):
        return self.base_caution * (1.0 + self.sensitization)

    def anticipate(self, expected_harm):
        """The amygdala's prediction becomes felt fear."""
        felt = 1.0 - float(np.exp(-self.fear_gain * max(expected_harm, 0.0)))
        self.now.fear = max(felt, self.now.fear * 0.85)
        return self.now.fear

    def notice(self, obs):
        """Boredom builds while nothing really changes around it.

        Spinning on the spot or nodding doesn't count as something happening: the
        summary compares what is around it (how much solid, lava, water, treasure
        and mobs in its near window, whichever way it faces) and its body state.
        """
        obs = np.asarray(obs, np.float32)
        view = None
        if len(obs) > 750 + 13:
            around = obs[:750].reshape(150, 5).sum(0)
            body = np.delete(obs[-13:], [5, 6, 7])           # without which way it's looking up or down
            view = np.round(np.concatenate([around, body]), 3).tobytes()
        else:
            view = obs.tobytes()
        self._same = self._same + 1 if view == self._last_view else 0
        self._last_view = view
        self.now.boredom = 1.0 - float(np.exp(-self._same / 3.0))
        return self.now.boredom

    def experience(self, harm, reward, surprise):
        """Update feelings after something happened."""
        f = self.now
        f.pain = max(min(harm, 1.0), f.pain * 0.5)
        self.sensitization = min(self.max_sensitization,
                                 self.sensitization * self.habituation + self.sensitization_gain * harm)
        f.caution = self.caution
        f.curiosity = 0.8 * f.curiosity + 0.2 * float(np.tanh(surprise))
        f.satisfaction = max(float(np.tanh(max(reward, 0.0))), f.satisfaction * 0.97)
        return f

    def reset(self):
        """A new life: the body calms down, but sensitization is remembered."""
        self.now = Feelings(caution=self.caution)

    def state(self):
        return {"sensitization": self.sensitization}

    def load_state(self, state):
        self.sensitization = float(state.get("sensitization", 0.0))
        self.now.caution = self.caution
