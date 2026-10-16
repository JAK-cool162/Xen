"""Reading a recorder session (xen_recordings/session_.../): its log, your screen pictures and Xen's pictures.

    python -m xen.vision.recordings summary xen_recordings/session_*      what's in them: time, pictures, events, notes, head turns
"""
import base64
import gzip
import json
import os
import struct
import sys
import zlib
from collections import Counter

import numpy as np

W, H = 64, 36


def read_png(path):
    """An RGB PNG as an (h, w, 3) uint8 array (the recorder writes them; plain zlib, so no image library is needed)."""
    with open(path, 'rb') as f:
        data = f.read()
    if data[:8] != b'\x89PNG\r\n\x1a\n':
        raise ValueError(f'{path}: not a PNG')
    pos, idat = 8, b''
    w = h = None
    while pos < len(data):
        n, kind = struct.unpack('>I4s', data[pos:pos + 8])
        body = data[pos + 8:pos + 8 + n]
        pos += 12 + n
        if kind == b'IHDR':
            w, h, depth, colour = struct.unpack('>IIBB', body[:10])
            if depth != 8 or colour != 2:
                raise ValueError(f'{path}: only 8-bit RGB')
        elif kind == b'IDAT':
            idat += body
        elif kind == b'IEND':
            break
    raw = np.frombuffer(zlib.decompress(idat), np.uint8).reshape(h, 1 + w * 3)
    out = np.zeros((h, w * 3), np.int32)
    for y in range(h):                                 # PNG row filters (ours are all 0; the rest for other writers)
        f, row = raw[y, 0], raw[y, 1:].astype(np.int32)
        prev = out[y - 1] if y else np.zeros(w * 3, np.int32)
        if f == 0:
            out[y] = row
        elif f == 1:
            r = row.copy()
            for i in range(3, w * 3):
                r[i] = (r[i] + r[i - 3]) & 255
            out[y] = r
        elif f == 2:
            out[y] = (row + prev) & 255
        else:
            r = row.copy()
            for i in range(w * 3):
                a = r[i - 3] if i >= 3 else 0
                b, c = prev[i], prev[i - 3] if i >= 3 else 0
                if f == 3:
                    r[i] = (r[i] + (a + b) // 2) & 255
                else:
                    p = a + b - c
                    pa, pb, pc = abs(p - a), abs(p - b), abs(p - c)
                    r[i] = (r[i] + (a if pa <= pb and pa <= pc else b if pb <= pc else c)) & 255
            out[y] = r
    return out.reshape(h, w, 3).astype(np.uint8)


def unrle(s, n=W * H):
    out = np.zeros(n, np.int32)
    i = 0
    for part in s.split(','):
        v, k = part.split('x')
        out[i:i + int(k)] = int(v)
        i += int(k)
    return out


class Session:
    """One recording: its header, its states (4 a second), its events and the label names."""

    def __init__(self, path):
        self.path = path
        self.header, self.states, self.events, self.labels = {}, [], [], {}
        with gzip.open(os.path.join(path, 'log.jsonl.gz'), 'rt', encoding='utf-8') as f:
            for line in f:
                line = line.strip()
                if not line:
                    continue
                try:
                    d = json.loads(line)
                except json.JSONDecodeError:
                    break                                  # (a session cut off mid-line: what came before is good)
                if 'session' in d:
                    self.header = d
                elif 'labels' in d:
                    self.labels.update({int(k): v for k, v in d['labels'].items()})
                elif 'event' in d:
                    self.events.append(d)
                else:
                    self.states.append(d)

    def pixels(self, state):
        """A state's view: label ids, distances (blocks), light (0-15) and face (0 none, 1 top, 2 bottom, 4 east/west, 5 north/south, 3 creature)."""
        what = unrle(state['what'])
        dist = np.frombuffer(base64.b64decode(state['dist']), np.uint8).astype(np.float32) / 4
        lf = np.frombuffer(base64.b64decode(state['light_face']), np.uint8)
        return what, dist, (lf >> 4).astype(np.int32), (lf & 15).astype(np.int32)

    def framed(self):
        """The states that have pictures: (state, your screen 72x128x3, Xen's picture 36x64x3)."""
        for s in self.states:
            f = s.get('frame', -1)
            if f < 0:
                continue
            real = os.path.join(self.path, 'frames', f'{f:06d}.png')
            xen = os.path.join(self.path, 'xen', f'{f:06d}.png')
            if os.path.exists(real) and os.path.exists(xen):
                yield s, read_png(real), read_png(xen)


def summary(path):
    s = Session(path)
    n = len(s.states)
    secs = (s.states[-1]['t'] - s.states[0]['t']) / 1000 if n > 1 else 0
    frames = sum(1 for x in s.states if x.get('frame', -1) >= 0)
    ev = Counter(e['event'] for e in s.events)
    turns = np.array([abs(x['turn'][0]) + abs(x['turn'][1]) for x in s.states]) if n else np.zeros(0)
    looked = Counter(x.get('looking_at', '') for x in s.states if x.get('looking_at'))
    size = sum(os.path.getsize(os.path.join(r, f)) for r, _, fs in os.walk(path) for f in fs)
    out = [f'{os.path.basename(path)}: {secs / 60:.1f} min, {n} states, {frames} pictures, {size / 1e6:.1f} MB ({size / 1e6 / max(secs / 3600, 1e-9):.0f} MB an hour)',
           '  events: ' + (', '.join(f'{k} {v}' for k, v in ev.most_common()) or 'none'),
           f'  head turns: median {np.median(turns) if n else 0:.1f} deg a quarter second, fast (>20) {int((turns > 20).sum())} times',
           '  looked at most: ' + ', '.join(f'{k} {v}' for k, v in looked.most_common(6))]
    notes = [e['text'] for e in s.events if e['event'] == 'note']
    if notes:
        out.append('  notes: ' + '; '.join(notes[:10]))
    return '\n'.join(out)


def main(argv):
    if len(argv) < 2 or argv[0] != 'summary':
        print(__doc__)
        return 1
    for p in argv[1:]:
        print(summary(p))
    return 0


if __name__ == '__main__':
    sys.exit(main(sys.argv[1:]))
