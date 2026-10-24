# Lift detection

Field observation (2026-10-10): the robot was lifted while driving forward and did not stop. Under BLE control (`DrvMotionCtrl` joystick commands), the LUBA 2 firmware keeps applying the requested speeds and does not necessarily report an error. Any robot error already stopped the route, so none occurred during that test.

BLE telemetry has no "lifted" field and no IMU data. `LiftGuard` (`diagnostics/LiftGuard.kt`), hooked into `RobotBle` on every telemetry frame and every motion command, therefore combines three signals, in every mode driven by the app (joystick, routes, debug mowing, survey, tests, base):

1. **Mammotion lift or tilt codes**: 1004, 1030, 2502, 2801, 11131, 11133 (PyMammotion table `error_codes.csv`). Stop if a movement or activity is in progress; otherwise just a `lift_reported` event.
2. **Bits 3 to 8 of `sensor_status`** (undocumented, between bumper and blades): any change while moving stops the robot.
3. **Wheels without progress**: linear command of constant sign, without a stop, over 2.5 s (offset by the 600 ms command delay), while the RTK position advances by less than 25 % of the expected distance (0.00095 m per unit, counted above a 100-unit low-speed dead band: below ≈ 60 units the LUBA 2 hardly moves, and up to about twice that it is much slower than the calibration). Minimum threshold: 20 cm expected, i.e. at least ≈ 185 units held 2.5 s, which ignores gentle joystick driving (2026-10-22: false "Wheels commanded without progress"), pivots, the slow approaches of the obstacle test and start-ups.

When triggered: `lift_detected` event (source, `sensor_status`, position, state), critical alert "Robot lifted", then STOP at zero speed and end of the activity.

Wheel slip (since 0.36.0): the same measurement, above the stop threshold but below 55 % of the robot's usual progress (median of the last 40 windows), is recorded as slip in the [difficulties heatmap](DIFFICULTIES.md), without stopping.

Limitations: progress-based detection takes about 3 s and does not see a robot being carried while it drives at the commanded speed. A hard blockage or heavy slip also triggers the stop, which is intended. The firmware itself cuts the blades when lifted: the route already fails with "Blade rotation interrupted".

Recommended test, blades stopped and under supervision: joystick forward, lift the rear then the front, check that it stops and export the log (`lift_detected`, `robot_error`, `sensor_status`).
