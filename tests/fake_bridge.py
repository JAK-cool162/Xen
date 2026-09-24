"""A stand-in for bridge/xen_bridge.js: speaks the same protocol, no Minecraft needed."""
import json
import socketserver
import threading

import numpy as np

from xen import blocks as B
from xen.perception import CUBE_SHAPE, DOWN


def flat_cube():
    cube = np.zeros(CUBE_SHAPE, np.int8)
    cube[:, :DOWN, :] = B.STONE
    return cube.ravel().tolist()


class FakeMinecraft:
    def __init__(self, max_players=3):
        self.max_players = max_players
        self.bots = {}
        self.built = []
        self.chat = []
        self.levers = {}

    def state(self, name, dead=False):
        bot = self.bots[name]
        return {"name": name, "cube": flat_cube(), "position": [0, 64, 0], "yaw": 0, "pitch": 0,
                "health": bot["health"], "food": 20, "night": False, "burning": False, "in_water": False,
                "in_lava": False, "inventory": dict(bot["inventory"]), "mobs": [], "heard": bot.pop("heard", []),
                "dead": dead}

    def handle(self, msg):
        op = msg["op"]
        name = msg.get("bot") or msg.get("name") or (next(iter(self.bots)) if self.bots else "Xen")
        if op == "spawn":
            if name not in self.bots and len(self.bots) >= self.max_players:
                return {"ok": False, "error": "kicked: The server is full!"}
            self.bots.setdefault(name, {"health": 20, "inventory": {"cobblestone": 0}})
            return {"ok": True, "name": name, "state": self.state(name)}
        if op == "list":
            return {"ok": True, "bots": list(self.bots)}
        if name not in self.bots:
            if op == "observe" and not msg.get("bot"):
                self.bots[name] = {"health": 20, "inventory": {"cobblestone": 0}}
            else:
                return {"ok": False, "error": f"no Xen named {name}"}
        if op == "observe":
            return {"ok": True, "state": self.state(name)}
        if op in ("act", "act_all"):
            actions = msg["actions"] if op == "act_all" else {name: msg["action"]}
            states = {}
            for bot_name, action in actions.items():
                bot = self.bots[bot_name]
                dead = False
                if action == "MINE":
                    bot["inventory"]["cobblestone"] += 1
                elif action == "JUMP":                  # jumping hurts in this fake world
                    bot["health"] -= 5
                    if bot["health"] <= 0:
                        dead, bot["health"] = True, 20
                states[bot_name] = self.state(bot_name, dead)
            return {"ok": True, "states": states} if op == "act_all" else {"ok": True, "state": states[name]}
        if op == "build":
            self.built.append(msg)
            return {"ok": True, "placed": sum(b[4] if len(b) > 4 else 1 for b in msg["blocks"]), "skipped": []}
        if op == "scan":
            lo, hi = msg["from"], msg["to"]
            size = [h - l + 1 for l, h in zip(lo, hi)]
            return {"ok": True, "origin": lo, "size": size, "states": ["stone"] * (size[0] * size[1] * size[2])}
        if op == "use":
            key = tuple(msg["pos"])
            self.levers[key] = not self.levers.get(key, False)
            return {"ok": True, "state": f"lever[powered={'true' if self.levers[key] else 'false'}]"}
        if op == "chat":
            self.chat.append((name, msg["text"]))
            return {"ok": True}
        if op == "despawn":
            self.bots.pop(name, None)
            return {"ok": True}
        return {"ok": False, "error": f"unknown op {op}"}


def serve(world):
    """Start a fake bridge on a free port; returns (server, port)."""
    class Handler(socketserver.StreamRequestHandler):
        def handle(self):
            for line in self.rfile:
                reply = world.handle(json.loads(line))
                self.wfile.write((json.dumps(reply) + "\n").encode())

    server = socketserver.ThreadingTCPServer(("127.0.0.1", 0), Handler)
    server.daemon_threads = True
    threading.Thread(target=server.serve_forever, daemon=True).start()
    return server, server.server_address[1]
