# Tests and diagnostics

The **Service** tab groups supervised tests for checking step by step what the robot does: first stationary, then
moving with the blades off, then with the blades running. Each test has its own guards and stops when the button is
released or on STOP.

| | |
|---|---|
| **Access** | Service → *Robot* (Cutting height), *Supervised tests* (Command cutoff, Navigation test, Border following, Obstacle tests, Blade test, Trial mowing) and *Diagnostics* (Bluetooth diagnostics, Detailed telemetry, Sensors and perception, Logs, Advanced settings) groups |
| **Code** | `diagnostics/`, `ui/RobotPanel.kt` (Service pages), `ui/BladesPanel.kt`, `ui/ObstaclesPanel.kt`, `ui/MowDebugPanel.kt`, `ui/BorderDebugPanel.kt`, `map/PaintedZone.kt`, `map/BorderFollow.kt` |
| **Recommended starting point** | [First test guide](../MILESTONE_1_TEST.md) |

Recommended order for a new robot: Bluetooth diagnostics → telemetry → [manual control](manual-control.md) →
[command cutoff](safety.md#command-cutoff-motor-watchdog) → navigation test → [obstacle tests](obstacles.md#service--obstacles-and-obstacle-tests)
→ cutting height and blade test → trial mowing → [real mowing](real-mowing.md).

Shared rules: the pages that command the robot show "Robot not connected: this page unlocks as soon as it is
(gateway or direct Bluetooth)." when needed, and refuse to start while a mowing is running ("Mowing is in progress:
finish it from the map before starting a test."). Each test has a supervision checkbox, only one test runs at a time,
and leaving the page stops the running test. A refusal opens an **Action blocked** dialog with **Open manual control**
(or **Unlock** when the robot is locked after STOP). The Service summary shows a short status on the right of each
entry ("in progress", "to test", height…). Service → **Advanced settings** → *Test limits* recalls the 30 % minimum
battery.

<p align="center">
  <img src="../images/diagnostics.png" width="170" alt="Bluetooth diagnostics">
  <img src="../images/blades.png" width="170" alt="Blade test">
  <img src="../images/debug-mowing-paint.png" width="170" alt="Painted zone">
  <img src="../images/border.png" width="170" alt="Border">
</p>

## Bluetooth diagnostics

Service → Diagnostics → **Bluetooth diagnostics**. Robot stationary, blades off, **no non-zero speed**.

- Local self-checks (protobuf STOP, MTU and fragmentation, CRC, encryption, sequence, watchdog…), then initial
  telemetry.
- Selectable cases: *30 s of zero commands at 5 Hz*, *3 STOP / reconnection cycles*, *Hide reports (monitoring)*,
  *Local error → STOP → reconnection*.
- **Final monitoring** of 1, 5, 10, 30 (default) or 60 min, or **Unlimited**; final STOP. All cases ticked by default.
- Tick **Robot stationary, blades stopped: I am supervising**, then **Run diagnostics**; or **Run tests without
  robot** (local self-checks only). Results with status and current case, **Export (ZIP)**, **Diagnostics history**
  (**Show the history** / **Refresh the history**).

The "PASS BLE ONLY" verdict validates neither movement, nor mechanical stopping, nor a real radio loss. An
unexpected failure cancels the rest of the run, with no recovery reconnection. [Campaign guide](../DIAGNOSTIC_CAMPAIGN.md).

## Detailed telemetry

Service → Diagnostics → **Detailed telemetry** (no robot command; last values received when disconnected). Groups
**Last robot error** (code and time, meaning, category, app response, advice; see [safety](safety.md#robot-error-codes)),
**Status** (robot state, self-check, sensor code, blades), **Position** (RTK status and quality, local position,
heading and type, position rate) and **Reception** (last telemetry, state and position, age of the last report,
reports · commands · interruptions).

## Sensors and perception

> [!NOTE]
> **Ultrasonic data is not retrieved.** The known protocol only publishes the health status of each sensor (OK,
> warning, error), no distance. The app therefore does not use them: only the bumper detects obstacles.
> This page is a protocol exploration tool.

Service → Diagnostics → **Sensors and perception**: **Start the capture** / **Stop the capture**, **Reset**,
**Export**. Capture probe: decodes the state of each ultrasonic sensor and of the bumper in `sensor_status`, adds the
vision streams during the capture (obstacle points, visual odometry), counts the perception messages and reports
unknown protobuf fields. No movement command; the capture keeps running in the background, so it can be repeated
during a drive. Two toggles during a capture: **Robot debug reports** (`debug_enable=1`, reset on stop) and **Robot
DDS → ZMQ bridge** (debug command of the Mammotion app): the robot republishes perception on its port 5555, listened
to over local Wi-Fi.
[Details](../SENSORS_PROBE.md).

## Cutting height and blade test

- Service → Robot → **Cutting height**: **Requested height** slider 25 to 70 mm in 1 mm steps (shortcuts 25, 30, 40,
  50, 60, 70; current height by default, otherwise 60 mm), then **Apply X mm**: the robot briefly switches to manual
  mowing with blades OFF, the height is awaited from the robot's feedback (30 s; within a sequence 20 s + 1 s per mm, 30 to 90 s),
  then it returns to ready. **Cancel waiting (setting not confirmed)**. *Automatic sequences*: **Round trip** (±10 mm and
  back) and **Full travel** (25 ↔ 70 mm and back), each setpoint confirmed before the next, **Cancel the sequence**.
  *On-site observation*: "Did the deck move?" **Yes** / **No** (recorded separately), with the driver and work
  report values. Live status: height, blades, rpm, height adjustment, robot mode, battery, blade status.
- Service → Supervised tests → **Blade test**, two tabs:
  - **Robot stationary**: **Check that the blades are stopped** first (OFF sent, fresh OFF state awaited), tick
    **I am supervising on site**, then **Hold to spin the discs (10 s max)** (manual mowing, state 20, at the chosen
    height); **Stop the blades** stays available. Option **Alternative SysKnifeControl command** (LUBA 1 / joystick
    variant). Preconditions shown live: LUBA 2, no diagnostics, height 25–70 mm, fresh state, battery > 30 %, ready
    (11), no protection, bumper free and blades OFF. If the stop is not confirmed within 5 s: STOP and the link is
    closed.
  - **Moving**: **Start the cutting test · X mm**: reported height, manual mowing, then three stripes and a border
    with blades running (links blades stopped) around the start. **Cutting height** 25–70 mm, **Travel speed**
    10–100 %, **Radius around the start** 2 to 8 m in 0.5 m steps (4 m by default), **Maximum duration** 2, 5
    (default) or 10 min. Start more than 0.95 m from boundaries and no-go zones. Deviation > 30 cm, obstacle,
    contact, unexpected state, leaving the screen or STOP: robot and blades stop, with no bypass ("Cutting test
    interrupted" dialog).
  - Results list and **Export tests and logs**.

[LUBA 2 messages, stop criteria and limitations](../BLADE_TESTS.md).

## Navigation test

The robot follows a short route computed on the map around its position (three stripes, one border, one exclusion),
blades off by default, displayed on the map with its trail.

- **Maximum duration** 1, 2, 5 (default) or 10 min; **Forward speed** 10 to 100 % (the travel speed shared with the
  map); **Maximum rotation speed** 60 to 450 units in steps of 10 (300 by default, shown in °/s); **Maximum radius
  around the start** 2 to 15 m in 1 m steps (5 m by default).
- The planned route is refreshed every 2 s while the page is open (**Refresh the planned route**); segment, distance
  covered and observed stop delay are shown during the run.
- **Automatic start** (button **Start · X min · blades stopped**, then STOP) or held button **Hold to drive (X max)**
  (release = STOP and blades off). An unrequested stop opens a "Navigation interrupted" dialog with the reason.
- Stop if deviation > 30 cm, no progress for 15 s or blocked for 3 s; fresh state, battery, bumper and blades
  checked continuously.
- Speeds are in raw units; the observed movement and the stopping delay are measured for calibration.

[Lightweight navigation on the map](../MAP_NAVIGATION_TEST.md) · [former random tests: guards and calibration](../NAV_RANDOM_TEST.md).

## Border following

Service → Supervised tests → **Border following**, just below the navigation test. The robot follows a single
border, blades off by default, to measure in the field the distance it actually keeps.

- Tap a border (or **Choose from the list** / **Change border**): zone boundary or no-go zone contour.
- **Offset between the robot and the outline** from 0 to 100 cm in 1 cm steps (starting from the mowing margin; at
  0 cm the robot's side passes 1 cm from the outline); **Blades running** off by default (with blades, a **Cutting
  height** slider appears); **Travel speed** 10 to 100 %. **Compute**, then **Follow · blades OFF** or **Mow · X mm**.
  The loop goes inside a zone and outside a no-go zone, bypassing other no-go zones, learned obstacles and the base
  surroundings.
- After the test, **Use X cm for mowing** saves this gap as the *Outer margin* of the
  [preparation](mowing-preparation.md).
- **Validate this border at X cm** (after the loop) keeps the border with its gap, retained across launches. On the
  map, validated borders are **green**, those still to validate **red**, and the chosen border sits on an orange
  halo; the list shows the status and gap of each one, plus the "Validated borders" counter. Choosing a validated
  border reuses its gap (**Reuse the validated offset (X cm)**); **Remove the validation** clears it. A retraced
  contour must be validated again.
- A learned obstacle near the docking corridor no longer blocks the computation: see
  [obstacles near the base](obstacles.md#obstacles-near-the-base).
- **Resume** (0.61.0): an interrupted loop (STOP, link, app restart) can be resumed from the point reached, from the
  map, in the **Border following interrupted** panel; the same applies to trial mowing below
  ([resume](mowing-resume.md)).

## Trial mowing (painted zone)

Real mowing limited to a small area painted with a finger, with the same engine and the same checks as mowing from
the map.

- **Paint / Erase / Move** with a finger (**Brush size** Ø 0.6 to 4 m, Ø 1.2 m by default), **Undo**, **Clear all**;
  one finger paints, two fingers pan and zoom; only paint inside existing zones is kept, no-go zones excluded
  (retained area shown).
- **Cutting height** 25–70 mm, **Pass spacing** 15 to 35 cm in 5 cm steps (25 cm by default), **Forward speed**
  10–100 %, **Border of the painted zone** (one lap of the retained outline before the passes, on by default).
- **Compute** (target area, planned coverage, cutting and link meters), then **Mow · X mm** (blades running; links
  blades stopped, return to base). Mowing continues if you leave the page; STOP stays available here and on the map.
- Minimum coverage of 80 % (instead of 95 %, as small areas have proportionally more edges).
- Interrupted (STOP, link, app closed): resume from the point reached, from the map.

[Trial mowing and border following: details](../DEBUG_MOWING.md).
