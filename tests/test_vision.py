import base64
import gzip
import json
import os
import struct
import tempfile
import unittest
import zlib

import numpy as np

from xen.vision import tune
from xen.vision.recordings import Session, read_png, summary, W, H


def write_png(path, rgb):
    h, w, _ = rgb.shape
    raw = b''.join(b'\x00' + rgb[y].astype(np.uint8).tobytes() for y in range(h))

    def chunk(kind, data):
        return struct.pack('>I', len(data)) + kind + data + struct.pack('>I', zlib.crc32(kind + data) & 0xffffffff)

    with open(path, 'wb') as f:
        f.write(b'\x89PNG\r\n\x1a\n' + chunk(b'IHDR', struct.pack('>IIBBBBB', w, h, 8, 2, 0, 0, 0)) + chunk(b'IDAT', zlib.compress(raw)) + chunk(b'IEND', b''))


def rle(v):
    out, i = [], 0
    while i < len(v):
        j = i
        while j < len(v) and v[j] == v[i]:
            j += 1
        out.append(f'{v[i]}x{j - i}')
        i = j
    return ','.join(out)


class VisionTuneTest(unittest.TestCase):
    """A made-up recording whose 'real' screen follows known shading: the tuner has to find it again."""

    def make(self, d, frames=12, truth=None):
        rng = np.random.default_rng(3)
        truth = truth or {'bottom': 0.45, 'northSouth': 0.75, 'eastWest': 0.55, 'gamma': 0.8, 'ambient': 0.06}
        base = {0: (125, 125, 125), 1: (127, 178, 56), 2: (151, 109, 77)}
        names = {0: 'stone', 1: 'grass_block', 2: 'dirt'}
        os.makedirs(os.path.join(d, 'frames'))
        os.makedirs(os.path.join(d, 'xen'))
        with gzip.open(os.path.join(d, 'log.jsonl.gz'), 'wt') as f:
            f.write(json.dumps({'session': 'test', 'format': 'xen-recorder 1'}) + '\n')
            f.write(json.dumps({'labels': {str(k): v for k, v in names.items()}}) + '\n')
            f.write(json.dumps({'t': 0, 'event': 'note', 'text': 'going for iron'}) + '\n')
            for n in range(frames):
                what = np.repeat(rng.integers(0, 3, W * H // 8), 8)
                face = rng.choice([1, 2, 4, 5], W * H)
                light = rng.integers(0, 16, W * H)
                dist = rng.uniform(1, 30, W * H)
                shade = np.choose(np.searchsorted([1, 2, 4, 5], face), [1.0, truth['bottom'], truth['eastWest'], truth['northSouth']])
                k = shade * tune.brightness(light, truth['gamma'], truth['ambient'])
                real = np.array([base[int(w)] for w in what], float) * k[:, None]
                shot = np.repeat(np.repeat(real.reshape(H, W, 3), 2, 0), 2, 1)
                write_png(os.path.join(d, 'frames', f'{n:06d}.png'), np.clip(np.round(shot), 0, 255))
                write_png(os.path.join(d, 'xen', f'{n:06d}.png'), np.clip(np.round(real.reshape(H, W, 3) * 0.7), 0, 255))
                st = {'t': n * 500, 'frame': n, 'turn': [1.0, 0.5], 'looking_at': 'stone', 'what': rle(list(what)),
                      'dist': base64.b64encode(np.minimum(255, np.round(dist * 4)).astype(np.uint8).tobytes()).decode(),
                      'light_face': base64.b64encode(((light << 4) | face).astype(np.uint8).tobytes()).decode()}
                f.write(json.dumps(st) + '\n')
        return truth

    def test_png_roundtrip(self):
        with tempfile.TemporaryDirectory() as d:
            img = np.random.default_rng(1).integers(0, 256, (5, 7, 3))
            write_png(os.path.join(d, 'a.png'), img)
            self.assertTrue((read_png(os.path.join(d, 'a.png')) == img).all())

    def test_tuner_finds_the_shading(self):
        with tempfile.TemporaryDirectory() as d:
            truth = self.make(d)
            s = Session(d)
            self.assertEqual(len(list(s.framed())), 12)
            self.assertIn('going for iron', summary(d))
            lab, face, light, real, xen = tune.gather([s])
            p, cols, err, _ = tune.fit(lab, face, light, real)
            for k in ('bottom', 'northSouth', 'eastWest'):
                self.assertAlmostEqual(p[k], truth[k], delta=0.06, msg=k)
            self.assertLess(err, np.abs(xen - real).mean())
            out = os.path.join(d, 'vision.txt')
            self.assertEqual(tune.main([d, '-o', out]), 0)
            text = open(out).read()
            self.assertIn('colour.stone = ', text)
            self.assertIn('northSouth = ', text)


if __name__ == '__main__':
    unittest.main()
