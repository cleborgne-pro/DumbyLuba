# Mowing simulation — DumbyLuba 0.10.0

> [!NOTE]
> This page is a development history, from 0.10.0 onwards: the earlier sections describe states that were replaced
> later in the page. The current behavior is summarized just below and, for users, in the
> [simulation feature page](features/simulation.md).

## Current behavior (0.65)

- Entry: Map → **Mow** → zones → **Task settings** → **Route preview** → **Simulate**. The simulation reuses the
  route already prepared for real mowing (`MowingSimulation`, links and recharges checked during the preview,
  "Checking the links · n/total"); the button starts it without any new geometric computation.
- Scenario: mowing already done 0 %, battery at start 100 %, autonomy 60 min (100 % → 0 %), full recharge 30 min,
  task speed and return / resume thresholds of the task settings. The *Scenario* sliders of the dialog (0–100 %,
  0–100 %, 5–180 min, 5–120 min) are only displayed before a start, which the current screen flow does not reach
  (the dialog opens from the bar of a running simulation).
- Bottom bar **Simulation · phase** with "Mowed x %", battery, simulated minutes, play/pause, speed ×1 to ×300 (×20 by
  default) and "⋯" (dialog: phase, battery, simulated time, recharges, last 12 log lines, **Finish and prepare another
  scenario**, **Show on map**).
- Phases: *Leaving the base · slow forward*, *Start / resume*, *Mowing*, *Return to base*, *Alignment before
  recharge*, *Recharge return · reversing*, *Recharge*, *Mowing finished · at the base*, *Simulation blocked*.
- Battery: linear consumption (no ×3 factor since 2026-10-08), return at the low threshold or earlier when the energy
  to reach the base requires it, finish below the low threshold and early departure from the base under the same
  rule as the robot (`FinishPolicy`, 0.48.0), slow straight exit from the base at every departure (0.34.3).
- Base: the station sent by the robot (or observed while charging) with its docking axis; no manual placement,
  rotation, influence radius or manual access drawing any more (see
  [removal of manual base settings](#removal-of-manual-base-settings)). Links use the passages recorded by the
  robot and manual accesses saved by older versions.
- **Run this route** (sheet of the **Simulated route** bar): **Follow, blades stopped** or **Mow**, with the same
  route and checks as real mowing.

## Initial version (0.10.0)

In **Map → grass button → Mowing simulation** (obsolete entry), choose the zones then create an optimized route. The computation returns to the simulation settings. Place the base if the map does not provide its position, then start the scenario.

## Scenario and tracking

- Initial mowing progress: 0–100 %, positioned along the cutting distance of the route. The simulated robot leaves the base and goes to the resume point.
- Initial battery: 0–100 %. A battery below the resume threshold is recharged at the base before departure.
- Reference autonomy at 100 %: 5–180 minutes, estimated discharge time from 100 to 0 %; full recharge: 5–120 minutes.
- Playback ×1, ×5, ×20, ×60, ×120 or ×300; pause/resume from the map or the details.
- Theoretical travel speed 0.3 m/s. Return with a 20 % reserve **plus the energy needed to reach the station**. Resume at 90 % at the exact interruption point. Final return to base after 100 %.
- Map: completed route in blue; departure, return or resume route in orange; LUBA 2 silhouette to scale (0.69 × 0.513 m) oriented by heading, small station icon and metric circle if its radius is set. Log of phase changes, simulated time and number of recharges.

The session and its map stay in a ViewModel across tab changes and activity recreation. It is paused when the app goes to the background. Closing the process abandons the session; base markers and manual accesses remain saved per map geometry.

## Base and accesses

The Bluetooth message `nav.toapp_chgpileto` provides station coordinates: they are decoded without making the safety watchdog valid. Failing that, a recent position of the robot observed while recharging can provide the marker. These sources are used only on the Bluetooth map, not mixed with a cloud map.

**Map options → Base** lets you place/move the local marker, also visible outside simulation. A manual position takes priority. The marker is remembered for the exact map geometry; no robot station is modified.

A base outside a zone or isolated zones require a mapped passage. **Draw a base / inter-zone access** lets you add a local polyline: tap its waypoints then Confirm. The corridor assumes a free width of 0.8 m plus the margin on each side. Accesses can be cleared in Options. These are simulation assumptions, never paths sent to the robot.

The engine searches for paths in the union of zones and manual corridors, minus the margin and exclusions/obstacles. It refuses impossible links. Manual accesses do not remove exclusions.

Since 0.10.2, links use the **passages imported from the robot (type 2)**, supplemented only by manually drawn accesses. The automatic off-map estimation option of 0.10.1 has been removed. Passages are shown in green on the map and its previews, as open polylines; they are not obstacles and are never mowed.

The travel margin without cutting is 0.3065 m (LUBA 2 half-width + 5 cm), independent of the mowing margin. Local exclusions keep their full reserve disc. The simulated corridor around each passage leaves a 0.8 m width in the middle; this passage model is not a measurement of its physical width. No shortcut is allowed through terrain missing from the zones, passages or manual accesses.

The computation has no vertex cap. An A* search uses the reflex vertices of the free area and the visible segments tangent to the outlines: it compares the sides of exclusions and minimizes the real distance, without choosing corridors through the centers of a mesh. Links are geometrically filtered, computed on demand then memoized, without a matrix of all pairs. Links within zones and through passages compete with each other, even when start and end belong to the same zone. An autonomy unable to allow the resume and a return blocks the scenario instead of looping endlessly.

## Scope

The simulator is independent of RobotBle: no movement, blade command or mowing command. It executes the local optimized plan, not Mammotion's proprietary native mowing plan. The battery is linear, without slope, slip or inertia; pivots are simulated on the spot at 45°/s. This check of the plan does not replace a physical validation of the robot.

JVM tests: full cycle with recharge and resume, return reserve, exclusion bypass, disconnected zones, manual access and external base, exclusion preserved under an access, start at 75 %, low battery, 100 % scenario, deterministic acceleration, invalid base, insufficient autonomy and decoding of the Bluetooth marker without modifying the watchdog.

## Validation on the Fold

Version `0.10.0-simulation`, code 29. Debug build, 114 JVM tests without failure and lint without errors. Run performed in the interface on the fictitious map with short autonomy and ×60 acceleration: 100 % mowed, 44 simulated minutes, 4 recharges, resumes at the interrupted point and final arrival at the base with 32 % battery. Local screenshots in `artifacts/ui/0.10.0/` (not versioned). The framing leaves room for the panels to keep the base visible.

Station coordinates sent by a real robot and the accesses of a real garden still have to be checked on site. Reception of the station message is covered by a protocol test, not by a physical connection during this validation.

## Fix for detailed maps — 0.10.1

Removal of the refusal above 1,200 vertices and of the quadratic visibility graph. Full outlines are kept; navigation uses a triangular mesh with neighbors, A* search, spatial index and shortcut checks. Non-regression test on 6,000 points with a dense exclusion, return and full mission.

Local validation on the geometry of the Fold's real cache: 43 elements, 7 zones. With estimation of missing accesses and a test base at the first point of the route, 174 returns are checked and the 7-zone scenario reaches 100 %, 5 recharges, 360 simulated minutes and final return at 28 % battery. This JVM validation of the model does not confirm the physical passages or the real position of the station. The personal cache stays out of the repository.

Version `0.10.1-simulation`, code 30: 117 JVM tests without failure, build and lint without errors; APK installed on the Fold via `tools/deploy.sh`.


## Passage tracking — 0.10.2

Fix of the interpretation of type 2 (passage, previously misnamed obstacle). No cache migration: the polylines were already kept. Base–zone, inter-zone and return/resume links now use their network. Complete removal of the off-map estimated link engine.

Tests: imported U-shaped passage between two zones with an external base, refusal of the off-network shortcut, exclusions preserved, full route without mowing in the passages and a distinct transit margin for a mapped bottleneck.

Validation of 0.10.2 on the real cache (7 zones, 10 imported passages, 43 elements) with a test base at the first point of the route: 174 returns checked; full scenario without off-map estimation, 6 recharges, 401 simulated minutes, final arrival at 62 % battery. This result validates the model on the real outlines, not the robot's physical maneuvers. Build, 118 JVM tests and lint without errors; version `0.10.2-passages`, code 31, installed on the Fold.


## Alignment and display — 0.10.3

Links follow the exact axis of the recorded polylines. When the geometry allows it, an approach point is added one chassis length (0.69 m) before the entry, inside the zone. The robot pivots before each segment, without moving forward during the rotation. Returns from the middle of a passage remain possible in both directions. Rotations consume battery and their maximum duration is included in the return reserve. This model does not yet check the full chassis sweep during pivots in the bends of passages.

Consumption is multiplied by three, without changing the recharge speed. The slider shows the reference autonomy and its effective value. The top-view silhouette uses the LUBA 2 envelope already defined in the app, without an artificial minimum size.

The station's influence radius is provided neither by the map nor by the base position message. Since 0.10.4, it defaults to **1.5 m**, an estimated value, and the circle follows the map scale. Bases already saved without a radius adopt this value; a custom radius is kept. The simulation setting allows adjusting it between 0.1 and 5 m. This estimate is not a Mammotion specification.

Validation: version `0.10.3-alignement`, code 32; debug build, 120 JVM tests without failure and lint passed, then installation on the Fold via `tools/deploy.sh`. The new tests check the passage axis, the pivot without displacement and the tripled consumption.


## Passage links — 0.10.5

Fix of the refusal "No link along the passage axis" when the passage ends on the edge of a zone: the approach point stays in the zone minus the margin, but its link to the end is checked in the union zone + corridor. The zone margin therefore no longer removes the link.

A base offset from the axis, but located in the mapped corridor, joins this axis through a short link checked within the accessible area (at most 0.4 m, matching the free half-width of the simulated corridor). The rest of the transit follows the recorded axis; a base outside the accessible area is still refused. Two non-regression tests cover ends on zone boundaries and the offset base, for departure and return.


## Base orientation — 0.10.6

In **Map options → Base** or **Simulation → Route and base**, "Set entry axis" enables a rotation setting from 0 to 360°. The arrow shows the docking direction towards the station: 0° to the right and 90° to the top of the map frame. The icon and the arrow rotate together; the orientation is saved with the base and kept when its marker is moved.

The simulation reaches an approach point on this axis, at the base radius distance (1.5 m by default), pivots to align, then travels the last straight segment to the station. Departure travels the same segment in the opposite direction. The return reserve accounts for this detour. An axis whose segment crosses an inaccessible area is refused with a message suggesting rotating the base or adjusting the access. An old base without orientation keeps using automatic routing until the user sets its axis.

Validation: debug build, 124 JVM tests without failure and lint passed. The new tests cover four cardinal orientations and the refusal of an off-map axis. The link fix also allowed checking the 174 returns to the saved base on the Fold's cache and a full 7-zone mission with a reference autonomy of 180 min (60 min effective), without versioning the personal cache.


## Reverse docking and station to scale — 0.10.7

The return takes place in three phases: route to the approach point (adjustable between 1 and 1.5 m, independent of the influence radius), pivot on the spot to present the rear to the station, then **simulated** activation of the recharge return mode and straight back-off. The robot's heading stays opposite to the direction of travel during docking, and is kept during recharge. Departure is driving forward along the same axis. The log distinguishes alignment and recharge return mode; no physical command is sent from this simulation. Sensor guidance is represented by an ideal back-off, at a theoretical speed of 0.1 m/s; no real infrared signal is simulated.

If no orientation is set, the engine looks for an accessible approach axis from the mapped route; it refuses the mission if no segment of 1 to 1.5 m is accessible. The manual orientation takes priority. The return reserve includes the pivot, the activation and the slower back-off.

The [LUBA 2 manual, section 3.4.4](https://www.luba.rs/wp-content/uploads/2024/05/luba_2_awd_user_manual_v3.pdf) describes placing the robot about 1.5 m away, rear facing the station, before Auto-return then Start.

The icon is a top-view vector silhouette inspired by the [official Mammotion photo](https://fr.mammotion.com/products/charging-station-for-luba-2-awd-series): black plate with two grooved tracks, rear frame, gray cover, gold contacts, green light and mast housing. It uses a 0.49 × 0.52 m footprint, dimensions published by [Robot Mowers Surrey for the LUBA 2 AWD station](https://robotmowerssurrey.co.uk/products/luba-2-awd-5000) (published height: 0.29 m). These dimensions are a commercial reference, not a measurement of the user's station. The base marker corresponds to the center of the parked robot; the frame is placed behind its rear. No pixel minimum enlarges the silhouette. The station is drawn under the robot.

Validation of 0.10.7: debug build, 125 JVM tests without failure and lint passed. The tests check activation at the approach point, back-off opposite to the heading, constant heading and the distance independent of the radius. On the real geometry of the Fold and its saved base: 174 accessible returns, 7-zone mission completed with 9 recharges and 607 simulated minutes (reference autonomy 180 min, i.e. 60 min effective). No robot command nor personal cache versioned.


## Base editing — 0.10.8

Placement mode stays active after each tap: subsequent taps move the base and do not select zones. "Finish editing the base" explicitly exits this mode. Zone selection is also disabled in the simulation, preview and mowing panels during editing. Taps consumed by overlaid buttons are not treated as taps on the map.


## Base editing gestures — 0.10.9

Dragging the base moves its marker while keeping the initial finger offset. Dragging the orange handle rotates its axis directly on the map. Touch targets stay comfortable when zoomed; the metric frame stays fixed during editing to avoid position jumps. Coordinates account for the map's zoom and rotation. Changes are saved at the end of the gesture. A simple tap still places the marker; "Finish editing the base" re-enables zone selection. A drag elsewhere pans the map.


## Back-off zone — 0.10.10

The arrow in front of the station is replaced by a translucent black rectangle (22 % opacity), with the real width of the LUBA 2 (0.513 m) and the set approach length (1 to 1.5 m). It rotates with the base and ends exactly at the approach point used by the engine. The robot reaches the center of the outer end, pivots to present its rear to the station, then activates the simulated recharge return and backs off. The existing docking tests check this position and the heading at activation. The rotation handle remains accessible in editing mode, without an orange line in front of the base; the orange docking segment is removed so the translucent zone stays visible.

### 0.10.11 · Robot base and docking corridor

The real base is now attached to the map cache (BLE and cloud), without reusing the manual placement. The NAV position is collected; the axis is acquired from the heading of the robot observed while recharging, inverted by 180° for the back-off direction. The NAV field `toward` is not interpreted until its unit is verified. If there is no axis, read the map again with the robot recharging; starting waits for this data. Placement and rotation gestures remain available in the demo.

The 1 to 1.5 m docking corridor extends the accessible areas for a station located outside the mowing outline. It must join the existing network; no shortcut to a distant zone is added, and exclusions remain blocking. The influence circle is white at 90 % opacity, with a 1.5 px stroke, regardless of zoom.

### 0.10.12 · Keeping the received base

Reading the map keeps the station already received over the BLE connection or in the cloud robot's cache, even if the firmware does not send it again during the transfer. Old saved markers whose origin is a reception from the robot are reused for display; manual placements do not become real positions. A position without an axis stays visible.

### 0.10.13 · Cloud session renewal

A 401 response triggers a signed OAuth renewal with the `refresh_token` kept in encrypted storage, then a single new attempt. Token rotation preserves the account information; no password is kept. A refused renewal session prompts reconnecting the account, while a network or server failure keeps the session.

The read-only barrier now also allows the two exact START/KEEP telemetry configurations already used during the cloud map read, in order to receive the position and heading of the robot while recharging. Motion and cutting commands remain refused.

### 0.10.14 · Base orientation convention

The transmitted compass heading (0° north, clockwise) is converted into a Cartesian map angle (0° right, counter-clockwise) by `90° − heading`. The back-off direction then adds 180°. The raw heading remains available in telemetry; the real position arrow uses the same conversion as the base. Automatic orientations from the previous cache are migrated, without changing the manual demo orientations. The cache now includes a convention marker to avoid a double conversion.

### No-go bypass without triangular detours

Mesh portals keep both of their ends so the route can be pulled tight in the accessible corridor. The autonomy reserve counts an initial U-turn, then the real heading changes, with a reserve for simulation ticks, rather than a U-turn at each rounding vertex. The non-regression test checks a bypass shorter than 11.8 m in both directions, the margins and the turns on the reserve outline.

### Shortest path and return variation (0.10.15)

The search on outlines now replaces the mesh and funnel described in the history above. The minimal path respects margins, exclusions, passage axes and docking. The tests compare its distances with an exhaustive visibility graph, for asymmetric and multiple exclusions, in both directions. A recorded 6 m link is preferred to the detour in a U-shaped zone.

Returns and resumes then apply an alternating lateral offset of 30 cm, then 60 cm, 90 cm, etc., when the free area allows it. Ends, passages and the docking axis stay fixed. Each offset segment is checked; the extra distance is limited to at most 1 m and 5 % of the route in the zone. If the requested side is blocked, the other side is tried; if both fail, the minimal path is kept. The autonomy reserve is computed on the route as actually offset.


### Pivots at the ends of offset returns

Validation of return and resume variations now includes the initial and final heading. If the offset requires a pivot whose chassis touches an exclusion or leaves the free physical area, the route without offset is kept and then checked before execution. The non-regression test places a small obstacle in the sweep of the rear corner during the initial pivot: the offset movement alone is free, but its link is refused and the initial route remains accessible.

## Removal of manual base settings

Map options and the simulation no longer offer placement, rotation or adjustment of the base influence radius. Editing gestures and the influence circle are removed. The station received from the robot is still used for simulation returns. This interface replaces the settings described in the historical versions above.

The "Draw a base / inter-zone access" button and the associated texts are removed from the map options and the simulation. Links are computed automatically from the recorded passages.

## Shared three-step preparation

*Historical (native preparation and button names replaced since; see the [preparation page](features/mowing-preparation.md)).* The grass button opens **Prepare mowing** directly: zone selection, settings (simple/checkerboard, height 25–70 mm, spacing 5–35 cm, speed 0.2–0.6 m/s), then route map and estimates. The last step offers **Simulate mowing** or **Launch mowing**. The chosen settings are passed to the native preparation; the firmware keeps computing its own route and the supervision confirmation before departure.

The full duration, the number of recharges and the final battery are computed with the simulation engine, at the chosen speed. Without a usable station or if the return is blocked, only the travel time excluding recharge is shown, with the reason why there is no full estimate. The computation is capped at 48 simulated hours and 15 seconds of processing; partial results are not presented as a completed mission.

Native mode uses `channelMode=0` for simple, `channelMode=1` and `towardIncludedAngle=90` for checkerboard; `channelWidth` is in centimeters and `speed` in m/s, consistent with the PyMammotion / Mammotion-HA parameters: https://github.com/mikey0000/Mammotion-HA/discussions/321.

The preview map supports pinch-to-zoom, panning and two-finger rotation. The +/− and recenter buttons also adjust the view. Four cards with icons show the total time (returns and recharges included), the number of recharges, the area of the selected zones excluding exclusions and the mowing distance. Zone overlaps are not counted twice. A missing estimate is shown as "—".

Clicking "View route" shows the map immediately, with an animated indicator overlaid. The drawing is published progressively; the same map stays displayed during the estimates. Views no longer reset the zoom at each route improvement. Identical geometric checks are memoized in the planner and in the physical envelope (caches bounded to 4096 entries). Return searches also memoize visibility towards their fixed destinations (64 destinations maximum). Margin and collision criteria are unchanged.

The indicators are displayed before the map so that they are visible when the preview opens. If the full estimate does not complete, a simplified "≈" estimate uses the distance, speed, initial battery and autonomy/recharge durations: return and pivot times are excluded. The area remains that of the selected zones excluding exclusions. Ellipses indicate that the computation is in progress.

The indicators are now compact (icon and label on one line, value below), and the map grows from 280 to 360 dp. Loading stays within the indicators; no overlay hides the map or gets in the way of gestures. The Simulate/Mow and Previous/Close actions are side by side.

The preview no longer replays the mission second by second for its estimates. It immediately uses the analytical model of distance/speed, consumption and recharge, flagged by "≈" and a text stating that returns and pivots are excluded. The detailed engine and its physical checks are built only when the simulation starts. The full estimates described above are historical.

The mowing settings separately offer 1 to 4 outer border passes and 1 to 4 passes on no-go outlines (0 to 4 today: **Perimeter loops**, **Loops around no-go zones**). These values drive the optimized route, its estimates and the simulation; the native preparation keeps its current border setting.

## Taught outlines as paths of the robot reference point

On the real map, outer and no-go outlines are interpreted as the traces of the robot reference point when they were created. The physical model only adds the areas swept by the chassis along these segments; the reference point stays inside the zone and outside no-go outlines. Local obstacles keep their independent reserves.

*Replaced in 0.40.0: recorded outlines are now the robot's outer side, see the [preparation page](features/mowing-preparation.md#robot-footprint).* The first border runs 8 cm from the outline, on the allowed side, to reserve the overhang during pivots; the 40 cm cut thus reaches the outline on straight portions. Outer corners are rounded inwards. The following passes are offset by the chosen spacing. Loop entries/exits are chosen at points that allow pivoting. Translations, turns, links and exclusions are checked by the chassis envelope; an impossible border is refused. The demo keeps its old physical boundary model. No native border setting is modified.

The simulation map always shows the LUBA silhouette, even if a real position exists. Playback speed (×1 to ×300) is set directly on the map. During simulated recharge, the battery reaches the configured resume threshold without waiting for the charging animation; the theoretical recharge time is still added to the simulated time. No real recharge command is sent.

A two-handle slider sets the low and high battery thresholds (5–100 % at the time; today 10–100 %, return at most 80 %, with a minimum gap of 5 points). The estimates use these two limits. The simulation additionally reserves the energy for the return at the low threshold and resumes at the high threshold; its instant recharge reaches that configured threshold. The firmware's native limits are not modified.

The low threshold is the battery level at the moment cutting stops and the robot leaves for the base; it is no longer increased by the cost of the return. The high threshold triggers the departure from the base to resume at the stop point. An independent check may request an early return if the remaining battery is no longer enough to reach the base; this reason appears in the log.

Entering a border no longer requires a full 360° pivot on the spot. When no vertex offers this space, the loop starts in the middle of its longest segment to allow a tangent link. The border route and its turns remain checked; the links actually used are checked by the engine before the simulation.


## Fewer detours and coverage by side

Passes are grouped into continuous cells: when they split around a no-go zone, one cell is finished before moving to the next. The choice of the first pass keeps this grouping.

Recharge departures and returns use prepared shortcuts to points of the base access route, without needlessly following the ends of the zigzags. Each shortcut is checked over the whole length of the interrupted segment, with the reserve needed for pivots; passages are still followed along their axis and the station approach is still respected. Simulation playback does not recompute any geometry.

All mapped zones remain available for transit, even when they are not selected for mowing. Local links account for headings and maneuvering space. The preparation logs its result and the zones used in `simulation_preparation`. On the current reference map, the Potager still has no usable access: passages reduced to a single point do not allow a safe path to be rebuilt. A complete map or a verified manual access is still needed for this zone.


### Recharge accesses on Piscine and preparation progress

Station shortcuts now compare the accesses prepared for the whole route, supplemented by direct links spread across the zone. Their validity is reserved on portions of at most 50 cm, without changing the playback segments or the simulated consumption. The Piscine test checks departure and return distances at 10, 25, 50 and 75 % of mowing, the headings and the chassis; full playback still performs zero geometric searches.

The estimate indicators stop loading after the coverage computation. A separate progress indicator then shows the links, recharges and optimization of accesses to the base. The Simulate and Mow buttons become available once this preparation is validated. A superseded preparation is interrupted at the progress steps.


### Returns close to borders

If the direct shortcut requires an unavailable full pivot, the preparation checks a link along the pass heading within 50 cm, before taking the station path again. The return no longer follows several zigzags to reach a distant link. Departures use the same principle with a link before the resume portion. The 99 progress points of Piscine are checked with the base and heading from the latest diagnostics.

`simulation_transit` events now record the departure, resume and return polylines, their distance, the phase, the cycle and the base, in order to reproduce an observed detour.


### Quick and full checks

`tools/check.sh quick` runs the build and the usual tests, excluding the three reference map suites during iterations. `tools/check.sh planner` checks all configurations and zones of the reference map. `tools/check.sh full` runs the build, all tests and lint. Normal deployment through `tools/deploy.sh` keeps the full validation. The tests use two processes; identical impossible links are memoized during preparation.

## Provisional consumption — 2026-10-08

The ×3 consumption factor has been removed from the engine and from the simplified estimate. An autonomy of 60 minutes now means 60 minutes to consume 100 battery points (about 1.67 points per minute). The return/resume thresholds still apply: from 100 to 20 %, the budget is 48 minutes, before accounting for return routes. Pivots, transits and docking also consume time and battery in the detailed engine.

The model remains constant and configurable, with no unmeasured coefficients for slope, grass or stopped blades. A timed comparison with real telemetry will allow calibrating the autonomy and distinguishing mowing from transit. Over a period without recharge, the extrapolated autonomy is `100 × duration in minutes / battery drop in points`; use a long enough drop to limit the percentage rounding error, then compare similar runs. The lap uses the same BLE pilot and the same telemetry, blade stop, battery and recharge guards as the other routes. The tests check links, the return to the approach point and outline sampling; they do not replace a physical test of the robot.


## Leaving the base — 0.34.3

Report of 2026-10-13 (22:37, Piscine from the saved base (-0.46; 1.09), heading 153°): the simulation
did not reproduce the robot leaving the station. The base → link point segment (1.5 m) was traveled like
an ordinary link, at mowing speed (0.3 m/s, 5 s), whereas the real pilot (`DockExit`, run by the
Fold or the gateway) leaves driving straight forward at about 10 cm/s, without pivoting, and departs as soon as the
return threshold + 10 points is reached instead of waiting for the resume threshold.

- New phase **Leaving base · slow forward** (`SimPhase.LEAVING_DOCK`) for every departure from the station:
  first departure and resume after recharge. The simulated robot only follows the base → link point edge, at
  `dockingSpeedMps` (0.1 m/s), keeping the axis heading. At the link point, the log notes "Link point
  reached" and the link continues at normal speed (*Departure / resume* phase), or mowing starts directly.
- The autonomy check before a resume counts the duration of this slow exit.
- Initial departure: recharge only below `min(resume, return + 10)`, like `RouteRecharge.departureAllowed`.
- The automatic pause in the background is written to the simulation log, so it is no longer mistaken for
  a blockage. Playback resumes with the play button.
- Diagnostics: `simulation_transit` is also written for leaving the base, the end and any blockage, with the last
  log line (cause), the battery, the simulated time and the position. The log of Oct 13 only contained the
  departure: the blockage state could not be read back.

Tests: `SimulationDockExitTest` (slow exit along the axis without pivot, duration, resume after recharge, battery
departure rule, pause in the background, complete Piscine mission from the Fold's base with a slow exit at every
departure).
