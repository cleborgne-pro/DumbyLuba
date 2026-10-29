# ESP32 diagnostics (LILYGO TTGO T-Call V1.3)

First program for the ESP32 gateway mounted on the LUBA ([enclosure](../../hardware/luba-esp32-dock/README.md)).
It **does not drive the robot**: it checks the board before the gateway firmware
([`firmware/esp32-gateway`](../esp32-gateway/README.md), which reuses the Wi-Fi network saved here) is installed on it.
The Redmi remains the gateway in service on the robot ([ANDROID_GATEWAY.md](../../docs/ANDROID_GATEWAY.md)).

| Check | How |
|---|---|
| IP5306 charging circuit: present, output kept on at low draw, USB or battery power, fully charged, level (100/75/50/25/0 %) | At boot and every 10 s on the console, `power` field of `/status` |
| Wi-Fi: connection, signal, channel, disconnections, power-saving mode | Page `http://dumbyluba-esp32.local/` (refreshed every 5 s), `/status` as JSON |
| Bluetooth: 5 s scan every 30 s, LUBA spotted by its name (containing "luba" or "mammotion") | `ble` field of `/status`. **No connection to the robot.** A LUBA held by the Redmi or the Mammotion app does not advertise |
| Wi-Fi latency at the gateway's PING rate (250 ms) and number of responses beyond 2 s (safety stop threshold) | TCP echo server on port 8767 + `tools/latency.py` |
| Unexpected restarts (USB unplugged, voltage drop) | `uptime_s` and `reset_reason` |
| 2G modem | Kept powered off (GPIO 23 at 0) |

## Build and upload

```sh
python3 -m venv /tmp/espvenv && /tmp/espvenv/bin/pip install platformio
cd firmware/esp32-diag
/tmp/espvenv/bin/pio run -t upload        # build and upload (USB port detected automatically)
/tmp/espvenv/bin/pio device monitor       # serial console 115200 baud
```

## Home Wi-Fi

With no saved network (or if the connection fails for 20 s), the board opens the access point
**`DumbyLuba-ESP32-xxxx`**. Its password, drawn at random at first boot and unique to the board, is
shown on the serial console (`ap-pass` command to show it again). From a phone: connect to it, the
setup page opens (otherwise `http://192.168.4.1/wifi`), pick the network, enter the password. The board
restarts and connects; the access point closes one minute later. Without Wi-Fi, the saved network is retried every
30 s (unless someone is connected to the access point).
The credentials stay in the board's memory, not in the repository.

Alternatively, on the serial console: `wifi NAME|PASSWORD`.

## Console commands

| Command | Effect |
|---|---|
| `status` | Full state as JSON |
| `wifi NAME\|PASSWORD` | Saves the network and restarts |
| `ap-pass` | Shows the access point name and password |
| `forget` | Erases the saved network and restarts |
| `ble off` / `ble on` | Stops / restarts Bluetooth (simulates the night standby) |
| `ps none\|min\|max` | Wi-Fi power saving. `none` only with Bluetooth stopped (ESP32 constraint) |
| `reboot` | Restarts |

## Tests

1. **Latency**: `python3 tools/latency.py dumbyluba-esp32.local --duration 300` (options: `--port` 8767,
   `--duration` 60 s by default, `--interval` 0.25 s, `--json` for a JSON summary; a 32-byte frame every 250 ms).
   Compare with Bluetooth active, then `ble off` + `ps none`. Criterion: no response lost or beyond 2 s.
2. **Battery** (18650 holder with JST PH 2.0 plug, polarity checked): unplug and replug USB-C,
   `uptime_s` must keep counting and `reset_reason` stay unchanged; `vin_present` goes to `false`.
3. **Low power**: on battery, `ble off` + `ps max` for 5 min: the board must stay reachable (the
   kept-on output prevents the IP5306 from cutting off).
4. **Dock charging**: 5 V brought to the USB-C by the dock contacts (enclosure blades on the plates): `vin_present`
   goes to `true`.

## Observed on 2026-10-12 (board on USB, no battery)

- ESP32-D0WD rev 1, 240 MHz, 4 MB flash, 4 MB usable PSRAM.
- IP5306 present, kept-on output enabled (register 0x00 = 0x37), USB power detected.
- Access point open, Bluetooth active: 13 to 15 devices seen per scan, LUBA not visible
  (held by the Redmi).
- Commands `ble off`, `ps none`, `ps min`, `ble on` run without a restart.
- Not checked: connection to the home Wi-Fi (credentials to enter), latency, running on battery, charging through the dock contacts.
- `temp_c_indicatif`: the internal sensor of the classic ESP32 is unreliable, the value is indicative only.
