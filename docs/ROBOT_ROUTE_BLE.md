# Simulated route applied to the Luba over BLE — 0.11.0

> [!NOTE]
> This page is a development history from 0.11.0. Several first-version rules were replaced later: the route now
> starts from the preview (**Start**) or from the **Simulated route** bar (**Follow, blades stopped** / **Mow**); the
> robot may start from the base (slow straight exit); the height follows the task settings (25 to 70 mm, height
> passes); the battery reserve is 5 % off the station, with recharges at the return / resume thresholds; a bumper
> contact triggers a back-off and a bypass; mowing continues in the background with the link service and can be
> resumed after a stop ([resume](MOWING_RESUME.md)). Current behavior: [real mowing](features/real-mowing.md).

## First version (0.11.0)

From the real map of the connected robot, prepare the preview and then run its simulation.
The panel below the map offers **Follow, blades stopped** and **Mow this route** (today **Mow**).
Both buttons suspend the simulation and use the positions and heading received from the robot.
The robot must be ready (state 11), off the base, with blades OFF and a battery above 30 %.
The height is fixed at 60 mm in this first version and must be confirmed before starting.

DumbyLuba takes all the simulator's edges in their order: link from the real position,
stripes, links, borders, then the route to the base approach point. It commands speed
and rotation at 5 Hz through `movement`, and the discs through `manualMow`.
This controller sends no native mission preparation, start or return command.

The orange line shows the planned route, the blue one the trace actually observed.
Links require an OFF state received after the BLE write of their OFF command.
Manual mowing mode must be observed before requesting disc rotation.
A cut without ON confirmation cannot be validated as finished.

A deviation of more than 30 cm, a robot position or state older than 2 s, a robot error,
a protection, no progress, a corridor incompatible with the chassis,
BLE saturation or a battery ≤30 % interrupts the route and requests STOP and blades OFF.
Going to the background and disconnection also interrupt the mission, without automatic resume (obsolete: see the
note at the top).
Bumper health does not represent a contact: this version requires human supervision.

## Physical validation to perform

1. Robot ready and area clear: use **Follow, blades stopped**, check the start, stripes, turns,
   links, displayed trace and stop at the approach point.
2. Press STOP and check the physical stop; reconnect for a new start.
3. Check the stop when the app goes to the background.
4. After validating tracking without cutting, use **Mow this route** and check the height,
   rotation during stripes and shutdown before each link.

Build, JVM tests and lint are not a validation of physical movement.
Recharge during a route: at the low threshold, return with blades OFF to the approach point, alignment and
back-off until charging contact. At the configured high threshold, the robot leaves the base driving forward,
in a straight line and slowly (≈ 10 cm/s, no pivot on the base, heading ±10° and corridor ±10 cm), up to the
link point in front of the base; release of the charging contact confirmed. It then resumes, at route
speed, via the shortest link recomputed from its real pose to the interrupted point
(link prepared before the return as a fallback). Same exit for "Leave the station" and for a
mowing start from the base.

Resumes of station routes (0.47.0): the return and the resume link go through `followStation`, which
substitutes the station route for `routeEdges` during tracking and reuses `avoidObstacle`, `recoverDrift`,
`pauseForProtection` and `recoverLostLeg`; the mowing route, its origins and its drawing are restored afterwards.
Tracking lost by the gateway (`RemoteLegs.LostLeg`, state `STATE_NONE` or another tracking on reconnection) becomes
`RouteLegLost` (last reported edge) and resumes like a route departure without a skipped cut
(`MAX_LOST_LEG_RECOVERIES` = 4). Recharge and the departure check go through `ChargeWatch` (incident tolerated for
`GRACE_MS` = 2 min); the gateway link is held (`holdUntil`) during charging and keepalive writes
follow `KeepaliveLoss` during a cutoff.

## Continuous tracking (2026-10-10)

Curved borders are split into edges of a few centimeters (Piscine: 941 edges, 537 of which
are shorter than 15 cm). The former tracking sent STOP, rewrote the blade command and waited for its
confirmation at every edge: the robot moved in jerks, pivoted slowly (minimum pivot of
5 units) and stopped with the error "Point reached without blade confirmation" on an edge
shorter than the arrival radius.

`PathFollower` now follows each range of edges with the same blade state without stopping: look-ahead
target 40 cm along the route, heading correction while moving forward, pivot on the spot only
at corners sharper than 30° (at the vertex, like the simulator), slowdown 60 cm before the corner
or the end of the range. STOP and blade confirmation only happen at an ON/OFF change. Maximum
rotation follows the set speed (45 + %, i.e. 75 units at 30 %), with a minimum pivot of 15 units.
Checks are unchanged: deviation ≤ 30 cm (current or previous edge), chassis envelope
projected up to the next stop, obstacles, robot state, battery, no progress (15 s).
A long range has a budget of 120 s + 20 s per meter.

Kinematic model on the Piscine route (200 ms delay, assumed units): 252 pivots for
248 marked corners, maximum deviation 15 cm, average speed ≈ 89 % of the commanded speed.
This model does not replace physical validation: check **Follow, blades stopped** first.

## Real survey of Oct 10 and fixes

Fold log (navigation test, firmware 1.30.29.26):
- positions and headings published at **1 Hz** despite the 200 ms request;
- rotation ≈ **0.12 °/s per unit** (87 units ≈ 9 °/s; below ≈ 25 units the pivot stalls);
  forward ≈ **0.95 mm/s per unit** (120 units ≈ 0.11 m/s);
- a 125° pivot took 33 s: the proportional pivot slowed down on a heading one second
  old and ended at 15 units (≈ 2 °/s). The 60 s of the test were used up this way.

Fixes:
- command bounds raised to 450 for rotation (PyMammotion joystick value, ≈ 50 °/s) and
  500 for forward motion; joystick unchanged (300 / 150);
- route speed: 100 % = 500 units (≈ 0.48 m/s), 30 % = 150; maximum rotation tied
  to the setting, from 210 to 450 units;
- `DeadReckoning` predicts the pose between two positions from the commands sent, with an
  online-calibrated, bounded gain; the command uses the predicted pose, safety checks
  the measured pose; minimum pivot 35 units;
- the navigation test on the map and go-to-point use the same `PathFollower`
  as mowing (`TrajectoryFollower` removed);
- folding / unfolding the Fold or rotating it: no more "App not in foreground" stop or
  screen-exit STOP, since the activity is only recreated.

Kinematic model (positions at 1 Hz, 200 ms delay, real gains ±40 %) on the Piscine route:
maximum deviation 16 cm at 30 %, 24 cm at 100 %; average speed 0.13 m/s at 30 % and 0.38 m/s at 100 %
with the measured gain. To be confirmed on the robot, blades OFF first.

## Bumper, outer lap offset and map crash (Oct 10, afternoon)

- **Stop on bumper contact.** The LUBA 2 only publishes the contact (-1014, error history) in
  manual mode (state 20), as validated by the obstacle test of Oct 8. Real mowing therefore keeps
  manual mode during blades-OFF transits too (`manualMow(1,0)` maintained every 500 ms;
  except when leaving the station), the map test and go-to-point enable it at start
  (refused if state 20 with blades OFF is not confirmed within 5 s). The error history is polled
  every 500 ms. A -1014/1014 stops immediately: mowing → STOP, blades OFF, obstacle
  suggestion; navigation → STOP, incident recorded, end of the test with the reason displayed.
  Manual mode is exited (blades OFF) on every STOP.
- **Adjustable outer lap offset.** Advanced settings → "Outer lap offset":
  5 cm to 1 m from the recorded boundary (25 cm by default, remembered). *Replaced in 0.40.0 by **Outer margin**
  (Task settings → *Mowing*), measured from the robot's side, 5 cm by default.* The inner area follows this
  offset. The planner keeps 8 cm by default for the reference tests.
- **Crash when opening the map.** ART `VerifyError` on `MapScreen` (Compose function of
  more than 1,000 lines) after adding a saved state. Reverted to the original code; the Fold
  folding protection for go-to-point is done in `MainActivity`. `tools/deploy.sh` now fails
  if `dex2oat` rejects an app class (check verified on the old faulty version).

## Blinking marker and go-to-point (Oct 10)

- **Blinking.** The map's display clock only advances every second and positions
  arrive at 1 Hz: a position more recent than the clock was rejected as "future". The
  marker toggled every second between the BLE position and the last FIX, and the framing (which
  includes the marker) jumped, also shifting the tapped destination. A 5 s tolerance was added.
- **Go-to-point.** The path was limited to the robot's zone, with a 0.95 m margin
  applied to the recorded outline as if it were physical: on the real map (26 exclusions),
  no path was found, even in "Aire de jeux". It now uses the same
  configuration as mowing transits: all recorded zones and passages, outline
  "recorded trace", chassis margin, local obstacles. Tracking checks verify the physical
  footprint of the LUBA 2 (like mowing) instead of the fixed margin of the map test. Test: from
  the real position of Oct 10, every zone of the reference map is reached and every vertex
  passes the tracking checks.

## Map crash: real cause and safeguards (Oct 10)

`MapScreen` used 266 Dalvik registers. Above 256, D8 (fold variant, non-debuggable)
reused the `Composer` register as an integer (offset 0x26C): ART rejected the class
(`VerifyError`) when opening the map. The previous revert was still above the threshold,
and the deployment's `dex2oat` check cleared the log after installation, hence too late.

- The map content (canvas, header, bars, panels) is compiled into a non-inline composable
  lambda (`IsolatedBox`): `MapScreen` goes from 266 to 185 registers.
- `checkFoldDexRegisters` (Gradle, after `assembleFold`, before `installFold`) refuses any APK in which
  an app method exceeds 224 registers (`tools/check-dex-registers.py`, dexdump).
- `tools/deploy.sh` clears the log before installation, then opens the map (`--ez open_map true`)
  and fails if the app crashes.
- Oct 20 (1.13.0): the cutting heights by model brought `MapScreen` to 225 registers and the check refused the
  Fold build. The mowing task settings are now saved by their own composable (`SaveMowingTask`, one
  `MowingTaskSettings` key instead of 12 captured values): 221 registers. Moving or inlining a few locals did not
  help (227): D8 allocation is not linear, measure with `./gradlew :app:assembleFold` (about 20 s) after each try.
- Oct 21: `MapScreen` brought well under the limit, without changing behaviour or display. Every
  `var x by remember { mutableStateOf() }` kept one register live for the whole method and every lambda capturing it
  copied it into a contiguous range: the states are grouped in holders (`MapScreenState.kt`: `MapSheets`,
  `MowingTaskForm`, `RoutePreparationState`, `PointMoveState`/`PointTarget`, `MapLayersState`, `MapActionState`,
  `MapViewState`, `MapFrameState`, `ResumeSkipState`, `PreviewStartState`, `ZoneStartsState`; same subset saved across
  a configuration change). The 45 parameters, all live until the restart lambda at the end of the method, are passed
  to the body as one object: `MapScreen` builds `MapScreenArgs` and composes `MapScreenContent`. The canvas, the
  panels, the sheets and the task pages are named composables (`MapRouteCanvas.kt`, `MapPanels.kt`,
  `MapSheetsHost.kt`, `MowingTaskPages.kt`), the signal survey values come from `rememberMapSignals`
  (`MapSignals.kt`). Measured on the Fold APK: 222 → 183 (sheets and task settings) → 165 (other states) → 138
  (parameter object) → 155 after the extractions (each named composable call needs its arguments in contiguous
  registers) → 136 once rebased on the real coverage layer (D8 allocation varies from one build to the next). **Method to watch: `MapScreenKt;MapScreenContent`, threshold 224** (`checkFoldDexRegisters`); the
  `MapScreen` wrapper stays under 120. To locate the widest ranges of a method, `dexdump -d` on the APK lists the
  `invoke-*/range` and their registers.

## Map screen callbacks: latest composition (Oct 27)

"Map changed: recomputation required" appeared on every "Route preview" that followed a change of a task setting.
The computation is a local function of `MapScreenContent` (`calculateMowingPreview`) reading values of the
composition (settings, map, obstacles, base…), and it was handed to the task pages as a function reference
(`onCompute = ::calculateMowingPreview`). Two references to one local function are **equal** whatever the values
they capture (`FunctionReference.equals` compares the name, the signature and the owner): Compose, which compares
function parameters and the keys of memoized lambdas with `equals`, kept the reference of an older composition in the
lambda of the button, so the computation started with the old settings and the check against the current inputs
refused it. The local functions handed to children (`calculateMowingPreview`, `startMowingSimulation`,
`startPreparedRoute`, `baseAction`, `resumeMission`, `backOffBeforeResume`, `openResumeSkip`, and the `compute`,
`prepare`, `restart`, `finish` and `select` of the Service test pages) now go through `latestAction` /
`latestAction1` (`ui/components/LatestAction.kt`): one stable callback that runs the function of the latest
composition (holder with referential equality, which `rememberUpdatedState` is not). The check of a map, obstacle or
setting change *during* the computation is unchanged. Tests: `MapPreviewSettingsTest` (setting changed then preview,
obstacle learned during the computation, equality of the references).

## Go-to-point: wrong heading at start (Oct 10, 11:18–11:21)

Fold log: after a pivot, the LUBA 2 published a heading of 139–142° while driving at ≈ 49°
(RTK positions), then corrected its heading on its own (77° jump) after a few tens of centimeters.
Same difference (≈ 80°) at 11:20. Consequences: start in the wrong direction (58 cm deviation),
then "Robot footprint outside the safe area" tested with this wrong heading near a boundary.

- `DeadReckoning` aligns the published heading with the direction actually traveled (straight
  displacement ≥ 8 cm between two positions) and detects the robot's own corrections (unexplained difference
  > 45° between two published headings): the offset is then dropped and reconfirmed while driving.
- As long as the heading is not confirmed by movement: forward motion limited to 120 units
  (≈ 0.11 m/s), check of the robot's center instead of the oriented footprint.
- Go-to-point: a deviation > 30 cm or an unsafe envelope triggers STOP then a
  new path from the real position (at most 3 recoveries) instead of aborting.
- Real mowing: same heading correction and same slow forward motion before confirmation.

Model of the two real routes (positions at 1 Hz, measured gains, published heading wrong by +90° / −80°
up to 0.6 m): maximum deviation 0.07–0.17 m, no false alert; without correction 0.56 m
(0.58 m measured in the field).

## Start refused "Chassis collision during the turn at segment 1" (Oct 10, 11:24)

Signal lap started with the robot stopped at (−12.97; 7.21), 0.87 m from an obstacle created by a
bumper contact (radius 0.2 m + margin 0.3 m). The route is prepared from the base; only the link
from the real pose is computed at start. The start pivot (0.43 m sweep) entered
the margin (0.5 + 0.43 > 0.87 m): refused, whatever the heading. Physically, the pivot would have fit.

- `RobotRoute.departure`: if the normal link fails, straight-line clearance (0.3 to 2 m)
  to the closest point where a full pivot is allowed with the margins. The segment is
  checked with obstacles at their physical radius (without margin); a full physical pivot is required at
  the start; the rest of the route keeps its margins. On this segment, tracking applies the same
  physical geometry (`SimEdge.escape`). Return to base: same departure.
  Chassis already overlapping an edge at the start (1.1.4, `TrajectoryEnvelope.leavesStraight`): the straight escape
  ahead is accepted if the chassis swept up to the first pose fully inside covers no ground outside the free area
  other than what the start footprint already covers (20 cm² tolerance), the rest of the translation being free;
  tracking (Fold and gateway) applies the same rule on escape edges. Oct 19, Fosse: robot stopped 15 cm from the
  border, "Resume" refused twice ("Link unreachable: Fosse → Fosse"); test `StuckNearBorderResumeTest`.
  Automatic resume after a trajectory exit or a protection (1.1.5, `DriftRecovery.plan`): when no back-off along the
  trail brings the chassis back inside, the current pose is tried last, and only a link starting with this escape is
  accepted. Oct 19 at 14:50, Fosse: protection 25 during a 150° pivot, trail ahead of the robot, mowing stopped with
  "Back-off impossible"; test `ProtectionAfterPivotResumeTest`.
- Obstacles suggested on contact: placed with the fused heading when it is confirmed by
  movement (the published heading could be wrong by 80–90°).
- Test `RouteDepartureEscapeTest`: reference map + real obstacles from the Fold; refusal reproduced
  without clearance, successful start for every heading with clearance.

## Base safety zone (Oct 10)

The docking corridor was added to the navigable area (base → approach axis widened by
0.7 m): routes could run along, go around or cross the station and reach the approach
point from the sides or from behind.

- Safety disc around the station, radius = approach distance − pivot sweep − 2 cm
  (1.05 m for 1.5 m): forbidden to every route (links, returns, recharges, internal shortcuts).
- The robot only leaves and returns to the base straight through the front corridor: base →
  approach point driving forward, return to the approach point in front of the station then back-off
  along the axis. This segment is validated on the maneuvering area and by the physical envelope.
- Mowing passes also keep away from this disc (known station orientation); it is removed from
  the coverage target.
- Test `BaseSafetyZoneTest`: returns from every zone of the reference map arriving at the
  approach point without entering the zone; production plan and prepared mission outside the zone.
  `ReferenceMapPlannerTest` validated on every zone.

## Signal survey that went through the base (Oct 10, 12:06)

Fold log: a signal grid started from (−12.38; 0.12) reached the point located
1.5 m behind the station, went through (−1.151; 1.407) — exactly one chassis length
behind the base — then through the base, and came out the front. The docking axis was among the
"accesses" of the passage graph: extended like a passage, it created an entry behind the
station and unchecked passage edges through the base. Not reproduced outside the Fold
with the retrieved maps and obstacles; fix and safeguard independent of the computation:

- the docking axis is removed from the passage graph (exit and return only via viaDock);
- `baseZoneViolation`: final geometric check of every route (mission preparation, real
  departure, return to base); a segment outside the front corridor inside the safety zone causes the
  route to be refused with its coordinates;
- `robot_route_base_zone` log at each start: station, approach, radius, minimum distance.
