#!/usr/bin/env python3
"""Measures the TCP round trip to the ESP32 (echo server on port 8767).

Sends a 32-byte frame every 250 ms, the gateway's PING rate, and counts the responses
arriving after 2 s: beyond that, the gateway would trigger the safety stop.
No dependency outside the standard library.

    python3 tools/latency.py dumbyluba-esp32.local --duration 120
"""

import argparse
import json
import socket
import statistics
import struct
import sys
import time

FRAME = struct.Struct("<IQ20x")  # number, ns timestamp, padding -> 32 bytes
SAFETY_LIMIT_S = 2.0


def percentile(values, p):
    if not values:
        return float("nan")
    ordered = sorted(values)
    k = min(len(ordered) - 1, max(0, round(p / 100 * (len(ordered) - 1))))
    return ordered[k]


def main():
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("host", nargs="?", default="dumbyluba-esp32.local")
    parser.add_argument("--port", type=int, default=8767)
    parser.add_argument("--duration", type=float, default=60.0, help="duration in seconds")
    parser.add_argument("--interval", type=float, default=0.25, help="interval between frames (s)")
    parser.add_argument("--json", action="store_true", help="summary as JSON")
    args = parser.parse_args()

    sock = socket.create_connection((args.host, args.port), timeout=5)
    sock.setsockopt(socket.IPPROTO_TCP, socket.TCP_NODELAY, 1)
    sock.settimeout(0.01)

    sent = {}
    rtts = []
    late = 0
    buf = b""
    seq = 0
    start = time.monotonic()
    next_send = start
    while True:
        now = time.monotonic()
        if now - start >= args.duration and not sent:
            break
        if now - start >= args.duration + 5:
            break
        if now >= next_send and now - start < args.duration:
            sock.sendall(FRAME.pack(seq, time.monotonic_ns()))
            sent[seq] = now
            seq += 1
            next_send += args.interval
        try:
            chunk = sock.recv(4096)
            if not chunk:
                print("connection closed by the ESP32", file=sys.stderr)
                break
            buf += chunk
        except socket.timeout:
            pass
        while len(buf) >= FRAME.size:
            num, _ = FRAME.unpack(buf[: FRAME.size])
            buf = buf[FRAME.size :]
            t0 = sent.pop(num, None)
            if t0 is None:
                continue
            rtt = time.monotonic() - t0
            rtts.append(rtt)
            if rtt > SAFETY_LIMIT_S:
                late += 1
    sock.close()

    ms = [r * 1000 for r in rtts]
    summary = {
        "host": args.host,
        "sent": seq,
        "received": len(rtts),
        "lost": len(sent),
        "over_2s": late,
        "p50_ms": round(percentile(ms, 50), 1),
        "p95_ms": round(percentile(ms, 95), 1),
        "p99_ms": round(percentile(ms, 99), 1),
        "max_ms": round(max(ms), 1) if ms else None,
        "mean_ms": round(statistics.fmean(ms), 1) if ms else None,
    }
    if args.json:
        print(json.dumps(summary))
    else:
        print(
            f"{summary['received']}/{summary['sent']} responses, {summary['lost']} lost, > 2 s: {late}\n"
            f"median {summary['p50_ms']} ms · p95 {summary['p95_ms']} ms · p99 {summary['p99_ms']} ms · "
            f"max {summary['max_ms']} ms"
        )
    return 0 if summary["lost"] == 0 and late == 0 else 1


if __name__ == "__main__":
    sys.exit(main())
