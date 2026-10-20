# DumbyLuba local notifications

Alerts come from local BLE tracking and navigation checks, with no dependency on the Mammotion cloud and no additional firmware navigation command.

## Triggers

| Incident | Trigger | Level |
| --- | --- | --- |
| Robot stuck | Progress check stop (insufficient translation for 3 s, or no progress toward a point for 15 s) | CRITICAL |
| Repeated bumper | 3 distinct 1014 collisions within 30 s; 5 collisions, or a 1207 latched bumper fault | WARNING then CRITICAL |
| Obstacle encountered | Bumper contact followed by a back-off (local route, move to point), once the obstacle photo has been taken or missed: position, delay, camera and attached **photo** (thumbnail, full-size image once expanded), or the reason why there is no photo ([camera](docs/CAMERA.md)) | WARNING |
| Critical battery | Latest fresh robot reading ≤10%, then ≤5%; hysteresis up to 13% / 8% | WARNING then CRITICAL |
| Return to base failed | Interruption in RETURNING, ALIGNING or DOCKING, including BLE loss (CRITICAL); automatic out-of-hours return to base that fails (WARNING) | CRITICAL or WARNING |
| Prolonged BLE loss | 5 s without valid telemetry once the stream is established (two valid reports since arming: at connection the robot answers once, then sends its periodic reports about 5 s later) | CRITICAL |
| Prolonged RTK loss | 10 s without a fresh FIX (`rtk.status` = 4 in an RTK report less than 5 s old) during a move (route, mowing, control, test), link healthy; never with the robot stationary. `pos_level` is not a solution quality: it is 1 in full FIX (`RtkAlertPolicy`) | CRITICAL |
| Robot offline | 30 s without valid telemetry after an unexpected loss | CRITICAL |
| Unexpected stop | Interruption of a route, test navigation or active control | CRITICAL |
| Automatic resume failed | Interruption in CHARGING or RESUMING, or navigation resume refused (CRITICAL); mowing not resumed after an ESP32 gateway update or a gateway loss during recharging (WARNING) | CRITICAL or WARNING |
| Error reported by the robot | Robot code other than lift/bumper classified as alert or stop ([error codes](docs/ROBOT_ERROR_CODES.md)); CRITICAL if a moving activity was stopped without any other alert | WARNING or CRITICAL |
| Low gateway battery | Control through the gateway, fresh health status (received less than 60 s ago, `ReceivedHealth.STALE_MS`), gateway phone not plugged in: ≤24%, then ≤14%; 3-point hysteresis (up to 27% / 17%, `GatewayAlertPolicy`); cleared as soon as it is plugged in or with direct Bluetooth | WARNING then CRITICAL |
| Gateway fell off the robot | Autonomous tracking by the gateway: 3 consecutive Bluetooth readings (1 Hz) of the gateway to the robot 15 dB below the learned mounted level, robot at least 1.5 m from its position at the last mounted-level reading; or Bluetooth link lost while the last reading was 10 dB below (marked "probably"). The gateway stops the robot; the alert carries the phone's position (`GatewayDetachment`) | CRITICAL |
| Gateway disconnected | Wi-Fi tunnel to the gateway silent for 2 s or cut, or gateway unreachable on reconnection (not a Bluetooth loss on the robot side); a single alert until telemetry comes back | CRITICAL if the robot was moving, otherwise WARNING |
| Move beyond the mapped zones | Start of a move to a point whose path has a section beyond the mapped zones (`PointPlanner`, `MapNavPlan.offMap`): length without a map, dotted on the map, no border check | WARNING |
| Rain during mowing | Mowing or route running, weather monitoring on and garden location set: rain observed, imminent or recent, or rain forecast within 30 min (`MowingRainCheck`); the robot is neither stopped nor sent back | WARNING |
| Link restored | New reception after a loss; also a gateway safety stop confirmed by GATT on reconnection | INFO |
| Mowing finished | End of a real mowing (blades) whose cutting is complete; zones and duration verdict, report in Service → Task history | INFO |
| Notification test | Test buttons of the panel settings; no robot command | chosen level |

"Offline" means no communication was observed, not proof that the robot is switched off. A presumed stuck state is not proof of physical contact. Historical collisions already deduplicated by `RobotErrorMonitor` are not counted as new contacts.

Each alert contains the robot, local time, last state and phase, known battery level, local coordinates of the last retained FIX with its timestamp, the reason and a recommended action. Unknown fields remain explicitly unknown. Retained measurements are not presented as a current position.

## Delivery and grouping

- Three separate Android channels INFO / WARNING / CRITICAL, with low / default / high importance respectively.
- One notification per robot and incident type, with a repeat counter; Android grouping per robot.
- Repeats are silent for 15 minutes, including after the user dismisses the notification (silent group behavior, in addition to `OnlyAlertOnce`). An escalation immediately triggers a new alert and moves to the higher channel. The group's maximum severity is kept during this window.
- Continuous observations only republish on onset or escalation; recovery re-arms the detector.
- Local log of the last 50 notifications (structured entries, sorted newest first, time of last viewing) and unpublished incidents persisted before display. The old text history is converted on load. The deduplication state is also kept across restarts.
- Permission or channel blocked: the incident is kept, visible in the log, and published when permission is restored. The time stays that of the incident, even for a delayed publication.
- Publication and writes run on a serialized IO worker: no disk wait before STOP commands.

## Interface and monitoring limits

The **Notifications** panel is opened by the bell on Home, by Me → **Notifications**, or by tapping an Android notification (the app reopens its single window on the panel). It lists the latest notifications (newest at the top, grouped by day, details on tap); the bell badge flags unseen or blocked notifications. The panel's ⚙ button opens the settings: Android 13+ permission, status and setting of each channel, three INFO / WARNING / CRITICAL buttons to test without sending any robot command, clearing the list. See [notifications](docs/features/notifications.md) for the user view.

Notifications must be allowed and the channels kept enabled. Android controls sounds, pop-ups, Do Not Disturb mode and lock-screen display: the app cannot guarantee their visibility or sound in these situations.

Monitoring runs for the lifetime of the ViewModel session, including after an unexpected BLE loss: the alert timer is independent of the watchdog and of the navigation task. It starts after the first valid telemetry. A requested disconnection, a manual STOP or moving to the background disarms the loss alerts: the robot is stopped according to the existing safety rules. Exception: when the foreground link service keeps the link (`RobotLinkService` or relay) and an automatic mission is running (route, mowing, automatic navigation test), the mission and the monitoring continue in the background; only hold-to-run activities stop, and they disarm the alerts. There is no 24/7 monitoring after the process is closed or destroyed, and this system introduces no automatic reconnection or resume.

## Verification

JVM tests (`RobotAlertsTest`, `LocalNotificationsTest`, `AlertJournalTest`, `RtkAlertPolicyTest`, `GatewayAlertPolicyTest`, `NotificationDaysTest`) cover the exact delays, absence of spam, recovery, voluntary stop, RTK loss, battery levels and hysteresis, unknown/stale measurements, repeated collisions, cooldown, escalation, clock changes, log restoration and per-robot incident isolation. The build, the full suite, lint and the Fold deployment are run through `tools/deploy.sh`.

Physical reception of a robot incident must be validated on site under supervision; software tests are not a physical validation of the bumper, docking or Android sound.

Android reference used for silencing repeats: [Notification.Builder.setGroupAlertBehavior](https://developer.android.com/reference/android/app/Notification.Builder#setGroupAlertBehavior(int)).
