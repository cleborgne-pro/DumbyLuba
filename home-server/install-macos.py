#!/usr/bin/env python3
"""Install/update the local user LaunchAgent without touching its database."""
import argparse
import importlib.util
from pathlib import Path
import plistlib
import shutil
import subprocess
import sys
import os


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--code", help="Six-digit Android code, for the first installation")
    args = parser.parse_args()
    if sys.platform != "darwin":
        raise SystemExit("This installer is for macOS")
    source = Path(__file__).resolve().parent
    root = Path.home() / "Library" / "Application Support" / "DumbyLubaServer"
    agents = Path.home() / "Library" / "LaunchAgents"
    label = "fr.luba.home-server"
    plist = agents / (label + ".plist")
    uid = os.getuid()
    os.umask(0o077)
    root.mkdir(parents=True, exist_ok=True)
    agents.mkdir(parents=True, exist_ok=True)
    # Validate the persisted code before interrupting a running service.
    spec = importlib.util.spec_from_file_location("server", source / "server.py")
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    store = module.Store(root / "data" / "relay.sqlite3", args.code)
    subprocess.run(["launchctl", "bootout", f"gui/{uid}/{label}"], stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL, check=False)
    shutil.copy2(source / "server.py", root / "server.py")
    shutil.copytree(source / "web", root / "web", dirs_exist_ok=True)
    interpreter = shutil.which("python3") or sys.executable
    definition = {
        "Label": label,
        "ProgramArguments": ["/usr/bin/caffeinate", "-i", interpreter, str(root / "server.py"), "--data", str(root / "data" / "relay.sqlite3")],
        "WorkingDirectory": str(root), "RunAtLoad": True, "KeepAlive": True,
        "ThrottleInterval": 5,
        "StandardOutPath": str(root / "server.log"), "StandardErrorPath": str(root / "server-error.log"),
    }
    with plist.open("wb") as f:
        plistlib.dump(definition, f)
    subprocess.run(["launchctl", "bootstrap", f"gui/{uid}", str(plist)], check=True)
    print(f"Server installed and started: http://localhost:8765/\nCode: {store.code}\nData: {root / 'data'}")


if __name__ == "__main__":
    main()
