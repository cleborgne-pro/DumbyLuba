# Supported models and read-only mode

DumbyLuba targets every Mammotion robot mower. Only the **LUBA 2 AWD** has been validated on a real robot so far:
the other models are recognized and connect in **read-only mode**, so that their owners can check what works and send
the data needed to adapt the app, without the app ever moving the robot or starting its blades.

| | |
|---|---|
| **Status** | Read-only barrier implemented and covered by JVM tests; LUBA 2 AWD validated on a single robot; no other model tested yet |
| **Code** | `protocol/ModelSupport.kt` (`ModelSupport`, `ReadOnlyPolicy`), `ui/RobotModel.kt` (model detection), `ble/RobotBle.kt` (`send`, scan) |
| **References** | [Bluetooth connection](bluetooth-connection.md) · [Safety](safety.md) · [Mammotion protocol](../MAMMOTION_PROTOCOL.md) · [CONTRIBUTING](../../CONTRIBUTING.md) |

> [!WARNING]
> Read-only mode is a safeguard, not an invitation to experiment: do not modify the app to bypass it. A model is
> unlocked in the code, for everyone, once its tests have been run and documented.

## Models

The model is deduced from the Bluetooth name prefix (and, with the Mammotion account, from the product key), using
the Mammotion app table reproduced by [PyMammotion](https://github.com/mikey0000/PyMammotion) (`DeviceType`).

| Model | Bluetooth name | Status |
|---|---|---|
| **LUBA 2 AWD** | `Luba-VS…` | ✅ validated: control, mowing, gateway, surveys |
| LUBA 2 AWD X series | `Luba-VP…` | 🔒 read-only, tester wanted |
| LUBA AWD (LUBA 1) | `Luba…` (any other Luba name) | 🔒 read-only, tester wanted |
| LUBA 3 AWD | `Luba-VA…` | 🔒 read-only, tester wanted |
| LUBA mini AWD | `Luba-MN…`, `Luba-LD…` | 🔒 read-only, tester wanted |
| LUBA mini 2 AWD | `Luba-LA…`, `Luba-MD…`, `Luba-MB…`, `Luba-LS…`, `Luba-MS…` | 🔒 read-only, tester wanted |
| Other recent Luba | `Luba-HM…`, `Luba-MH…`, `Luba-ME…`, `Luba-TR…`, `Luba-SP…` | 🔒 read-only, tester wanted |
| Yuka family | `Yuka…`, `Ezy-…` | 🔒 read-only, tester wanted; commands known to differ (blade height, cutting width) |
| Other Mammotion mowers | `Kumar…`, `Maston…` | 🔒 read-only, listed by the scan, no model page yet |

Pool cleaners (`Spino…`) and RTK reference stations are not mowers and are not listed.

## What read-only mode allows

With a model that is not validated, the app keeps:

- the connection (direct Bluetooth or through the [gateway](gateway.md)) and the telemetry subscription;
- the BLE map reading (zones, no-go zones, routes stored by the robot) and the planning and
  [simulation](simulation.md) on that map, which send nothing to the robot;
- the error code and self-check queries, the cutter mode query, the robot network information;
- the [camera](camera.md) video (Mammotion account);
- STOP and the stop commands: zero speed, blades off, pause, stop or cancel of a Mammotion task;
- logs and the diagnostics ZIP export ([logs and data](logs-and-data.md)).

Everything else is refused: joystick movement, blades on, blade height, routes and mowing, move to a point, return
to base (local route and native docking), signal survey routes, navigation, blade and obstacle tests, sensor probe
switches (firmware debug, ZMQ bridge), and any command the list below does not name.

## How it is enforced

1. **Before Bluetooth**: every payload written by the app goes through `RobotBle.send`. For a robot whose name is
   not a validated prefix, `ReadOnlyPolicy` decodes the protobuf message and lets through only an explicit list of
   read and stop commands; anything else, including unknown or future commands, is dropped, logged as
   `read_only_refused` and reported with "Read-only: this robot model is not validated yet…".
2. **At the entry points**: every route (mowing, survey, move to a point, return to base, base exit), the
   navigation, blade, obstacle and border tests, the joystick and the cutting height already require a validated
   model and show the refusal before starting, so that no mission or test begins and then waits for a robot that will
   never move. The native return and the firmware update through the Mammotion account are refused the same way.
3. **On the Android gateway**: autonomous tracking, which the gateway writes to the robot itself, is never offered for
   a model that is not validated, and the gateway refuses a leg when the robot it holds has a known name that is not
   validated ("Robot model in read-only mode: autonomous tracking refused").
4. **On the screen**: the Service tab shows a *Read-only* card for the connected robot, with its detected model and a
   link to this page.

The validated prefixes are listed once, in `ModelSupport.VALIDATED_PREFIXES` (today `Luba-VS`).

## Testing your model

1. Install the [published APK](../../README.md#installation) and close the Mammotion app.
2. Service → Bluetooth connection: check that the robot is listed with its name, then connect. The *Read-only* card
   must appear.
3. Check the telemetry (battery, state, position), read the map (Map → read over Bluetooth), press STOP once.
4. With a Mammotion account, try the camera.
5. Service → Logs → **Export all (ZIP)**, remove personal data (serial number, address, coordinates, Wi-Fi names).
6. Open a [Model compatibility report](https://github.com/cleborgne-pro/DumbyLuba/issues/new?template=model-compatibility.yml)
   issue with the model, the Bluetooth name prefix, the firmware version and the ZIP.

## Unlocking a model

A model moves from read-only to validated through a code change reviewed like any safety change
([CONTRIBUTING](../../CONTRIBUTING.md)):

1. the protocol differences are identified from the reports and the PyMammotion sources (for example the Yuka mini
   has neither blade-height setting nor blade speed control);
2. the owner runs, robot in sight and blades stopped, the [command cutoff](../MOTOR_WATCHDOG.md) test, then
   the joystick at low speed, then the blade test;
3. the prefix is added to `ModelSupport.VALIDATED_PREFIXES`, with the tests and this table updated.

## Map of a LUBA 2 AWD X: vision zones (version 1.12.1)

Besides zones, no-go zones and passages, the map of a LUBA 2 AWD X (`Luba-VP…`, seen on a 3000X, firmware
1.30.31.19, issue DumbyLuba#1) carries rectangles of **type 25**: zones recorded by the robot's vision
(PyMammotion `VISUAL_SAFETY_ZONE`; **type 26**, `VISUAL_OBSTACLE_ZONE`, is their obstacle sibling). Before 1.12.1
these types were unknown: the reading received every element, then waited for the 60 s timeout ("unsupported
types"), the map was never cached and was read again at each connection, cloud reading included.

Since 1.12.1 types 25 and 26 are recognized (`ShapeKind.VISION_ZONE`, `map/Geometry.kt`):

- the map is complete and cached like any other;
- vision zones are drawn like map obstacles and **avoided by every move** (links, return to base, move to a point);
- their exact meaning (area to avoid, or area the robot may cross with its camera) is **not confirmed**, so the
  mowing computation is **refused** on a map that contains them ("Map with vision zones (Luba 2 AWD X): meaning not
  confirmed, planning disabled"). The model is read-only anyway until it is validated.

Cloud failures now name the MQTT reason ("MQTT disconnected", "Cloud queue full") instead of
`IllegalStateException`.

## Cutting heights by model (version 1.13.0)

The "H" versions of the LUBA (high cut, sold in North America) raise the blade disc higher than the standard
versions. The heights offered (sliders, shortcuts, sequences, height passes) and accepted (blade test, walk, routes,
mowing) follow the connected model:

| Model | Standard version | H version |
|---|---|---|
| LUBA 2 AWD (and X series), LUBA 3 AWD | 25–70 mm | 55–100 mm |
| LUBA AWD (LUBA 1) | 30–70 mm | 60–100 mm |
| LUBA mini AWD, LUBA mini 2 AWD | 20–65 mm | 55–100 mm |

Sources: [LUBA 2 AWD specifications](https://support.mammotion.com/portal/en/kb/articles/luba-2-awd-series-specifications)
("25-70mm (Standard Version) 55-100mm (H Version)"), Mammotion LUBA 3 and LUBA mini pages, and the Mammotion app table
reproduced by PyMammotion (`pymammotion/data/model/device_capabilities.py`, `blade_height_min` / `blade_height_max`).

**Detection.** The H and standard versions share the Bluetooth name prefix and the account product key. At each
connection the app asks the robot for its model (`MctlSys.device_product_type_info`, `result = 1`, PyMammotion
`get_device_product_model`, allowed in read-only mode, asked up to 3 times): the reply's `sub_product_type` is the
internal model code (`HM060100LBVAWD50OMNIH` = LUBA 2 AWD 5000H, or a numeric identifier such as `62` = LUBA 3 AWD
1500H), looked up in the table (`protocol/CutHeight.kt`, `CutHeightRange`). An unlisted Luba code ending with "H" is
an H version of the family. The code is remembered per robot (`robot_product_type` preferences), so the range is
known as soon as it reconnects, and logged (`robot_product_type` event). Without a code, the range of the family
deduced from the name applies, raised to the H range when the robot reports a height above the standard maximum.

The Height page (Service → Robot → Cutting height) shows the range and the model code ("H version (high cut):
55–100 mm. Model code …"). The mowing settings keep the height chosen and bring it into the range of the connected
robot where it is used; the protocol itself accepts 20–100 mm (every model).

**Not verified on a robot:** no H version has been connected to the app yet (the validated LUBA 2 is a standard
version); the model reply of the LUBA 2 firmware has not been observed either. The H ranges come from the
manufacturer's specifications and the Mammotion app table.

## Limitations

- A Bluetooth name proves neither the exact model nor its firmware; the product key is only known with the account.
- No model other than the LUBA 2 AWD has been connected to the app yet: telemetry, map and camera on these models are
  expected from the shared protocol, not verified.
- The ESP32 gateway firmware still lists only `Luba…` names: Yuka owners connect in direct Bluetooth or through the
  Android gateway. Both gateways relay the frames built by the pilot app, which applies the barrier; the ESP32 has no
  autonomous tracking.
- The sensor probe switches (firmware debug, ZMQ bridge) are refused in read-only mode; the probe still records the
  reports the robot sends.
