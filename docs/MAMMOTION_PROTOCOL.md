# Mammotion protocol — milestone 1 research

Code inspected on 2026-10-06. Links are pinned to the commits actually downloaded. No physical compatibility with the LUBA 2 AWD 1000 has been demonstrated yet.

> This page keeps the protocol research made at each milestone (M1, M2, 0.1.x, 0.2.0). Robot compatibility has since
> been exercised on a LUBA 2 AWD (see the [CHANGELOG](../CHANGELOG.md)). Where the link behavior described below has
> changed since, the current behavior is noted in the text; reference pages: [Bluetooth
> connection](features/bluetooth-connection.md) and [Bluetooth link in the background](BACKGROUND_LINK.md).

## Sources examined

- [PyMammotion: BLE commands, transport, protobuf, telemetry and maps](https://github.com/mikey0000/PyMammotion/blob/0bf120b2b390603266c8fe8a4b44347bdc994006/README.md), commit `0bf120b2b390603266c8fe8a4b44347bdc994006`.
- [Mammotion-HA: PyMammotion dependency](https://github.com/mikey0000/Mammotion-HA/blob/9b77f9defe692300800192800b89a6688a3829c9/custom_components/mammotion/manifest.json), commit `9b77f9defe692300800192800b89a6688a3829c9`. This revision requires `pymammotion==0.10.7`: do not assume its dependency is identical to the PyMammotion main branch inspected.
- [Mammo: Go CLI using Aliyun MQTT](https://github.com/mikeymclellan/mammo/blob/85c93ce6abc87205c75353e47e608e7ce1aa82f8/README.md), commit `85c93ce6abc87205c75353e47e608e7ce1aa82f8`. Useful for maps and coordinates; it is no proof that BLE works on Android.

The upstream schemas are kept in `app/src/main/proto/pymammotion/proto/`. Only the Java generation options are added. GPL-3.0 license: see `THIRD_PARTY.md`.

## Detection and GATT

[Model identification](https://github.com/mikey0000/PyMammotion/blob/0bf120b2b390603266c8fe8a4b44347bdc994006/pymammotion/utility/device_type.py): prefix `Luba-VS` for LUBA 2. A BLE name proves neither the 1000 capacity nor the owner's identity. The scan shows Luba names for diagnostics; the user selects their robot by name/address.

[Actual UUIDs](https://github.com/mikey0000/PyMammotion/blob/0bf120b2b390603266c8fe8a4b44347bdc994006/pymammotion/bluetooth/const.py):

| Element | UUID |
|---|---|
| UART service | `0000ffff-0000-1000-8000-00805f9b34fb` |
| Write with response | `0000ff01-0000-1000-8000-00805f9b34fb` |
| Notifications | `0000ff02-0000-1000-8000-00805f9b34fb` |
| CCCD | `00002902-0000-1000-8000-00805f9b34fb` |

Android: service discovery, MTU 512 request (the value actually negotiated is displayed), FF02 subscription via CCCD, then synchronization and report request. One GATT operation at a time. MTU 23 remains supported with fragmentation. At M1, no reconnection after an unexpected failure; the app now tries at most 5 automatic reconnections of the link alone, with no mission or command resumed ([background link](BACKGROUND_LINK.md)). A diagnostics campaign only allows reconnections explicitly scheduled in the campaign, after a STOP confirmed by GATT.

## Verified BLE encapsulation

[BleMessage](https://github.com/mikey0000/PyMammotion/blob/0bf120b2b390603266c8fe8a4b44347bdc994006/pymammotion/bluetooth/ble_message.py) and [FrameCtrlData](https://github.com/mikey0000/PyMammotion/blob/0bf120b2b390603266c8fe8a4b44347bdc994006/pymammotion/bluetooth/data/framectrldata.py):

- Header: type, control, sequence modulo 256, data length on one byte.
- Custom data: type 1, subtype 19, hence `0x4d`.
- Control: bit 0 encryption, bit 1 checksum, bit 2 direction, bit 3 ACK request, bit 4 fragmentation.
- Each non-final fragment prefixes its content with the remaining length on two little-endian bytes.
- Upstream sends without encryption, without checksum and without BluFi ACK request by default, with GATT response. The POC follows this configuration.
- If a checksum is received, CRC identical to `calc_crc(calc_crc(0, [sequence, dataLen]), dataBytes)`, two little-endian bytes.

The POC validates length, CRC, duplicates and continuity of RX sequences. A sequence loss stops the acquisition, rather than a silent resume as in some upstream branches. Received encryption is explicitly refused. Legacy JSON messages do not refresh the watchdog.

`tools/reference_blufi_vectors.py` only runs the CRC and frame assembly methods extracted by AST from the upstream code. Its frozen results feed the JVM tests. TX fragmentation accounts for the ATT MTU, in addition to the 255-byte limit of the BluFi format.

## Protobuf envelope

[LubaMsg](https://github.com/mikey0000/PyMammotion/blob/0bf120b2b390603266c8fe8a4b44347bdc994006/pymammotion/proto/luba_msg.proto): `msgtype=1`, `sender=2`, `rcver=3`, `msgattr=4`, `seqs=5`, `version=6`, `subtype=7`, `net=8`, `sys=10`, `nav=11`, `driver=12`, `timestamp=15` (field numbers).

The POC sends requests: sender `DEV_MOBILEAPP=7`, attribute `REQ=1`, version 1, sequence modulo 256 and Unix timestamp in milliseconds. `subtype=0` follows the account-less BLE instance of [BLETransport](https://github.com/mikey0000/PyMammotion/blob/0bf120b2b390603266c8fe8a4b44347bdc994006/pymammotion/transport/ble.py). Acceptance of this subtype by the robot firmware remains to be checked; no account identifier is invented.

## Synchronization and telemetry

[MessageNetwork](https://github.com/mikey0000/PyMammotion/blob/0bf120b2b390603266c8fe8a4b44347bdc994006/pymammotion/mammotion/commands/messages/network.py): `LubaMsg.msgtype=ESP=248`, rcver `DEV_COMM_ESP=0`, `DevNet.todev_ble_sync` field 1, value 2.

[ble_activity_loop / ble_polling_loop](https://github.com/mikey0000/PyMammotion/blob/0bf120b2b390603266c8fe8a4b44347bdc994006/pymammotion/device/ble_loop.py): BLE sync every 5 s and stream renewal every 8 s. The upstream comment describes a synchronization window of about 10 s and an official app rate of about 1.5 s. These are upstream observations, not measurements on your robot and not a demonstrated motor watchdog.

[MessageSystem.request_iot_sys](https://github.com/mikey0000/PyMammotion/blob/0bf120b2b390603266c8fe8a4b44347bdc994006/pymammotion/mammotion/commands/messages/system.py), [DeviceHandle.enqueue_ble_stream_command](https://github.com/mikey0000/PyMammotion/blob/0bf120b2b390603266c8fe8a4b44347bdc994006/pymammotion/device/handle.py) and [mctrl_sys.proto](https://github.com/mikey0000/PyMammotion/blob/0bf120b2b390603266c8fe8a4b44347bdc994006/pymammotion/proto/mctrl_sys.proto):

- `LubaMsg.msgtype=EMBED_SYS=244`, rcver `DEV_MAINCTL=1`.
- `MctlSys.todev_report_cfg` field 38: `report_info_cfg`.
- `act`: START=0, STOP=1, KEEP=2; `timeout`, `period`, `no_change_period`, `count`, `sub` fields 2 to 6.
- `count=0` requests a continuous stream. POC: timeout 10,000 ms, period 1,000 ms, no_change_period 1,000 ms (both 200 ms since then while a route, a navigation test, a bumper contact test or the command cutoff test runs; `Commands.reports`, `RobotBle.reportPeriodMs`). This last parameter differs from the 4,000 ms used in the upstream BLE loop; it requests a more regular stationary rate, without guaranteeing that it will be honored.
- POC subscriptions: CONNECT=0, DEV_STA=1, RTK=2, DEV_LOCAL=3, FW_INFO=5; WORK=4 and CUTTER_INFO=11 added with the cutting extension (0.1.2); MAINTAIN=6 always requested since the maintenance page (mileage, working time, battery cycles, blade use). The sensor probe adds vision streams and the RTK base report.
- **Measured rate: 1 Hz.** On 2026-10-20 (LUBA 2 AWD, firmware 1.30.31.19), after a 200 ms request (`ROUTE_REPORTS` at 17:16:33, `NAV_RPT_FAST` at 19:19:31) and no other request for 20 s, the positions stayed 1.0 s apart (median 1005–1010 ms; 1.01 Hz over the 109 s route handed to the gateway, whose `LegRunner` renews 200 ms every 8 s). The 200 ms request is kept in case another firmware honours it; the tracking already models 1 Hz positions.
- `MctlSys.toapp_report_data` field 39: `report_info_data`.
- Report: `connect=1`, `dev=2`, `rtk=3`, `locations=4`, `work=5`, `fw_info=6`.
- `dev.battery_val` field 3, `sys_status` field 1, `sensor_status` field 4 and `self_check_status` field 12.
- `rtk.status` field 1, `pos_level` field 2. `rtk.status` follows PyMammotion's `RTKStatus` (0 none, 1–2 Single, 4 Fix, 5 Float, other unknown) and alone is used to recognize a FIX (`Telemetry.rtkFix`). `pos_level` has no documented meaning (exposed raw by the Home Assistant integration): read as 1 in all 1,972 reports of 2026-10-15, robot on the base in FIX (status 4). Reading it as "0 = FIX" wrongly triggered the "Prolonged RTK loss" alert and prevented saving the last reliable position (fixed in 0.55.1).
- Position: `rpt_dev_location.real_pos_x=1`, `real_pos_y=2`, `real_toward=3`, `pos_type=4`, `zone_hash=5`, `bol_hash=6`.

A report containing a robot state with a battery between 0 and 100, or a position, refreshes the watchdog. State and position timestamps are separate; a new state does not make an old position fresh. ACKs and empty reports are never valid telemetry. For the navigation milestone, RTK quality, simultaneous freshness of position/heading/state and physical protections will also have to be validated.

Displayed rate: number of position reception intervals / observed duration, 10 s sliding window. A reception containing several positions is counted only once (the first position is used, as upstream). The 8–10 Hz examples in the specification are not presumed possible. No GPS is fabricated in the absence of an RTK origin and yaw.

## Joystick, STOP and sessions — research before milestone 2

[MessageDriver.send_movement](https://github.com/mikey0000/PyMammotion/blob/0bf120b2b390603266c8fe8a4b44347bdc994006/pymammotion/mammotion/commands/messages/driver.py) and [mctrl_driver.proto](https://github.com/mikey0000/PyMammotion/blob/0bf120b2b390603266c8fe8a4b44347bdc994006/pymammotion/proto/mctrl_driver.proto): `EMBED_DRIVER=243`, rcver `DEV_MAINCTL=1`, `MctlDriver.todev_devmotion_ctrl` field 1, `DrvMotionCtrl.setLinearSpeed` field 1 and `setAngularSpeed` field 2, int32 integers. `channel` field 3 is omitted by the upstream BLE builder.

STOP: both speeds are explicitly zero in the model. Proto3 omits their zero values on the wire, but keeps the empty `todev_devmotion_ctrl` sub-message. The test checks the presence of this sub-message and the exact bytes of the envelope. A GATT write response proves neither that a motor command was accepted nor a mechanical stop.

[JoystickControl](https://github.com/mikey0000/PyMammotion/blob/0bf120b2b390603266c8fe8a4b44347bdc994006/pymammotion/mammotion/control.py) uses a 0.2 s worker (5 Hz). [movement.py](https://github.com/mikey0000/PyMammotion/blob/0bf120b2b390603266c8fe8a4b44347bdc994006/pymammotion/utility/movement.py) applies in particular a factor of 10 on the linear component and a factor of 4.5 with integer conversion on the angular one, after the rocker transformation. These values alone do not allow concluding on SI units for your firmware. m/s and rad/s conversion to be calibrated before Pure Pursuit; no non-zero movement is implemented at M1.

`toapp_devmotion_ctrl_ack` field 16 contains `timestamp`, `is_drop`, `delay_ms`. The `DrvSessionCtrlReq/Ack` messages, token and exit reasons are also present in the schema, but [session_ctrl](https://github.com/mikey0000/PyMammotion/blob/0bf120b2b390603266c8fe8a4b44347bdc994006/pymammotion/mammotion/commands/messages/driver.py) is explicitly an IoT/cloud mechanism: do not substitute it for the BLE joystick.

To be measured: minimum accepted rate, stop delay after commands cease, availability and meaning of ACKs, need to enter manual mode, duration limits, reaction to protections and to conflicts with the Mammotion app. No safety reset, motor test mode, unlock or blade command is exposed.

## Map and account — research for milestone 5

[Mammotion-HA.async_sync_maps](https://github.com/mikey0000/Mammotion-HA/blob/9b77f9defe692300800192800b89a6688a3829c9/custom_components/mammotion/coordinator.py) calls `manager.start_map_sync`. [MapFetchSaga](https://github.com/mikey0000/PyMammotion/blob/0bf120b2b390603266c8fe8a4b44347bdc994006/pymammotion/messaging/map_saga.py) retrieves names, hash lists and common data fragments from the robot through its transport. An existing map is therefore not necessarily a single HTTP download from the account.

[MessageNavigation](https://github.com/mikey0000/PyMammotion/blob/0bf120b2b390603266c8fe8a4b44347bdc994006/pymammotion/mammotion/commands/messages/navigation.py):

1. `todev_gethash=NavGetHashList(pver=1, sub_cmd=0)` requests the root hashes.
2. The `toapp_gethash_ack` responses are acknowledged with `sub_cmd=2`, total_frame and current_frame.
3. `todev_get_commondata=NavGetCommData(pver=1, action=8, hash=..., sub_cmd=1)` requests an element.
4. The `toapp_get_commondata_ack` fragments, and possibly `toapp_svg_msg`, must be assembled and acknowledged according to their type.

[Mammo.FetchMap and MowerMap model](https://github.com/mikeymclellan/mammo/blob/85c93ce6abc87205c75353e47e608e7ce1aa82f8/cmd/maps.go) provide a second implementation: maps retrieved from the robot via MQTT cloud commands; types 0 zone, 1 obstacle, 2 path, other types distinct. Do not assume that every forbidden geometry or every physical obstacle is present in the data.

[MammotionHTTP](https://github.com/mikey0000/PyMammotion/blob/0bf120b2b390603266c8fe8a4b44347bdc994006/pymammotion/http/http.py) exposes login_v2/OAuth, session and refresh tokens, robot list and map backup endpoints. Backups/restores do not replace geometric synchronization, and a restore can modify the robot: it is out of scope. Cloud authentication is not implemented at M1. Future storage: Android Keystore keys, encrypted blobs, no password or token in the logs, preference for a refresh token rather than persisting the password.

## Frames and heading

[Device.update_report_data](https://github.com/mikey0000/PyMammotion/blob/0bf120b2b390603266c8fe8a4b44347bdc994006/pymammotion/data/model/device.py), [parse_double](https://github.com/mikey0000/PyMammotion/blob/0bf120b2b390603266c8fe8a4b44347bdc994006/pymammotion/utility/conversions.py) and [Mammo.pilot](https://github.com/mikeymclellan/mammo/blob/85c93ce6abc87205c75353e47e608e7ce1aa82f8/cmd/pilot.go) divide position and heading by 10,000: meters and degrees. Telemetry keeps the raw heading in `rawHeading` and provides controllers with a mathematical angle `heading = (90 - rawHeading) mod 360`. The tests of Oct 8 (heading close to 0°, translation towards +y; heading close to 180°, translation towards -y) motivate this correction. The joystick, observed to be inverted, also motivates converting the angular sign at serialization: internal positive = left, firmware positive = right. These corrections still have to be checked during the next physical test.

PyMammotion transforms the robot frame into ENU with the RTK yaw:

```
east  = cos(yaw) * x - sin(yaw) * y
north = sin(yaw) * x + cos(yaw) * y
```

Then [CoordinateConverter](https://github.com/mikey0000/PyMammotion/blob/0bf120b2b390603266c8fe8a4b44347bdc994006/pymammotion/data/model/coordinates.py) uses WGS84/ECEF/ENU for latitude/longitude. RTK origin and yaw must be obtained together. Mammo describes a compass-type heading in the map frame; this convention must be checked against the axes of the real map and an observed movement before the external controller. Do not treat the heading directly as a Pure Pursuit mathematical angle without this validation.

## Firmware dependencies and safety limits

| Function | Status at M1 / dependency to check |
|---|---|
| GATT FFFF/FF01/FF02 | Upstream reference; presence and characteristics checked on connection |
| BLE sync subtype 0 / value 2 | Acceptance and persistence to be tested on real firmware |
| START/KEEP reports | Effective rate and renewal to be measured |
| Model query `MctlSys.device_product_type_info` (field 36, `result = 1`) | Reply `main_product_type` (trade name) / `sub_product_type` (internal model code, H versions distinguished) per PyMammotion; not yet observed on the LUBA 2 firmware ([cutting heights by model](features/supported-models.md#cutting-heights-by-model-version-1130)) |
| Position and heading | Availability, frame and quality depend on firmware reports |
| DrvMotionCtrl movement | Format identified; units, manual mode entry, ACK and duration not validated |
| Stop after loss/crash | Motor watchdog to be demonstrated by the Service → Command cutoff test ([MOTOR_WATCHDOG.md](MOTOR_WATCHDOG.md)); no software guarantee possible without a link |
| BLE auth | `BleSignatureReq`, `BlePairReq/Rsp` exist in [dev_net.proto](https://github.com/mikey0000/PyMammotion/blob/0bf120b2b390603266c8fe8a4b44347bdc994006/pymammotion/proto/dev_net.proto); not implemented, no bond removal or bypass |
| Session-based cloud control | Distinct protocol, capabilities depend on model/firmware; outside the BLE POC |
| Maps | Hashes/types/fragments and origin to be validated on the real robot |

The STOP button purges pending messages, finishes the fragments of the message already in flight so as not to corrupt protobuf assembly, then prioritizes zero speed and closes the connection. TX sequences are assigned at the actual write, after the purge. It cannot preempt a busy Android transaction. GATT timeout 1.5 s, fallback close 2 s after the STOP request. Latency and physical stop must be measured; the term "immediately" is not a demonstrated property.

Unexpected BLE loss at M1: FAILED and no reconnection (now: at most 5 reconnections of the link, no command resumed). Invalid/stale telemetry, exception or disk failure: STOP requested if GATT is ready, then close. Leaving the foreground at M1: same; now the link is kept by the `RobotLinkService` foreground service and only hold-to-run activities are stopped ([background link](BACKGROUND_LINK.md)). Crash/OS kill/radio loss: the app may transmit nothing. Milestone 2 must demonstrate a firmware watchdog compatible with the expected safety level before any long or autonomous test. Milestone 1 causes no movement to validate these limits.

## Diagnostics extension 0.1.1

The campaign chains stationary cases and logs the existing messages. It changes neither the UUIDs, the schemas nor the units. The only speed protobufs remain zero: 30 s at 5 Hz to observe throughput, then STOP during stops. GATT success does not validate manual mode. Silence and exceptions are injected in the app, never by sending invalid frames to the robot. See `DIAGNOSTIC_CAMPAIGN.md` for the criteria, scheduled reconnections and limitations.

## Supervised cutting extension 0.1.2

The initial restriction "no blade command exposed" remains true for the automatic campaign. Separate height and rotation tests were added later. `set_blade_height` is a driver message, field 2; height feedback is read in driver field 4 / work. For LUBA 2, `mower_api.async_start_stop_blades` uses `DrvMowCtrlByHand`, not `SysKnifeControl`, which is reserved for LUBA 1: upstream parameters reused, no alternative command attempted in case of refusal. Travel speeds remain zero. The known WORK and CUTTER_INFO channels are now requested to observe height and RPM if the firmware provides them. Compatibility and mechanical stop remain unvalidated; see `BLADE_TESTS.md`.

## Map and account — 0.2.0

Source reference still pinned at `0bf120b2b390603266c8fe8a4b44347bdc994006`:

- [navigation.py](https://github.com/mikey0000/PyMammotion/blob/0bf120b2b390603266c8fe8a4b44347bdc994006/pymammotion/mammotion/commands/messages/navigation.py): `get_all_boundary_hash_list(sub_cmd=0)`, ACK `get_hash_response(sub_cmd=2)`, `synchronize_hash_data(action=8,sub_cmd=1,hash)`, `get_regional_data(sub_cmd=2,action,type,hash,total_frame,current_frame)`.
- [map_saga.py](https://github.com/mikey0000/PyMammotion/blob/0bf120b2b390603266c8fe8a4b44347bdc994006/pymammotion/messaging/map_saga.py): subscription before request, ACK of each frame, root list mandatory, sync per transport (2 BLE / 3 cloud). Frames indexed from 1 to total, deduplication, and only complete elements are published. BLE timeout 60 s, cloud 90 s for collection after connection. No modification, deletion, backup or restore of the robot map.
- [hash_list.py](https://github.com/mikey0000/PyMammotion/blob/0bf120b2b390603266c8fe8a4b44347bdc994006/pymammotion/data/model/hash_list.py): type 0 zones, 1 obstacles/no-go zones, 2 paths; since 1.12.1, types 25/26 (vision zones of the LUBA 2 AWD X, `VISUAL_SAFETY_ZONE` / `VISUAL_OBSTACLE_ZONE`) are read, avoided by moves and block the mowing computation ([details](features/supported-models.md#map-of-a-luba-2-awd-x-vision-zones-version-1121)). Other types block the POC computation; SVG, zone names, collection spots and dock are not interpreted in this version.
- [generate_geojson.py](https://github.com/mikey0000/PyMammotion/blob/0bf120b2b390603266c8fe8a4b44347bdc994006/pymammotion/data/model/generate_geojson.py): CommDataCouple X/Y are meters in the robot/RTK local frame, kept as is. The integer report position is still divided by 10000. No approximate WGS84 conversion added.
- [http.py](https://github.com/mikey0000/PyMammotion/blob/0bf120b2b390603266c8fe8a4b44347bdc994006/pymammotion/http/http.py): login_v2 POST `/oauth2/token`, base64 password in the HTTPS parameters, HMAC-SHA256 signature with the hex MD5 key of the client secret, headers Ma-App-Key/Ma-Signature/Ma-Timestamp. Client constants borrowed from the PyMammotion 0.10.8 package distributed on PyPI (not the user credentials). List `/v1/user/device/page`, MQTT `/v1/mqtt/auth/jwt`, invoke `/v1/mqtt/rpc/thing/service/invoke` identifier `device_protobuf_sync_service`. IoT domains obtained from the JWT, never an arbitrary domain outside Mammotion HTTPS. Protobuf subtype = numeric userInformation.userAccount, as in client_auth.py.
- [mqtt.py](https://github.com/mikey0000/PyMammotion/blob/0bf120b2b390603266c8fe8a4b44347bdc994006/pymammotion/transport/mqtt.py) and [envelope.py](https://github.com/mikey0000/PyMammotion/blob/0bf120b2b390603266c8fe8a4b44347bdc994006/pymammotion/transport/envelope.py): topics `/sys/<pk>/<name>/thing/event/+/post` and `/sys/proto/<pk>/<name>/thing/event/+/post`, params.value.content / params.content in base64. The POC requires MQTT TLS and valid certificates/host names; no plaintext MQTT transport or legacy Aliyun transport is implemented.

Session (access/refresh and metadata) encrypted with AES-GCM via Android Keystore, Android backup disabled. Password neither kept nor logged, never in EventLog. No automatic renewal in this first POC: expired session → explicit logout/login. Errors show codes and steps, never a raw HTTP response or a token. Real login observed after user input in the app: session and a list with one LUBA retrieved. Complete cloud map (43 elements) then downloaded and displayed on the Fold.

Private cache of complete maps, isolated by SHA256 robot identifier, date displayed. The cache allows previewing without a connection; it may be out of date and is never used for motor execution.

For LUBA 2, NAV messages are addressed to **DEV_NAVIGATION**, as in `abstract_message.py:get_msg_device` when `DeviceType.is_luba_pro` (meaning LUBA 2 or later). This remapping does not affect the DRIVER/SYS messages of the existing BLE control. Check added to the tests.
