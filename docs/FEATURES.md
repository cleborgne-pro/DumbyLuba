# Features

One page per feature: what it is for, where to find it in the app, its settings, its safeguards and its validation
status. Overview, installation and warnings: [README](../README.md).
Version history: [CHANGELOG](../CHANGELOG.md). Technical documentation: [docs/ index](README.md).

What the official app does not offer (remote control with the camera, animated routes, simulation, coverage maps,
painted zone, remembered last position, obstacle photo, debug tools) is summarized in the
[README](../README.md#what-the-official-app-does-not-do).

> [!IMPORTANT]
> A device placed on the LUBA ([gateway](features/gateway.md)) is required to control it over Bluetooth throughout
> the garden. The ultrasonic sensors are not available: only the bumper detects obstacles.

> [!NOTE]
> **Status** states what has actually been verified: *JVM tests*, *simulation*, *test on the robot*. Many features
> are validated in simulation and tests, and only partly in the field, on **a single** LUBA 2 AWD.

## Getting started

| Page | In short |
|---|---|
| [Getting started and setup check](features/getting-started.md) | three setups, pairing the gateway, Service → Check the setup: Wi-Fi, gateway, robot, placement and safety, link by link |
| [Interface and navigation](features/interface.md) | Device / Service / Me tabs, Service page groups, global STOP bar, light and dark theme, English or French |
| [Supported models](features/supported-models.md) | LUBA 2 AWD validated; LUBA 1, LUBA 3, LUBA mini, Yuka… in read-only mode, how to test your model |
| [Bluetooth connection](features/bluetooth-connection.md) | scanning, connection, link loss, link kept in the background, advanced settings |
| [Mammotion account](features/mammotion-account.md) | optional: map and status through the cloud, camera, native return, firmware update |
| [Map](features/map.md) | BLE or cloud reading, cache, map sheet (background, layers, legend, source), local corrections (zones, no-go zones, obstacles), DumbyLuba zones and passages recorded by driving, beyond the Mammotion boundaries hatched, base and manual accesses, device status, last known position |

## Mowing

| Page | In short |
|---|---|
| [Preparing a mowing session](features/mowing-preparation.md) | zones in mowing order, Zigzag / Optimized / Checkerboard modes, task settings (height, decreasing-height passes, speed, recharge thresholds, border laps), start point, planning engine, robot footprint |
| [Mowing simulation](features/simulation.md) | animated route with battery, recharges and resumes, no command sent to the robot |
| [Real mowing](features/real-mowing.md) | route tracking over BLE, mowing bar, blades, speed, automatic stops, working hours, pivots on slopes, recharge during mowing |
| [Resuming an interrupted mowing session](features/mowing-resume.md) | progress saving for any tracked task, resume with overlap, Unblock, Resume further on, automatic resume after a gateway loss while recharging |
| [Mowing history](features/mowing-history.md) | task history (History button on the map), replay of the actual route, end-of-mowing report, duration and recharges compared with the estimate, adjusted estimates |
| [Obstacles](features/obstacles.md) | back-off and bypass after a contact, encountered and presumed obstacles, difficulties heatmap, saved obstacles, obstacle tests (including the frontal border test), obstacle simulation |
| [Station and moves](features/station-and-moves.md) | charging station dialog: leave the base, recharge, stop the Mammotion return; move to a point |
| [Manual control](features/manual-control.md) | two joysticks, maximum speed 10 to 100 %, blades stopped, camera thumbnail, recording a zone, passage or no-go zone by driving |

## Safety and monitoring

| Page | In short |
|---|---|
| [Safety](features/safety.md) | global STOP, continuous guards, lift detection, 664 error codes, command cutoff |
| [Local alerts](features/notifications.md) | robot stuck or lifted, battery, link or RTK loss, rain… no cloud; notification list and settings |
| [Rain and weather](features/rain-and-weather.md) | RainViewer radar, Open-Meteo forecast, warnings |
| [Robot camera](features/camera.md) | live video, movable and resizable thumbnail, photo of the obstacle after a contact |

## Measuring and diagnosing

| Page | In short |
|---|---|
| [Signal surveys and terrain](features/signal-surveys-and-terrain.md) | Wi-Fi, RTK, Bluetooth, mobile, GNSS heatmaps; dedicated survey route (Full loop or Grid); estimated terrain and 3D view; history |
| [Tests and diagnostics](features/tests-and-diagnostics.md) | Bluetooth diagnostics, detailed telemetry, sensors and perception, cutting height and blade test, navigation test, border following (validated borders), trial mowing (painted zone) |
| [Logs and local data](features/logs-and-data.md) | JSONL logs, ZIP export, data stored on the phone |

## Going further

| Page | In short |
|---|---|
| [Gateway on the robot](features/gateway.md) | Android phone or ESP32 placed on the robot, safety stop, autonomous tracking, gateway health, ESP32 updates over Wi-Fi |
| [Home server](features/home-server.md) | local web page with the robot's status, dependency-free |
| [ESP32 hardware](features/esp32-hardware.md) | PlatformIO firmware, waterproof enclosure charged by induction |

## Milestones

| Milestone | Status |
|---|---|
| M1 Connection and telemetry | Implemented; real BLE campaign |
| M2 Slow forward motion, STOP, motor watchdog | Control and STOP on the real robot; command cutoff test available, **watchdog to be demonstrated per robot** |
| M3 5-minute automatic control | Supervised automatic navigation and real tests; no timed 5-minute campaign |
| M4 30-minute control | Not demonstrated |
| M5 Mammotion map | Implemented: BLE and cloud reading, local cache, local corrections (0.60.0) |
| M6 Position on the map and history | Implemented: last reliable RTK position, track, surveys and progress in local files; task history with replay of the actual route (0.53.0) |
| M7 Safe geometric generation | Implemented: 690 × 513 mm footprint checked on translations, pivots and links |
| M8 External BLE trajectory tracking | Implemented: app-driven mowing, recharge and resume, working hours (0.51.0), resume of any interrupted task (0.61.0); full physical validation still to be continued; autonomous tracking by the gateway not yet tried on the robot |
| M9 Accuracy and comparison | Modeled tracking bench ([results](TRACKING_EXECUTION.md)); field measurements to be completed |
