"""Building templates: roofs, walls, arches, houses, towers and bridges.

Roofs are defined as *profiles*: a height line across the span of the roof
(like the classic "roof profiles for Minecraft builders" charts: gable, A-frame,
gambrel, clerestory, mono-pitched, curved, mansard, saltbox, M-shaped, lower
pitch gable, butterfly).  A profile is turned into stairs where it slopes,
slabs where it is nearly flat and full blocks where it is steep, then extruded
along the length of the building, with the gable ends filled in.
"""
import math
from dataclasses import dataclass, replace

from .blueprint import Blueprint


# --------------------------------------------------------------------- palettes
@dataclass(frozen=True)
class Palette:
    name: str
    wall: str
    frame: str            # corner pillars / beams (use a log for contrast)
    base: str             # foundation
    roof: str             # wood/stone type with _stairs and _slab variants
    roof_block: str       # full block matching the roof
    trim: str             # roof edge / gable trim
    window: str = "glass_pane"
    door: str = "oak_door"
    light: str = "lantern"
    fence: str = "oak_fence"
    floor: str = "oak_planks"


PALETTES = {
    "oak": Palette("oak", "oak_planks", "oak_log[axis=y]", "cobblestone", "spruce", "spruce_planks",
                   "stripped_spruce_log[axis=y]", door="spruce_door", fence="spruce_fence"),
    "spruce": Palette("spruce", "spruce_planks", "stripped_spruce_log[axis=y]", "stone_bricks", "dark_oak",
                      "dark_oak_planks", "spruce_planks", door="dark_oak_door", fence="dark_oak_fence"),
    "birch": Palette("birch", "birch_planks", "birch_log[axis=y]", "stone_bricks", "oak", "oak_planks",
                     "oak_planks", door="birch_door", fence="birch_fence", floor="birch_planks"),
    "stone": Palette("stone", "stone_bricks", "polished_andesite", "cobblestone", "deepslate_tile",
                     "deepslate_tiles", "polished_deepslate", door="dark_oak_door", fence="cobblestone_wall",
                     floor="spruce_planks"),
    "sandstone": Palette("sandstone", "smooth_sandstone", "cut_sandstone", "sandstone", "smooth_sandstone",
                         "smooth_sandstone", "cut_sandstone", door="jungle_door", fence="sandstone_wall",
                         light="torch", floor="birch_planks"),
    "fantasy": Palette("fantasy", "stripped_birch_log[axis=y]", "dark_oak_log[axis=y]", "mossy_cobblestone",
                       "dark_oak", "dark_oak_planks", "crimson_planks", door="dark_oak_door",
                       fence="dark_oak_fence", floor="dark_oak_planks"),
    "medieval": Palette("medieval", "white_terracotta", "dark_oak_log[axis=y]", "cobblestone", "spruce",
                        "spruce_planks", "dark_oak_planks", door="spruce_door", fence="spruce_fence",
                        floor="spruce_planks"),
}


def _stairs(material, facing, half="bottom"):
    return f"{material}_stairs[facing={facing},half={half},shape=straight]"


def _slab(material, kind="bottom"):
    return f"{material}_slab[type={kind}]"


# ------------------------------------------------------------------ roof profiles
def _gable(x, c, pitch=1.0):
    return pitch * (c - abs(x - c))


def profile(style, span):
    """Heights (in blocks, >= 0) of the roof surface across `span` columns."""
    c = (span - 1) / 2.0
    heights = []
    for x in range(span):
        d = c - abs(x - c)                       # distance from the nearest edge
        if style == "gable":
            h = d
        elif style == "gable_low":
            h = 0.5 * d
        elif style == "a_frame":
            h = 2.0 * d
        elif style == "gambrel":
            knee = max(1.0, c / 3.0)
            h = 2.0 * d if d <= knee else 2.0 * knee + 0.6 * (d - knee)
        elif style == "mansard":
            h = min(3.0 * d, 4.0)
        elif style == "curved":
            h = (c + 0.5) * math.sqrt(max(0.0, 1.0 - ((x - c) / (c + 0.5)) ** 2))
        elif style == "saltbox":
            ridge = span * 0.35
            h = x if x <= ridge else ridge - 0.5 * (x - ridge)
        elif style == "mono":
            h = 0.5 * x
        elif style == "butterfly":
            h = 0.5 * abs(x - c)
        elif style == "m_shaped":
            half = span / 2.0
            local = x if x < half else x - half
            h = (half - 1) / 2.0 - abs(local - (half - 1) / 2.0)
        elif style == "clerestory":
            split = int(span * 0.45)
            h = 0.5 * x if x < split else 0.5 * split + 3.0 - 0.35 * (x - split)
        elif style == "flat":
            h = 0.0
        else:
            raise ValueError(f"unknown roof style {style!r}; choose from {', '.join(ROOF_STYLES)}")
        heights.append(max(0.0, h))
    return heights


ROOF_STYLES = ("gable", "gable_low", "a_frame", "gambrel", "mansard", "curved", "saltbox",
               "mono", "butterfly", "m_shaped", "clerestory", "flat")


def _profile_cells(heights):
    """Turn a height line into (x, y, kind) cells: 'full', 'east'/'west' stairs, 'slab'/'slab_top'."""
    span = len(heights)
    tops, cells = [], []
    for x, h in enumerate(heights):
        left = heights[x - 1] if x > 0 else h - (heights[1] - h if span > 1 else 0)
        right = heights[x + 1] if x + 1 < span else h + (h - heights[x - 1] if span > 1 else 0)
        slope = (right - left) / 2.0
        level = int(math.floor(h + 1e-6))
        if abs(slope) < 0.35:
            kind = "slab_top" if h - level >= 0.5 else "slab"
        elif abs(slope) <= 1.5:
            level = int(math.floor(h + 0.5))
            kind = "east" if slope > 0 else "west"
        else:
            kind = "east" if slope > 0 else "west"
        tops.append(level)
        cells.append((x, level, kind))
    # Fill steep drops so the roof has no holes.
    for x, top in enumerate(tops):
        neighbours = [tops[i] for i in (x - 1, x + 1) if 0 <= i < span]
        for y in range(min(neighbours + [top]) + 1, top):
            cells.append((x, y, "full"))
    return cells


def roof(style, width, length, palette, overhang=1):
    """A roof covering a `width` x `length` footprint; ridge runs along z.

    Returns (blueprint, underside) where underside[x] is the lowest roof block
    height over column x (used to close the gable ends).
    """
    span = width + 2 * overhang
    cells = _profile_cells(profile(style, span))
    bp = Blueprint(f"{style} roof")
    for x, y, kind in cells:
        if kind == "full":
            state = palette.roof_block
        elif kind in ("east", "west"):
            state = _stairs(palette.roof, kind)
        else:
            state = _slab(palette.roof, "top" if kind == "slab_top" else "bottom")
        for z in range(-overhang, length + overhang):
            bp.set(x - overhang, y, z, state)
        if style != "flat":
            # Contrasting trim along the gable edges, like the charts' "edge example".
            for z in (-overhang, length + overhang - 1):
                if kind == "full":
                    bp.set(x - overhang, y, z, palette.trim)
    if style == "flat":
        for x in range(-overhang, width + overhang):
            for z in (-overhang, length + overhang - 1):
                bp.set(x, 1, z, palette.fence)
        for z in range(-overhang, length + overhang):
            for x in (-overhang, width + overhang - 1):
                bp.set(x, 1, z, palette.fence)
    lowest = {}
    for x, y, kind in cells:
        lowest[x - overhang] = min(lowest.get(x - overhang, y), y)
    return bp, lowest


# ----------------------------------------------------------------------- walls
def walls(width, length, height, palette, window_spacing=3, door=True):
    bp = Blueprint("walls")
    for y in range(height):
        for x in range(width):
            for z in range(length):
                if x in (0, width - 1) or z in (0, length - 1):
                    corner = x in (0, width - 1) and z in (0, length - 1)
                    bp.set(x, y, z, palette.frame if corner else palette.wall)
    # Beams along the top of the walls.
    beam = palette.frame.replace("[axis=y]", "")
    for x in range(width):
        for z in (0, length - 1):
            bp.set(x, height - 1, z, beam + ("[axis=x]" if beam.endswith("_log") else ""))
    for z in range(length):
        for x in (0, width - 1):
            bp.set(x, height - 1, z, beam + ("[axis=z]" if beam.endswith("_log") else ""))
    # Windows.
    if window_spacing and height >= 3:
        for y in range(1, min(3, height - 1)):
            for x in range(2, width - 2, window_spacing):
                for z in (0, length - 1):
                    bp.set(x, y, z, palette.window)
            for z in range(2, length - 2, window_spacing):
                for x in (0, width - 1):
                    bp.set(x, y, z, palette.window)
    # Door in the middle of the south wall, lanterns beside it.
    if door:
        dx, dz = width // 2, length - 1
        bp.set(dx, 0, dz, f"{palette.door}[facing=north,half=lower,hinge=left,open=false]")
        bp.set(dx, 1, dz, f"{palette.door}[facing=north,half=upper,hinge=left,open=false]")
        for side in (dx - 1, dx + 1):
            bp.set(side, 2, dz + 1, "wall_torch[facing=south]")
    return bp


# ----------------------------------------------------------------------- houses
@dataclass(frozen=True)
class HouseSpec:
    width: int = 9
    length: int = 7
    wall_height: int = 4
    roof: str = "gable"
    palette: str = "oak"
    overhang: int = 1
    window_spacing: int = 3
    porch: bool = False


def house(spec=None, **kwargs):
    spec = replace(spec or HouseSpec(), **kwargs)
    p = PALETTES[spec.palette]
    w, l, h = max(5, spec.width), max(5, spec.length), max(3, spec.wall_height)
    bp = Blueprint(f"{spec.palette} {spec.roof} house")
    bp.fill(0, 0, 0, w - 1, 0, l - 1, p.base)                           # foundation
    bp.fill(1, 0, 1, w - 2, 0, l - 2, p.floor)
    bp.merge(walls(w, l, h, p, spec.window_spacing), (0, 1, 0))
    top, lowest = roof(spec.roof, w, l, p, spec.overhang)
    flat = spec.roof == "flat"
    base_y = h + 1 if flat else h            # the eaves sit on the top beam
    bp.merge(top, (0, base_y + (1 if flat else 0), 0), replace=True)
    if flat:
        bp.fill(0, base_y, 0, w - 1, base_y, l - 1, p.roof_block)
    # Wall in everything up to the roof line: gable ends and high eaves.
    for x in range(w):
        underside = base_y + lowest.get(x, 0)
        for z in range(l):
            if x in (0, w - 1) or z in (0, l - 1):
                for y in range(h + 1, underside):
                    bp.setdefault(x, y, z, p.wall)
    mid = w // 2
    if lowest.get(mid, 0) + base_y - (h + 1) >= 3:
        for z in (0, l - 1):
            bp.set(mid, h + 2, z, p.window)                               # gable window
    # Light inside so mobs don't spawn.
    bp.set(w // 2, h, l // 2, "lantern[hanging=true]")
    if spec.porch:
        for x in range(1, w - 1):
            bp.setdefault(x, 0, l, p.floor)
            bp.setdefault(x, 0, l + 1, p.floor)
        for x in (1, w - 2):
            for y in range(1, 3):
                bp.set(x, y, l + 1, p.fence)
            bp.set(x, 3, l + 1, p.trim if not p.trim.endswith("]") else p.roof_block)
        for x in range(0, w):
            bp.set(x, 4 if h >= 4 else h, l + 1, _slab(p.roof))
    return bp


def tower(radius=3, height=10, palette="stone", roof_height=None):
    """A round tower with a pointed (cone) roof."""
    p = PALETTES[palette]
    bp = Blueprint(f"{palette} tower")
    r = radius
    for y in range(height):
        for x in range(-r, r + 1):
            for z in range(-r, r + 1):
                d = math.hypot(x, z)
                if y == 0 and d <= r + 0.5:
                    bp.set(x, y, z, p.base)
                elif r - 0.5 <= d <= r + 0.5:
                    window = y % 4 == 2 and (x == 0 or z == 0)
                    bp.set(x, y, z, p.window if window else p.wall)
    bp.set(0, 1, r, f"{p.door}[facing=north,half=lower,hinge=left,open=false]")
    bp.set(0, 2, r, f"{p.door}[facing=north,half=upper,hinge=left,open=false]")
    bp.set(0, 3, r + 1, "wall_torch[facing=south]")
    bp.set(0, height - 2, 0, "lantern[hanging=true]")
    bp.fill(-r + 1, height - 1, -r + 1, r - 1, height - 1, r - 1, p.floor)   # ceiling / top floor
    peak = roof_height or 2 * r + 2
    for level in range(peak):
        rr = (r + 1) * (1 - level / peak)
        for x in range(-r - 1, r + 2):
            for z in range(-r - 1, r + 2):
                d = math.hypot(x, z)
                if d > rr + 0.2:
                    continue
                outer = d > rr - 0.8
                if outer and (x or z):
                    # Stairs on the surface, their tall side towards the middle.
                    facing = ("west" if x > 0 else "east") if abs(x) >= abs(z) else ("north" if z > 0 else "south")
                    bp.set(x, height + level, z, _stairs(p.roof, facing))
                else:
                    bp.set(x, height + level, z, p.roof_block)
    bp.set(0, height + peak, 0, p.fence)
    return bp


# ----------------------------------------------------------------------- arches
ARCH_STYLES = ("round", "pointed", "segmental", "flat", "horseshoe", "tudor")


def arch_opening(style, width, height):
    """Height of the opening at each column (the underside of the arch)."""
    c = (width - 1) / 2.0
    s = c + 0.5                                   # half span
    out = []
    for x in range(width):
        a = abs(x - c)
        u = a / s                                 # 0 centre .. <1 edge
        if style == "round":
            h = height - 1 + (c + 0.5) * (math.sqrt(max(0.0, 1 - u * u)) - 1)
        elif style == "pointed":
            # Gothic: two arcs of radius 1.5 * half-span meeting at a point.
            r = 1.5 * s
            peak = math.sqrt(r * r - (r - s) ** 2)
            h = height - 1 - peak + math.sqrt(max(0.0, r * r - (a + r - s) ** 2))
        elif style == "segmental":
            h = height - 1 - 1.5 * u * u
        elif style == "flat":
            h = height - 1
        elif style == "horseshoe":
            h = height - 1 - (c + 0.5) * (1 - math.sqrt(max(0.0, 1 - (u * 0.95) ** 2)))
            h = h if u < 0.85 else h - 1.5
        elif style == "tudor":
            h = height - 1 - (2.0 * u if u > 0.6 else 0.5 * u)
        else:
            raise ValueError(f"unknown arch style {style!r}; choose from {', '.join(ARCH_STYLES)}")
        out.append(max(1, int(math.floor(h + 0.5))))
    if style == "pointed" and width % 2 and width > 2:
        out[width // 2] = max(out) + 1            # sharpen the point
    return out


def arch(style="round", width=5, height=5, depth=1, material="stone_bricks", thickness=1, solid=True):
    """An archway / hallway section with pillars; the passage runs along z.

    solid=True fills above the curve up to a flat top (hallway / bridge arches);
    otherwise only a ring `thickness` blocks thick is built.
    """
    bp = Blueprint(f"{style} arch")
    opening = arch_opening(style, width, height)
    top = max(opening) + thickness - 1
    for x in range(-1, width + 1):
        inside = 0 <= x < width
        low = opening[x] if inside else 0
        high = top if (solid or not inside) else opening[x] + thickness - 1
        for y in range(low, high + 1):
            for z in range(depth):
                bp.set(x, y, z, material)
    return bp


def bridge(length=15, width=3, height=5, palette="stone", arch_style="round"):
    """A bridge with arches underneath and railings on top; runs along x."""
    p = PALETTES[palette]
    bp = Blueprint(f"{palette} {arch_style} bridge")
    span = 5
    deck = 0
    for start in range(0, length, span + 1):
        piece = arch(arch_style, span, height, depth=width, material=p.base)
        deck = piece.bounds()[1][1] + 1
        bp.merge(piece, (start, 0, 0), replace=False)
    end = bp.bounds()[1][0]
    for x in range(-1, end + 1):
        for z in range(width):
            bp.set(x, deck, z, p.floor)
        for z in (-1, width):
            bp.set(x, deck, z, p.wall)
            bp.set(x, deck + 1, z, p.fence)
    for x in (-1, end):
        for z in (-1, width):
            bp.set(x, deck + 2, z, "lantern[hanging=false]")
    return bp


TEMPLATES = {
    "house": lambda **kw: house(**kw),
    "tower": lambda **kw: tower(**{k: v for k, v in kw.items() if k in ("radius", "height", "palette")}),
    "arch": lambda **kw: arch(**{k: v for k, v in kw.items() if k in ("style", "width", "height", "depth", "material")}),
    "bridge": lambda **kw: bridge(**{k: v for k, v in kw.items() if k in ("length", "width", "height", "palette", "arch_style")}),
}


def roof_showcase(style, palette="oak", width=9):
    """Just a roof on a wall stub, handy to compare profiles side by side."""
    return house(HouseSpec(width=width, length=5, wall_height=3, roof=style, palette=palette,
                           window_spacing=0))
