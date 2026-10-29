# Border contacts, proportionate obstacle response and turn tracking — implementation plan (2026-10-28)

Status: **steps A and B implemented** (B3 left open, see 3.2), **step C implemented** (see 4.5; the duration target per
rolling U is not reached on the bench, field check pending). Three independently
deliverable steps, in priority order. Figures tagged
*(calc.)* are computed from the constants of the code, not measured; figures tagged *(real 28/10)* come from the
mowing log of 2026-10-28 (Redmi gateway, Zamboni 59° zone, positions at 1 Hz). Bench baselines still have to be
recorded (step C0, B0); acceptance criteria below are written so that they can be checked against those baselines.

Related: [TRACKING_EXECUTION.md](TRACKING_EXECUTION.md), [OBSTACLE_BYPASS.md](OBSTACLE_BYPASS.md),
[features/obstacles.md](features/obstacles.md), [features/real-mowing.md](features/real-mowing.md),
[features/safety.md](features/safety.md), `ESSAIS_2026-10-15.md` (private trial notes).

## 1. What the code does today (verified by reading)

| Fact | Where |
|---|---|
| Fold and both gateways (Redmi `LegRunner.kt`, ESP32 `gw_leg_runner.cpp`) stop the robot, wait **2 s** for the code (`CollisionLatch.CONTACT_GRACE_MS`), then hand back either `LegOutcome.CONTACT` (pose, code) or `FAILED` with the text `Protection / robot state changed (state 20, protection 25)`. They never classify. | `LegRunner.kt` l.284-323, `RobotBle.followRemote` l.480-505 |
| Both outcomes converge on the **Fold**: `ObstacleEncounter` → `avoidObstacle` (l.3894), or `RouteProtection` → `pauseForProtection` (l.4033) → `route_latch_contact` → `avoidObstacle`. The only exemption is a no-go zone (`OffMapLatch.ignored` + `PointPlanner.atExclusion` → `route_exclusion_latch`, which ends the mission). | `RobotBle.kt` |
| `avoidObstacle` always builds `ObstacleBypass.contactObstacle` (disc 0.2 m + margin 0.3 m, centre `0.345 + 0.2 = 0.545 m` ahead of the reference point), counts one of the `MAX_BYPASSES = 8` per mission, backs off 0.3-1.0 m, repairs with `ObstacleBypass.repair` and holds for the photo. | `ObstacleBypass.kt`, `RobotBle.kt` |
| The contact is **recorded on receipt** (`recordContactOnReceipt`, kind `CONTACT`) before any decision. `PresumedObstacles.clusters` keeps every non-dismissed `CONTACT` for 4 h (single contact is enough) and the next routes avoid it. | `RobotBle.kt` l.2155, `Difficulties.kt` l.205 |
| Clip radius of a discovered obstacle = `holeRadius + turnClearance + 0.03` = **1.18 m** *(calc.: keepOut 0.50 + 0.43 = 0.93; hole 0.93 - 0.2565 + 0.05 = 0.72; clip 0.72 + 0.43 + 0.03)*. Every route piece within it is removed and re-linked. | `ObstacleBypass.kt` l.52-95 |
| Curve speed governor: `v <= turnRate / curvature`, `turnRate = angular/2 x degPerUnit x 0.6` (x 0.7 until the gain is calibrated); steering is **proportional only** (`steer = error x 1.5 / degPerUnit`, clamped to +-angular/2); the reference heading is the **chord to the point 0.25 m ahead**. | `PathFollower.kt` l.31-54, 288-312 |

Consequences for the three requests:

* **The 28/10 log does not show an over-sensitive detection.** Both -1014 contacts are real objects (wood pile and
  pipe at the border; hedge). What is disproportionate is the **response**: 79.2 m of detour for 14.1 m of cut
  skipped (5.6 m of transit per skipped metre), then 73.6 m for the hedge in transit. Section B addresses that.
* **The "drift, hit the border, believed an obstacle, bypass" chain** is the 25-without-code and the coded contact
  at the drawn border (19/10 14:50: slid 24 cm during a 150° pivot). Section A addresses it. Nothing in the 28/10
  log exercises it (no 25 without code), so its tests are synthetic plus the 19/10 and 25/10 geometries.
* **The slow rolling U is not a tracking defect of the measured kind, it is the design of the curve governor**
  (section C): the measured 0.07 m/s and 14-15 s are what the constants predict.

All decisions below are taken on the **Fold**. `LegRunner.kt` and the ESP32 firmware need no change for A and for
the logic of B; C touches `PathFollower` (shared by Fold and Redmi, ported line by line to `gw_follow.cpp`) and the
pivot guard of B2 touches the two leg runners.

---

## 2. Step A — border contact = back-off and resume (no obstacle, no detour)

### 2.1 Classification (new pure object `simulation/EdgeContact.kt`, shared by Fold, obstacle simulation and tests)

Inputs available on the Fold at the time of the decision: contact (code or none), `selfCheck`, `sensorStatus`
(telemetry after the stop), pose at contact, bumper point `P = pose + 0.345 m x heading`, map (zones, no-go, passages),
mission trail (`robotRoute.trail`), route edges around `encounter.index`, pivot-slide measurements of the last 10 s
(`PivotSlide`/`reportSlide`, already on the Fold from both trackers), blade-OFF flag of the current edge.

`EdgeContact.classify(...)` returns one of:

| Kind | Condition (all of them) | Action |
|---|---|---|
| `LIMIT` (robot at the task-area limit) | **a)** no code: `selfCheck == 25`, `sensorStatus and 7 == 0`, `OffMapLatch.atEdge(map, centre)` (beyond the zones or <= 0.35 m inside their limits, or at a no-go) -- the exact rule that already exempts the joystick and moves, extended to routes; **or b)** coded contact (-1014) with bumper point `P` between **0.15 m inside and 0.40 m beyond** the drawn outer border (`LIMIT_BAND_M`, `BEYOND_MAX_M`), heading within **75°** of the direction to that border (`APPROACH_DEGREES`: the front bumper hit it), **and** a drift towards that border: cross-track of the trail to the planned line >= **0.08 m** (`DRIFT_M`) in the last **10 s**, or a pivot slide >= **0.10 m** (`SLIDE_M`) towards it | back-off + resume (2.2) |
| `OBJECT_AT_EDGE` | coded contact, `P` within **0.50 m** inside the border (`OBJECT_BAND_M`, the project's own border rule) and no drift (b fails only on the drift test) | obstacle path of today, with the tightened response of step B; **kept** in the memory |
| `OBJECT` | everything else (mid-zone contact, bits `and 7 != 0` without code, lift codes) | unchanged |

Rationale for the 28/10 09:52:08 contact (real, 0.41 m from the outer border, deviation <= 4 cm): it is
`OBJECT_AT_EDGE`, **not** `LIMIT`. There was no excursion: the planned line itself touches a physical object (wood
pile, pipe) that the map does not contain. A back-off and resume would replay the same line and hit it again, and the
object is real, so it must stay in the 4 h presumed-obstacle memory. What was wrong there is the size of the response
(step B), not the fact of treating it as an object. The `LIMIT` band is deliberately narrow (15 cm inside) so that a
real object in the middle of a border pass is never taken for a limit.

Safety stances: the bumper and lift sensors are never ignored (any bit in `and 7`, or a lift code, forces `OBJECT`
or the lift stop); the robot is stopped with blades OFF before any decision (unchanged order in `avoidObstacle`); the
30 cm deviation stop is untouched. Misclassifying a real object as `LIMIT` costs one extra bump at 10 cm/s with
blades OFF, then the second-contact rule (2.3) sends it down the object path.

### 2.2 Action `LIMIT`

Same building blocks as today, no new motion code:

1. `bladesOff(contactLatch = latched)` and `freshState(contactLatch = latched)` (as `avoidObstacle`), so the stop
   is confirmed under lock 25.
2. `recoverDrift` gets two parameters: `contactLatch: Boolean` (forwarded to `bladesOff`/`freshState`) and
   `afterContact: Boolean` (forwarded to `reverseStraight`, which already accepts 25 through
   `CollisionLatch.reverseAllowed`). Back-off from `DriftRecovery.plan` (trail, 0.1-1.0 m, chassis back inside the safe
   surface) **raised to at least 0.30 m** (`CollisionLatch.UNLOCK_BACK_OFF_M`) when the lock is up; if the trail is
   ahead of the robot (after a pivot, 19/10 case) the existing straight escape ahead / blind 30 cm back-off applies.
3. `awaitCollisionLatchCleared` (existing), then the blades-OFF link of `DriftRecovery.plan` (same-spot skip
   `driftStreak x 1.0 m`, `ENTRY_OFFSETS_M` up to 4 m).
4. No `contactObstacle`, no `discovered`/`plannedObstacles`, no `bypassCount++` (separate counter
   `edgeContacts`, max **4** per mission, then stop kept), no photo hold, no teach proposal (`proposeRouteCollision`
   skipped when the heading test of 2.1 is met).
5. `LIMIT` at a no-go zone: keep `route_exclusion_latch` (stop, joystick) **unless** `DriftRecovery.plan` finds a
   back-off along the trail that the physical planner accepts; the 25/10 case (rear inside the no-go, nothing behind)
   stays a stop.

### 2.3 Second contact at the same place

Keyed on the bumper point: a second `LIMIT` within **1.5 m** (`EDGE_SAME_SPOT_M`) of the previous one in the same
mission is reclassified `OBJECT_AT_EDGE` (it is not a transient drift any more): obstacle created and remembered,
bypass with the step-B response. No lateral offset of the line (needs planner work, not justified yet). A third
`DriftRecovery` same-spot exit still defers the segment through `SmartResume` as today.

Added after QA (2026-10-28, see 2.8): a `LIMIT` within **1.5 m** of an obstacle **discovered during the mission**
(distance from the bumper point to the estimated disc) is also `OBJECT_AT_EDGE` (cause `near_obstacle`); and the
limit path is dropped for the obstacle path when its resume link exceeds **2 m** (`EdgeContact.MAX_LINK_M`, the 2.7
criterion), without counting a limit contact.

### 2.4 Logging, messages, memory

* Event `route_edge_contact`: `kind`, `code`, `selfCheck`, `sensors`, `x`, `y`, `heading`, `borderDistM` (signed, +
  inside), `driftM`, `slideM`, `cause` (`lock_at_limit` | `coded_with_drift`), `backOffM`, `rejoinM`,
  `skippedCuttingM`, `count`, `sameSpot`. Replaces `route_latch_contact` for these cases.
* Bilingual status: `tr("Border contact: back-off, then the route resumes", "Contact en bordure de zone : recul puis reprise du trajet")`.
* Presumed obstacle: new `DifficultyKind.EDGE` (`edge`, weight 0.3, label "Border contact" / "Contact en bordure").
  The entry recorded on receipt is **re-typed** (`DifficultyLog.refine`-style update by id) once classified.
  `PresumedObstacles.clusters` already filters `kind == CONTACT`, so no 4 h memory; the heat map still shows it.
  `OBJECT_AT_EDGE` keeps `CONTACT`.

### 2.5 Parity

| Component | Change |
|---|---|
| Fold (`RobotBle.avoidObstacle`, `pauseForProtection`, `recoverDrift`) | classification + action; **single decision point for local tracking and for both gateways** |
| Redmi `LegRunner.kt` | none (outcomes `CONTACT` / `FAILED(25)` already carry what the Fold needs) |
| ESP32 firmware | none (`gw_leg_runner.cpp` identical contract); no protocol field added: the bits are read from the Fold telemetry after the stop, the code from the contact |
| Fold unreachable (Wi-Fi down) | unchanged: the gateway stays stopped at the contact; the classification happens when the Fold is back |
| `ObstacleEncounterSimulation` | calls `EdgeContact.classify` and the same recovery so the simulation stays the reference |

### 2.6 Tests

* `EdgeContactTest` (unit): `lockWithoutCodeAtTheOuterBorderIsALimitContact` (19/10 14:50: 150° pivot, 24 cm slide);
  `codedContactAtTheBorderAfterADriftIsALimitContact`; `codedContactWithoutDriftNearTheBorderIsAnObjectAtTheEdge`
  (28/10: P 0.41 m inside, deviation 0.04 m, narrow 2 m corridor); `lockWithBumperBitsSetIsNeverALimitContact`;
  `midZoneContactIsAnObject`; `secondLimitContactWithin1500mmBecomesAnObject`; boundaries at 0.15 / 0.40 / 0.08 / 0.10 / 75°.
* `PresumedObstaclesTest`: an `EDGE` entry creates no cluster; an `OBJECT_AT_EDGE` entry does.
* `ObstacleEncounterSimulationTest`: `driftingRobotBumpingTheBorderBacksOffAndResumesWithoutDetour` (wall-like
  `ProbeObject` along the border, robot started 0.2 m towards it): 1 contact, `discovered` empty, 0 bypass metres,
  skipped cutting <= 1.0 m, `DONE`, worst deviation < 0.2 m. Same on an `Enfer` reference-zone pass.
* No new bench needed beyond the above; run `ObstacleBypassTest` and `ObstacleEncounterSimulationTest` (unchanged
  expectations for objects away from the border).

### 2.7 Acceptance

Synthetic and 19/10 replays: robot back on its line after **<= 1.0 m** of back-off + **<= 2 m** of link; no
`discovered` entry; no `CONTACT` cluster; the 8-bypass budget untouched; all existing obstacle tests unchanged.
Docs: `features/obstacles.md`, `features/safety.md` (self-check 25 section), `features/real-mowing.md` (Leaving the
trajectory / Transient protection), `OBSTACLE_BYPASS.md` (collision lock section), `DIFFICULTIES.md`, CHANGELOG
`Unreleased:`.

### 2.8 Done (Unreleased) — what was built and what changed from the plan

Code: `simulation/EdgeContact.kt` (classification, `Border`), `RobotBle.limitContact` (called from `avoidObstacle`
after the confirmed stop, so the remote legs of both gateways and the local tracking share it), `DriftRecovery.plan(
afterContact = true)` / `limitStarts`, `DifficultyKind.EDGE` + `DifficultyLog.refine` re-typing,
`ObstacleEncounterSimulation` (same classification and resume). Decisions taken on the open points: **no photo and no
teach proposal** for a limit contact (the pending photo request and the proposal of this contact are dropped); the
contact stays in the log (`route_edge_contact`), the task history ("Bumper contact" incident) and the heatmap.

Changes from 2.1-2.4, with their reason:

* **Band measured on the front edge** (bumper centre or either front corner), not on the bumper centre alone: in the
  simulated pivot slide the front corner hit the wall while the bumper centre was 0.16-0.18 m back (heading 45-55°), so
  a 0.15 m band on the centre never matched a real angled hit. 28/10 stays an object at the edge (corner 0.28 m inside).
* **No separate pivot-slide input** (`SLIDE_M`): the drift is measured from positions (centre at the contact and the
  last 1.5 m of trail against the nearest point of the planned line, 3 m behind to 1 m ahead), so a pivot slide shows
  up as a drift (19/10: 0.24 m). One threshold, `DRIFT_M` = 0.08 m.
* **`OBJECT_BAND_M` 0.50 → 0.60 m, inclusive**: the 50 cm rule of the blade-OFF links applies to the robot centre
  line, so the B4 hedge contact (link at 0.5 m, heading along the border) has its bumper exactly 0.50 m inside and
  would have been `OBJECT` with a strict 0.50.
* **Limit at a no-go zone (2.2 item 5)**: not unified, `route_exclusion_latch` kept. No real case shows a back-off
  that would have worked there (25/10: nothing behind), and a no-go is often a real hazard.
* **Back-off always first** (`limitStarts`): the lock clears only once the robot moved away, so the current pose is
  never a start; along the trail at least 0.30 m, else the blind 0.30 m straight back-off checked on the map; no
  escape ahead (the bumper may be against the limit). None possible: obstacle path, as before.
* `route_latch_contact` is not logged when the lock is at the limit (`route_edge_contact` follows instead).
* Same-spot: `DriftRecovery` streak not shared; the 1.5 m second-contact rule and the 4-per-mission limit apply.
* **QA fix, limit next to a discovered obstacle**: on an `Enfer` pass (1026) with the wall right on the border and a
  12 cm/s slide, the first contact (front corner at 77°) was an object at the edge (estimated disc, 1.2 m repair) and
  the next one, 0.3 m away, was classified `LIMIT` (2.3 only looked at earlier limits): its resume link, computed
  around the disc just estimated, was **52.0 m** blades OFF. Two rules, shared by the Fold and the simulation:
  (1) `EdgeContact.classify(discovered = ...)`: a would-be limit whose bumper point lies within `EDGE_SAME_SPOT_M`
  (1.5 m) of the disc of an obstacle discovered during the mission is `OBJECT_AT_EDGE`, cause `near_obstacle`
  (`sameSpot` set); (2) guard `EdgeContact.longLink`: a `DriftRecovery` link longer than `MAX_LINK_M` = 2 m drops the
  limit path (Fold: `route_edge_contact_fallback` with `reason` and `rejoinM`), the contact goes down the obstacle path
  and is not counted in the 4 per mission. Same replay now: 3 contacts, all objects at the edge (`object_at_edge`, then
  `near_obstacle` twice), repairs of 1.2 / 1.6 / 0.8 m, route finished
  (`EdgeContactSimulationTest.limitContactNextToAnObjectAtTheEdgeKeepsAShortLink`); guard played on the Enfer limit
  case with the cap lowered to 0.1 m: obstacle path, 1.1 m repair, route finished
  (`limitContactWithATooLongResumeLinkTakesTheObstaclePath`).

Measured (`EdgeContactTest`, `ObstacleEncounterSimulationTest.driftingRobotBumpingTheBorderBacksOffAndResumesWithoutDetour`):
pivot slide of 0.20 m into a wall along the border, 1 contact, classified `LIMIT` (`coded_with_drift`), no obstacle,
no repair, back-off 0.30 m, link 0.2 m, nothing skipped, `DONE`, worst published deviation 0.12 m (< 0.3 m stop; the
plan's < 0.2 m holds here but the slide itself can exceed it). Existing obstacle tests unchanged.

---

## 3. Step B — proportionate response to obstacles

Detection is left as it is: bumper (-1014), lift, 30 cm, 3 s without translation, 15 s without progress. Only the
**size of the reaction** changes. Order of work:

**B0 (measure first, no behaviour change).** Add `EdgeObjectBypassTest` reproducing the 28/10 geometry: 2 m wide
corridor, 59° passes at 0.35 m spacing, estimated centre 0.21 m from the outer border, 0.55 m ahead of the robot.
Print per link (length, side, border clearance, `cutting skipped`) from `RouteRepair.detours`. Target: reproduce the
order of magnitude of 79.2 m / 6 links / 14.1 m skipped. The levers below are kept or dropped by that decomposition.

**B1 — detour budget (the main lever).** In `avoidObstacle`, after `ObstacleBypass.repair`, refuse a repair whose
`bypassMeters > max(15 m, 3 x skippedCuttingMeters)` (`DETOUR_FLOOR_M`, `DETOUR_RATIO`): 79.2 > max(15, 42.3) and
73.6 > 42 when it skipped <= 24 m, so both 28/10 detours would be refused. Fallback order: (1) B2 re-repair, (2)
`deferBlockage` (segment deferred to the end of the mowing through `SmartResume`; already used for "bypass
impossible"), (3) when no checkpoint exists (station route, resume link), keep the repair (never worse than today).
`ObstacleBypass.repair` stays pure; the cap lives in a new `ObstacleBypass.detourRefusal(repair)` shared with the
simulation.

**B2 — border-aware estimate for `OBJECT_AT_EDGE`.** Margin of the estimated disc `0.30 -> 0.20 m`
(`CONTACT_EDGE_MARGIN_M`) only for contacts classified `OBJECT_AT_EDGE`; radius stays 0.2 m. Clip radius *(calc.)*
1.18 -> 1.08 m, keep-out 0.93 -> 0.83 m. This is an estimate for planning, **not a robot protection**: the bumper,
the guard `FollowObstacleGuard` and the physical radius are unchanged, and a new contact still adds a disc. Not
applied to mid-zone objects. Rejected: elongating the disc along the border (worse for neighbouring passes),
keeping straight pieces inside the clip radius (would fail `requirePath`, whose hole is 0.98 m centre to centre).

**B3 — pivot in a narrow corridor (09:52:46, "Robot envelope outside the safe corridor" at 3.6 cm).** At a planned
pivot vertex (`follower.pivoting`, `linear == 0`) with deviation <= **0.06 m**, if `guard` refuses the sweep but the
`physicalGuard` accepts it with a **3 cm** clearance, continue and log `route_pivot_margin` (once per vertex).
Elsewhere (translation, deviation > 6 cm, physical collision) the stop stays. Touches `LegRunner.kt` l.435-440,
`RobotBle.followPath` (same lines), `gw_leg_runner.cpp`/`gw_envelope.cpp`, `TrajectoryEnvelope.turnCollision` (new
clearance parameter). The mission was recovered by `route_drift_recovery` on 28/10, so the gain is time and
avoided back-off, not a lost mission: this is the lowest-priority lever and can be shipped separately.

**B4 — contacts in transit outside the mowed area (09:53:53, hedge, blades OFF link).** Same `EdgeContact.classify`
(bumper 0.5 m rule): the hedge was met on a link that the planner had kept >= 0.5 m from the drawn border, so it
is `OBJECT_AT_EDGE` (vegetation overgrown beyond the border), remembered, bypass with the same budget. Open point:
for a transit link the budget floor (15 m) may be too low when no cutting is skipped; decide after B0 on the transit
replay.

**Not touched:** `MAX_BYPASSES = 8` (but `LIMIT` events no longer consume it), `MAX_NEARBY_CONTACTS = 4`, back-off
bounds, `MotionProgressGuard` (3 s) and the 15 s no-progress rule.

### 3.1 Tests and acceptance

* `EdgeObjectBypassTest` (B0 replay): after B1+B2 the corridor repair is **<= 25 m with <= 2 links**, or is deferred;
  skipped cutting <= 14.1 m; passes entered again with deviation < 0.2 m.
* `DetourBudgetTest`: ratio and floor boundaries; mid-zone objects of `ObstacleEncounterSimulationTest` give the
  same repairs as before (plan hash and metres unchanged).
* `NarrowCorridorPivotTest` (`RouteExecutionModel`, 2 m corridor): vertex offset 3.6 cm towards the wall -> no
  `RouteDrift`; 10 cm -> still stops; physical collision -> still stops. C++: same vectors in `test_autonomy`.
* Full set: `ObstacleBypassTest`, `ObstacleEncounterSimulationTest`, `RouteExecutionRegressionTest`,
  `ReferenceMapPlannerTest` (RULES.md), `pio test -e native`.
* Docs: `features/obstacles.md`, `OBSTACLE_BYPASS.md` (limits), `features/real-mowing.md`, CHANGELOG.

### 3.2 Done (Unreleased) — B0, B1, B2, B4; B3 open

**B0 measured** (`EdgeObjectBypassTest`, two lawns joined by a 2 m corridor along the north border and, the long way
round, by 2 m strips around a house drawn as a no-go; Zamboni, 0.35 m spacing):

| Case | Margin 0.30 m | Margin 0.20 m |
|---|---|---|
| Passes **along** the corridor, contact 0.34 m from the border (`OBJECT_AT_EDGE`) | **66.2 m** of links (4), 9.4 m skipped: the first blade-OFF link cannot keep the 50 cm border rule beside the disc and goes round the house | **9.3 m** (4 links, 2 arcs), 7.5 m skipped; bench replay max deviation 0.19 m |
| Passes **crossing** it at 59°, bumper 0.41 m inside (the 28/10 contact pose) | 0.8 m (2), 2.8 m skipped | 0.7 m (2), 2.4 m skipped |
| **Transit** link 0.5 m from the border, hedge (B4) | 52.8 m (1) | 52.9 m (1) |

The 79.2 m / 6 links / 14.1 m of 28/10 are of the order of the first line (same mechanism: the 50 cm rule of the
blade-OFF link next to the estimated disc in a 2 m corridor). The real zone is not in the repository, so this is the
closest synthetic decomposition; the crossing geometry alone stays local.

**B1**: `ObstacleBypass.detourRefusal` / `detourBudgetM` (`DETOUR_FLOOR_M` = 15 m, `DETOUR_RATIO` = 3), applied in
`avoidObstacle` **only to contacts on a cutting edge**; refused → `deferBlockage` when the mission has saved progress,
is not a station route and has blockages left (same preconditions checked first, including
`SmartResume.deferBlocked`), with a fallback that recomputes and keeps the repair if the deferred remainder cannot be
routed after the back-off (`route_detour_kept`); otherwise the repair is kept. The "(1) B2 re-repair" fallback is
moot: B2 is applied from the start to objects at the edge. Events `route_detour_refused`. The simulation notes the
refusal and keeps the repair. The reference-map repairs of `ObstacleBypassTest` (links 3.4-23 m for 2.4-17.5 m
skipped) are all within budget; the mid-zone simulations record no refusal.

**B2**: `ObstacleBypass.CONTACT_EDGE_MARGIN_M` = 0.20 m for `OBJECT_AT_EDGE` only (Fold and simulation). Kept: it is the
lever that removes the long way round in the corridor (66.2 → 9.3 m).

**B4**: the transit contact is `OBJECT_AT_EDGE` (with the 0.60 m band). Decision on the open point: the budget is
**not** applied to transit contacts. Deferral cannot shorten a transit (its destination is beyond the object) and the
replay shows the estimated disc closes the corridor whatever the margin (52.8 / 52.9 m). Shortening it needs a better
estimate of a contact met heading along the border (the object is likelier on the border side) — left open.

**B3 not implemented** (touches a protection and the firmware): still open as written above.

Acceptance against 3.1: corridor repair ≤ 25 m (9.3 m) and **≤ 2 arcs around the object** (the plan's "≤ 2 links"
counted the detours; `RouteRepair.bypasses` also counts 2 short re-entries, 4 in total), skipped ≤ 14.1 m (7.5 m),
replay deviation < 0.2 m (0.19 m). `NarrowCorridorPivotTest` belongs to B3, not written.

Open after A and B: transit detours (B4 estimate), B3, the `Enfer` replay of 2.6, the field check of the thresholds
(0.08 / 0.15 / 0.40 / 75° / 0.60) on the next real border contacts, and whether a deferral after a refusal can itself
need a long link when the corridor is closed (not measured: the corridor case is accepted with B2 before reaching it).

---

## 4. Step C — turns (rolling U and tight curves)

### 4.1 Diagnosis from the code *(calc.; to confirm with the bench trace of C0)*

Speed. For a rolling U (R = 0.35 m, vertex every 4°, `curvature = 4°/2.44 cm = 164 °/m`):

| Set speed | `angularLimit` | `turnRate` (°/s) | `v = turnRate / curvature` | 1.1 m of arc |
|---|---|---|---|---|
| 30 % | 263 | 9.5 | 0.058 m/s | 19 s |
| 63 % (planner default 0.3 m/s) | 351 | 12.6 | **0.077 m/s** | **14.3 s** |
| 100 % | 450 | 16.2 | 0.099 m/s | 11.1 s |

The measured 0.07 m/s and 14-15 s *(real 28/10)* are exactly what the governor allows. Cause: the feed-forward
budget is `0.6 x (angular/2)` = **30 % of the available rotation**; the other 70 % is kept for the proportional
correction. The figure "4.6 s estimated" is the flat planner duration model (0.3 m/s, 45 °/s); `TurnOptimizer.drive`
already contains the governor and predicts about the measured value, so the optimizer does **not** mis-rank
manoeuvres against itself (to be confirmed by asking which estimate produced 4.6 s).

Accuracy. Steering is proportional only: in an arc the yaw rate `omega = v x curvature` (11 °/s at 0.07 m/s) needs a
permanent heading error `omega / 1.5` = 7.6°, and the reference heading is the chord to the point 0.25 m ahead,
which leads the tangent by `0.25 / (2R)` = **20°** at R = 0.35 m. The loop settles where the lateral term
`atan(lateral / 0.8)` balances the difference: a steady **inward offset of the order of 8-17 cm** *(calc.)*, matching
the 8.3 cm median *(real 28/10)*; at the end of the arc the accumulated heading lead plus the 0.6 s delay overshoots
(peak 24.9 cm at the exit). The same mechanism explains the tight curves of the outer lap (p90 34 cm, max 44 cm
*(real 28/10, indicative)*; "cuts the corner" in [TRACKING_EXECUTION.md](TRACKING_EXECUTION.md#published-point-behind-the-centre-of-rotation-0432)).
In-place pivots (p90 6.3 cm, no time lost) and straight passes (median 1.0 cm) are fine and must not move.

### 4.2 Steps

**C0 (diagnostic, no behaviour change).** (1) From the 28/10 log, estimate the yaw gain along arcs
(`delta heading / sum of commanded steer`) against the pivot gain 0.12 °/s per unit: if the arc gain is more than 15 %
lower, add an arc gain to the calibration before anything else. (2) In `RollingTurnExecutionTest`, add per-U window
metrics to `RouteExecutionModel.Run` (test code only): duration, median speed, true deviation median / p90 / max,
deviation over the first metre after the exit, pivots inside the arc. (3) Record the baseline for Potager and Aire de
jeux at 30, 50 and 100 %, plus the Zamboni execution test, and the border lap.

**C1 — tangent reference in curves.** In `PathFollower.command`, when the cumulative turn over `CURVE_WINDOW_M`
exceeds **3°**, the reference heading is the heading of the edge under the foot of the perpendicular (plus half of
the next vertex turn) instead of the 0.25 m chord; straight stretches keep the chord (unchanged, so entries and
exits of curves stay anticipated by the 0.4 m `lookahead`). Removes the 20° lead.

**C2 — curvature feed-forward and governor on the real capacity.** Signed curvature of the window (S-bends no
longer cancel), `steer = clamp((error x 1.5 + v x kappa) / degPerUnit, +-0.8 x angular)`, and the governor
`turnRate = angular x degPerUnit x CURVE_FF_SHARE` with `CURVE_FF_SHARE = 0.5` (start value; the bench sweeps
0.4 / 0.5 / 0.6), bounded by the **calibrated** gain (`reckoning.degreesPerUnit`) and by 0.7 of the measured pivot
rate when known (27-43 °/s depending on slope). *(calc.)* speeds 0.096 / 0.129 / 0.165 m/s at 30 / 63 / 100 %, i.e.
arc 11.4 / 8.5 / 6.7 s instead of 19 / 14.3 / 11.1 s. The 0.7 uncalibrated factor is kept.

**C3 — exit of the arc.** The heading error saturation (`PIVOT_ENTER_DEGREES = 25°`) must not trigger a pivot inside
an arc: when `curveDegrees > 3°` and the lateral offset < 0.15 m, enter the pivot state only above 40°. Exit speed:
limit the linear command to the governor value for **0.3 m after the last curved vertex** (instead of releasing at the
vertex), then ramp to the set speed within 0.5 m.

**C4 — the model and the planner follow.** `TurnOptimizer.drive` and `PathFollower` read the same constants through a
single helper (`PathFollower.curveSpeed(...)`) so that the optimizer duration, the ETA of the mission and the
tracking stay consistent; the ETA of the mission uses `TurnOptimizer.drive` for the turns instead of the flat model
(about +10 s per rolling U today).

**C5 — logging.** The route plan event lists the chosen manoeuvre per U (`kind`, `durationS`, `wear`, `tight`, edge
range) -- Fold side only, no `SimEdge` field and no frame change; at run time the existing `follower.index` joins on
it. Add `route_curve_stats` per manoeuvre at the end of a curved range (duration, median speed, max deviation) to
make the next field comparison direct.

Should the Turn Optimizer penalise the rolling U while it is slower than the pivot? **No penalty now.** `LAWN`
scores `0.25 x duration + 0.06 x wear` on purpose (lawn wear is the reason for choosing it), `FAST` already picks a
pivot whenever it is quicker, and the optimizer's duration model already carries the governor. After C2 the arc is
about as fast as the pivot U at 0.7 m spacing *(calc. 14 s vs about 14 s: 9.7 s of pivots and stops plus 0.7 m of
link)*. Safety valve for later: a measured/modelled factor per manoeuvre kind (`ARC_TIME_FACTOR`), applied to
`U_ARC` only if the real ratio exceeds 1.3 after C2.

### 4.3 Parity

`PathFollower.kt` is shared by the Fold and the Redmi gateway (same class). ESP32: mirror in `gw_follow.cpp` /
`gw_follow.h` (constants at l.33-43, steer at l.236-244) and parity vectors in `test_follow` (same `command()`
inputs, same outputs, tolerance one command unit). `TurnOptimizer` is planner-side only.

### 4.4 Tests and acceptance (compare with the C0 baseline, same seeds, `RouteExecutionModel`)

| Criterion | Target |
|---|---|
| Duration per rolling U (window of the manoeuvre) | **<= 0.65 x baseline** (about 9 s for the real 14.5 s case) and never above the pivot U of the same pair |
| Median speed on the arc | >= 0.10 m/s at 63 % |
| True deviation inside the manoeuvre | p90 <= 0.5 x baseline, max <= 0.15 m; first metre after the exit <= 0.06 m |
| Pivot inside an arc | 0 (`midPivots == 0`) |
| `RouteExecutionRegressionTest`, `RollingTurnExecutionTest` (`worst <= 0.27`, lawn pivot time < fast), `ZamboniExecutionTest`, `SpiralExecutionTest` | unchanged thresholds, 0 interrupted missions at 30 / 50 %, nominal robot < 0.30 m at 100 % |
| Full `ExecutionProfileTest` | interrupted missions not above 0 / 0 / 4 (30 / 50 / 100 %), mean maximum deviation not above 0.149 / 0.150 / 0.202 m |
| Border lap (tight curves) | bench max real deviation <= 0.20 m (today 0.16-0.23 in the bench, 0.44 m in the field: the bench does not reproduce the field, so the field check below is mandatory) |
| Other tests | `PathFollowerTest`: `curveFeedForwardKeepsTheTangentReference`, `sBendDoesNotCancelTheFeedForward`, `straightPassIsUnchanged` (bit for bit); `TurnOptimizerTest`: `driveUsesTheTrackingGovernor`; `pio test -e native` |

Field check (blades OFF, supervised, per [ROBOT_TRIAL_PLAN.md](ROBOT_TRIAL_PLAN.md)): 10 rolling U on the flat part
of a zone and one border lap; compare duration, speed and deviation with the 28/10 figures. Roll back C2 first if
deviation worsens (it is the only step that raises curve speed); C1 and C3 are independent of speed.

Docs: `TRACKING_EXECUTION.md` (new section "Curves: tangent reference and feed-forward"), `features/mowing-preparation.md`
and `path-planning/PARAMETERS.md` (turn duration model), `features/real-mowing.md`, firmware README, CHANGELOG.

### 4.5 Done (Unreleased) — C0 to C5, measured on the bench

Details, tables and sweeps: [TRACKING_EXECUTION.md](TRACKING_EXECUTION.md#curves-tangent-reference-and-feed-forward-2026-10-28).

* **C0**: `TurnExecutionProfileTest` (opt-in) measures every manoeuvre found by `RouteManoeuvres` in
  `RouteExecutionModel` (duration, next metre, speed, true deviation median / p90 / max, first metre after, pivots
  inside), robots of `RouteExecutionRegressionTest`, 30 / 63 / 100 %. Baseline: 13.2 to 13.8 s per U at 63 %, 0.054 m/s,
  p90 17 to 18 cm, max 18 to 20 cm, 3 to 4.5 pivots per U (the bench is harsher than the field: 8.3 cm median). The
  arc gain estimate from the 28/10 log (C0 (1)) was not done: the log is not in the repository.
* **C1**: tangent at the **nearest** point of the route, not at the followed edge: on 2.4 cm edges the followed edge is
  reached up to 15 cm early (`ARRIVAL_M`), which was the larger part of the 20-25° lead. Only for arcs of edges under
  15 cm (`CURVE_ARC_EDGE_M`: the chamfered corners of the map sync outlines keep the chord, `MapSyncBenchTest`); kept on
  the chord right after a planned stop vertex and before the stop of a tight rounded turn (rounded no-go corners).
* **C2**: feed-forward v × κ (signed, ±10 cm, 0.25 s of travel ahead), steer bound 0.8 × limit, curve speed with
  `CURVE_FF_SHARE` = 0.6 (sweep 0.4 / 0.5 / 0.6, the 0.6 respects every threshold) and a cap of 22 °/s added
  (`CURVE_MAX_RATE`: without it, 6 interrupted missions out of 175 at 100 %, and the painted route of 2026-10-10 at
  28 cm; the 0.7 bound of the measured pivot rate of the plan is not known at run time).
* **C3**: pivot in a curve only beyond 40° within 15 cm of the line; exit held 0.3 m then ramped over 0.5 m (shorter
  exits save 1.4 to 1.8 s per U at 63 % but leave up to 9 to 14 cm after the U).
* **C4**: `PathFollower.curveSpeed` / `exitLimit` shared by the tracking and `TurnOptimizer.drive`. The "4.6 s" per U is
  most likely the map screen estimate (length at the task speed × history factor, pivots excluded), not the planner;
  that estimate is left unchanged (its history factor already absorbs the turns).
* **C5**: `route_manoeuvres` at the start of a route, `route_curve_stats` per manoeuvre while driving (Fold tracking and
  gateway progress); no frame field.

| Criterion (4.4) | Result (nominal robot unless stated) |
|---|---|
| Duration per rolling U ≤ 0.65 × baseline | **not reached**: 0.67 to 0.72 at 63 %, 0.68 to 0.86 at 30 %, 0.79 to 0.86 at 100 % (Potager 63 %: 13.5 → 9.3 s; with the next metre 17.2 → 15.1 s) |
| Median speed on the arc ≥ 0.10 m/s at 63 % | 0.121 to 0.131 m/s |
| p90 ≤ 0.5 × baseline, max ≤ 0.15 m; first metre after ≤ 0.06 m | p90 0.13 to 0.31 × baseline, max 2.5 to 13.6 cm; first metre after 0.4 to 11.6 cm (above 6 cm in 5 of the 15 route × speed cases) |
| Pivot inside an arc = 0 | 0, all robots, all speeds |
| Regression tests | `RouteExecutionRegressionTest`, `RollingTurnExecutionTest` (U checks added), `ZamboniExecutionTest`, `SpiralExecutionTest`, `TrackingPrecisionTest`, `MapSyncBenchTest` green, thresholds unchanged |
| `ExecutionProfileTest` ≤ 0 / 0 / 4 interrupted, mean max ≤ 0.149 / 0.150 / 0.202 m | 0 / 0 / 3 (0 / 0 / 3 before); 0.114 / 0.131 / 0.186 m (0.177 / 0.178 / 0.209 m before this bench run) |
| Border lap max ≤ 0.20 m on the bench | curves 10.8 to 13.9 cm at 63 % (14.3 to 17.2 before) |

Field check (blades OFF, supervised) still to do: 10 rolling U on the flat part of a zone and one border lap, compared
with the figures of 28/10 through `route_curve_stats`; roll back the curve speed first (`CURVE_FF_SHARE`,
`CURVE_MAX_RATE`) if the deviation worsens.

---

## 5. Order, dependencies and risks

1. **A** first (self-contained, Fold only, removes the false obstacle and its 4 h memory). It also stops `LIMIT` events
   from eating the 8-bypass budget.
2. **B0 + B1** next (largest time saving per line of code), then B2, then B4; B3 is optional and independent.
3. **C0** can start in parallel with A (read-only + test metrics); C1-C3 only after the baseline is recorded.

Safety summary: no robot-side protection is relaxed (bumper, lift bits `and 7`, lock handling, 30 cm stop, progress
guards, 50 cm border rule for blades-OFF links). The relaxations are planning estimates (B2 margin) and one
guard tolerance bounded to a planned pivot at <= 6 cm deviation with 3 cm of physical clearance (B3). Misclassification
risk of A is bounded by the second-contact rule and the 4-per-mission limit. C raises curve speed: guarded by the
calibrated-gain bound, the 0.12-0.25 m deviation governor (unchanged) and the field check.

## 6. Open points

* Which estimate gave "4.6 s" per U: most likely the map screen estimate (4.5); to be confirmed with the user.
* Share of 25-without-code events in real logs (none on 28/10; the 19/10 and 25/10 cases are the only documented
  ones): A(a) is justified by the firmware behaviour documented in safety.md, A(b) thresholds (0.08 / 0.10 / 0.15 /
  0.40 / 75°) are initial values to adjust on the next real border contacts.
* Photo and teach proposal for `LIMIT`: decided, skipped (2.8).
* Transit-link budget floor (B4) and the exact B2 margin depend on the B0 decomposition.
