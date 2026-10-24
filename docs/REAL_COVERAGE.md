# Real coverage (CoverageMap)

What a mowing actually mowed, measured from the recorded track rather than from the prepared route. User-facing
description: [task history → real coverage](features/mowing-history.md#real-coverage) and
[map → real coverage layer](features/map.md#real-coverage-layer).

| | |
|---|---|
| **Code** | `history/CoverageMap.kt` (`CoverageMap`, `CoverageBuilder`, `ZoneCoverage`, `CoverageGap`), `history/RealCoverage.kt` (published layer, image, labels), `planning/RasterCoverage.kt` (`RasterCoverage.Grid`, shared with the planner measure), `ble/RobotBle.kt` (`coverageBegin`, `coverageAdd`, `coveragePublish`, `coverageRestore`), `map/PaintedZone.kt` (`prepare(map, area, …)`) |
| **Tests** | `CoverageMapTest` (stripes, skipped stripes, phases and track breaks, zones and no-go, incremental builder, image, gaps planned and closed on the reference map), `HistoryComposeTest` (task detail), `MapSettingsTest` (layer defaults) |

## Measure

- **Input**: the task track (`TaskTrack`): rotation centre positions, the same point as the prepared stripes, with
  their activity. Only `TrackPhase.CUTTING` counts (blades reported running, or presumed running while the robot has
  not reported them).
- **Target**: the task zones (`TrackMeta.zoneIds`, the `Preview.zoneIds` frozen at start; all map zones for a track
  recorded before) minus the no-go zones. Cell centres inside the target are measured (even-odd rule, as for the
  planner).
- **Grid**: `RasterCoverage.Grid`, 10 cm cells (`CoverageMap.CELL_M`), enlarged beyond 4 million cells. Each cell
  holds the `TARGET`, `COVERED` and `REPEATED` bits.
- **Sweep**: between two consecutive positions both `CUTTING`, in time order and not broken
  (`TaskTrackPieces.broken`: more than 3 m or 60 s apart), the band of half the cutting width (`Geometry.CUT_WIDTH_M`,
  0.40 m) plus the two end discs is swept. A cutting position without a joinable predecessor stamps its disc only. A
  covered cell swept again becomes `REPEATED`, except around the start of a segment continuing the previous one (same
  temporal rule as the planner measure).
- **Shares**: per zone (`ZoneCoverage`: measured area, covered, repeated) and overall; percentages are rounded down
  in the interface.

## Gaps

`CoverageMap.gaps()` returns the unmowed target cells after a morphological opening of 10 cm (`GAP_OPENING_M`, square
kernel of 3 cells: strips narrower than about 30 cm disappear — edges the blades cannot reach along a boundary, raster
noise between two passes), grouped in 4-connected pieces of at least 0.25 m² (`MIN_GAP_M2`). Each gap carries its
area, an interior point, the zone holding most of it and a JTS polygon (union of the row runs, simplified at
0.75 cell, valid).

`CoverageMap.gapArea()` is the union of the gaps, a planner target: `PaintedZone.prepare(map, area, …)` clips it to
the zones, splits it into simple temporary zones and plans and prepares it exactly like a painted debug mowing (same
automatic planner, navigation and route checks on the unchanged robot map). `CoverageMapTest` leaves a 3 m band
unmowed across Aire de jeux on the reference map, plans its gaps, replays the blades-on edges of the prepared mission
into the track and checks that the coverage rises and less than 20 % of the gap area is left. Narrow gaps (about 1 m)
are poorly served by this preparation: the edge of a temporary zone counts as a recorded outline, so the lap and the
passes stay inside the gap.

## Finishing

`planning/FinishingPass` turns the gaps into a dedicated finishing route:

- **Offer** (`FinishingPass.offer`): finished mowing (`MowingOutcome.COMPLETE`) with a measured coverage, gaps totalling
  at least `MIN_OFFER_M2` (1 m²) and at most `MAX_GAP_SHARE` (30 %) of the task zones (beyond, resume or mow again).
  Computed with the score in the end-of-mowing report (`rememberMowingAnalysis`) and in the task sheet.
- **Target** (`FinishingPass.target`): `gapArea()` buffered by the lap inset of a recorded outline (`targetInsetM`:
  half width + outer margin) plus `OVERLAP_M` (0.2 m). The lap of each temporary zone then runs 0.2 m outside the gap,
  on its ragged edge, and the zigzags cover the gap; where the gap touches the zone boundary, the clipping to the zones
  makes that lap the usual border lap. Nearby gaps merge into a single piece.
- **Preparation** (`FinishingPass.prepare`): `PaintedZone.prepare(map, target, …, borders = true)` with temporary
  zones named "Finishing n", then `check`: every cutting path against `TrajectoryEnvelope(engine.routes.physicalSurface)`
  (the surface the BLE route follows) and the whole route length against a new mowing of the task zones (area /
  spacing, cutting only). Refused when a pass collides or when the ratio exceeds `MAX_ROUTE_RATIO` (0.5).
- **Launch**: `FinishingDraft` (UI state, per map) set from the report for the connected robot of that mowing with its
  complete map; Service → Trial mowing then targets the gaps, shows the route length, its share of a new mowing and
  the envelope result, and starts it like a painted zone (`startRobotRoute`).

`FinishingPassTest` leaves a 1 m and a 3 m band unmowed across Aire de jeux on the reference map: the 1 m band is closed
entirely, the 3 m band to 2 % (what is left lies along the boundary), routes of 79 m and 168 m against 636 m for a new
mowing; the former painted preparation of the 1 m band leaves more. Variants measured before choosing: without a lap,
3.8 m² stayed along the boundary of the 3 m band (passes 0.55 m from the edge); a lap with no overlap refused the 1 m
band (empty target after the inset).

## Live layer

At the start of a mowing (`historyBegin` → `trackBegin`, `TaskKind.MOW` only), `RobotBle` creates a `CoverageBuilder`
on the task map and zones; on a resume, the positions already saved are swept in a separate run (`addRun`). Every
position kept by the track sampler is added on the main thread (a few cells per segment); an immutable snapshot is
published to `RealCoverage.flow` at most every 2 s, and once more, marked finished, when the track stops. At launch,
the coverage of the latest finished mowing with a recorded context is recomputed in the background and published
unless a mowing already published its own.

The map (`MapScreen`, in its isolated content: register limit) and the replay map (`MapLive(coverage = …)`) draw the
snapshot as an ARGB image of one pixel per cell (`CoverageLayerStyle.image`, nearest-neighbour scaling), over the
planned stripes: covered in translucent dark green, repeated darker, gaps in orange only once the task has ended.
The layer is shown only when one of its zones belongs to the displayed map, and never in the demo.

## Limits

- Coverage is only as good as the reported position and blade state; a position hole longer than 3 m or 60 s is not
  bridged (conservative).
- The cut is assumed to be 40 cm wide and centred on the rotation centre, like the planner model.
- Cells are 10 cm: expected error in the order of one cell along the edges, about 1 % of a small zone.
