# Mammotion account: what can be read and done

Inventory of 2026-10-11. Sources: PyMammotion 0.10.10 (commit `967c7ac9`), Mammotion-HA 0.6.17
(commit `e81891c9`) and the repository's proto files (`app/src/main/proto/pymammotion/proto`).
**[V]** = verified in the source code; **[I]** = inferred, not verified on the robot.

## Account channels

| Channel | Access | Used by the app |
|---|---|---|
| OAuth2 `id.mammotion.com/oauth2/token` | Bearer token + refresh | yes |
| `{iot}/v1/user/device/page` | list of robots (`deviceName`, `iotId`, `productKey`, `status`) | yes |
| `{iot}/v1/mqtt/auth/jwt` + MQTT TLS | robot events | yes |
| `{iot}/v1/mqtt/rpc/thing/service/invoke` | sends a protobuf `LubaMsg` to the robot | yes, filtered by `CloudReadOnly` |
| `https://domestic.mammotion.com` (same Bearer) | application API (history, firmware, error codes…) | yes: 2 reads, update launch, wake-up |

## Read by the app (0.26.0, Me → Mammotion account screen)

Everything goes through reads. The `CloudReadOnly` safeguard only accepts cloud sync (`todev_ble_sync` 3), map
reading (`todev_gethash`, `todev_get_commondata`), the exact subscription configurations
`Commands.reports(keep, account)`, the camera commands (`set_video` on the left position, `req_encode`) and the two
navigation actions described below; anything else is refused with "Cloud command forbidden: map reading, telemetry,
camera and native return to base only".

- **State** (`report_info.dev`): robot state (WorkMode codes [V], e.g. 11 ready/at the base,
  13 mowing, 14 returning, 19 paused, 39 recharge pause, 48 standby), battery, charging contact,
  lock, battery heating.
- **Current task** (`report_info.work`, Mammotion-HA encoding [V]): `area >> 16` = progress %,
  `area & 0xFFFF` = area m², `progress & 0xFFFF` = total duration (min), `progress >> 16` = remaining
  duration (min), `man_run_speed / 100` = speed m/s, cutting height.
- **Positioning** (`report_info.rtk`): RTK solution, L1/L2 satellites (`dis_status` bits 16-23
  and 24-31), satellites in common view (`co_view_stars`), correction age (s), robot/base
  scores, base moved, RTCM ready.
- **Network** (`report_info.connect`): network in use (Wi-Fi/4G), Wi-Fi and 4G RSSI.
- **Maintenance** (`RIT_MAINTAIN`, added to the cloud subscription): mileage (m), working time (s),
  battery cycles, blade wear (s) and alert threshold set in the Mammotion app.
- **RTK base** (`RIT_BASESTATION_INFO`): version, state.
- **Versions** (`report_info.fw_info`): firmware and per-module versions.
- **Mowing history**: `POST /device-server/v1/device/work-report/page`
  `{deviceName, pageNumber, pageSize}` (first page, 10 records, shown under **Latest mowings**) → name, type (1 one-off, 2 schedule, 3 on demand,
  4 resume), result (1 in progress, 2 paused, 3 stopped, 4 interrupted, 5 finished), area,
  progress, duration, start, end [V].
- **Firmware**: `POST /device-server/v1/devices/version/check` `{deviceIds:[iotId]}` → installed
  version, available update, release notes, update in progress [V]. No launch.
- Already present before: map (zones, exclusions, passages, base), position, battery and charging
  (charging animation), error codes (`toapp_err_code`, `debug_errocode_report`).

The full read is triggered when the Account screen opens and by "Refresh from the
account" (history and firmware over HTTP, then the MQTT reports; a failed part is listed, the others stay
displayed). On the home screen and the map, the 30 s polling (without BLE, app in the foreground) fills in the same
details; each poll waits 15 s at most for the position and battery. A map read waits 90 s at most.

## Account actions (without BLE, on explicit request)

Two exceptions to read-only access, each behind a user confirmation.

### Native return to base

Map → "Charging station", when Bluetooth is not connected and the account follows this robot
(`map/CloudStationDialog.kt`, `CloudViewModel.nativeReturnToBase`).

1. MQTT connection, report subscription (`sync(3)` + `reports(account=true)`), wait for a recent
   `report_info.dev` report. Robot silent for 15 s: `POST /device-server/v1/device/wakeup {deviceName}`
   (standby 48), then at most 60 s before the "robot unreachable" failure.
2. Already charging → nothing is sent. Update in progress (16) → refused. Already returning (14) → tracking
   only. Otherwise `todev_taskctrl {type 1, action 5}` via `thing/service/invoke`.
3. Tracking (`CloudReturnTracker`): state 14 = return in progress, charging contact = finished, any other state
   after 14 = interrupted, no 14 within 45 s = not started, 30 min = too long. The subscription is
   refreshed every 8 s; an MQTT drop is recovered without resending the command (5 reconnection attempts, 3 s
   apart).
4. "Stop the Mammotion return" (shown while the return is requested or running): `todev_taskctrl {type 1,
   action 12}`, always accepted by `CloudReadOnly`. A cloud refusal points to the Mammotion app or the robot's STOP
   button.

The "Charging station" dialog requires the checkbox "I am supervising the robot and the way to the station is clear"
before **Recharge (native Mammotion)**.

`CloudReadOnly` only accepts action 12 and, with the `nativeReturn` flag, action 5 (type 1,
navigation recipient): mowing, pause, resume, task cancellation and leaving the base remain refused.

### Firmware update

Me → Mammotion account → Software (`auth/FirmwareUpdateUi.kt`, `CloudViewModel.startFirmwareUpdate`).

- Launch: `POST /device-server/v1/ota/device/upgrade {deviceId: iotId, version}` (PyMammotion
  `start_ota_upgrade`), version = `productVersionInfoVo.releaseVersion` from `version/check`. Refused if
  no update is offered, if an update is already in progress or if the robot is working
  (states 13, 14, 16, 19, 20, 39, 45-47); the button is also disabled while a native return is tracked. The
  "Update the robot?" dialog requires the checkbox "The robot is stopped (ideally on its base), the battery is
  sufficient, and I will not use it during the update". The cloud's `causeMsg` is shown when it refuses.
- Progress (`FirmwareUpdateTracker`), all sources merged:
  MQTT property `otaProgress` (`…/thing/event/property/post`, flat form or Aliyun
  `items.otaProgress.value`, object or JSON string; `result` 0 success, 1 or negative failure, 2 or absent
  in progress), protobuf report `net.toapp_upgrade_report` if it arrives, `deviceState`/`sys_status`
  16 / 22 / 23, and `version/check` re-read every 15 s (it lags behind: it only ends tracking once
  the installed version is the target version). Progress is monotonic within a given `otaId`.
- End of tracking: success, failure, no progress received within 10 min of the request, 30 min of silence (the
  robot reboots to install) or 2 h. After an MQTT drop, `version/check` is re-read 3 times 5 s apart before the next
  connection. "Stop following" does not interrupt the update; an update launched
  elsewhere can be tracked ("Follow progress").

While tracking, the periodic account polling is suspended (a single MQTT connection): the tracking
itself updates state, battery and position.

Not verified on the robot at delivery: actual cadence of `otaProgress` from the cloud and
behavior of the native return from standby.

## Readable but not yet used

- **MQTT `thing/event/property/post`** (already received through the subscription wildcard; only `otaProgress` and
  `deviceState` are read, while tracking an update): `batteryPercentage`, `knifeHeight`,
  `networkInfo` (RSSI, `mileage`, `work_time`, `bat_cycles`), module versions [V]. Partial duplicate
  of the protobuf reports.
- **MQTT `/sys/{pk}/{dn}/app/down/thing/status`**: `online`/`offline` [V] (not subscribed; of little use
  with short 15 s connections).
- **Events** `device_warning_event`, `device_warning_code_event`, `device_notification_event`:
  alert codes [V]. Useful with a long-lived MQTT connection (not chosen: polling every 30 s).
- **HTTP**: catalog of translated error codes (`/device-server/v1/code/page-lan`,
  `/user-server/v1/code/record/export-data`, already integrated offline), detailed list
  (`/device-server/v1/device/list`: serial number, position `locationVo`), products and capabilities, RTK
  devices (`/device-server/v1/rtk/devices`), video quotas, sharing.
- **Pure-read protobuf, outside the allowlist**: `batch_query_req` (rain sensor, LED,
  cutting mode, no-mow hours, rain/child protection), `todev_get_dev_fw_info`,
  `ota.todev_get_info_req`, `todev_networkinfo_req`, error log `bidire_comm_cmd`
  (`rw=1`, `id=5`, already used over BLE). Each would require widening `CloudReadOnly` after a test.
- **To validate before any use**: reading schedules (`todev_planjob_set`) and the protobuf
  history (`todev_work_report_cmd`) — the read `subCmd` value is not known; a
  wrong value writes a schedule.
- **Sensitive data deliberately ignored**: IMEI/IMSI/ICCID, 4G APN with credentials,
  Wi-Fi password.

## What the account allows (not implemented)

Commands available over the same channel [V], refused by `CloudReadOnly` (except return to base 5
and return cancellation 12, see above):

- **Tasks** (`todev_taskctrl`, action): start 1, pause 2, resume 3, cancel 4, return to
  base 5, resume from the stop point 7, cancel return 12; leave the base; resume the
  last task; run a schedule.
- **Settings**: cutting height, speed, cutting mode, blades, schedules, no-mow hours,
  battery return/resume thresholds, rain/animal protection, side LEDs, reboot, blade
  counter reset.
- **HTTP**: map backup/restore, RTK pairing (update launch, wake-up and camera
  stream are implemented).

Project rules: starting, resuming or delegating a mowing to the native planner is forbidden;
only the native return to base chosen by the user is allowed (BLE or account). STOP of a
native task via the cloud (pause 2 or cancel 4) remains to be opened after a real test: not done.
