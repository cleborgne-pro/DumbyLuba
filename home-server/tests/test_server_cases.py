"""Additional home server use cases: validation, HTTP security, web page, startup.

Standard library only; servers on an ephemeral port (0) of 127.0.0.1; data in tempfile.
"""
import contextlib
import http.client
import io
import json
import os
from pathlib import Path
import re
import signal
import socket
import socketserver
import sqlite3
import stat
import subprocess
import sys
import tempfile
import threading
import time
import unittest
from unittest import mock

from test_server import server, snapshot

CODE = "001234"
WEB = Path(server.__file__).parent / "web"


class StoreCodeTest(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        self.path = Path(self.tmp.name) / "sub" / "relay.sqlite3"

    def test_generated_code_has_six_digits_and_is_kept(self):
        store = server.Store(self.path)
        self.assertRegex(store.code, r"^[0-9]{6}$")
        self.assertEqual(server.Store(self.path).code, store.code)
        self.assertEqual(server.Store(self.path, store.code).code, store.code)

    def test_leading_zero_code_and_private_file(self):
        store = server.Store(self.path, "000042")
        self.assertEqual(server.Store(self.path).code, "000042")
        self.assertEqual(stat.S_IMODE(os.stat(store.path).st_mode), 0o600)

    def test_malformed_codes_are_refused(self):
        for code in ("", "12345", "1234567", "abcdef", "12 345", "١٢٣٤٥٦", "123456\n"):
            with self.subTest(code=code), self.assertRaises(ValueError):
                server.Store(self.path, code)
        # No folder or empty database left behind by a refused code.
        self.assertFalse(self.path.parent.exists())


class StoreIngestTest(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        self.store = server.Store(Path(self.tmp.name) / "relay.sqlite3", CODE)

    def assertRefused(self, value):
        with self.assertRaises(ValueError):
            self.store.ingest(value, now=1000)
        with self.store.db() as db:
            self.assertEqual(db.execute("SELECT count(*) FROM history").fetchone()[0], 0)

    def test_empty_store_returns_offline_placeholder(self):
        data = self.store.latest()
        self.assertFalse(data["relayOnline"])
        self.assertIsNone(data["receivedAt"])
        self.assertIsNone(data["state"]["position"])
        self.assertEqual(data["map"]["shapes"], [])

    def test_invalid_snapshots_are_refused(self):
        def change(**edits):
            value = snapshot()
            for path, new in edits.items():
                target = value
                keys = path.split("__")
                for key in keys[:-1]:
                    target = target[key]
                if new is KeyError:
                    del target[keys[-1]]
                else:
                    target[keys[-1]] = new
            return value
        cases = {
            "list": [snapshot()],
            "version 2": change(version=2),
            "missing version": change(version=KeyError),
            "text version": change(version="1"),
            "boolean version": change(version=True),
            "float version": change(version=1.0),
            "empty relay": change(relayId=""),
            "relay too long": change(relayId="r" * 129),
            "numeric relay": change(relayId=7),
            "zero sequence": change(sequence=0),
            "negative sequence": change(sequence=-1),
            "boolean sequence": change(sequence=True),
            "float sequence": change(sequence=1.0),
            "sequence 2^63": change(sequence=2**63),
            "missing state": change(state=KeyError),
            "list state": change(state=[]),
            "missing map": change(map=KeyError),
            "numeric robot": change(robotId=5),
            "robot too long": change(robotId="r" * 129),
            "missing contours": change(map__shapes=KeyError),
            "object contours": change(map__shapes={}),
            "text contour": change(map__shapes=["zone"]),
            "text type": change(map__shapes=[{"type": "0", "points": []}]),
            "boolean type": change(map__shapes=[{"type": True, "points": []}]),
            "missing points": change(map__shapes=[{"type": 0}]),
            "three-coordinate point": change(map__shapes=[{"type": 0, "points": [[0, 0, 0]]}]),
            "infinite point": change(map__shapes=[{"type": 0, "points": [[float("inf"), 0]]}]),
            "point NaN": change(map__shapes=[{"type": 0, "points": [[0, float("nan")]]}]),
            "boolean point": change(map__shapes=[{"type": 0, "points": [[True, 0]]}]),
            "list base": change(map__base=[0, 0]),
            "base without y": change(map__base={"x": 0}),
            "position without age": change(state__position={"x": 0, "y": 0, "at": 1}),
            "empty position": change(state__position={}),
            "text position": change(state__position={"x": "0", "y": 0, "at": 1, "ageMs": 0}),
            "missing timestamp": change(capturedAt=KeyError),
            "zero timestamp": change(capturedAt=0),
            "negative timestamp": change(capturedAt=-5),
            "text timestamp": change(capturedAt="1000"),
        }
        for name, value in cases.items():
            with self.subTest(name):
                self.assertRefused(value)

    def test_integer_too_large_for_a_float_is_refused_as_invalid(self):
        # Bug: math.isfinite(10**400) raised OverflowError instead of an "Invalid snapshot" refusal.
        for field in ("capturedAt", "point", "base", "position"):
            value = snapshot()
            huge = 10**400
            if field == "capturedAt":
                value["capturedAt"] = huge
            elif field == "point":
                value["map"]["shapes"][0]["points"][0] = [huge, 0]
            elif field == "base":
                value["map"]["base"]["x"] = huge
            else:
                value["state"]["position"]["at"] = huge
            with self.subTest(field):
                self.assertRefused(value)

    def test_limits_that_are_accepted(self):
        value = snapshot(seq=2**63 - 1, relay="r" * 128, robot="b" * 128)
        value["map"]["shapes"] = [{"type": 2, "points": []}]
        value["map"]["base"] = None
        value["state"]["position"] = None
        self.assertTrue(self.store.ingest(value, now=1000))
        self.assertEqual(self.store.latest(now=1000)["robotId"], "b" * 128)

    def test_missing_robot_id_uses_relay_identity(self):
        value = snapshot(relay="phone-1")
        del value["robotId"]
        self.store.ingest(value, now=1000)
        with self.store.db() as db:
            self.assertEqual(db.execute("SELECT robot FROM latest").fetchone()[0], "relay:phone-1")
            self.assertEqual(db.execute("SELECT value FROM settings WHERE key='active_robot'").fetchone()[0], "relay:phone-1")

    def test_transport_delay_is_added_to_ages_and_future_clock_counts_zero(self):
        self.store.ingest(snapshot(), now=1005)  # captured at 1000 s, received 5 s later
        data = self.store.latest(now=1005)
        self.assertEqual(data["state"]["ageMs"], 5100)
        self.assertEqual(data["state"]["position"]["ageMs"], 5100)
        self.assertNotIn("_positionReceivedAt", data)
        self.store.ingest(snapshot(2), now=990)  # phone clock ahead
        self.assertEqual(self.store.latest(now=990)["state"]["position"]["ageMs"], 100)

    def test_online_and_fresh_thresholds(self):
        self.store.ingest(snapshot(), now=1000)
        data = self.store.latest(now=1001.5)
        self.assertTrue(data["relayOnline"])
        self.assertTrue(data["state"]["position"]["fresh"])
        data = self.store.latest(now=1002)
        self.assertTrue(data["relayOnline"])
        self.assertFalse(data["state"]["position"]["fresh"])  # position older than 2 s
        self.assertTrue(self.store.latest(now=1015)["relayOnline"])
        self.assertFalse(self.store.latest(now=1016)["relayOnline"])
        self.assertEqual(self.store.latest(now=999)["relayAgeMs"], 0)

    def test_position_marked_old_by_phone_never_becomes_fresh(self):
        value = snapshot()
        value["state"]["position"]["fresh"] = False
        self.store.ingest(value, now=1000)
        self.assertFalse(self.store.latest(now=1000)["state"]["position"]["fresh"])

    def test_map_merge_keeps_new_base_and_inherits_missing_base(self):
        self.store.ingest(snapshot(), now=1000)
        partial = snapshot(2)
        partial["map"] = {"shapes": [], "complete": False, "base": {"x": 5, "y": 6, "source": "BLE"}}
        self.store.ingest(partial, now=1001)
        data = self.store.latest(now=1001)
        self.assertEqual(len(data["map"]["shapes"]), 1)
        self.assertEqual(data["map"]["base"]["x"], 5)
        new_map = snapshot(3)
        new_map["map"] = {"shapes": [{"type": 0, "points": [[1, 1]]}], "complete": True, "base": None}
        self.store.ingest(new_map, now=1002)
        data = self.store.latest(now=1002)
        self.assertEqual(data["map"]["shapes"][0]["points"], [[1, 1]])
        self.assertEqual(data["map"]["base"]["x"], 5)

    def test_partial_map_replaces_previous_partial_map(self):
        first = snapshot()
        first["map"]["complete"] = False
        self.store.ingest(first, now=1000)
        second = snapshot(2)
        second["map"] = {"shapes": [{"type": 1, "points": [[3, 3]]}], "complete": False}
        self.store.ingest(second, now=1001)
        self.assertEqual(self.store.latest(now=1001)["map"]["shapes"][0]["type"], 1)

    def test_sequences_are_independent_per_relay(self):
        self.assertTrue(self.store.ingest(snapshot(5, relay="phone-A"), now=1000))
        self.assertTrue(self.store.ingest(snapshot(1, relay="phone-B"), now=1001))
        self.assertEqual(self.store.latest(now=1001)["relayId"], "phone-B")

    def test_failed_write_rolls_back_without_consuming_sequence(self):
        self.store.ingest(snapshot(), now=1000)
        value = snapshot(2)
        value["state"]["ageMs"] = float("nan")  # refused at JSON encoding, inside the transaction
        with self.assertRaises(ValueError):
            self.store.ingest(value, now=1001)
        self.assertEqual(self.store.latest(now=1001)["sequence"], 1)
        self.assertTrue(self.store.ingest(snapshot(2), now=1002))

    def test_input_is_not_mutated(self):
        value = snapshot()
        before = json.dumps(value, sort_keys=True)
        self.store.ingest(value, now=1010)
        self.assertEqual(json.dumps(value, sort_keys=True), before)


class StoreSessionTest(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        self.store = server.Store(Path(self.tmp.name) / "relay.sqlite3", CODE)

    def test_tokens_are_random_hashed_and_bounded(self):
        a, b = self.store.create_session(), self.store.create_session()
        self.assertNotEqual(a, b)
        self.assertGreaterEqual(len(a), 40)
        with self.store.db() as db:
            stored = {row[0] for row in db.execute("SELECT token FROM sessions")}
        self.assertNotIn(a, stored)
        self.assertEqual(len(stored), 2)

    def test_session_table_keeps_newest_thousand_and_purges_expired(self):
        now = time.time()
        with self.store.db() as db:
            db.executemany("INSERT INTO sessions VALUES(?,?)", [(f"old{i}", now + 86400 + i) for i in range(1005)])
            db.execute("INSERT INTO sessions VALUES('expired',?)", (now - 1,))
        token = self.store.create_session()
        with self.store.db() as db:
            self.assertEqual(db.execute("SELECT count(*) FROM sessions").fetchone()[0], 1000)
            self.assertIsNone(db.execute("SELECT 1 FROM sessions WHERE token='expired'").fetchone())
        self.assertTrue(self.store.has_session(token))

    def test_invalid_tokens_are_rejected_and_revocation_is_safe(self):
        token = self.store.create_session()
        for bad in (None, "", "x" * 129, token[:-1], token + "x"):
            self.assertFalse(self.store.has_session(bad))
        self.store.revoke_session(None)
        self.store.revoke_session("")
        self.store.revoke_session("unknown")
        self.assertTrue(self.store.has_session(token))
        self.store.revoke_session(token)
        self.assertFalse(self.store.has_session(token))


class LiveServer(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        self.store = server.Store(Path(self.tmp.name) / "db", CODE)
        self.server = server.Server(("127.0.0.1", 0), self.store)
        self.thread = threading.Thread(target=self.server.serve_forever, daemon=True)
        self.thread.start()
        self.port = self.server.server_port

    def tearDown(self):
        self.server.shutdown()
        self.server.server_close()
        self.thread.join()

    def call(self, method, path, headers=(), body=None):
        """Raw request: repeatable headers, no automatic Content-Length."""
        conn = http.client.HTTPConnection("127.0.0.1", self.port, timeout=5)
        try:
            conn.putrequest(method, path, skip_accept_encoding=True)
            for key, value in headers:
                conn.putheader(key, value)
            conn.endheaders(body)
            response = conn.getresponse()
            return response.status, response.read(), response.headers
        finally:
            conn.close()

    def post_json(self, path, value, headers=(), raw=None):
        body = raw if raw is not None else json.dumps(value).encode()
        return self.call("POST", path, [("Content-Length", str(len(body))), *headers], body)

    def raw_socket(self, data, shutdown=True):
        with socket.create_connection(("127.0.0.1", self.port), timeout=10) as sock:
            sock.sendall(data)
            if shutdown:
                sock.shutdown(socket.SHUT_WR)
            chunks = []
            while chunk := sock.recv(65536):
                chunks.append(chunk)
        return b"".join(chunks)

    def login_cookie(self):
        status, _, headers = self.post_json("/api/login", None, [("X-Relay-Code", CODE)], raw=b"")
        self.assertEqual(status, 200)
        return headers["Set-Cookie"].split(";", 1)[0]


class HTTPSecurityTest(LiveServer):
    def test_security_headers_on_pages_and_api(self):
        for method, path in (("GET", "/"), ("GET", "/app.js"), ("GET", "/api/latest"), ("GET", "/unknown")):
            status, _, headers = self.call(method, path)
            with self.subTest(path):
                self.assertEqual(headers["X-Content-Type-Options"], "nosniff")
                self.assertEqual(headers["Cache-Control"], "no-store")
                self.assertIn("frame-ancestors 'none'", headers["Content-Security-Policy"])
                self.assertIn("default-src 'self'", headers["Content-Security-Policy"])
                self.assertIsNone(headers["Access-Control-Allow-Origin"])
                self.assertEqual(headers["Connection"], "close")

    def test_only_listed_files_are_served(self):
        source = (Path(server.__file__)).read_bytes()
        for path in ("/index.html", "/web/app.js", "/server.py", "/../server.py", "/%2e%2e/server.py",
                     "/app.js?v=2", "/api/latest/", "/API/LATEST", "/data/relay.sqlite3", "/tests/test_server.py"):
            status, body, _ = self.call("GET", path)
            with self.subTest(path):
                self.assertEqual(status, 404)
                self.assertNotIn(b"sqlite3", body)
        self.assertNotIn(source[:40], self.call("GET", "/")[1])

    def test_unsupported_methods_are_refused(self):
        for method in ("PUT", "DELETE", "PATCH", "OPTIONS", "HEAD", "TRACE"):
            for path in ("/", "/api/latest", "/api/snapshots"):
                status, _, headers = self.call(method, path, [("X-Relay-Code", CODE)])
                with self.subTest(method=method, path=path):
                    self.assertEqual(status, 501)
                    self.assertIsNone(headers["Access-Control-Allow-Origin"])
        for method, path in (("GET", "/api/login"), ("GET", "/api/logout"), ("GET", "/api/snapshots"),
                             ("POST", "/"), ("POST", "/api/latest"), ("POST", "/api/stop"), ("POST", "/api/login/")):
            with self.subTest(method=method, path=path):
                self.assertEqual(self.post_json(path, None, [("X-Relay-Code", CODE)], raw=b"")[0] if method == "POST"
                                 else self.call(method, path, [("X-Relay-Code", CODE)])[0], 404)

    def test_login_refuses_missing_wrong_and_duplicated_codes(self):
        for headers in ((), [("X-Relay-Code", "000000")], [("X-Relay-Code", CODE), ("X-Relay-Code", CODE)],
                        [("X-Relay-Code", CODE + "0")], [("X-Relay-Code", CODE[:-1])]):
            status, body, response_headers = self.post_json("/api/login", None, headers, raw=b"")
            with self.subTest(headers=headers):
                self.assertEqual(status, 401)
                self.assertEqual(json.loads(body)["error"], "Incorrect code")
                self.assertIsNone(response_headers["Set-Cookie"])
        with self.store.db() as db:
            self.assertEqual(db.execute("SELECT count(*) FROM sessions").fetchone()[0], 0)

    def test_login_cookie_attributes_and_logout(self):
        status, body, headers = self.post_json("/api/login", None, [("X-Relay-Code", CODE)], raw=b"")
        self.assertEqual(json.loads(body), {"connected": True})
        cookie = headers["Set-Cookie"]
        self.assertRegex(cookie, r"^luba_session=[A-Za-z0-9_-]{40,}; HttpOnly; SameSite=Strict; Path=/; Max-Age=7776000$")
        self.assertNotIn(CODE, cookie)
        status, body, headers = self.post_json("/api/logout", None, raw=b"")  # without a session: no error
        self.assertEqual((status, json.loads(body)), (200, {"connected": False}))
        self.assertIn("Max-Age=0", headers["Set-Cookie"])

    def test_session_cookie_cannot_push_snapshots(self):
        cookie = self.login_cookie()
        # Empty body: if authenticated it would give 413; the 401 proves the session is not enough.
        self.assertEqual(self.post_json("/api/snapshots", None, [("Cookie", cookie)], raw=b"")[0], 401)
        self.assertEqual(self.post_json("/api/snapshots", None, raw=b"")[0], 401)
        self.assertIsNone(self.store.latest()["receivedAt"])

    def test_malformed_or_oversized_cookies_are_rejected_without_counting_failures(self):
        for cookie in ('luba_session="abc', "luba_session=" + "x" * 200, "other=1", "luba_session=", ";;;", "==", "a,=1"):
            with self.subTest(cookie=cookie):
                self.assertEqual(self.call("GET", "/api/latest", [("Cookie", cookie)])[0], 401)
        self.assertEqual(self.call("GET", "/api/latest", [("X-Relay-Code", CODE)])[0], 200)

    def test_valid_cookie_wins_over_wrong_code_and_wrong_code_alone_counts(self):
        cookie = self.login_cookie()
        self.assertEqual(self.call("GET", "/api/latest", [("Cookie", cookie), ("X-Relay-Code", "999999")])[0], 200)
        self.assertEqual(self.call("GET", "/api/latest", [("Cookie", "luba_session=fake"), ("X-Relay-Code", "999999")])[0], 401)
        self.assertEqual(len(self.server.failures["127.0.0.1"]), 1)

    def test_rate_limit_blocks_even_the_right_code_then_expires(self):
        for _ in range(5):
            self.assertEqual(self.post_json("/api/login", None, [("X-Relay-Code", "111111")], raw=b"")[0], 401)
        for path in ("/api/login", "/api/snapshots"):
            status, body, _ = self.post_json(path, None, [("X-Relay-Code", CODE)], raw=b"")
            self.assertEqual(status, 429)
            self.assertIn("wait one minute", json.loads(body)["error"])
        self.assertEqual(self.call("GET", "/api/latest", [("X-Relay-Code", CODE)])[0], 429)
        with self.server.auth_lock:
            self.server.failures["127.0.0.1"] = [t - 61 for t in self.server.failures["127.0.0.1"]]
        self.assertEqual(self.call("GET", "/api/latest", [("X-Relay-Code", CODE)])[0], 200)
        self.assertEqual(self.server.failures, {})

    def test_successes_do_not_count_as_failures(self):
        for _ in range(8):
            self.assertEqual(self.call("GET", "/api/latest", [("X-Relay-Code", CODE)])[0], 200)
        self.assertEqual(self.call("GET", "/api/latest", [("X-Relay-Code", "999999")])[0], 401)

    def test_global_limit_applies_to_all_addresses(self):
        for n in range(100):
            self.assertEqual(self.server.authenticate(f"10.0.{n // 250}.{n % 250}", "999999"), 401)
        self.assertEqual(self.server.authenticate("192.168.1.50", CODE), 429)
        self.assertEqual(self.server.authenticate("192.168.1.50", None), 429)
        self.assertEqual(self.server.authenticate("192.168.1.51", 123456), 429)

    def test_non_string_code_is_a_failure(self):
        self.assertEqual(self.server.authenticate("10.0.0.1", 1234), 401)
        self.assertEqual(self.server.authenticate("10.0.0.1", None), 401)
        self.assertEqual(len(self.server.failures["10.0.0.1"]), 2)

    def test_server_logs_nothing(self):
        stderr = io.StringIO()
        with contextlib.redirect_stderr(stderr):
            self.call("GET", "/")
            self.call("GET", "/api/latest", [("X-Relay-Code", "999999")])
            self.post_json("/api/snapshots", snapshot(), [("X-Relay-Code", CODE)])
            self.call("PUT", "/")
            self.raw_socket(b"NONSENSE\r\n\r\n")
        self.assertEqual(stderr.getvalue(), "")


class HTTPSnapshotTest(LiveServer):
    def headers(self, *extra):
        return [("X-Relay-Code", CODE), *extra]

    def test_nominal_push_then_duplicate_is_acknowledged_without_change(self):
        status, body, headers = self.post_json("/api/snapshots", snapshot(), self.headers())
        self.assertEqual((status, json.loads(body)), (200, {"stored": True}))
        self.assertTrue(headers["Content-Type"].startswith("application/json"))
        status, body, _ = self.post_json("/api/snapshots", snapshot(), self.headers())
        self.assertEqual((status, json.loads(body)), (200, {"stored": False}))

    def test_session_reads_latest(self):
        self.post_json("/api/snapshots", snapshot(), self.headers())
        status, body, _ = self.call("GET", "/api/latest", [("Cookie", self.login_cookie())])
        data = json.loads(body)
        self.assertEqual(status, 200)
        self.assertEqual(data["robotId"], "robot-A")
        self.assertTrue(data["relayOnline"])
        self.assertIn("relayAgeMs", data)

    def test_body_framing_errors(self):
        body = json.dumps(snapshot()).encode()
        cases = {
            # Refused before reading the body: no body sent (avoids an RST on unread data).
            "no length": ([], None, 400),
            "text length": ([("Content-Length", "abc")], None, 400),
            "two lengths": ([("Content-Length", str(len(body))), ("Content-Length", str(len(body)))], None, 400),
            "chunked": ([("Content-Length", str(len(body))), ("Transfer-Encoding", "chunked")], None, 400),
            "negative length": ([("Content-Length", "-1")], None, 400),
        }
        for name, (headers, payload, expected) in cases.items():
            with self.subTest(name):
                self.assertEqual(self.call("POST", "/api/snapshots", self.headers(*headers), payload)[0], expected)
        self.assertIsNone(self.store.latest()["receivedAt"])

    def test_empty_body_is_refused(self):
        for length in ("0", "-5"):
            with self.subTest(length=length):
                status, body, _ = self.call("POST", "/api/snapshots", self.headers(("Content-Length", length)))
                self.assertEqual((status, json.loads(body)["error"]), (400, "Invalid snapshot"))

    def test_body_over_8_mib_is_refused_before_reading(self):
        request = (f"POST /api/snapshots HTTP/1.1\r\nHost: x\r\nX-Relay-Code: {CODE}\r\n"
                   f"Content-Length: {server.MAX_BODY + 1}\r\n\r\n").encode()
        started = time.monotonic()
        response = self.raw_socket(request)
        self.assertTrue(response.startswith(b"HTTP/1.0 413"), response[:40])
        self.assertLess(time.monotonic() - started, 4)

    def test_body_of_exactly_8_mib_is_read(self):
        body = b" " * (server.MAX_BODY - 2) + b"{}"
        status, data, _ = self.post_json("/api/snapshots", None, self.headers(), raw=body)
        self.assertEqual((status, json.loads(data)["error"]), (400, "Invalid snapshot"))

    def test_truncated_body_is_refused(self):
        request = (f"POST /api/snapshots HTTP/1.1\r\nHost: x\r\nX-Relay-Code: {CODE}\r\n"
                   f"Content-Length: 100\r\n\r\n{{\"version\":1}}").encode()
        self.assertTrue(self.raw_socket(request).startswith(b"HTTP/1.0 400"))

    def test_invalid_json_contents_are_refused(self):
        valid = json.dumps(snapshot())
        cases = {
            "not JSON": b"{",
            "invalid UTF-8": b'{"version":1,"relayId":"\xff"}',
            "NaN": valid.replace('"ageMs": 100', '"ageMs": NaN', 1).encode(),
            "Infinity": valid.replace('"capturedAt": 1000000', '"capturedAt": Infinity').encode(),
            "-Infinity": valid.replace('"x": 2', '"x": -Infinity', 1).encode(),
            "1e999": valid.replace('"x": 2', '"x": 1e999', 1).encode(),
            "huge integer": valid.replace('"capturedAt": 1000000', '"capturedAt": 1' + "0" * 400).encode(),
            "5000-digit integer": valid.replace('"capturedAt": 1000000', '"capturedAt": ' + "9" * 5000).encode(),
            "deep nesting": b"[" * 100000 + b"]" * 100000,
            "array": b"[]",
            "string": b'"snapshot"',
        }
        for name, body in cases.items():
            with self.subTest(name):
                status, data, _ = self.post_json("/api/snapshots", None, self.headers(), raw=body)
                self.assertEqual((status, json.loads(data)), (400, {"error": "Invalid snapshot"}))
        self.assertIsNone(self.store.latest()["receivedAt"])

    def test_storage_error_on_read_and_login_answers_503(self):
        with mock.patch.object(self.store, "has_session", side_effect=sqlite3.OperationalError("database is locked")):
            status, body, _ = self.call("GET", "/api/latest", [("Cookie", "luba_session=abc")])
        self.assertEqual((status, json.loads(body)["error"]), (503, "Storage unavailable"))
        with mock.patch.object(self.store, "create_session", side_effect=sqlite3.OperationalError("disk I/O error")):
            status, body, _ = self.post_json("/api/login", None, [("X-Relay-Code", CODE)], raw=b"")
        self.assertEqual((status, json.loads(body)["error"]), (503, "Storage unavailable"))
        with mock.patch.object(self.store, "latest", side_effect=sqlite3.OperationalError("database is locked")):
            status, _, _ = self.call("GET", "/api/latest", [("X-Relay-Code", CODE)])
        self.assertEqual(status, 503)

    def test_storage_error_answers_503(self):
        with mock.patch.object(self.store, "ingest", side_effect=sqlite3.OperationalError("database is locked")):
            status, body, _ = self.post_json("/api/snapshots", snapshot(), self.headers())
        self.assertEqual((status, json.loads(body)["error"]), (503, "Storage unavailable"))


class HTTPConnectionLimitsTest(LiveServer):
    def test_ninth_simultaneous_connection_is_closed_then_service_resumes(self):
        for _ in range(8):
            self.assertTrue(self.server.slots.acquire(False))
        try:
            with self.assertRaises((http.client.RemoteDisconnected, ConnectionError)):
                self.call("GET", "/")
        finally:
            for _ in range(8):
                self.server.slots.release()
        self.assertEqual(self.call("GET", "/")[0], 200)

    def test_slot_is_released_when_thread_cannot_start(self):
        request = mock.Mock()
        with mock.patch.object(socketserver.ThreadingMixIn, "process_request", side_effect=RuntimeError("thread")):
            with self.assertRaises(RuntimeError):
                self.server.process_request(request, ("127.0.0.1", 1))
        acquired = [self.server.slots.acquire(False) for _ in range(8)]
        for ok in acquired:
            if ok:
                self.server.slots.release()
        self.assertTrue(all(acquired))

    def test_idle_client_is_disconnected_after_five_seconds(self):
        started = time.monotonic()
        response = self.raw_socket(b"GET / HTTP/1.1\r\n", shutdown=False)
        elapsed = time.monotonic() - started
        self.assertEqual(response, b"")
        self.assertGreater(elapsed, 4)
        self.assertLess(elapsed, 9)


class WebPageTest(LiveServer):
    def test_page_and_scripts_are_served_with_their_types(self):
        expected = {"/": ("index.html", "text/html"), "/app.js": ("app.js", "application/javascript"),
                    "/relay-map.js": ("relay-map.js", "application/javascript")}
        for path, (name, kind) in expected.items():
            status, body, headers = self.call("GET", path)
            with self.subTest(path):
                self.assertEqual(status, 200)
                self.assertEqual(body, (WEB / name).read_bytes())
                self.assertTrue(headers["Content-Type"].startswith(kind))
                self.assertEqual(int(headers["Content-Length"]), len(body))

    def test_scripts_referenced_by_the_page_exist(self):
        page = (WEB / "index.html").read_text()
        sources = re.findall(r'<script[^>]*\bsrc="([^"]+)"', page)
        self.assertEqual(sources, ["/relay-map.js", "/app.js"])
        for src in sources:
            self.assertEqual(self.call("GET", src)[0], 200)

    def test_routes_called_by_the_page_exist_with_the_same_method(self):
        script = (WEB / "app.js").read_text()
        calls = re.findall(r"fetch\('([^']+)',\{([^}]*)\}", script)
        routes = {url: ("POST" if "method:'POST'" in options else "GET") for url, options in calls}
        self.assertEqual(routes, {"/api/latest": "GET", "/api/login": "POST", "/api/logout": "POST"})
        for url, method in routes.items():
            status = (self.post_json(url, None, raw=b"") if method == "POST" else self.call(method, url))[0]
            with self.subTest(url):
                self.assertIn(status, (200, 401))
        self.assertIn("'X-Relay-Code':code.value", script)
        self.assertIn("response.status===401", script)
        self.assertIn("response.status===429", script)

    def test_page_matches_server_code_format_and_stays_offline(self):
        page = (WEB / "index.html").read_text()
        self.assertIn('pattern="[0-9]{6}"', page)
        self.assertIn('maxlength="6"', page)
        for name in ("index.html", "app.js", "relay-map.js"):
            text = (WEB / name).read_text()
            with self.subTest(name):
                self.assertIsNone(re.search(r"https?://", text))
                self.assertNotIn("localStorage", text)
                self.assertNotIn("document.cookie", text)


class MainTest(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        self.data = Path(self.tmp.name) / "data" / "relay.sqlite3"
        umask = os.umask(0o022)
        os.umask(umask)
        self.addCleanup(os.umask, umask)

    def run_main(self, *args):
        out = io.StringIO()
        argv = ["server.py", "--host", "127.0.0.1", "--port", "0", "--data", str(self.data), *args]
        with mock.patch.object(sys, "argv", argv), \
             mock.patch.object(server.Server, "serve_forever", side_effect=KeyboardInterrupt), \
             mock.patch.object(server.Server, "server_close", autospec=True,
                               side_effect=socketserver.TCPServer.server_close) as close, \
             contextlib.redirect_stdout(out):
            server.main()
        return out.getvalue(), close

    def test_main_prints_address_and_code_then_stops_cleanly(self):
        out, close = self.run_main("--code", CODE)
        self.assertRegex(out, r"^DumbyLuba: http://127\.0\.0\.1:[1-9][0-9]*/ — code 001234\n$")
        close.assert_called_once()
        self.assertEqual(server.Store(self.data).code, CODE)
        self.assertEqual(stat.S_IMODE(os.stat(self.data).st_mode), 0o600)
        out, _ = self.run_main()
        self.assertTrue(out.endswith("code 001234\n"))

    def test_main_refuses_bad_or_different_code(self):
        with self.assertRaises(ValueError):
            self.run_main("--code", "12345")
        self.run_main("--code", CODE)
        with self.assertRaises(ValueError):
            self.run_main("--code", "654321")

    def test_real_process_serves_then_stops_on_interrupt(self):
        script = Path(server.__file__)
        process = subprocess.Popen([sys.executable, str(script), "--host", "127.0.0.1", "--port", "0",
                                    "--data", str(self.data), "--code", CODE],
                                   stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True)
        try:
            line = process.stdout.readline()
            port = int(re.search(r":([0-9]+)/", line).group(1))
            conn = http.client.HTTPConnection("127.0.0.1", port, timeout=5)
            conn.request("GET", "/api/latest", headers={"X-Relay-Code": CODE})
            self.assertEqual(conn.getresponse().status, 200)
            conn.close()
            process.send_signal(signal.SIGINT)
            self.assertEqual(process.wait(timeout=10), 0)
            self.assertEqual(process.stderr.read(), "")
        finally:
            if process.poll() is None:
                process.kill()
                process.wait()
            process.stdout.close()
            process.stderr.close()


if __name__ == "__main__":
    unittest.main()
