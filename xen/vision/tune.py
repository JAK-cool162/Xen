"""Tune Xen's picture to the real game, from recordings: your screen next to Xen's picture of the same view.

    python -m xen.vision.tune xen_recordings/session_* -o config/xen/vision.txt

For every pixel that shows a block face (not the sky, not a creature, not the hotbar or your hand, not far off in the
haze) it has what the real game drew there, what the block is, which face, and how much light. It fits the shading
(how much darker the sides and undersides are than the tops), the light curve (gamma, how dark "no light" is) and a
colour for each block, so that block colour x shading x light comes as close as it can to the real screen. It says
how far off Xen's picture was before and how far off it would be with the fitted ones (on the same pixels: a fit, not
a test), and writes vision.txt for the mod (config/xen/vision.txt, read when the server starts).
"""
import argparse
import sys
from collections import defaultdict

import numpy as np

from .recordings import Session, W, H

FACES = {1: 'top', 2: 'bottom', 4: 'eastWest', 5: 'northSouth'}
DEFAULT = {'top': 1.0, 'bottom': 0.5, 'northSouth': 0.8, 'eastWest': 0.6, 'gamma': 0.5, 'ambient': 0.03}


def brightness(light, gamma, ambient):
    """The mod's light curve (Vision.brightness)."""
    f = np.clip(light, 0, 15) / 15.0
    curve = f / (4 - 3 * f)
    bright = 1 - (1 - curve) ** 4
    return ambient + (1 - ambient) * (curve + (bright - curve) * gamma)


def gather(sessions, far=35.0):
    """The usable pixels: (label, face, light, real rgb, Xen's rgb), from every picture."""
    lab, face, light, real, xen = [], [], [], [], []
    keep = np.zeros((H, W), bool)
    keep[:int(H * 0.8)] = True                         # (not the hotbar, your hand, or the item in it)
    keep[H // 2 - 1:H // 2 + 1, W // 2 - 1:W // 2 + 1] = False   # (nor the crosshair)
    keep = keep.ravel()
    for s in sessions:
        for st, shot, mine in s.framed():
            what, dist, li, fa = s.pixels(st)
            r = shot.reshape(H, 2, W, 2, 3).mean(axis=(1, 3)).reshape(-1, 3)   # (your screen, 128x72 -> 64x36)
            ok = keep & np.isin(fa, list(FACES)) & (dist < far)
            names = np.array([s.labels.get(int(i), '?') for i in what])
            ok &= ~np.char.startswith(names, 'mob:') & (names != 'water')
            lab.append(names[ok]); face.append(fa[ok]); light.append(li[ok]); real.append(r[ok]); xen.append(mine.reshape(-1, 3)[ok])
    if not lab:
        return None
    return np.concatenate(lab), np.concatenate(face), np.concatenate(light), np.concatenate(real).astype(np.float64), np.concatenate(xen).astype(np.float64)


def fit(lab, face, light, real, rounds=4):
    """Shading, light curve and a colour per block, by turns: colours by least squares, the rest by a search."""
    p = dict(DEFAULT)
    labels = np.unique(lab)
    idx = np.searchsorted(labels, lab)

    def k_of(q):
        shade = np.choose(np.searchsorted([1, 2, 4, 5], face), [q['top'], q['bottom'], q['eastWest'], q['northSouth']])
        return shade * brightness(light, q['gamma'], q['ambient'])

    def colours(k):
        num = np.zeros((len(labels), 3))
        den = np.zeros(len(labels))
        np.add.at(num, idx, real * k[:, None])
        np.add.at(den, idx, k * k)
        return num / np.maximum(den[:, None], 1e-9)

    def err(q):
        k = k_of(q)
        base = colours(k)
        return np.abs(base[idx] * k[:, None] - real).mean(), base

    best, base = err(p)
    grids = {'bottom': np.arange(0.3, 1.01, 0.05), 'eastWest': np.arange(0.4, 1.01, 0.05), 'northSouth': np.arange(0.4, 1.01, 0.05),
             'gamma': np.arange(0, 1.01, 0.1), 'ambient': np.arange(0, 0.21, 0.02)}
    for _ in range(rounds):
        for name, grid in grids.items():
            for v in grid:
                q = dict(p, **{name: float(v)})
                e, b = err(q)
                if e < best - 1e-9:
                    best, base, p = e, b, q
    return p, dict(zip(labels, base)), best, np.bincount(idx, minlength=len(labels))


def main(argv=None):
    ap = argparse.ArgumentParser(description=__doc__.split('\n')[0])
    ap.add_argument('sessions', nargs='+')
    ap.add_argument('-o', '--out', default='vision.txt')
    ap.add_argument('--min-pixels', type=int, default=30)
    a = ap.parse_args(argv)
    got = gather([Session(s) for s in a.sessions])
    if got is None or len(got[0]) == 0:
        print('No pictures with block faces in those sessions.')
        return 1
    lab, face, light, real, xen = got
    before = np.abs(xen - real).mean()
    p, cols, after, counts = fit(lab, face, light, real)
    with open(a.out, 'w') as f:
        f.write(f'# Xen vision, tuned from {len(a.sessions)} recording(s), {len(lab)} pixels\n')
        f.write(f'# off by {before:.1f} per channel before (Xen\'s own picture), {after:.1f} with these (on the same pixels)\n')
        for k in ('top', 'bottom', 'northSouth', 'eastWest', 'gamma', 'ambient'):
            f.write(f'{k} = {p[k]:.3f}\n')
        for (name, c), n in zip(sorted(cols.items()), counts):
            if n >= a.min_pixels and name != '?':
                c = np.clip(np.round(c), 0, 255).astype(int)
                f.write(f'colour.{name} = {c[0]:02x}{c[1]:02x}{c[2]:02x}\n')
    print(f'{len(lab)} pixels from {len(a.sessions)} session(s): off by {before:.1f} per channel before, {after:.1f} after; '
          + ', '.join(f'{k} {p[k]:.2f}' for k in ('bottom', 'northSouth', 'eastWest', 'gamma', 'ambient')) + f' -> {a.out}')
    return 0


if __name__ == '__main__':
    sys.exit(main())
