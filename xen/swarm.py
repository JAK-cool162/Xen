"""Many Xens, one mind.

A swarm is any number of Xen players on a server, all driven by a single
shared brain: every body's experience (and every body's pain) teaches the
same critics, so the swarm learns faster the bigger it gets.  Each body still
has its own feelings and short-term memory.

With count=None the swarm keeps inviting new Xens until the server says it is
full, then keeps trying now and then (players leave, slots free up).
"""
import queue
import threading
import time
from dataclasses import dataclass, field

from .actions import Action
from .life import LifeStats
from .worlds.mineflayer import MineflayerWorld, encode_state, outcome

MAX_NAME = 16


@dataclass
class Member:
    world: MineflayerWorld
    obs: object
    emotions: object
    stats: LifeStats = field(default_factory=LifeStats)
    lives: int = 0


class Swarm:
    def __init__(self, xen, bridge, count=4, prefix="Xen", spawn_every=5.0, spread=0, skills=None,
                 speak=False, log=print):
        self.xen = xen
        self.bridge = bridge
        self.target = count or None          # None: as many as the server allows
        self.prefix = prefix[:MAX_NAME - 4]
        self.spawn_every = spawn_every
        self.spread = spread
        self.skills = skills
        self.speak = speak
        self.log = log
        self.bodies = {}
        self._serial = 0
        self._next_spawn = 0.0
        self._handled = set()
        self._requests = queue.Queue()
        self._worker = None

    def _name(self):
        self._serial += 1
        return self.prefix if self._serial == 1 else f"{self.prefix}_{self._serial}"

    def want_more(self):
        return self.target is None or len(self.bodies) < self.target

    def spawn(self):
        name = self._name()
        reply = self.bridge.call({"op": "spawn", "name": name, "spread": self.spread}, check=False)
        if not reply.get("ok"):
            self.log(f"[swarm] {name} could not join: {reply.get('error')}")
            self._serial -= 1
            self._next_spawn = time.time() + 60.0         # the server is full or unhappy; back off
            return None
        state = reply["state"]
        state["hurt"] = 0.0
        world = MineflayerWorld(name=name, bridge=self.bridge, speak=self.speak)
        world._last = state
        member = Member(world, encode_state(state), self.xen.new_body())
        self.bodies[name] = member
        self._next_spawn = time.time() + self.spawn_every
        self.log(f"[swarm] {name} joined ({len(self.bodies)} Xen online)")
        return member

    def step(self):
        """One decision for every body, executed simultaneously."""
        if self.want_more() and time.time() >= self._next_spawn:
            self.spawn()
        if not self.bodies:
            time.sleep(1.0)
            return 0
        thoughts = {name: self.xen.decide(m.obs, emotions=m.emotions) for name, m in self.bodies.items()}
        reply = self.bridge.call({"op": "act_all", "actions": {n: Action(t.action).name for n, t in thoughts.items()}})
        heard = []
        for name, state in reply["states"].items():
            member = self.bodies.get(name)
            if member is None:
                continue
            if state.get("gone"):
                self.log(f"[swarm] {name} left the server")
                del self.bodies[name]
                continue
            if state.get("error"):
                self.log(f"[swarm] {name}: {state['error']}")
                continue
            reward, harm, dead, events = outcome(member.world._last, state)
            member.world._last = state
            next_obs = encode_state(state)
            self.xen.learn(member.obs, thoughts[name].action, reward, harm, next_obs, dead,
                           emotions=member.emotions, stream=name)
            s = member.stats
            s.steps += 1
            s.reward += reward
            s.harm += harm
            s.fear += thoughts[name].feelings.fear
            feelings = thoughts[name].feelings
            if self.speak and (feelings.pain > 0.15 or feelings.fear > 0.6):
                self._say(member, f"[{feelings.mood}] {thoughts[name].text}")
            if dead:
                member.lives += 1
                self.log(f"[swarm] {name} died after {s.steps} steps (reward {s.reward:.1f}); respawning")
                member.stats = LifeStats()
                self.xen.new_life(member.emotions)
            member.obs = next_obs
            heard += [(name, h) for h in state.get("heard", [])]
        for _, message in heard:
            key = (message["from"], message["text"])
            if key in self._handled or not self.skills or not message["text"].lower().startswith(("xen", "!xen", "@xen")):
                continue
            self._handled.add(key)
            self._request(message["text"])
        if len(self._handled) > 500:
            self._handled.clear()
        return len(self.bodies)

    def _request(self, text):
        """Skills (building, redstone...) run on a helper thread so the swarm keeps living."""
        if self._worker is None:
            self._worker = threading.Thread(target=self._work, daemon=True)
            self._worker.start()
        self._requests.put(text)

    def _work(self):
        from .worlds.mineflayer import Bridge
        bridge = Bridge(*self.bridge.address)
        while True:
            text = self._requests.get()
            if text is None:
                break
            member = self.builder()
            body = MineflayerWorld(name=member.world.name, bridge=bridge) if member else None
            try:
                if body:
                    body.reset()
                answer = self.skills.handle(text, body, swarm=self)
            except Exception as err:                    # a failed skill must not stop the swarm
                answer = f"Sorry, that didn't work: {err}"
            if answer and body:
                try:
                    body.say(answer, force=True)
                except (RuntimeError, ConnectionError) as err:
                    self.log(f"[swarm] could not reply: {err}")
        bridge.close()

    def builder(self):
        """The Xen that answers requests: the first one (give it /op to build with commands)."""
        return self.bodies.get(self.prefix) or next(iter(self.bodies.values()), None)

    def _say(self, member, text):
        try:
            member.world.say(text, force=True)
        except RuntimeError as err:                     # it left the server meanwhile
            self.log(f"[swarm] {member.world.name} could not speak: {err}")

    def run(self, save_path=None, save_every=1000, should_stop=None):
        last_save = self.xen.steps
        while not (should_stop and should_stop()):
            self.step()
            if save_path and self.xen.steps - last_save >= save_every:
                self.xen.save(save_path)
                last_save = self.xen.steps
        if save_path:
            self.xen.save(save_path)
        if self._worker:
            self._requests.put(None)
