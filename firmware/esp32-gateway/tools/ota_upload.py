#!/usr/bin/env python3
"""Sends a firmware to the ESP32 gateway over Wi-Fi (src/ota.cpp), then waits for it to come back and confirm.

    pio run -e ota -t upload                                   # builds then calls this script
    python3 tools/ota_upload.py .pio/build/ota/firmware.bin --host dumbyluba-esp32.local --code 123456

Gateway code: --code, else the DUMBYLUBA_CODE variable, else prompted. It is never transmitted: the gateway
issues a single-use challenge and the script returns HMAC-SHA256(code, "DumbyLuba-ota-v1" + challenge + image md5).
While a pilot is in control, the gateway refuses the update unless the app allows it ("Updates during mowing"
setting): it then stops the robot, updates itself, restarts, and the app resumes mowing.

Standard library only. Exit code: 0 update confirmed, 2 refused, 1 error.
"""

import argparse
import getpass
import hashlib
import hmac
import http.client
import json
import os
import socket
import sys
import time
import urllib.parse
import uuid

LABEL = b"DumbyLuba-ota-v1"


def get_json(host, path, timeout=8):
    conn = http.client.HTTPConnection(host, 80, timeout=timeout)
    try:
        conn.request("GET", path)
        resp = conn.getresponse()
        body = resp.read()
        if resp.status != 200:
            raise RuntimeError(f"{path}: HTTP {resp.status} {body[:200]!r}")
        return json.loads(body)
    finally:
        conn.close()


def status(host):
    try:
        return get_json(host, "/status", timeout=4)
    except (OSError, RuntimeError, ValueError):
        return None


def proof(code, nonce, md5):
    return hmac.new(code.encode(), LABEL + nonce.encode() + md5.encode(), hashlib.sha256).hexdigest()


def upload(host, image, nonce, auth, md5):
    boundary = "----dumbyluba" + uuid.uuid4().hex
    head = (f"--{boundary}\r\nContent-Disposition: form-data; name=\"firmware\"; filename=\"firmware.bin\"\r\n"
            "Content-Type: application/octet-stream\r\n\r\n").encode()
    tail = f"\r\n--{boundary}--\r\n".encode()
    query = urllib.parse.urlencode({"nonce": nonce, "auth": auth, "md5": md5, "size": len(image)})
    conn = http.client.HTTPConnection(host, 80, timeout=120)
    try:
        conn.putrequest("POST", "/ota?" + query)
        conn.putheader("Content-Type", f"multipart/form-data; boundary={boundary}")
        conn.putheader("Content-Length", str(len(head) + len(image) + len(tail)))
        conn.endheaders()
        conn.send(head)
        sent, step, shown = 0, 16384, -1
        while sent < len(image):
            conn.send(image[sent:sent + step])
            sent += step
            pct = min(100, sent * 100 // len(image))
            if pct // 10 != shown:
                shown = pct // 10
                print(f"  upload {pct:3d} %", flush=True)
        conn.send(tail)
        resp = conn.getresponse()
        return resp.status, resp.read().decode("utf-8", "replace")
    finally:
        conn.close()


def main():
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("image", help="firmware.bin")
    parser.add_argument("--host", default=os.environ.get("DUMBYLUBA_HOST", "dumbyluba-esp32.local"))
    parser.add_argument("--code", default=os.environ.get("DUMBYLUBA_CODE"))
    parser.add_argument("--no-wait", action="store_true", help="do not wait for the restart or the confirmation")
    args = parser.parse_args()

    image = open(args.image, "rb").read()
    md5 = hashlib.md5(image).hexdigest()
    code = args.code or getpass.getpass("Gateway code (six digits): ")
    if len(code) != 6 or not code.isdigit():
        print("Invalid code: six digits expected", file=sys.stderr)
        return 1

    # mDNS name resolved only once, over IPv4: on macOS each ".local" lookup can take 5 s.
    try:
        host = socket.getaddrinfo(args.host, 80, socket.AF_INET, socket.SOCK_STREAM)[0][4][0]
    except OSError as error:
        print(f"Gateway {args.host} not found: {error}", file=sys.stderr)
        return 1
    if host != args.host:
        print(f"{args.host} → {host}")
    args.host = host

    before = status(args.host)
    if before is None:
        print(f"Gateway {args.host} unreachable (/status page)", file=sys.stderr)
        return 1
    print(f"Gateway {args.host}: {before.get('firmware')} · partition {before.get('ota', {}).get('partition', '?')} · "
          f"up for {before.get('uptime_s')} s")
    print(f"Image: {len(image)} bytes, MD5 {md5}")

    challenge = get_json(args.host, "/ota/nonce")
    if not challenge.get("allowed", False):
        print(f"Refused: {challenge.get('reason')}", file=sys.stderr)
        return 2
    if challenge.get("partition_size") and len(image) > challenge["partition_size"]:
        print(f"Image too large for the partition ({challenge['partition_size']} bytes)", file=sys.stderr)
        return 1

    t0 = time.monotonic()
    code_http, text = upload(args.host, image, challenge["nonce"], proof(code, challenge["nonce"], md5), md5)
    print(f"Response ({time.monotonic() - t0:.1f} s): HTTP {code_http} · {text}")
    if code_http != 200:
        return 2 if code_http in (403, 409) else 1
    if args.no_wait:
        return 0

    # Coming back: the gateway restarts on the other partition, then confirms the version after 60 s with Wi-Fi.
    old_partition = before.get("ota", {}).get("partition")
    deadline = time.monotonic() + 240
    back = None
    while time.monotonic() < deadline:
        time.sleep(3)
        now = status(args.host)
        if now is None:
            continue
        ota = now.get("ota", {})
        expected = before.get("uptime_s", 0) + (time.monotonic() - t0)  # without a restart
        if back is None and now.get("uptime_s", 1 << 30) < expected - 10:
            back = time.monotonic()
            print(f"Back after {back - t0:.0f} s: {now.get('firmware')} · partition {ota.get('partition')} · {ota.get('state')}")
            if old_partition and ota.get("partition") == old_partition:
                print("Still on the old partition: the new version did not start", file=sys.stderr)
                return 1
        if back is not None and ota.get("state") == "valid":
            print(f"New version confirmed on {ota.get('partition')}; robot: "
                  f"{'connected' if now.get('robot', {}).get('ready') else 'not connected'}")
            return 0
    print("No confirmation within 4 min: check /status (automatic rollback to the previous version possible)",
          file=sys.stderr)
    return 1


if __name__ == "__main__":
    sys.exit(main())
