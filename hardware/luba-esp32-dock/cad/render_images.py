#!/usr/bin/env python3
"""Retake the README images from the generated viewer with headless Google Chrome.

Standard library only. Run cad/generate.py first. Chrome is taken from $CHROME, or from the
usual macOS / Linux locations. A connection is needed: the viewer loads three.js from a CDN.

Usage: python3 -I render_images.py   (from any directory)
"""
from __future__ import annotations

import os
import shutil
import subprocess
import sys
import tempfile
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
VIEWER = ROOT / "viewer" / "luba-esp32-dock.html"
SIZE = (1400, 900)

# image -> URL parameters of the viewer (see applyHash in viewer_template.html)
SHOTS = {
    "01_overview.png": "ui=0",
    "02_enclosure_docked.png": "detail=5&ui=0",
    "03_exploded_view.png": "mode=boitier&explode=1&notes=1&ui=0",
    "04_side_docked.png": "detail=1&ui=0",
    "05_side_approach.png": "pos=300&ghost=1&view=side&ui=0",
    "06_sealing_detail.png": "detail=0&ui=0",
}

CANDIDATES = [
    "/Applications/Google Chrome.app/Contents/MacOS/Google Chrome",
    "/Applications/Chromium.app/Contents/MacOS/Chromium",
    "google-chrome", "chromium", "chromium-browser",
]


def find_chrome() -> str:
    for c in ([os.environ["CHROME"]] if os.environ.get("CHROME") else []) + CANDIDATES:
        path = c if os.path.isabs(c) else shutil.which(c)
        if path and os.path.exists(path):
            return path
    raise SystemExit("Google Chrome or Chromium not found: set CHROME to its executable")


def main() -> int:
    if not VIEWER.exists():
        raise SystemExit("Run cad/generate.py first")
    chrome = find_chrome()
    out = ROOT / "images"
    out.mkdir(exist_ok=True)
    with tempfile.TemporaryDirectory() as profile:
        for name, params in SHOTS.items():
            target = out / name
            cmd = [chrome, "--headless=new", "--enable-gpu", "--ignore-gpu-blocklist", "--hide-scrollbars",
                   f"--user-data-dir={profile}", f"--window-size={SIZE[0]},{SIZE[1]}", "--virtual-time-budget=20000",
                   f"--screenshot={target}", f"{VIEWER.as_uri()}#{params}"]
            if sys.platform == "darwin":
                cmd.insert(2, "--use-angle=metal")     # WebGL needs the GPU in headless mode
            subprocess.run(cmd, check=True, capture_output=True)
            print(f"  - images/{name}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
