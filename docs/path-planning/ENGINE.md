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

**Direction of the passes** (`PlanningConfig.orientation`). *Automatic* is the search above, unchanged. *Manual*
replaces the candidate directions of every zone by `manualAngleDeg` (modulo 90 in Checkerboard) and makes the zone its
own surface reference: `sameFootprint` holds, so no fallback can swap the angle. A refused sweep is reported as
"Manual angle 37° cannot be mowed on <zone>: … Use Automatic.", an evaluation under the threshold keeps the plan
`localOnly` with "Manual angle 37°: route not validated at this angle. Use Automatic.". Optimized ignores the setting
(both of its plans run with `orientation=false`). `Preview.zonePhases` and `Preview.zoneModes`, aligned with
`zoneIds`, give the row phase (0.5: centred) and the pattern applied on each zone; `MissionCheckpoint` saves them and
reads older saves without them. Reference map at 25 cm (`OrientationReferenceTest`, 7 zones × Zigzag and Checkerboard
× 0, 37, 90, 143°): every plan valid, the requested angle on every zone, mission duration −6.8 to +9.3 % against
Automatic in Zigzag and −1.2 to +5.5 % in Checkerboard, computation ×0.46 to ×0.53 of Automatic over three runs (0.31 s
against 0.67 s per zone on an idle machine: ordering and evaluation do not shrink with the search). Automatic gives the same `planHash` as before on the
seven zones, in Zigzag, Checkerboard and Optimized.

*Change every session* (`OrientationRule`, `map/Orientation.kt`; memory in `planning/ZoneOrientationMemory.kt`). For a
zone with a valid entry in `PlanningRequest.orientationMemory`, the candidates get the directions 45° and 135° away
from each remembered direction (`extraAngles`); `CoveragePlanner.plan` keeps every `(candidate, ranking cost)` and
`OrientationRule.pick` takes the cheapest one at least 45° from the remembered directions (both families of a
checkerboard count) whose cost is at most 5 % above the cheapest; none ⇒ the cheapest orientation is swept again with
the next row phase (0.5 → 0.2 → 0.8, `Preview.zonePhases`). If the evaluated mission of a moved direction is refused, the
fallback `continuous-orientation-shift` tries the automatic direction with the shifted rows before the usual reference
fallback. `RobotBle` keeps the preview and map of a real mowing (`historyBegin`) and, at its end, `ZoneProgress.update`
records every zone with at least 50 % of its cutting metres done (`doneMeters`, scaled to the prepared mission) whose
pattern uses an orientation, with its outline key (`stableHash` of the points rounded to 1 cm); `ZoneOrientationStore`
writes `zone-orientations.json` atomically through a conflated channel. Each cutting path counts for the zone it was
planned for: `MultiZonePlanner.combine` sets `Preview.pathZoneIds` (one zone id per cutting path), `CutPasses.expand`
and the reorderings (`TaskSequencer`, `NeighborhoodSequencer`, through `Preview.zonesOf`) keep it, the mission
checkpoint saves it; a border loop running inside an overlapping neighbour never counts for that neighbour. Without it
(older checkpoint), a path goes to the zone holding most of its length (inside or within 5 cm of the outline, sampled
every 25 cm): on the reference map with every zone, 20 m misattributed in Zigzag (61 m in Checkerboard) instead of
535 m (731 m) with the former middle-point rule. The memory is the last direction only, not a
fixed cycle relative to the automatic direction, which may change between sessions. Reference map, automatic plan
then four sessions per zone, Zigzag and Checkerboard: 40 of 56 sessions moved by 45 to 78°, 16 with shifted rows, mission
duration −7.0 to +6.2 % against Automatic (the cap bounds the ranking cost, not the evaluated duration), coverage within
0.17 point, computation ×0.96 to ×1.10 of Automatic over three runs.

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

## Zamboni order

`CoverageMode.ZAMBONI` (experimental) keeps the zigzag passes of each zone and changes their order inside the regular
cells so that two passes driven one after the other are at least `m = TurnOrder.step` rows apart (3 at 25 cm, 0.75 m:
a rolling U-turn of 0.35 m fits) while the mowed front keeps advancing. Design: [PATTERNS.md](PATTERNS.md), section 4.

**Order of one cell** (`map/ZamboniOrder.kt`). Rows `0 .. n-1` of a regular cell (`TurnOrder.ladder`: straight passes,
alternating directions, one spacing ± 30 % apart; the check of the Lawn friendly order, now shared). Window
`W = min(2 (m + 1), 12)` rows (8 at 25 cm): a pass may be driven only once every pass more than `W` rows behind it is
done. Exact dynamic programme over the states (first row not done, rows done in the window, last pass), one layer per
pass, both directions of the first pass tried; moves ±m, ±(m+1), ±(m+2) rows (rolling) and ±1 (adjacent pass). The
cost of a move is the score of its U-turn for the *Lawn friendly* objective (`0.25 × duration + 0.06 × wear`, whatever
the toggle says: with the fast weights the plain zigzag wins), given by the same `TurnOptimizer.candidates`, `safe`
and `TrajectoryEnvelope` check as the join, memoised in a concurrent table shared with the zigzag ranking and the final
join; a move whose U-turn cannot be driven is a break (new run, 60 like the join), so a complete order always exists.
Layers above 4,096 states keep the cheapest (deterministic order), `checkpoint` is called at every layer. Not applied
(zigzag order of the cell): cells with fewer than `2 m + 2` passes, spacings whose rolling moves do not fit in 12 rows
(under 7 cm), and, after the join, a result with more runs or a higher cost than the zigzag order of the cell (the
search estimates its U-turns on whole passes; the join drives the shortened ones). With the synthetic cost of
`ZamboniOrderTest` the order at 25 cm is `0, 3, 6, 2, 5, 1, 4, 7, 10, 13, 9, 12, 8, 11, 14 …` (up by three twice, back
by four), every move rolling.

**Orientation** (`CoveragePlanner.plan`). The orientations are ranked as for Zigzag (zigzag join, Lawn friendly
objective, no one-in-two order); the passes of the 3 cheapest ones are reordered, each in both directions (the
reversed cell reuses the order found, mirrored), and the cheapest of these 6 candidates is kept. A manual angle or the
direction kept by *Change every session* is the only one reordered. `Preview.zoneModes` stays `ZAMBONI`; the reference
orientation, the 0.2-point floor and the fallbacks of `AutomaticPlanner` keep their meaning (same passes).

**Order between runs** (`LocalMowingOrder`). A Zamboni cell cut by breaks is one order: it is entered at its first run
forwards or at its last run backwards, then driven run after run in that order (the nearest run of the cell only if
the next one has no safe link). When this order reaches a dead end (no safe link to the remaining blocks, so no order
of the zone at all) and the plan is not validated, `AutomaticPlanner.plan` computes the plan once more with that zone
ordered without chains (`chained = false`, the order of the Zigzag mode on the same runs) and keeps it only if it is
validated: plans validated with chains are unchanged. Such a zone has `Preview.zoneReorderedCells` −1 and the warning
"Zamboni on X: no safe link in the Zamboni order, zigzag order" (Aire de jeux at a manual 0°, 115° or 155°,
`ZamboniDeadEndTest`). Other modes are unchanged (same `planHash` on the seven zones in Zigzag fast and Lawn
friendly, Checkerboard and Optimized at 25 and 35 cm, and on the seven zones in one Zigzag task).

Reference map, 25 cm, recorded boundaries, production entry point (`ZamboniReferenceTest`, 2026-10-27). Zigzag with
fast U-turns / Lawn friendly / Zamboni, each at its own orientation; pass duration and wear from `TurnOptimizer.drive`
on the cutting runs; U-turns without pivot counted between the passes of the regular cells (2 m + 2 passes or more):

| Zone | Angle | Pivoted in place | Wear | Pass duration | No pivot (regular cells) | Mission | Computation |
|---|---|---|---|---|---|---|---|
| Jardin d'Eden | 60 / 55 / 60° | 1970 / 360 / 890° | 1970 / 950 / 1696 | 709 / 720 / 778 s | 11 of 14 | 1157 / 1170 / 1140 s | ×1.38 |
| Aire de jeux | 150 / 146 / 151° | 6398 / 4976 / 4631° | 6403 / 5936 / 5518 | 1793 / 1900 / 1847 s | 12 of 27 | 2731 / 2711 / 2735 s | ×1.15 |
| Piscine | 59 / 60 / 75° | 11943 / 10358 / 13185° | 11955 / 10589 / 13938 | 4352 / 4282 / 4568 s | 10 of 72 | 5234 / 5198 / 5142 s | ×1.56 |
| Figuier | 99 / 90 / 75° | 6602 / 5745 / 4146° | 6614 / 5892 / 5278 | 1088 / 1075 / 1147 s | 15 of 31 | 2122 / 1992 / 1901 s | ×1.44 |
| Enfer | 120 / 150 / 140° | 3413 / 2856 / 2871° | 3429 / 2857 / 2871 | 634 / 598 / 604 s | no regular cell | 1695 / 1728 / 1706 s | ×0.95 |
| Fosse | 135 / 135 / 135° | 7622 / 7262 / 7622° | 7631 / 7418 / 7997 | 3248 / 3250 / 3316 s | 5 of 28 | 4729 / 4732 / 4732 s | ×1.09 |
| Potager | 135 / 150 / 150° | 4927 / 1238 / 1440° | 4937 / 2710 / 2906 | 869 / 888 / 901 s | 20 of 22 | 1401 / 1414 / 1441 s | ×1.67 |
| Map | | ×0.81 of Zigzag | ×0.94 | ×1.037 (×1.035 of Lawn friendly) | 73 of 194 (38 %) | −10.4 to +2.9 % | ×1.24 |

Coverage within 0.11 point of Zigzag (99.87 to 99.99 %), every plan valid, no geometric violation, same `planHash` twice;
front at most 8 rows (`W`); largest order search 0.18 s (29 passes, Potager), computation of a whole zone ×0.95 to ×1.67
of Zigzag in the same JVM. Mowed share still in one piece after a stop at 25, 50 and 75 % of the cutting metres
(`PassOrderMetrics.contiguousShare`, whole zone): mean of the map 0.89 (Zigzag 0.83, Lawn friendly 0.89), from 0.71
(Potager) to 1.00 (Jardin d'Eden, Figuier); it also counts the order of the cells, which Zamboni does not change.
Tracking bench (`ZamboniExecutionTest`, Potager and Jardin d'Eden at 30 and 50 %): largest deviation 0.197 to 0.204 m
(Lawn friendly 0.198 to 0.208 m), no blockage, no pivot in the middle of a pass.

First targets of the design note and what was measured instead:

| Target (PATTERNS.md 4.4) | Measured | Why |
|---|---|---|
| pivots ≤ 0.35 × Zigzag | ×0.81 | on this map most pass ends lie along slanted recorded borders: a rolling U-turn between passes three or four rows apart needs them shortened by more than its arcs sweep (`MAX_TRIM_M` 0.6 m): on Jardin d'Eden and Potager the rolling U fits 389 of the 1,404 rolling moves evaluated, and where nothing fits the refusal is "shortened passes not swept by the manoeuvre" (491 of 518); 55 % of the passes are in cells of fewer than 8 passes |
| wear ≤ 0.6 × Zigzag | ×0.94 | same; where only pivots fit, Zamboni and Lawn friendly loop around adjacent passes or pivot |
| ≥ 80 % of rolling U-turns in the regular cells | 38 % | same; square to the border every U-turn of the regular cells rolls (`CoveragePlannerTest`: 39 of 39 in a rectangle, pivots 7020° → 0°, wear ×0.41) and at 30° only 5 of 63 |
| pass duration ≤ Lawn friendly on every zone | up to ×1.08 (Jardin d'Eden), ×1.035 on the map | same objective plus the front rule; the orientation is chosen on the whole mission (Figuier: passes ×1.07, mission −4.6 % against Lawn friendly) |
| contiguous share ≥ 0.9 on every zone | 0.71 to 1.00, mean 0.89 | the metric counts the order of the cells as well |
| computation ≤ 2 × Zigzag on every zone | ×0.95 to ×1.67 (up to ×2.1 under load) | asserted on the map (×1.24) |

Met: coverage, front ≤ W, pass duration ≤ 1.10 × Zigzag (×1.097 at most), no violation, determinism, search ≤ 3 s per
cell, Zigzag/Optimized/Checkerboard unchanged.

## Spiral

`CoverageMode.SPIRAL` (experimental) replaces the cell sweep of a zone by contour-parallel rings joined into inward
spirals (`map/SpiralSweep.kt`). Design: [PATTERNS.md](PATTERNS.md), section 5.

**Rings** (`SpiralSweep.build`). On the coverage area of `CoveragePlanner.plan` (the one the zigzag passes are clipped
to: border loops, margins, no-go reserves and physical surface already applied), each connected part is made simply
connected by slits (shortest segment from each hole to the exterior or to a hole already joined, staying inside, cut
`cutWidth − 0.1 m` wide with flat caps, so that the rings on both sides sweep it). Level `j` is
`opening(P ⊖ 5 mm ⊖ j × spacing, 0.35 m)`, erosion step by step, JTS buffers with 23 segments per quadrant and no input
simplification (the default simplification cut reflex corners by up to 1 % of the distance and put rings outside the
area). Each part of level `j + 1` lies in one part of level `j`; a part with one child continues the chain, a part with
several ends it and each child starts its own chain (own cell id).

**Chain**. Rings counter-clockwise. The start `s_0` of the outer ring is in the middle fifth of a straight stretch (at
most 15° of turn within `T` on each side, `T = max(0.8 m, 4 × spacing)`), among the stretches at least half as long as
the longest, the one nearest to the entry point of the zone; `s_{j+1}` is the point of ring `j + 1` nearest to `s_j`
(the nearest straight point within two spacings when that one is not straight; farther, the chain stops and the
remaining rings start a new chain). The outer ring is driven whole from `T` before `s_0`; ring `j` from `s_j` to `T`
before it, then a slant to `s_{j+1}`; the last ring whole. The slants of successive rings are parallel and one spacing
apart, so the stretch they replace stays swept. Every ring piece, with the vertex before it, is checked once as a whole
(safe surface, chassis envelope with the pivots at its vertices); a refused piece ends the chain on the previous ring
driven whole and the next ring starts a new chain. A JTS failure, or no chain at all, returns null.

**Pockets**. The area minus the chains swept at half a cut (chains simplified by 5 mm, buffer of 4 segments), closed by
one spacing (the gaps along the bisector of a sharp corner or between two parts of a split merge into a few strips),
pieces of 0.04 m² at least. Each is filled with `MixedSweep.fill` in the direction (long axis or every 30°) of the least
cost (length, 2.7 m per U-turn, 6 m per extra cell) among those keeping every pass on the safe surface; then each cell of
3 passes or more is filled again in its own direction when cheaper (the core of an L). Pocket cells are joined like
zigzag cells (Turn Optimizer, fast U-turns: *Lawn friendly* is forced off in Spiral).

**Choice** (`CoveragePlanner.plan`). The spiral is built as one more task of the parallel orientation search, which
computes the zigzag of the zone exactly as in Zigzag mode (same angles and ranking). The spiral is kept unless its
cutting paths sweep more than 0.2 point less than that zigzag (2 cm raster, half a cut, over the coverage area widened
by half a cut); otherwise the zigzag is returned with `Preview.zoneModes = [LINES]` and the preview warns
(`zoneModeWarnings`). `Preview.mode` stays `SPIRAL`, `angle` is 0, `passes` are the chains and pocket passes; the
reference and fallbacks of `AutomaticPlanner` keep their meaning (reference: the zigzag). `LocalMowingOrder`,
`MissionEvaluator`, checkpoints and the smart resume take the chains as runs, enterable at either end. Other modes are
unchanged (same `planHash` on the seven zones in Zigzag fast and Lawn friendly, Checkerboard, Optimized and Zamboni at
25 and 35 cm, on the seven zones in one task, and same `Geometry.plan` previews).

**Direction** (`PlanningConfig.spiralDirection`, `SpiralDirection`). `FROM_EDGE` (default) is the plan above, unchanged
(same `planHash` on the seven zones in Spiral, Zigzag, Zamboni and Checkerboard at 25 cm). `FROM_CENTRE`
(`CoveragePlanner.plan` → `outward`): the same build, every chain reversed (driven from its innermost ring out to its
outer ring) and the chains taken in reverse build order, so the first chain built, which starts at the edge nearest to
the entry point, is driven last and ends there. The surface check reads the same segments both ways, but the chassis
envelope is not symmetric for a robot whose front and rear overhangs differ (another footprint backwards, other pivots):
each reversed chain is checked again with the same `validChain` (safe surface, `TrajectoryEnvelope.pathCollision` with
the pivots at its vertices) and a refused one keeps its inward direction (already checked), free at either end. A pocket
within one cut and one spacing of the first point of a reversed chain is its core. `SpiralSweep.Result.parents` gives,
for each chain, the chain it continues inwards (split, refused ring, cut). After `route` (chains kept as single runs,
never joined to a pocket pass), a chain, its cores and all the chains inside it share the cell of the outermost one, and
`Preview.outwardRuns` maps each reversed chain run to the runs that must come first (its cores and the chains continuing
it inwards). `LocalMowingOrder.schedule` enters such a run only at its first point (`Task.oneWay`) and only once those
runs are mowed (`ready`, applied to the open blocks, the active cell and the bar fallback); without `outwardRuns` the
order is unchanged. `Preview.zoneReorderedCells` holds the number of chains driven outwards per zone. A greedy order can
still end a block where no safe link leaves (Aire de jeux: the exit of a no-go loop lead); `AutomaticPlanner` then plans
that zone again from the edge (`ZonePlan.fromEdge`, `zoneReorderedCells` −1, warning "Spiral from the centre not
possible on <zone>: spiral from the edge used"). `nearBase` (research `Geometry.plan` path) keeps the planned order when
`outwardRuns` is set. Checkpoints save the scheduled paths, so a resume keeps the direction. Once a zone is ordered,
`LocalMowingOrder.schedule` turns `outwardRuns` into `Preview.outwardPaths` (indices of `cuttingPaths`), adding every
path it mowed before a chain within a core's reach of its centre end (`Preview.outwardReachM`, one cut + one spacing: a
pocket pass whose pocket went to another chain, a loop); the order itself is unchanged. `MultiZonePlanner.combine`
shifts them to the merged paths and the checkpoint saves them (`PreviewJson`, missing in an older save: none); a
reordering of the merged paths that does not follow them (`CutPasses.expand`, the research sequencers) clears them.
`SmartResume.afterRecharge` places them on the prepared edges (`outwardRules`) and drops every rotation of the
remainder that breaks one the current order keeps (`brokenRules`); a plan whose constraints do not match its edges is
not reordered.

Reference map, 25 cm, recorded boundaries (`SpiralDirectionTest`, 2026-10-27), from the edge / from the centre:

| Zone | From the centre | Coverage | Mission | Transit |
|---|---|---|---|---|
| Jardin d'Eden | 1 of 1 chain outwards | 99.98 / 99.98 % | 1220 / 1247 s (+2.2 %) | 84 / 93 m |
| Aire de jeux | from the edge (no safe order from the centre, warned) | 99.92 / 99.92 % | 3320 / 3320 s | 68 / 68 m |
| Piscine | 11 of 11 | 99.96 / 99.96 % | 5745 / 5922 s (+3.1 %) | 104 / 156 m |
| Figuier | zigzag (as from the edge) | 99.97 / 99.97 % | 2122 / 2122 s | 143 / 143 m |
| Enfer | 8 of 8 | 99.87 / 99.86 % | 2010 / 2107 s (+4.8 %) | 113 / 138 m |
| Fosse | 12 of 12 | 99.91 / 99.92 % | 5573 / 5571 s (−0.0 %) | 204 / 202 m |
| Potager | 4 of 4 | 99.94 / 99.94 % | 1531 / 1542 s (+0.7 %) | 120 / 124 m |

Every plan valid, no geometric violation; synthetic rectangle 12 × 8 m and 14 × 10 m with a 3 × 2 m no-go zone (one root
chain, two chains inside its split): same chains and coverage, cores and inner chains first.

Reference map, 25 cm, recorded boundaries, production entry point (`SpiralReferenceTest`, 2026-10-27), Zigzag / Spiral;
pivots, wear and pass duration from `TurnOptimizer.drive` on the cutting runs, mission duration from the planner model
(every degree of heading change at 45°/s, rolling or not):

| Zone | Spiral | Coverage | Mission | Pivoted in place | Wear | Pass duration | Cutting length | U-turns | Computation |
|---|---|---|---|---|---|---|---|---|---|
| Jardin d'Eden | kept: 1 chain, 8 rings, 2 pockets (4.0 m²) | 99.98 / 99.98 % | 1157 / 1220 s (+5.5 %) | 1970 / 1439° | 1970 / 3327 | 709 / 944 s | ×1.006 | 21 / 6 | ×1.14 |
| Aire de jeux | kept: 14 chains, 22 rings, 4 splits, 11 pockets (15.7 m²) | 99.96 / 99.92 % | 2731 / 3320 s (+21.6 %) | 6398 / 19104° | 6403 / 25595 | 1793 / 3468 s | ×1.094 | 88 / 104 | ×1.19 |
| Piscine | kept: 11 chains, 28 rings, 3 splits, 10 pockets (19.6 m²) | 99.99 / 99.96 % | 5234 / 5745 s (+9.8 %) | 11943 / 13720° | 11955 / 22533 | 4352 / 5673 s | ×1.019 | 117 / 65 | ×1.50 |
| Figuier | zigzag: 69.92 m² swept against 70.13 m² (limit 0.14 m²) | 99.97 / 99.97 % | 2122 / 2122 s | same | same | same | ×1.000 | 85 / 85 | ×1.02 |
| Enfer | kept: 8 chains, 12 rings, 1 split, 7 pockets (10.8 m²) | 99.97 / 99.87 % | 1695 / 2010 s (+18.6 %) | 3413 / 11336° | 3429 / 14578 | 634 / 1570 s | ×1.142 | 70 / 60 | ×1.09 |
| Fosse | kept: 12 chains, 32 rings, 2 splits, 20 pockets (16.1 m²) | 99.98 / 99.91 % | 4729 / 5573 s (+17.8 %) | 7622 / 15464° | 7631 / 26494 | 3248 / 5178 s | ×1.019 | 117 / 85 | ×1.33 |
| Potager | kept: 4 chains, 12 rings, 3 pockets (5.4 m²) | 99.98 / 99.94 % | 1401 / 1531 s (+9.3 %) | 4927 / 2341° | 4937 / 4856 | 869 / 1091 s | ×1.028 | 43 / 16 | ×1.25 |
| Map | 6 of 7 | within 0.10 point | +5.5 to +21.6 % | | | | | | ×1.24 |

Every plan valid, no geometric violation, same `planHash` twice; the Figuier fallback is the Zigzag plan itself (same
`planHash`). Synthetic (`CoveragePlannerTest`, recorded boundary, 25 cm): 16 × 11 m rectangle, same surface, cutting
×1.003, 2 U-turns (the 3-pass core) against 39, pivots 7020° → 360°, wear ×0.45; L, same surface, cutting ×0.995, 6
U-turns against 50, pivots 9000° → 1035°. Tracking bench (`SpiralExecutionTest`, Potager and Jardin d'Eden at 30 and
50 %): largest deviation 0.186 to 0.196 m (Zigzag 0.176 to 0.184 m), no blockage, no pivot in a stripe; missions
+3.0 / +12.0 % (Jardin d'Eden) and +7.3 / +15.4 % (Potager) against Zigzag, pivoting time 22 to 31 % lower.

First targets of the design note and what was measured instead:

| Target (PATTERNS.md 5.4, 6) | Measured | Why |
|---|---|---|
| spiral kept on at least 4 of 7 zones | 6 of 7 | met (Figuier: corners and splits leave 0.21 m² more than the zigzag) |
| coverage ≥ Zigzag − 0.2 point, or fallback | within 0.10 point; Figuier falls back | met |
| length ≤ 1.05 × Zigzag on rectangle and L | ×1.003 and ×0.995 | met; on the reference map ×1.006 to ×1.142 (pockets, slit and split gaps) |
| zero U-turns | none inside a spiral chain; 2 (rectangle) and 6 (L) in the zigzag pockets | the core of a ring narrower than 0.7 m + 2 spacings cannot hold another ring of radius 0.35 m |
| computation ≤ 1.0 × Zigzag | ×1.09 to ×1.50, ×1.24 on the map | the zigzag of the zone is computed anyway (comparison and fallback), the spiral is one more parallel task, and its long runs make the local order and the evaluation slower |
| (reported) mission duration | +5.5 to +21.6 % | each ring turns 360° plus the bends of the recorded boundary, charged at 45°/s by the planner model; pockets are short zigzags with pivot U-turns |

## Limitations

- Gains are measured in simulation and in the geometric model; they do not prove a gain in the field.
- Orientation ranking ignores obstacle detours and exact overlap: a favorable candidate
  may fail final validation. The fallback is then tried, without an exhaustive backup search; a map
  that cannot be sequenced is refused.
- The order between zones stays as requested: no global search.
- The exact union of footprints (JTS) remains the main computation cost on large zones.
- The limitations of the envelope, of the tool (centered disc), of the unknown slope and of the uncalibrated pivot apply.
