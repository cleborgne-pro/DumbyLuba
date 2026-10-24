# Production planning engine

This document describes the coverage engine used by the app. The [architecture](ARCHITECTURE.md), the
[algorithms studied](ALGORITHMS.md) and the [parameters](PARAMETERS.md) detail the design.

## One engine, one prepared route

The app never uses the native Mammotion planner to prepare, start, resume or delegate
mowing or a movement. The only exception is final docking: the robot first reaches, via the local route,
the approach point in front of the base, and the official Mammotion return then only handles docking. Map and
telemetry reads and safety stop commands remain available.

The local automatic engine is the only computation path: simulation, real mowing over Bluetooth, signal
surveys, go-to-point and station routes. The interface offers no choice of algorithm; the route
types (Zigzag lines, Optimized, Checkerboard, full loop or grid survey) are goals, not engines. The search
variants (DP, 2-opt, LNS…) remain reserved for tests and benchmarks.

`MapScreen` calls `RoutePlanning.coverage`, which delegates to `AutomaticPlanner`; the map it receives is the
Mammotion map with the [local corrections](../features/map.md#correcting-the-map-local-corrections) applied, and the
same planner serves the supervised tests (painted zone, border following) and the resume (`RoutePlanning.resume`). All links go through
`RoutePlanning.assemble` and `TrajectoryPlanner`, with validation of the robot envelope. The simulation and the
Bluetooth controller follow the **same prepared route**, with the same geometric checks.

## Real execution and safeguards

Only a mission fully validated in the model, without any geometric violation, is published as executable
(`localOnly=false`). Partial or invalid plans remain forbidden to Bluetooth tracking. Building
`MowingSimulation` checks segments and recharges. Starting requires the map of the connected robot, a real base
axis, fresh states and positions, a compatible battery and no competing activity. Tracking
checks deviations, chassis collisions, local obstacles, blades and battery thresholds: any
failure stops the route. Commands are supervised local control, never an autonomous firmware mission.

On a real map, the covered-area threshold stays at 95 % (80 % for a painted trial zone); the demo uses 65 % on
fictitious outlines and cannot start a robot. Surveys without blades check the geometry without requiring an area.

## Continuous mowing

Lines and checkerboard keep every pass whole and finish the cell they started before visiting another
cell or a no-go zone border; the robot then reaches a neighboring block through a safe link. The
cells are the groups of stripes bounded by connectivity changes around exclusions.

`AutomaticPlanner` ranks orientations by an approximate cost (length, hypothetical transit, angles, stops,
U-turn penalty) over the directions of the longest edges and a 15° grid, builds a local order
by cells with real directed links, then **evaluates the whole mission**. If the geometry differs from the reference
stripes, the covered area is compared with an exact footprint: a loss of more than 0.2 points triggers a
fallback to the reference orientation, still in a continuous and revalidated order.

The search for the next block examines neighboring ends in batches of three and only widens if no safe link
exists: it favors proximity without guaranteeing a global minimum. A started cell takes precedence over a
closer cell.

## Nearest start

Mowing, a survey or a trial mowing starts as close as possible to where the robot is, through the shortest
reachable link among the safe entries; from the base only if the robot is charging, if its
position is unknown or if it is inside the base safety zone. A start point tapped in the route
preview, or else the start saved for the first zone mowed, replaces the robot's position
(`ZoneStart.requested`); it is moved to the nearest free point, within 2 m, where the chassis can pivot
(`RoutePlanning.nearestUsable`).

`LoopEntries` offers as entries of a closed loop its vertices and the projections of the start point onto its
segments, in both directions: the origin of the loop moves instead of keeping an arbitrary anchor. Open
passes stay whole and can only be entered at their ends. The engine keeps the shortest **length of a
directed link found by navigation**, not the straight-line distance. A new origin requires a full
chassis pivot reserve. With several zones, the order chosen by the user is kept as is: the first zone chosen is
entered as close as possible to the start, and each next zone starts from the exit of the previous one. The whole mission is then evaluated and checked, without removing any
envelope, obstacle or tracking check.

Measured on the reference map (`StartTransitTest`): robot at a far corner of each zone, initial transit to the
chosen start 0.0 to 0.2 m against 11 to 28 m to the fixed anchor of the plan (its first vertex), 0.7 m against
134 m over the seven zones. The **Start** marker of the map and of the preview carries an arrow along the first edge
of the first pass (`routeStartAhead`): the direction the robot sets off in.

Transits keep away from the boundary portions typed **Danger** ([border types](../features/map.md#border-types)):
`TrajectoryPlanner` first looks for the transit in the comfort and crossing surfaces minus a band of half a width +
0.8 m around those portions, with no detour limit, then falls back to the usual surfaces. Same planner for the
computation (`PlanningRequest.dangerLines`, filled in by `RoutePlanning.coverage` from the annotations) and for the
mission (`RoutePlanning.mission`).

Border strategy per type (`RoutePlanning.withBorderStrategy`, `map/BorderStrategy.kt`): for a recorded boundary, the
planner works on the outlines moved along each typed portion by its lap margin minus the set margin (open lawn 2 cm,
wall ≥ 12 cm, danger ≥ 55 cm), `PlanningRequest.navigationMap` keeping the recorded outlines for the transits. The
envelope of the passes is that of the moved outlines: along a Danger portion the chassis footprint stays ≥ 40 cm away,
pivots included (`BorderStrategyTest`).

## Sweep bar and checkerboard by direction — 0.46.0

Observation: on a checkerboard zone, passes of both directions were mixed and the robot crossed from one side of
the zone to the other; in zigzag, stripes were left behind it and mowed last.

`LocalMowingOrder`:
- **Checkerboard**: all passes of one direction before those of the other (hard constraint). No-go zone laps
  remain possible in both. The second direction starts from the side where the robot is; if a
  cell is temporarily unreachable, each direction is also tried first and the order that changes
  direction the least is kept.
- **Bar** (`SweepBar`): a virtual bar moves across the zone, perpendicular or parallel to the passes, in one
  direction or the other, with a ±1 m window. Only blocks that start before the bar are open: everything
  left on one side is mowed before moving on. Without a safe link in this choice, fallback to all remaining blocks.
- Metrics: `layerSwitches` (direction changes in checkerboard) and `backtracks` (cells started while a
  cell of the same orientation remained entirely behind).

`AutomaticPlanner.order` compares the greedy order and the 8 bars. It keeps the fewest direction changes
(checkerboard), then the fewest backtracks, among the orders whose cost does not exceed the greedy one by more than 2 %
(`BAR_TOLERANCE`). Signal surveys are not affected.

Reference map, spacing 0.25 m (`SweepBarProbeTest`, `-PsweepBarProbe=true`), 0.45.2 → 0.46.0:

| Zone | Zigzag: backtracks | Zigzag: duration | Checkerboard: direction changes | Checkerboard: duration |
|---|---|---|---|---|
| Jardin d'Eden | 0 → 0 | 1176 → 1157 s | 1 → 1 | 1985 → 1967 s |
| Aire de jeux | 19 → 13 | 2717 → 2731 s (+0.5 %) | 19 → 1 | 5025 → 5052 s (+0.5 %) |
| Piscine | 12 → 5 | 5260 → 5234 s | 14 → 1 | 9690 → 9674 s |
| Figuier | 44 → 10 | 2113 → 2122 s (+0.4 %) | 11 → 1 | 3501 → 3482 s |
| Enfer | 32 → 32 | unchanged (every bar > 2 %) | 26 → 1 | 2657 → 2696 s (+1.5 %) |
| Fosse | 33 → 26 | 4777 → 4729 s | 30 → 1 | 8406 → 8613 s (+2.5 %) |
| Potager | 0 → 0 | unchanged | 10 → 1 | 2299 → 2285 s |

Coverage unchanged (≥ 99.96 %). Checkerboard by direction costs transit on Fosse (221 → 266 m): that is the price
of one whole direction before the other.

Tests: `LocalMowingOrderTest`, `SweepBarOrderTest`, `ZoneStartTest`, and `ReferenceMapPlannerTest` on every
zone of the reference map (modes, spacings, margins and border passages).

## Limitations

- Gains are measured in simulation and in the geometric model; they do not prove a gain in the field.
- Orientation ranking ignores obstacle detours and exact overlap: a favorable candidate
  may fail final validation. The fallback is then tried, without an exhaustive backup search; a map
  that cannot be sequenced is refused.
- The order between zones stays as requested: no global search.
- The exact union of footprints (JTS) remains the main computation cost on large zones.
- The limitations of the envelope, of the tool (centered disc), of the unknown slope and of the uncalibrated pivot apply.
