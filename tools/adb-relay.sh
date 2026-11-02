#!/usr/bin/env bash
# Wireless ADB devices that the adb server cannot reach, connected through a local nc relay (sourced by tools/deploy.sh).
#
# Why: macOS Local Network privacy blocks third-party binaries whose responsible process has no permission. An adb server
# started by an agent (launched by the AgentDeck daemon, a LaunchAgent without UI and ad-hoc signed) sees no wireless
# device: empty `adb devices` and `adb mdns services`, "No route to host" on `adb connect IP:port` (2026-11-02, Fold).
# Apple's own binaries are exempt: dns-sd finds the phone and nc reaches its port. So each wireless debugging service
# advertised over mDNS whose phone is missing from `adb devices` is connected directly, or else through
# `nc` listening on 127.0.0.1 and forwarding to the phone (the TLS pairing does not depend on the address).
# The relays live as long as the calling script: adb_relay_stop stops them (their own PIDs, never by name).

ADB_RELAY_SERVICE="_adb-tls-connect._tcp"
# dns-sd never exits by itself: each query is read for this long.
ADB_RELAY_DNSSD_SECONDS=2
ADB_RELAY_PIDS=()
ADB_RELAY_FIFOS=()

# Output of a dns-sd query, read for ADB_RELAY_DNSSD_SECONDS.
adb_relay_dnssd() {
  local out pid
  out="$(mktemp)"
  dns-sd "$@" >"$out" 2>/dev/null &
  pid=$!
  sleep "$ADB_RELAY_DNSSD_SECONDS"
  kill "$pid" 2>/dev/null || true
  wait "$pid" 2>/dev/null || true
  cat "$out"
  rm -f "$out"
}

# Hardware serial numbers of the devices adb already sees, one per line.
adb_relay_connected_serials() {
  local serial state
  while read -r serial state _; do
    [ "$state" = "device" ] || continue
    "$ADB" -s "$serial" shell getprop ro.serialno 2>/dev/null </dev/null | tr -d '\r' || true
  done < <("$ADB" devices | tail -n +2)
}

# A free TCP port on 127.0.0.1 (the system picks it).
adb_relay_free_port() {
  python3 -c 'import socket; s = socket.socket(); s.bind(("127.0.0.1", 0)); print(s.getsockname()[1]); s.close()'
}

# Kills a relay loop, then the nc processes it started (in that order, or the loop would start new ones), by PID.
adb_relay_kill_tree() {
  local child children
  children="$(ps -A -o pid=,ppid= | awk -v parent="$1" '$2 == parent { print $1 }')"
  kill "$1" 2>/dev/null || true
  for child in $children; do adb_relay_kill_tree "$child"; done
}

adb_relay_stop() {
  local pid
  for pid in "${ADB_RELAY_PIDS[@]+"${ADB_RELAY_PIDS[@]}"}"; do
    adb_relay_kill_tree "$pid"
    # Reaps the loop, so bash does not report it as "Terminated".
    wait "$pid" 2>/dev/null || true
  done
  rm -f "${ADB_RELAY_FIFOS[@]+"${ADB_RELAY_FIFOS[@]}"}"
  ADB_RELAY_PIDS=()
  ADB_RELAY_FIFOS=()
}

# Starts a relay 127.0.0.1:$3 -> $1:$2, re-armed after each connection (sets ADB_RELAY_PIDS, hence no subshell).
adb_relay_start() {
  local ip="$1" port="$2" local_port="$3" fifo
  fifo="$(mktemp -u)"
  mkfifo "$fifo"
  ADB_RELAY_FIFOS+=("$fifo")
  # The fifo carries the phone's replies back to the listening nc: read and written on purpose.
  # shellcheck disable=SC2094
  (while true; do nc -l 127.0.0.1 "$local_port" <"$fifo" | nc "$ip" "$port" >"$fifo"; done) </dev/null >/dev/null 2>&1 &
  ADB_RELAY_PIDS+=("$!")
  # Leaves nc the time to listen before adb connects.
  sleep 0.5
}

# True when adb connected to $1 (output read whole: grep -q on a pipe can fail under pipefail).
adb_relay_adb_connect() {
  local out
  out="$("$ADB" connect "$1" 2>&1 || true)"
  case "$out" in "connected to"* | "already connected to"*) return 0 ;; *) return 1 ;; esac
}

# Connects one advertised service: directly when the adb server can reach the phone, else through a relay.
adb_relay_connect_instance() {
  local instance="$1" target host port ip local_port
  target="$(adb_relay_dnssd -L "$instance" "$ADB_RELAY_SERVICE" local. | sed -n 's/.* can be reached at \([^ ]*\) .*/\1/p' | head -1)"
  host="${target%:*}"
  port="${target##*:}"
  [ -n "$host" ] && [ -n "$port" ] || { echo "   $instance: address not resolved" >&2; return 0; }
  ip="$(adb_relay_dnssd -G v4 "$host" | awk '$2 == "Add" { print $6; exit }')"
  [ -n "$ip" ] || { echo "   $instance: no IPv4 address for $host" >&2; return 0; }
  if adb_relay_adb_connect "$ip:$port"; then
    echo "   $instance: connected at $ip:$port"
    return 0
  fi
  nc -z -G 3 "$ip" "$port" 2>/dev/null || { echo "   $instance: $ip:$port unreachable from this Mac" >&2; return 0; }
  local_port="$(adb_relay_free_port)"
  adb_relay_start "$ip" "$port" "$local_port"
  if adb_relay_adb_connect "127.0.0.1:$local_port"; then
    echo "   $instance: connected through the local relay 127.0.0.1:$local_port -> $ip:$port (adb without Local Network access)"
  else
    echo "   $instance: connection through the relay refused" >&2
  fi
}

# Connects every wireless debugging phone advertised over mDNS that adb does not see yet (macOS only).
adb_relay_missing_devices() {
  command -v dns-sd >/dev/null && command -v nc >/dev/null || return 0
  local connected instance serial
  connected=" $(adb_relay_connected_serials | tr '\n' ' ')"
  # Instance names are adb-<hardware serial>-<random suffix>.
  for instance in $(adb_relay_dnssd -B "$ADB_RELAY_SERVICE" local. | awk '$2 == "Add" { print $NF }' | sort -u); do
    serial="$(echo "$instance" | sed -n 's/^adb-\(.*\)-[^-]*$/\1/p')"
    [ -n "$serial" ] || continue
    case "$connected" in *" $serial "*) continue ;; esac
    echo ">> Wireless device $serial advertised but not seen by adb"
    adb_relay_connect_instance "$instance"
  done
}
