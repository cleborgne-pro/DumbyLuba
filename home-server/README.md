# DumbyLuba home server

Python **3.10 or later**, nothing to install. Android handles BLE and forwards
the information; the server keeps the data and serves the web page.
It never sends any command to the robot. The server must stay on for the
page to remain reachable while the phone is off.

## Run

From the repository root:

```sh
python3 home-server/server.py --data home-server/data/relay.sqlite3
```

The server listens on port **8765** and prints
`DumbyLuba: http://HOST:PORT/ — code NNNNNN` (a **six-digit** code).
The code is stored in the database and stays the same after a restart.
Options: `--host` (default `0.0.0.0`), `--port` (default `8765`), `--data`
(default `./data/relay.sqlite3`, relative to the current folder) and `--code`.
In **Service → Home server** on Android: enter
`http://SERVER_IP:8765` and the code, then **Start the relay**.
Connect the robot and load its map in **Service → Bluetooth connection**.
The Android address and code are saved automatically as soon as they are
valid, even without starting the relay; rotations and restarts restore them.
In a browser: open `http://SERVER_IP:8765/` and enter the same code.
The code stays visible and saved in the app, even after the relay stops.

To use the code already shown in the app from the very first launch:

```sh
python3 home-server/server.py --data home-server/data/relay.sqlite3 --code 123456
```

`123456` is an **example to replace** with the app's code.
If the database exists, a different code is refused ("The code differs from
the one stored in the database"); a malformed code is refused before the folder
or the database is created. Change the Android field to
use the existing server's code; do not delete the database to change
the code, as that would erase the observations. The code may start with zero.

For a test on the same machine only, add `--host 127.0.0.1`.
For the LAN, use this machine's IP and allow TCP 8765 in its
firewall. Reserve its address in the router's DHCP. The Redmi requires a 2.4 GHz
Wi-Fi without client isolation. Do not expose this HTTP POC to the Internet.
The phone can use an HTTPS URL if a reverse proxy with a valid
certificate terminates TLS in front of this server; the sender follows no HTTP
redirect, so the code always goes to its configured destination.

## Storage and offline operation

- Sent every three seconds, once the previous upload has finished.
- Snapshots contain all decoded telemetry, the Android domain state
  (`RobotUi`), visible events, diagnostics, traces/last FIX, obstacles,
  the received map and the known base. No Mammotion account, password or code
  is included in the snapshot. Historical logs on disk and
  LubaBrain corrections held by another controller are not exported.
- The phone atomically keeps **one last snapshot** in its private storage
  before sending. During an outage, recent observations replace
  earlier ones; it is not a complete history of the outage.
  After a restart, the remaining snapshot is sent before new observations.
- SQLite keeps the latest state **per robot** and the last 1,000 snapshots
  received. Transactions are durable; a 200 response is sent only after
  validation and commit. Back up the data directory to guard against
  losing the server's own disk. The database also contains the code.
- Sequences are monotonic per relay. A duplicate or a delayed snapshot
  never overwrites a more recent observation from the same relay.
- An empty/partial map does not replace an older complete map of the same
  robot. A missing position keeps the last known position, grayed out.
  No geometry or position is ever borrowed from another robot.
- After 15 seconds without data, the page shows **Relay offline** and
  keeps displaying the latest information and its age. A snapshot
  retransmitted late is not shown as a fresh position.
  Keep the server and phone clocks synchronized automatically.
- The page works without a CDN, external library or Internet access.
  Zoom, panning, base and position reuse the POC's map rendering.
  The Kotlin domain logic and BLE tracking stay on Android; the Android Compose
  screens do not run in the browser.

Code errors are limited to five attempts per minute and per IP, with an
additional global limit. Once blocked, wait one minute. No CORS,
no code in the URL and no code embedded in the page. The browser session is
remembered for **90 days** in an HttpOnly/SameSite=Strict cookie.
The code is cleared from the field after sign-in and is not stored in
localStorage. Sessions are stored as hashes in SQLite and
survive a server restart. "Sign out" revokes the session.
Android uploads always use the `X-Relay-Code` header.

## API

| Method | Route | Purpose |
|---|---|---|
| GET | `/` | Web interface, no embedded code |
| GET | `/app.js` | Web page script |
| GET | `/relay-map.js` | Shared map rendering |
| POST | `/api/login` | Six-digit code to persistent browser session |
| POST | `/api/logout` | Session revocation and cookie removal |
| POST | `/api/snapshots` | Authenticated upload of a v1 snapshot, 8 MiB limit |
| GET | `/api/latest` | Latest snapshot of the active robot, age and availability |

401: incorrect code (or "Sign-in required" on `/api/latest` without a session);
429: too many attempts (5 wrong codes per minute and per IP, 100 in total);
400: invalid snapshot; 413: too large; 404: unknown route; 503: storage
unavailable. At most 8 simultaneous connections, 5 s socket timeout; requests
are not logged. No command route
(scan/STOP/control) is exposed. BLE is handled from Android.

## Keeping the server running

On Linux, use the example service below, adapting `/opt/dumbyluba`
and the `luba` account to the machine. Initialize the database with the previous command
under that account, then enable the service. Do not run it as root.

```ini
[Unit]
Description=DumbyLuba home server
After=network.target

[Service]
Type=simple
User=luba
WorkingDirectory=/opt/dumbyluba
ExecStart=/usr/bin/python3 /opt/dumbyluba/home-server/server.py --data /opt/dumbyluba/home-server/data/relay.sqlite3
Restart=on-failure
RestartSec=5
UMask=0077

[Install]
WantedBy=multi-user.target
```

On **Mac**, an installer is provided:

```sh
python3 home-server/install-macos.py            # or --code NNNNNN on the first installation
```

It validates the code stored in the database first (a different `--code` is
refused before the running service is touched), copies the server and the web files to
`~/Library/Application Support/DumbyLubaServer`, keeps the database in `data/`,
then installs `~/Library/LaunchAgents/fr.luba.home-server.plist`. The server
restarts if it stops and is launched at login. The
`caffeinate -i` process prevents the Mac from sleeping automatically while it runs;
a manual sleep or closing the lid can still make the server
unreachable. At the end it prints the address, the code and the data folder.
The deployed files are independent of the Git checkout.
Run the installer again after an update; the database and its code are kept.

Status and stop:

```sh
launchctl print gui/$(id -u)/fr.luba.home-server
launchctl bootout gui/$(id -u)/fr.luba.home-server
```

Logs: `server.log` and `server-error.log` in the Application Support directory.
Do not copy the database while it is live without its WAL files: use the
SQLite backup command or stop the service before copying the whole `data/`.
On **Windows**, the script works; the service still has to be adapted to the machine.

## Check

```sh
python3 -m unittest discover -s home-server/tests -v
node --test tools/relay-map-test.cjs tools/relay-web-test.cjs
```

The tests cover HTTP upload, the six-digit code and its rate limiting,
SQLite restore after restart, old positions,
duplicates, partial maps and robot separation, as well as
snapshot validation, refused routes and methods, size and connection
limits, the web page and its routes, startup and
the macOS installer (in a temporary folder, without a real launchctl).
The web page shows French texts when the browser language is French, English otherwise.

Hardware test: connect Android, check reception and the map, close
the app/turn off the phone, wait more than 15 seconds and check that the
server still shows the latest data. Restart the server with the
same database and confirm the data is restored. Then test the relay resuming.
