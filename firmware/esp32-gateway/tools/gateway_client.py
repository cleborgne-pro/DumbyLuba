#!/usr/bin/env python3
"""Gateway test client (same protocol as the Fold, GatewayProtocol.kt version 2).

    python3 tools/gateway_client.py HOST --code 123456 check            # code and health
    python3 tools/gateway_client.py HOST --code 123456 scan             # robots seen by the gateway
    python3 tools/gateway_client.py HOST --code 123456 open ADDRESS     # robot link, syncs, latency
    python3 tools/gateway_client.py HOST --code 123456 open ADDRESS --silence
        # then goes silent: the gateway must write the safety stop, read back on reconnection
    python3 tools/gateway_client.py HOST --code 123456 --ota-mowing on hold ADDRESS --seconds 120
        # holds the robot like a pilot while mowing (OTA setting sent) and shows the update announcement

Standard library only. Only sends syncs (DevNet.todev_ble_sync) to the robot and, with --stop,
a zero-speed setpoint: no motion or blade command.
"""

import argparse
import hashlib
import hmac
import socket
import statistics
import struct
import sys
import time

VERSION = 2
CHALLENGE, HELLO, WELCOME, DENIED = 0x01, 0x02, 0x03, 0x04
SCAN, SCAN_RESULT, SCAN_END = 0x10, 0x11, 0x12
OPEN, DISCOVERING, READY, LOST, FAILURE = 0x20, 0x21, 0x22, 0x23, 0x24
WRITE, WRITTEN, RSSI, RSSI_RESULT = 0x30, 0x31, 0x32, 0x33
NOTIFY, PING, CLOSE, EVENT, HEALTH = 0x40, 0x50, 0x60, 0x70, 0x71
CONFIG = 0x61  # pilot settings, only if the gateway announces the "ota" capability
NAMES = {v: k for k, v in globals().items() if k.isupper() and isinstance(v, int) and k != "VERSION"}


def varint(v):
    out = bytearray()
    while v >= 0x80:
        out.append((v & 0x7F) | 0x80)
        v >>= 7
    out.append(v)
    return bytes(out)


def field_varint(num, v):
    return varint(num << 3) + varint(v) if v else b""


def envelope(msgtype, rcver, sub_field, sub, seq, now_ms):
    return (field_varint(1, msgtype) + field_varint(2, 7) + field_varint(3, rcver) + field_varint(4, 1)
            + field_varint(5, seq) + field_varint(6, 1) + varint(sub_field << 3 | 2) + varint(len(sub)) + sub
            + field_varint(15, now_ms))


def sync_payload(seq):
    return envelope(248, 0, 8, b"\x08\x02", seq, int(time.time() * 1000))


def stop_payload(seq):
    return envelope(243, 1, 12, b"\x0a\x00", seq, int(time.time() * 1000))


def blufi_frame(payload):
    assert len(payload) <= 255
    return bytes([0x4D, 0, 0, len(payload)]) + payload  # sequence rewritten by the gateway


class Channel:
    def __init__(self, host, port, code, timeout=2.0):
        self.sock = socket.create_connection((host, port), timeout=3)
        self.sock.setsockopt(socket.IPPROTO_TCP, socket.TCP_NODELAY, 1)
        self.sock.settimeout(timeout)
        self.buf = b""
        frame = self.read(wait_challenge=True)
        if frame[0] == DENIED:
            raise SystemExit(f"refused: {frame[1].decode()}")
        assert frame[0] == CHALLENGE and frame[1][0] == VERSION, f"unexpected response {frame}"
        nonce = frame[1][1:]
        proof = hmac.new(code.encode(), f"DumbyLuba-gateway-v{VERSION}".encode() + nonce, hashlib.sha256).digest()
        self.send(HELLO, bytes([VERSION]) + proof)
        answer = self.read()
        if answer[0] == DENIED:
            raise SystemExit(f"refused: {answer[1].decode()}")
        assert answer[0] == WELCOME, f"unexpected response {answer}"
        lines = answer[1].decode(errors="replace").split("\n")
        self.capabilities = set(lines[1][5:].split(",")) if len(lines) > 1 and lines[1].startswith("caps=") else set()
        self.last_ping = time.monotonic()

    def send(self, kind, payload=b""):
        self.sock.sendall(struct.pack(">BH", kind, len(payload)) + payload)

    def read(self, wait_challenge=False):
        while True:
            while len(self.buf) < 3 or len(self.buf) < 3 + struct.unpack(">H", self.buf[1:3])[0]:
                chunk = self.sock.recv(4096)
                if not chunk:
                    raise EOFError("gateway closed")
                self.buf += chunk
            size = struct.unpack(">H", self.buf[1:3])[0]
            kind, payload = self.buf[0], self.buf[3:3 + size]
            self.buf = self.buf[3 + size:]
            if wait_challenge and kind == PING:
                continue  # admission: the gateway makes us wait
            return kind, payload

    def keepalive(self):
        if time.monotonic() - self.last_ping >= 0.25:
            self.send(PING)
            self.last_ping = time.monotonic()

    def close(self):
        try:
            self.send(CLOSE)
        except OSError:
            pass
        self.sock.close()


def health_text(p):
    v = struct.unpack(">9i", p[:36])
    return f"battery {v[0]} % {'(charging)' if v[1] else ''} · Wi-Fi {v[3]} dBm"


def describe(kind, payload):
    if kind == HEALTH:
        return "health: " + health_text(payload)
    if kind in (READY,):
        return f"READY MTU {struct.unpack('>i', payload[:4])[0]}"
    if kind == LOST:
        return f"LOST {struct.unpack('>i', payload[:4])[0]}: {payload[4:].decode(errors='replace')}"
    if kind in (FAILURE, DENIED):
        return f"{NAMES[kind]}: {payload.decode(errors='replace')}"
    if kind == EVENT:
        return "EVENT " + payload.decode(errors="replace").replace("\n", ": ")
    return NAMES.get(kind, hex(kind))


def cmd_check(ch, args):
    end = time.monotonic() + 1.5
    while time.monotonic() < end:
        kind, payload = ch.read()
        if kind == HEALTH:
            print("code accepted;", describe(kind, payload))
            return 0
    print("code accepted; no health received")
    return 0


def cmd_scan(ch, args):
    ch.send(SCAN)
    ch.sock.settimeout(0.3)
    end = time.monotonic() + 12
    found = 0
    while time.monotonic() < end:
        ch.keepalive()
        try:
            kind, payload = ch.read()
        except socket.timeout:
            continue
        if kind == SCAN_RESULT:
            rssi = struct.unpack(">i", payload[:4])[0]
            n = payload[4]
            print(f"  {payload[5 + n:].decode()}  {payload[5:5 + n].decode()}  {rssi} dBm")
            found += 1
        elif kind == SCAN_END:
            print(f"end of scan: {found} robot(s)")
            return 0
    print("end of scan not received")
    return 1


def cmd_open(ch, args):
    ch.send(OPEN, (args.address + ("\n" + args.name if args.name else "")).encode())
    ch.sock.settimeout(0.05)
    start = time.monotonic()
    ready = False
    seq = 0
    pending = None
    latencies, notifications, failures = [], 0, 0
    deadline = start + args.timeout
    syncs_left = args.syncs
    silent_at = None
    while time.monotonic() < deadline:
        if silent_at is None:
            ch.keepalive()
        try:
            kind, payload = ch.read()
        except socket.timeout:
            kind = None
        if kind is not None and kind not in (PING, NOTIFY, WRITTEN):
            print(f"{time.monotonic() - start:6.2f} s  {describe(kind, payload)}")
        if kind == READY:
            ready = True
            deadline = time.monotonic() + args.syncs * 0.3 + 5
        elif kind in (LOST, FAILURE):
            return 1
        elif kind == NOTIFY:
            notifications += 1
        elif kind == WRITTEN:
            code, gatt_ms = struct.unpack(">ii", payload[:8])
            if code != 0:
                failures += 1
            latencies.append(((time.monotonic() - pending) * 1000, gatt_ms))
            pending = None
        if ready and pending is None and syncs_left > 0 and silent_at is None:
            seq = (seq + 1) & 255
            ch.send(WRITE, blufi_frame(sync_payload(seq)))
            pending = time.monotonic()
            syncs_left -= 1
        if ready and syncs_left == 0 and pending is None and silent_at is None:
            if args.stop:
                seq = (seq + 1) & 255
                ch.send(WRITE, blufi_frame(stop_payload(seq)))
                pending = time.monotonic()
                args.stop = False
                continue
            if args.silence:
                print("silence: no more frames sent (the gateway must stop the robot after 2 s)")
                silent_at = time.monotonic()
                deadline = silent_at + 4
            else:
                break
    if latencies:
        total = [l[0] for l in latencies]
        gatt = [l[1] for l in latencies]
        print(f"{len(latencies)} writes, {failures} refused · round trip median {statistics.median(total):.0f} ms, "
              f"max {max(total):.0f} ms · Bluetooth median {statistics.median(gatt):.0f} ms, max {max(gatt)} ms")
    print(f"{notifications} robot notifications relayed")
    if not ready:
        print("robot not ready")
        return 1
    if args.silence:
        ch.sock.close()
        time.sleep(2)
        again = Channel(args.host, args.port, args.code)
        again.send(OPEN, args.address.encode())
        again.sock.settimeout(0.5)
        end = time.monotonic() + 5
        while time.monotonic() < end:
            again.keepalive()
            try:
                kind, payload = again.read()
            except socket.timeout:
                continue
            if kind == EVENT:
                print("on reconnection:", describe(kind, payload))
                again.close()
                # "STOP écrit" is the gateway's protocol text for a written safety stop (kept in French).
                return 0 if "STOP écrit" in payload.decode(errors="replace") else 1
            if kind == READY:
                print("on reconnection: READY")
        again.close()
        print("no stop report received")
        return 1
    return 0 if failures == 0 else 1


def cmd_hold(ch, args):
    """Pilot holding the robot (as during mowing) until the gateway closes the session or the time runs out."""
    ch.send(OPEN, args.address.encode())
    ch.sock.settimeout(0.1)
    start = time.monotonic()
    announced = None
    while time.monotonic() - start < args.seconds:
        ch.keepalive()
        try:
            kind, payload = ch.read()
        except socket.timeout:
            continue
        except (EOFError, OSError) as error:
            print(f"{time.monotonic() - start:6.2f} s  session closed by the gateway ({error})")
            if announced is not None:
                print(f"closed {time.monotonic() - announced:.1f} s after the update announcement")
            return 0 if announced is not None else 1
        if kind in (PING, NOTIFY, WRITTEN, HEALTH):
            continue
        print(f"{time.monotonic() - start:6.2f} s  {describe(kind, payload)}")
        if kind == EVENT and payload.startswith(b"ota_begin"):
            announced = time.monotonic()
    print("time elapsed without closing")
    return 1


def main():
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("host")
    parser.add_argument("--port", type=int, default=8766)
    parser.add_argument("--code", required=True)
    parser.add_argument("--ota-mowing", choices=("on", "off"), help="sends the \"updates during mowing\" setting")
    sub = parser.add_subparsers(dest="cmd", required=True)
    sub.add_parser("check")
    sub.add_parser("scan")
    o = sub.add_parser("open")
    o.add_argument("address")
    o.add_argument("--name", default="")
    o.add_argument("--syncs", type=int, default=20, help="syncs to write once ready")
    o.add_argument("--timeout", type=float, default=40)
    o.add_argument("--stop", action="store_true", help="also write a zero speed")
    o.add_argument("--silence", action="store_true", help="go silent to trigger the safety stop")
    h = sub.add_parser("hold")
    h.add_argument("address")
    h.add_argument("--seconds", type=float, default=120)
    args = parser.parse_args()
    ch = Channel(args.host, args.port, args.code)
    print("capabilities:", ",".join(sorted(ch.capabilities)) or "none")
    if args.ota_mowing:
        if "ota" not in ch.capabilities:
            print("gateway without the \"ota\" capability: setting not sent")
        else:
            ch.send(CONFIG, f"ota_during_mowing={1 if args.ota_mowing == 'on' else 0}".encode())
    try:
        return {"check": cmd_check, "scan": cmd_scan, "open": cmd_open, "hold": cmd_hold}[args.cmd](ch, args)
    finally:
        if not args.__dict__.get("silence"):
            ch.close()


if __name__ == "__main__":
    sys.exit(main())
