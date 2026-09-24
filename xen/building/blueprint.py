"""Blueprints: a set of Minecraft block states at relative positions.

Coordinates: x = east, y = up, z = south (Minecraft's axes).  Block states are
real Minecraft strings, e.g. "spruce_stairs[facing=east,half=bottom]", so a
blueprint can be pasted into any world with /setblock (see to_mcfunction) or
built block by block by a Xen player.
"""
import re

_STATE = re.compile(r"^([a-z0-9_:]+)(?:\[(.*)\])?$")
_TURN = {"north": "east", "east": "south", "south": "west", "west": "north"}
_MIRROR_X = {"east": "west", "west": "east"}


def parse_state(state):
    """'oak_stairs[facing=east]' -> ('oak_stairs', {'facing': 'east'})."""
    m = _STATE.match(state)
    if not m:
        raise ValueError(f"bad block state: {state!r}")
    props = dict(kv.split("=", 1) for kv in (m.group(2) or "").split(",") if kv)
    return m.group(1).replace("minecraft:", ""), props


def format_state(name, props):
    if not props:
        return name
    return f"{name}[{','.join(f'{k}={v}' for k, v in sorted(props.items()))}]"


def base_name(state):
    return parse_state(state)[0]


class Blueprint:
    def __init__(self, name="build", blocks=None):
        self.name = name
        self.blocks = dict(blocks or {})

    # ------------------------------------------------------------- editing
    def set(self, x, y, z, state):
        self.blocks[(int(x), int(y), int(z))] = state

    def setdefault(self, x, y, z, state):
        self.blocks.setdefault((int(x), int(y), int(z)), state)

    def get(self, x, y, z, default=None):
        return self.blocks.get((x, y, z), default)

    def remove(self, x, y, z):
        self.blocks.pop((x, y, z), None)

    def fill(self, x0, y0, z0, x1, y1, z1, state, hollow=False, replace=True):
        for x in range(min(x0, x1), max(x0, x1) + 1):
            for y in range(min(y0, y1), max(y0, y1) + 1):
                for z in range(min(z0, z1), max(z0, z1) + 1):
                    edge = x in (x0, x1) or y in (y0, y1) or z in (z0, z1)
                    if hollow and not edge:
                        continue
                    if replace:
                        self.set(x, y, z, state)
                    else:
                        self.setdefault(x, y, z, state)

    def merge(self, other, offset=(0, 0, 0), replace=True):
        ox, oy, oz = offset
        for (x, y, z), state in other.blocks.items():
            if replace or (x + ox, y + oy, z + oz) not in self.blocks:
                self.set(x + ox, y + oy, z + oz, state)
        return self

    # ---------------------------------------------------------- geometry
    def bounds(self):
        if not self.blocks:
            return (0, 0, 0), (0, 0, 0)
        xs, ys, zs = zip(*self.blocks)
        return (min(xs), min(ys), min(zs)), (max(xs), max(ys), max(zs))

    @property
    def size(self):
        lo, hi = self.bounds()
        return tuple(h - l + 1 for l, h in zip(lo, hi)) if self.blocks else (0, 0, 0)

    def normalized(self):
        """Shift so the lowest corner is at (0, 0, 0)."""
        (lx, ly, lz), _ = self.bounds()
        return Blueprint(self.name, {(x - lx, y - ly, z - lz): s for (x, y, z), s in self.blocks.items()})

    def rotated(self, turns=1):
        """Rotate clockwise (seen from above) by 90 degrees per turn."""
        bp = self
        for _ in range(turns % 4):
            blocks = {}
            for (x, y, z), state in bp.blocks.items():
                name, props = parse_state(state)
                if props.get("facing") in _TURN:
                    props["facing"] = _TURN[props["facing"]]
                if "axis" in props and props["axis"] in "xz":
                    props["axis"] = "z" if props["axis"] == "x" else "x"
                blocks[(-z, y, x)] = format_state(name, props)
            bp = Blueprint(self.name, blocks)
        return bp.normalized()

    def mirrored(self):
        """Mirror east <-> west."""
        blocks = {}
        for (x, y, z), state in self.blocks.items():
            name, props = parse_state(state)
            if props.get("facing") in _MIRROR_X:
                props["facing"] = _MIRROR_X[props["facing"]]
            if "hinge" in props:
                props["hinge"] = "right" if props["hinge"] == "left" else "left"
            blocks[(-x, y, z)] = format_state(name, props)
        return Blueprint(self.name, blocks).normalized()

    # ------------------------------------------------------------- output
    def to_list(self):
        """[[x, y, z, state]] in build order: bottom up, solid blocks before the
        things that hang on them (torches, levers, wire, doors, lanterns...)."""
        def order(item):
            (x, y, z), state = item
            return (y, is_attachment(state), x, z)
        return [[x, y, z, s] for (x, y, z), s in sorted(self.blocks.items(), key=order)]

    def to_runs(self):
        """Build order as [[x, y, z, state, n]]: n identical blocks in a row along +x."""
        def order(item):
            (x, y, z), state = item
            return (y, is_attachment(state), z, x)
        runs = []
        for (x, y, z), state in sorted(self.blocks.items(), key=order):
            last = runs[-1] if runs else None
            if last and last[1] == y and last[2] == z and last[3] == state and last[0] + last[4] == x:
                last[4] += 1
            else:
                runs.append([x, y, z, state, 1])
        return runs

    def clearance(self, headroom=2):
        """Bounding box to clear before building: (x0, y0, z0, x1, y1, z1)."""
        (x0, y0, z0), (x1, y1, z1) = self.bounds()
        return x0, y0, z0, x1, y1 + headroom, z1

    def to_commands(self, origin=None, clear=True):
        """Commands that build this blueprint, bottom layer first.

        origin None = relative to whoever runs them (~), starting 2 blocks east
        and south so the builder doesn't end up inside the build.
        """
        if origin is None:
            ox, oy, oz = 2, 0, 2
            at = lambda x, y, z: f"~{x + ox} ~{y + oy} ~{z + oz}"
        else:
            at = lambda x, y, z: f"{origin[0] + x} {origin[1] + y} {origin[2] + z}"
        lines = []
        if clear and self.blocks:
            x0, y0, z0, x1, y1, z1 = self.clearance()
            lines.append(f"fill {at(x0, y0, z0)} {at(x1, y1, z1)} air")
        for x, y, z, state, n in self.to_runs():
            if n == 1:
                lines.append(f"setblock {at(x, y, z)} {state}")
            else:
                lines.append(f"fill {at(x, y, z)} {at(x + n - 1, y, z)} {state}")
        return lines

    def to_mcfunction(self, path, origin=None):
        """Write a datapack function: run it in-game with /function <namespace>:<name>."""
        with open(path, "w") as f:
            f.write(f"# {self.name}: {len(self.blocks)} blocks, generated by Xen\n")
            f.write("\n".join(self.to_commands(origin)) + "\n")

    def front_view(self):
        """ASCII elevation from the south (x across, y up), nearest block wins."""
        return self._view(lambda x, y, z: (x, y), depth=lambda x, y, z: -z)

    def side_view(self):
        """ASCII elevation from the east (z across, y up)."""
        return self._view(lambda x, y, z: (z, y), depth=lambda x, y, z: -x)

    def top_view(self):
        """ASCII plan from above (x across, z down), highest block wins."""
        return self._view(lambda x, y, z: (x, z), depth=lambda x, y, z: -y, flip=False)

    def _view(self, project, depth, flip=True):
        if not self.blocks:
            return ""
        best = {}
        for (x, y, z), state in self.blocks.items():
            key = project(x, y, z)
            d = depth(x, y, z)
            if key not in best or d < best[key][0]:
                best[key] = (d, state)
        us, vs = zip(*best)
        rows = []
        for v in (range(max(vs), min(vs) - 1, -1) if flip else range(min(vs), max(vs) + 1)):
            rows.append("".join(glyph(best[(u, v)][1]) if (u, v) in best else " "
                                for u in range(min(us), max(us) + 1)))
        return "\n".join(rows)

    def __len__(self):
        return len(self.blocks)


_ATTACHED = ("torch", "lever", "button", "redstone_wire", "repeater", "comparator", "_door", "lantern",
             "ladder", "_sign", "_carpet", "rail", "flower_pot", "_pane", "_fence", "_trapdoor", "chain")


def is_attachment(state):
    """Blocks that need something to rest on or connect to."""
    name = base_name(state)
    return any(part in name for part in _ATTACHED)


def glyph(state):
    name, props = parse_state(state)
    if name.endswith("_stairs"):
        return {"east": "/", "west": "\\"}.get(props.get("facing"), "^")
    if name.endswith("_slab"):
        return "=" if props.get("type") == "double" else ("-" if props.get("type") == "top" else "_")
    if "glass" in name:
        return "o"
    if name.endswith("_door"):
        return "D"
    if name.endswith("_log") or name.endswith("_wood"):
        return "|"
    if "lantern" in name or "torch" in name:
        return "*"
    if name.endswith("_fence") or name.endswith("_wall"):
        return "+"
    if name == "redstone_wire":
        return "r"
    if "leaves" in name:
        return "%"
    return "#"
