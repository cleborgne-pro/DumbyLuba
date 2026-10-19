"""macOS installer: run in a temporary HOME with a mocked launchctl (no real service touched)."""
import contextlib
import importlib.util
import io
import os
from pathlib import Path
import plistlib
import sys
import tempfile
import unittest
from unittest import mock

from test_server import server, snapshot

SOURCE = Path(__file__).parents[1]
spec = importlib.util.spec_from_file_location("install_macos", SOURCE / "install-macos.py")
installer = importlib.util.module_from_spec(spec)
spec.loader.exec_module(installer)


class InstallMacOSTest(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        self.home = Path(self.tmp.name)
        self.root = self.home / "Library" / "Application Support" / "DumbyLubaServer"
        self.plist = self.home / "Library" / "LaunchAgents" / "fr.luba.home-server.plist"
        umask = os.umask(0o022)
        os.umask(umask)
        self.addCleanup(os.umask, umask)

    def install(self, *args, platform="darwin"):
        out = io.StringIO()
        run = mock.Mock()
        with mock.patch.object(sys, "argv", ["install-macos.py", *args]), \
             mock.patch.object(sys, "platform", platform), \
             mock.patch.object(Path, "home", return_value=self.home), \
             mock.patch.object(installer.subprocess, "run", run), \
             mock.patch.object(installer.shutil, "which", return_value="/usr/bin/python3"), \
             contextlib.redirect_stdout(out):
            try:
                installer.main()
            finally:
                self.run_calls = run.call_args_list
        return out.getvalue()

    def test_refuses_other_systems_without_writing(self):
        with self.assertRaises(SystemExit):
            self.install(platform="linux")
        self.assertFalse((self.home / "Library").exists())
        self.assertEqual(self.run_calls, [])

    def test_first_install_copies_files_writes_agent_and_restarts_it(self):
        out = self.install("--code", "001234")
        self.assertIn("Code: 001234", out)
        self.assertEqual((self.root / "server.py").read_bytes(), (SOURCE / "server.py").read_bytes())
        for name in ("index.html", "app.js", "relay-map.js"):
            self.assertEqual((self.root / "web" / name).read_bytes(), (SOURCE / "web" / name).read_bytes())
        database = self.root / "data" / "relay.sqlite3"
        self.assertEqual(server.Store(database).code, "001234")
        with self.plist.open("rb") as f:
            definition = plistlib.load(f)
        self.assertEqual(definition["Label"], "fr.luba.home-server")
        self.assertEqual(definition["ProgramArguments"], ["/usr/bin/caffeinate", "-i", "/usr/bin/python3",
                                                          str(self.root / "server.py"), "--data", str(database)])
        self.assertTrue(definition["RunAtLoad"])
        self.assertTrue(definition["KeepAlive"])
        self.assertEqual(definition["WorkingDirectory"], str(self.root))
        self.assertNotIn("001234", self.plist.read_text(errors="ignore"))  # the code is not in the plist
        commands = [call.args[0] for call in self.run_calls]
        uid = os.getuid()
        self.assertEqual(commands, [["launchctl", "bootout", f"gui/{uid}/fr.luba.home-server"],
                                    ["launchctl", "bootstrap", f"gui/{uid}", str(self.plist)]])
        self.assertTrue(self.run_calls[1].kwargs["check"])

    def test_update_keeps_database_and_code(self):
        self.install("--code", "001234")
        store = server.Store(self.root / "data" / "relay.sqlite3")
        store.ingest(snapshot(), now=1000)
        out = self.install()
        self.assertIn("Code: 001234", out)
        self.assertEqual(server.Store(self.root / "data" / "relay.sqlite3").latest(now=1000)["sequence"], 1)

    def test_different_code_is_refused_before_stopping_the_service(self):
        self.install("--code", "001234")
        with self.assertRaises(ValueError):
            self.install("--code", "999999")
        self.assertEqual(self.run_calls, [])
        with self.assertRaises(ValueError):
            self.install("--code", "12345")
        self.assertEqual(self.run_calls, [])


if __name__ == "__main__":
    unittest.main()
