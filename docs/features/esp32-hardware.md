# ESP32 hardware: gateway, diagnostics and inductively charged enclosure

The repository contains two PlatformIO firmwares for an ESP32 board and a printable waterproof enclosure, charged
wirelessly when the robot is at the base.

| | |
|---|---|
| **Firmwares** | [`firmware/esp32-gateway`](../../firmware/esp32-gateway/README.md) (gateway) · [`firmware/esp32-diag`](../../firmware/esp32-diag/README.md) (board diagnostics) |
| **Enclosure** | [`hardware/luba-esp32-qi`](../../hardware/luba-esp32-qi/README.md): STL, parametric CAD, 3D viewer |
| **Status** | Gateway built and tested on the board (23 native core tests; update over Wi-Fi tested on the board on Oct 15), **not yet tried with the robot** (robot held by the Redmi during the tests); enclosure designed, **not yet mounted on the robot** |

## Boards

- The gateway firmware builds for two boards (firmware 0.5.0): the **Olimex ESP32-S3-DevKit-Lipo** (EA variant,
  ESP32-S3-WROOM-1(U)-N8R8: 8 MB flash, 8 MB octal PSRAM, u.FL antenna; environment `s3lipo`, the default) and the
  **LILYGO TTGO T-Call V1.3** (ESP32-WROVER-B; environment `tcall`, boards in service). The Olimex board has **not
  been tried yet** (not received): the firmware builds for it, nothing more is known. The diagnostics firmware stays
  T-Call only (PlatformIO `esp32dev`). Both use NimBLE-Arduino.
- Olimex board: 18650 cell on a 2-pin, 2.0 mm connector (probably JST PH, to check), charged at about 100 mA (BL4054B,
  about 34 h for an empty 3,400 mAh cell) from either USB-C port or a 5 V Qi receiver. **The board has no cell
  protection: use a protected 18650**, and **check the polarity with a multimeter** before plugging it in. No
  temperature input on the charger; board specified up to 65 °C ambient. Details: [firmware README](../../firmware/esp32-gateway/README.md#olimex-board-battery-and-wiring).
- Toolchains: the gateway is built with **arduino-esp32 3.3.12 (ESP-IDF 5.5.5) and NimBLE-Arduino 2.5.2** through the
  community platform **pioarduino 55.03.312-1** (pinned in `platformio.ini`; PlatformIO Core 6.2.0 or later); the
  diagnostics firmware stays on `espressif32@6.9.0` (arduino-esp32 2.0.17, IDF 4.4.7, NimBLE-Arduino 1.4.3).
- The enclosure holds the **Olimex ESP32-S3-DevKit-Lipo-EA**, the board the gateway firmware targets with the
  environment `s3lipo` (`ota-s3lipo` over Wi-Fi): see [Inductively charged enclosure](#inductively-charged-enclosure).

## Installing PlatformIO

```sh
python3 -m venv /tmp/espvenv && /tmp/espvenv/bin/pip install 'platformio>=6.2.0'
```

The gateway's first build downloads about 4 GB (pioarduino toolchain and precompiled IDF 5.5 libraries). To keep them
apart from the diagnostics firmware's packages, prefix the gateway commands with
`PLATFORMIO_CORE_DIR=/tmp/esp32-idf5-pio` (any directory of your own).

## ESP32 gateway

```sh
cd firmware/esp32-gateway
/tmp/espvenv/bin/pio run -e s3lipo -t upload  # Olimex board: build and upload (USB-UART port)
/tmp/espvenv/bin/pio run -e tcall -t upload   # T-Call: build and upload (USB)
DUMBYLUBA_CODE=123456 /tmp/espvenv/bin/pio run -e ota-s3lipo -t upload   # Olimex board over Wi-Fi (0.5.0 installed)
DUMBYLUBA_CODE=123456 /tmp/espvenv/bin/pio run -e ota -t upload   # T-Call over Wi-Fi (firmware 0.2.0 already installed)
/tmp/espvenv/bin/pio device monitor           # serial console, 115200 baud
/tmp/espvenv/bin/pio test -e native           # core tests on the computer
```

1. **Wi-Fi**: on a T-Call, the network saved by the diagnostics firmware is reused. With no saved network (or after 20 s of
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
| `wifi-ps` / `wifi-ps none\|min` | Wi-Fi power save of the station: show / set (stored, default `none`) |

- Status page: `http://dumbyluba-esp32.local/`, refreshed every 5 s (robot, pilot, last safety stop, **Release the
  robot** button); `/status` as JSON; `/wifi` network setup.
- LED (T-Call GPIO 13, Olimex board GPIO 38): slow blinking = robot connected, medium = Wi-Fi only, fast = no Wi-Fi.
- Update over Wi-Fi: single-use challenge, HMAC proof of the code, image checked by MD5; the new version is kept after
  60 s with Wi-Fi, otherwise the board rolls back by itself. During mowing it is refused unless the pilot's **Updates
  during mowing** setting is on ([gateway](gateway.md#esp32-gateway),
  [firmware README](../../firmware/esp32-gateway/README.md#update-over-wi-fi)).
- Route tracking (firmware 0.4.0): the tracking and the route decoding run on two workers created at boot
  (`gw_worker.h`), so starting a tracking no longer needs a 20 KB block of internal RAM (refused twice on
  2026-10-25 after a long route); envelope surface indexes live in PSRAM (`gw_alloc.h`) and the last 4 routes and
  surfaces of each kind are kept, as the pilot assumes. Robot notifications are handled on their own task
  (`robot-notify`), no longer behind the GATT writes, queued writes go out back to back, and the Bluetooth link
  asks for a 15-30 ms connection interval (jerky tracking of 2026-10-25). Wi-Fi joins the strongest access point
  of the network (all-channel scan) at full transmit power. `/status` adds `heap_largest_internal`, the free stack
  of each task (`tasks`), and the Wi-Fi channel and last disconnect reason. Each pass of the pilot session sends
  its frames (HEALTH, robot responses, PING) in a single write, one TCP segment and one radio frame instead of one per
  frame; the station uses a 20 MHz channel (HT20); `/status` gives the last and the longest Wi-Fi outage since boot
  (`wifi.last_outage_ms`, `wifi.max_outage_ms`, from the loss to the new IP address) to compare with the pilot's 2 s
  silence limit; a 1 KB serial transmit buffer keeps the console lines from stalling the tasks.
- Wi-Fi without power save (firmware 0.4.0): the gateway moved to arduino-esp32 3.x
  (ESP-IDF 5.5) and NimBLE-Arduino 2.x, because IDF 4.4 refuses Wi-Fi without modem sleep while Bluetooth runs, and
  modem sleep makes the station wake only at the router's DTIM beacons (the main cause of receive latency). The
  station now runs with `WIFI_PS_NONE` by default; `wifi-ps none|min` on the console switches between the two to
  compare them on the board (stored), a refused `none` is logged and falls back to `min`, and `/status` gives the
  mode in force (`wifi.ps`). Wi-Fi and Bluetooth still share the radio in time slices, so the gain is to be measured.
  Protocol, safety stop, PWR pairing, Bluetooth parameters and update over Wi-Fi are unchanged. A board in 0.3.1
  takes this image over Wi-Fi with its old IDF 4.4.7 bootloader, which boots newer IDF apps and keeps the rollback:
  tried on the test board on 2026-10-27 (0.3.1 and its IDF 4.4.7 bootloader rewritten over USB, NVS kept). A reset
  before the 60 s confirmation brought 0.3.1 back; the next update was confirmed after 60 s (`gateway-0.4.0`,
  `wifi.ps: none`, internal RAM 74,392 bytes free, lowest 72,096), with Wi-Fi network, code and retained robot kept;
  the bootloader stays the IDF 4.4.7 one, as on boards in service
  ([firmware README](../../firmware/esp32-gateway/README.md#toolchain-arduino-esp32-3x-esp-idf-55)). Image 95.4 % of
  the OTA slot with the precompiled libraries (78.1 % before), 83.5 % with the configuration below.
- Internal RAM (firmware 0.4.0): the gateway is built with an ESP-IDF configuration of its own (pioarduino rebuilds the
  Arduino IDF libraries, about 3 min on the first build): Wi-Fi and lwIP buffers and `malloc` blocks over 256 bytes in
  PSRAM, Bluetooth controller in BLE-only mode for one link without the Bluedroid host, smaller timer task stacks,
  unused Arduino components (Zigbee, RainMaker, Insights, Matter at link time...) left out. In a trial of three 990 KB
  routes and three surfaces on 2026-10-26, the lowest internal RAM went from 548 bytes to 61 KB (74 KB free at rest
  instead of about 22 KB), static RAM from 78.0 KB to 63.3 KB, IRAM left from 2.6 KB to 13 KB, image from 95.4 % to
  83.5 % of the OTA slot. The robot was not connected (not advertising that day): its share is still to be measured.
  `/status` adds `heap_internal_free` and `heap_internal_min` (byte-addressable internal RAM, what the task stacks
  use). Options, rejected leads and figures:
  [firmware README](../../firmware/esp32-gateway/README.md#esp-idf-configuration-of-the-gateway-internal-ram).
- Two boards (firmware 0.5.0): `src/board.h` holds what differs (2G modem off and IP5306 on the T-Call; LED; power
  reading). On the Olimex board the cell voltage is read on BAT_SENS (GPIO 6) and 5 V presence on PWR_SENS (GPIO 5);
  the level comes from a conservative curve (`gw_battery`, native tests: 0 % at 3.40 V, 4 % or less below 3.50 V) so
  that the gateway's cutting rule (4 % or less with no power: blades forbidden) comes before the cut-off of a
  protected cell; with 5 V present the level is unknown (the pin then reads the charger, 4.2 V even with no cell).
  `/status` adds `board` (`tcall` or `s3lipo`) and `power.battery_mv` (−1 on the T-Call); the IP5306 fields stay on
  the T-Call only. Olimex build: 8 MB partition table `ota_8mb.csv` with two 4,128,768-byte OTA slots (image at
  38.9 %), ESP-IDF configuration of the T-Call carried over to the ESP32-S3 keys
  ([firmware README](../../firmware/esp32-gateway/README.md#esp-idf-configuration-of-the-gateway-internal-ram)). T-Call
  image unchanged apart from the moved board code (same IDF configuration, partitions and bootloader). Building one
  board after the other rebuilds the Arduino IDF libraries (108-142 s measured instead of 35-37 s, see the
  [firmware README](../../firmware/esp32-gateway/README.md#esp-idf-configuration-of-the-gateway-internal-ram)).
- Test tool that plays the role of the pilot phone:
  `python3 firmware/esp32-gateway/tools/gateway_client.py HOST --code 123456 check|scan|open ADDRESS [--silence] [--stop]|hold ADDRESS`.

Same protocol, safety stop and autonomous tracking (firmware 0.3.0, not tried on the robot yet) as the
[Android gateway](gateway.md), but without terrain.

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
- The enclosure now holds an **Olimex ESP32-S3-DevKit-Lipo-EA** (ESP32-S3-WROOM-1U N8R8 with a u.FL socket, two
  USB-C, Li-ion charger, no battery protection, no mounting holes). The board clips into a cradle printed with the base
  (corner ledges, end stops, two snap arms), with no soldering. A **protected** 18650 sits in a separate holder with
  leads and a JST plug: **check its polarity with a multimeter** before plugging it in. A flexible 2.4 GHz FPC antenna
  on a u.FL cable is bonded inside the end wall, about 24 mm from the Qi patch and 33 mm from the cell, with nothing
  through the wall. The Qi patch receiver plugs into one USB-C socket (native USB); the other, the USB-UART port
  (CH340X) used for the console and flashing, stays free with the lid open. The inside is 6 mm wider (enclosure
  82.2 × 136.2 × 32.2 mm). Height, seal, window, coil distance (4.9 mm) and the fixed transmitter mount are unchanged.
  The enclosure was first drawn for the LILYGO T-Energy S3.
- Estimated runtime: about 22–29 h with Wi-Fi without power saving and BLE, not measured; charging runs at about
  100 mA (about 34 h for an empty 3,400 mAh cell). The module is rated for 65 °C ambient, so print the enclosure in
  light-colored ASA. About €105 to €135 of parts and 300 g of ASA.
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
