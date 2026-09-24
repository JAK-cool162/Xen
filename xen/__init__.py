"""Xen: a lightweight decision-making model that learns to play Minecraft.

Xen perceives, feels (pain, fear, curiosity), thinks (imagines outcomes with a
learned world model) and acts through Minecraft controls, learning
continuously from every tick of experience.
"""
from .actions import Action, NUM_ACTIONS
from .brain.agent import Thought, Xen, XenConfig
from .life import live
from .perception import OBS_DIM

__all__ = ["Action", "NUM_ACTIONS", "OBS_DIM", "Thought", "Xen", "XenConfig", "live"]
__version__ = "0.1.0"
