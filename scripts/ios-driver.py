#!/usr/bin/env python3
"""Host side of the iOS remote driver (iosAppUITests/RemoteDriver.swift).

    python3 scripts/ios-driver.py serve            # in one terminal (port 8099)
    python3 scripts/ios-driver.py tree             # commands: tree | tap <label> [index] | tapxy x y | type <text> |
                                                   #   into <field> <text> | swipe up|down | shot out.png | wait s | relaunch | quit
"""
import json, sys, threading, time, uuid, base64, urllib.request
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

PORT = 8099
queue, results, lock = [], {}, threading.Condition()

class H(BaseHTTPRequestHandler):
    def log_message(self, *a): pass
    def _json(self, obj, code=200):
        b = json.dumps(obj).encode(); self.send_response(code); self.send_header("Content-Type", "application/json"); self.send_header("Content-Length", str(len(b))); self.end_headers(); self.wfile.write(b)
    def do_GET(self):
        if self.path == "/next":
            with lock:
                if not queue: lock.wait(timeout=25)
                cmd = queue.pop(0) if queue else {"action": "noop"}
            return self._json(cmd)
        if self.path.startswith("/poll/"):
            rid = self.path[6:]
            with lock:
                if rid not in results: lock.wait(timeout=25)
                r = results.pop(rid, None)
            return self._json(r or {"pending": True})
        self._json({"error": "?"}, 404)
    def do_POST(self):
        n = int(self.headers.get("Content-Length", 0)); body = json.loads(self.rfile.read(n) or b"{}")
        if self.path == "/cmd":
            body["id"] = str(uuid.uuid4())
            with lock: queue.append(body); lock.notify_all()
            return self._json({"id": body["id"]})
        if self.path.startswith("/result/"):
            with lock: results[self.path[8:]] = body; lock.notify_all()
            return self._json({"ok": True})
        self._json({"error": "?"}, 404)

import re
TYPE_MAP = {"Button": "9", "StaticText": "48", "Other": "1", "TextField": "49", "SecureTextField": "50", "Image": "43", "Slider": "52", "Switch": "53"}
def parse_tree(desc):
    """debugDescription → one line per labelled element: type, label, value, [x,y wxh], disabled."""
    out = []
    for line in desc.splitlines():
        m = re.match(r"\s*(\w+), 0x[0-9a-f]+, \{\{([-\d.]+), ([-\d.]+)\}, \{([-\d.]+), ([-\d.]+)\}\}(.*)$", line)
        if not m: continue
        typ, x, y, w, h, rest = m.groups()
        lab = re.search(r"label: '((?:[^'\\]|\\.)*)'", rest); val = re.search(r"value: ([^,]+)", rest)
        label = lab.group(1) if lab else ""; value = val.group(1).strip() if val else ""
        if not label and not value: continue
        out.append("\t".join([TYPE_MAP.get(typ, typ), label, value, f"[{int(float(x))},{int(float(y))} {int(float(w))}x{int(float(h))}]"] + (["disabled"] if "Disabled" in rest else [])))
    return "\n".join(out)

def serve():
    print(f"driver listening on {PORT}"); ThreadingHTTPServer(("127.0.0.1", PORT), H).serve_forever()

def send(cmd, timeout=120):
    r = urllib.request.urlopen(urllib.request.Request(f"http://127.0.0.1:{PORT}/cmd", data=json.dumps(cmd).encode(), headers={"Content-Type": "application/json"})).read()
    rid = json.loads(r)["id"]; end = time.time() + timeout
    while time.time() < end:
        res = json.loads(urllib.request.urlopen(f"http://127.0.0.1:{PORT}/poll/{rid}", timeout=40).read())
        if not res.get("pending"): return res
    return {"ok": False, "error": "timeout"}

if __name__ == "__main__":
    a = sys.argv[1:]
    if not a or a[0] == "serve": serve(); sys.exit()
    op = a[0]
    if op == "tree": cmd = {"action": "tree"}
    elif op == "tap": cmd = {"action": "tap", "label": a[1], "index": int(a[2]) if len(a) > 2 else 0}
    elif op == "tapxy": cmd = {"action": "tap", "x": float(a[1]), "y": float(a[2])}
    elif op == "type": cmd = {"action": "type", "text": " ".join(a[1:])}
    elif op == "into": cmd = {"action": "typeInto", "label": a[1], "text": " ".join(a[2:])}
    elif op == "swipe": cmd = {"action": "swipe", "dir": a[1] if len(a) > 1 else "up"}
    elif op == "drag": cmd = {"action": "drag", "x1": float(a[1]), "y1": float(a[2]), "x2": float(a[3]), "y2": float(a[4])}
    elif op == "system": cmd = {"action": "system", "label": a[1] if len(a) > 1 else "Allow"}
    elif op == "shot": cmd = {"action": "screenshot"}
    elif op == "wait": cmd = {"action": "wait", "seconds": float(a[1])}
    elif op in ("relaunch", "quit"): cmd = {"action": op}
    else: sys.exit("unknown command")
    res = send(cmd)
    if op == "shot" and res.get("png"):
        open(a[1] if len(a) > 1 else "shot.png", "wb").write(base64.b64decode(res.pop("png"))); print("saved")
    if "tree" in res: print(parse_tree(res.pop("tree")))
    print(json.dumps(res))
