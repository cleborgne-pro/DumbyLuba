# Supervised height and rotation tests — version 0.1.4

Since 0.54.0 the height is set in Service → Robot → **Cutting height**, and the rotation tests are in Service → Supervised tests → **Blade test** (tabs **Robot stationary** and **Moving**); user guide: [tests and diagnostics](features/tests-and-diagnostics.md#cutting-height-and-blade-test). The sections below keep the history of these tests.

These tests were added after the initial "blades OFF" scope. They are **separate from the automatic BLE campaign**, which keeps the blades OFF and never sends a non-zero travel speed. Do not start a rotation while leaving the phone alone on the robot.

## Height

In Service → Robot → **Cutting height**, choose between 25 and 70 mm, then **Apply X mm** (formerly **TEST HEIGHT** in the Blades tab). The app requires a Luba-VS or Luba-VP, READY mode=11, a state no older than ≤2 s, battery >30 %, bumper free, blades OFF and self-check 0 or 10 (11 = robot locked after STOP: "Unlock" button on the map, or Grass then Start on the robot). No campaign or other cutting test in parallel.

The command is `MctlDriver.todev_knife_height_set` (field 2), `DrvKnifeHeight.knifeHeight` (field 1). Source: [MessageDriver.set_blade_height](https://github.com/mikey0000/PyMammotion/blob/0bf120b2b390603266c8fe8a4b44347bdc994006/pymammotion/mammotion/commands/messages/driver.py). The 25–70 mm range is the one from [Mammotion-HA.number](https://github.com/mikey0000/Mammotion-HA/blob/9b77f9defe692300800192800b89a6688a3829c9/custom_components/mammotion/number.py). Since 1.13.0 the range follows the connected model (H versions 55–100 mm): see [cutting heights by model](features/supported-models.md#cutting-heights-by-model-version-1130).

The POC waits for the target height in `bidire_knife_height_report` (driver field 4), or `report_info_data.work.knife_height`. A matching report produces **REPORTED**, which is not proof of mechanical movement. Without a report in time (10 s in this first version, 30 s since 0.7.2): **UNCONFIRMED**, without retry. The height stays at the requested value; no restoration. Automatic sequences (**Round trip** ±10 mm, **Full travel** 25 ↔ 70 mm) were added later, each setpoint confirmed before the next (wait of 20 s + 1 s per mm, 30 to 90 s).

## Rotation: deliberate action in place

1. Robot on the ground and stationary, area clear, discs unobstructed, people/animals at a distance, physical stop within reach. Do not lift the robot to look under the blades.
2. Finish any height test and any BLE campaign.
3. Press **Check that the blades are stopped** (formerly CHECK BLADES STOPPED (OFF)). Wait for the GATT write, then a fresh OFF state received after that write. This check tests the OFF command path on blades that are already stationary; it does not prove the mechanical braking time.
4. Tick **I am supervising on site**, then **hold** **Hold to spin the discs (10 s max)**. The test sends ON only once. Releasing requests OFF; after 10 s of app time at the latest, OFF is requested even if the finger is still pressing.
5. Wait for the robot's OFF state and record what you actually observed. The **Stop the blades** button and the global STOP remain available. If in doubt, use the physical stop.

**The ten seconds limit the app's requests, not a guaranteed mechanical duration.** Android may delay a write, the motor may have inertia, the firmware may refuse ON/OFF and a crash/BLE loss may prevent OFF. No watchdog guarantee for the cutting motors has been demonstrated. Do not try to work around a refusal.

## Command actually used for LUBA 2

[PyMammotion.mower_api.async_start_stop_blades](https://github.com/mikey0000/PyMammotion/blob/0bf120b2b390603266c8fe8a4b44347bdc994006/pymammotion/homeassistant/mower_api.py) distinguishes:

- LUBA 1: `SysKnifeControl`.
- LUBA 2 / other non-LUBA 1 models: `operate_on_device` → `MctlDriver.mow_ctrl_by_hand` field 6, `DrvMowCtrlByHand`.

The LUBA 2 POC reuses **the upstream parameters**: ON `main_ctrl=1`, `cut_knife_ctrl=1`, OFF `main_ctrl=0`, `cut_knife_ctrl=0`, selected height, `max_run_speed=1.2`. The last field is the limit passed by upstream, not a speed setpoint. This message can change manual mode and height: it is not a universal command independent of the robot mode.

An explicitly zero travel speed is sent after the test and on stop. **No repeated zero speed during the disc start-up phase**: the logs of October 7, 2026 show that a stream of zero speeds counts as "inactivity", which makes the robot drop from MODE_MANUAL_MOWING (20) to READY (11) in ~3 s and prevents start-up (see next section). No non-zero travel speed, motor test mode, safety reset, unlock or protection bypass is sent. The firmware must keep applying its normal protections.

On release, timeout, leaving the Blade test screen or a state/protection change, the app only finishes the protobuf fragment already in flight, flushes pending messages, and prioritizes OFF and then zero speed. The global STOP also requests OFF if a start-up is possible, then zero speed and BLE close. A lost link makes these transmissions impossible.

## Diagnostics of October 7, 2026 — corrected start-up sequence (0.7.1)

**Findings (Fold JSONL logs, firmware 1.30.29.26).** The `mow_ctrl_by_hand(main_ctrl=1, cut_knife_ctrl=1, cut_knife_height, max_run_speed=1.2)` command sent is byte for byte the one from upstream `operate_on_device`, written and acknowledged over GATT. The robot does switch to `sys_status: 20` (MODE_MANUAL_MOWING) in under a second, then **drops back by itself to `sys_status: 11` (READY) after ~3 s**, while a stream of zero speeds at 5 Hz continues. The blade bits (`sensor_status` 9–11) never became non-zero, `current_cutter_rpm` was never reported (zero RPM omitted in proto3), no `toapp_knife_status` received. **2 long beeps** are heard when the request is made. Cutting height, on the other hand, works (`todev_knife_height_set`).

**Hypothesis adopted.** The official manual for "manual mowing" mode says: "the cutting disc stops automatically after 5 s of inactivity" and "swipe again to restart the disc after each stop". The disc is tied to **activity** (movement commands): a stream of zero speeds is exactly inactivity, which explains both the drop to READY and the lack of rotation, despite a correct command. Starting the disc with the robot stationary has not been demonstrated; it may only be commanded while driving.

**Corrected sequence (0.7.1).**

1. `manualMow(main_ctrl=1, cut_knife_ctrl=0)`: enter manual mowing mode; the app observes `sys_status` 20 for 2 s (`blade_mode_check` in the log).
2. `manualMow(main_ctrl=1, cut_knife_ctrl=1)`: disc start request, **renewed every 500 ms** (`BLADES_ON_KEEPALIVE`) during the test, with no travel speed and no zero speed in this phase.
3. `cutterQuery` poll (`current_cutter_mode`) at the start of the test; `knife_status` and `cutter_mode` are now decoded and shown (**Blade status** line).
4. **"Alternative SysKnifeControl command" checkbox**: step 2 sent via `MctlSys.todev_knife_ctrl` (`SysKnifeControl.knife_status`), the command the upstream joystick uses for all models. OFF covers both paths.
5. **Random walk mowing** (since replaced by the cutting test of the **Moving** tab): the ON confirmation before movement is no longer blocking — the disc may only be commanded while driving; the walk starts, the blade bits are monitored (`manualMow` keep-alive every 2 s) and the observation is recorded in the result.

**What to observe in the next test** (robot on the ground, discs unobstructed, physical stop within reach): `blade_mode_check` (does mode 20 hold beyond 3 s with the keep-alive?), blade bits ON, RPM, `knife_status`, beeps — then compare with the official app under the same conditions ("swipe to start the disc"). If the disc still only starts while driving, only the random walk mowing scenario can demonstrate it: the stationary "supervised rotation" test will then remain inconclusive by firmware design.

## Reports and results

The app also subscribes to `RIT_WORK` and `RIT_CUTTER_INFO`. The firmware may ignore these channels or not provide RPM. Missing fields stay unknown and the latest timestamps are kept. A height report alone does not refresh the general telemetry watchdog.

- `ON_AND_OFF_REPORTED`: blade code ON=1 observed, then OFF state after the OFF write. These are the robot's logical states, not an independent measurement of rotation/braking.
- `OFF_REPORTED_ON_UNCONFIRMED`: OFF received but no ON observed; test inconclusive regarding rotation. Start-up may be preceded by beeps for 2–3 s, observed during the tests; the window is now 10 s in total, beeps included. An ON state may nevertheless be missed depending on the actual report rate.
- `OFF_UNCONFIRMED` / `INTERRUPTED_OFF_UNCONFIRMED` / `LINK_CLOSED_OFF_UNCONFIRMED`: no stop demonstrated; physical observation required. No automatic restart.

Without a fresh OFF within 5 s of its request, the app requests the stop again and closes the connection, reporting the result as unconfirmed. A raw blade state >1 is conservatively rejected during the test. READY, MANUAL_MOWING and the self-check values come from the [upstream enums](https://github.com/mikey0000/PyMammotion/blob/0bf120b2b390603266c8fe8a4b44347bdc994006/pymammotion/utility/constant/device_enums.py); no new firmware semantics are assumed.

`campaign-<id>.json` reports of type `blade_height` or `supervised_blades` and the associated events are included in the ZIP, with the BLE campaign results. After the process is interrupted, RUNNING becomes an unconfirmed interruption result at the next launch.

## Feedback from the real test

Report: firmware, requested/reported height, observed mechanical movement, ON accepted/refused, RPM present/absent, ON/OFF states received, stop observed on release and any error. Do not follow up with autonomous navigation or a long test with active blades.

## Height validation (0.7.2)

The wait timeout is 30 seconds. The screen distinguishes the dedicated `bidire_knife_height_report` report from the `work.knife_height` field, each with its timestamp: a work report may still show another value. A target received in a new message confirms the software report, not a physical measurement.

Test for example 40 then 60 mm, blades OFF and robot stationary. After each command, record "Plates raised / lowered" or "No movement observed". These observations are kept separately in the exportable reports and do not turn an unconfirmed height into a success. The noise indicates the mechanism acted; the exact height requires a measurement, with the robot switched off and secured.

## Diagnostics of October 8 at 13:18

The 40 mm height request remained pending; the following requests were refused and no disc activation was sent. The screen now shows the pending wait and lets you cancel it without confirming the height. `toapp_knife_status_change` reports are decoded: current height, target and movement in progress; an announced target is not a reached height. The OFF check states why it is blocked by the height. The rotation test now refuses ON if manual mode is not observed. After a height result or cancelling the wait, use "Check blade stop", wait for a fresh OFF, then hold the supervised button. Mechanical rotation remains unvalidated.

## Height never confirmed — analysis of the October 10 logs (firmware 1.30.29.26)

Frames checked: `BLADE_HEIGHT 40 mm` = `MctlDriver.todev_knife_height_set { knifeHeight: 40 }` (EMBED_DRIVER → MAINCTL, GATT write acknowledged), matching upstream. Yet in the READY state (11), the robot **never** responds to it: 4 attempts (40, 25, 25, 50 mm), no driver message, `report.work.knife_height` stays at 60 mm for 30 s. The command is ignored by the firmware outside mowing mode.

Conversely, `mow_ctrl_by_hand(main_ctrl=1, cut_knife_ctrl=0, cut_knife_height=40)` (switch to manual mowing) immediately triggers `toapp_knife_status_change { is_start: 1, start_height: 60, end_height: 40 }`, then `work.knife_height` 55 mm 1.7 s later.

Fixes:

- **Apply X mm** and the sequences: manual mowing mode, blades OFF, with the height; driver setpoint sent a second time once state 20 is observed; wait for a reached height (end of driver movement or `work.knife_height`, received after the command; a merely announced target does not count); exit the mode (`main_ctrl=0`), then 4 s of checking. Results: **REPORTED** (height kept), **REVERTED** (the firmware returns to another height when leaving manual mode), **UNCONFIRMED** (timeout, mode 20 not observed within 6 s), **INTERRUPTED**.
- `work.knife_height` equal to the target ends a movement whose end message was not received.
- Navigation test with cutting: the height is awaited after entering manual mode with blades OFF (and no longer before, in the READY state, where it always failed).
- BLE route with cutting: the 8 s wait in the READY state (always failing if the height differed) is replaced by a wait, robot stopped, at the start of each cutting range; the height is carried there by `mow_ctrl_by_hand`.

To be validated on the robot: a REVERTED result would mean that this firmware only keeps the height in mowing mode; it would then remain applied at each cut (manual, or native mission via `knifeHeight`).
