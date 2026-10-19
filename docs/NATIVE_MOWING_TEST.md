# Native mowing — preparation and validation on the robot

> [!WARNING]
> **Historical procedure: this feature is no longer in the app.** The **Robot autonomous mowing** entry
> (**Prepare on the robot**, **Start mowing** with the firmware's route) has been removed from the interface; the
> version that removed it is not recorded in the [CHANGELOG](../CHANGELOG.md) (not verified). Mowing is now always
> driven by the app along its own route ([real mowing](features/real-mowing.md)); Mammotion's native planner is never
> used. Only the final docking still goes through the official Mammotion return
> ([station and moves](features/station-and-moves.md)), and a Mammotion task started from another app is still shown
> ("Mammotion task active") and paused by STOP ([safety](features/safety.md#global-stop)). The text below is kept as
> a record of the native protocol that was studied.

This feature uses the route computed by the firmware. It does not follow the optimized route shown by DumbyLuba and does not send local obstacles. Zones, passages and exclusions must exist in the robot's map. Whether the firmware can actually reach the start, turn, align, charge and set off again remains to be validated on site.

## Usage

1. Connect the LUBA 2 over BLE from the Debug panel. Close the Mammotion app to avoid a competing pilot.
2. Read the map **over BLE during this connection**; a cloud map or a cache does not replace this read. Select a small accessible zone, linked to the station by the passages recorded in the robot.
3. Map → **Robot autonomous mowing**. Choose the height, then **Prepare on the robot**. Initial settings: lines, native speed 0.3 m/s, spacing 25 cm, one border pass, upstream avoidance `UltraWave=2`. The settings of the external preview are independent.
4. Wait for a native response matching the zones and the height, with result 0 and a route hash that is non-zero and different from 1. Maximum delay 60 s. These criteria are conservative: a different response must be analyzed in the logs; no fallback start is sent.
5. Check that there are no people/animals/objects, stay close with access to the physical stop, tick supervision and press **Start mowing**. State and position less than 2 s old, battery >30 %, blades OFF and protections clear are required.
6. Observe the arrival at the start, blade activation, the first row, the U-turns and the links; fill in the on-site checks.
7. Test **Pause**, then **Resume mowing**. Test **Return to base**: if the robot is mowing, pause is requested first and the return is sent only after a new pause state.
8. Check the final alignment, the contacts, charging and the battery trend. `charge_state=1/2` with a ready/charging state is a reported contact, not an electrical certification nor a full recharge.
9. From the station, **Test leaving the station** (departure without a blade command) or **Restart from the resume point** (mowing, native action 7). Physically check the departure and the resume at the right point.
10. For automatic return and resume after low battery, observe a real supervised cycle: thresholds and decisions remain the firmware's. The app does not change the recharge settings.

**STOP stays pinned at the bottom of the window.** It requests a native pause, cutting OFF if the blades are reported active and a zero joystick, then closes the link. A successful BLE write does not prove a mechanical stop. Leaving the app, loss of fresh telemetry, a protection or a transition without a report within 30 s trigger the stop; no app-side automatic reconnection or resume. After a stop, reconnect and read the map again for a new mission.

## Recorded evidence

`native_mowing` report exportable from the logs: zones, height, firmware, timestamped phases, observed movement (≥20 cm), mowing with WORKING state and blade bits ON, return with RETURNING state, charging contact, and departure with loss of contact and movement ≥20 cm. The human observation checkboxes are recorded separately. They do not automatically validate all of the robot's capabilities. The exact cutting start point and the manoeuvres are not exposed by these reports: visual validation is mandatory.

The JVM tests check the protobuf messages, the guards, charge decoding and tracking transitions. They do not run any mowing on the real robot.

## References inspected

Frozen local PyMammotion sources `0bf120b2b390603266c8fe8a4b44347bdc994006`:

- [Navigation commands](https://github.com/mikey0000/PyMammotion/blob/0bf120b2b390603266c8fe8a4b44347bdc994006/pymammotion/mammotion/commands/messages/navigation.py): `generate_route_information`, `start_job` (1), pause (2), resume (3), return (5), breakpoint resume (7), `leave_dock`.
- [Native configuration](https://github.com/mikey0000/PyMammotion/blob/0bf120b2b390603266c8fe8a4b44347bdc994006/pymammotion/data/model/generate_route_information.py), [preparation and response](https://github.com/mikey0000/PyMammotion/blob/0bf120b2b390603266c8fe8a4b44347bdc994006/pymammotion/messaging/mow_path_saga.py).
- [Firmware states](https://github.com/mikey0000/PyMammotion/blob/0bf120b2b390603266c8fe8a4b44347bdc994006/pymammotion/utility/constant/device_enums.py): READY=11, WORKING=13, RETURNING=14, CHARGING=15, PAUSE=19, CHARGING_PAUSE=39.
- [Charging contact](https://github.com/mikey0000/Mammotion-HA/blob/9b77f9defe692300800192800b89a6688a3829c9/custom_components/mammotion/binary_sensor.py): codes 1 and 2.

Physical validation of this new feature: **not done**. To be completed with the reports and observations from the real cycle.
