"""Real Minecraft on your screen, played with real key presses and mouse moves.

Xen sees a downscaled grayscale picture of the screen and feels pain from the
health bar (red hearts disappearing).  It needs no mods, bots or servers: it
presses W/A/S/D/space, moves the mouse and clicks exactly like a person.

Requirements: `pip install mss pynput`, Minecraft in windowed/fullscreen on the
primary monitor, default key bindings, food in hotbar slot 9, and Raw Input
off (Options > Controls > Mouse Settings) so synthetic mouse moves register.
Calibrate `ScreenConfig` if your GUI scale or resolution make the health bar
land elsewhere.
"""
import time
from dataclasses import dataclass

import numpy as np

from ..actions import KEYMAP, NUM_ACTIONS, Action

FRAME = (18, 32)                # rows, cols of Xen's view
OBS_DIM = FRAME[0] * FRAME[1] + 2


@dataclass
class ScreenConfig:
    # Health bar box as fractions of the screen (left, top, right, bottom); GUI scale "auto" at 16:9.
    health_box: tuple = (0.33, 0.855, 0.49, 0.885)
    hotbar_box: tuple = (0.33, 0.9, 0.67, 0.995)
    pixels_per_degree: float = 6.67     # mouse pixels per degree (sensitivity 100%)
    tool_slot: str = "1"
    respawn_click: tuple = (0.5, 0.52)  # "Respawn" button, fractions of the screen


def _crop(frame, box):
    h, w = frame.shape[:2]
    l, t, r, b = box
    return frame[int(t * h):int(b * h), int(l * w):int(r * w)]


def downscale(gray, shape=FRAME):
    """Average-pool a grayscale image to `shape`."""
    rows, cols = shape
    h, w = gray.shape
    gray = gray[: h - h % rows, : w - w % cols]
    return gray.reshape(rows, gray.shape[0] // rows, cols, gray.shape[1] // cols).mean((1, 3))


def red_fraction(rgb):
    """Share of pixels that look like a full red heart."""
    r, g, b = rgb[..., 0].astype(int), rgb[..., 1].astype(int), rgb[..., 2].astype(int)
    return float(((r > 150) & (g < 70) & (b < 70)).mean()) if rgb.size else 0.0


class MssCapture:
    def __init__(self):
        import mss
        self._mss = mss.mss()
        self.monitor = self._mss.monitors[1]

    def grab(self):
        shot = np.asarray(self._mss.grab(self.monitor))     # BGRA
        return shot[..., 2::-1]                              # -> RGB


class PynputController:
    def __init__(self, config):
        from pynput import keyboard, mouse
        self._keyboard = keyboard.Controller()
        self._mouse = mouse.Controller()
        self._keys = {"space": keyboard.Key.space, "shift": keyboard.Key.shift}
        self._buttons = {"left": mouse.Button.left, "right": mouse.Button.right}
        self.config = config

    def _key(self, name):
        return self._keys.get(name, name)

    def perform(self, action):
        press = KEYMAP[Action(action)]
        if press.slot:
            self.tap(press.slot)
        if press.turn != (0.0, 0.0):
            steps = 10
            dx, dy = (int(round(v * self.config.pixels_per_degree / steps)) for v in press.turn)
            for _ in range(steps):                      # smooth, like a hand
                self._mouse.move(dx, dy)
                time.sleep(0.01)
        for key in press.keys:
            self._keyboard.press(self._key(key))
        if press.button:
            self._mouse.press(self._buttons[press.button])
        time.sleep(press.hold)
        if press.button:
            self._mouse.release(self._buttons[press.button])
        for key in press.keys:
            self._keyboard.release(self._key(key))
        if press.slot:
            self.tap(self.config.tool_slot)

    def tap(self, key):
        self._keyboard.press(self._key(key))
        self._keyboard.release(self._key(key))

    def click(self, x, y):
        self._mouse.position = (x, y)
        self._mouse.click(self._buttons["left"])

    def release_all(self):
        for key in ("w", "a", "s", "d", "space"):
            self._keyboard.release(self._key(key))
        for button in self._buttons.values():
            self._mouse.release(button)


class ScreenWorld:
    obs_dim = OBS_DIM
    n_actions = NUM_ACTIONS

    def __init__(self, config=None, capture=None, controller=None, countdown=5):
        self.config = config or ScreenConfig()
        self.capture = capture or MssCapture()
        self.controller = controller or PynputController(self.config)
        self.countdown = countdown
        self._full_hearts = None
        self._last = None
        self.t = 0

    def _look(self):
        rgb = self.capture.grab()
        hearts = red_fraction(_crop(rgb, self.config.health_box))
        if self._full_hearts is None or hearts > self._full_hearts:
            self._full_hearts = max(hearts, 1e-3)
        health = min(1.0, hearts / self._full_hearts)
        gray = rgb.mean(-1) / 255.0
        hotbar = downscale(_crop(gray, self.config.hotbar_box), (4, 36))
        # "You died" screen: the whole view is washed in red.
        dead = rgb[..., 0].mean() > 1.6 * rgb[..., 1].mean() + 20 and health < 0.05
        return downscale(gray), health, hotbar, dead

    def _obs(self, view, health, hurt):
        return np.concatenate([view.ravel(), [health, hurt]]).astype(np.float32)

    def reset(self):
        if self.countdown and self.t == 0:
            for i in range(self.countdown, 0, -1):
                print(f"Focus the Minecraft window... {i}", flush=True)
                time.sleep(1)
        view, health, hotbar, dead = self._look()
        if dead:
            self._respawn()
            view, health, hotbar, _ = self._look()
        self._last = (health, hotbar)
        self.t = max(self.t, 1)
        return self._obs(view, health, 0.0)

    def _respawn(self):
        rgb = self.capture.grab()
        h, w = rgb.shape[:2]
        x, y = self.config.respawn_click
        self.controller.click(int(x * w), int(y * h))
        time.sleep(2.0)

    def step(self, action):
        self.controller.perform(action)
        view, health, hotbar, dead = self._look()
        prev_health, prev_hotbar = self._last
        self.t += 1
        lost = max(0.0, prev_health - health)
        harm = lost + (1.0 if dead else 0.0)
        reward, events = 0.0, []
        # Something new in the hotbar after mining or attacking: we picked it up.
        if Action(action) in (Action.MINE, Action.ATTACK) and np.abs(hotbar - prev_hotbar).mean() > 0.02:
            reward += 0.5
            events.append("picked something up")
        if lost > 0.02:
            events.append(f"lost {lost * 20:.0f} health")
        if dead:
            events.append("died")
        self._last = (health, hotbar)
        info = {"events": events, "terminal": dead, "health": round(health * 20), "t": self.t}
        return self._obs(view, health, min(1.0, lost * 4)), reward, harm, dead, info

    def close(self):
        self.controller.release_all()
