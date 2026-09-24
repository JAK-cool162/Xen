"""Block and item catalogue shared by every world Xen can live in.

Real Minecraft has hundreds of blocks; Xen perceives them through a small set
of categories that matter for survival and progress.
"""
import numpy as np

(AIR, GRASS, DIRT, STONE, LOG, LEAVES, COAL, IRON, GOLD, DIAMOND,
 LAVA, WATER, BEDROCK) = range(13)
NUM_BLOCKS = 13

NAMES = ("air", "grass", "dirt", "stone", "log", "leaves", "coal_ore",
         "iron_ore", "gold_ore", "diamond_ore", "lava", "water", "bedrock")

SOLID = np.array([0, 1, 1, 1, 1, 1, 1, 1, 1, 1, 0, 0, 1], dtype=bool)
LIQUID = np.array([0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 1, 1, 0], dtype=bool)

# Ticks of holding MINE needed to break a block; -1 means it cannot be mined.
HARDNESS = np.array([-1, 2, 2, 4, 3, 1, 5, 6, 6, 8, -1, -1, -1])

# What ends up in the inventory when a block breaks.
DROPS = {GRASS: "dirt", DIRT: "dirt", STONE: "cobblestone", LOG: "log",
         COAL: "coal", IRON: "raw_iron", GOLD: "raw_gold", DIAMOND: "diamond"}

# How satisfying it is to obtain one of each item.
ITEM_VALUE = {"dirt": 0.05, "cobblestone": 0.1, "log": 1.0, "coal": 1.5,
              "raw_iron": 3.0, "raw_gold": 4.0, "diamond": 10.0, "food": 0.3}

# Common items lose their appeal as Xen piles them up (a stack of dirt is plenty);
# ores, logs and food never do.
COMMON = ("dirt", "cobblestone")


def satisfaction(item, carried):
    """How rewarding it is to get one more `item` when already carrying `carried` of it."""
    value = ITEM_VALUE[item]
    return value * 16.0 / (16.0 + carried) if item in COMMON else value


# Items that can be placed back into the world, and the block they become.
PLACEABLE = {"cobblestone": STONE, "dirt": DIRT}

INVENTORY_ITEMS = ("dirt", "cobblestone", "log", "coal", "raw_iron",
                   "raw_gold", "diamond", "food")

VALUE = np.zeros(NUM_BLOCKS, dtype=np.float32)
for _block, _item in DROPS.items():
    VALUE[_block] = ITEM_VALUE[_item]

# Blocks worth walking towards.
TREASURE = VALUE >= 1.0
