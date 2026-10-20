# Redmi A1 gateway mounted on the robot

The Redmi A1 stays on the LUBA 2 and holds the **Bluetooth** link with it. The pilot
phone (Fold) talks to it over **Wi-Fi**: every app function (scan, connection,
map, routes, BLE mowing, return to base, joystick, tests) goes through the gateway, with no
Bluetooth range limit between the Fold and the robot.

```
Fold (planning, tracking, safety)  ── Wi-Fi TCP 8766 ──  Redmi A1 (on the robot)  ── BLE ──  LUBA 2
```

## Principle

- **The Redmi connects to the robot itself and holds the Bluetooth link**: on the Redmi, Service →
  Bluetooth connection, tap the robot (or automatic connection if it is the only one).
  The robot is remembered; the gateway reconnects to it on its own (loss, restart, update)
  and sends a sync every 5 s when no pilot is attached.
  "Release the robot" on the same page hands the robot back to other phones / the Mammotion app.
- The Fold **attaches** to this link: it connects immediately if the Redmi already holds the
  robot. The gateway numbers the BluFi frames itself (sequence byte rewritten), which
  forms a single sequence across its own frames and those of successive pilots; an arriving pilot
  only receives notifications starting from the beginning of a message.
- The gateway **decides no command**: it writes to FF01 the BluFi frames the
  Fold prepared and returns the FF02 notifications, one GATT operation at a time, with the
  actual write confirmation. The Fold keeps the same planning, the same geometric
  checks, watchdogs, guards (lift, bumper, blades) and the same reconnection.
- **Autonomous tracking** (2026-10-12): during a route, the Fold no longer drives frame by frame; the Redmi follows the prepared route
  ([AUTONOMOUS_GATEWAY.md](AUTONOMOUS_GATEWAY.md)). In that case, silence or a Wi-Fi drop **does not stop** the robot
  (tracking continues, automatic stop after 20 min without a pilot); what follows applies outside autonomous tracking.
- **Safety stop by the gateway**: if the Fold goes silent for more than **2 s** (no frame,
  not even the PING sent every 250 ms) or if the Wi-Fi connection drops without a deliberate
  close, the Redmi itself writes, continuing the BluFi sequence: blades OFF if
  they may be spinning (ON command not followed by OFF, or non-zero blade bits in the
  telemetry), then zero speed — each frame confirmed by GATT. The Bluetooth link is
  kept. If a fragmented message is in progress (map read), no frame can be
  injected: the link is closed (reconnection afterwards) and the stop is reported as "not guaranteed".
  A deliberate departure of the Fold (disconnection, STOP already sent) adds no frame.
- **Gateway stopped while piloting** (**Stop** button, **Stop the gateway** in the notification, role
  change): the Redmi first writes the same safety stop (blades OFF if needed, zero speed, frames
  confirmed), notifies the pilot ("gateway stopped", with the result of the stop; gateway unreachable), then
  closes Bluetooth. The stop runs off the main thread, with CPU and Wi-Fi locks held until the end.
- GATT timeout or unanswered connection on the gateway side: link closed, pilot notified as for a Bluetooth
  loss, new attempt after 10 s (and not at the next tick).
- On the Fold side, gateway silence beyond 2 s counts as **link loss**: same
  handling as a BLE loss (mission interrupted, resume point kept, only the link
  reconnected, no command resumed).
- **Stop report**: the Fold reports the loss as "STOP not guaranteed". When it reconnects (within 30 min),
  the gateway hands over the result of the stop it wrote in the meantime (`gateway_emergency_stop` in the
  log): "STOP written by the gateway … (HH:MM:SS) — confirmed by GATT, check the physical stop", or a
  critical alert if the stop could not be confirmed. Delivered only once.
- One pilot session at a time; a second phone receives "Gateway busy with another pilot", shown as a refusal
  from the gateway (reachable), not as a Wi-Fi drop. The gateway logs "Connection of … refused: a pilot is already
  connected (…)".
- **Same phone reconnecting**: if it comes back (Wi-Fi dropped for a moment) while its old session is
  ending (2 s silence then safety stop), the gateway makes it wait (PING, 8 s at most) instead of
  refusing it, then accepts it.
- The robot requested by the Fold (address and name) is remembered by the gateway, which reconnects to it after a
  Redmi restart.
- Authentication: random challenge from the gateway, HMAC-SHA256 response from the **six-digit
  code** shown on the Redmi (never sent in clear), at most 5 wrong attempts per minute.
  Traffic is not encrypted: for the home Wi-Fi only.
- Automatic discovery: mDNS announcement `_dumbyluba._tcp`; the address found is remembered
  and tried first.

## Gateway health reported to the pilot

Every 5 s (`HEALTH` frame, protocol version 2), the gateway sends the pilot the state of the phone mounted on the robot:
battery (% and whether plugged in), battery temperature, Wi-Fi (signal in dBm and link rate), Android thermal state, battery
saver, battery optimization exemption and screen. The pilot shows it on Home and in Service (one line, in
orange with a ⚠ when needed) and in detail in Service → Wi-Fi gateway (**Gateway health** group: Battery, Battery
temperature, Wi-Fi, Thermal, Screen, Battery saver, Battery exemption); the gateway also shows its own health in
Service → Wi-Fi gateway ("Health of this phone (reported to the pilot)"). Warning conditions: battery < 25 % and not
charging ("very low" below 15 %), overheating (thermal ≥ moderate, otherwise battery ≥ 45 °C), Wi-Fi ≤ −70 dBm,
battery saver on, app not exempt from battery optimization. An unknown Wi-Fi signal (Android reports −127 dBm) and an
unknown battery level are shown as "unknown", not as a measurement (0.65.1). Without a recent health state, the group
says "No information received: gateway not contacted yet.", "Gateway reachable; health state not received yet." or
"Gateway unreachable: …". Each state received is also
written to the pilot's log (`gateway_health`). Both phones must run the same app version.

## Setup

> **No pairing and no detour through the Mammotion app is needed.** Quite the opposite: the robot
> accepts only **one** Bluetooth link and stops advertising while it is open. If the
> Fold (DumbyLuba in "Direct Bluetooth", which connects automatically when opened) or
> the Mammotion app is connected to the robot, the Redmi cannot see it. First switch the Fold to
> "Control via the gateway" and close the Mammotion app.

1. Install the same app version on both phones (`tools/deploy.sh` installs on
   all distinct ADB devices). The same gateway protocol is required; different app versions only get a note.
2. **Redmi**: Service → Wi-Fi gateway → role **Gateway** ("Gateway on the robot"). Allow Bluetooth.
   Note the code shown. Exempt the app from battery optimization ("Battery" button, direct system request).
   The gateway restarts on its own after a phone restart or an app update,
   and every time the app is opened.
3. **Fold**: Service → Wi-Fi gateway → **Pilot**, **Scan the gateway QR code** (or choose the gateway in
   *Gateways on this Wi-Fi* (announced as "DumbyLuba passerelle · Redmi A1"), enter the code,
   **Find and test**). From then on, robot scanning and connection go through the Redmi: tap the robot in
   Service → Bluetooth connection, the Redmi connects to it, holds it and remembers it (no action on the Redmi).
4. Turn **Go through the gateway** off to drive over direct Bluetooth without forgetting the gateway.

## Automatic connection and two phones next to the robot

- **Fold in "Control via the gateway"** (setting to keep): scanning goes through the Redmi;
  if it is unreachable (off, out of Wi-Fi), automatic fallback to direct Bluetooth. Reconnection
  after a loss stays on the same path; the gateway takes over again at the next scan.
- **Fold in "Direct Bluetooth"**: if a gateway is advertising on the Wi-Fi, direct automatic
  connection is suspended to leave the robot free; manual connection is still possible.
- **Redmi gateway**: holds the link in its service; while it holds it, the robot no longer
  advertises, and the Fold's scan through the gateway returns that robot directly.
- Wireless ADB debugging on the Redmi drops every time it roams between two Wi-Fi access points of the same
  network (tied to the BSSID); the gateway is not affected (same IP). For development:
  `adb tcpip 5555` then `adb connect IP:5555`, valid until the phone restarts.

## Latency and link losses (measurements of 2026-10-11)

Navigation test in gateway mode: GATT writes of 500 to 1300 ms (median 74 ms, versus 37 ms and
198 ms maximum in direct Bluetooth), Fold queue saturated (> 4 frames) so the route was interrupted, and robot error
−2804 "Robot control delayed due to weak Bluetooth signal" while the Redmi ↔ robot signal was
excellent (−24 dBm): the error comes from delayed commands, not from Bluetooth range.

Ping Fold → Redmi (60 packets, average / maximum): the **Redmi's Wi-Fi**, not Bluetooth, is the culprit.

| Redmi | Average | Maximum |
|---|---|---|
| screen off, app stopped | 224 ms | 770 ms |
| screen off, pilot attached (high-performance Wi-Fi lock) | 98 ms | 611 ms |
| screen on, Bluetooth held | 60 ms | 421 ms |
| screen on, neither app nor Bluetooth | 22 ms | 174 ms |
| screen dimmed by the gateway, pilot attached (version 0.27+) | 71 ms | 318 ms |

- With the screen off, the phone's Wi-Fi goes into power saving. The gateway can keep the screen on during a
  piloting session (Service → Wi-Fi gateway → "Keep the screen on while piloting"), then lets it turn off.
- **Off by default since 2026-10-13 (0.27.9)**: in practice the screen stayed at full brightness (the wake-up
  counts as a user action, system sleep at 30 min) as soon as the Fold was connected, including with the robot on
  its base. Redmi `batterystats` measurement: ~1.5-2 %/h with the screen off, **~8 %/h** with the screen held on (70 → 29 % in
  under 5 h). Autonomous tracking has taken Wi-Fi out of the control loop; only the short sequences still
  driven from the Fold (bypass, docking, recharge) remain sensitive to latency. Turn the option back on if
  they fail on slow writes without `gateway_gatt_slow`.
- Wi-Fi / Bluetooth coexistence on the Redmi and its Wi-Fi signal (−72 dBm measured, 2.4 GHz) leave a residue of
  a few hundred ms: moving the Wi-Fi access point closer or adding a repeater on the garden side remains the real fix.
- The pilot's log now separates the two legs: `gateway_gatt_slow` (gateway Bluetooth write
  ≥ 150 ms, field `gattMs`); a slow write without this event is a Wi-Fi delay.

## Base exit interrupted "BLE interrupted or queue saturated" (2026-10-11, 19:36)

Fold log: base exit with blades OFF, setpoint every 200 ms. Writes at ~50 ms for 3.5 s, then 268, 389,
732 ms: the queue exceeded 4 frames and the route stopped (STOP confirmed). No `gateway_gatt_slow`: the
Redmi ↔ robot Bluetooth (−24 dBm) responded quickly; the delay came from the **Redmi's Wi-Fi** (−73 then −76 dBm, 52 Mbit/s). Over the whole
session, including with the robot stationary: median 70 ms, p95 684 ms, 80 writes out of 503 above 500 ms. At 19:23, an
RSSI read of 1.5 s, robot at rest, had also dropped the link (GATT timeout).

Fixes (version 0.27.1):
- **Latest setpoint only** (`MotionSetpoints`): a new motion setpoint replaces the non-zero setpoints
  still pending, instead of piling up behind a slow write. Zero setpoints (stop) and the rest of a
  message being written are never removed. `setpoint_replaced` event in the pilot's log.
- **RSSI**: through the gateway, read every 30 s and never during motion (it is the Redmi ↔ robot signal, constant).
- **GATT operation timeout**: 1.5 s in motion (unchanged); 3 s at rest through the gateway, where a Wi-Fi delay
  is not a link loss (the 2 s silence is still detected by the link and by the gateway).

Limitation: with 700 ms writes, the robot receives about 1.4 setpoints per second instead of 5. Route tracking
remains under control (positions, protections), but the underlying fix remains better Wi-Fi on the garden side (2.4 GHz repeater).

## GATT errors during mowing and recharge (2026-10-16, 13:25 and 13:49)

Fold log (0.60.0), Redmi gateway on 0.58.0, mowing with blades at 40 mm on a zone handed to the gateway.

- **13:49, recharging at the base**: a keepalive sync (`sync(2)`) was confirmed by the gateway in
  **1.8 s**, with GATT status 0 and no `gateway_gatt_slow` (Redmi ↔ robot Bluetooth < 150 ms): it was the
  Redmi's Wi-Fi (−74 dBm, screen off). The strict 1.5 s timeout, applied to every write during a mission, declared
  "FAILED — GATT timeout" and the mowing stopped on the base, at 20 % progress. It was not a fault.
- **13:25, while mowing**: the gateway stopped its tracking on "BLE interrupted or queue saturated", then could not write
  the stop ("GATT write blocked: link closed, stop not guaranteed"). Robot reports were still arriving
  until the close: a Bluetooth write remained **unconfirmed for more than 3 s** while the link
  was alive. The first reconnection failed ("Robot not responding over Bluetooth"), the second succeeded 25 s later.
  The logged reason did not say which of the four possible causes had occurred.

Fixes (version 0.60.1):
- **Timeout for keepalive writes through the gateway** (`KeepaliveLoss.timeoutMs`): sync, report
  subscription and RSSI wait **5 s** instead of 1.5 s, whether or not a mission is running. The gateway closes its
  Bluetooth link itself after 3 s without a GATT response and reports it as a loss: the rest of the timeout covers the Wi-Fi
  round trip. Setpoints, STOP and blades keep 1.5 s during a mission; so do joystick and on-screen tests; 3 s through
  the gateway without a mission, 1.5 s in direct Bluetooth (unchanged).
- **Precise reason**: the failure message names the write and its wait ("GATT timeout (sync(2) unconfirmed after
  5.0 s)"); the gateway's tracking stops on "BLE interrupted:" followed by the cause
  (`GatewayRobot.linkProblem`): robot Bluetooth link closed, write refused by the robot (code), write
  unconfirmed by the robot for N s, write queue saturated (N frames).

Limitation: a Bluetooth write left unanswered during mowing (13:25) still stops tracking, since the robot's stop
cannot otherwise be guaranteed; the cause (robot busy, Redmi Bluetooth stack, Wi-Fi coexistence) is yet to be measured with the
new reason. Resuming remains manual, from the saved progress.

## Points to validate on the robot

- 2.4 GHz Wi-Fi coverage over the whole garden (the Redmi A1 has no 5 GHz); measure
  drops > 2 s, which stop the mission.
- Latency added by Wi-Fi to trajectory tracking (compare with `TRACKING_DELAY.md`).
- Redmi battery life: permanent CPU lock, high-performance Wi-Fi only during a
  session. The Redmi is not charged by the robot.
- Actual safety stop: cut the Fold's Wi-Fi during a route with blades stopped and check
  the physical stop; the Redmi's log (Service → Wi-Fi gateway, *Gateway log*) shows "STOP written by the gateway".
- The relay to the home server (`REDMI_RELAY_POC.md`) reads the link state of the phone
  that launches it: with the gateway, launch it from the Fold.

## Tests

JVM tests in `app/src/test/java/fr/luba/poc/gateway/`. `GatewayTest`: HMAC proof and rate limiting, BluFi sequence continuity of the stop
frames, blades OFF at the commanded height, refusal to inject in the middle of a fragmented message,
full tunnel over local sockets (writes, notifications, RSSI), deliberate close without
an added stop, stop on a silent pilot and on a dropped socket, stop written before closing when the gateway stops,
retry delay after an unanswered connection, same phone reconnecting while its previous session
ends and stop report (delivered once), "busy" refusal seen as a reachable gateway, robot
requested by the pilot remembered, wrong code, scan through the gateway. Others: `GatewaySearchTest` (search
over mDNS and failure messages), `GatewayClientTest`, `GatewayConfigTest`, `GatewayServiceTest`,
`GatewayHealthStoreTest`, `GatewayHealthDisplayTest`, `GatewayTiltTest`, `GatewayLinkProblemTest` and
`KeepaliveLossTest` (0.60.1), `GatewayOtaTest` (ESP32 update), `GatewayAutonomyTest` and
`GatewayLinkAutonomyTest` (autonomous tracking), `SilenceKeeperTest`, `SilencePolicyTest`,
`WirelessDebugKeeperTest`, `WirelessDebugPolicyTest`, `PhoneWifiTest`, `PhoneSensorsTest`.

## Silent gateway phone

Android restores ringtone, notification and alarm volumes to a loud level (restart, updates, keys
pressed by accident). In the gateway role, the app keeps the Redmi silent (`SilenceKeeper`, started when the
process starts, no effect in other roles):

- ringtone, notifications, alarms, system, media and call at minimum, then silent mode and "Do Not
  Disturb" in total silence;
- restored 300 ms after each change of volume, ringer mode or "Do Not Disturb", and checked every
  30 s; volumes are lowered before silent mode, which hides their actual value;
- "Do Not Disturb" access granted by `tools/deploy.sh` (`adb shell cmd notification allow_dnd fr.luba.poc`) or
  by the panel button; without it, only the volumes are lowered;
- "Keep this phone silent" switch in Service → Wi-Fi gateway (*Gateway settings*, on by default), with the
  last actions below it and, when access is missing, the `adb` command and an **Allow "Do not disturb"** button; when it is turned off (or the gateway role
  is left), "Do Not Disturb" is handed back if the app had turned it on, and volumes stay as they are.

Verified on the Redmi A1 on 2026-10-12: after deployment, "Do Not Disturb" in total silence (`zen_mode` 2),
ringtone, notifications, system and media at 0; with "Do Not Disturb" turned off and ringtone set back to 12 via ADB: ringtone
12 → 0, silent mode and "Do Not Disturb" restored immediately; actual volume still at 0 at the next check.
Alarms: minimum 3 without a system permission, muted by total silence. The Fold (pilot) is not affected.

## Permanent wireless debugging

Android turns wireless debugging off on every Wi-Fi loss (Redmi at the far end of the garden), on every
change to a non-approved access point and on every restart. The app turns it back on by itself
(`WirelessDebugKeeper`, started when the process starts, whatever the phone's role):

- `WRITE_SECURE_SETTINGS` permission, granted by `tools/deploy.sh` after each installation
  (by hand: `adb shell pm grant fr.luba.poc android.permission.WRITE_SECURE_SETTINGS`);
- re-enabled 3 s after Wi-Fi returns or the setting is turned off, checked every 60 s,
  at most one attempt every 10 s; "Keep wireless debugging on" switch (on by default; "Permission missing" and the
  `adb shell pm grant` command when the permission is absent) and log in Service → **Advanced settings** (moved
  there from the gateway page in 0.54.0);
- without a dialog only on an already approved network ("Always allow on this
  network"); the Mac finds the phone again by itself via mDNS (new port on each activation).

Verified on the Redmi A1 (Android 12) on 2026-10-11: Wi-Fi cut for 10 s then restored, debugging
back on 7 s after Wi-Fi returned, automatic ADB reconnection from the Mac.

Full Redmi restart verified on 2026-10-12 (`adb reboot`, no action on the phone): wireless debugging
back on and Mac reconnected via mDNS 179 s after the request; gateway service in the foreground, remembered robot
(Luba-VSDEMO01) searched for on its own every 30 s (robot off), authentication challenge served on port 8766.

## Automatic scan and reconnection (0.28.1, 2026-10-13)

Finding: after the reinstall at 14:30, the Fold did not reconnect. The gateway was listening (port 8766 reachable),
but the pilot's link restart only ran while the app screen was in the foreground, and BLE reconnection gave up
after 5 attempts (≈ 30 s).

- **Scan**: the pilot tries the saved address, then **all** gateways advertised over mDNS
  (`_dumbyluba._tcp`, previously only the first one) with the saved code; the one that accepts the code becomes
  the saved address (DHCP address change, another gateway phone). "Find and test" does the same.
- **Reconnection**: in "Control via the gateway", as long as the robot is not connected (startup, update,
  Wi-Fi or Redmi back, reconnections exhausted), one attempt every 15 s, spaced to 30 s then 1 min when
  no gateway answers, indefinitely and whether or not the app is in the foreground. These attempts never fall back to
  direct Bluetooth (the robot stays free for the gateway). A requested disconnection suspends them until the
  next manual scan. No mission or command is resumed.
- On the Redmi side, the gateway service already restarts on its own after a restart or an update (`GatewayBootReceiver`).
