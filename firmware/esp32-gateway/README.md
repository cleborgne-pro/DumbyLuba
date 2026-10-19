# ESP32 gateway (LILYGO TTGO T-Call V1.3)

Replaces the Redmi mounted on the robot ([ANDROID_GATEWAY.md](../../docs/ANDROID_GATEWAY.md)) with the
**same protocol** (version 2): nothing changes on the Fold, it stays in "Control via the gateway".

```
Fold (planning, safety) ── Wi-Fi TCP 8766 ── ESP32 (on the robot) ── Bluetooth ── LUBA 2
```

- **Connecting to the robot from the Fold**: Service → Wi-Fi gateway → Control via the gateway, the ESP32's
  six-digit code, "Find and test", then search for the robot: the Bluetooth scan is done by the ESP32; tap
  the robot. The ESP32 connects to it (no pairing), retains it (stored on the board) and reconnects to it by
  itself afterwards: power cut, restart, robot switched back on (new attempt every 10 s). A Bluetooth scan lasts
  6 s.
- **Relay**: BluFi frames from the Fold written to FF01 one at a time with confirmation, sequence rewritten by
  the gateway (a single sequence with its own frames), FF02 notifications forwarded starting from the beginning
  of a message. Each write must be confirmed within 1.5 s. Sync every 5 s when no pilot is connected.
- **Safety stop**: Fold silent for more than 2 s, or Wi-Fi cut without an intentional close → blades OFF if they
  may be turning (ON command not followed by OFF, or blade bits in the telemetry), then zero speed, each frame
  confirmed; Bluetooth link kept. Fragmented message in progress: link closed, stop reported as
  "not guaranteed". The report is handed to the pilot when it comes back (`emergency_stop` event, 30 min).
- **One session at a time**; another phone receives the protocol's busy refusal (`gw::proto::BUSY`, a fixed wire text matched by the app), shown as "Gateway busy with another pilot". The same phone coming
  back waits for its previous session to end (PING, 8 s at most).
- **Authentication**: random challenge, HMAC-SHA256 response with the code (never sent in clear), 5 wrong
  attempts per minute. Unencrypted traffic: home Wi-Fi only.
- **mDNS advertisement** `_dumbyluba._tcp` (and `_http._tcp`), instance "DumbyLuba passerelle ESP32"; status page
  `http://dumbyluba-esp32.local/` (see [Web pages](#web-pages)).
- **Health** reported to the Fold every 5 s: battery level (IP5306: 100/75/50/25/0 %), power present
  (shown as "charging"), Wi-Fi signal. No temperature or thermal state (−1), no accelerometer (no terrain).
- The board's 2G modem is kept powered off; the IP5306 output is kept on so that the board stays powered on battery.

- **Update over Wi-Fi** (version 0.2.0): `pio run -e ota -t upload`, see [below](#update-over-wi-fi).
  `ota` capability announced in WELCOME; the Fold sends its "Updates during mowing" setting (CONFIG frame
  0x61, never sent to a gateway that does not announce it).

- **No autonomous tracking**: the ESP32 does not announce the `autonomy` capability (WELCOME), so the Fold drives it
  frame by frame and a Wi-Fi silence of more than 2 s stops the robot. The Redmi's autonomous tracking
  ([AUTONOMOUS_GATEWAY.md](../../docs/AUTONOMOUS_GATEWAY.md)) is not ported to the ESP32.

## Setup

1. Build and upload (board on USB):
   ```sh
   python3 -m venv /tmp/espvenv && /tmp/espvenv/bin/pip install platformio
   cd firmware/esp32-gateway && /tmp/espvenv/bin/pio run -e tcall -t upload
   ```
   The Wi-Fi saved by the diagnostic program is reused. Otherwise (no saved network, or not connected after
   20 s): access point `DumbyLuba-ESP32-xxxx` (last four hex digits of the MAC address), page
   `http://192.168.4.1/wifi` (any other address redirects there). Password unique to the board (12 characters,
   no ambiguous ones), generated at first boot and shown on the serial console (`ap-pass` to show it again). Or from
   the console: `wifi NAME|PASSWORD`. The access point closes 60 s after the board has joined the network; without
   Wi-Fi, the saved network is retried every 30 s (unless someone is connected to the access point).
2. **Code**: shown on the serial console at boot (`code` to show it again, `code NNNNNN` to change it, for
   example to reuse the one already entered on the Fold).
3. **Stop the Redmi gateway** (the robot accepts only one link, and the Fold first tries the stored
   address): on the Redmi, Service → Wi-Fi gateway → **Stop**, or **Release the robot** (Service → Bluetooth
   connection).
4. On the Fold: Service → Wi-Fi gateway → Control via the gateway, the ESP32's code, **Find and test**
   (the address is found over mDNS), then search for and tap the robot.

## Update over Wi-Fi

Once version 0.2.0 has been installed over USB, the cable is no longer needed:

```sh
cd firmware/esp32-gateway
DUMBYLUBA_CODE=123456 /tmp/espvenv/bin/pio run -e ota -t upload   # code: DUMBYLUBA_CODE, otherwise prompted
# host: DUMBYLUBA_HOST (default dumbyluba-esp32.local); upload only: python3 tools/ota_upload.py firmware.bin --host IP [--code NNNNNN] [--no-wait]
```

1. **Challenge and proof**: `GET /ota/nonce` returns a single-use challenge (60 s) and says whether the update is
   accepted. The image is then sent to `POST /ota` with `HMAC-SHA256(code, "DumbyLuba-ota-v1" + challenge + md5)`:
   the code is never transmitted, and the image is verified (MD5) before being activated.
2. **While piloted** (the Fold holds the robot, mowing in progress): refused if the app setting
   "Updates during mowing" is off (default). When on: the gateway announces the restart to the Fold
   (`ota_begin` event), waits 3 s, closes the session with the usual safety stop (blades OFF, zero speed),
   refuses new connections, writes the image and restarts. The retained robot is reconnected automatically;
   the Fold resumes mowing from its saved progress (6 min at most, 3 attempts).
3. **Confirmation**: the new version is only confirmed after running 60 s with Wi-Fi. If it crashes before
   that, or stays 3 min without Wi-Fi, the board rolls back to the previous version by itself.
4. `tools/ota_upload.py` waits for the restart and then the confirmation, and exits with 0 (confirmed),
   2 (refused) or 1.

Status: `ota` block of `/status` (partition, `pending_verify`/`valid`, setting, last report), serial
command `ota`. While writing (~12 s), the board no longer answers over Wi-Fi.

**LED** (GPIO 13): slow blink (1 s) = robot connected; medium (0.4 s) = Wi-Fi only; fast (0.12 s) = no Wi-Fi.

## Web pages

- `/`: status page refreshed every 5 s: **Robot** ("No robot: pick it from the Fold (Control via the gateway)",
  "… connected over Bluetooth · piloted from the Fold", "… waiting for the pilot phone", "Bluetooth connection to …",
  "… off, out of range or held by another device: retrying automatically"), **Pilot**, **Last safety stop**, button
  **Release the robot** (Mammotion app, direct Bluetooth; no more automatic reconnection), links to Wi-Fi and JSON,
  and the full status. Redirects to `/wifi` while only the access point is up.
- `/status`: status as JSON (firmware, uptime, memory, power, Wi-Fi, robot, pilot, `ota`).
- `/wifi`: network list with signal, or a typed name, and password; **Save** restarts the board on that network.
- `/robot/release` (POST), `/ota/nonce` and `/ota`: release and update.

## Serial console (115,200 baud)

| Command | Effect |
|---|---|
| `status` | Full state as JSON (same content as `/status`) |
| `code` / `code NNNNNN` | Shows / changes the six-digit code |
| `release` | Releases the robot (Mammotion app, direct Bluetooth) and forgets it |
| `wifi NAME\|PASSWORD` / `forget-wifi` | Wi-Fi network (saved, then restart) |
| `ap-pass` | Shows the access point name and password again |
| `reboot` | Restarts |
| `ota` / `ota-mowing on\|off` | Update-over-Wi-Fi status / "during mowing" setting (normally sent by the Fold) |

A state line is printed every 30 s (Wi-Fi, robot, pilot, power, free memory).

## Tests

- `pio test -e native` (23 tests): BluFi (splitting, reassembly, CRC, sequence loss), protobuf encoding of the stop and
  sync frames, blade tracking, refusal to inject in the middle of a message, TCP protocol, attempt limiting.
  The stop, blades OFF and sync bytes are identical to those of `Commands.kt` (checked by running the app's
  compiled classes).
- `tools/gateway_client.py` plays the role of the Fold (Python standard library):
  `HOST --code NNNNNN [--port 8766]`, then `check` (code, health), `scan`, `open ADDRESS [--syncs 20] [--timeout 40]`
  (link, syncs, Wi-Fi and Bluetooth latencies),
  `open ADDRESS --silence` (goes silent: the gateway must write the safety stop, read back on reconnection).
  It only sends syncs and, with `--stop`, a zero speed. `--ota-mowing on|off` sends the update setting;
  `hold ADDRESS [--seconds 120]` holds the robot like a Fold while mowing and shows the `ota_begin` announcement.

## Status as of 2026-10-15: update over Wi-Fi (0.2.0)

On the board (T-Call on USB for power, Wi-Fi −31 to −55 dBm), from the Mac:

- 0.2.0 installed once over USB; 18 core tests passed (including rules, challenge, WELCOME and CONFIG).
- Wrong code: image refused (HTTP 403, "Wrong gateway code").
- `pio run -e ota -t upload`: write in 12 to 14 s, back in 16 to 27 s on the other partition (`pending_verify`),
  confirmed 60 s later; final version installed this way (partition app1).
- Pilot holding the robot (`hold`, dummy address), setting off: refused with "Robot being piloted…".
- Same with the setting on: `ota_begin` received, session closed 3.0 s later, update done, board confirmed.
- Image that crashes at boot: the board rolls back to the old partition by itself (`valid`).
- **Not tried**: safety stop written on a real robot before the update, and the Fold resuming mowing
  (robot held by the Redmi during the tests).

## Status as of 2026-10-12

- Built (RAM 18 %, flash 61 %), 14 core tests passed, HMAC proof identical to the app's.
- **On the board (T-Call on USB, Wi-Fi −33 dBm), with `tools/gateway_client.py` from the Mac**:
  - boot, IP5306 output kept on, diagnostic Wi-Fi reused, mDNS advertisement "DumbyLuba passerelle ESP32"
    seen next to the Redmi's;
  - right code accepted with health received, wrong code refused ("Wrong gateway code");
  - scan: 6 s, end of scan received, no robot (held by the Redmi);
  - OPEN to an absent address: "connecting" then LOST 8 "connection not answered" after 20 s,
    session closed cleanly; "Release the robot" (web page) erases the retained address;
  - admission: a second connection from the same computer waits (PING) and goes through when the first ends.
- **Not tried with the robot** (held by the Redmi): real connection, relaying writes and notifications,
  latency, stop on silence, then real piloting from the Fold.
- No night standby yet (Bluetooth off from 21:00 to 09:00).
