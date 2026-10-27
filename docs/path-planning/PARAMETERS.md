# Parameters of the software CPP engine

The entry point is `fr.luba.poc.planning.CoverageEngine.plan(PlanningRequest, PlannerStrategy, checkpoint)`.
The core uses Kotlin/JVM and JTS 1.20, already present in the project. It knows nothing about Android, BLE or MQTT.
Simple mode offers Reference, Optimized (phase 2), Advanced (phase 3) and BCD in the preparation screen. The interface's Reference mode keeps the historical path; the others show the results of the common evaluator and remain strictly local. The joint and LNS ablations are available in the API and the benchmark.

## Input contract

`PlanningRequest` contains the effective map, the **ordered** list of zones, the optional start position and heading, the footprint, local obstacles, known corridor widths and available slope regions. A missing start heading means a free initial orientation in the model; it does not certify the robot's real heading. Coordinates are metric in the existing local frame. The engine copies the lists before any computation and keeps a SHA-256 of the inputs. Map order is part of this identity; geometrically equivalent rings serialized differently can have different hashes.

A local obstacle is a disc of physical radius **plus its user margin**, without the robot radius. The adapter adds the envelope only for the historical generator. The validator directly removes the reserved physical disc. No-go outlines and the chassis are handled separately from the cutting tool.

`orientationMemory` (zone id → `ZoneOrientation`: directions 0..179, row phase index, outline key, date) gives the
directions last mowed per zone for `EACH_SESSION`; the snapshot sorts it by zone id so that the hash stays stable, and an
entry whose outline key no longer matches the zone is ignored.

Passages without a known width never enlarge the physical domain. Their axis remains available to the existing transit planner, but every link must also pass the independent validator. A single-point passage does not define a measured corridor. Manual accesses and docking are not implicitly turned into allowed areas by the new engine.

## Configuration

| Parameter | Default | Meaning and unit |
|---|---:|---|
| `spacingM` | 0.25 | Maximum row spacing, m; minimum 0.05 |
| `marginM` | 0.60 | Historical coverage reserve, m; minimum 0.30; pivot reserve also applied |
| `cutWidthM` | 0.40 | Diameter of the tool disc centered on the reference point, m; explicitly approximate model |
| `minimumCoverage` | 0.95 | Minimum fraction of the fixed target; blocking rejection below it |
| `speedMps` | 0.30 | Translation speed used to estimate time |
| `turnDegreesPerSecond` | 45 | Model rotation speed, degrees/s; not a robot calibration |
| `stopSeconds` | 0 | Extra time per heading change, s |
| `uncertaintyM` | 0 | Additional erosion of the physical domain, m; does not reduce the target |
| `borders`, `borderPasses`, `noGoPasses` | true, 1, 1 | Parameters passed to the existing generator |
| `bordersFirst`, `nearbyNoGo` | true, true | Initial historical ordering; local search keeps task-class separations |
| `boundary` | `PHYSICAL` | `PHYSICAL`, `RECORDED_TRACE` or `UNKNOWN`; unknown refused |
| `allowHypotheses` | true | Allows a simulation result that explicitly carries its assumptions; no effect on the control ban |
| `pivotVerified` | false | Profile declaration; validates neither the protocol nor the other capabilities |
| `verifiedMaxSlopeDegrees` | null | If slopes are provided, an explicit limit is mandatory |
| `candidateBudget` | 8 | Budget of variants after keeping the baseline; bound in evaluations, not a guaranteed time |
| `localSearchBudget` | 24 | Maximum number of permutations explored, deterministic |
| `indexedCoverage` | true | Evaluator with STRtree; false keeps the sequential oracle for ablations |
| `optimizeZoneOrder` | false | Allows permuting blocks of whole zones; the user order remains the default |
| `orientation` | `AUTOMATIC` | Direction of the passes of Zigzag, Checkerboard and Zamboni: `AUTOMATIC` (cheapest per zone), `EACH_SESSION` (away from `PlanningRequest.orientationMemory`) or `MANUAL`; ignored by Optimized, Spiral and the surveys |
| `manualAngleDeg` | 0 | Angle of `MANUAL`, degrees 0..179 (refused outside), counter-clockwise from the map's x axis; folded modulo 90 in Checkerboard |
| `spiralDirection` | `FROM_EDGE` | Spiral only: `FROM_EDGE` (chains as built, inwards) or `FROM_CENTRE` (every chain driven outwards after its core, a zone without a safe order from the centre planned from the edge) |

The domain beyond an admissible slope is forbidden. The cutting target is unchanged: the unreachable part appears in the residual. Missing terrain data is reported. The motion model is **forward, stop, short pivot**, plus the rolling U-turns of the Turn Optimizer (arcs of `TurnOptimizer.FOLLOW_RADIUS_M` = 0.35 m, 4° per vertex, checked like the passes) between passes at least 0.7 m apart, used by *Lawn friendly* (`turnStyle = LAWN`) and Zamboni; no coverage reversing is invented.

The tool is a disc polygonized with 64 sides. The measured area uses an inner approximation; the exclusion check uses an outer approximation. The maximum radial error of the inner disc is `r × (1 − cos(π/64))`, about 0.24 mm at `r=0.20 m`. This choice does not represent a measurement of the real tool. Areas are in m², positions in double precision; coverage equivalence threshold between candidates: `10⁻⁶ m²`.

## Score and validity

`ScoreWeights(distance=1, duration=0.3, uTurns=2, overlap=1, transit=2)` computes:

```text
score = distance × Dtotal[m] + duration × time[s]
      + uTurns × number_of_opposing_passes
      + overlap × extra_visit_area[m²]
      + transit × Doutside_cut[m]
```

These coefficients have the corresponding reciprocal units; the components are not normalized. They must be finite and non-negative. `transit` is a penalty **in addition** to its share already included in the total distance. Duration includes translation, heading changes and configured stops, without energy, recharge or docking. A zero weight does not disable any validity check.

Any candidate that is geometrically invalid, has no link, or is below the coverage threshold gets a `null` score. To beat the reference, it must also cover at least its area within the fixed tolerance, even when the minimum threshold is lower. If no candidate is valid, the engine returns a diagnostic and, if available, a partial preview; the interface refuses to simulate it. A heuristic failure is not a proof of impossibility.

Overlap measures the tool's revisits, excluding the continuity at the vertex of two segments. It is invariant to the subdivision of a straight line. `repeatedAreaM2` is the area touched at least twice; `extraVisitAreaM2` counts extra visits with multiplicity. This is **raw** overlap, not a proof that every repetition is avoidable: the share required for safety is not separated yet.

U-turns are defined as reversals of at least 150° between sweep passes recognized in the route as actually ordered. Heading changes and their cumulative angle are published separately; a link with two right angles between opposing passes thus counts as a single U-turn. Border loops are not sweep passes. Fragments of passes split by the historical entry are recognized by their collinear overlap. This software count is not a measurement of maneuvers in the field.

## Strategies and reproducibility

- `BASELINE`: unchanged call to `MultiZonePlanner`, then common validation and mission measurement.
- `OPTIMIZED`: baseline, DP over both directions of each block, bounded 2-opt and relocation; dominant edge directions, a 5° neighborhood around the best historical angle and phases 0.25/0.50/0.75 depending on the budget. Angles are quantized to the degree. Orientations are evaluated separately from the DP ablation; no global optimum is promised.
- `EXPERIMENTAL_BCD`: same safeguards, plus an event-based partition in the sweep frame. Slices at the ordinates of ring extrema, plateaus grouped, one-to-one adjacencies merged, stop at splits/merges. The partition is checked by symmetric difference and sum of areas. Maximum 512 critical events; beyond that, the candidate is rejected with a budget diagnostic.
- `EXPERIMENTAL_JOINT`: keeps all phase 2 candidates and also optimizes the order of each orientation/phase. Recognizes border loops even when their anchor has been moved.
- `EXPERIMENTAL_LNS`: keeps phase 2 then exactly repairs windows of at most six blocks, within the same zone and cut/border class. At most two passes of `localSearchBudget` windows, sorted by decreasing link cost; second pass skipped if no different plan is available.
- `ADVANCED`: adds both previous refinements. Same LNS pass cap as its ablation; extra order computation for each orientation. The total numbers of evaluations and neighborhoods therefore exceed those of phase 2.

For these three new strategies, replacing phase 2 requires a score, duration, distance and transit that are not higher, within `10⁻⁶`, and a covered area that is not lower, within `10⁻⁶ m²`. U-turns and revisits may vary within the weighted score: their trade-offs appear in the results. The additive DP cost remains distinct from the full score. A window optimum is neither a CPP optimum nor a proof of physical time.

The DP is exact for the additive travel/duration cost at a fixed order and for the two directions offered. The full score is rechecked afterwards. Local search stops after a fixed number of attempts, never on a clock. It keeps cutting blocks and their anchors, does not resample their geometry and is limited to 64 tasks. Zone permutations are an API option; arbitrary precedence is not supported yet. No randomness is used; the published seed is 0.

The link cache holds at most 2,048 `(position, heading, position, heading)` keys and the corresponding failures. It is created for each snapshot, so no result survives a map or profile change. The historical backend keeps its own local cache. Computation stays single-threaded; the `checkpoint` callback allows cancellation between steps and candidates.

## Integration and control

Candidates consume `brainEffective.patched` and the effective obstacles. The preview and the simulation preparation use this same map. A map/obstacle change invalidates the preview, and a result arriving after a modification is refused. Historical base actions keep their raw map and their previous behavior.

All results of the new engine carry `Preview.localOnly=true`, including a baseline kept as the best. This flag is preserved by the multi-zone combination. `RobotRoute.requireExecutable` is called before any BLE command preparation and refuses these plans. The interface's historical reference remains available for pre-existing uses. No new control protocol is added.

The existing simulation rebuilds its links and recharges with the effective map; it may fail independently of the level B score. Its duration must not be confused with the mission time without recharge published by this engine. Certification of the route actually transmitted and full sharing of primitives with the executor remain conditional on the field phases planned in the initial plan.

## Example configuration in Kotlin

```kotlin
val request = PlanningRequest(
    map = effectiveMap,
    zoneIds = selectedZoneIds, // order kept by default
    start = startPoint,
    config = PlanningConfig(
        minimumCoverage = 0.95,
        weights = ScoreWeights(
            distance = 1.0, duration = 0.3, uTurns = 2.0,
            overlap = 1.0, transit = 2.0,
        ),
        candidateBudget = 8,
        localSearchBudget = 24,
    ),
)
val result = CoverageEngine.plan(request, PlannerStrategy.OPTIMIZED)
// Even if selected.valid is true, selected.preview remains localOnly.
```

Weights, the motion profile and free ordering are configurable in the Kotlin API. The interface currently exposes the strategy choice and the coverage threshold; it does not offer an editor for the five weights yet.
