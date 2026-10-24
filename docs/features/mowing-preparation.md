# Preparing a mowing run: local planning

The route is computed **on the phone**, not by the robot. A single coverage engine produces passes (zigzag,
checkerboard…), border laps and links, all checked against the LUBA 2 AWD footprint. The same route is used for
simulation, real mowing and signal surveys.

| | |
|---|---|
| **Access** | Map → **Mow** (reopens the already computed preview if there is one); Home → **Start mowing** |
| **Status** | Implemented; validated in JVM tests on a real reference map (7 zones) and on the test phone |
| **Code** | `planning/` (`RoutePlanning.kt`, `AutomaticPlanner.kt`, `CoverageEngine.kt`), `map/CoveragePlanner.kt`, `MixedSweep.kt`, `MultiZonePlanner.kt`, `TrajectoryEnvelope.kt`, `MowingPreview.kt`, `StartPointGroup.kt`, `MowingTaskSettings.kt`, `OuterOffsetSetting.kt`, `CutPasses.kt`, `planning/ZoneStart.kt`, `map/MapScreen.kt` (task pages) |
| **References** | [Architecture](../path-planning/ARCHITECTURE.md) · [Algorithms](../path-planning/ALGORITHMS.md) · [Parameters](../path-planning/PARAMETERS.md) · [Single engine](../path-planning/ENGINE.md#one-engine-one-prepared-route) · [Continuous mowing](../path-planning/ENGINE.md#continuous-mowing) · [Nearest start](../path-planning/ENGINE.md#nearest-start) |

<p align="center">
  <img src="../images/zones.png" width="170" alt="Mowing zones">
  <img src="../images/task-settings.png" width="170" alt="Task settings">
  <img src="../images/route-preview.png" width="170" alt="Route preview">
  <img src="../images/animated-trace.png" width="170" alt="Progressive trace">
</p>

## Three-step flow

### 1. Mowing zones

The **Mowing zones** panel opens over the map (the map stays visible): "Tap the zones in mowing order: they will be
mowed in this order."

- Tap the zones **in mowing order**: they will be mowed in that order, each zone finished before the next.
  Numbered badges show the order on the map and in chips under the title; tapping a zone again, or a chip, removes
  it.
- **Add all**, **Clear**, then **Next**. A complete map is required ("Load a complete map first (Map button).").
- The cross closes the zone choice. **Mow** reopens the route preview already computed, if any, instead of the zone
  choice.
- [DumbyLuba zones](map.md#mammotion-zones-and-dumbyluba-zones) (recorded by driving, kept on the phone) are chosen and
  mowed like the Mammotion zones; their chip and their name carry the **DL** mark ("3. Verger · DL").

### 2. Task settings

The **Task settings** page starts with the **Zones** group ("3 zones · mowing order", with the order "1. Aire de
jeux → 2. Piscine…"; a tap goes back to the zone choice on the map). When the chosen zones go beyond the Mammotion
boundaries (DumbyLuba zones, enlargements), an orange note gives the area concerned: "Includes 34.0 m² beyond the
Mammotion boundaries (DumbyLuba zones, enlargements; hatched on the map): this surface only exists on this phone and is
mowed under your responsibility."

**Route mode**

| Mode | Principle |
|---|---|
| **Zigzag** (default) | parallel passes; the orientation is chosen automatically for the zone |
| **Optimized** | each part of a zone takes the direction that needs the fewest U-turns; the simple variant is kept if it is better |
| **Checkerboard** | two families of passes crossed at 90°: all the passes in one direction, then all those in the other |

With **Optimized**, a note recalls: "Zigzag fitted to the shape: each part takes the direction needing the fewest
U-turns." ([details](../ROUTE_OPTIMIZATIONS.md#optimized-mode-orientation-chosen-per-part-of-a-zone)).

Signal surveying is no longer a mowing mode: it runs automatically during moves, and the dedicated survey route
(*Full loop* or *Grid*, blades stopped) is prepared from Service → **Signal surveys**
([signal surveys](signal-surveys-and-terrain.md)). It then opens the same flow with a **Survey zones** panel and a
**Survey route** page (group *Signal survey route*): **Full loop** ("Adjacent zones merged: only the shared outer
boundary is driven") or **Grid** (merged boundary and crossed grid), **Grid spacing** 1 to 10 m (default 3 m),
**Edge margin** (5 to 100 cm, default 10 cm, kept) and **Survey speed** 0.2 to 0.6 m/s; the preview button reads
**Start the survey**.

**Settings**

| Setting | Range | Default |
|---|---|---|
| *Mowing* · Cutting height | range of the connected model, 1 mm steps: 25 to 70 mm (LUBA 2), 55 to 100 mm on an H version ([heights by model](supported-models.md#cutting-heights-by-model-version-1130)) | 60 mm |
| *Mowing* · Cutting passes | 1 to 3 passes, at least 5 mm apart | 1 pass |
| *Mowing* · First pass height (2 passes or more) | from cutting height + 5 mm per pass up to the model's maximum (70 mm, 100 mm on an H version) | 70 mm |
| *Mowing* · Task speed | 0.2 to 0.6 m/s | 0.3 m/s |
| *Mowing* · Outer margin | 5 to 100 cm between the robot's side and the boundary, in 5 cm steps ("5 cm from the edge"); disabled with 0 perimeter loops | 5 cm |
| *Working hours* · Limit mowing hours | off / on, window in 15 min steps ([working hours](real-mowing.md#working-hours)) | off |
| *Advanced settings* · Pass spacing | 5 to 35 cm, 1 cm steps | 25 cm |
| *Advanced settings* · Perimeter loops | 0 to 4 | 1 |
| *Advanced settings* · Loops around no-go zones | 0 to 4 | 1 |
| *Advanced settings* · Perimeter first | on / off ("Mow the borders before the inside of the zones.") | on |
| *Advanced settings* · No-go zones on the way | on: "Their loop is done when the route passes nearby, then mowing resumes."; off: "All the no-go zone loops are grouped before the perimeter." | on |
| *Advanced settings* · Battery: return / resume | 10 to 100 %, return ≤ 80 %, gap ≥ 5 points | 20 % / 90 % |
| *Advanced settings* · Return reserve | 5 to 30 %, never above the return threshold (brought back under it when the threshold is lowered) | 10 % |

The *Advanced settings* group is collapsed; its header summarizes spacing and loops ("25 cm · 1 + 1 loops"). The
battery range recalls "Mowing stops at 20 % to recharge, then resumes at the interrupted point at 90 %." The return
reserve recalls "Far from the base, the robot returns as soon as its battery nears the cost of the return plus 10 %,
even above 20 %. Between 5 and 20 % (never above the low threshold)." (see
[return reserve](real-mowing.md#return-reserve-never-die-outside)). With 2 or 3
cutting passes, a note gives the heights ("Tall grass: 70 → 55 → 40 mm. Every other pass is driven in reverse;
duration multiplied by 3."); a cutting height too high for several passes shows "Cutting height too high for several
passes (at least 5 mm apart, 70 mm at most)." (100 mm on an H version)

The footer gives the area and a first duration estimate ("412 m² · ≈ 1 h 05 (estimate before computation)"), then
the **Route preview** button.

**Height passes for tall grass** (since 0.45.0): with 2 or 3 passes, the task is mowed several times, lower and
lower, down to the cutting height. Heights are spread evenly between the first pass and the cutting height: for
example 3 passes from 70 to 40 mm give **70 → 55 → 40 mm**. The computed route contains every pass; every other pass
is driven **in reverse**, so that it starts where the previous one ended (no link route between two passes) and the
grass is cut in the other direction. Between two passes, the robot stops, sets the new height and waits until it
reports it before resuming cutting. Duration and distance are multiplied by the number of passes (estimate,
recharges and progress included). The preview shows "3 passes 70 → 55 → 40 mm" in its settings. A cutting height
that is too high limits the number of passes (at least 5 mm apart, 70 mm at most: 66 mm and above only allows one
pass; on an H version up to 100 mm, so 96 mm and above).

**Last settings reused** (since 0.39.1): all these settings (height passes included since 0.45.0), and the route mode (Zigzag, Optimized,
Checkerboard), are kept from one task to the next and after an app restart; the default value is only used the
first time (stored in the `mowing_task` preferences; the outer margin and the survey edge margin in `planner`). The
**travel speed** set during mowing is kept in the same way. The **Outer margin** (between the robot's side and the
boundary), adjustable up to 1 m, is shown with the height and speed (it used to sit in the advanced settings). The
working hours are a setting shared by all tasks.

**Margin applied everywhere** (since 0.40.1): the lap around no-go zones follows the **outer lap margin** (it stayed
5 cm from the no-go zone whatever the setting). Passes stay behind the laps; without a lap, they stop at the lap
distance + 20 cm (the margin was ignored). They therefore never get closer to a boundary than the lap, and the
bumper stays at least the set margin away from a no-go zone at the end of a pass.

**Margin also kept during pivots near no-go zones** (since 0.45.1): when pivoting, the robot's corners sweep 43 cm
around its center, more than the bumper (34.5 cm). On Oct 15, with a 25 cm margin, the Fosse saw four bumper
contacts around a no-go zone, all during a pivot: the corners passed 17.6 cm from the outline at the end of a pass
and 7.6 cm at the entry of the no-go lap (pivot placed on the lap). Now:

- passes that end facing a no-go zone stop at 43 cm + the margin from its outline (9 cm earlier), the no-go lap
  mowing the strip;
- the lap of a no-go zone is joined and left **at an angle**: the robot pivots further out, where the whole pivot
  keeps the margin, then joins the lap along a tangent curve over 1.2 m (symmetrical exit), with no pivot on the lap;
Outside this guarantee remain the sharp corners inside a lap: hollows of a no-go zone (30 to 40° pivot), points of
the outer lap where it goes around a no-go zone close to the boundary, and a blades-stopped link that pivots in a
passage narrower than the full pivot with the margin (between a no-go zone and the boundary). Rounding them lost
coverage in several zones; no contact has been recorded there. Test: `NoGoPivotMarginTest`.

**Pass ends in the middle of the lap's stripe** (since 0.41.0): with an outer lap, each pass stops half a cut
(20 cm) inside the lap's track, that is in the middle of the stripe the lap has mowed, including in the corners and
narrow lobes that the lap rounds off. Up to 0.40.2, the pockets recovered behind no-go zones followed the outline
without this rounding: in these corners, passes came within 1–13 cm of the lap, or even beyond it (reference map,
Jardin d'Eden, Aire de jeux and Piscine; test `ZigzagBehindLoopTest`).

### 3. Route preview

- Indicators: **Total time**, **Recharges**, **Area**, **Distance**. Time and recharges start from the actual
  battery of the connected robot and are adjusted on previous real mowing runs (duration factor, battery life,
  recharge duration); the note under **Route** gives the source
  ([adjusted estimates](mowing-history.md#adjusted-estimates)).
  Before the full estimate is ready, a simplified "≈" value from the distance is shown.
- Preview map (drag, pinch to zoom, rotate with two fingers; **Zoom out** / **Zoom in** with the zoom factor and
  **Recenter the map** at the top right): white = mowing route and border loops (the latter on a lighter edging that
  shows the margin along boundaries and around no-go zones), numbers = zone order, purple **Start** marker = first
  cutting point, with an arrow giving the direction the robot sets off in along its first pass, blue dot = requested start point (chosen or saved). The route is traced progressively in mowing
  order once computed (instant when Android animations are off). "N border fragment(s) too narrow to maneuver: not
  driven." flags skipped border loops.
- **Start point** group: *Start* shows "Nearest to the robot", "Saved for <zone>" or "Chosen on the map · <zone>".
  **Change** (then **Cancel**), then tap the preview map inside a chosen zone (outside no-go zones): the route is
  recomputed from that point. **Nearest** returns to the default start for this preview. **Save for <zone>** stores
  the point as the default start of the zone containing it; **Forget the saved start of <zone>** clears it. See
  [chosen or saved start](#start-chosen-or-saved-per-zone).
- **Route** group: zones in order and settings ("Zigzag · 60 mm · 25 cm · 0.30 m/s", or "3 passes 70 → 55 → 40 mm"),
  then the estimate note.
- **Check**: **Coverage**, **Unmowed area**, **Total distance** ("1,234 m including 210 m without cutting"), and the
  note "Route checked: same route for the simulation and real mowing." or "Route not validated: …" with the reasons.
- Warnings: rain forecast ([weather](rain-and-weather.md)), "Robot self-stop not demonstrated (Service → Command
  cutoff): supervision required." ([command cutoff](safety.md#command-cutoff-motor-watchdog)), "Starting replaces the
  saved "Mowing interrupted" (42 % mowed)." when an interrupted task exists.
- Status line above the buttons: "Computing the route · …", "Checking the links · n/total" (then recharges and
  returns optimization), "Start possible as soon as the robot is connected (gateway or direct Bluetooth)." or, on the
  account or demo map, "To mow, read the robot's Bluetooth map."
- **Simulate** ([simulation](simulation.md)), enabled once the links are checked and the base and its docking axis
  are known; **Start** ([real mowing](real-mowing.md)), enabled with a connected robot, the robot's own map, a valid
  plan and no other activity.

## How the route is computed

1. **Ground**: chosen zones minus no-go zones and [learned obstacles](obstacles.md), shrunk by the safety margin. An
   obstacle counts for its radius, its margin and the robot's turning envelope.
2. **Orientations**: for each zone, the directions of the six longest edges and a grid every 15° are evaluated in
   parallel, with a cost that accounts for turns and stops.
3. **Passes and borders**: full passes, concentric border laps offset by the spacing.
4. **Continuous order**: the cell started is finished before the next; a neighboring block is then reached through
   a safe link ([continuous mowing](../path-planning/ENGINE.md#continuous-mowing)). A **virtual bar** advances across
   the zone: the robot mows everything left on one side before moving on, instead of leaving a stripe in the middle
   and coming back to it across the zone. The bar is tried across and along the passes, in both directions; the
   tidiest order is kept if it costs no more than 2 % of route length, otherwise the nearest link is kept. In
   **Checkerboard**, all the passes in one direction are mowed before those in the other
   ([sweep bar](../path-planning/ENGINE.md#sweep-bar-and-checkerboard-by-direction--0460)).
5. **Nearest start**: the route starts with the shortest reachable link from the robot's position; from the base
   if it is charging, if its position is unknown or if it is inside the base safety zone. Closed loops change origin rather than
   keep an arbitrary anchor. The mission ends at the base.
   A start point chosen in the preview or saved for the first zone replaces the robot's position in this
   computation (below).
6. **Links**: A* search in the free area (zones, robot passages, manual access), with at least 50 cm from the
   borders during moves with blades stopped, except in a narrow passage. Areas flagged as difficult (Difficulties
   heatmap) are avoided by these blades-stopped moves, and a learned obstacle near the docking corridor gets a
   reduced margin ([obstacles near the base](obstacles.md#obstacles-near-the-base)).
7. **Validation**: whole mission evaluated (coverage on a 2 cm grid), chassis checked on every translation and every
   pivot. A plan can only be run if it is valid and has no geometric violation.

## Start chosen or saved per zone

Priority order for a preview's start point:

1. **Point chosen in the preview** (**Change** button, then tap the map); it must lie in a chosen zone, outside
   no-go zones. **Nearest** forces the default start for this preview, even if a point is saved.
2. **Saved start of the first zone** in the mowing order (only the first zone counts: it is the one mowed first). A
   saved point that is no longer inside its zone (map reread or redrawn) is ignored.
3. Otherwise, **nearest to the robot**: from the base if it is charging, if its position is unknown or if it is in
   the base safety zone.

- The requested point is moved to the nearest free point (2 m at most) where the robot can stand and pivot;
  otherwise the computation is refused: "Start point unreachable: choose a point further inside the zone".
- The start point sets the route's **entry** (shortest link from that point, origin of the loops); at the real
  launch, the robot first reaches this entry from its exact position through a safe link, as for any start. The
  mission always ends at the base.
- Saved starts are kept on the phone (`zone-starts` preferences), per zone identifier of the robot's map. The choice
  made in the preview is forgotten when the zone selection or the map changes.
- Code: `planning/ZoneStart.kt` (`ZoneStart`, `ZoneStartStore`), `RoutePlanning.nearestUsable`,
  `map/StartPointGroup.kt`; tests `ZoneStartTest`.

## Robot footprint

- LUBA 2 AWD: **690 × 513 × 273 mm**, position reference at the center (345 mm to the front and to the rear).
- **Recorded outlines (zones, no-go zones) are the robot's outer side**, not its center. All center routes (outer
  lap, no-go laps, passes, links) therefore stay **at least half a width (25.7 cm)** from the outline: outer lap at
  25.7 cm + the set margin, no-go laps at 25.7 cm + the same margin (5 cm by default, see below). As the blades (40 cm) are narrower than the robot, a 5.6 cm
  strip along the outlines is never cut; it is not counted in the coverage. During a pivot or a tracking deviation,
  the chassis can overhang the outline by 15 cm at most. Up to 0.39, the outline was taken as the center's track: the
  no-go laps passed 8 cm from the outline, with the robot entering it by 18 cm.
- The robot stops and pivots at vertices: the corners sweep a radius of about **430 mm**, reserved around the edges
  during turns.
- Translations use an exact envelope; turns are split into steps of 5° at most, with an allowance that covers the
  space between poses. Any contact with a boundary is rejected.
- Modeled cutting width: 40 cm disc.

## Safeguards

- Minimum coverage: **95 %** on a real map (65 % on the demo map, which cannot start a robot). It is measured inside
  the outer lap: the strip left on purpose along the outline (robot half-width, **outer lap margin**, rounded
  corners included) and around no-go zones is not counted as missed. Before 0.39.2, a set-back of 50 cm or more was
  refused ("Residual area …; threshold or reference not reached").
- Explicit rejection messages: "No route possible with this margin", "Unsafe border: increase the margin", "No
  coverage space left after the border loops", "Map changed: recomputation required", "Map or obstacles changed
  during the computation: recomputation required", "Start point unreachable: choose a point further inside the
  zone", passages reduced to a point or unconnected zones.
- **Start** requires a connected and ready robot, the robot's real map (neither the demo nor the account map), a
  known base with its docking axis and no other activity; the full list of start conditions is in
  [real mowing](real-mowing.md#start-conditions).

## Limitations

- Free areas come from the map: the width of a passage defined by a simple axis is an assumption, not a field
  measurement. Obstacle height is not checked.
- The nearest start minimizes the link among a finite set of candidates; it does not guarantee the shortest total
  duration.
- Task settings are stored on the phone (Fold), not on the robot or in the account: another phone keeps its own.
- Several zones chosen: only the saved start of the first zone is used.
