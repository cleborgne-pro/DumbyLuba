# Charging station and moves

Leaving the base, returning to it, or sending the robot to a point on the map, always with blades stopped and through
the same route engine as mowing.

| | |
|---|---|
| **Access** | Map → side bar → **Charging station** or **Move to a point** |
| **Status** | Implemented; leaving the base and returning tried on the robot, docking via the official Mammotion return |
| **Code** | `map/MapScreen.kt` (`StationDialog`, `InterruptedPointPanel`), `map/CloudStationDialog.kt`, `simulation/DockExit.kt`, `NativeDocking.kt`, `PointResume.kt`, `planning/RoutePlanning.kt` (`station`, `point`), `ble/RobotBle.kt` (`baseAction`, `startPointNavigation`) |
| **Reference** | [Station commands verified in PyMammotion](../STATION_BLE.md) |

<p align="center">
  <img src="../images/station.png" width="200" alt="Charging station">
  <img src="../images/move-to-point.png" width="200" alt="Move to a point">
</p>

## Charging station (robot connected)

The **Charging station** dialog recalls: "Recharge: DumbyLuba drives the robot in front of the base along the verified
local route (50 cm from the edges, blades stopped), then the official Mammotion return handles the docking." and
"Stay nearby, stop within reach." It needs a known base and an idle robot (no route, test, manual control or
simulation). Tick **I am supervising the robot and the way to the station is clear**, then:

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
- **Recharge (native Mammotion)**, supervision box (**I am supervising the robot and the way to the station is
  clear**) mandatory and unticked after each request: wakes the robot if it is asleep, official return and docking
  monitoring with a progress bar and its state;
- **Stop the Mammotion return**: goes through the cloud (a few seconds of latency) or the robot's STOP button;
  **Close** leaves the dialog.

## Move to a point

1. **Move to a point** (side bar; robot connected over Bluetooth or gateway and idle, complete map, not on the demo
   or account map), then tap the destination ("Tap the map to choose the destination."). The panel computes the path
   ("Computing the path…") and shows "Safe path: 12.3 m · blades stopped" with a blue target, or the reason of the
   refusal. Another tap changes the destination.
2. Tick **I am supervising the move, area clear and stop within reach**, set the **Travel speed** (10 to 100 %, the
   same setting as during mowing), **Go to the point**.

- Blades always stopped; at most 600 s; destination within a 200 m radius; battery above 30 %; fresh state and
  position and a known heading. Stop if no progress towards the point for 15 s.
- The robot and the destination must lie in a mapped zone: "Place the robot inside a mapped zone", "Choose a point
  inside a mapped zone", "Destination too close to a limit or an exclusion", "No safe path to this point (zones not
  connected by a recorded passage?)", "The robot is already at the requested point".
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

## Project rule on native commands

`ExternalNavigationPolicy` filters every navigation command at send time: only stop commands (pause, cancel, cancel
return) and, on explicit choice, the return to base for docking are allowed. Starting a native mowing or a native
exit from the base is forbidden.

**Stop the Mammotion task** (Service → Manual control, *Mammotion task* group) stops a task started from the Mammotion
app: pause, cancel, blades stopped and STOP, with confirmation of the ready state within 15 s.
