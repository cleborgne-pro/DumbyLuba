# Charging station — commands checked in PyMammotion

Source consulted on 2026-10-07:
https://github.com/mikey0000/PyMammotion/blob/main/pymammotion/mammotion/commands/messages/navigation.py

| PyMammotion function | DumbyLuba builder | NAV message |
| --- | --- | --- |
| `return_to_dock()` | `Commands.returnToDock()` | `todev_taskctrl { type: 1, action: 5, result: 0 }` |
| `cancel_return_to_dock()` | `Commands.cancelReturnToDock()` | `todev_taskctrl { type: 1, action: 12, result: 0 }` |
| `leave_dock()` | `Commands.leaveDock()` | `todev_one_touch_leave_pile: 1` |

These protobufs can be sent over the existing BLE transport; the Mammotion app
does not need to be open. The firmware still performs the station maneuvers.

> [!NOTE]
> Current state (0.65): DumbyLuba sends only `returnToDock()` (action 5, docking from the approach point, see
> [Local commands from the map](#local-commands-from-the-map)) and `cancelReturnToDock()` (action 12).
> `ExternalNavigationPolicy` refuses `todev_one_touch_leave_pile` at send time: the base is left by the app's own
> slow straight exit (`DockExit`). The investigation notes below date from 2026-10-07.

The action 5 return contains no approach point, no distance and no "final back-off only" option.
It is a global return to the station. Triggering it at the approach point may allow a short
docking, but nothing in this command guarantees that the firmware limits itself to the final back-off.
This behavior still has to be measured on the real Luba before integrating it into the DumbyLuba route.
The route controller therefore keeps its stop at the approach point.

Leaving with `leave_dock()` is automatic too. At the time it existed in `RobotBle.leaveMowingDock()`
for a DOCKED native mission (since removed; only the `Commands.leaveDock()` builder remains, never sent).
It was never integrated into the external controller's departure.
The library also exposes a recharge test (action 10), with no proof that it only performs
the final docking: it does not replace a dedicated docking command.

A future integration must confirm blades OFF and a fresh position, block any other pilot,
wait for actual charging (`chargeState` 1 or 2 depending on the decoder and existing logic), then
confirm the end of charging and the return to a ready state after leaving. A GATT ACK proves neither
docking, nor charging, nor physical departure. No station command was executed
on the robot during this investigation.

# Position on the map

The map shows the recent BLE position first, then the last available
API position or recorded FIX of the same robot. API telemetry is read through MQTT and the account API,
read-only, without starting any mowing. Without a BLE connection, the map requests a position
every 30 seconds, waiting at most 15 seconds for MQTT per request.
A map read in progress is not interrupted by these requests.

An API position without FIX quality stays visible with the note "non-FIX accuracy";
it does not replace the reliable FIX history and is never used for BLE control. Old
points are explicitly flagged. During simulation, the real robot stays visible;
the simulated point is drawn as an orange circle when the real position is known.

## Local commands from the map

Map → side bar → **Charging station** (dialog of the same name, supervision box required): **Leave the station** and **Recharge** reuse the `TrajectoryPlanner` engine and the mission's local tracking (through the gateway when it sits on the robot). A base, axis and safe access are required. An explicit departure does not wait for the high recharge threshold; blades OFF and charging contact are required, and it is refused outside the DumbyLuba working hours. No height is requested for these blades-OFF routes. User-facing behavior: [station and moves](features/station-and-moves.md).

**Docking (since 0.28.0, 2026-10-13)**: every return to base — **Recharge**, recharge at the low threshold during mowing, end of mission — follows the local route to the approach point in front of the base (blades OFF, bumper detection). Once the stop is confirmed (blades OFF, two stationary positions) within 20 cm of the approach point and manual mode has been exited, the app sends the official action 5 return; the firmware only handles docking from the front of the base. Tracking: RETURN state (14) expected within 15 s, then charging contact (1/2) within 5 min; a state that leaves RETURN without charging contact within 5 s is a failure. STOP during docking: cancel the return (action 12), pause, STOP. The "DumbyLuba / native Mammotion" selector has been removed. The former local docking (pivot at command 20 then back-off at −10) did not turn and backed off at ~1 cm/s: it never docked (test of Oct 13, "Robot state missing or stale" during alignment). Robot already stopped in front of the base: official return directly. Route refused or interrupted before the approach point: no Mammotion return is sent.

**Stop the Mammotion task** is also available in Service → Manual control (*Mammotion task* group); **Stop the Mammotion return** in the Charging station dialog cancels a native return. States 13/14/19/39: cancel the return if present (action 12), pause (2), task cancellation (4), discs OFF and STOP. The button does not start any local route. New READY / OFF telemetry later than the final write is required, with a 15 s timeout; otherwise the result is unconfirmed. Other local activities and a cancellation already in progress block the action. Sources of the actions: [MessageNavigation](https://github.com/mikey0000/PyMammotion/blob/main/pymammotion/mammotion/commands/messages/navigation.py). Local stops without closing the connection now keep the BLE renewal and the session availability.
