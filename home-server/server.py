#!/usr/bin/env python3
"""DumbyLuba home server. Python 3 standard library only; SQLite is authoritative."""
import argparse
import copy
from contextlib import contextmanager
import math
import hmac
import hashlib
from http.cookies import SimpleCookie
import json
import os
from pathlib import Path
import re
import secrets
import sqlite3
import threading
import time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

WEB = Path(__file__).parent / "web"
MAX_BODY = 8 * 1024 * 1024


class Store:
    def __init__(self, path, code=None):
        self.path = str(path)
        # An invalid code is refused before the folder or database is created.
        if code is not None and not re.fullmatch(r"[0-9]{6}", code):
            raise ValueError("The code must have six digits")
        Path(path).parent.mkdir(parents=True, exist_ok=True)
        with self.db() as db:
            db.executescript("""
              CREATE TABLE IF NOT EXISTS settings(key TEXT PRIMARY KEY,value TEXT NOT NULL);
              CREATE TABLE IF NOT EXISTS sessions(token TEXT PRIMARY KEY,expires REAL NOT NULL);
              CREATE TABLE IF NOT EXISTS sequences(relay TEXT PRIMARY KEY,seq INTEGER NOT NULL);
              CREATE TABLE IF NOT EXISTS latest(robot TEXT PRIMARY KEY,payload TEXT NOT NULL,received REAL NOT NULL);
              CREATE TABLE IF NOT EXISTS history(id INTEGER PRIMARY KEY,robot TEXT,payload TEXT,received REAL);
            """)
            old = db.execute("SELECT value FROM settings WHERE key='code'").fetchone()
            if old and code and old[0] != code:
                raise ValueError("The code differs from the one stored in the database")
            self.code = old[0] if old else (code or f"{secrets.randbelow(1000000):06d}")
            db.execute("INSERT OR IGNORE INTO settings VALUES('code',?)", (self.code,))
        os.chmod(self.path, 0o600)

    @contextmanager
    def db(self):
        db = sqlite3.connect(self.path, timeout=10)
        try:
            db.execute("PRAGMA journal_mode=WAL")
            db.execute("PRAGMA synchronous=FULL")
            with db:
                yield db
        finally:
            db.close()

    def create_session(self):
        token = secrets.token_urlsafe(32)
        with self.db() as db:
            db.execute("DELETE FROM sessions WHERE expires<=?", (time.time(),))
            db.execute("INSERT INTO sessions VALUES(?,?)", (hashlib.sha256(token.encode()).hexdigest(), time.time()+90*86400))
            db.execute("DELETE FROM sessions WHERE token NOT IN (SELECT token FROM sessions ORDER BY expires DESC LIMIT 1000)")
        return token

    def has_session(self, token):
        if not token or len(token) > 128:
            return False
        with self.db() as db:
            return db.execute("SELECT 1 FROM sessions WHERE token=? AND expires>?", (hashlib.sha256(token.encode()).hexdigest(), time.time())).fetchone() is not None

    def revoke_session(self, token):
        if token:
            with self.db() as db:
                db.execute("DELETE FROM sessions WHERE token=?", (hashlib.sha256(token.encode()).hexdigest(),))

    def ingest(self, value, now=None):
        now = time.time() if now is None else now
        if not isinstance(value, dict) or type(value.get("version")) is not int or value["version"] != 1:
            raise ValueError("Snapshot v1 expected")
        relay, seq = value.get("relayId"), value.get("sequence")
        if not isinstance(relay, str) or not 1 <= len(relay) <= 128 or type(seq) is not int or not 1 <= seq < 2**63:
            raise ValueError("Invalid identity or sequence")
        if not isinstance(value.get("state"), dict) or not isinstance(value.get("map"), dict):
            raise ValueError("State and map required")
        robot = value.get("robotId") or "relay:" + relay
        if not isinstance(robot, str) or len(robot) > 128:
            raise ValueError("Invalid robot")
        if not isinstance(value["map"].get("shapes"), list):
            raise ValueError("Contours required")
        shapes = value["map"]["shapes"]
        def number(v):
            try:
                return type(v) in (int, float) and math.isfinite(v)
            except OverflowError:  # integer too large for a float: invalid (400), not a server error
                return False
        for shape in shapes:
            if not isinstance(shape, dict) or type(shape.get("type")) is not int or not isinstance(shape.get("points"), list):
                raise ValueError("Invalid contour")
            if any(not isinstance(p, list) or len(p) != 2 or not all(number(v) for v in p) for p in shape["points"]):
                raise ValueError("Invalid coordinates")
        base = value["map"].get("base")
        if base is not None and (not isinstance(base, dict) or not all(number(base.get(k)) for k in ("x", "y"))):
            raise ValueError("Invalid base")
        pos = value["state"].get("position")
        if pos is not None and (not isinstance(pos, dict) or any(not number(pos.get(k)) for k in ("x", "y", "at", "ageMs"))):
            raise ValueError("Invalid position")
        captured = value.get("capturedAt")
        if not number(captured) or captured <= 0:
            raise ValueError("Timestamp required")
        payload = copy.deepcopy(value)
        transport_age = max(0, int(now * 1000 - captured))
        if isinstance(payload["state"].get("ageMs"), (int, float)):
            payload["state"]["ageMs"] += transport_age
        if pos:
            payload["state"]["position"]["ageMs"] += transport_age
        with self.db() as db:
            db.execute("BEGIN IMMEDIATE")
            old_seq = db.execute("SELECT seq FROM sequences WHERE relay=?", (relay,)).fetchone()
            if old_seq and seq <= old_seq[0]:
                return False  # Idempotent acknowledgment: never overwrite newer observations.
            old = db.execute("SELECT payload FROM latest WHERE robot=?", (robot,)).fetchone()
            previous = json.loads(old[0]) if old else None
            payload["_positionReceivedAt"] = now
            if previous:
                if not pos and previous["state"].get("position"):
                    payload["state"]["position"] = previous["state"]["position"]
                    payload["_positionReceivedAt"] = previous["_positionReceivedAt"]
                incoming = payload["map"]
                if not incoming["shapes"] or (previous["map"].get("complete") and not incoming.get("complete")):
                    payload["map"] = copy.deepcopy(previous["map"])
                    if incoming.get("base"):
                        payload["map"]["base"] = incoming["base"]
                elif not incoming.get("base"):
                    incoming["base"] = previous["map"].get("base")
            encoded = json.dumps(payload, ensure_ascii=False, allow_nan=False, separators=(",", ":"))
            db.execute("INSERT OR REPLACE INTO sequences VALUES(?,?)", (relay, seq))
            db.execute("INSERT OR REPLACE INTO latest VALUES(?,?,?)", (robot, encoded, now))
            db.execute("INSERT INTO history(robot,payload,received) VALUES(?,?,?)", (robot, encoded, now))
            db.execute("DELETE FROM history WHERE id NOT IN (SELECT id FROM history ORDER BY id DESC LIMIT 1000)")
            db.execute("INSERT OR REPLACE INTO settings VALUES('active_robot',?)", (robot,))
        return True

    def latest(self, now=None):
        now = time.time() if now is None else now
        with self.db() as db:
            row = db.execute("SELECT l.payload,l.received FROM latest l JOIN settings s ON s.key='active_robot' AND s.value=l.robot").fetchone()
        if not row:
            return {"state": {"position": None}, "map": {"shapes": [], "base": None, "source": "No data received", "complete": False}, "relayOnline": False, "receivedAt": None}
        data = json.loads(row[0])
        age = max(0, int((now - row[1]) * 1000))
        data.update(receivedAt=int(row[1] * 1000), relayAgeMs=age, relayOnline=age <= 15000)
        state = data["state"]
        if isinstance(state.get("ageMs"), (int, float)):
            state["ageMs"] += age
        p = state.get("position")
        if p:
            p["ageMs"] = max(0, p["ageMs"]) + max(0, int((now - data["_positionReceivedAt"]) * 1000))
            p["fresh"] = bool(p.get("fresh")) and p["ageMs"] <= 2000 and data["relayOnline"]
        data.pop("_positionReceivedAt", None)
        return data


class Server(ThreadingHTTPServer):
    daemon_threads = True
    def __init__(self, address, store):
        self.store = store
        self.slots = threading.BoundedSemaphore(8)
        self.failures = {}
        self.auth_lock = threading.Lock()
        super().__init__(address, Handler)

    def process_request(self, request, address):
        if not self.slots.acquire(False):
            request.close()
            return
        try:
            super().process_request(request, address)
        except Exception:
            self.slots.release()
            raise

    def process_request_thread(self, request, address):
        try:
            super().process_request_thread(request, address)
        finally:
            self.slots.release()

    def authenticate(self, ip, code):
        now = time.monotonic()
        with self.auth_lock:
            self.failures = {key: [t for t in values if now - t < 60] for key, values in self.failures.items() if values and now - values[-1] < 60}
            if len(self.failures.get(ip, [])) >= 5 or sum(map(len, self.failures.values())) >= 100:
                return 429
            if isinstance(code, str) and hmac.compare_digest(code.encode(), self.store.code.encode()):
                return 200
            self.failures.setdefault(ip, []).append(now)
            return 401


class Handler(BaseHTTPRequestHandler):
    def setup(self):
        super().setup()
        self.connection.settimeout(5)

    def respond(self, code, value, content_type="application/json; charset=utf-8", cookie=None):
        body = value if isinstance(value, bytes) else json.dumps(value, ensure_ascii=False, allow_nan=False).encode()
        self.send_response(code)
        self.send_header("Content-Type", content_type)
        self.send_header("Content-Length", str(len(body)))
        self.send_header("Cache-Control", "no-store")
        self.send_header("X-Content-Type-Options", "nosniff")
        self.send_header("Content-Security-Policy", "default-src 'self'; script-src 'self' 'unsafe-inline'; style-src 'unsafe-inline'; connect-src 'self'; frame-ancestors 'none'")
        self.send_header("Connection", "close")
        if cookie is not None:
            self.send_header("Set-Cookie", cookie)
        self.end_headers()
        self.wfile.write(body)
        self.close_connection = True

    def session_token(self):
        try:
            cookie = SimpleCookie(self.headers.get("Cookie", ""))
            return cookie["luba_session"].value if "luba_session" in cookie else None
        except Exception:
            return None

    def auth(self, allow_session=False):
        if allow_session and self.server.store.has_session(self.session_token()):
            return True
        if allow_session and self.headers.get("X-Relay-Code") is None:
            self.respond(401, {"error": "Sign-in required"})
            return False
        codes = self.headers.get_all("X-Relay-Code", [])
        status = self.server.authenticate(self.client_address[0], codes[0] if len(codes) == 1 else None)
        if status != 200:
            self.respond(status, {"error": "Too many attempts: wait one minute" if status == 429 else "Incorrect code"})
            return False
        return True

    def do_GET(self):
        self.guarded(self.get)

    def do_POST(self):
        self.guarded(self.post)

    def guarded(self, handler):
        # Database unavailable (locked, disk full): answer 503 rather than dropping the connection.
        try:
            handler()
        except sqlite3.Error:
            self.respond(503, {"error": "Storage unavailable"})

    def get(self):
        public = {"/": ("index.html", "text/html; charset=utf-8"), "/app.js": ("app.js", "application/javascript; charset=utf-8"), "/relay-map.js": ("relay-map.js", "application/javascript; charset=utf-8")}
        if self.path in public:
            name, kind = public[self.path]
            self.respond(200, (WEB / name).read_bytes(), kind)
        elif self.path == "/api/latest":
            if self.auth(allow_session=True):
                self.respond(200, self.server.store.latest())
        else:
            self.respond(404, {"error": "Unknown route"})

    def post(self):
        if self.path == "/api/login":
            if self.auth():
                token = self.server.store.create_session()
                self.respond(200, {"connected": True}, cookie=f"luba_session={token}; HttpOnly; SameSite=Strict; Path=/; Max-Age=7776000")
            return
        if self.path == "/api/logout":
            self.server.store.revoke_session(self.session_token())
            self.respond(200, {"connected": False}, cookie="luba_session=; HttpOnly; SameSite=Strict; Path=/; Max-Age=0")
            return
        if self.path != "/api/snapshots":
            self.respond(404, {"error": "Unknown route"})
            return
        if not self.auth():
            return
        try:
            lengths = self.headers.get_all("Content-Length", [])
            if len(lengths) != 1 or self.headers.get("Transfer-Encoding"):
                raise ValueError("Length required, no chunked transfer")
            size = int(lengths[0])
            if size <= 0:
                raise ValueError("Empty snapshot")
            if size > MAX_BODY:
                self.respond(413, {"error": "Snapshot too large"})
                return
            body = self.rfile.read(size)
            if len(body) != size:
                raise ValueError("Incomplete snapshot")
            def invalid_constant(value):
                raise ValueError("Non-finite number")
            value = json.loads(body, parse_constant=invalid_constant)
            changed = self.server.store.ingest(value)
            self.respond(200, {"stored": changed})
        except (ValueError, TypeError, KeyError, RecursionError, UnicodeError):
            self.respond(400, {"error": "Invalid snapshot"})
        except sqlite3.Error:
            self.respond(503, {"error": "Storage unavailable"})

    def log_message(self, format, *args):
        # Do not log headers, PIN or snapshot contents.
        return


def main():
    parser = argparse.ArgumentParser(description="DumbyLuba home server")
    parser.add_argument("--host", default="0.0.0.0")
    parser.add_argument("--port", type=int, default=8765)
    parser.add_argument("--data", default="./data/relay.sqlite3")
    parser.add_argument("--code", help="Six-digit code from the Android app (first launch)")
    args = parser.parse_args()
    os.umask(0o077)
    store = Store(args.data, args.code)
    server = Server((args.host, args.port), store)
    print(f"DumbyLuba: http://{args.host}:{server.server_port}/ — code {store.code}", flush=True)
    try:
        server.serve_forever()
    except KeyboardInterrupt:
        pass
    finally:
        server.server_close()


if __name__ == "__main__":
    main()
