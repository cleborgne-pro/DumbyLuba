# Documentation

- **User**: [Features](FEATURES.md), one page per feature (access, settings, safeguards, validation status).
- **Technical**: design, research and test documents, listed below. Many are dated: they describe the state of the
  project at the time they were written. In case of discrepancy, the code and the [feature](FEATURES.md) pages take
  precedence.

## Protocol and robot

| Document | Topic |
|---|---|
| [MAMMOTION_PROTOCOL.md](MAMMOTION_PROTOCOL.md) | BLE, BluFi, protobuf, telemetry: PyMammotion sources and checks (milestone 1 research, 2026-10-06) |
| [MAMMOTION_ACCOUNT.md](MAMMOTION_ACCOUNT.md) | cloud API and MQTT: what is read, what is allowed, what is refused (inventory of 2026-10-11) |
| [STATION_BLE.md](STATION_BLE.md) | station commands and position on the map |
| [ROBOT_ERROR_CODES.md](ROBOT_ERROR_CODES.md) | table of the 664 error codes and the app's response |
| [SENSORS_PROBE.md](SENSORS_PROBE.md) | ultrasonic sensors and perception: what the robot publishes over BLE, DDS → ZMQ perception bridge (Service → Sensors and perception) |
| [CAMERA.md](CAMERA.md) | Agora video stream, token sequence, delays, obstacle photo |

## Route planning

| Document | Topic |
|---|---|
| [path-planning/ARCHITECTURE.md](path-planning/ARCHITECTURE.md) | architecture of the coverage engine |
| [path-planning/ALGORITHMS.md](path-planning/ALGORITHMS.md) | comparison of algorithms |
| [path-planning/PARAMETERS.md](path-planning/PARAMETERS.md) | engine parameters |
| [path-planning/ENGINE.md](path-planning/ENGINE.md) | production engine: single engine, continuous mowing, nearest start, sweep bar |
| [ROUTE_OPTIMIZATIONS.md](ROUTE_OPTIMIZATIONS.md) | route preparation and zone entry |
| [REAL_COVERAGE.md](REAL_COVERAGE.md) | real coverage of a mowing from its track (CoverageMap), gaps as planner targets |

## Execution and safety

| Document | Topic |
|---|---|
| [ROBOT_ROUTE_BLE.md](ROBOT_ROUTE_BLE.md) | route applied to the robot over BLE, continuous tracking (written for 0.11.0) |
| [TRACKING_EXECUTION.md](TRACKING_EXECUTION.md) | modeled tracking bench (175 robots per speed, 2026-10-10) |
| [TRACKING_DELAY.md](TRACKING_DELAY.md) | command delay and the "deviation greater than 30 cm" stop |
| [BLE_NAVIGATION_AUDIT.md](BLE_NAVIGATION_AUDIT.md) | audit of local BLE navigation and the built-in recharge cycle (2026-10-08) |
| [MOWING_RESUME.md](MOWING_RESUME.md) | saving and resuming a mowing session |
| [OBSTACLE_BYPASS.md](OBSTACLE_BYPASS.md) · [LOCAL_REPLANNING.md](LOCAL_REPLANNING.md) | back-off and bypass after a contact (2026-10-11); local route repair (earlier approach, now limited to the obstacle recorded during a Service run) |
| [DIFFICULTIES.md](DIFFICULTIES.md) | obstacles encountered (photo) on the map, difficulties heatmap, presumed obstacles and avoided areas |
| [MOTOR_WATCHDOG.md](MOTOR_WATCHDOG.md) | command cutoff test: does the robot stop on its own when commands stop |
| [LIFT_DETECTION.md](LIFT_DETECTION.md) | lift detection without a "lifted" field: signals combined by `LiftGuard` |
| [BACKGROUND_LINK.md](BACKGROUND_LINK.md) | Bluetooth link kept in the background by a foreground service |
| [../LOCAL_NOTIFICATIONS.md](../LOCAL_NOTIFICATIONS.md) | local alerts: incidents, triggers and levels |
| [MOWING_SIMULATION.md](MOWING_SIMULATION.md) | mowing simulator: scenario, battery and recharge model (written for 0.10.0; menu paths have changed) |

## Gateway, relay and measurements

| Document | Topic |
|---|---|
| [ANDROID_GATEWAY.md](ANDROID_GATEWAY.md) | Android gateway (Redmi A1) placed on the robot: roles, protocol, discovery |
| [AUTONOMOUS_GATEWAY.md](AUTONOMOUS_GATEWAY.md) | autonomous route tracking by the gateway (tested in simulation, used on the robot during the tests of Oct 14 to 16; full checklist not yet run on the robot) |
| [AUTONOMOUS_GATEWAY_MEASUREMENTS_2026-10-11.jsonl](AUTONOMOUS_GATEWAY_MEASUREMENTS_2026-10-11.jsonl) · [GATEWAY_BYPASS_MEASUREMENTS_2026-10-12.jsonl](GATEWAY_BYPASS_MEASUREMENTS_2026-10-12.jsonl) | raw measurements quoted in AUTONOMOUS_GATEWAY.md |
| [REDMI_RELAY_POC.md](REDMI_RELAY_POC.md) | relay to the home server |
| [RELIEF.md](RELIEF.md) | estimated terrain and the "Signals and terrain" view (menu paths have changed: see [signal surveys](features/signal-surveys-and-terrain.md)) |
| [RAIN_WEATHER_FUSION.md](RAIN_WEATHER_FUSION.md) | rain: radar and forecast in observation mode; planned fusion with the robot's sensor |

## Test procedures

| Document | Topic |
|---|---|
| [ROBOT_TRIAL_PLAN.md](ROBOT_TRIAL_PLAN.md) | single field-trial plan for the three NOW roadmap items: Redmi autonomous tracking, motor watchdog and recharge/resume cycle, map sync with the Mammotion app; order of the day, measurements, report template |
| [MILESTONE_1_TEST.md](MILESTONE_1_TEST.md) | first physical test: connection and telemetry, robot stationary |
| [DIAGNOSTIC_CAMPAIGN.md](DIAGNOSTIC_CAMPAIGN.md) | Bluetooth diagnostics campaign, robot stationary, blades OFF |
| [BLADE_TESTS.md](BLADE_TESTS.md) | supervised blade height and rotation tests (written for 0.1.4) |
| [MAP_NAVIGATION_TEST.md](MAP_NAVIGATION_TEST.md) · [NAV_RANDOM_TEST.md](NAV_RANDOM_TEST.md) | navigation test on the map (now Service → Navigation test); former random walk (historical) |
| [OBSTACLE_TESTS.md](OBSTACLE_TESTS.md) | bumper and obstacle tests (October 2026 updates) |
| [MAP_PREVIEW_TEST.md](MAP_PREVIEW_TEST.md) | map and route preview (historical, 0.3.0 – 0.4.x) |
| [DEBUG_MOWING.md](DEBUG_MOWING.md) | trial mowing (painted zone) and border following |
| [NATIVE_MOWING_TEST.md](NATIVE_MOWING_TEST.md) | former native mowing (historical, no longer in the app) |

## Interface

| Document | Topic |
|---|---|
| [UI_MAMMOTION.md](UI_MAMMOTION.md) | interface inspired by the Mammotion app (written for 0.21.0) |
| [BRANDING.md](BRANDING.md) | name and visual identity (0.9.0) |

## Firmware, hardware and server

| Document | Topic |
|---|---|
| [../firmware/esp32-gateway/README.md](../firmware/esp32-gateway/README.md) | ESP32 gateway firmware (PlatformIO): build, upload, over-the-air update, native tests |
| [../firmware/esp32-diag/README.md](../firmware/esp32-diag/README.md) | ESP32 diagnostics firmware: board checks before the gateway (charging, Wi-Fi, Bluetooth scan, latency), no robot control |
| [../hardware/luba-esp32-dock/README.md](../hardware/luba-esp32-dock/README.md) | ESP32 enclosure charged by contacts at the base: STL parts, shopping list |
| [../home-server/README.md](../home-server/README.md) | home server: installation and usage |

## Project

| Document | Topic |
|---|---|
| [../CHANGELOG.md](../CHANGELOG.md) | version history |
| [../CONTRIBUTING.md](../CONTRIBUTING.md) · [../SECURITY.md](../SECURITY.md) | contributing, reporting a vulnerability, threat model |
| [../THIRD_PARTY.md](../THIRD_PARTY.md) | third-party sources, licenses and services |
