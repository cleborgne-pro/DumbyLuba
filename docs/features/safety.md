# Safety: STOP, guards and error codes

DumbyLuba controls a machine with blades. Every movement or blade command goes through continuous guards, and
everything can be interrupted with STOP. This page summarizes these protections and their limits.

| | |
|---|---|
| **Status** | Implemented and covered by JVM tests; partial physical validation (see each section) |
| **Code** | `ble/RobotBle.kt` (`stop`, `requestStop`, `unlockAfterStop`), `protocol/` (`TelemetryWatchdog.kt`, `SelfCheck.kt`, `StopUnlock.kt`, `CollisionLatch.kt`, `RobotErrorCatalog.kt`, `RobotErrorMonitor.kt`, `ExternalNavigationPolicy.kt`, `ModelSupport.kt`, `NativeTaskStop.kt`), `simulation/ProtectionPause.kt`, `diagnostics/` (`LiftGuard.kt`, `MotionProgressGuard.kt`, `BladeSafety.kt`, `CommandSilenceProbe.kt`), `map/MapScreen.kt` (protection panels) |
| **References** | [Lift detection](../LIFT_DETECTION.md) · [Robot error codes](../ROBOT_ERROR_CODES.md) · [Command cutoff](../MOTOR_WATCHDOG.md) · [Bypass after a contact](../OBSTACLE_BYPASS.md) |

> [!WARNING]
> None of these guards replaces human supervision. Keep the robot in sight, nobody in the zone, and the robot's
> physical stop within reach.

<p align="center">
  <img src="../images/command-cutoff.png" width="200" alt="Command cutoff">
  <img src="../images/robot-locked.png" width="200" alt="Robot locked after STOP">
</p>

## Read-only mode for the models that are not validated

Motion and blade commands are reserved for the validated model, the LUBA 2 AWD (`Luba-VS…`). Every other Mammotion
mower is in read-only mode: the Bluetooth send lets through only read and stop commands, routes and the native
return are refused before they start. See [supported models](supported-models.md).

## Global STOP

The STOP button in the [global bar](interface.md#global-stop-bar), the panels and the "robot connected"
notification:

1. cancels all activities (route, mowing, tests, manual control, Bluetooth diagnostics) and empties the send queue,
   keeping only the end of a message already being written;
2. stops the blades if they may be spinning (or if the robot is in manual mode, state 20);
3. cancels the Mammotion return of a route being docked (state 14), then pauses a native Mammotion task;
4. sends zero speed;
5. closes the robot link (no automatic reconnection: reconnect from Service → Bluetooth connection).

If the STOP is not confirmed by the link within 2 s, the link is closed anyway with "STOP not confirmed before
closing — check that the robot has physically stopped". A confirmed STOP only proves the write, not the mechanical
stop.

Stops triggered by a guard (below) use the same sequence without closing the link, and raise a critical
**Unexpected stop** alert when a route, a test or manual control was running.

## Continuous guards

| Guard | Rule |
|---|---|
| Freshness | state and position less than 2 s old in every moving mode; telemetry watchdog from 5 to 30 s (10 s by default, Service → Advanced settings → **Telemetry validity delay**, 1 s steps, changeable with the robot disconnected) |
| Battery | 5 % reserve when mowing; 30 % minimum for the joystick, tests and the diagnostic campaign |
| Self-check | only states 0 and 10 are nominal; 11 = locked after STOP; 23 = outside the Mammotion app's working hours; 25 = collision lock; any other or missing value = active protection |
| Bumper | contact (1014) = stop then [back-off and bypass](obstacles.md); stuck fault (1207) never taken for a contact. **Only obstacle detection**: the ultrasonic sensors are not read |
| Progress | stop if there is no progress for 15 s, or less than 3 cm in 3 s |
| Deviation | stop beyond 30 cm of deviation from the route (60 cm during a pivot and the reconvergence after it); during mowing, a route exit is recovered by a back-off and a link ([real mowing](real-mowing.md#leaving-the-trajectory-back-off-and-resume)) |
| Transient protection | non-nominal self-check during mowing: STOP, then resume only if it returns to 0/10 and stays there for 3 s (at most 30 s, 5 times per mission, never on lock 11 or 23; [details](real-mowing.md#transient-protection-pause-then-resume)) |
| Geometry | chassis envelope checked against borders, no-go zones and obstacles before every move |
| Blades | rotation confirmed within 5 s, stop within 3 s; links outside the zone with blades stopped |
| Native commands | `ExternalNavigationPolicy` forbids native mowing and native leaving of the base at send time; only the native return to base is allowed, for docking |
| Manual control | LUBA 2 only, ready state, joystick input older than 500 ms, protection, bumper/lift alert, robot error or saturated queue (more than 4 commands): STOP, new press required ([manual control](manual-control.md#guards)) |

## Lift

BLE telemetry contains neither a "lifted" field nor an inertial measurement unit. `LiftGuard` combines three signals,
in every mode controlled by the app:

1. Mammotion lift or tilt codes (1004, 1030, 2502, 2801, 11131, 11133);
2. a change in bits 3 to 8 of `sensor_status` while moving;
3. wheels without progress: less than 25 % of the expected distance over 2.5 s (at least 20 cm expected).

When triggered: critical "Robot lifted" alert, STOP and end of the activity. Detection by progress takes about 3 s and
cannot see a carried robot moving at the commanded speed.
[Details](../LIFT_DETECTION.md).

## Robot error codes

The **664 Mammotion codes** are decoded in English and French (embedded table taken from PyMammotion, completed with the
project's own translations) and each one gets a response:

| Response | Examples | Effect |
|---|---|---|
| Log | information, network, map editing | event in the log |
| Alert without stop | low battery, dirty optics, rain seen by the camera | alert notification |
| Stop | motors, blades, charging, positioning, sensors, system, navigation, **unknown code** | STOP and blades stopped if the robot is moving, critical alert |
| Dedicated safety | lift, bumper | handling described above |

Last error visible in Service → Diagnostics → **Detailed telemetry** → *Last robot error* (code and time, meaning,
category, app response, advice).
[Table and policy](../ROBOT_ERROR_CODES.md).

## Robot locked after STOP

After a STOP, some firmware versions lock the robot (self-check 11). The **Robot locked after STOP** panel on the map
offers **Unlock**: zero speed, task cancellation, error read, then up to 8 s waiting for a self-check 0 or 10 in a
report received after the write; no movement. If that fails, the app advises pressing the robot's Grass button, then
Start. The same **Unlock** is offered in the "Action blocked" dialog when a Service test is refused for this reason.

Any other protection (for example self-check 41) shows the **Robot protection active** panel: **Unlock** sends the
same commands without movement and also asks the robot for its self-check details (logged). If it fails: free the
robot, press STOP then Start. During mowing, a short protection is first waited for (resume if it clears and stays
clear for 3 s, 30 s at most, 5 times per mission). **Details** shows what the button does and the stop cause.

### Collision lock (self-check 25)

After a bumper contact, the robot can stay in self-check 25: joystick and routes are refused, and neither zero speed
nor task cancellation clears it (Oct 15: more than 10 min against a post). The **Robot stuck against an obstacle**
panel then offers **Clear**, which **backs the robot up 30 cm** in a straight line, heading unchanged, blades stopped,
along the trace it has just covered (with no trace in memory, after an app restart: along a free line on the map,
checked against borders, no-go zones and obstacles). The guards are those of the back-off after contact: lateral
deviation ≤ 12 cm, progress, lift, blades. The app then waits for the lock to clear (3 s stable, at most 30 s). If the
robot refuses to back up or keeps the lock: free it by hand, press STOP then Start. The lock remains a protection:
joystick and routes stay refused while it is active.

Beyond the mapped zones (version 1.5.2), after a [move to a point beyond the map](station-and-moves.md#beyond-the-mapped-zones):
the back-off was always refused, since it was checked against the zone borders only and the trace of the move was
not used ("no safe back-off behind the robot"). **Clear** now takes the trace of the move as well as the one of a
route, and the last 3 m of that trace driven outside the zones open a ±0.9 m corridor, as for the section of the move;
no-go zones and saved obstacles stay excluded, and inside the zones the borders apply unchanged. Without a recorded
trace beyond the map (app restarted), the back-off stays refused: "robot beyond the mapped zones without a recorded
trace", free it by hand. The same corridor is used for the back-off after a bumper contact during the move.

### Self-check 25 beyond the mapped zones (version 1.11.0)

Driven beyond its zones (2026-10-20, move to a point), the robot publishes self-check 25 without any contact. For the
blade-OFF moves, 25 is now **ignored** while the robot centre stands beyond the zones and passages, or less than 35 cm
inside their limits (chassis across the border), with bumper and lift sensors clear (`protocol/OffMapLatch.kt`):

- **joystick**: control starts and goes on;
- **move to a point**: start accepted, tracking from the Fold not stopped; with the gateway, the sections beyond the
  map travel with the route (optional trailing field of `LegSpec`) and 25 is ignored within 0.9 m of them (an older
  gateway still stops);
- map: the **Robot stuck against an obstacle** panel gives way to a dismissible note "Beyond the mapped zones
  (self-check 25): ignored…"; the log records `off_map_latch_ignored`.

A real contact is still handled: bumper or lift alert stops the joystick and the move, the contact code (-1014) gives
the back-off and the new path of the move. Inside the zones, during mowing and for routes with blades, 25 stays the
collision lock described above. Whether the robot itself accepts the speed commands under 25 remains to be checked
on the robot: if it refuses them, the progress guard stops the move ("no progress").

Mowing interrupted and robot stuck: a single panel (0.49.1), with progress, **Discard** and **Clear**; the technical
detail (self-check, stop cause) is behind **Details**. Once the robot is freed, the **Mowing interrupted** panel offers
to resume.

### Mammotion app working hours (self-check 23)

On 2026-10-15, the self-check went from 10 to 23 at **exactly 21:00:00**, robot stationary on charge, with no command
and no sensor active; the unlock attempt had no effect. This is the forbidden time window set in the Mammotion app: the
robot refuses to work until it ends. The **Robot protection active** panel and its **Unlock** button are replaced
(0.51.0) by a message: robot blocked by the Mammotion app's working hours, to be disabled in that app in favour of
DumbyLuba's, nothing to unlock (**OK** hides it until the state changes). Joystick and routes stay refused while it
lasts; a mowing that runs into it stops without a waiting pause. DumbyLuba's
[working hours](real-mowing.md#working-hours) do better: clean stop, return to base and resume at the
interrupted point.

## Command cutoff (motor watchdog)

Key question: **if the app freezes mid-mowing, does the robot stop on its own?** The protocol does not document it.
Service → **Command cutoff** measures it on your robot and your firmware.

1. Conditions: LUBA 2 ready (state 11), fresh state, battery > 30 %, no protection, blades stopped, RTK FIX, map read,
   **2.5 m clear** in front of the robot in the same zone (away from edges, no-go zones and saved obstacles), no other
   mission or test. Tick **I am supervising on site, 2.5 m clear in front of the robot**.
2. Hold **Hold for the test (≈ 10 s)**: the robot moves slowly in a straight line (**Forward speed** 10 to 120 units
   in steps of 10, 30 by default) for at least 3 s and 10 cm.
3. The app **stops sending any movement command**, while still listening to telemetry.
4. Verdict:
   - *Stops on its own*: three stationary positions within 3 cm; **demonstrated** if the stop happens within 2.5 s;
   - *Does not stop on its own*: more than 1 m covered, or still moving 6 s after the last command → the app sends
     STOP;
   - *Inconclusive*: not enough positions, button released, connection lost…

The result is stored per robot (date, firmware, speed, delay, distance, observed speed) and recalled in the Service
summary ("demonstrated", "not demonstrated", "to test"), in the route preview before **Start** ("Robot self-stop not
demonstrated (Service → Command cutoff): supervision required.") and on the resume panels ("Stay nearby during the
resume.") as long as it is not demonstrated. It does not prevent mowing. An inconclusive test does not replace a
previous conclusive result.

**Limitations**: positions at 1 Hz (delay known to the nearest second); the test simulates a frozen app with the
connection maintained, **not** a complete loss of Bluetooth; repeat it after a firmware update.
[Procedure and criteria](../MOTOR_WATCHDOG.md).

## Gateway

When controlling through a [gateway](gateway.md), the gateway stops the robot (blades then zero speed) if the pilot
phone goes silent for more than 2 s, except during autonomous tracking, where it continues the route on its own with
its own guards. Among them, a gateway phone that has fallen off the robot is detected from its Bluetooth signal and
stops the robot before it comes back over the phone with its blades
([gateway fallen off the robot](gateway.md#autonomous-tracking-android-gateway)).
