#!/usr/bin/env python3
"""Listens to the robot's DDS -> ZMQ debug bridge (port 5555) from a computer on the same Wi-Fi network.

The bridge is enabled from the app: Service -> Tests and diagnostics -> Ultrasonic sensors -> capture -> "DDS -> ZMQ bridge".
Minimal ZMTP 3.0 client (NULL mechanism), without pyzmq. Each message is written to OUT/NNNNN-pK.bin and summarized on screen.

Usage: tools/zmq-listen.py ROBOT_IP [--port 5555] [--out FOLDER] [--seconds 120] [--type SUB]
"""
import argparse
import os
import socket
import struct
import sys
import time

COMPATIBLE = {"PUB": "SUB", "XPUB": "SUB", "PUSH": "PULL", "PAIR": "PAIR", "ROUTER": "DEALER", "DEALER": "DEALER", "REP": "DEALER"}


def read_exact(sock, n):
    data = b""
    while len(data) < n:
        chunk = sock.recv(n - len(data))
        if not chunk:
            raise EOFError("connection closed by the robot")
        data += chunk
    return data


def frame(body, command=False, more=False):
    flags = (1 if more else 0) | (4 if command else 0)
    if len(body) > 255:
        return bytes([flags | 2]) + struct.pack(">Q", len(body)) + body
    return bytes([flags, len(body)]) + body


def read_frame(sock):
    flags = read_exact(sock, 1)[0]
    if flags & 0xF8:
        raise ValueError("invalid ZMTP frame (flags 0x%02x)" % flags)
    size = struct.unpack(">Q", read_exact(sock, 8))[0] if flags & 2 else read_exact(sock, 1)[0]
    if size > 64 << 20:
        raise ValueError("frame too large (%d bytes)" % size)
    return flags & 1, flags & 4, read_exact(sock, size)


def properties(body):
    n = body[0]
    props, i = {}, 1 + n
    while i < len(body):
        k = body[i]; name = body[i + 1:i + 1 + k].decode(errors="replace"); i += 1 + k
        size = struct.unpack(">I", body[i:i + 4])[0]; i += 4
        props[name] = body[i:i + size].decode(errors="replace"); i += size
    return body[1:1 + n].decode(errors="replace"), props


def handshake(sock, socket_type):
    greeting = bytearray(64)
    greeting[0], greeting[9], greeting[10], greeting[11] = 0xFF, 0x7F, 3, 0
    greeting[12:16] = b"NULL"
    sock.sendall(bytes(greeting))
    peer = read_exact(sock, 64)
    if peer[0] != 0xFF or peer[9] != 0x7F:
        raise ValueError("not a ZMTP greeting: " + peer[:16].hex())
    mechanism = peer[12:32].rstrip(b"\0").decode()
    print("Robot greeting: ZMTP %d.%d, mechanism %s" % (peer[10], peer[11], mechanism))
    if mechanism != "NULL":
        raise ValueError("mechanism %s not supported" % mechanism)
    body = b"\x05READY" + bytes([11]) + b"Socket-Type" + struct.pack(">I", len(socket_type)) + socket_type.encode()
    if socket_type == "DEALER":
        body += bytes([8]) + b"Identity" + struct.pack(">I", 0)
    sock.sendall(frame(body, command=True))
    _, command, ready = read_frame(sock)
    name, props = properties(ready)
    if name == "ERROR":
        raise ValueError("refused: " + ready[7:].decode(errors="replace"))
    print("Robot READY:", props)
    if socket_type == "SUB":
        sock.sendall(frame(b"\x01"))
    return props.get("Socket-Type", "")


def preview(part, limit=48):
    if part and all(32 <= b < 127 for b in part[:limit]):
        return '"%s"%s' % (part[:limit].decode(), "…" if len(part) > limit else "")
    return part[:limit].hex() + ("…" if len(part) > limit else "")


def main():
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("ip")
    parser.add_argument("--port", type=int, default=5555)
    parser.add_argument("--out", default=os.path.join(os.environ.get("TMPDIR", "/tmp"), "luba-zmq"))
    parser.add_argument("--seconds", type=float, default=120)
    parser.add_argument("--type", default="SUB")
    args = parser.parse_args()
    sys.stdout.reconfigure(line_buffering=True)  # output readable live, even when redirected to a file
    os.makedirs(args.out, exist_ok=True)
    socket_type, deadline, count = args.type, time.time() + args.seconds, 0
    while time.time() < deadline:
        try:
            with socket.create_connection((args.ip, args.port), timeout=3) as sock:
                sock.settimeout(5)
                peer = handshake(sock, socket_type)
                wanted = COMPATIBLE.get(peer.upper(), socket_type)
                if wanted != socket_type:
                    print("Robot %s: reconnecting as %s" % (peer, wanted)); socket_type = wanted; continue
                sock.settimeout(max(1, deadline - time.time()))
                while time.time() < deadline:
                    parts, more = [], 1
                    while more:
                        more, command, body = read_frame(sock)
                        if command:
                            more = 1 if not parts else more
                            continue
                        parts.append(body)
                    count += 1
                    for k, part in enumerate(parts):
                        with open(os.path.join(args.out, "%05d-p%d.bin" % (count, k)), "wb") as f:
                            f.write(part)
                    print("#%d %s · %s" % (count, time.strftime("%H:%M:%S"),
                                           " | ".join("%d B %s" % (len(p), preview(p)) for p in parts)), flush=True)
        except socket.timeout:
            print("No message within the timeout."); break
        except (OSError, EOFError, ValueError) as error:
            print("%s:%d: %s" % (args.ip, args.port, error), flush=True)
            time.sleep(3)
    print("%d message(s) saved to %s" % (count, args.out))
    return 0 if count else 1


if __name__ == "__main__":
    sys.exit(main())
