# Supported models and read-only mode

DumbyLuba targets every Mammotion robot mower. Only the **LUBA 2 AWD** has been validated on a real robot so far, and
its **X series** unlocked from it: the other models are recognized and connect in **read-only mode**, so that their owners can check what works and send
the data needed to adapt the app, without the app ever moving the robot or starting its blades.

| | |
|---|---|
| **Status** | Read-only barrier implemented and covered by JVM tests; LUBA 2 AWD validated on a single robot, X series unlocked on the same platform and firmware (not driven by DumbyLuba yet); model trial covered by JVM tests and the test bench, not yet run on a robot of another model |
| **Code** | `protocol/ModelSupport.kt` (`ModelSupport`, `ModelTrial`, `ReadOnlyPolicy`), `ui/RobotModel.kt` (model detection), `auth/CloudRobotFilter.kt` (account devices), `ui/ModelTrialGroup.kt` (trial card), `ble/RobotBle.kt` (`send`, scan, `armModelTrial`) |
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
| LUBA 2 AWD X series | `Luba-VP…` | ✅ unlocked: same platform and firmware (1.30.31.19) as the validated LUBA 2; reading, map (No-Stop zones included), camera and STOP checked on a 3000X (DumbyLuba#1), [X series](#luba-2-awd-x-series-unlocked) |
| LUBA AWD (LUBA 1) | `Luba…` (any other Luba name) | 🔒 read-only, tester wanted |
| LUBA 3 AWD | `Luba-VA…` | 🔒 read-only, tester wanted |
| LUBA mini AWD | `Luba-MN…`, `Luba-LD…` | 🔒 read-only, tester wanted |
| LUBA mini 2 AWD | `Luba-LA…`, `Luba-MD…`, `Luba-MB…`, `Luba-LS…`, `Luba-MS…` | 🔒 read-only, tester wanted |
| Other recent Luba | `Luba-HM…`, `Luba-MH…`, `Luba-ME…`, `Luba-TR…`, `Luba-SP…` | 🔒 read-only, tester wanted |
| Yuka family | `Yuka…`, `Ezy-…` | 🔒 read-only, tester wanted; commands known to differ (blade height, cutting width) |
| Other Mammotion mowers | `Kumar…`, `Maston…` | 🔒 read-only, listed by the scan, no model page yet |

Pool cleaners (`Spino…`) and RTK reference stations are not mowers and are not listed.

**Mammotion account.** The account lists a device as a robot when its name carries a mower prefix
above **or** its product key is a mower key of that table (`ModelSupport.isMower(name, productKey)`,
`RobotModel.fromProductKey`): the account name of a LUBA AWD 5000 carried no model prefix and the robot was dropped
("0 LUBA in the account", [DumbyLuba#3](https://github.com/cleborgne-pro/DumbyLuba/issues/3)). The table holds mower
keys only, no RTK station, pool cleaner or charging pile key. Each device left out is logged as
`cloud_device_skipped` with its product key, the start of its name (the model part up to the first `-` or `_` and 2
characters, e.g. `RTK-AB`, otherwise 3 characters, never more than half of the name) and the name length, never the full name, the serial number or the iotId, so the anonymized export keeps
it. When the account has devices but none is recognized, the status says "N devices in the account, none recognized
as a Mammotion mower: export the diagnostics (Service → Logs → Export all) and report the model"; an empty account
says "No device in the account". A robot listed by its product key only gains nothing else: motion stays bound to
the validated name prefixes, so it stays in read-only mode.

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
5. **Model trial**: the only exception, armed by the owner for the three unlocking tests ([below](#model-trial)).

The validated prefixes are listed once, in `ModelSupport.VALIDATED_PREFIXES` (today `Luba-VS` and `Luba-VP`).

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
   the joystick at low speed, then the blade test, through the [model trial](#model-trial);
3. the prefix is added to `ModelSupport.VALIDATED_PREFIXES`, with the tests and this table updated.

## Model trial

The owner of a model in read-only mode can run these three tests from the app. Service tab,
robot connected: the **Model trial** group under the *Read-only* card offers **Arm the model trial**. A dialog
recalls that the robot has never been driven by DumbyLuba and asks for three confirmations (owner next to the robot,
flat open ground with 3 m clear, physical STOP within reach). Once armed, for **30 minutes** and for **this robot
only** (`ModelTrial`, `protocol/ModelSupport.kt`):

| Test | Page | Trial limit |
|---|---|---|
| 1. Command cutoff | Service → Command cutoff | unchanged test: slow straight drive (10–120 units), then silence; STOP by the app after 6 s or 1 m of drift (2.5 m clear in front) |
| 2. Joystick at low speed | Service → Manual control | joystick bounded to 300 linear units (≈ 0.3 m/s) and 150 angular units (≈ 15 °/s), blades stopped |
| 3. Blade test | Service → Blade test | discs while stationary, 10 s at most, OFF checked before and after |

**Bumper "alert" at rest.** The three tests refuse to start, and stop, when the bumper slot of `sensor_status`
(bits 0–2) is not 0. A LUBA 3 AWD reports 1 ("alert") there permanently with a free bumper
([DumbyLuba#2](https://github.com/cleborgne-pro/DumbyLuba/issues/2)), so none of its tests could start. When the trial
is armed while this slot reads 1, that value becomes the robot's resting value for the trial (`Armed.bumperRest`,
`ModelTrial.sensors`, logged in `model_trial_armed`): the guards of the three tests ignore it, but an error/obstacle
value (2–7, never accepted) and the collision protection (self-check 25, code 1014) still refuse or stop them. The arming dialog says so and asks to check that the bumper moves freely. The LUBA 2 is not concerned
(validated model, no trial). The raw value stays shown as is in Service → Sensors.

Everything else stays refused, at the entry points and by `RobotBle.send`: routes and mowing, move to a point, return
to base, base exit, gateway tracking, cutting height, obstacle and border tests, map writing. During the trial the
barrier lets through, on top of the read and stop commands, only motion within the trial ceilings, the manual
mowing mode and discs, and the alternative knife command (`ReadOnlyPolicy.allowsTrial`).

The trial is kept in memory only: it ends with **End the trial**, at disconnection, after 30 minutes, or when another
robot is selected; nothing survives a restart of the app. A test running when the trial ends stops (STOP or blades
OFF). Arming and ending are logged (`model_trial_armed`, `model_trial_ended`), as well as each test (`silence_test_*`,
`manual_*` with `modelTrial`, `blade_*`).

Then: Service → Logs → **Export all (ZIP)**, which is anonymized, attached to a
[Model compatibility report](https://github.com/cleborgne-pro/DumbyLuba/issues/new?template=model-compatibility.yml)
with what was seen (did the robot stop on its own, did it follow the joystick, did the discs start and stop).

## Map of a LUBA 2 AWD X: vision module zones

Besides zones, no-go zones and passages, the map of a LUBA 2 AWD X (`Luba-VP…`, seen on a 3000X, firmware
1.30.31.19, issue DumbyLuba#1) carries rectangles of **type 25**: zones recorded by the robot's vision
(PyMammotion `VISUAL_SAFETY_ZONE`; **type 26**, `VISUAL_OBSTACLE_ZONE`, is their obstacle sibling). Before 1.12.1
these types were unknown: the reading received every element, then waited for the 60 s timeout ("unsupported
types"), the map was never cached and was read again at each connection, cloud reading included.

Since 1.12.1 types 25 and 26 are recognized and the map completes. Their owner then showed what type 25 is in the
Mammotion app: a **No-Stop zone**, where the robot keeps going even when its vision module detects an obstacle (the
bumper still stops it). Now (`map/Geometry.kt`):

- **type 25** (`ShapeKind.NO_STOP_ZONE`) is **ordinary ground**: neither avoided by moves nor refused by the mowing
  computation, mowed like the rest of its zone; it is drawn with a light blue tint and a dashed blue outline;
- **type 26** (`ShapeKind.VISION_ZONE`, vision obstacle zone) has not been observed yet: it stays drawn like a map
  obstacle, avoided by every move, and the mowing computation is refused on a map that contains one ("Map with vision
  obstacle zones (type 26, Luba 2 AWD X): meaning not confirmed, planning disabled");
- a map holding these types is now read back from the local cache: until 1.14.0 it was written but refused on
  reading, so it was read again over Bluetooth at every connection.

DumbyLuba does not drive the robot's vision module: inside a No-Stop zone as elsewhere, its own guards (bumper, lift,
errors, link) apply.

## Map element types

Every element type known to PyMammotion (`PathType`, `hash_list.py`, commit c0e5ada) has its kind in
`map/Geometry.kt` (`ShapeKind`), and what each kind does is carried by the kind itself:

| Type | Kind | Map | Mowing computation | Moves |
|------|------|-----|--------------------|-------|
| 0 | zone | filled | mowed | allowed inside |
| 1 | no-go zone | red | hole | avoided |
| 2 | passage | grey band | link between zones | used |
| 3, 10, 12, 18, 19, 20 | dock transfer zone, planned lines, dump spot, mowing progress, corridor (Yuka mini) | thin dashed line | ignored | ignored |
| 13 | drawing (SVG pattern, `toapp_svg_msg`) | purple pattern and dotted tile ([patterns](map.md#loading-a-map)) | ignored | ignored |
| 21 | virtual wall (line never to cross) | thick red line | refused | refused |
| 22 | no-go zone variant (meaning not confirmed upstream) | like a no-go zone | refused | avoided |
| 23 | rectangular no-go zone (drawn in the Mammotion app, LUBA 3) | like a no-go zone | hole | avoided |
| 25 | No-Stop zone | light blue | ordinary ground | ordinary ground |
| 26 | vision obstacle zone (never observed) | grey | refused | avoided |
| other | unknown element | grey | refused | avoided if closed, refused otherwise |

An element of type 22, 23 or 26, or of an unknown type, with fewer than 3 points cannot be avoided: the computation
and the moves refuse the map, and the message names the elements and their types. An unknown type never blocks the
reading: the element is kept (even without points), drawn, cached, and the map completes. Only a zone, no-go zone
or passage answered without points leaves the map incomplete. The interpretation of types 21, 22 and 23 comes from
PyMammotion and the Mammotion app code, not from a map read by DumbyLuba.

## LUBA 2 AWD X series: unlocked

The X series (`Luba-VP…`) is a LUBA 2 AWD with a vision module: same platform, same Bluetooth protocol, and the 3000X
of DumbyLuba#1 runs the firmware of the validated LUBA 2 (1.30.31.19). Its owner checked the connection, telemetry,
map reading, camera and STOP; the map differences (No-Stop zones, vision obstacle zones) are handled above. The
project owner therefore unlocked it without the [model trial](#model-trial): `Luba-VP` is in
`ModelSupport.VALIDATED_PREFIXES`, with every control of the LUBA 2 AWD (motion, blades, routes, mowing, gateway).

Not verified yet, on a real X: no DumbyLuba route or move has been run; the [command cutoff](../MOTOR_WATCHDOG.md)
test is the first thing to run before mowing, as on any robot; the vision module may stop or slow the robot in front
of an obstacle during a DumbyLuba route (seen by the app as a robot that no longer follows); type 26 zones stay
avoided and still refuse the mowing computation.

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

- A Bluetooth name proves neither the exact model nor its firmware (an X series is recognized only by `Luba-VP`); the product key is only known with the account.
- No model other than the LUBA 2 AWD has been connected to the app yet: telemetry, map and camera on these models are
  expected from the shared protocol, not verified.
- The ESP32 gateway firmware still lists only `Luba…` names: Yuka owners connect in direct Bluetooth or through the
  Android gateway. Both gateways relay the frames built by the pilot app, which applies the barrier; the ESP32 has no
  autonomous tracking.
- The sensor probe switches (firmware debug, ZMQ bridge) are refused in read-only mode; the probe still records the
  reports the robot sends.
