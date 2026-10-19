import importlib.util
import json
from pathlib import Path
import tempfile
import threading
import unittest
from urllib.request import Request, urlopen
from urllib.error import HTTPError

spec = importlib.util.spec_from_file_location("server", Path(__file__).parents[1] / "server.py")
server = importlib.util.module_from_spec(spec)
spec.loader.exec_module(server)


def snapshot(seq=1, relay="phone", robot="robot-A"):
    return {"version": 1, "relayId": relay, "sequence": seq, "capturedAt": 1000000, "robotId": robot,
            "state": {"connection": "BLE", "ageMs": 100, "position": {"x": 2, "y": -3, "at": 1000000, "ageMs": 100, "fresh": True, "source": "BLE"}, "telemetry": {"battery": 82}},
            "map": {"shapes": [{"id": "1", "type": 0, "points": [[0,0],[10,0],[10,10]]}], "complete": True, "source": "BLE", "base": {"x": 0, "y": -2, "source": "Robot"}}}


class StoreTest(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        self.path = Path(self.tmp.name) / "relay.sqlite3"
        self.store = server.Store(self.path, "001234")

    def test_restart_keeps_map_position_and_code(self):
        self.store.ingest(snapshot(), now=1000)
        restored = server.Store(self.path)
        self.assertEqual(restored.code, "001234")
        data = restored.latest(now=1030)
        self.assertFalse(data["relayOnline"])
        self.assertFalse(data["state"]["position"]["fresh"])
        self.assertEqual(data["state"]["position"]["ageMs"], 30100)
        self.assertEqual(data["map"]["base"]["y"], -2)

    def test_duplicates_and_delayed_snapshots_never_regress(self):
        self.assertTrue(self.store.ingest(snapshot(2), now=1000))
        self.assertFalse(self.store.ingest(snapshot(1), now=1001))
        self.assertFalse(self.store.ingest(snapshot(2), now=1002))
        self.assertEqual(self.store.latest(now=1003)["sequence"], 2)
        self.assertEqual(self.store.latest(now=1003)["receivedAt"], 1000000)

    def test_partial_map_and_missing_position_do_not_erase_last_known(self):
        self.store.ingest(snapshot(), now=1000)
        missing = snapshot(2)
        missing["map"]["shapes"] = []
        missing["map"]["base"] = None
        missing["map"]["complete"] = False
        missing["state"]["position"] = None
        self.store.ingest(missing, now=1010)
        data = self.store.latest(now=1020)
        self.assertTrue(data["map"]["complete"])
        self.assertEqual(data["state"]["position"]["ageMs"], 20100)
        self.assertEqual(len(data["map"]["shapes"]), 1)

    def test_robot_switch_never_reuses_another_robot_map(self):
        self.store.ingest(snapshot(), now=1000)
        value = snapshot(2, robot="robot-B")
        value["map"] = {"shapes": [], "complete": False, "base": None}
        value["state"]["position"] = None
        self.store.ingest(value, now=1001)
        self.assertEqual(self.store.latest(now=1002)["map"]["shapes"], [])
        self.assertIsNone(self.store.latest(now=1002)["state"]["position"])

    def test_invalid_payload_does_not_change_saved_data(self):
        self.store.ingest(snapshot(), now=1000)
        value = snapshot(2)
        value["map"]["shapes"][0]["points"] = [["bad",0]]
        with self.assertRaises(ValueError):
            self.store.ingest(value)
        self.assertEqual(self.store.latest()["sequence"], 1)
        with self.assertRaises(ValueError):
            server.Store(self.path, "999999")

    def test_delayed_phone_outbox_is_never_displayed_as_live(self):
        self.store.ingest(snapshot(), now=1060)
        data = self.store.latest(now=1061)
        self.assertFalse(data["state"]["position"]["fresh"])
        self.assertEqual(data["state"]["position"]["ageMs"], 61100)

    def test_history_is_bounded(self):
        for n in range(1, 1003):
            self.store.ingest(snapshot(n), now=1000+n)
        with self.store.db() as db:
            self.assertEqual(db.execute("SELECT count(*) FROM history").fetchone()[0], 1000)


class HTTPTest(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.store = server.Store(Path(self.tmp.name) / "db", "001234")
        self.server = server.Server(("127.0.0.1", 0), self.store)
        self.thread = threading.Thread(target=self.server.serve_forever, daemon=True)
        self.thread.start()
        self.base = f"http://127.0.0.1:{self.server.server_port}"

    def tearDown(self):
        self.server.shutdown()
        self.server.server_close()
        self.thread.join()
        self.tmp.cleanup()

    def request(self, path, code=None, value=None, cookie=None):
        headers = {} if code is None else {"X-Relay-Code": code}
        if cookie:
            headers["Cookie"] = cookie
        body = None if value is None else json.dumps(value).encode()
        request = Request(self.base+path, data=body, headers=headers)
        try:
            with urlopen(request, timeout=3) as response:
                return response.status, response.read(), dict(response.headers)
        except HTTPError as error:
            with error:
                return error.code, error.read(), dict(error.headers)

    def test_public_page_has_no_code_and_api_requires_six_digits(self):
        status, page, _ = self.request("/")
        self.assertEqual(status, 200)
        self.assertNotIn(b"001234", page)
        self.assertEqual(self.request("/api/latest")[0], 401)
        self.assertEqual(self.request("/api/latest", "001234")[0], 200)
        self.assertEqual(self.request("/relay-map.js")[0], 200)

    def test_end_to_end_push_then_offline_read_from_disk(self):
        self.assertEqual(self.request("/api/snapshots", "001234", snapshot())[0], 200)
        status, body, headers = self.request("/api/latest", "001234")
        self.assertEqual(status, 200)
        self.assertEqual(json.loads(body)["state"]["telemetry"]["battery"], 82)
        self.assertNotIn("Access-Control-Allow-Origin", headers)
        restored = server.Store(self.store.path)
        self.assertEqual(restored.latest()["map"]["base"]["y"], -2)

    def test_bruteforce_is_rate_limited(self):
        for _ in range(5):
            self.assertEqual(self.request("/api/latest", "999999")[0], 401)
        self.assertEqual(self.request("/api/latest", "999999")[0], 429)

    def test_login_cookie_survives_server_restart_and_logout_revokes_it(self):
        status, body, headers = self.request("/api/login", "001234", {})
        self.assertEqual(status, 200)
        cookie = headers["Set-Cookie"].split(";",1)[0]
        self.assertIn("HttpOnly", headers["Set-Cookie"])
        self.assertIn("SameSite=Strict", headers["Set-Cookie"])
        self.assertIn("Max-Age=7776000", headers["Set-Cookie"])
        self.assertEqual(self.request("/api/latest", cookie=cookie)[0], 200)
        restored = server.Store(self.store.path)
        self.assertTrue(restored.has_session(cookie.split("=",1)[1]))
        self.server.store = restored
        self.assertEqual(self.request("/api/latest", cookie=cookie)[0], 200)
        self.assertEqual(self.request("/api/logout", value={}, cookie=cookie)[0], 200)
        self.assertEqual(self.request("/api/latest", cookie=cookie)[0], 401)
        self.assertFalse(restored.has_session(cookie.split("=",1)[1]))

    def test_expired_session_is_rejected_without_blocking_login(self):
        token = self.store.create_session()
        with self.store.db() as db:
            db.execute("UPDATE sessions SET expires=0")
        for _ in range(6):
            self.assertEqual(self.request("/api/latest", cookie="luba_session="+token)[0], 401)
        self.assertEqual(self.request("/api/login", "001234", {})[0], 200)
        with self.store.db() as db:
            self.assertNotEqual(db.execute("SELECT token FROM sessions").fetchone()[0], token)

    def test_invalid_requests_and_robot_commands_are_refused(self):
        self.assertEqual(self.request("/api/snapshots", "001234", {"bad": 1})[0], 400)
        self.assertEqual(self.request("/api/stop", "001234", {})[0], 404)
        self.assertEqual(self.request("/api/latest?code=001234")[0], 404)


if __name__ == "__main__":
    unittest.main()
