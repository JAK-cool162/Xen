"""Xen's action space: the choices it can make every tick.

Every action is a real Minecraft input (keys held, mouse moved, buttons
clicked).  The same actions drive the simulator, the mineflayer bot bridge and
the raw keyboard/mouse backend, so a brain trained in one world can act in
another.
"""
from dataclasses import dataclass
from enum import IntEnum


class Action(IntEnum):
    IDLE = 0
    FORWARD = 1
    BACK = 2
    LEFT = 3
    RIGHT = 4
    JUMP = 5
    TURN_LEFT = 6
    TURN_RIGHT = 7
    LOOK_UP = 8
    LOOK_DOWN = 9
    MINE = 10
    PLACE = 11
    ATTACK = 12
    EAT = 13


NUM_ACTIONS = len(Action)

VERBS = {
    Action.IDLE: "wait",
    Action.FORWARD: "walk forward",
    Action.BACK: "step back",
    Action.LEFT: "strafe left",
    Action.RIGHT: "strafe right",
    Action.JUMP: "jump forward",
    Action.TURN_LEFT: "turn left",
    Action.TURN_RIGHT: "turn right",
    Action.LOOK_UP: "look up",
    Action.LOOK_DOWN: "look down",
    Action.MINE: "mine",
    Action.PLACE: "place a block",
    Action.ATTACK: "attack",
    Action.EAT: "eat",
}


@dataclass(frozen=True)
class KeyPress:
    """The physical inputs behind an action (default Minecraft controls)."""
    keys: tuple = ()          # keyboard keys held together
    button: str = ""          # "left" / "right" mouse button
    hold: float = 0.05        # seconds the keys/button stay down
    turn: tuple = (0.0, 0.0)  # mouse look change in degrees (yaw, pitch)
    slot: str = ""            # hotbar key pressed before the action


KEYMAP = {
    Action.IDLE: KeyPress(hold=0.1),
    Action.FORWARD: KeyPress(keys=("w",), hold=0.25),
    Action.BACK: KeyPress(keys=("s",), hold=0.25),
    Action.LEFT: KeyPress(keys=("a",), hold=0.25),
    Action.RIGHT: KeyPress(keys=("d",), hold=0.25),
    Action.JUMP: KeyPress(keys=("w", "space"), hold=0.35),
    Action.TURN_LEFT: KeyPress(turn=(-90.0, 0.0)),
    Action.TURN_RIGHT: KeyPress(turn=(90.0, 0.0)),
    Action.LOOK_UP: KeyPress(turn=(0.0, -40.0)),
    Action.LOOK_DOWN: KeyPress(turn=(0.0, 40.0)),
    Action.MINE: KeyPress(button="left", hold=1.0),
    Action.PLACE: KeyPress(button="right", hold=0.05),
    Action.ATTACK: KeyPress(button="left", hold=0.05),
    Action.EAT: KeyPress(button="right", hold=1.7, slot="9"),
}
