# Home server

A small Python server, with no dependencies, receives the robot's status relayed by a phone and shows it in a web
page on the local network: zoomable map, robot, base, battery, last position. It remains available when the phone
is off. It sends **no** command to the robot.

```
Robot ── BLE ── Phone (relay) ── HTTP, every 3 s ── Home server ── browser on the local network
```

| | |
|---|---|
| **Access** | Phone: Service → **Home server** (Connection group, "Robot status on a local web page", shows "active" while the relay runs); browser: `http://SERVER_IP:8765/` |
| **Status** | Implemented and tested (69 Python tests, 11 Node tests); keeping it running with the screen off on the Redmi still to be validated |
| **Code** | `home-server/server.py`, `home-server/web/`, `home-server/install-macos.py`, `app/…/relay/` (`RelayPanel.kt`, `RelayService.kt`, `RelayConfig.kt`, `RelaySnapshot.kt`, `RelayMapJson.kt`, `RelayOutbox.kt`) |
| **References** | [Home server README](../../home-server/README.md) (API, storage, keeping it running) · [Android relay configuration](../REDMI_RELAY_POC.md) |

The relay is independent of the [Wi-Fi gateway](gateway.md): the relay phone only reports what it sees of the robot;
no command goes through it or through the server.

## Starting the server

Python 3.10 or later, from the repository root:

```sh
python3 home-server/server.py --data home-server/data/relay.sqlite3
```

| Option | Default |
|---|---|
| `--host` | `0.0.0.0` (all interfaces; `127.0.0.1` for a local test) |
| `--port` | `8765` |
| `--data` | `./data/relay.sqlite3` (relative to the current folder) |
| `--code` | generated at first launch and stored in the database |

At startup the server prints `DumbyLuba: http://HOST:PORT/ — code NNNNNN`. The **six-digit code** is kept across
restarts. The app also generates its own code the first time the Home server page is opened; to reuse it, pass
`--code 123456` at the first launch. A code different from the one in the database is refused ("The code differs
from the one stored in the database"): change the code in the app instead of deleting the database, which would erase
the observations. A malformed `--code` is refused before the folder or the database is created.

Permanent service: macOS installer (`python3 home-server/install-macos.py [--code NNNNNN]`, user LaunchAgent
`fr.luba.home-server`, restarted if it stops, `caffeinate -i` against automatic sleep) or example systemd unit in the
[server README](../../home-server/README.md#keeping-the-server-running).

## Configuring the relay phone

1. Service → **Home server**. *Relay settings* group:

   | Control | Detail |
   |---|---|
   | **Home server** | server address, for example `http://192.168.1.10:8765`; `http` or `https`, no path, query or credentials ("Expected address: http://server-address:8765") |
   | **Six-digit code** | digits only, may start with 0 ("Expected code: six digits") |
   | **Start the relay** / **Stop the relay and Bluetooth** | starts or stops the sending service |
   | **Battery optimization** · **Android settings** | open the Android battery-optimization list and the app's system settings |

   Address and code are saved automatically as soon as they are valid (even without starting the relay) and are
   locked while the relay runs. Starting asks for the Bluetooth permissions (scan and connect on Android 12+, plus
   notifications on Android 13+; precise location on Android 8 to 11); if they are refused: "Bluetooth permissions
   are required."
2. **Start the relay**, then connect the robot ([Bluetooth connection](bluetooth-connection.md)) and read its map.
3. Open the server page in a browser and enter the same code.

The *Relay status* group shows **Status** ("Relay stopped", "Sending…", "Information sent to the server" or
"Sending suspended: <cause> — automatic retry"), **Last sent** (time of the last accepted snapshot) and, while the
relay runs, **Battery exemption** ("active" or "missing", with the warning "Battery exemption missing: set it up
before a test with the screen off.").

The relay sends a snapshot every 3 s, after the previous upload has finished (4 s connection and read timeouts, no
HTTP redirect followed, code in the `X-Relay-Code` header). A snapshot holds the connection state, decoded telemetry,
the displayed position, the app's robot state (status, obstacles, last FIX…), the map (outlines, completeness, source)
and the base (robot station or the base saved in the app). No Mammotion credentials and no log files are transmitted.
Only the last snapshot is kept on the phone (`relay-latest.json`, atomic write) during an outage, and it is sent first
after a restart of the relay. Notification: "DumbyLuba — relay active", "Sending to the home server · BLE with screen
off", action **Stop the relay**. The service keeps the CPU (wake lock renewed every 5 min) and the Wi-Fi awake while
it runs.

**Stop the relay and Bluetooth** (or the notification's **Stop the relay**) stops sending and releases the relay's
locks. The robot link is then handled like the app going to the background: while the
[background link](../BACKGROUND_LINK.md) service holds it, it stays open; disconnect from
[Bluetooth connection](bluetooth-connection.md) to close it.

## Storage and web page

- SQLite (WAL, `synchronous=FULL`, file with 0600 permissions): last status per robot and the last 1,000
  snapshots; a 200 response is sent only after validation and commit. A partial or empty map does not replace a
  complete map of the same robot; a missing position keeps the last known one; a duplicate or late snapshot (sequence
  not higher than the last one of that relay) does not overwrite a more recent observation.
- The page works without a CDN or Internet access, in English or in French depending on the browser language, in
  light or dark theme. It refreshes every 3 s and shows:
  - a status line: **Relay connected** or **Relay offline · latest information kept** (after 15 s without data),
    with the time and age of the latest data; "Server unreachable: …" if the server does not answer;
  - three tiles: **Battery**, **Robot** (Ready, connection state or Offline), **Last position** (age);
  - **My garden**: zones, no-go zones, base and robot (with heading) on a canvas, drag, pinch or mouse-wheel zoom,
    **Recenter**, **＋** and **−** buttons, scale bar; a position older than 2 s or from an offline relay is shown
    grayed out as "Robot · old"; the line below gives the map source, "incomplete map", position and base sources;
  - **Robot information**: the raw received status, collapsed by default.

## Security

- Six-digit code: 5 wrong attempts per minute and per IP, plus a global limit of 100 per minute (429 "Too many
  attempts: wait one minute"; the page then waits one minute). 90-day browser session (HttpOnly cookie,
  SameSite=Strict, stored hashed, kept across restarts), revocable with **Sign out**. The code is never in the URL or
  the page and is cleared from the field after sign-in. No CORS.
- A malformed snapshot (invalid JSON, missing field, non-finite number or one too large for a float) is refused with
  a 400 error "Invalid snapshot", without modifying the stored data; the same goes for an empty body or a version
  that is not the integer 1. Bodies above 8 MiB get 413.
- Database unavailable (locked, disk full): 503 response "Storage unavailable" on every route, instead of a dropped
  connection.
- At most 8 simultaneous connections, 5 s socket timeout; requests are not logged (no code or snapshot in the logs).
- Plain HTTP: **never expose it on the Internet**. For remote access, use a VPN or an HTTPS reverse proxy.

## Tests

```sh
python3 -m unittest discover -s home-server/tests -v
node --test tools/relay-map-test.cjs tools/relay-web-test.cjs
```

Python: `test_server.py`, `test_server_cases.py` (upload, code and rate limiting, sessions, restore after restart,
validation, limits, routes, 503) and `test_install_macos.py` (installer in a temporary folder, without a real
`launchctl`). Node: map rendering and web page. The hardware checklist is in the
[server README](../../home-server/README.md#check) and the [relay configuration](../REDMI_RELAY_POC.md#hardware-validation).
