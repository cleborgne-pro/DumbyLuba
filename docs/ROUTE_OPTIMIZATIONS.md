# Route preparation and zone entry

Planning a zone now uses the end of the previous zone's cutting route as its
starting point. The first zone is entered as close as possible to the start: the robot's
position, a start point chosen or saved for the zone, or the base if the robot is charging or its position
is unknown ([nearest start](path-planning/ENGINE.md#nearest-start); the base was the reference before). The
chosen zone order and the validation of mapped links still apply.

With nearby no-go borders, the lap is inserted on the pass and segment
closest to the border. The previous one-meter threshold could cause
a departure towards the border from an earlier pass. The interrupted pass is
resumed at the same point, without losing any portion to mow. The verified
maneuver entries of recorded borders are kept.

For recharges, one access can be shared along a whole pass if the same
access minimizes the cost at both ends and if the geometric envelope of the link
is safe for the whole pass. Otherwise, the 50 cm splitting is still used. When a
validated direct link reaches the lower length bound of the candidate accesses,
detours through intermediate pivots are not explored: the triangle
inequality guarantees that they cannot improve it.

## Reproducible checks

- `MowingOrderTest`: waiting for the closest pass and segment,
  resuming the cut and keeping verified entries.
- `MultiZonePlannerTest`: the second zone starts on the side of the previous
  exit, even if the base is on the other side.
- `ReferenceTransitTest.potagerToFosseStartsAtTheNearbyCoverageEntry`: selection of
  Potager then Fosse on the reference map and validation of the links.
- `ReferenceSimulationTest`: length of departures from and returns to the base, and
  playback of the simulation without any extra geometric query.
- `RechargePreparationTest`: clear 36 m pass; 331 geometric queries
  before optimization, 189 after (about 43 % fewer), with checks of recharge routes
  from several positions and a regression threshold below 300.

The computation measurement applies to this synthetic scenario, not a guaranteed time saving
on every map. The reference map configurations remain
covered by `ReferenceMapPlannerTest`.

## Faster computation and preparation — 2026-10-09

- `AreaIndex`: containment of points, segments, triangles, discs and convex
  polygons through an edge grid, without JTS allocation. Same tolerances as the
  replaced `PreparedGeometry`; chassis checks during rotation test
  the exact disc, which contains the former polygonal buffer (at least as cautious).
- `ShortestRoutes`: lazy A* (visibility checked when popped from the queue),
  memoized triangular visibility matrix.
- Navigation surfaces (`TrajectoryPlanner.Surfaces`) and areas of recorded outlines
  memoized across computation, simulation and control; links without heading
  memoized; links from the computation reused by the preparation only after the
  same surface, chassis and heading checks.
- Sweep orientations and optimization of returns/departures computed in
  parallel (`PreparationPool`), selection in the original order: identical
  result. Nested calls run sequentially; cancellation and errors stop the rest.
- Mowed area measured on a 2 cm grid (`RasterCoverage`); the vector
  oracle remains available (`coverageCellM = 0`) and serves as the test reference.
- Passage without a measured width: same conventional corridor as navigation;
  Jardin d'Eden and Potager, previously refused ("No reachable safe entry"),
  are now computed.

Host benchmark `PipelineProfileTest` (`-PpipelineProfile=true`), computation + navigation +
preparation up to the Simulate button, reference map, first run:

| Zone | Before | After | Plan |
|---|---:|---:|---|
| Aire de jeux | 20.2 s | 1.8 s | identical (hash) |
| Piscine | 16.9 s | 0.9 s | identical |
| Figuier | 13.6 s | 0.6 s | identical |
| Enfer | 18.3 s | 0.7 s | identical |
| Fosse | 34.2 s | 1.1 s | identical |

Durations, transits and coverage unchanged on these zones. Measured on a Mac (host),
not on the Fold: the gain on Android still has to be measured with `PlannerDeviceBenchmark`.

## "Optimized" mode: orientation chosen per part of a zone

Zigzag mode sweeps a whole zone in a single direction. **Optimized** mode
("Optimized" tile in the task settings) can change
direction inside the same zone:

1. The best single orientation is searched for exactly as for Zigzag.
2. At that orientation, the zone is split into bands (`MixedSweep`): the usual
   boustrophedon cells (around no-go zones), plus a cut when a pass end
   jumps by more than max(1.5 m; 4 × spacing) or when the pass length
   changes markedly (> max(0.6 m; 40 %)) — typically the entrance of a narrow
   part, such as the "chimney" of Fosse.
3. Each band of at least 2 m² tries every orientation of the computation (15° steps,
   edge directions, the band's own axis). It is only reoriented if it
   saves at least 3 U-turns and its cost drops: length + 2.7 m per
   U-turn (≈ 10 s measured on the robot) + 6 m per extra link.
4. The other bands stay identical to Zigzag, in a continuous zigzag.
5. If the computation falls back to the reference orientation (area loss), that
   reference also gets its reoriented bands before falling back to pure zigzag.
6. Final safeguard (`CoverageEngine.planOptimized`): the complete mission, with its
   real links, is only kept if its score, with pivot durations measured
   on the robot (27 °/s, 1.5 s per stop), is at least as good as that of plain
   zigzag, within 0.2 area points. Otherwise the zigzag is kept.

A reoriented crumb (0.3 m² between the small no-go zones of Fosse) disrupted the order
of neighboring cells and caused the whole plan to be rejected: hence the area
and saved-U-turn thresholds.

Measurements (JVM tests, reference map):

| Zone | Zigzag | Optimized |
|---|---|---|
| L 30 × 6 m + 6 × 20 m (0.25 m) | 99 U-turns, 4125 s | 38 U-turns, 3852 s (−6.6 %) |
| Fosse 0.25 m | 107 U-turns, 5080 s | 101 U-turns, 5028 s — chimney along the height |
| Fosse 0.15 / 0.20 / 0.30 m | 186 / 137 / 95 U-turns | 174 / 132 / 89 U-turns |
| Fosse 0.35 m | whole zigzag at 75° (almost vertical) | identical: nothing to reorient |
| Figuier 0.25 m | 87 U-turns, 2428 s | 69 U-turns, 2371 s |
| Jardin d'Eden, Aire de jeux, Piscine, Enfer, Potager | — | identical |

Durations above: default computation model (45 °/s, no stop), which underestimates
the real cost of a U-turn; the real gain is therefore higher.

Checks: `OptimizedModeTest` (mixed L, unchanged rectangle, production pipeline
validated and deterministic, Fosse with vertical chimney and the rest along the
length at 0.25 and 0.30 m, recorded zones never worse, mission
resume); `ReferenceMapPlannerTest` also covers the mode (route safety).
