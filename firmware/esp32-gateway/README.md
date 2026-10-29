# ESP32 gateway (Olimex ESP32-S3-DevKit-Lipo, LILYGO TTGO T-Call V1.3)

Replaces the Redmi mounted on the robot ([ANDROID_GATEWAY.md](../../docs/ANDROID_GATEWAY.md)) with the
**same protocol** (version 2): nothing changes on the Fold, it stays in "Control via the gateway".

```
Fold (planning, safety) ── Wi-Fi TCP 8766 ── ESP32 (on the robot) ── Bluetooth ── LUBA 2
```

- **Connecting to the robot from the Fold**: Service → Wi-Fi gateway → Control via the gateway, the ESP32's
  six-digit code, "Find and test", then search for the robot: the Bluetooth scan is done by the ESP32; tap
  the robot (every Mammotion mower is listed, as by the Android gateway: Luba, Yuka, Ezy-, Kumar, Maston). The
  ESP32 connects to it (no pairing), retains it (stored on the board) and reconnects to it by
  itself afterwards: power cut, restart, robot switched back on (new attempt every 10 s), while it is in use
  (pilot connected, autonomous tracking) and for 10 min after; beyond, it waits for the next pilot (`retry_paused`
  in `/status`), leaving the robot to the device using it (Redmi gateway, Mammotion app). A Bluetooth scan lasts
  6 s; a scan refused by the Bluetooth stack is reported to the pilot (FAILURE "Scanner Bluetooth indisponible"),
  not as an empty list. A scan requested while the gateway is trying to reach its retained robot (off or out of
  range: up to 2 × 10 s) cancels that attempt, retried after the scan, so that the end of scan reaches the app
  within its 12 s.
- **Relay**: BluFi frames from the Fold written to FF01 one at a time with confirmation, sequence rewritten by
  the gateway (a single sequence with its own frames), FF02 notifications forwarded starting from the beginning
  of a message. Each write must be confirmed within 1.5 s. Sync every 5 s when no pilot is connected.
- **Safety stop**: Fold silent for more than 2 s, or Wi-Fi cut without an intentional close → blades OFF if they
  may be turning (ON command not followed by OFF, or blade bits in the telemetry), at the commanded or reported
  height (20 to 100 mm, every model including the H versions, as `CutHeightRange.ANY`; 60 mm if unknown), then zero
  speed, each frame confirmed; Bluetooth link kept. Fragmented message in progress: link closed, stop reported as
  "not guaranteed". The report is handed to the pilot when it comes back (`emergency_stop` event, 30 min).
- **One session at a time**; another phone receives the protocol's busy refusal (`gw::proto::BUSY`, a fixed wire text matched by the app), shown as "Gateway busy with another pilot".
- **Protocol texts** (refusals, failures, link losses, stop reports: `gw::proto::wire`): in French byte for byte, the
  same as the Android gateway's, whatever the language; the app compares some of them ("occupée", "STOP écrit") and
  translates the known ones into the pilot phone's language (`GatewayWireText`). Console, logs and web pages are in
  English. The same phone coming
  back waits for its previous session to end (PING, 8 s at most).
- **Authentication**: random challenge, HMAC-SHA256 response with the code (never sent in clear), 5 wrong
  attempts per minute. Unencrypted traffic: home Wi-Fi only.
- **mDNS advertisement** `_dumbyluba._tcp` (and `_http._tcp`), instance "DumbyLuba passerelle ESP32"; status page
  `http://dumbyluba-esp32.local/` (see [Web pages](#web-pages)).
- **Health** reported to the Fold every 5 s: battery level (T-Call: IP5306, 100/75/50/25/0 %; Olimex board: from the
  cell voltage, unknown while 5 V is present, see [Boards](#boards)), power present (shown as "charging"), Wi-Fi
  signal. No temperature or thermal state (−1), no accelerometer (no terrain).
- T-Call: the board's 2G modem is kept powered off; the IP5306 output is kept on so that the board stays powered on
  battery.

- **Update over Wi-Fi** (version 0.2.0): `pio run -e ota -t upload`, see [below](#update-over-wi-fi).
  `ota` capability announced in WELCOME; the Fold sends its "Updates during mowing" setting (CONFIG frame
  0x61, never sent to a gateway that does not announce it).

- **Autonomous tracking** (version 0.3.0): `autonomy` and `dock-exit` capabilities announced in WELCOME. The Fold
  hands the route and the free physical surface over once, then the ESP32 follows them on its own with the same
  loop and guards as the Redmi ([AUTONOMOUS_GATEWAY.md](../../docs/AUTONOMOUS_GATEWAY.md#esp32-gateway-2026-10-23)):
  a Wi-Fi outage no longer stops the robot (stop after 20 min without a pilot), progress and survey samples are
  replayed when the pilot returns, official Mammotion docking requested when a return ends with no pilot. A pilot
  write or a deliberate departure stops the tracking; so does **Stop the autonomous tracking** on the gateway page.
  No update over Wi-Fi and no **Release the robot** while a tracking runs. Routes and surfaces of 1.5 MB at most.

## Boards

Firmware 0.5.0 builds for two boards, one PlatformIO environment each (`src/board.h` holds what differs):

| | Olimex ESP32-S3-DevKit-Lipo (`s3lipo`, default) | LILYGO TTGO T-Call V1.3 (`tcall`) |
|---|---|---|
| Module | ESP32-S3-WROOM-1(U)-N8R8: 8 MB quad flash, 8 MB octal PSRAM; EA variant with a u.FL antenna connector | ESP32-WROVER-B: 4 MB flash, 4 MB PSRAM (ESP32-D0WD revision 1.0 on the test board) |
| PlatformIO board | `esp32-s3-devkitc1-n8r8` (same memory, `qio_opi`) with the pin names of the Arduino variant `esp32s3-devkit-lipo`: PlatformIO has no board file for the Olimex ESP32-S3 | `esp32dev` |
| Partitions | `ota_8mb.csv`: two OTA slots of 4,128,768 bytes, NVS 20 KB, core dump 64 KB, no SPIFFS | `min_spiffs.csv`: two OTA slots of 1,966,080 bytes |
| Upload and console | USB-UART port (CH340X on UART0, auto-reset); the native USB port also powers and charges the board | USB |
| Power reading | cell voltage on BAT_SENS (GPIO 6, 470 k / 150 k divider), 5 V present on PWR_SENS (GPIO 5); charger state not readable | IP5306 over I2C: level in 25 % steps, power present, charge full |
| LED | GPIO 38, green, lit when LOW | GPIO 13 |
| Pairing window opened by | a reset through EN (reset button, if the board has one: to check; auto-reset of the USB-UART port when a console opens) or a power-up | the PWR button or a power-up |
| Status | builds; **not tried**: board not received yet | in service |

The diagnostics firmware (`firmware/esp32-diag`) stays T-Call only.

### Olimex board: battery and wiring

- Charger BL4054B (linear, 4.2 V, about 100 mA as read from the schematic) with a power path: the 5 V of either USB-C
  port (in the enclosure, the 5 V of the dock contacts through a USB-C plug) supplies the board and charges the cell
  beside it. **The board has no cell protection** (no over-discharge, over-current or short-circuit cut-off): use a
  **protected 18650** only. No temperature input on
  the charger: nothing stops charging in heat or frost (most cells are specified for charging between 0 and 45 °C,
  see the cell's datasheet); the board itself is specified up to 65 °C ambient.
- Battery connector: 2 pins, 2.0 mm pitch (probably JST PH: to check). **Check the polarity with a multimeter before
  plugging the cell in**: lead orders differ between suppliers.
- Charge time: at about 100 mA an empty 3,400 mAh cell needs about 34 h, plus the slower end of charge (constant
  voltage).
- Level: 16 ADC samples averaged (`analogReadMilliVolts`, 2.5 dB attenuation set globally with `analogSetAttenuation`
  before the first read, so that the calibration is made for 2.5 dB too: 1.02 V at the pin for 4.2 V) and
  converted by `gw_battery` (gwcore, native tests): a conservative curve read under the board's own load, 0 % at
  3.40 V, 4 % or less below 3.50 V, 100 % from 4.18 V, so that the cutting rule of the gateway (board battery at 4 %
  or less with no power: blades forbidden, blades-OFF moves allowed) comes well before the cut-off of a protected
  cell (2.5-3.0 V). A voltage outside 2.8-4.5 V gives an unknown level. With 5 V present the pin reads the charger
  output (4.2 V even with no cell fitted): the level is then reported unknown (−1, "—" in the app) and the power as
  present ("charging"); `battery_mv` still gives the reading. Charge full is not known (CHRG not wired to the ESP32).
  The refusal sent to the pilot keeps the Android gateway's text ("Batterie du téléphone posé sur le robot…"), which
  the app matches to translate it.

## Setup

1. Build and upload (board on USB; toolchain: see [Toolchain](#toolchain-arduino-esp32-3x-esp-idf-55)):
   ```sh
   python3 -m venv /tmp/espvenv && /tmp/espvenv/bin/pip install 'platformio>=6.2.0'
   cd firmware/esp32-gateway
   /tmp/espvenv/bin/pio run -e s3lipo -t upload   # Olimex ESP32-S3-DevKit-Lipo, cable on its USB-UART port
   /tmp/espvenv/bin/pio run -e tcall -t upload    # T-Call
   ```
   On a T-Call the Wi-Fi saved by the diagnostic program is reused. Otherwise (no saved network, or not connected after
   20 s): access point `DumbyLuba-ESP32-xxxx` (last four hex digits of the MAC address), page
   `http://192.168.4.1/wifi` (any other address redirects there). Password unique to the board (12 characters,
   no ambiguous ones), generated at first boot and shown on the serial console (`ap-pass` to show it again). Or from
   the console: `wifi NAME|PASSWORD`. The access point closes 60 s after the board has joined the network; without
   Wi-Fi, the saved network is retried every 30 s (unless someone is connected to the access point).
2. **Pairing with the PWR button** (version 0.3.1, no cable): briefly press the board's PWR button (Olimex board: a
   reset through EN, see [Boards](#boards)). The ESP32 restarts and opens a **3 min pairing window** (LED: double flash). On the Fold, Service → Wi-Fi gateway →
   **Pair the ESP32 (PWR button)**: the app finds the gateway on the Wi-Fi, reads its pairing link (`GET /pair`),
   checks the code and saves it. The status page `http://dumbyluba-esp32.local/` also shows the code and the link
   during the window. The window only opens after a press on the button or a power-up (never after a software
   restart, an update, a crash or a watchdog), closes as soon as a pilot authenticates with the code, and `/pair`
   answers 403 outside it: anyone on the home Wi-Fi can read the code during these 3 minutes, as with WPS.
   **Code**: also shown on the serial console at boot (`code` to show it again, `code NNNNNN` to change it, for
   example to reuse the one already entered on the Fold). `pair` prints the pairing link
   `dumbyluba://pair?v=2&host=IP&port=8766&code=NNNNNN&name=ESP32`, the one behind the Android gateway's QR code:
   opened on the pilot phone, it offers **Use this gateway?**. It carries the code, so it is never shown on the
   web pages.
3. **Stop the Redmi gateway** (the robot accepts only one link, and the Fold first tries the stored
   address): on the Redmi, Service → Wi-Fi gateway → **Stop**, or **Release the robot** (Service → Bluetooth
   connection).
4. On the Fold: Service → Wi-Fi gateway → Control via the gateway, the ESP32's code, **Find and test**
   (the address is found over mDNS), then search for and tap the robot.

## Toolchain (arduino-esp32 3.x, ESP-IDF 5.5)

From version 0.4.0 (`gateway-0.4.0`), the gateway is built with **arduino-esp32 3.3.12 (ESP-IDF 5.5.5)** and
**NimBLE-Arduino 2.5.2**, instead of arduino-esp32 2.0.17 (IDF 4.4.7) and NimBLE-Arduino 1.4.3. The official
PlatformIO platform stops at arduino-esp32 2.x: `platformio.ini` pins the community platform **pioarduino 55.03.312-1**
(`https://github.com/pioarduino/platform-espressif32/releases/download/55.03.312-1/platform-espressif32.zip`), which
needs **PlatformIO Core 6.2.0 or later**; because of that requirement, the `pioarduino` 6.2.0.post1 that the platform
installs by itself is refused (note below the example). Commands: `pio run -e s3lipo` / `pio run -e tcall`, `pio run -e ota-s3lipo` / `pio run -e ota`,
`pio test -e native`); the first build downloads about 4 GB of packages (toolchain GCC 14.2, precompiled IDF
libraries). To keep them apart from the packages of `firmware/esp32-diag` (still on espressif32 6.9.0), give the
commands a core directory of their own, for example:

```sh
cd firmware/esp32-gateway
export PLATFORMIO_CORE_DIR=/tmp/esp32-idf5-pio
/tmp/espvenv/bin/pio run -e tcall
```

On 2026-10-27 a new core directory failed: the platform creates its Python environment (`penv/` in the core
directory) with the latest `pioarduino` from PyPI, 6.2.0.post1, then refuses it ("not compatible with PlatformIO Core
v6.2.0-post.1 and depends on PlatformIO Core >=6.2.0") and removes itself from the core. Creating that environment
first with 6.2.0, before the first build in that core directory, avoids it (the platform keeps an installed version
that satisfies `>=6.2.0`):

```sh
/tmp/espvenv/bin/python -m venv /tmp/esp32-idf5-pio/penv
/tmp/esp32-idf5-pio/penv/bin/pip install "pioarduino==6.2.0"
```

This workaround was used on 2026-10-27: in a copy of a core directory (packages kept, `penv/` left out), a `penv/`
created this way with `pioarduino==6.2.0` let the platform install itself again, and `pio run -e tcall` and
`pio test -e native` (199 of 199 tests) went through.

- Why: IDF 4.4 aborts when Bluetooth starts with Wi-Fi power save off ("Should enable WiFi modem sleep…"); IDF 5
  accepts `WIFI_PS_NONE` alongside Bluetooth (esp-idf commit 728cd24, in IDF 5.1.2 and later). With modem sleep the
  station only wakes at the router's DTIM beacons, the main cause of receive latency from the pilot. The radio stays
  shared with Bluetooth in coexistence (Wi-Fi is awake during its own time slices only): the gain is to be measured.
- Setting `wifi-ps none|min` (serial console, stored on the board, default `none`); a refused `none` is logged and
  falls back to `min`. Mode in force: `wifi.ps` of `/status` (`none`, `min`, `max`), also on the "connected" console line.
- With the precompiled IDF libraries of the core: image of 1,875,440 bytes, 95.4 % of the 1,920 KB OTA slot
  (1,542,096 bytes with arduino 2.x), static RAM 78,000 bytes (61,516 before), IRAM nearly full (2,653 bytes left:
  with the PSRAM cache workaround IDF 5 links the newlib time and string functions into IRAM, so `strftime` is no
  longer used), and as little as 548 bytes of internal RAM left during a route upload. Hence the IDF configuration of
  the gateway's own, below. Partition table unchanged (`min_spiffs.csv`, identical in both cores).
- Built with `-std=gnu++17`, as the native tests (the cores default to gnu++2a); `-mfix-esp32-psram-cache-issue` is
  now added by the platform, for the T-Call's ESP32 only (the ESP32-S3 has no such PSRAM cache bug).
- **Update over Wi-Fi from 0.3.1**: a board already in service keeps its IDF 4.4.7 bootloader (OTA never rewrites the
  bootloader). An IDF bootloader boots apps built with newer IDF versions ("Bootloader compatibility",
  `docs/en/api-guides/bootloader.rst`), the otadata format and rollback states are unchanged between 4.4 and 5.5, and
  that bootloader was built with `CONFIG_BOOTLOADER_APP_ROLLBACK_ENABLE` (rollback checked on the board on 2026-10-15);
  the new image keeps `CONFIG_APP_ROLLBACK_ENABLE` and the 60 s confirmation. Saved settings (NVS format 0xfe in both)
  are read as before. **Tried from 0.3.1 on 2026-10-27** (test board, ESP32-D0WD revision 1.0): 0.3.1 rebuilt from
  596660fd (espressif32 6.9.0, arduino-esp32 2.0.17) and written over USB with its own IDF 4.4.7 bootloader, partition
  table, `boot_app0` and application, without erasing the flash (NVS kept): the board booted `gateway-0.3.1` and
  joined the saved Wi-Fi. Then, over Wi-Fi with `tools/ota_upload.py` and the code read from `/pair`:
  - **Rollback**: image of 0.4.0 built for the trial (1,642,352 bytes, 83.5 % of the OTA slot; the size changes
    from one build to another, see the note under the table of the next section) sent and written in 14.9 s,
    restart on `app1` in `pending_verify`, then a reset through EN (opening the serial console) at 14 s of uptime:
    the old bootloader went back to 0.3.1 on `app0`, which logged "the new version did not start (app1): back to the
    previous version".
  - **Update**: same image sent and written in 15.2 s, back 25 s after the upload started, on `app1` in
    `pending_verify`, confirmed after 60 s (`valid`, "new version confirmed (app1)", `ota_upload.py` exit code 0,
    79 s in all). `/status` at 71 s of uptime: `firmware: gateway-0.4.0`, `wifi.ps: none`, `heap_internal_free`
    74,392 and `heap_internal_min` 72,096 bytes; pairing window closed (software restart). Saved Wi-Fi network,
    gateway code (same code on the console) and retained robot kept. `wifi-ps` read `none` before and after, which is
    also the default: whether the stored value survives 0.3.1 is not shown.
  - Bootloader read back after the update: byte for byte the IDF 4.4.7 one of 0.3.1 (OTA does not write it), as on
    boards in service; a later reset kept 0.4.0 (`app1`, `valid`).
  The robot was not connected. Before that day, the test board had only taken IDF 5 images over Wi-Fi with the
  IDF 5.5.5 bootloader written over USB (2026-10-26).

### ESP-IDF configuration of the gateway (internal RAM)

The precompiled Arduino libraries are built for every use (Wi-Fi and lwIP buffers in internal RAM, Bluedroid and
Classic Bluetooth, Matter, Zigbee, RainMaker...). `platformio.ini` gives pioarduino a configuration of its own
(`custom_sdkconfig`, `custom_component_remove`): on the first build, or when these lines change, it rebuilds the IDF
libraries of the Arduino core with it ("hybrid compile", about 3 min on a recent Mac: 2 min 52 s and 3 min 27 s
measured, then about 35-45 s per build of the gateway alone). Points to know:

- The rebuilt libraries replace the precompiled ones inside the PlatformIO core (`framework-arduinoespressif32-libs`,
  reinstalled when a project builds without this configuration): use a core directory of its
  own (`PLATFORMIO_CORE_DIR`, above).
- Generated in the project and ignored by git: `sdkconfig.defaults` (Arduino template plus `custom_sdkconfig`),
  `sdkconfig.tcall` / `sdkconfig.ota` / `sdkconfig.s3lipo` / `sdkconfig.ota-s3lipo` (the configuration really used:
  check an option there), `.dummy/`, `managed_components/`. `min_spiffs.csv` and `ota_8mb.csv` are versioned: the IDF
  build looks for the partition table in the project (`min_spiffs.csv`: copy of the core's, identical).
- **Switching boards**: `sdkconfig.defaults` is written in the project, so it is shared by the environments. Building
  `s3lipo` after `tcall`, or the reverse, makes pioarduino reinstall the Arduino libraries of the core directory,
  rebuild them for the other chip and delete the other board's `sdkconfig.<env>` (measured on 2026-10-27: 142 s
  for `s3lipo` after `tcall`, 108 s for `tcall` after `s3lipo`, against 35-37 s for a build on the same board). `ota` after `tcall`, and `ota-s3lipo` after `s3lipo`, share the configuration: no rebuild.
- T-Call: bootloader (the precompiled `bootloader_dio_40m`, byte for byte), image header (DIO, 40 MHz, 4 MB), partitions
  and rollback (`CONFIG_APP_ROLLBACK_ENABLE`, `CONFIG_BOOTLOADER_APP_ROLLBACK_ENABLE`) stay those of the build with the
  precompiled libraries; over Wi-Fi only the app is sent.
- T-Call: flash and PSRAM speeds in the app stay those of the precompiled libraries: 80 MHz (`CONFIG_ESPTOOLPY_FLASHFREQ_80M`,
  `CONFIG_SPIRAM_SPEED_80M`, `CONFIG_SPIRAM_OCCUPY_HSPI_HOST`). The rebuild derives both from the board's `f_flash`
  (40 MHz for `esp32dev`), which would halve the PSRAM clock, so `custom_sdkconfig` sets them back; raising
  `board_build.f_flash` instead would also change the image header and pick the 80 MHz bootloader. The bootloader
  reads the app at the header's 40 MHz; the app moves the shared flash and PSRAM bus to 80 MHz when it starts the
  PSRAM, as with the precompiled libraries.

T-Call (`[env:tcall]`, also used by `ota`):

| Option | Why |
|---|---|
| `CONFIG_SPIRAM_TRY_ALLOCATE_WIFI_LWIP=y` | Wi-Fi dynamic buffers and lwIP (pbufs, PCBs) in PSRAM first: a route upload (3 × 990 KB) no longer grows the internal heap (heap walks during the trial). DMA buffers stay internal (the Wi-Fi driver asks for them explicitly). |
| `CONFIG_ESP_WIFI_STATIC_TX_BUFFER_NUM=6` | With the previous option IDF only offers static TX buffers (the core used up to 32 dynamic ones, internal): 6 internal buffers of 1.6 KB instead of the default 16; the gateway mostly receives, frames beyond wait in the cache queue (`CONFIG_ESP_WIFI_CACHE_TX_BUFFER_NUM`, 32). |
| `CONFIG_SPIRAM_MALLOC_ALWAYSINTERNAL=256` | `malloc` of more than 256 bytes goes to PSRAM first (4,096 in the core): pilot frames (3.8 KB blob chunks), `WiFiClient` buffers, decoded routes, `/status` text, NimBLE-Arduino objects. Memory that must be internal (task stacks, FreeRTOS objects, DMA, ISR data, Wi-Fi and Bluetooth controller) is requested explicitly by IDF; flash writes from a PSRAM buffer go through an internal bounce buffer. |
| `CONFIG_ESP_TIMER_TASK_STACK_SIZE=4096` | 8,192 in the core, IDF default 3,584; at most 300 bytes used (Wi-Fi timers; NimBLE callouts only post to the host queue). |
| `CONFIG_FREERTOS_TIMER_TASK_STACK_DEPTH=2048` | 4,096 in the core, IDF default 2,048; at most 500 bytes used. |
| `CONFIG_BT_CONTROLLER_ONLY=y`, no Bluedroid, `CONFIG_BTDM_CTRL_MODE_BLE_ONLY=y`, `CONFIG_BTDM_CTRL_BLE_MAX_CONN=1`, no BLE mesh | NimBLE-Arduino brings its own host; the controller is sized for the one robot link. `robot.cpp` then defines `_bleLibraryInUse` itself (the core only defines it with an IDF host). |

Olimex ESP32-S3-DevKit-Lipo (`[env:s3lipo]`, also used by `ota-s3lipo`): the same choices with the keys of the
ESP32-S3, and only those that differ from the Arduino ESP32-S3 template. Octal PSRAM, QIO flash, 8 MB and their
80 MHz clock come from the board (`memory_type` `qio_opi`, `f_flash` 80 MHz; pioarduino then also sets
`CONFIG_IDF_EXPERIMENTAL_FEATURES`, which its octal PSRAM settings require); Wi-Fi and lwIP buffers in PSRAM
(`CONFIG_SPIRAM_TRY_ALLOCATE_WIFI_LWIP`) and the absence of Bluedroid are already in the template. Checked in the
generated `sdkconfig.s3lipo`: `CONFIG_SPIRAM_MODE_OCT`, `CONFIG_SPIRAM_SPEED_80M`, `CONFIG_ESPTOOLPY_FLASHMODE_QIO`,
`CONFIG_ESPTOOLPY_FLASHSIZE_8MB`, `CONFIG_APP_ROLLBACK_ENABLE`, `CONFIG_BOOTLOADER_APP_ROLLBACK_ENABLE`, core dump to
flash, console on UART0. Not tried on the board.

| Option | Why |
|---|---|
| `CONFIG_ESP_WIFI_STATIC_TX_BUFFER_NUM=6` | as on the T-Call (8 in the template) |
| `CONFIG_SPIRAM_MALLOC_ALWAYSINTERNAL=256` | as on the T-Call (4,096 in the template) |
| `CONFIG_ESP_TIMER_TASK_STACK_SIZE=4096` | 8,192 in the template |
| `CONFIG_FREERTOS_TIMER_TASK_STACK_DEPTH=2048` | 3,120 in the template |
| no `CONFIG_BT_NIMBLE_ENABLED`, `CONFIG_BT_CONTROLLER_ONLY=y` | the ESP32-S3 template builds the IDF NimBLE host; NimBLE-Arduino brings its own |
| no `CONFIG_BLE_MESH`, no `CONFIG_BT_CTRL_BLE_MESH_SCAN_DUPL_EN` | no BLE mesh, nor its duplicate-scan cache in the controller (`CONFIG_BTDM_BLE_MESH_SCAN_DUPL_EN` on the T-Call) |

No ESP32-S3 equivalent: `CONFIG_BTDM_CTRL_MODE_BLE_ONLY` (the ESP32-S3 controller is BLE only), the HSPI host and the
flash and PSRAM speeds set back on the T-Call. `CONFIG_BTDM_CTRL_BLE_MAX_CONN=1` has none either: the ESP32-S3
controller counts scans, connection attempts and links together (`CONFIG_BT_CTRL_BLE_MAX_ACT`), left at its default
of 6 until tried with the robot.

NimBLE-Arduino flags (`build_flags`, both boards): host memory (mbufs, HCI buffers, GATT tables) in PSRAM
(`CONFIG_BT_NIMBLE_MEM_ALLOC_MODE_EXTERNAL`), a single connection, central and observer roles only.

Components removed (`custom_component_remove`, both boards): Zigbee (ZBOSS), RainMaker, Insights and its diagnostics (with their
log hook), network provisioning, CBOR, QR code, libsodium, Modbus, DSP, MP3 decoder (Matter is not built for this
target). Kept: Wi-Fi, lwIP, mDNS, HTTP server, OTA, NVS, Bluetooth controller. `tools/drop_unused_libs.py` (post
script) also takes the precompiled `esp_matter` and `esp_modem` libraries off the link line: their objects carry their
own copies of the `std::string` code, and the linker took some of them from Matter, pulling in Matter's globals.
Measured on 2026-10-26: 76,779 bytes of static RAM without the script, 63,331 with it.

Tried and not kept:

- Task stacks in PSRAM (`xTaskCreatePinnedToCoreWithCaps`, `CONFIG_FREERTOS_TASK_CREATE_ALLOW_EXT_MEM`): the T-Call's
  chip is an ESP32-D0WD **revision 1.0**, whose PSRAM cache bug is only worked around in recompiled code; ROM code,
  Wi-Fi and Bluetooth blobs must not run on a PSRAM stack (IDF help of the option), and a task on a PSRAM stack must
  never write the flash (NVS, OTA). The tracking, decoding, robot and pilot tasks do all of this. The ESP32-S3 has
  no such cache bug, but the flash rule still holds: not tried there either.
- `leg-prepare` stack reduced from 16 KB to 8 KB: 1.8 KB used with the trial's routes and surfaces, but real
  surfaces were not tried and the target is reached without it.
- TCP window, Wi-Fi RX buffers and block-ack window: left as they are, the heap walks show no internal growth from
  them during the uploads.

| | Precompiled libraries | Own configuration |
|---|---|---|
| Static RAM (DRAM `.data` + `.bss`) | 77,999 bytes | 63,339 bytes |
| IRAM left | 2,653 bytes | 13,121 bytes |
| Image (`firmware.bin`) / OTA slot of 1,966,080 bytes | 1,875,440 bytes, 95.4 % | 1,642,496 bytes, 83.5 % |
| Internal RAM free at rest (`heap_internal_free`) | about 21,800 bytes | about 74,000 bytes |
| Internal RAM free during the uploads (lowest sample) | 1,912 bytes | 63,336 bytes |
| Lowest internal RAM since boot (`heap_internal_min`) | 548 bytes | 61,256 bytes |
| Largest internal block (lowest sample) | 1,076 bytes | 61,428 bytes |
| PSRAM free (lowest sample, 3 routes and 3 surfaces kept) | 1,965,348 bytes | 1,920,700 bytes |

Image size, own configuration: `firmware.bin` measured with `stat` after `pio run -e tcall` on the code of `main`
24cd9b17 on 2026-10-27, 1,642,496 bytes (83.5 % of the slot). The same code gave 1,642,352 bytes for the image of the
trial of 0.3.1 to 0.4.0 above and 1,642,480 bytes at 7e1ca9c (only `FW_VERSION` differs, same length): the image
keeps 25 source file paths of the Arduino framework, under the PlatformIO core directory, so its size depends on the
build directories. The "Flash" figure printed by PlatformIO (81.8 %, 1,607,830 bytes for the 1,642,496-byte image) is
computed from the sections of the program, not from the size of the file.

Firmware 0.5.0 (`stat` of `firmware.bin` and PlatformIO's RAM figure, 2026-10-27, both boards built from the same
code): T-Call 1,641,776 bytes, 83.5 % of its 1,966,080-byte slot, static RAM 63,340 bytes, IRAM and DRAM sections
unchanged from 0.4.0 (the board code moved to `board.cpp`; `sdkconfig.tcall`, partition table and bootloader identical
byte for byte); Olimex board 1,606,976 bytes, 38.9 % of its 4,128,768-byte slot, static RAM 59,852 bytes (`.data`
23,755 + `.bss` 36,096), 198,942 bytes of the ESP32-S3's 341,760-byte internal DIRAM left after code and static data.

Trial of 2026-10-26 (board on USB power, Wi-Fi at -27 dBm, `wifi.ps` none): pilot simulated by a session
script on `tools/gateway_client.py` (authentication, LEG_HELLO, OPEN), 3 routes of 30,000 edges (990,004 bytes) then
3 surfaces of 2,000 vertices (32,029 bytes) uploaded back to back, all accepted (7.6-8.6 s each for a route, 6.7-9.1 s
in a second run; 5.9-8.2 s with a first build whose rebuild had left flash and PSRAM at 40 MHz, so no gain measured
there from the 80 MHz clock), 30 s held, `/status` read every 2 s: no Wi-Fi outage, no restart (uptime continuous, no
reset on the console). **The robot
was not connected**: the LUBA was not advertising (gateway attempts ending in `status=13`, gateway and Mac scans
empty), so the robot's share (BLE link, notifications, `robot-notify` stack) is not in these figures. Stack left
during the trial: `robot` 5,792 / 8,192 bytes, `robot-notify` 7,616 / 8,192 (no notification), `leg` 19,576 /
20,480 (no tracking run), `leg-prepare` 14,548 / 16,384; read on a build of the same code with a temporary heap-walk page: pilot 5,344 /
8,192, Arduino loop 5,580 / 8,192, `esp_timer` 3,796 / 4,096, timer service 1,548 / 2,048. The precompiled-library
column comes from the same trial run on that firmware, robot not connected either.

## Update over Wi-Fi

Once version 0.2.0 (T-Call) or 0.5.0 (Olimex board) has been installed over USB, the cable is no longer needed:

```sh
cd firmware/esp32-gateway
DUMBYLUBA_CODE=123456 /tmp/espvenv/bin/pio run -e ota-s3lipo -t upload   # Olimex board; code: DUMBYLUBA_CODE, otherwise prompted
DUMBYLUBA_CODE=123456 /tmp/espvenv/bin/pio run -e ota -t upload          # T-Call
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
5. **Image of the other board**: `ota` builds the T-Call image (ESP32), `ota-s3lipo` the Olimex one (ESP32-S3); check
   `board` in `/status` before sending. ESP-IDF checks the chip of the image header before activating it
   (`esp_image_verify`), so an image for the other chip should be refused at the end of the upload and the gateway
   stay on its version (read in the IDF code, not tried).

Status: `ota` block of `/status` (partition, `pending_verify`/`valid`, setting, last report), serial
command `ota`. While writing (~12 s), the board no longer answers over Wi-Fi.

**LED** (T-Call GPIO 13; Olimex board GPIO 38, green): double flash = pairing window open; slow blink (1 s) = robot connected; medium (0.4 s) = Wi-Fi only; fast (0.12 s) = no Wi-Fi.

## Web pages

- `/`: status page refreshed every 5 s: **Robot** ("No robot: pick it from the Fold (Control via the gateway)",
  "… connected over Bluetooth · piloted from the Fold", "… waiting for the pilot phone", "Bluetooth connection to …",
  "… off, out of range or held by another device: retrying automatically"), **Pilot**, **Last safety stop**, button
  **Release the robot** (Mammotion app, direct Bluetooth; no more automatic reconnection), links to Wi-Fi and JSON,
  and the full status. Redirects to `/wifi` while only the access point is up.
- `/status`: status as JSON (firmware, `board` (`tcall` or `s3lipo`), uptime, memory, power, Wi-Fi with its power
  save mode in force `wifi.ps`, robot, pilot, `ota`). `power`: `vin_present` (external 5 V), `level_percent` (−1:
  unknown) and `battery_mv` (cell voltage, −1 on the T-Call, which does not measure it) on both boards; T-Call only:
  `ip5306_ok`, `keep_on`, `charge_full`. Memory: `heap_free` / `heap_min` count every internal region (including the IRAM left over, 32-bit
  access only); `heap_internal_free` / `heap_internal_min` (lowest since boot) count the byte-addressable internal RAM
  that `malloc` and the task stacks use; `heap_largest_internal`; `psram` (size, free, largest block).
- `/wifi`: network list with signal, or a typed name, and password; **Save** restarts the board on that network.
- `/pair`: pairing link as JSON (`{"open":true,"link":"dumbyluba://pair?…"}`) while the window opened by the PWR
  button is open, 403 otherwise. `/status` only says whether it is open (`pairing` block), never the code.
- `/robot/release` (POST), `/ota/nonce` and `/ota`: release and update.

## Serial console (115,200 baud)

| Command | Effect |
|---|---|
| `status` | Full state as JSON (same content as `/status`) |
| `code` / `code NNNNNN` | Shows / changes the six-digit code |
| `pair` | Prints the pairing link (`dumbyluba://pair?…`, with the code) |
| `release` | Releases the robot (Mammotion app, direct Bluetooth) and forgets it |
| `wifi NAME\|PASSWORD` / `forget-wifi` | Wi-Fi network (saved, then restart) |
| `ap-pass` | Shows the access point name and password again |
| `reboot` | Restarts |
| `ota` / `ota-mowing on\|off` | Update-over-Wi-Fi status / "during mowing" setting (normally sent by the Fold) |
| `wifi-ps` / `wifi-ps none\|min` | Wi-Fi power save of the station: shows / sets it (stored, default `none`, applied at once) |

A state line is printed every 30 s (Wi-Fi, robot, pilot, power (`usb`: external 5 V present) with the cell voltage on
the Olimex board, free memory: `heap`, then `internal` and its lowest value since boot `min`).

## Tests

- `pio test -e native` (213 tests in six suites; `-f test_core` etc. for one). `test_autonomy` (53): autonomous
  tracking frame codecs, the Kotlin `LegRunnerTest` scenarios on a port of its simulated robot, gateway end to end
  (upload, refusals, Wi-Fi drop with sample replay, abort, deliberate departure, pilot absent too long, docking
  without pilot), gateway fallen off. `test_telemetry` (32): robot telemetry decoding, observer, error catalogue,
  lift, navigation guards, tracking commands byte for byte against protobuf-java. `test_geom` (20): WKB, free
  surface index, chassis envelope compared with JTS on the reference map. `test_follow` (61): path follower (curve tracking
  with the Kotlin parity vectors of `PathFollowerTest`: tangent reference, feed-forward, curve speed, exit of a curve),
  dead reckoning, return drift, slides, station exit. `test_power` (12): battery level of the Olimex board (divider,
  conservative curve, unknown on 5 V or out of range). `test_core` (35): BluFi (splitting, reassembly, CRC, sequence loss), protobuf encoding of the stop and
  sync frames, blade tracking, cutting heights of every model, refusal to inject in the middle of a message, TCP
  protocol, protocol texts identical to the Android gateway's, mower name filter, pairing link, attempt limiting.
  The stop, blades OFF and sync bytes are identical to those of `Commands.kt` (checked by running the app's
  compiled classes).
- `tools/gateway_client.py` plays the role of the Fold (Python standard library):
  `HOST --code NNNNNN [--port 8766]`, then `check` (code, health), `scan`, `open ADDRESS [--syncs 20] [--timeout 40]`
  (link, syncs, Wi-Fi and Bluetooth latencies),
  `open ADDRESS --silence` (goes silent: the gateway must write the safety stop, read back on reconnection).
  It only sends syncs and, with `--stop`, a zero speed. `--ota-mowing on|off` sends the update setting;
  `hold ADDRESS [--seconds 120]` holds the robot like a Fold while mowing and shows the `ota_begin` announcement.

## Status as of 2026-10-23: autonomous tracking (0.3.0)

Installed over Wi-Fi and confirmed, 181 native tests passed. On the board, without a robot: `caps=autonomy,dock-exit,ota`
in WELCOME, routes of 990 KB and surfaces of 2,000 vertices accepted three times in a row (PSRAM), tracking start refused
for want of a robot link. Not tried with the robot yet: tick time on the board, robot MTU and the whole tracking remain to
be checked at the first real route.

## Status as of 2026-10-23: brought up to the app

Installed over Wi-Fi (`pio run -e ota -t upload`, confirmed), 27 core tests passed. On the board, from the Mac:
right code accepted, wrong code refused with "Code de passerelle incorrect", `pair` prints the link, and 5 scans in a
row during reconnection attempts to an absent retained robot all end within 6 s, without restart.

**Fixed**: the robot task was woken by FreeRTOS task notifications, which NimBLE also uses to wait for its blocking
calls (connection, write with response, RSSI). A pilot's request (scan, write) arriving during one of them ended the
wait early as a success: the client then stayed "busy" for good (every reconnection and scan refused) and the board
could restart; a write could have been reported confirmed before the robot answered. The task now waits on its own
semaphore. Not tried with the robot (held by the Redmi): real connection, relaying and safety stop.

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
