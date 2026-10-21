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
are poorly served by this preparation (0.6 m margin of painted zones): the finishing phase (roadmap) will need a
dedicated route.

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
