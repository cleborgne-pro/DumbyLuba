# Robot error codes

Every code emitted by the robot is decoded and gets an explicit response; none is silently ignored.

## Code sources

`RobotErrorMonitor` reads the three protocol channels: `toapp_err_code` (instant error), `debug_errocode_report` and the type 2 `system_update_buf` history (the first history received serves as the baseline; only new code/time pairs are processed). Codes arrive signed (`-1005`); lookup uses the absolute value.

## Embedded table

`app/src/main/resources/fr/luba/poc/protocol/robot_error_codes.tsv`: the 664 codes of the Mammotion table distributed by PyMammotion (`error_codes.csv`, pinned commit, GPL-3.0), each with an English label and advice (upstream `en_implication` / `en_solution`) and a French label and advice; the app shows them in the interface language. 197 French labels missing or inaccurate upstream are written in `tools/robot-error-codes-fr.tsv`. The manufacturer's advice is used when it exists in the interface language; otherwise the app shows the category's action. Regeneration: `tools/build-robot-error-codes.py` (checksum check of the source CSV, rejects any code without a French label).

A code missing from the table is classified as "Unlisted code" and treated as a fault (precautionary stop).

Every code that maps to a difficulty (blockage, slip, lift codes) is also recorded on the [difficulty map](DIFFICULTIES.md).

## App response

| Response | Families | Effect |
| --- | --- | --- |
| Log | Information (0, docking 1307, updates), network and account (Wi-Fi, 4G, LoRa, certificate, server codes), map editing | `robot_error` event with label, category and response; no stop, no notification |
| Alert without stop | Low battery (1005, 8003, 7000001/2: return to base is still handled by the route), degraded vision or dirty optics, battery temperature at its limit, water/rain seen by the camera (5002, 5004, 11116: stopping for rain remains the user's decision), trace of an earlier crash | Status + "Error reported by the robot" notification (WARNING) |
| Stop | Motors and blades, charging failure, positioning, faulty sensors, water inside the robot, system, navigation (robot stuck, return impossible…), bumper when leaving the station (1217), unknown code | If an activity moves the robot or the blades (BLE mowing, route, navigation, manual control, obstacle tests, command cutoff, blades, height, native mission, preparation): STOP at zero speed, blades OFF, reason "Robot error code · label" and CRITICAL alert. Robot stationary: status + WARNING alert |
| Dedicated safety | Lift / tilt (`LiftGuard.LIFT_CODES`), contact 1014, stuck fault 1207 | Existing handling unchanged (lift stop, bumper validation, local obstacle, bypass) |

Notifications follow the rules of [local alerts](features/notifications.md): one notification per robot and type, silent repeats for 15 min, immediate escalation. A STOP-category code raised during a route, a navigation run or manual control appears as the **Unexpected stop** alert of that stop instead of a separate one.

## Display

Service → Diagnostics → **Detailed telemetry** → **Last robot error**: code and time received, meaning, category, app response ("log only", "alert without stopping", "movement stopped and alert", "lift / bumper safety") and advice; "No error since the app was opened." otherwise. User guide: [safety](features/safety.md#robot-error-codes). The cloud read-out (MQTT) also decodes received codes: log for all of them, status for those that are not purely informational; it sends no movement command.

## Limitations

The classification relies on the text and the module in the Mammotion table, not on a physical validation of each fault. Codes for non-LUBA models (pool cleaner, sweeper) are decoded and classified like the others. The manufacturer's severity levels (`niveau`) are kept in the table but do not drive the response: their semantics are not documented.
