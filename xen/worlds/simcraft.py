"""SimCraft: a small, fast Minecraft-like voxel world for Xen to grow up in.

It keeps the parts of Minecraft that matter for learning to survive and
progress: terrain with dirt, stone and trees, ores that get richer (and more
dangerous) with depth, caves, lava, water, fall damage, day and night,
zombies, hunger and food.  Controls are the same actions Xen uses in the real
game, and observations come from the same perception code.
"""
from dataclasses import dataclass

import numpy as np

from .. import blocks as B
from ..actions import NUM_ACTIONS, Action
from ..perception import (EYE, NEAR, OBS_DIM, Body, Senses, Sight, cast_rays_grid, facing, in_view,
                          line_of_sight)

PAD = NEAR + 2


@dataclass
class Mob:
    x: int
    y: int
    z: int
    hp: int = 10
    cooldown: int = 0


class SimCraft:
    obs_dim = OBS_DIM
    n_actions = NUM_ACTIONS

    def __init__(self, size=64, height=24, max_steps=1500, day_length=800, seed=None):
        self.size = size
        self.height = height
        self.max_steps = max_steps
        self.day_length = day_length
        self.rng = np.random.default_rng(seed)
        self.blocks = None
        self.senses = Senses()
        self.reset()

    # ------------------------------------------------------------ world gen
    def reset(self, seed=None):
        if seed is not None:
            self.rng = np.random.default_rng(seed)
        self._generate()
        self.t = 0
        self.health = 20
        self.hunger = 20
        self.burning = 0
        self.yaw = int(self.rng.integers(4))
        self.pitch = 0
        self.hurt = 0.0
        self.inventory = {item: 0 for item in B.INVENTORY_ITEMS}
        self.mobs = []
        self.cause = ""
        self._spawn_player()
        self.senses.reset()                    # a new world: nothing known yet
        return self.observe()

    def _generate(self):
        rng, X, Y = self.rng, self.size, self.height
        Z = X
        xs, zs = np.meshgrid(np.arange(X), np.arange(Z), indexing="ij")
        surface = np.full((X, Z), Y * 0.6)
        for _ in range(4):
            fx, fz = rng.uniform(0.05, 0.3, 2)
            surface += rng.uniform(0.5, 1.5) * np.sin(fx * xs + fz * zs + rng.uniform(0, 6.3))
        surface = np.clip(surface.astype(int), 8, Y - 8)
        self.surface = surface

        y = np.arange(Y)[None, :, None]
        top = surface[:, None, :]
        world = np.zeros((X, Y, Z), np.int8)
        world[y < top - 3] = B.STONE
        world[(y >= top - 3) & (y < top)] = B.DIRT
        world[y == top] = B.GRASS

        stone = world == B.STONE
        roll = rng.random(world.shape)
        yy = np.broadcast_to(y, world.shape)
        world[stone & (roll < 0.05) & (yy < top - 4)] = B.COAL
        world[stone & (roll > 0.97) & (yy <= 10)] = B.IRON
        world[stone & (roll > 0.94) & (roll < 0.95) & (yy <= 6)] = B.GOLD
        diamonds = stone & (roll > 0.955) & (roll < 0.97) & (yy <= 5)
        world[diamonds] = B.DIAMOND

        # Caves: wandering tunnels, flooded with lava near the bottom.
        for _ in range(rng.integers(2, 5) * (X * Z) // 1024):
            p = np.array([rng.integers(3, X - 3), rng.integers(3, 9), rng.integers(3, Z - 3)], float)
            heading = rng.normal(size=3)
            for _ in range(rng.integers(25, 60)):
                heading = heading * 0.8 + rng.normal(size=3) * 0.5
                heading[1] *= 0.4
                p = np.clip(p + heading / (np.linalg.norm(heading) + 1e-6), [2, 2, 2], [X - 3, Y - 6, Z - 3])
                cx, cy, cz = p.astype(int)
                world[cx - 1:cx + 2, cy:cy + 2, cz - 1:cz + 2] = B.AIR
        cave = (world == B.AIR) & (yy <= 3)
        world[cave] = B.LAVA

        # Hidden lava pockets, often right next to diamonds.
        for x, yv, z in np.argwhere(diamonds):
            if rng.random() < 0.5 and yv > 1:
                world[x, yv - 1, z] = B.LAVA
        for _ in range(rng.integers(3, 7) * (X * Z) // 1024):
            cx, cy, cz = rng.integers(3, X - 3), rng.integers(1, 5), rng.integers(3, Z - 3)
            world[cx - 1:cx + 2, cy:cy + 1, cz - 1:cz + 2] = B.LAVA

        # Ponds of water on the surface.
        for _ in range(rng.integers(0, 3) * (X * Z) // 1024):
            cx, cz = rng.integers(4, X - 4), rng.integers(4, Z - 4)
            h = surface[cx, cz]
            world[cx - 1:cx + 2, h - 1:h + 1, cz - 1:cz + 2] = B.WATER

        world[:, 0, :] = B.BEDROCK

        # Trees.
        for _ in range(rng.integers(5, 10) * (X * Z) // 1024):
            x, z = rng.integers(3, X - 3), rng.integers(3, Z - 3)
            h = surface[x, z]
            if world[x, h, z] != B.GRASS:
                continue
            trunk = int(rng.integers(3, 6))
            canopy = world[x - 2:x + 3, h + trunk - 1:h + trunk + 2, z - 2:z + 3]
            canopy[canopy == B.AIR] = B.LEAVES
            world[x, h + trunk + 2, z] = B.LEAVES
            world[x, h + 1:h + trunk + 1, z] = B.LOG

        padded = np.full((X + 2 * PAD, Y + 2 * PAD, Z + 2 * PAD), B.BEDROCK, np.int8)
        padded[:, PAD + Y:, :] = B.AIR
        padded[:PAD, :, :] = B.BEDROCK
        padded[PAD + X:, :, :] = B.BEDROCK
        padded[:, :, :PAD] = B.BEDROCK
        padded[:, :, PAD + Z:] = B.BEDROCK
        padded[PAD:PAD + X, PAD:PAD + Y, PAD:PAD + Z] = world
        self.blocks = padded

    def _spawn_player(self):
        X = self.size
        for _ in range(200):
            x, z = self.rng.integers(X // 4, 3 * X // 4, 2)
            px, pz = x + PAD, z + PAD
            column = B.SOLID[self.blocks[px, :, pz]]
            py = int(np.nonzero(column[:PAD + self.height])[0].max()) + 1
            if self.blocks[px, py - 1, pz] == B.GRASS and self._free(px, py, pz):
                self.pos = [px, py, pz]
                return
        self.pos = [px, py, pz]

    # --------------------------------------------------------------- helpers
    def _free(self, x, y, z):
        """Can a two-block-tall creature stand here?"""
        b = self.blocks
        return not (B.SOLID[b[x, y, z]] or B.SOLID[b[x, y + 1, z]])

    def _mob_at(self, x, y, z):
        for mob in self.mobs:
            if mob.x == x and mob.z == z and mob.y - 1 <= y <= mob.y + 1:
                return mob
        return None

    def _occupied_by_player(self, x, y, z):
        px, py, pz = self.pos
        return x == px and z == pz and py <= y <= py + 1

    def _target(self):
        x, y, z = self.pos
        (fx, fz), _ = facing(self.yaw)
        if self.pitch < 0:
            return x, y - 1, z
        return x + fx, y + (1 if self.pitch > 0 else 0), z + fz

    def _sky(self, x, y, z):
        return not B.SOLID[self.blocks[x, y + 2:, z]].any()

    @property
    def night(self):
        return (self.t % self.day_length) >= 0.7 * self.day_length

    # ------------------------------------------------------------------ step
    def step(self, action):
        action = Action(int(action))
        reward, damage, ticks, events = 0.0, 0, 1, []
        (fx, fz), (rx, rz) = facing(self.yaw)
        moves = {Action.FORWARD: (fx, fz), Action.BACK: (-fx, -fz),
                 Action.LEFT: (-rx, -rz), Action.RIGHT: (rx, rz)}
        if action in moves:
            self._walk(*moves[action])
        elif action == Action.JUMP:
            self._jump(fx, fz)
        elif action == Action.TURN_LEFT:
            self.yaw = (self.yaw - 1) % 4
        elif action == Action.TURN_RIGHT:
            self.yaw = (self.yaw + 1) % 4
        elif action == Action.LOOK_UP:
            self.pitch = min(1, self.pitch + 1)
        elif action == Action.LOOK_DOWN:
            self.pitch = max(-1, self.pitch - 1)
        elif action == Action.MINE:
            gained, ticks = self._mine(events)
            reward += gained
        elif action == Action.PLACE:
            self._place(events)
        elif action == Action.ATTACK:
            reward += self._attack(events)
        elif action == Action.EAT:
            reward += self._eat(events)

        for _ in range(ticks):              # mining keeps the button held for a while
            damage += self._tick(events)
            if damage >= self.health:
                break

        self.health -= damage
        self.hurt = min(1.0, damage / 5.0)
        harm = damage / 20.0
        terminal = self.health <= 0
        if terminal:
            harm += 1.0
            events.append(f"died ({self.cause or 'unknown'})")
        elif damage:
            self.cause = ""
        done = terminal or self.t >= self.max_steps
        info = {"events": events, "terminal": terminal, "health": self.health,
                "hunger": self.hunger, "inventory": dict(self.inventory), "t": self.t}
        return self.observe(), float(reward), float(harm), done, info

    def _tick(self, events):
        """Advance the world by one game tick; returns damage taken."""
        self.t += 1
        damage = self._gravity(events) + self._hazards(events) + self._update_mobs(events)
        if self.t % 60 == 0:
            self.hunger = max(0, self.hunger - 1)
        if self.hunger == 0 and self.t % 20 == 0:
            damage += 1
            self.cause = self.cause or "starvation"
            events.append("starving")
        if self.hunger >= 16 and self.t % 30 == 0 and self.health < 20:
            self.health += 1
        return damage

    def _walk(self, dx, dz):
        x, y, z = self.pos
        nx, nz = x + dx, z + dz
        if self._free(nx, y, nz) and not self._mob_at(nx, y, nz):
            self.pos = [nx, y, nz]
            return True
        return False

    def _jump(self, fx, fz):
        x, y, z = self.pos
        b = self.blocks
        if B.LIQUID[b[x, y, z]] and self._free(x, y + 1, z):
            self.pos = [x, y + 1, z]           # swim up
            self._walk(fx, fz)
            return
        nx, nz = x + fx, z + fz
        if (B.SOLID[b[nx, y, nz]] and self._free(nx, y + 1, nz) and not B.SOLID[b[x, y + 2, z]]
                and not self._mob_at(nx, y + 1, nz)):
            self.pos = [nx, y + 1, nz]
        else:
            self._walk(fx, fz)

    def _mine(self, events):
        """Hold the mine button until the target breaks: (reward, ticks spent)."""
        target = self._target()
        block = int(self.blocks[target])
        hardness = int(B.HARDNESS[block])
        if hardness < 0:
            return 0.0, 1
        self.blocks[target] = B.AIR
        item = B.DROPS.get(block)
        if block == B.LEAVES and self.rng.random() < 0.15:
            item = "food"
        if item is None:
            return 0.0, hardness
        reward = B.satisfaction(item, self.inventory[item])
        self.inventory[item] += 1
        events.append(f"got {item}")
        return reward, hardness

    def _place(self, events):
        target = self._target()
        if B.SOLID[self.blocks[target]] or self._occupied_by_player(*target) or self._mob_at(*target):
            return
        for item, block in B.PLACEABLE.items():
            if self.inventory[item] > 0:
                self.inventory[item] -= 1
                self.blocks[target] = block
                events.append(f"placed {item}")
                return

    def _attack(self, events):
        x, y, z = self.pos
        (fx, fz), _ = facing(self.yaw)
        mob = self._mob_at(x + fx, y, z + fz)
        if mob is None:
            return 0.0
        mob.hp -= 4
        events.append("hit zombie")
        if mob.hp > 0:
            return 0.0
        self.mobs.remove(mob)
        events.append("killed zombie")
        if self.rng.random() < 0.7:
            self.inventory["food"] += 1
        return 1.0

    def _eat(self, events):
        if self.inventory["food"] <= 0 or self.hunger >= 20:
            return 0.0
        before = self.hunger
        self.inventory["food"] -= 1
        self.hunger = min(20, self.hunger + 6)
        events.append("ate")
        return 0.5 * (self.hunger - before) / 6.0 if before < 14 else 0.0

    def _gravity(self, events):
        x, y, z = self.pos
        b = self.blocks
        if B.LIQUID[b[x, y, z]]:
            if not B.SOLID[b[x, y - 1, z]]:
                self.pos[1] = y - 1
            return 0
        fell = 0
        while not B.SOLID[b[x, y - 1, z]]:
            y -= 1
            fell += 1
            if B.LIQUID[b[x, y, z]]:
                fell = 0
                break
        self.pos[1] = y
        if fell > 3:
            events.append(f"fell {fell} blocks")
            self.cause = "fall"
            return fell - 3
        return 0

    def _hazards(self, events):
        x, y, z = self.pos
        b = self.blocks
        here = (b[x, y, z], b[x, y + 1, z])
        if B.LAVA in here:
            self.burning = 6
            self.cause = "lava"
            events.append("in lava")
            return 4
        if B.WATER in here:
            self.burning = 0
        if self.burning > 0:
            self.burning -= 1
            self.cause = self.cause or "fire"
            events.append("burning")
            return 1
        return 0

    # ------------------------------------------------------------------ mobs
    def _update_mobs(self, events):
        damage = 0
        px, py, pz = self.pos
        underground = not self._sky(px, py, pz)
        if len(self.mobs) < 2:
            if self.night and self.rng.random() < 0.01:
                self._spawn_mob(surface=True)
            elif underground and self.rng.random() < 0.004:
                self._spawn_mob(surface=False)
        b = self.blocks
        for mob in list(self.mobs):
            if B.LAVA in (b[mob.x, mob.y, mob.z], b[mob.x, mob.y + 1, mob.z]):
                mob.hp = 0
            elif not self.night and self._sky(mob.x, mob.y, mob.z):
                mob.hp -= 1                       # zombies burn in daylight
            if mob.hp <= 0 or abs(mob.x - px) + abs(mob.z - pz) > 24:
                self.mobs.remove(mob)
                continue
            if self.t % 2 == 0:
                self._chase(mob)
            while not B.SOLID[b[mob.x, mob.y - 1, mob.z]] and not B.LIQUID[b[mob.x, mob.y, mob.z]]:
                mob.y -= 1
            mob.cooldown = max(0, mob.cooldown - 1)
            if abs(mob.x - px) + abs(mob.z - pz) <= 1 and abs(mob.y - py) <= 1 and mob.cooldown == 0:
                damage += 2
                mob.cooldown = 20
                self.cause = "zombie"
                events.append("zombie attack")
        return damage

    def _chase(self, mob):
        px, _, pz = self.pos
        dx, dz = np.sign(px - mob.x), np.sign(pz - mob.z)
        steps = [(dx, 0), (0, dz)] if abs(px - mob.x) >= abs(pz - mob.z) else [(0, dz), (dx, 0)]
        b = self.blocks
        for sx, sz in steps:
            if sx == 0 and sz == 0:
                continue
            nx, nz = mob.x + sx, mob.z + sz
            for ny in (mob.y, mob.y + 1):
                if ny == mob.y + 1 and B.SOLID[b[mob.x, mob.y + 2, mob.z]]:
                    continue
                if (self._free(nx, ny, nz) and B.LAVA not in (b[nx, ny, nz], b[nx, ny - 1, nz])
                        and not self._occupied_by_player(nx, ny, nz)
                        and not self._occupied_by_player(nx, ny + 1, nz)
                        and not self._mob_at(nx, ny, nz)):
                    mob.x, mob.y, mob.z = int(nx), int(ny), int(nz)
                    return

    def _spawn_mob(self, surface):
        px, py, pz = self.pos
        lo, hi = PAD, PAD + self.size - 1
        for _ in range(10):
            angle, dist = self.rng.uniform(0, 2 * np.pi), self.rng.uniform(6, 12)
            x = int(np.clip(px + dist * np.cos(angle), lo, hi))
            z = int(np.clip(pz + dist * np.sin(angle), lo, hi))
            if surface:
                column = B.SOLID[self.blocks[x, :, z]]
                y = int(np.nonzero(column[:PAD + self.height])[0].max()) + 1
            else:
                y = int(py + self.rng.integers(-3, 4))
            if (self._free(x, y, z) and B.SOLID[self.blocks[x, y - 1, z]]
                    and not B.LIQUID[self.blocks[x, y, z]]
                    and surface == self._sky(x, y, z) and not self._mob_at(x, y, z)):
                self.mobs.append(Mob(x, y, z))
                return

    # ------------------------------------------------------------ perception
    def local_cube(self):
        """What Xen fully senses: everything within NEAR blocks."""
        x, y, z = self.pos
        return self.blocks[x - NEAR:x + NEAR + 1, y - NEAR:y + NEAR + 1, z - NEAR:z + NEAR + 1]

    def _lookup(self, cells):
        cells = np.asarray(cells)
        shape = np.array(self.blocks.shape)
        inside = np.all((cells >= 0) & (cells < shape), axis=1)
        out = np.full(len(cells), -1, np.int64)
        c = cells[inside]
        out[inside] = self.blocks[c[:, 0], c[:, 1], c[:, 2]]
        return out

    def eye(self):
        x, y, z = self.pos
        return np.array([x + 0.5, y + EYE, z + 0.5])

    def sight(self):
        """What Xen's senses deliver right now: near cube, what its eyes see, the mobs it notices."""
        x, y, z = self.pos
        eye = self.eye()
        dist, cat, hit = cast_rays_grid(self.blocks, self.pos, self.yaw, self.pitch, phase=int(self.t))
        near_mobs, far_mobs = [], []
        for m in self.mobs:
            rel = (m.x - x, m.y - y, m.z - z)
            if max(abs(v) for v in rel) <= NEAR and np.linalg.norm(rel) <= NEAR:
                near_mobs.append(rel)
            else:
                head = np.array([m.x + 0.5, m.y + 1.5, m.z + 0.5])
                if in_view(eye, self.yaw, self.pitch, head) and line_of_sight(self._lookup, eye, head):
                    far_mobs.append((m.x, m.y, m.z))
        return Sight(near=self.local_cube(), yaw=self.yaw, body=self.body(), position=(x, y, z), t=self.t,
                     near_mobs=near_mobs, ray_dist=dist, ray_cat=cat, ray_hit=hit, far_mobs=far_mobs)

    def body(self):
        x, y, z = self.pos
        b = self.blocks
        placeable = sum(self.inventory[item] for item in B.PLACEABLE)
        return Body(health=self.health, hunger=self.hunger, night=self.night,
                    burning=self.burning > 0, hurt=self.hurt, pitch=self.pitch,
                    blocks=placeable, food=self.inventory["food"],
                    in_water=b[x, y, z] == B.WATER, in_lava=b[x, y, z] == B.LAVA)

    def observe(self):
        return self.senses.perceive(self.sight())

    # ------------------------------------------------------------- rendering
    GLYPHS = {B.AIR: " ", B.GRASS: '"', B.DIRT: ".", B.STONE: "#", B.LOG: "T", B.LEAVES: "*",
              B.COAL: "c", B.IRON: "i", B.GOLD: "g", B.DIAMOND: "D", B.LAVA: "L",
              B.WATER: "~", B.BEDROCK: "="}

    def render(self):
        """Side view along the facing direction plus a top-down map."""
        x, y, z = self.pos
        (fx, fz), (rx, rz) = facing(self.yaw)
        side = []
        for dy in range(4, -6, -1):
            row = ""
            for ahead in range(-4, 10):
                cx, cy, cz = x + ahead * fx, y + dy, z + ahead * fz
                row += self._glyph(cx, cy, cz)
            side.append(row)
        top = []
        for ahead in range(6, -4, -1):
            row = ""
            for lat in range(-6, 7):
                cx, cz = x + lat * rx + ahead * fx, z + lat * rz + ahead * fz
                row += self._glyph(cx, y, cz, top=True)
            top.append(row)
        width = max(len(r) for r in side)
        lines = [f"{'side view (facing ->)':<{width}}   top view (facing ^)"]
        for i in range(max(len(side), len(top))):
            left = side[i] if i < len(side) else ""
            right = top[i] if i < len(top) else ""
            lines.append(f"{left:<{width}}   {right}")
        return "\n".join(lines)

    def _glyph(self, x, y, z, top=False):
        px, py, pz = self.pos
        if x == px and z == pz and (y == py or (not top and y == py + 1)):
            return "@" if y == py else "o"
        for m in self.mobs:
            if m.x == x and m.z == z and (m.y == y or m.y + 1 == y or (top and abs(m.y - y) <= 1)):
                return "Z"
        if not (0 <= x < self.blocks.shape[0] and 0 <= y < self.blocks.shape[1] and 0 <= z < self.blocks.shape[2]):
            return "="
        block = self.blocks[x, y, z]
        if top and block == B.AIR:
            below = self.blocks[x, y - 1, z]
            return "_" if B.SOLID[below] else ("L" if below == B.LAVA else " ")
        return self.GLYPHS[int(block)]
