#!/usr/bin/env python3
"""Xen live view: watch what each Xen sees, with how it is (health, gear, goal, what it's doing, its journal).

The mod writes each Xen's view (a picture drawn on the server from the blocks and mobs in front of its eyes) and its
status once a second to config/xen/live, when that folder exists. This serves a page that shows them.

    mkdir <server>/config/xen/live                         # turn it on (delete the folder to turn it off)
    python3 serve.py <server>/config/xen/live              # then open http://localhost:8765
    python3 serve.py <server>/config/xen/live 8765 0.0.0.0 # to open it from other machines on your network

No packages needed (standard library only).
"""
import http.server
import json
import os
import sys

if len(sys.argv) < 2:
    sys.exit(__doc__)
LIVE = os.path.abspath(sys.argv[1])
PORT = int(sys.argv[2]) if len(sys.argv) > 2 else 8765
HOST = sys.argv[3] if len(sys.argv) > 3 else '127.0.0.1'
PAGE = os.path.join(os.path.dirname(os.path.abspath(__file__)), 'index.html')


class Handler(http.server.BaseHTTPRequestHandler):
    def log_message(self, *args):
        pass

    def send(self, code, body, ctype):
        self.send_response(code)
        self.send_header('Content-Type', ctype)
        self.send_header('Cache-Control', 'no-store')
        self.send_header('Content-Length', str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def do_GET(self):
        path = self.path.split('?')[0]
        if path in ('/', '/index.html'):
            with open(PAGE, 'rb') as f:
                return self.send(200, f.read(), 'text/html; charset=utf-8')
        if path == '/xens':
            out = {}
            if os.path.isdir(LIVE):
                for f in sorted(os.listdir(LIVE)):
                    if f.endswith('.json') and not f.startswith('_'):
                        try:
                            with open(os.path.join(LIVE, f), encoding='utf-8') as fh:
                                out[f[:-5]] = json.load(fh)
                        except (OSError, ValueError):
                            pass                                   # (being written right now: next second)
            return self.send(200, json.dumps(out).encode(), 'application/json')
        if path == '/exam':                                        # its eyes' exam (Xen Glance: what it guessed, what was there)
            try:
                with open(os.path.join(LIVE, '_exam.json'), 'rb') as fh:
                    return self.send(200, fh.read(), 'application/json')
            except OSError:
                return self.send(200, b'{}', 'application/json')
        name = os.path.basename(path)                              # (only the folder's own pictures)
        full = os.path.join(LIVE, name)
        if name.endswith('.png') and os.path.isfile(full):
            with open(full, 'rb') as f:
                return self.send(200, f.read(), 'image/png')
        self.send(404, b'not found', 'text/plain')


if __name__ == '__main__':
    print(f'Xen live view: http://{"localhost" if HOST == "127.0.0.1" else HOST}:{PORT} (reading {LIVE})')
    http.server.ThreadingHTTPServer((HOST, PORT), Handler).serve_forever()
