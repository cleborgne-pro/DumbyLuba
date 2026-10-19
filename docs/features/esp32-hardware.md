# ESP32 hardware: gateway, diagnostics and inductively charged enclosure

The repository contains two PlatformIO firmwares for an ESP32 board and a printable waterproof enclosure, charged
wirelessly when the robot is at the base.

| | |
|---|---|
| **Firmwares** | [`firmware/esp32-gateway`](../../firmware/esp32-gateway/README.md) (gateway) · [`firmware/esp32-diag`](../../firmware/esp32-diag/README.md) (board diagnostics) |
| **Enclosure** | [`hardware/luba-esp32-qi`](../../hardware/luba-esp32-qi/README.md): STL, parametric CAD, 3D viewer |
| **Status** | Gateway built and tested on the board (23 native core tests; update over Wi-Fi tested on the board on Oct 15), **not yet tried with the robot** (robot held by the Redmi during the tests); enclosure designed, **not yet mounted on the robot** |

## Boards

- Both firmwares target the **LILYGO TTGO T-Call V1.3** (ESP32-WROVER-B, PlatformIO `esp32dev`, NimBLE-Arduino).
- The enclosure is designed for a **LILYGO T-Energy S3** (ESP32-S3, built-in 18650 holder). The firmwares have not
  yet been ported to this board: contributions welcome.

## Installing PlatformIO

```sh
python3 -m venv /tmp/espvenv && /tmp/espvenv/bin/pip install platformio
```

## ESP32 gateway

```sh
cd firmware/esp32-gateway
/tmp/espvenv/bin/pio run -e tcall -t upload   # build and upload (USB)
DUMBYLUBA_CODE=123456 /tmp/espvenv/bin/pio run -e ota -t upload   # over Wi-Fi (firmware 0.2.0 already installed)
/tmp/espvenv/bin/pio device monitor           # serial console, 115200 baud
/tmp/espvenv/bin/pio test -e native           # core tests on the computer
```

1. **Wi-Fi**: the network saved by the diagnostics firmware is reused. With no saved network (or after 20 s of
   failure), the board opens the access point `DumbyLuba-ESP32-xxxx`. Its password, specific to the board, is
   generated at first boot and shown on the console (`ap-pass` to show it again). Configure the network at
   `http://192.168.4.1/wifi`, or from the console: `wifi NAME|PASSWORD`. The access point closes one minute after the
   board joins the network.
2. **Code**: shown on the console at startup (`code` to show it again, `code NNNNNN` to change it).
3. On the pilot phone: Service → Wi-Fi gateway → **Control via the gateway**, the ESP32's code, **Find and test**,
   then search for and tap the robot.

| Console | Effect |
|---|---|
| `status` | full status as JSON (like `/status`) |
| `code` / `code NNNNNN` | read / change the code |
| `release` | release the robot and forget it |
| `wifi NAME\|PASSWORD` / `forget-wifi` | Wi-Fi network |
| `ap-pass` | show the access point password again |
| `reboot` | restart |
| `ota` / `ota-mowing on\|off` | update status / "Updates during mowing" setting (normally sent by the pilot) |

- Status page: `http://dumbyluba-esp32.local/`, refreshed every 5 s (robot, pilot, last safety stop, **Release the
  robot** button); `/status` as JSON; `/wifi` network setup.
- LED (GPIO 13): slow blinking = robot connected, medium = Wi-Fi only, fast = no Wi-Fi.
- Update over Wi-Fi: single-use challenge, HMAC proof of the code, image checked by MD5; the new version is kept after
  60 s with Wi-Fi, otherwise the board rolls back by itself. During mowing it is refused unless the pilot's **Updates
  during mowing** setting is on ([gateway](gateway.md#esp32-gateway),
  [firmware README](../../firmware/esp32-gateway/README.md#update-over-wi-fi)).
- Test tool that plays the role of the pilot phone:
  `python3 firmware/esp32-gateway/tools/gateway_client.py HOST --code 123456 check|scan|open ADDRESS [--silence] [--stop]|hold ADDRESS`.

Same protocol and same safety stop as the [Android gateway](gateway.md), but without autonomous tracking or terrain.

## ESP32 diagnostics

A program that checks the board before the gateway is installed on it; it **does not control the robot**. It checks
the charging circuit (IP5306), Wi-Fi (signal, disconnections), Bluetooth discovery of the LUBA (without
connecting), latency at the gateway's PING rate (TCP echo on port 8767) and unexpected reboots. Console commands:
`status`, `wifi NAME|PASSWORD`, `forget`, `ap-pass`, `ble off`/`ble on`, `ps none|min|max` (Wi-Fi power saving),
`reboot`; status page `http://dumbyluba-esp32.local/`.

```sh
cd firmware/esp32-diag
/tmp/espvenv/bin/pio run -t upload
python3 tools/latency.py dumbyluba-esp32.local --duration 300
```

## Inductively charged enclosure

- Waterproof enclosure glued behind the camera of the LUBA 2 AWD, below the top of the camera and next to the rain
  sensor.
- A **fixed mount** under the garage roof carries the Qi transmitter. Hung from two M6 rods, it can be adjusted to
  the millimeter (one turn of the nut = 1 mm) so that it sits 2.5 mm above the lid when the robot docks in reverse:
  4.9 mm between the coils (Qi range 2 to 8 mm). The robot must therefore come back to the same height within about
  ±3 mm, which remains to be measured on site.
- No soldering: everything plugs in over USB, screws or glues together. About €100 to €130 of parts and 300 g of ASA.
- Waterproofing: silicone cord seal in a sized groove (25 % compression), 6 screws outside the seal, drip lip on the
  lid, ePTFE vent. An optional base plate compensates for the curvature of the hood once measured.
- Print-ready parts in `stl/` (enclosure base and lid, transmitter mount and its cap, roof bridge; deck shim only if
  the deck is crowned). 3D viewer in `viewer/luba-esp32-qi.html` (model embedded, three.js loaded from a CDN: a
  connection is needed when opening it), designed for the Galaxy Z Fold 8 (cover screen, inner screen, Flex mode):
  modes *On the robot*, *Enclosure*, *+ Qi mount*, exploded view, cross-section, annotated details, a sheet for each
  part, docking animation, dimensions report. Regenerating the parts (the script refuses to produce a result when a
  clearance or seal check fails):

  ```sh
  python3 -m venv /tmp/cadvenv
  /tmp/cadvenv/bin/pip install -r hardware/luba-esp32-qi/cad/requirements.txt
  /tmp/cadvenv/bin/python -I hardware/luba-esp32-qi/cad/generate.py
  ```

![Enclosure overview](../../hardware/luba-esp32-qi/images/01_overview.png)
