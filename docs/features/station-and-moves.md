# Charging station and moves

Leaving the base, returning to it, or sending the robot to a point on the map, always with blades stopped and through
the same route engine as mowing.

| | |
|---|---|
| **Access** | Map → side bar → **Charging station** or **Move to a point** |
| **Status** | Implemented; leaving the base and returning tried on the robot, docking via the official Mammotion return |
| **Code** | `map/MapScreen.kt` (`StationDialog`, `PointMovePanel`, `InterruptedPointPanel`), `map/CloudStationDialog.kt`, `simulation/DockExit.kt`, `simulation/BaseDeparture.kt`, `NativeDocking.kt`, `PointResume.kt`, `planning/RoutePlanning.kt` (`station`, `point`), `simulation/PointPlanner.kt`, `ble/RobotBle.kt` (`baseAction`, `startPointNavigation`) |
| **Reference** | [Station commands verified in PyMammotion](../STATION_BLE.md) |

<p align="center">
  <img src="../images/station.png" width="200" alt="Charging station">
  <img src="../images/move-to-point.png" width="200" alt="Move to a point">
</p>

## Charging station (robot connected)

The **Charging station** dialog recalls: "Recharge: DumbyLuba drives the robot in front of the base along the verified
local route (50 cm from the edges, blades stopped), then the official Mammotion return handles the docking." and
"Stay nearby, stop within reach." It needs a known base and an idle robot (no route, test, manual control or
simulation). Only the action that fits the charging contact of the latest Bluetooth report (at most 15 s old) is
shown: robot on its base, **Leave the station** alone (note "Robot on its base. Leave the station: straight exit
driving forward, slowly, blades stopped." and box **I am supervising the robot and the exit in front of the station
is clear**); robot away from the base, **Recharge** alone. Without a recent report, both are shown and the route checks
the contact itself. Tick the supervision box (away from the base: **I am supervising the robot and the way to the
station is clear**), then:

- **Leave the station**: charging contact required, exit driving forward, in a straight line and slowly (≈ 10 cm/s,
  no pivot on the base, heading within ±10°, ±10 cm corridor, at most 60 s). Release of the contact is confirmed within
  5 s. The battery level is not waited for.
- **Recharge**: local route with blades stopped to the approach point in front of the base, keeping at least 50 cm
  from the borders, then a confirmed stop and the **official Mammotion return** for the docking alone. If the robot is
  already within 20 cm of the approach point, the official return is requested directly (start expected within 15 s,
  at most 30 min). Already charging: "Already on the base: charging observed"; a Mammotion return already in
  progress is refused.
- **Stop the Mammotion return**, visible when a native return is in progress (robot state 14); **Leave the station**
  and **Recharge** are then disabled.

The dialog lists the progress ("Preparing the station route…", route state, native return state, task stop state)
and, on a refusal, "Base: …". Leaving the base is refused outside the [working hours](real-mowing.md#working-hours);
returning to it is always allowed.

## Charging station (through the account, without Bluetooth)

With the [Mammotion account](mammotion-account.md) connected and no Bluetooth link:

- the dialog states "Native Mammotion return through the account": the Mammotion firmware computes the route and
  handles docking, as in the Mammotion app;
- robot already on its base according to the latest account report (at most 3 min old): "Robot on its base: no return
  needed." replaces the supervision box and the **Recharge (native Mammotion)** button;
- **Recharge (native Mammotion)**, supervision box (**I am supervising the robot and the way to the station is
  clear**) mandatory and unticked after each request: wakes the robot if it is asleep, official return and docking
  monitoring with a progress bar and its state;
- **Stop the Mammotion return**: goes through the cloud (a few seconds of latency) or the robot's STOP button;
  **Close** leaves the dialog.

## Move to a point

1. **Move to a point** (side bar; robot connected over Bluetooth or gateway and idle, complete map, not on the demo
   or account map), then tap the destination ("Tap the map to choose the destination, inside or beyond the zones.").
   The panel computes the path ("Computing the path…") and shows "Safe path: 12.3 m · blades stopped" with a blue
   target, or the reason of the refusal. Another tap changes the destination.
2. Tick **I am supervising the move, area clear and stop within reach**, set the **Travel speed** (10 to 100 %, the
   same setting as during mowing), **Go to the point**.

- Robot on its base: it first leaves in a straight line, slowly, to the link point (see
  [below](#leaving-the-base-first)).
- Blades always stopped; at most 600 s; destination within a 200 m radius; battery above 30 %; fresh state and
  position and a known heading. Stop if no progress towards the point for 15 s.
- Refusals: "Destination inside a no-go zone or an obstacle", "Destination too close to a no-go zone or an obstacle",
  "No safe path to this point (zones not connected by a recorded passage?)", "The robot is already at the requested
  point", and beyond the zones the limits below.
- The path respects zones, passages and accesses, avoids no-go zones and obstacles, and keeps the move distance from
  borders.
- A bumper contact triggers a back-off then a new path (at most 3 recoveries and 3 repairs).
- **STOP** at any time (in the panel and the global STOP bar). Closing the panel with its cross, or leaving the map,
  stops a move in progress.
- **Resume** (0.61.0): the destination is saved at the start. If the move does not complete (STOP, contact, connection
  lost, app closed or restarted), the map shows **Move to a point interrupted** with the time, the reason and the
  remaining distance ("Destination 8.4 m from the robot: route recomputed from its position."): **Resume** recomputes
  the path from the robot's actual position to the same destination, with the same checks; **Discard** deletes the
  save. "Stay nearby during the resume." appears while the robot self-stop is not demonstrated. Arrival deletes it too, as does starting a coverage task (mowing, survey,
  border).

### Leaving the base first

A move started with the robot on its base (charging contact) or still inside the base safety zone never pivots near
the station (version 1.5.1):

- the robot first leaves in a **slow straight line** to the link point in front of the base, exactly like a mowing
  route leaves it ([DockExit], 10 cm corridor, heading checked at rest, at most 60 s, release of the charging contact
  confirmed; run by the gateway when it is on the robot), then turns and follows the path computed **from the link
  point**;
- the preview draws that exit before the path and the panel recalls it: "Robot on its base: it first leaves in a
  straight line, slowly, to the link point (1.5 m), then follows the path.";
- every path, from the base or not, goes around the base safety zone; a destination inside it is refused
  ("Destination inside the base safety zone: use Recharge to return to the base");
- refusals before any movement: "Charging observed far from the configured base", "Unknown base axis: observe the
  robot while charging", "Robot next to the base, outside its exit corridor: put it back on the base or move it away
  with the joystick";
- the same exit precedes the [navigation test](tests-and-diagnostics.md#navigation-test) and the cutting test while
  moving; a new path computed from inside the safety zone (after a contact) starts with it too.

### Beyond the mapped zones

The robot and the destination may lie outside the mapped zones (version 1.5.0), for example to reach an area not yet
recorded before [recording it by driving](manual-control.md#recording-a-zone-a-passage-or-a-no-go-zone-by-driving),
or a point along a boundary that the 50 cm reserve kept out of reach.

- The path uses the zones and passages as usual; the part outside them is a **straight section** from the nearest
  point of the navigable surface to the destination (or from the robot to that surface, or straight from the robot to
  the destination when both are outside, whichever is shortest without a map). It is drawn in **orange dots** over the
  route, during the preview and the move.
- The panel shows "Path: 18.4 m including 6.2 m beyond the map · blades stopped" and a warning: the section is driven
  without border check, only the no-go zones and the saved obstacles are avoided; a wall, a pool or a slope are not
  known there. A second box, **I have checked that the dotted section is clear and safe**, is required (unticked on
  every new destination).
- At the start, the **Move beyond the mapped zones** notification ([notifications](notifications.md)) recalls the
  length without a map.
- Limits: at most 30 m beyond the map per move ("Too far beyond the mapped zones: 42.0 m without a map (maximum
  30 m)"); the axis of the section stays at least half a chassis width + 5 cm from the no-go zones and from the saved
  obstacles with their margin ("The section beyond the map passes too close to a no-go zone or an obstacle"); a
  destination inside one of them is refused.
- The section opens a ±0.9 m corridor that joins the surface of the planner: the footprint checks, the tracking from
  the Fold or by the gateway, the recoveries and the new path after a bumper contact work there as inside the zones.
  Blades always stopped, same STOP, duration, radius and battery limits.
- Self-check 25 published beyond the map does not stop the move nor prevent starting one from there (version 1.11.0,
  [safety](safety.md#self-check-25-beyond-the-mapped-zones-version-1110)).
- Robot stopped beyond the map with self-check 25 or after a contact: the back-off follows the trace just driven there
  (corridor of its last 3 m), see [collision lock](safety.md#collision-lock-self-check-25).

## Project rule on native commands

`ExternalNavigationPolicy` filters every navigation command at send time: only stop commands (pause, cancel, cancel
return) and, on explicit choice, the return to base for docking are allowed. Starting a native mowing or a native
exit from the base is forbidden.

**Stop the Mammotion task** (Service → Manual control, *Mammotion task* group) stops a task started from the Mammotion
app: pause, cancel, blades stopped and STOP, with confirmation of the ready state within 15 s.
