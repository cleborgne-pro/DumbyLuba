# Obstacle bypass during BLE routes — October 11, 2026

## Before

A bumper contact (1014 / -1014) during mowing, a survey or a station route stopped the mission: STOP, an
obstacle proposal in Service → Obstacles, manual resume. The existing local repair (`LocalReplanner`) never
succeeded after a contact: the robot touches the obstacle, its center is ≈ 0.55 m from the estimated center,
still inside the reserve where pivoting is forbidden, and no reverse movement was planned. An obstacle recorded
during the mission also stopped the route ("Local obstacle inside the robot envelope").

## Now

A single building block: `ObstacleBypass` (simulation) repairs the prepared route using the mission's navigation
(`TrajectoryPlanner.withHoles`), with the same envelope, zone-edge, no-go, access and base safety zone checks as
the preparation.

1. **Stop**: on contact, immediate STOP (command queue flushed), contact pose stored with the fused heading from
   tracking. Tracking turns the blades off (stop confirmed by the robot) and waits for two stationary
   positions.
2. **Estimated obstacle**: from the stopped position, front bumper at 0.345 m, radius 0.2 m, margin 0.3 m
   (type "Other"). It applies to the current mission; it is recorded at once on the map as an encountered obstacle
   (since 0.43.1) and becomes a presumed obstacle for 4 h; saving it permanently is a user action (record on the map
   or Service → Obstacles; no Mammotion no-go zone).
3. **Back-off**: straight line, unchanged heading, ≈ 10 cm/s, from 30 cm to 1 m. The back-off point lies on the
   traveled trace (≤ 25 cm); the chassis sweep is checked against edges, no-go zones and obstacles at their
   physical radius; the list stops at the first blocked step. Back-off chosen: the shortest one that allows a full
   pivot and travel with the margins, otherwise the longest one that allows a physical pivot (departure then
   includes a clearance move). During the back-off: lateral deviation ≤ 12 cm, progress, envelope at every step,
   bumper detection kept alive, 20 s maximum (pivot time excluded), confirmed stop.
   Heading hold (`BackOffSteering`, 1.1.4): the reverse is commanded with a zero angular speed and the robot does not
   steer while reversing (steering sign in reverse never measured). When the heading error exceeds 8°, the robot
   pivots in place (verified convention) to the back-off heading tilted towards the line (atan(offset / 0.4 m),
   20° at most) until the error is below 3°; heading compared through the change of the reported heading since the
   start (no compass offset). No pivot in the first 10 cm, pivot checked with the physical envelope, 4 s at most
   (then 15 cm of reverse before the next one), 6 per back-off. A deviation beyond 12 cm (`BackOffDeviation`) after a
   contact during a mission becomes a `RouteDrift` handled by `DriftRecovery` on the repaired route, instead of
   stopping the mowing. Oct 19, 14:02, Fosse: heading 90° → 55° over 0.69 m (slip at 43 %), 14 cm, mowing stopped
   15 cm from the border; replayed in `BackOffSteeringTest`: the pivot would have started at 1.7 cm of deviation.
   **Obstacle photo** (setting enabled by default): the camera stream is requested as soon as the contact occurs;
   after the confirmed stop, the robot waits stationary, blades OFF, up to 25 s for the photo from its camera, then
   resumes (details: [CAMERA.md](CAMERA.md)).
4. **Repair of the remaining route**: any portion closer than the cut radius of a discovered obstacle is
   removed — including later passes that cross it again. Cut radius = tracking reserve (radius + margin + 0.43 m
   rotation envelope) + 5 cm, brought back to the robot center, + the space for a full pivot (≈ 1.18 m from the
   center for a 0.2 m + 0.3 m obstacle). The kept pieces are joined first by a **tight detour on the most
   favorable side** (see below), otherwise by `TrajectoryPlanner.between` with the entry and exit headings
   (50 cm from drawn edges when possible, narrow passages crossed). If a link fails, the entry point moves
   forward (0.3 to 4 m) and the exit moves back (0.3 to 1 m); an unreachable continuation is skipped; the end of
   the route (base approach point) is never abandoned. **Non-mowing transit** crossed by the reserve (since
   0.37.0): see below, direct link to its destination.
5. **Blades**: the first link after the contact is blades OFF. Later links lying entirely inside the mowed area
   keep the blades running, like the prepared links (`BladeContinuity`).
6. **Final check**: the full route is verified (`requirePath`) from the end of the clearance move, outside the
   base safety zone, then followed with the usual checks (30 cm deviation, progress, envelope, recorded and
   discovered obstacles).

### Tight detour and side choice (since 0.35.0)

Previously, the link followed the navigation comfort ring: 50 cm beyond the obstacle exclusion, i.e. ≈ 1.22 m
from its center, and the first path found, with no side choice. Now `ObstacleBypass.repair` first tries, for each
link, **tight detours** around the nearest discovered obstacle (`arcAround`):

- **Candidates**: the straight line (a pass that grazes the obstacle without entering its reserve), then, for each
  side (left, right), tangent, arc around the obstacle and tangent, at the smallest feasible radius among
  tracking reserve + 8, 15, 25 or 40 cm (≈ 1.01 m from the center for a 0.2 m + 0.3 m obstacle). Arc vertices
  every 20° at most, on the circumscribed radius: the chords stay at the requested radius.
- **Checks**: every candidate passes the mission checks (`pathSafe`: safe surface, envelope and pivots with the
  entry and exit headings) and keeps the tracking reserve from every discovered obstacle.
- **Cost**: length + pivots (1° ≈ 1/150 m) + **6 m per missing meter** below 1.5 m of distance to the drawn edge
  of the prepared mission (zone edges, exclusions, recorded obstacles; `TrajectoryPlanner.borderClearance`) +
  known difficulties along the detour (heatmap, if provided). Near an edge, the **inner** side wins even if it
  is slightly longer; far from edges, the shortest wins.
- **Blades stopped** (first bypass after a contact): a detour closer than 50 cm to a drawn edge is only chosen
  when no other link exists (transit rule).
- Links that graze the obstacle while mowing (blades running inside the zone) mow around it.

Every chosen detour is logged (`RouteRepair.detours`: center, side, radius, length, edge clearance, blades,
inner side) in the `route_obstacle_bypass` event (`detours`); the tracking message states the side ("Obstacle
bypassed on the right, inner side · …").

### Non-mowing transit: direct link to the destination (since 0.37.0)

Previously, an obstacle on a link (access, passage between zones, return to base) was bypassed, then the robot
went back onto the planned link right behind it, including corners that had become useless. Now
(`ObstacleBypass.directTransits`), any sequence of non-cutting edges that crosses the reserve of a discovered
obstacle is removed **entirely**: the link joins the end of the previous cut (or the back-off point) directly to
the start of the next cut, via the tight detour or the mission navigation, i.e. by the shortest safe path. At the
end of the route, the last 5 centimetres are kept: base approach point and arrival heading unchanged. Links that
do not cross the obstacle remain those of the preparation.

`contactDuringATransitGoesStraightToTheDestination`: L-shaped transit, contact on the first leg, 10.0 m to the
destination instead of 13.7 m via the corner, without returning to the axis behind the obstacle. On the 7
reference zones, the total length of the repaired route never increases (Jardin d'Eden −0.5 m, Figuier −2.6 m, the
others identical); the "Bypass" column of the table now counts the whole replaced return.

An obstacle recorded during the mission on the remaining route triggers the same repair without back-off.
The move to a point (map → **Move to a point**) applies the same back-off after a contact, then recomputes the path
to the destination around the recorded obstacle (placed in front of the position confirmed stationary before the
back-off, since 0.59.0).

### Collision lock and back-off without a straight track (0.42.1, 0.49.1, 0.59.0)

- On contact, the LUBA 2 often publishes self-check 25 (collision lock) one report before the -1014 code. Tracking
  stops at once and waits up to 2 s for the contact code (`CollisionLatch.CONTACT_GRACE_MS`). Without a code, the
  lock is handled as a contact (0.49.1): back-off, estimated obstacle in front of the robot, bypass.
- The straight back-off is allowed under this lock (`CollisionLatch.reverseAllowed`), and the stop before it is
  confirmed despite the lock (0.59.0). The repaired route resumes once the self-check is back to 0 or 10 and stays
  there 3 s (30 s at most, `ProtectionPause`); otherwise the stop is kept with "free the robot, press STOP then
  Start".
- After a pivot there is no straight track behind the robot: the back-off is then a 30 cm straight line
  (`ObstacleBypass.BLIND_BACK_OFF_M`), its chassis sweep checked against borders, no-go zones and known obstacles.

## Who computes what in real mowing: Fold or gateway

Behaviour matches the simulation: the same tracking blocks (`PathFollower`, `DeadReckoning`, `ReturnDrift`) and
the same repair (`ObstacleBypass`). Only the device executing each step changes.

| Step | Autonomous gateway (Redmi, default setting) | Without autonomous gateway (Fold direct, or ESP32 relay) |
|---|---|---|
| Line following | Redmi (`autonomy/LegRunner.kt`, line-by-line port of `followPath`) | Fold (`RobotBle.followPath`) |
| Contact detection | Redmi: stop, blades OFF, contact pose and trace sent back (`LegOutcome.CONTACT`) | Fold |
| Back-off, photo, bypass computation, direct transit link | **Fold** (`RobotBle.avoidObstacle` → `ObstacleBypass.repair`) | Fold |
| Following the repaired route | Redmi (new tracking job sent by the Fold) | Fold |

The bypass stays on the Fold: it needs the navigation planner (surfaces, exclusions, passages), which the gateway
does not rebuild. Both 0.37 fixes therefore also apply in real mowing: the direct transit link is computed on the
Fold, and the icon display does not change for the real robot (positions at 1 Hz, 950 ms glide as before).

### Fold unreachable (Wi-Fi down)

- **No contact**: the Redmi carries on with mowing and transits on its own; the Fold shows "Gateway Wi-Fi lost for
  N s · the robot carries on alone" and, once back, retrieves progress, trace and outcome. Beyond **20 min without
  a pilot**, the Redmi stops the robot for safety (`GatewayAutonomy.ORPHAN_LIMIT_MS`).
- **Contact or obstacle added during the outage**: the Redmi stops the robot, blades OFF, and keeps the outcome.
  **No bypass without the Fold**: the robot stays stationary at the contact location (no automatic return to
  base). When Wi-Fi is back, the Fold receives the contact, backs the robot off, computes the bypass and restarts
  tracking.
- Recharge, docking and leaving the station also wait for the Fold.
- Redmi switched off or out of battery: the Bluetooth link drops and the robot stops by itself.
- Not verified: how long the robot tolerates waiting stationary in manual mode, and how its firmware reacts to a
  long wait.
- Ideas not implemented: bypass computed by the gateway (planner to embed on the Redmi), or return to base via
  the Mammotion return after N minutes of waiting without the Fold.

None of this path has been tried on the robot yet (autonomous gateway, back-off after contact and bypass: see
[autonomous gateway](AUTONOMOUS_GATEWAY.md#to-validate-on-the-robot)).

### Differences between simulation and real mowing

- Real contact: bumper error code (-1014) read from the history polled twice per second; in simulation, contact
  as soon as the footprint touches the object (checked every 50 ms).
- After the back-off, stationary wait for the obstacle photo (25 s at most); Fold ↔ Redmi exchanges,
  confirmation of blades and cutting height: absent from the simulation.
- Actual object shape unknown (estimated 0.2 m disc in both cases); ground, slope and wheel slip not simulated.

### Fixes from the afternoon of 2026-10-14 (0.39.1)

Three bumper contacts (14:16, 14:28, 14:35): the bypass was computed every time, but:

- **14:28**: the robot republished the same entry of its error history with another timestamp (137498, internal
  clock, then 1791980897), **entry counter unchanged (868)**. Taken for a second contact at the start of the
  back-off, it stopped the mission: neither back-off nor bypass. `RobotErrorMonitor` now relies on this counter
  (Fold and gateway): counter unchanged, no new error; increased by n, at most n entries.
- **14:35**: back-off stopped after 2 s, "BLE interrupted during back-off". Through the gateway, each write took
  ≈ 430 ms; the manual-mode keep-alive and the error read, re-sent every 500 ms, piled up beyond 4 queued
  commands. They are now **periodic** (not re-sent until the previous one has been written), as for go-to-point
  since Oct 13; same during the photo and the wait for the stop.
- **14:28:35 and 14:35**: the resume and then the restarted mowing headed back onto the touched object, which had
  been forgotten along with the interrupted mission (see below, presumed obstacle for 4 h).
- **14:36:57**: stop "Local obstacle inside the robot envelope" during a pivot, 95 cm from a recorded obstacle that
  the route was going around (guard at 93 cm, prepared route at 98 cm). The guard for an obstacle already
  bypassed by the route tolerates 15 cm on its margin, never on its physical radius (`FollowObstacleGuard`, Fold
  and gateway); an obstacle absent from the prepared route still triggers the bypass at its full margin.

The stop "Robot envelope outside the safe corridor" when leaving the 14:16 bypass has been recovered since 0.39.0
(back-off then resume).

## Limits and stops that remain

- 8 bypasses at most per mission (`ObstacleBypass.MAX_BYPASSES`). An object larger than the estimated disc is hit
  again further on: each contact adds a disc; a 5th contact within 2.5 m of four already discovered obstacles:
  stop (object too large, to be drawn as a no-go zone). Rule shared by real mowing and simulation
  (`ObstacleBypass.contactFailure`).
- Back-off impossible (no trace, chassis blocked behind), no link, route end inside the reserve, obstacle in a
  narrow passage (forced axis): stop, progress kept for the resume.
- Contact during docking, the cutting test (Blade test → While moving) or the bypass course of Service → Obstacle
  tests: stop as before. Since 0.47.0, a contact on the return route to recharge or on the resume link is bypassed as
  during mowing.
- The cut around the discovered obstacle is left unmowed (ring of ≈ 1.2 m radius); recording it as an obstacle
  lets the next mission go around it.
- Discovered obstacles are not recorded: recording the obstacle changes the plan fingerprint (resume refused).
  Each contact is logged on the map (Obstacles layer, with its photo) and becomes a **presumed obstacle** for
  **4 h** ("Recently hit obstacle", since 0.39.1), then permanently if it is hit again during another trip
  ([obstacles encountered and difficulties](DIFFICULTIES.md)). A resume after interruption or a new mowing run
  therefore avoids it: prepared with it, or bypassed by tracking (obstacle absent from the prepared route).
  Previously, on Oct 14 at 14:28 and 14:35, the robot drove straight back onto the object.
- Heading at contact: Teach mode assumption (degrees, counter-clockwise). Reverse movement and bumper contact
  during the back-off remain to be validated on the robot, blades OFF first, under supervision.

## Simulation (Service → Obstacle simulation)

Reached from Service → Garden and history → Obstacles → **Obstacle simulation** (user guide:
[obstacle simulation](features/obstacles.md#obstacle-simulation)).

A square, round or triangular object (0.1 to 3 m, adjustable orientation) is placed with a finger on the map; two
fingers move the view. The mowing route of the chosen zone is prepared as from the map (passes, links, return to
the approach point), **without knowing about the object**.

**Navigation (since 0.37.0)**: the simulated robot is driven by the real-mowing tracking module, not by a
simulation-specific kinematic model: `PathFollower` on the pose predicted by `DeadReckoning`, one range per blade
state with STOP on change, end of range confirmed by `RangeArrival`, commands at 5 Hz, positions published at
1 Hz (RTK noise 1 cm, heading ±1°), `ReturnDrift` safety stop (30 cm, drift tolerated on return) and progress
guard, at the forward speed set for the robot. Commands act on `SimulatedRobot`, the execution bench model (0.6 s
delay, inertia, friction, real gains unknown to tracking). Previously, a simplified model advanced edge by edge
along each edge's axis, without deviation correction, and pivoted in place at every vertex, including on borders
split into edges a few centimetres long: the robot drifted off the line and changed heading for no apparent
reason. Playback ×1 to ×40, start a few meters before the object; back-off at the real pilot's command
(≈ 10 cm/s). Contact is detected when the LUBA 2 footprint (690 × 513 mm), checked every 50 ms, touches the
object. Then: estimated obstacle in front of the bumper, acceptance rules, back-off and remaining route repaired
by `ObstacleBypass` — the same functions as real mowing. The map shows the prepared route (grey), the followed
and adapted route (orange), the trace, the object and contacts (red) and the estimated obstacles (circles). No
command is sent to the robot; the kinematics are simplified (tracking accuracy is the execution bench's job).

`ObstacleEncounterSimulationTest`: the three shapes at 0.3 and 0.8 m are hit and then bypassed until the end of
the route (1 contact at 0.3 m, 2 at 0.8 m: the estimated 0.2 m disc is smaller than the object), the trace never
crosses the object, the published deviation from the route stays below 20 cm; contact on a transit: direct link
to the destination; a 3 m square is hit again until the maintained stop; an object off the route is not hit;
Enfer zone of the reference map: 0.6 m triangle on the longest pass, 1 contact, 60 cm back-off, 8 links, end at
the base approach point.

## Automatic validation

`ObstacleBypassTest`: contact on a pass (back-off along the axis, bypass, later passes repaired, replay by the
execution bench), back-off stopped before a no-go zone and refused off the trace, obstacle added without back-off
(blades kept inside the zone), route end inside the reserve refused, and reference map on its 7 zones (contact in
the middle of the longest pass of the central third):

| Zone | Back-off | Links | Bypass (before → tight detour) | Tight detours | Cut left | Max deviation (bench, 30 %) |
|---|---:|---:|---:|---:|---:|---:|
| Jardin d'Eden | 0.70 m | 8 | 24.0 → 20.3 m | 7 | 13.2 m | 0.08 m |
| Aire de jeux | 0.70 m | 9 | 24.2 → 23.0 m | 7 | 17.5 m | 0.15 m |
| Piscine | 0.70 m | 1 | 3.9 → 3.4 m | 1 | 2.4 m | 0.10 m |
| Figuier | 0.70 m | 7 | 23.1 → 19.5 m | 6 | 10.1 m | 0.13 m |
| Enfer | 0.70 m | 4 | 10.0 → 8.6 m | 3 | 7.5 m | 0.14 m |
| Fosse | 0.70 m | 6 | 15.7 → 14.1 m | 4 | 11.4 m | 0.19 m |
| Potager | 0.90 m | 6 | 10.4 → 9.8 m | 3 | 11.0 m | 0.14 m |

Tight detour radius: 1.01 m from the center (0.2 m + 0.3 m obstacle). "Cut left" counts the planned passes
removed; detours with blades running additionally mow a ring around the obstacle.
`tightDetourTakesTheInnerSideNearAZoneBorder`: obstacle offset 15 cm towards the inside of a pass 2 m from an edge
(the outer side would be shorter), the detour goes on the inside, within 1.1 m of the center; the test fails if
the edge penalty is removed.

No bench mission interrupted or blocked; repaired route within the safe surface, outside the base zone, never
closer than the tracking reserve to the obstacle.
