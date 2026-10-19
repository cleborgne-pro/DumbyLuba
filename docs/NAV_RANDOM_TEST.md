> The random walk described here has been replaced in the interface by the [lightweight map navigation](MAP_NAVIGATION_TEST.md). This document keeps the description of the earlier tests.

# Supervised random walk — version 0.7.0

Supervised navigation tests: the robot **actually moves** along random segments, its position is shown on the map if telemetry provides it, and mowing can be switched on or off during the walk. It is the **only test in the POC that sends non-zero motion speeds**; the BLE campaign and the cutting tests remain stationary.

## Principle

Debug panel → **Navigation**. For each segment, the planned walk alternates: an **on-the-spot rotation** (1–3 s), a **forward move** (2–6 s), then an **explicit STOP** followed by a pause (1–2 s). Between segments, zero speed is re-sent at 5 Hz. The plan is drawn at random on every launch (`RandomWalk.plan`, tested on the JVM: total duration capped, always ending with a STOP). Parameters: number of segments (3–20), maximum duration (30/60/120 s), linear and angular speeds in **raw units** (10–300 and 10–150), maximum radius around the start (2–15 m), mowing ON/OFF with a height of 25–70 mm.

The map view (`MapLive`) shows the outlines, the starting point, the track of received positions and the robot with its heading. **If available**: without a position in the telemetry, no marker is drawn and the interface says so; the test goes on with the logs and duration measurements. Without a map, the walk is still possible (confinement reduced to the maximum radius).

## Test procedure

1. Clear, flat area, no people or animals, the robot's physical stop within reach. Never leave the phone unattended during a walk.
2. Connect the robot and wait for fresh telemetry (state ≤ 2 s, battery > 30 %, MODE_READY=11, self-check 0 or 10 (11 = robot locked after STOP: "Unlock" button on the map, or Grass then Start on the robot), bumper free). These preconditions are checked at start-up and **every 200 ms during the walk**.
3. Set the speeds **to the lowest values** for the first walk (uncalibrated raw units).
4. Tick on-site supervision, then **hold** the "RANDOM WALK" button. Releasing it (dead-man) stops immediately: STOP, and blades OFF if mowing was active. The STOP WALK button, the emergency STOP and the physical stop remain available. Leaving the Navigation screen also stops the walk.
5. Read the results: observed displacement per segment, observed speed (m/s), observed stopping delay after each STOP, residual displacement during pauses.

## Mowing option

"Enable mowing during the walk" checkbox. In that case, the **prior OFF check is mandatory**: "CHECK BLADES STOPPED (OFF)" button, wait for the OFF state received after the write (same flow as the Blades tab). Only then does the app request ON (`DrvMowCtrlByHand`) and **wait for the blades ON state to be confirmed (5 s max) before any movement**; without confirmation, no movement is sent (`TONTE_NON_CONFIRMEE`). During the walk, the blade state must stay consistent with the chosen option (OFF ⇒ bits 0; mowing ⇒ bits 1): any inconsistency stops the walk and requests OFF. At the end of the walk, OFF is requested before zero speed; an OFF not confirmed within 5 s triggers STOP and shutdown.

## Navigation measurements

- **Observed displacement**: sum of the position steps received during each piece (meters, local robot/RTK frame). Without a position, the measurement is missing and flagged.
- **Observed speed**: displacement / duration of the piece. Used to **calibrate raw units**: compare the observed speed with the value sent to estimate your firmware's real factor.
- **Observed stopping delay**: time between the STOP at the start of the pause and the moment two consecutive positions differ by less than 5 mm. Not timestamped if the position is missing. This is an observation, not a guarantee of a motor watchdog.

## Safeguards

- Held button (dead-man); release, leaving the screen, background, GATT or log failure → STOP (+ blades OFF), logged as `ABORTED`.
- Continuous check: state freshness ≤ 2 s, battery > 30 %, bumper free, consistent blade bits.
- **Maximum radius** around the starting point (default 5 m): leaving it → stop.
- **Zone confinement** when the map is available: the robot must stay inside the starting zone shrunk by a 0.5 m margin (`Geometry.inside` + distance to edges); leaving it → stop.
- Total duration capped by construction of the plan (30/60/120 s), then a final STOP.
- Mutually exclusive with the BLE campaign and the cutting tests; command speeds are bounded in `Commands.movement` (±300 / ±150).

## Limits — do not over-interpret

- **Uncalibrated speed units**: `DrvMotionCtrl.set_linear_speed` / `set_angular_speed` are raw integers. Upstream ([movement.py](https://github.com/mikey0000/PyMammotion/blob/0bf120b2b390603266c8fe8a4b44347bdc994006/pymammotion/utility/movement.py)) applies ×10 and ×4.5 after the rocker transformation; this does not allow concluding m/s on your firmware. Start low, increase in steps, note the observed speeds.
- **No motor watchdog has been demonstrated**: an Android crash, a BLE loss or a process kill can prevent the STOP from being sent. The robot must have its own safety stop; the physical stop stays within reach.
- A GATT write response proves neither that a motor command was accepted nor that the machine actually stopped.
- Mowing while moving is a new combination (the cutting tests assumed a stationary robot); disc rotation is not proven by the state bits.
- Positions come from telemetry (`rpt_dev_location` / 10,000); their quality depends on RTK and the firmware. The track shown is made of **received** positions, not an interpolation.

## Reports

Each walk persists a `campaign-<id>.json` report with `kind = "random_navigation"`: configuration, planned pieces, full track (x, y, heading, t), results per segment, observed displacement, stopping delay, battery, firmware. The `nav_*` events go into the JSONL log; the Debug panel's ZIP export includes them.

## Automatic mode (0.7.2)

In Debug → Navigation, tick "Automatic mode", choose with or without mowing, confirm supervision, then press "Start auto". No need to hold the button. The Stop button, leaving the tab and going to the background stop the test. The hold-to-run mode remains available.

Auto mode requires a recent position (≤ 2 s), keeps the maximum radius and the zone margin when a map is available, and interrupts the walk on bumper, protection, low battery or loss of telemetry. The duration includes blade activation. A displacement under 10 cm, or mowing without an observed ON state, gives UNCONFIRMED. A STOP pause without a measured mechanical delay also gives UNCONFIRMED for that pause. PASS does not certify the final mechanical stop.

## Manual joystick speed

The **Maximum speed** slider (10–100 %, 30 % by default, kept between launches) of the Manual control page reaches the real ceiling of the Mammotion app's rocker (PyMammotion `control.py`: `linear_speed == ±1000`, `angular_speed == ±450`): 10 % = 100, 30 % = 300 and 100 % = 1000 linear units, rotation 450 at 100 %. With ≈ 1 mm/s and ≈ 0.1 °/s per unit as measured, 100 % ≈ 1 m/s and ≈ 45 °/s; above 500 units (0.48 m/s) the speed has not been measured yet. The former ceiling (300 / 150, ≈ 0.3 m/s and 15 °/s) now corresponds to 30 %. The automatic navigation ceilings (500 / 450) are unchanged.

## Move to a point

Map → **Move to a point** → tap the destination → check the displayed path → tick **I am supervising the move, area clear and stop within reach** → **Go to the point**. The path is recomputed at launch from the fresh BLE position. The visibility graph searches for the shortest path in the navigable polygonal surface, with 0.95 m of clearance and a margin around local obstacles that includes the rotation footprint. Straight line when clear; otherwise bypass of known no-go zones and obstacles. The full real map is mandatory; the destination must lie in the robot's zone. A destination that is forbidden, unreachable, too close to a boundary or in another zone is refused. Passages between zones are not validated for this mode.

Blades OFF; speed from the panel's **Travel speed** slider (10–100 %, 50–500 raw units; rotation 210–450 units), maximum radius 200 m, maximum duration 10 min. Tracking keeps the checks on freshness, battery, protections, 30 cm deviation and presumed blocking. Arrival within 15 cm: STOP. STOP, closing the mode, leaving the map and going to the background stop the move. Avoiding an unknown obstacle cannot be guaranteed: a received contact or a presumed blocking causes a stop, and no remote detection is claimed. The mode requires supervised physical validation after deployment.

## Shared tracking and planning after the tests of October 8 at 13:48

`TrajectoryPlanner` is the engine for navigable surface, visibility, passages and footprint used by mowing routes, the simulator, move-to-point and base maneuvers. The separate point planner has been removed; the mowing stripe generators remain specific to coverage, but their transits use the same engine. `TrajectoryFollower` drives all BLE tracking: pivot proportional to the error, 12°/5° hysteresis to reduce pivot/advance alternation, slowing down on arrival. A rotation in progress no longer counts as a lack of linear progress. The envelope and stops are still checked.

The real speed is adjusted live from the map (point or mowing route) and the Navigation test: 10–100 %, i.e. 50–500 raw linear units (originally 30–300). A change takes effect at the next tracking loop and never resumes a stopped mission. Simulator speeds remain expressed in simulated m/s, with no invented physical conversion. The next test must confirm the real smoothness; software tests do not prove it.
