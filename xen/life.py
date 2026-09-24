"""Xen's life loop: perceive -> feel -> think -> act -> learn, continuously."""
from dataclasses import dataclass, field


@dataclass
class LifeStats:
    steps: int = 0
    reward: float = 0.0
    harm: float = 0.0
    fear: float = 0.0
    thoughts: int = 0
    died: bool = False
    cause: str = ""
    items: dict = field(default_factory=dict)

    @property
    def mean_fear(self):
        return self.fear / max(1, self.steps)


def live(world, xen, lives=None, learn=True, explore=True, on_step=None, on_life=None,
         save_path=None, save_every=10_000, should_stop=None):
    """Run Xen in a world for a number of lives (None = forever)."""
    life = 0
    while lives is None or life < lives:
        obs = world.reset()
        xen.new_life()
        stats = LifeStats()
        done = False
        while not done:
            thought = xen.decide(obs, explore=explore)
            next_obs, reward, harm, done, info = world.step(thought.action)
            terminal = bool(info.get("terminal", False))
            xen.learn(obs, thought.action, reward, harm, next_obs, terminal, train=learn, end=done)
            stats.steps += 1
            stats.reward += reward
            stats.harm += harm
            stats.fear += thought.feelings.fear
            stats.thoughts += thought.mode == "thought"
            for event in info.get("events", ()):
                if event.startswith("got "):
                    item = event[4:]
                    stats.items[item] = stats.items.get(item, 0) + 1
                elif event.startswith("died"):
                    stats.cause = event[6:-1]
            stats.died = stats.died or terminal
            if on_step:
                on_step(world, xen, thought, reward, harm, info)
            if save_path and learn and xen.steps % save_every == 0:
                xen.save(save_path)
            if should_stop and should_stop():
                return
            obs = next_obs
        life += 1
        if on_life:
            on_life(life, stats, xen)
    if save_path and learn:
        xen.save(save_path)
