# New mowing patterns: Zamboni, Spiral and pass orientation

Design note, written at main 7e1ca9c. It adds, next to the existing modes Zigzag (`CoverageMode.LINES`), Optimized
(`OPTIMIZED`) and Checkerboard (`CHECKERBOARD`):

1. a **Zamboni** mode (`ZAMBONI`): the passes of each regular cell are ordered by a sliding-window dynamic programme so
   that the robot makes rolling U-turns between passes that are far enough apart, and the mowed front keeps advancing;
2. a **Spiral** mode (`SPIRAL`): passes parallel to the contour, joined into an inward spiral;
3. an **orientation** task setting: *Automatic* (today), *Change every session*, *Manual*.

Delivery order, each step shippable alone: **(1) orientation**, **(2) Zamboni**, **(3) Spiral**.

Project rules that bind this work (`RULES.md`): one planning brick shared by preview, simulation and real mowing; continuous
mowing (finish the started cell, virtual sweep bar); start nearest to the robot, end at the base; safe links only;
`ReferenceMapPlannerTest` on every zone of the reference map; documentation and `CHANGELOG.md` `Unreleased:` in the same
commit; every displayed text through `tr("EN", "FR")`; code, comments and docs in English; no version bump.

## 1. Where it plugs in (line numbers at 7e1ca9c)

| Concern | Place | Today |
|---|---|---|
| Mode enum | `map/CoveragePlanner.kt:14` | `LINES, CHECKERBOARD, TOUR, SURVEY_GRID, OPTIMIZED`, `label`, `isSurvey` |
| Orientation candidates | `CoveragePlanner.kt:25` `SweepOptions(angles, phase, decompose)`; `:557` default angles; `:576-583` selection of the cheapest; `AutomaticPlanner.kt:101-157` per-zone call with 6 longest edges + 15° grid | one best angle per zone, phase 0.5 |
| Sweep and cells | `CoveragePlanner.kt:346-391` `sweep(angle)` (inner `SweepLine(path, cell)`) | rows of `spacing`, cells by row continuity |
| U-turn join | `CoveragePlanner.kt:460-479` `join(cellLines)`; `:480-506` `route(lines, angle)`; `:493-497` LAWN skip | best `TurnOptimizer` candidate by `turnStyle` score, checked on `safe` and `envelope` |
| Turn physics | `map/TurnOptimizer.kt:111` `candidates`, `:138` `best`, `:320-349` `TurnOrder` | `FOLLOW_RADIUS_M = 0.35`, U needs 0.7 m between passes |
| Engine entry | `RoutePlanning.kt:20-23` `calculate`; `CoverageEngine.kt:24-28` `planPattern`; `AutomaticPlanner.kt:18` `plan(input, mode, checkpoint)` | every non-LINES mode except OPTIMIZED goes to `AutomaticPlanner.plan(mode)` |
| Coverage floor | `AutomaticPlanner.kt:174-201` reference orientation, `0.002 * target.area` (0.2 point), fallbacks | |
| Multi-zone merge | `map/MultiZonePlanner.kt:11-27` `combine` (`zoneIds`, `zoneAngles`) | |
| Preview model | `map/Geometry.kt:114` `Preview` | persisted by `simulation/MissionCheckpoint.kt:331-353` |
| Config | `planning/PlanningModel.kt:18-34` `PlanningConfig`, `:52` `PlanningRequest`, `:72` `snapshot()` | |
| Task settings | `map/MowingTaskSettings.kt:14-111` (data, `sanitized`, `MOWING_MODES` at `:73`, prefs `read`/`write`) | prefs file `mowing_task` |
| Form and UI | `map/MapScreenState.kt:69-125` `MowingTaskForm` (positional `settings()` at `:103`, saver list); `map/MapScreen.kt:306-310` `planningInputs`, `:484-488` timing JSON, `:511-516` `PlanningConfig(...)`, `:535` `PlanningRequest(...)`; `map/MowingTaskPages.kt:100-107` mode tiles, `:163` turn style toggle, `:223-228` preview "Route" group (the `when (form.mode)` at `:226` has an `else -> "Zigzag"`) | |
| Run history | `ble/RobotBle.kt:2656-2700` `historyBegin`, `:2915-2930` `historyEnd`, `:841-863` stores in `app.filesDir` | `MowingRecord` has zone names, no angle |
| Order inside a zone | `planning/LocalMowingOrder.kt` (a run can be entered at either end, the started cell is finished first, sweep bar between cells) | |

Two facts that shape the design:

* In production `CoveragePlanner.plan` is called with `continuous = true`, so `nearBase` does not split runs: the start is
  chosen by `LocalMowingOrder` among run ends. A Zamboni run or a spiral may therefore be driven from either end (the
  reversed path is a valid forward path, only mirrored).
* `NeighborhoodSequencer` is not called for the pattern modes in production (only by the research path `planInternal`),
  so no later step reorders passes inside a Zamboni cell.

## 2. Common contracts

### 2.1 Enums and flags

```kotlin
enum class CoverageMode { LINES, CHECKERBOARD, TOUR, SURVEY_GRID, OPTIMIZED,
    ZAMBONI("Zamboni", "Zamboni"), SPIRAL("Spiral", "Spirale") }   // appended: persisted by name
val CoverageMode.usesOrientation: Boolean   // LINES, ZAMBONI, CHECKERBOARD

enum class OrientationMode(en, fr, enHint, frHint) {                // new file map/Orientation.kt
    AUTOMATIC("Automatic", "Automatique"),
    EACH_SESSION("Change every session", "Changer à chaque séance"),
    MANUAL("Manual", "Manuelle") }
```

`MowingTaskSettings.MOWING_MODES` (`:73`) must list the new modes, otherwise `sanitized()` brings them back to Zigzag.
`ReferenceMapPlannerTest` iterates `CoverageMode.entries` minus the survey modes, so the new modes join it automatically.

### 2.2 Settings and persistence

* `MowingTaskSettings(orientation: OrientationMode = AUTOMATIC, manualAngleDeg: Int = 0)`; prefs keys `orientation_mode`
  (enum name, `runCatching` + default) and `manual_angle_deg` (int); `sanitized()` folds the angle with
  `((a % 180) + 180) % 180`. `MowingTaskForm.settings()` is a positional call: switch it to named arguments.
* `MowingTaskForm`: `orientation`, `manualAngle`; add both to the saver list at indexes 19 and 20 (read with
  `getOrNull`); add both to `planningInputs` (`MapScreen.kt:306`) so that a change invalidates the preview, and to the
  timing JSON (`:484`).
* `PlanningConfig(orientation: OrientationMode = AUTOMATIC, manualAngleDeg: Int = 0)` with
  `require(manualAngleDeg in 0..179)`. `PlanningRequest(orientationMemory: Map<Long, ZoneOrientation> = emptyMap())`;
  `snapshot()` copies it sorted by key (`toSortedMap()`, like `corridorWidthsM`) so that `hash` stays stable.
* `Preview` gets two optional fields, aligned with `zoneIds`/`zoneAngles`: `zonePhases: List<Double> = emptyList()` and
  `zoneModes: List<CoverageMode> = emptyList()`. `CoveragePlanner.plan` returns lists of size 1,
  `MultiZonePlanner.combine` concatenates them (`flatMap`, as for `passes`). `MissionCheckpoint.encode/decode` write them
  and read them with `optJSONArray` (old saves decode with empty lists).

### 2.3 What the UI shows

* A new **Direction of the passes** group in Task settings, under *Route mode*: a three-way segmented control, and for
  *Manual* a slider 0 to 175° in 5° steps ("35°"). Strings through `tr`.
* For Optimized and Spiral the group stays visible but disabled, with the note
  `tr("Not used by Optimized and Spiral: the direction follows the shape.", "Non utilisé par Optimisé et Spirale : la direction suit la forme.")`.
  The saved choice is kept for the next Zigzag.
* The preview "Route" group (`MowingTaskPages.kt:223-228`) gets a *Direction* row per zone from `Preview.zoneAngles`
  ("Piscine 35° · Figuier 120°"), a "(shifted by 5 cm)" suffix when `zonePhases[i] != 0.5`, and a warning row when any
  `zoneModes[i]` differs from the requested mode ("Spiral not possible on Figuier: zigzag used").
* The *Lawn friendly* toggle (`:163`) is disabled with a subtitle for Zamboni (fixed objective) and Spiral (no U-turns).
* The `when (form.mode)` label at `:226` gets explicit Zamboni and Spiral branches.
* Angle convention: the planner angle is the direction of the passes (`Preview.angle`, counter-clockwise from the map's
  x axis, 0..179), shown as is (see section 8).

## 3. Step 1: orientation

Split in two shippable commits: **1a Manual**, then **1b Change every session**.

### 3.1 Manual (1a)

*Choice*: one angle for the whole task, not one per zone. Justification: the user's reason for choosing an angle is
usually the look of the stripes (parallel to the house, same direction in every zone), and the task settings page is
where that choice belongs; a per-zone editor needs a new screen. The preview displays the angle per zone, so a poorly
fitting zone is visible. A per-zone override (saved like `ZoneStartStore`) is a later extension.

* `AutomaticPlanner.kt:101-116`: when `c.orientation == MANUAL` and `mode.usesOrientation`, `angles = listOf(c.manualAngleDeg
  % limit)` (`limit` is 180, or 90 for Checkerboard where the two families are `a` and `a+90`). A single candidate means
  about 12 times fewer sweeps.
* The reference orientation (`:118-123`) is then the same preview, so `sameFootprint` is true and there is no silent
  fallback to another angle: the user's angle is never replaced. If it cannot be mowed (thin strip, coverage under the
  `minimumCoverage` threshold), the existing mechanism returns an invalid or `PARTIAL_COVERAGE` result, the preview is
  `localOnly` and *Start* stays disabled, with the diagnostics shown. Wrap the sweep failure message:
  `tr("Manual angle %d° cannot be mowed on %s: %s. Use Automatic.", "Angle manuel %d° impossible sur %s : %s. Utilisez Automatique.")`.
* Zamboni and Zigzag use the same code path. Optimized and Spiral skip it (`usesOrientation` false).

### 3.2 Change every session (1b)

Memory, per zone, of what was **actually mowed**:

```kotlin
// planning/ZoneOrientationMemory.kt (new)
data class ZoneOrientation(val directions: List<Int>,   // 1 value (lines), 2 (checkerboard: a, a+90), each 0..179
                           val phaseIndex: Int,         // index in OrientationRule.PHASES
                           val shapeKey: String,        // stableHash of the zone points rounded to 1 cm
                           val mowedAtMillis: Long)
class ZoneOrientationStore(directory: File) {           // zone-orientations.json, atomic write like MowingHistoryStore
    fun read(): Map<Long, ZoneOrientation>; fun save(all: Map<Long, ZoneOrientation>) }
object ZoneProgress {                                    // pure, JVM-testable
    fun shares(preview: Preview, map: RobotMap, doneMeters: Double): Map<Long, Double>   // share of each zone's cutting metres done
    fun update(memory: Map<Long, ZoneOrientation>, preview: Preview, map: RobotMap, doneMeters: Double,
               now: Long): Map<Long, ZoneOrientation> }  // records zones with share >= 0.5 whose mode usesOrientation
```

* Hook: `RobotBle.historyBegin` computes and keeps the plan reference from `engine.preview` and `engine.map` (for a real
  mowing, resumed or not, since both paths pass through it); `historyEnd` (`:2915`) calls `ZoneProgress.update` with
  `final.doneMeters` and sends the result to a conflated write channel like `historyWrites` (`:842`). Only `TaskKind.MOW`.
  An interrupted mowing records the zones mowed at 50 % or more; a zone mowed less keeps its previous entry.
* Zone of each cutting path: by construction, `Preview.pathZoneIds` set by `MultiZonePlanner.combine` and kept through
  `CutPasses.expand` and the reorderings (saved in the checkpoint), never from the geometry of overlapping zones; an older
  checkpoint without it falls back to the zone holding most of the path's length.
* An entry whose `shapeKey` no longer matches the zone (retraced, enlarged) is ignored.
* `MapScreen` reads the store when it builds the request (`PlanningRequest.orientationMemory`) and exposes its content to
  `planningInputs`.
* Optimized and Spiral never write an entry (no single direction). Zigzag, Zamboni, Checkerboard do.

Selection rule, a pure function in `map/Orientation.kt`:

```kotlin
class OrientationRule(val avoid: List<Int>, val phase: Double,
                      val minSeparationDeg: Int = 45, val maxExtraCost: Double = 0.05) {
    companion object { val PHASES = listOf(0.5, 0.2, 0.8); fun nextPhaseIndex(i: Int) = (i + 1) % PHASES.size
        fun distance(a: Int, b: Int): Int            // min(|a-b| mod 180, 180 - that), 0..90
        fun extraAngles(avoid: List<Int>): List<Int> }   // (y+45) % 180 and (y+135) % 180, added to the candidates
    fun separation(angle: Int, family: Int): Int      // min distance over {a} or {a, a+90} against avoid
    fun <T> pick(candidates: List<Candidate<T>>): Candidate<T>?   // cheapest with separation >= min and cost <= best*(1+cap); null if none
}
```

* `CoveragePlanner.plan(..., orientationRule: OrientationRule? = null, checkpoint: () -> Unit = {})`: in the selection
  loop (`:576-583`) keep every `(candidate, cost)`; after it, `rule.pick(all)`; if null, re-run `route(sweep(autoAngle, rule.phase), ...)`
  (`sweep` takes a `phase` parameter instead of reading `sweepOptions?.phase` only) so the angle stays the automatic one
  and the rows shift (0.5 → 0.2 → 0.8, 5 to 7.5 cm at 25 cm spacing).
* `AutomaticPlanner` builds the rule per zone from `request.orientationMemory[zone.id]` (and `shapeKey`), adds
  `extraAngles(avoid)` to `angles` (`:109-112`) and sets the new phase index in the result (`Preview.zonePhases`).
* Why a memory-based separation rather than a fixed cycle 0/45/90/135 relative to the automatic angle: the automatic angle
  can move between sessions (map edit, obstacles), which would break a cycle; the memory rule only needs the last
  direction. The cap is applied on the candidate cost used for ranking (`AutomaticPlanner.kt:125-144`); the acceptance
  below checks it on the real mission duration.
* Checkerboard against a previous Checkerboard can only be separated by exactly 45 (both families at least 45 apart): the
  `extraAngles` make it a candidate. A previous Checkerboard followed by Zigzag is the usual case.

### 3.3 Files, tests, docs, acceptance (step 1)

Files: `map/CoveragePlanner.kt`, `map/Orientation.kt` (new), `map/MultiZonePlanner.kt`, `map/Geometry.kt` (`Preview`),
`map/MowingTaskSettings.kt`, `map/MapScreenState.kt`, `map/MapScreen.kt`, `map/MowingTaskPages.kt`, `planning/PlanningModel.kt`,
`planning/AutomaticPlanner.kt`, `planning/ZoneOrientationMemory.kt` (new), `simulation/MissionCheckpoint.kt`, `ble/RobotBle.kt`.

Tests:
* `OrientationRuleTest` (distance mod 180, separation with one or two directions, cap at 5 %, `extraAngles`, phase cycle,
  determinism).
* `MowingTaskSettingsTest` (round trip, `sanitized` of angle 200 and of unknown mode names, old file without the keys).
* `ZoneOrientationStoreTest` (round trip, corrupt file ignored, `shapeKey` change ignores the entry) and `ZoneProgressTest`
  (zone attribution, 50 % threshold, resumed task, interrupted task).
* `MissionCheckpointTest`: `zonePhases`/`zoneModes` round trip and an old save without them.
* Synthetic: rectangle and L shape with Manual at 0, 37, 90, 143: `Preview.zoneAngles` all equal the angle (mod 90 for
  Checkerboard); thin strip at the wrong angle gives an invalid, `localOnly` result with the message.
* Reference map (`ReferenceMapPlannerTest` extended, plus a new `OrientationReferenceTest` through
  `RoutePlanning.calculate`): 7 zones × {Zigzag, Checkerboard} × Manual {0, 37, 90, 143}: `geometricViolations == 0`; and
  Change every session with memory = the automatic result of the previous run, four sessions in a row.

Acceptance (numbers are targets to confirm on the first measurement):
* Manual: the angle used is the requested one on every zone, or the plan is flagged invalid; never silently replaced.
  Computation time at most 0.2 × the Automatic time of the same request.
* Change every session: for every zone either separation ≥ 45° with `durationSeconds` ≤ 1.05 × Automatic and coverage ≥
  Automatic − 0.2 point, or the automatic angle with a different phase; over four consecutive sessions the pair
  (angle, phase) of a zone never repeats twice in a row; computation time ≤ 1.15 × Automatic.
* Automatic is byte-identical to today (same `planHash` on the reference map).
* No new geometric violation anywhere; build, tests and lint pass.

Docs (same commit): `docs/features/mowing-preparation.md` (settings table row, new section on direction, "How the route
is computed" item 2), `docs/path-planning/PARAMETERS.md` (new config fields), `docs/path-planning/ENGINE.md` (orientation
policy, memory), `README.md` if the setting is cited, `CHANGELOG.md` `Unreleased:`.

## 4. Step 2: Zamboni (sliding-window order)

### 4.1 Idea

In a regular cell the passes are ranks `0 .. n-1`. A rolling U-turn needs the two passes at least `2 * FOLLOW_RADIUS_M =
0.7 m` apart, so consecutive passes must be at least `m = TurnOrder.step(spacing, radius)` ranks apart (`m = 3` at 25 cm).
The existing *Lawn friendly* order takes every m-th pass over the whole cell and leaves the cell striped until the last
sweep. Zamboni instead keeps a window `W` ranks wide: a pass is allowed only if every pass more than `W` ranks behind it
is done. With `k = m + 1` (4 at 25 cm) and `W = min(2k, 12)` (8 at 25 cm) the cheapest order is the alternation `+k, -m`
(net progress of one rank per two passes), which gives, at 25 cm:
`0,4,1,5,2,6,3,7,11,8,12,9,13,10,14,18,15,...`. Every move is a rolling U-turn (1.0 m then 0.75 m lateral gap) and the
unmowed part is always one block with a ragged edge at most `W` ranks wide: a stop at any moment leaves no isolated stripe.

### 4.2 Contracts

```kotlin
// map/ZamboniOrder.kt (new), next to TurnOrder
object ZamboniOrder {
    const val MAX_WINDOW = 12; const val MAX_STATES_PER_LAYER = 4096; const val STEPS_ABOVE_M = 2
    fun window(spacing: Double, radius: Double): Int      // min(2 * (TurnOrder.step + 1), MAX_WINDOW)
    /** Passes of one cell in front-advancing order, directions alternating; null when the cell is not a regular ladder,
     *  has fewer than 2 * m + 2 passes, needs a window larger than MAX_WINDOW, or no complete sequence exists. */
    fun <T> order(group: List<T>, spacing: Double, radius: Double, path: (T) -> List<Point>, with: (T, List<Point>) -> T,
                  transition: (aFrom: Point, aEnd: Point, bStart: Point, bTo: Point) -> Double?,   // null = not drivable/verified
                  checkpoint: () -> Unit = {}): List<T>?
}
```

* The ladder test is the `regular` check of `TurnOrder.reorder` (`TurnOptimizer.kt:338-343`), extracted into a shared
  helper (`TurnOrder.ladder(...)`) used by both: every path has 2 points, directions alternate, offsets differ by one
  spacing ± 30 %. Staggered ends are fine.
* State: `(f, mask, last)` where `f` is the first undone rank, `mask` the done passes among `f+1 .. f+W` (a `Long`), `last`
  the last pass; the number of passes done is `t = f + popcount(mask)`, so the direction of every pass is known from
  `t` and the initial direction. The initial pass is rank 0 in both directions; the scheduler may drive the run reversed.
* Moves from `last`: `±1` (pivot U-turn, last resort) and `±m, ±(m+1), ±(m+2)` (rolling), to an undone rank `q` with
  `q <= f + W`. Cost of a move = `transition(...)`, memoized per `(rank, direction, rank)`, so about `n × 8 × 2` calls of
  `TurnOptimizer.candidates` per cell. Exact DP; if a layer exceeds `MAX_STATES_PER_LAYER`, keep the cheapest states
  (beam), deterministic tie-break on rank.
* **Objective**: the `TurnStyle.LAWN` score (`0.25 × duration + 0.06 × wear`), whatever the *Lawn friendly* toggle says.
  Reason: with the FAST weights the DP's search space contains the plain zigzag order, whose pivot U-turns can be cheaper
  than a 1 m rolling U, so the mode would degenerate to Zigzag. "Duration plus wear" is the stated objective of the mode.
* Integration in `CoveragePlanner.route(lines, angle, front: Boolean = false)` (`:480-506`), at the `join(cellLines)`
  call (`:493`): for `mode == ZAMBONI && front`, `ZamboniOrder.order(cellLines, spacing, FOLLOW_RADIUS_M, ...)` gives the
  order, then `join` assembles it with the style LAWN and a memoized `turn(last, run, style, kinds)` function shared with
  the DP (the same `TurnOptimizer.candidates` filter + `safe` + `envelope.pathCollision` check as today, so no unverified
  U-turn); the LAWN skip (`:494-497`) is not applied. If the DP returns null or the joined result has more runs than the
  zigzag, the zigzag order of the cell is kept. A concurrent map is needed for the memo (orientations run in parallel).
* Cost control: orientations are ranked as today with the zigzag join (`route(front = false)`); the DP then runs only on
  the 3 cheapest orientations (and on their reversed order by mirroring the result instead of re-running it), and the
  cheapest after Zamboni wins. `plan()` gets a `checkpoint: () -> Unit` parameter called every layer, so cancellation works.
* Irregular cells and spacings with `W < k + 1` (spacing under about 0.09 m) keep the zigzag order, with no message
  (the mode silently equals Zigzag there; `Preview.zoneModes` stays ZAMBONI because the pattern is requested and applied
  wherever it fits).
* `planPattern` (`CoverageEngine.kt:24-28`) needs no change; `AutomaticPlanner` reference: Zamboni passes are the same
  as the zigzag ones, so the 0.2-point floor and the fallback to the reference orientation keep their meaning.

### 4.3 The "stop" metric

Test-side helper `PassOrderMetrics` (promotable to main if the UI ever shows it), pure geometry on the swept union:

* `contiguousShare(path, zone, cut, stopPercent)`: area of the largest connected component of the swept union of the first
  `stopPercent` % of the cutting metres, divided by the area of the whole union.
* `unmowedFragments(path, zone, cut, stopPercent)`: number of connected components of the zone minus the swept union
  (minus the cells not yet started).
* `frontWidthRanks(order)`: for rank-based orders, `max(highest done rank - first undone rank)` over time.
* Reported at 10, 25, 50, 75, 90 %, for Zigzag, Lawn friendly (skip order), Zamboni, Spiral. Expected shape: Zigzag and
  Spiral about 1.0 contiguous and 1 fragment; Lawn friendly low and many fragments; Zamboni `frontWidthRanks <= W` and a
  mean `contiguousShare` close to 1.

### 4.4 Files, tests, docs, acceptance (step 2)

Files: `map/CoveragePlanner.kt`, `map/TurnOptimizer.kt` (`TurnOrder.ladder`), `map/ZamboniOrder.kt` (new),
`map/MowingTaskSettings.kt` (`MOWING_MODES`), `map/MowingTaskPages.kt` (tile, labels, toggle states),
`planning/AutomaticPlanner.kt` (nothing but a possible reference check), `simulation/MissionCheckpoint.kt` (mode name only).

Tests:
* `ZamboniOrderTest` (pure, synthetic `transition`: cost `1 + 0.1 × gap` for gap ≥ m, 3 for gap 1, null otherwise): every
  rank visited once; window rule respected at every step; all moves ≥ m ranks except at most 2 closing moves; the first 15
  ranks at 25 cm equal `0,4,1,5,2,6,3,7,11,8,12,9,13,10,14` (confirm at implementation: the invariants matter more than
  the exact string); null on irregular input and on `n < 2m + 2`; spacing 0.05 gives null; deterministic.
* `CoveragePlannerTest` additions: rectangle and a zone with a hole, Zamboni versus Zigzag: same pass set (same covered
  area within 0.2 point), `connectors` equal, runs ≤ Zigzag runs, every run passes `TrajectoryEnvelope.pathCollision == null`.
* `ReferenceMapPlannerTest` runs automatically for `ZAMBONI` (7 zones × spacings 0.05, 0.25, 0.35 × margins × border
  passages). Its "start nearest to the station" assertion holds because `Geometry.plan` (non-continuous) still calls
  `nearBase`; a Zamboni run may be split there only in this research path, never in production (`continuous = true`).
* `ZamboniReferenceTest` (like `TurnStyleReferenceTest`): for each zone at 25 cm print and assert, against Zigzag FAST and
  against Lawn friendly: degrees pivoted in place, wear, pass duration with `TurnOptimizer.drive`, rolling U-turn count,
  mowed area (swept union), stop metric. Targets: mowed area ≥ Zigzag − 0.2 point; pivot degrees ≤ 0.35 × Zigzag FAST;
  wear ≤ 0.6 × Zigzag FAST; pass duration ≤ 1.10 × Zigzag FAST and ≤ Lawn friendly; at least 80 % of the transitions of
  regular cells are rolling; `contiguousShare` mean ≥ 0.9 at 25, 50, 75 %; `frontWidthRanks ≤ W`.
* `RoutePlanning.calculate(request, ZAMBONI)` on each reference zone: `selected.valid`, `geometricViolations == 0`, same
  `planHash` twice (deterministic), coverage ≥ Zigzag − 0.2 point, mission duration reported against Zigzag.
* Compute time (same JVM, after warm-up): Zamboni ≤ 2.0 × Zigzag on every zone and ≤ 3 s per cell for the DP; the
  largest cell of the map (Piscine) measured and printed; a hard cap of 4096 states per layer.
* `RollingTurnExecutionTest` style bench: tracking simulation of a Zamboni mission on two zones: no pivot in the middle of
  a pass, largest deviation within the 21 cm of today's rolling U-turns.
* `MissionCheckpointTest` / `SmartResume` tests with a Zamboni preview (long arc runs): resume at an edge in the middle of
  an arc and in the middle of a pass.

Docs: `docs/features/mowing-preparation.md` (mode table, U-turns section, settings table), `docs/path-planning/ENGINE.md`
(new "Zamboni order" section with the measured table), `docs/path-planning/ALGORITHMS.md` (ordering), `docs/path-planning/PARAMETERS.md`
(the sentence "A non-zero minimum turning radius is refused" is already out of date since the Turn Optimizer), `README.md`
where modes are listed, `docs/FEATURES.md:35`, `CHANGELOG.md` `Unreleased:`.

### 4.5 As built (2026-10-27)

Differences from the contracts above, and the measured values that replace the targets of 4.4 (details and table:
[ENGINE.md, Zamboni order](ENGINE.md#zamboni-order)):

* `ZamboniOrder.order(..., transition, breakCost, checkpoint)`: a move whose U-turn is not drivable is a break at
  `breakCost` (60, like the join) instead of being forbidden. Without it no order was found in most cells of the
  reference map, whose zigzag itself is cut by breaks (Potager: 13 runs for 32 passes).
* After the join, the Zamboni order of a cell is kept only with no more runs **and no higher cost** than the zigzag
  order of the cell (the search estimates its U-turns on whole passes).
* `LocalMowingOrder`: the runs of a Zamboni cell are entered at the first run forwards or the last one backwards and
  driven in their order; otherwise the nearest-run choice broke the front (27 rows on Piscine).
* `CoveragePlannerTest`: more U-turns than the zigzag where Zamboni joins passes the zigzag had to split (rectangle with
  a hole: 52 against 48), so "connectors equal" became "one U-turn per joined pass, at least as many as the zigzag".
* `ZamboniOrderTest`: with the synthetic cost the order starts `0, 3, 6, 2, 5, 1, 4, 7, 10, 13, …` (1.33 per pass)
  rather than `0, 4, 1, 5, …` (1.35); at 10 cm a short cell may need 3 closing moves.
* Measured instead of the targets: pivots ×0.81, wear ×0.94, 38 % of rolling U-turns in the regular cells, pass duration
  up to ×1.08 of Lawn friendly on a zone, contiguous share 0.71 to 1.00 (mean 0.89), computation ×0.95 to ×1.67 per zone
  (×1.24 on the map). Cause: on the reference map most pass ends lie along slanted recorded borders, where a rolling U
  between passes three rows apart needs more shortening than its arcs sweep (`TurnOptimizer.MAX_TRIM_M`); square to
  the border every U-turn of a regular cell rolls. Met: coverage, front ≤ W, pass duration ≤ 1.10 × Zigzag,
  determinism, no violation, ≤ 3 s per cell, deviation ≤ 21 cm on the tracking bench, other modes unchanged.

## 5. Step 3: Spiral

### 5.1 How it plugs in

The spiral replaces the cell sweep inside `CoveragePlanner.plan`; it works on the same `inset` polygon as the zigzag (so
the border loops, margins, no-go reserves and the physical-surface intersection apply unchanged, with ring 0 at the
coverage boundary, half a cut inside the last perimeter loop, exactly where the zigzag pass ends stop). One candidate, no
orientation search (cheaper than Zigzag). The result is `SweepLine`s, each one a whole spiral chain with its own cell id,
so `join` returns one run per chain, `route`/`LocalMowingOrder`/`MissionEvaluator` need no change, and the "finish the
started cell, then the sweep bar" rules apply between chains. Orientation does not apply. `Preview.angle` is 0.
`Preview.passes` receives the long polylines (drawn as bands by `MapRouteCanvas` and `MowingPreview` as is).

```kotlin
// map/SpiralSweep.kt (new)
internal object SpiralSweep {
    class Result(val chains: List<List<Point>>, val pockets: JtsGeometry)       // pockets: still to be filled in zigzag
    fun build(inset: JtsGeometry, spacing: Double, cutWidth: Double, radius: Double,
              start: Point?, valid: (List<Point>) -> Boolean, checkpoint: () -> Unit): Result?   // null = not applicable
}
```

### 5.2 Construction

1. **Components.** Each connected polygon of `inset` is handled alone.
2. **Holes (no-go zones, obstacles).** A component with holes is made simply connected by **slits**: for each hole, the
   shortest segment to the exterior (or to an already connected hole) that stays inside the component, buffered to a
   width of `cutWidth - 0.1 m` (flat caps) and removed. Rings then run along both sides of the slit; since the two ring-0
   lines are about 0.3 m apart the slit strip is covered by their cuts (radius 0.2 m). A component for which no slit fits
   goes to the pockets.
3. **Rings.** `Q_j = opening(P.buffer(-j × spacing), radius)` with `opening(g, r) = g.buffer(-r).buffer(r)` and 23
   segments per quadrant (4° per vertex, like the Turn Optimizer arcs). Convex corners get radius 0.35; reflex corners keep
   the offset arc. A reflex arc under 0.35 is accepted (the tracking pivots there, counted by `TurnOptimizer.drive`) and
   reported as a metric.
4. **Spiral.** Rings are oriented the same way. Ring `j` is driven from its start `s_j` to a point `T` before closing,
   then a slanted segment of along-length `T` and across-length `spacing` leads to `s_{j+1}`, the point of ring `j+1`
   nearest to `s_j`; `T = max(0.8 m, 4 × spacing)`. The starts are placed on a straight edge at least `2T` long when one
   exists. `s_0` is the point of ring 0 nearest to `start`; the scheduler may also take the chain from its inner end.
5. **Splits.** When `Q_{j+1}` has several polygons, the chain stops at ring `j` and each child polygon starts its own chain
   (own cell id).
6. **Pockets.** The core left when rings vanish, lobes removed by the opening, failed components and the corner gaps form
   `pockets = P - union(chains).buffer(cutWidth/2)`. Pockets of at least 0.04 m² are filled in zigzag with
   `MixedSweep.fill(piece, axis, spacing)` as extra cells; passes that are not `valid` are dropped and counted in the coverage.
7. **Checks.** Every chain goes through the same `valid(path)` (safe surface) and `envelope.pathCollision` as the zigzag passes.
8. **Fallback.** If `build` returns null, or the coverage is under the zigzag reference by more than 0.2 point, `plan`
   produces the zigzag candidates instead and returns `zoneModes = [LINES]`. `AutomaticPlanner` computes its `reference`
   from a zigzag plan for the Spiral so the existing floor and fallbacks work; the UI warns from `zoneModes`.

### 5.3 Limits stated up front

* Concave zones are handled by splits and pockets, not optimally.
* Zones with several close no-go zones may fail the slits: pockets or zigzag fallback.
* With spacing above 0.28 m the corner gaps appear; the pockets fill them.
* The perimeter loop and ring 0 are 0.2 m apart: some double mowing along the border.
* No orientation, no *Lawn friendly* effect.

### 5.4 Files, tests, docs, acceptance (step 3)

Files: `map/SpiralSweep.kt` (new), `map/CoveragePlanner.kt`, `map/MixedSweep.kt` (reuse), `planning/AutomaticPlanner.kt`,
`map/MultiZonePlanner.kt`, `map/MowingTaskPages.kt`, `map/MowingTaskSettings.kt`, `simulation/MissionCheckpoint.kt`.

Tests: `SpiralSweepTest` (rectangle, centred hole, L, U split, thin lobe, round zone, start nearest, null input,
TopologyException never thrown); curvature report; `CoveragePlannerTest` Spiral versus Zigzag (coverage ≥ Zigzag − 0.2
point, length ≤ 1.05 ×, zero U-turns); `ReferenceMapPlannerTest` automatic; `SpiralReferenceTest` (spiral kept on at least
4 of 7 zones at 25 cm, every fallback with a reason); `RoutePlanning.calculate(SPIRAL)` valid and deterministic; compute
time ≤ 1.0 × Zigzag; tracking bench; `MissionCheckpoint`/`SmartResume` with a spiral preview.

Docs: `docs/features/mowing-preparation.md`, `docs/path-planning/ENGINE.md`, `docs/path-planning/ALGORITHMS.md`,
`README.md`, `docs/FEATURES.md:35`, `CHANGELOG.md` `Unreleased:`.

### 5.5 As built (2026-10-27)

Differences from 5.1 to 5.4, and the measured values that replace the targets (details and table:
[ENGINE.md, Spiral](ENGINE.md#spiral)):

* `SpiralSweep.build(inset, spacing, cutWidth, radius, start, valid, checkpoint): Result?` returns the chains, the
  pockets and counters (rings, splits, refused rings, slit failures); null when no chain fits or JTS fails. The pocket
  fill is `SpiralSweep.fill(pocket, spacing, valid)` (long axis or every 30°, cells refilled in their own direction),
  not a single `MixedSweep.fill` at the long axis: one direction for a branched core cut it into many short cells.
* Levels are eroded step by step from the outer one, with JTS buffers without input simplification: the default
  simplification cut reflex corners by a few millimetres and put the outer ring outside the area (every chain refused
  on the reference map). The outer ring is 5 mm inside the area.
* The outer ring is driven whole, from `T` before its start; the slants of successive rings are parallel and one spacing
  apart. Starts in the middle fifth of a straight stretch at least half as long as the longest (nearest to the entry
  point): a start near a corner ran the inner starts into the corner arcs (82° slants). When the next start is more than
  two spacings away, the chain stops and the remaining rings start a new chain.
* Pockets are closed by one spacing before being filled: the gaps along a bisector became one strip instead of one detour
  each (from 30 to 50 pockets per zone down to 3 to 20).
* The spiral is compared with the zigzag of the zone computed as in Zigzag mode (same angles and ranking, one more
  parallel task), not with a cheaper zigzag of reference: the coarse one mowed up to 0.25 point less than Zigzag
  (Figuier), which made the fallback worse than the Zigzag mode.
* *Lawn friendly* is forced off in Spiral (pocket U-turns fast), like it is forced on in Zamboni.
* Zamboni (review of step 2): `Preview.zoneReorderedCells` counts the cells of each zone driven in their Zamboni order
  (saved in checkpoints); a zone with none gets "Zamboni on X: no regular cell, zigzag order" in the preview. Plans
  unchanged.
* Measured instead of the targets: computation ×1.24 of Zigzag on the map (target ×1.0), mission duration +5.5 to
  +21.6 %, wear ×0.98 to ×4.3 on the reference map; zero U-turns in the chains only. Met: kept on 6 of 7 zones, coverage
  within 0.10 point, length ×1.003 (rectangle) and ×0.995 (L), determinism, no violation, tracking deviation ≤ 0.20 m,
  other modes unchanged.

## 6. Shared acceptance criteria

* Coverage of the new pattern ≥ Zigzag − 0.2 point on every reference zone and every tested spacing.
* No new geometric violation; every U-turn and every ring goes through `safe` and `TrajectoryEnvelope`.
* Start nearest to the robot, end at the base, started cell finished before another, safe links only: unchanged.
* Determinism: same request, same `planHash`.
* Zigzag, Optimized and Checkerboard with Automatic orientation: identical plans to today.
* Computation time within the budgets of each step, measured in the same JVM against Zigzag.
* Documentation and `CHANGELOG.md` `Unreleased:` updated in the same commit as each step; no version bump.

## 7. Risks

1. **Rolling radius 0.35 m is not validated on the robot** (bench of 2026-10-10 could not follow about 0.3 m). Zamboni and
   Spiral rely on it; if the robot pivots at each arc, their gain disappears. Mitigation: shared `FOLLOW_RADIUS_M`,
   costs from `TurnOptimizer.drive`, experimental label.
2. **DP cost**: bounded by window cap, state cap, top-3 orientations, memoization, per-layer `checkpoint`; else zigzag order.
3. **Spiral geometry**: JTS robustness, slits, splits, corner gaps, tight reflex corners, double mowing along the border;
   all end in pockets or zigzag fallback, never in an unchecked path.
4. **Orientation proxy**: 5 % cap on ranking cost; acceptance measures mission duration; phase-shift fallback.
5. **Memory semantics**: "actually mowed" approximated by ≥ 50 % of the zone's cutting metres.
6. **Reference test size**: two more modes add cases to `ReferenceMapPlannerTest`; measure the suite time.
7. **Interactions to check**: `SmartResume`/`ResumeSkipView` on long arc runs, `CutPasses.expand`, `FinishingPass`,
   history display of the mode label.

Not verifiable without the robot: tracking of 0.35 m arcs and slants on real grass, real wear and duration gains, pass
ends swept by rolling U-turns, effect of phase shifts on lawn tracks.

## 8. Decisions (2026-10-26)

* Angle display: the planner angle in the map frame (0..179°, counter-clockwise from the map x axis), shown as is in
  the settings and the preview.
* Zamboni always uses the *Lawn friendly* objective (duration plus wear); the toggle is disabled in that mode.
* Manual angle: one per task; a per-zone override is a later extension.
* Zamboni and Spiral carry an "experimental" label in the interface until the 0.35 m rolling radius is validated on the robot.
* Numeric thresholds are first targets, replaced by the measured values in each step's docs.
