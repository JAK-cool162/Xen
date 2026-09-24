"""Real Minecraft (Java Edition) through the mineflayer bot bridge.

Every Xen in the game is a mineflayer bot: a real player connection, so the
server treats it like any other player.  Start a world (a server, or a
single-player world opened to LAN, with online-mode=false for offline
accounts), then the bridge, then Xen:

    cd bridge && npm install && node xen_bridge.js --host localhost --port 25565
    python -m xen play --world mineflayer            # one Xen
    python -m xen swarm --count 0                    # as many Xens as the server takes

The bridge sends the same local block cube the simulator produces, so a brain
trained in SimCraft keeps its instincts (and fears) in the real game, and
keeps learning there.
"""
import json
import socket
import time

import numpy as np

from .. import blocks as B
from ..actions import NUM_ACTIONS, Action
from ..perception import CUBE_SHAPE, OBS_DIM, Body, encode


class Bridge:
    """JSON-lines client for bridge/xen_bridge.js."""

    def __init__(self, host="127.0.0.1", port=8765, timeout=120.0, attempts=30):
        self.address = (host, port)
        for attempt in range(attempts):
            try:
                self._sock = socket.create_connection(self.address, timeout=timeout)
                self._reader = self._sock.makefile("r", encoding="utf-8")
                return
            except OSError:
                if attempt == attempts - 1:
                    raise ConnectionError(
                        f"Could not reach the Xen bridge at {host}:{port}. "
                        "Start it with: cd bridge && npm install && node xen_bridge.js")
                time.sleep(1.0)

    def call(self, message, check=True):
        self._sock.sendall((json.dumps(message) + "\n").encode())
        line = self._reader.readline()
        if not line:
            raise ConnectionError("The Xen bridge closed the connection.")
        reply = json.loads(line)
        if check and not reply.get("ok"):
            raise RuntimeError(f"bridge: {reply.get('error')}")
        return reply

    def close(self):
        self._sock.close()


def encode_state(state):
    cube = np.asarray(state["cube"], np.int8).reshape(CUBE_SHAPE)
    inventory = state.get("inventory", {})
    body = Body(health=state["health"], hunger=state["food"], night=state["night"],
                burning=state["burning"], hurt=state.get("hurt", 0.0), pitch=state["pitch"],
                blocks=sum(inventory.get(item, 0) for item in B.PLACEABLE),
                food=inventory.get("food", 0), in_water=state["in_water"], in_lava=state["in_lava"])
    return encode(cube, state["yaw"], body, state.get("mobs", ()))


def outcome(prev, state):
    """(reward, harm, dead, events) of going from one bridge state to the next."""
    dead = bool(state.get("dead"))
    lost = prev["health"] if dead else max(0.0, prev["health"] - state["health"])
    state["hurt"] = min(1.0, lost / 5.0)
    harm = lost / 20.0 + (1.0 if dead else 0.0)
    reward, events = 0.0, []
    if not dead:
        for item, value in B.ITEM_VALUE.items():
            gained = state["inventory"].get(item, 0) - prev["inventory"].get(item, 0)
            if gained > 0:
                reward += value * gained
                events.append(f"got {item}")
        if state["food"] > prev["food"] and prev["food"] < 14:
            reward += 0.5 * (state["food"] - prev["food"]) / 6.0
            events.append("ate")
    if lost:
        events.append(f"lost {lost:g} health")
    if dead:
        events.append("died")
    return reward, harm, dead, events


class MineflayerWorld:
    """One Xen body in a real world, with the same interface as SimCraft."""
    obs_dim = OBS_DIM
    n_actions = NUM_ACTIONS

    def __init__(self, host="127.0.0.1", port=8765, name=None, speak=False, bridge=None):
        self.bridge = bridge or Bridge(host, port)
        self.name = name
        self.speak = speak
        self._last = None
        self._last_spoken = 0.0
        self.t = 0

    def _msg(self, **message):
        if self.name:
            message["bot"] = self.name
        return message

    def reset(self):
        if self.name and self.name not in self.bridge.call({"op": "list"})["bots"]:
            self.bridge.call({"op": "spawn", "name": self.name})
        self._last = self.bridge.call(self._msg(op="observe"))["state"]
        self.name = self.name or self._last.get("name")
        self._last["hurt"] = 0.0
        self.t = 0
        return encode_state(self._last)

    def step(self, action):
        state = self.bridge.call(self._msg(op="act", action=Action(int(action)).name))["state"]
        reward, harm, dead, events = outcome(self._last, state)
        self._last = state
        self.t += 1
        info = {"events": events, "terminal": dead, "health": state["health"], "hunger": state["food"],
                "inventory": state["inventory"], "t": self.t, "heard": state.get("heard", []),
                "position": state.get("position")}
        return encode_state(state), reward, harm, dead, info

    @property
    def position(self):
        return self._last.get("position") if self._last else None

    def build(self, blueprint, origin=None, mode="commands", clear=True):
        """Build a blueprint in the world (origin defaults to two blocks in front of Xen)."""
        if origin is None:
            x, y, z = self.position
            origin = [x + 2, y, z + 2]
        return self.bridge.call(self._msg(op="build", origin=list(origin), blocks=blueprint.to_runs(), mode=mode,
                                          clear=list(blueprint.clearance()) if clear else None))

    def scan(self, corner1, corner2):
        return self.bridge.call(self._msg(op="scan", **{"from": list(corner1), "to": list(corner2)}))

    def use(self, pos):
        return self.bridge.call(self._msg(op="use", pos=list(pos)))["state"]

    def say(self, text, force=False):
        """Speak in game chat. Feelings are rate limited; forced replies always go out."""
        if force or (self.speak and time.time() - self._last_spoken > 3.0):
            self._last_spoken = time.time()
            self.bridge.call(self._msg(op="chat", text=text))

    def close(self):
        self.bridge.close()
