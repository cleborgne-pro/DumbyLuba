# Disturbance bench — route exits, border overruns, blockages (2026-11-01)

`DisturbanceBenchTest` plays the mowing route of a garden-sized zone with the **actual mowing tracking**
([`ObstacleEncounterSimulation`](../app/src/main/java/fr/luba/poc/simulation/ObstacleEncounterSimulation.kt):
`PathFollower`, `DeadReckoning`, `RangeArrival`, `ReturnDrift`, `DriftRecovery`, `EdgeContact`, `ObstacleBypass`) on
disturbed ground, and measures how each mission ends and how far the **chassis corners go beyond the drawn border**.
It runs in the quick suite (about 3 s).

## Set-up

- Zone: L shape 16 × 11 m (concave corner), base on its west side (axis 180°), route prepared like the map
  (`ObstacleEncounterSimulation.route`: 25 cm spacing, border loop with a 5 cm outer margin, recorded boundary):
  313 edges, 536 m, 4 467 s at 30 %.
- Robot: `SimulatedRobot` (0.6 s delay, inertia, friction, gains unknown to the tracking) with a ground model added for
  the bench (`SimulatedRobot.Params.ground`): traction factor, drift of the centre while driving, yaw of the chassis.
- Published positions at 1 Hz with 1 cm noise, or a noise that depends on the place (`positionNoiseM`: RTK degraded
  under a tree).
- Local disturbances are played on a stretch of 12 edges around the longest pass **in the middle of the zone**
  ("interior", > 1.5 m from the border) and around the longest stretch of the **border loop** (< 0.5 m).
- The projected footprint check of the actual tracking (`envelopeGuard`) is off in the obstacle simulation; the
  border cases were played again with it on ("+ envelope").

## Results

| Scenario | End | Exits recovered | Chassis beyond the border |
|---|---|---|---|
| Reference, firm flat ground, whole route | finished | 0 | **0.11 m** (pivot on the border loop) |
| Cross-slope 1 cm/s | finished | 0 | 0.13 m |
| Cross-slope 3 cm/s | **stopped** at 3 582 s of 4 467 ("slide of 34 cm inside the base safety zone") | **13** | 0.35 m |
| Cross-slope 6 cm/s | **stopped** at 286 s, 2 false blockages (12.8 and 13.3 m of cutting left around) | 5 | 0.28 m |
| Slope towards the border 1 cm/s | finished | 0 | 0.17 m |
| Slope towards the border 3 cm/s | **stopped** at 43 s | 0 | 0.35 m |
| … with the envelope check | **stopped** at 44 s | 2 | 0.08 m |
| Pivot slide towards the border 6 / 12 cm/s | **stopped** at 39 / 44 s | 0 / 1 | 0.35 / 0.43 m |
| Interior: wheel held 30°/s 1 s, 60°/s 1.5 s, 90°/s 2 s | finished | 0 / 1 / 0 | 0 |
| Interior: wet patch (traction 0.3) | finished, +41 % on the stretch | 0 | 0 |
| Interior: stuck 2 s | finished | 0 | 0 |
| Interior: stuck 5 s, stuck for good | finished, 1 estimated obstacle bypassed | 0 | 0 |
| Interior: RTK noise 5 cm | finished | 1 | 0 |
| Interior: RTK noise 10 / 25 cm (stretch of 750 s) | **stopped** at 1 110 / 64 s | 16 / 3 | 0 |
| Interior: branch 2 m × 5 cm across the pass, 20° / 60° | finished, 2 contacts | 0 | 0 |
| Border loop: wheel held 30°/s 1 s | finished | 0 | **0.17 m** (0.12 m + envelope) |
| Border loop: wheel held 60°/s 1.5 s | finished | 1 | **0.47 m** (0.29 m + envelope) |
| Border loop: wheel held 90°/s 2 s | finished | 1 | 0.51 m |
| Border loop: stuck 5 s, stuck for good | finished, 1 estimated obstacle bypassed | 0 | 0.12 m |
| Border loop: RTK noise 5 cm | finished | 2 | **0.47 m** |
| … with the envelope check | **stopped** at 94 s (4th exit at the same place) | 3 | 0.09 m |
| Border loop: RTK noise 10 / 25 cm | **stopped** at 63 / 38 s | 0 / 3 | 0.16 / 0.02 m |
| Border loop: branch across the pass | finished, 2 contacts | 0 | 0.12 m |

Handled well: a wheel briefly held in the middle of the zone, a wet patch, a branch across a pass, a blockage that
clears within the 3 s of the progress guard.

## Fixed with the bench

**Blockage in the simulation**: wheels turning without moving for 3 s (branch under the chassis, root) ended the
simulated mission, while actual mowing (`RouteOrchestrator.stallEncounter`) estimates an obstacle in front of the
robot, backs off and bypasses it. The simulation now takes the same path (no border classification, 0.3 m margin,
label "Blockage during the mission"): stuck 5 s or for good → 1 estimated obstacle, route finished.

## Points to improve (not changed)

1. **Degraded RTK stops the mission (≥ 10 cm noise).** The 30 cm deviation and the 60 cm pivot slide are judged on
   a single fix: under a tree, noisy fixes trigger route exits (16 on the interior stretch, several "pivot slide
   > 60 cm" among them) until the "4th exit in a row at the same place" rule stops the mowing; on the border loop,
   5 cm of noise is enough once the envelope check is on. Lead: judge the
   deviation on two consecutive fixes (or the median of three), use the RTK state / reported accuracy when the robot
   publishes it (slower speed, wider tolerance, no blades-ON resume while it is float), and give the resume link a
   reconvergence tolerance like `PivotSlide`. Since 2026-11-02 the tracking drives at 60 % of the set speed in RTK
   float and 50 % without RTK ([adaptive speed](features/real-mowing.md#adaptive-speed)); the bench publishes a FIX
   status throughout, so these rows are unchanged, and a slower speed alone does not filter a noisy fix.
2. **No recovery from a drift out of the border loop.** The border loop runs with the chassis 5 cm from the line; once
   the robot has drifted outwards (slope, pivot slide), `DriftRecovery.plan` finds no back-off point ("chassis outside
   the zone on the whole trail within 100 cm") nor a link, and the mission stops. The trail of the border loop is
   itself at the limit of the safe surface. Lead: an inward escape (pivot towards the inside, short straight move to
   the nearest safe point) when the back-off along the trail fails, or rejoin the loop further on.
3. **Symmetric 30 cm tolerance next to the border.** On the border loop a wheel held 1 s at 30°/s puts the chassis
   15-17 cm beyond the line without any stop; 1.5 s at 60°/s 29 cm with the envelope check, 47 cm without. Lead: on
   the border loop and near no-go zones, stop when the chassis (not the centre) crosses the line by more than 5-10 cm,
   and detect an unexpected yaw from the published heading (heading away from the commanded one by > 20° while driving
   straight) before the position shows it.
4. **Pivots on the border loop sweep beyond the line on flat ground.** In-place pivots at the vertices of the border
   loop swing the front corner (turning radius 0.43 m) 11-13 cm beyond the drawn border while the centre is 0.31 m
   inside, even without any disturbance; it matches the border contacts seen after pivots (19/10, 28/10).
   `BorderMarginChassisTest` checks the loop line, not the pivot sweep. Lead: pivot at a point moved inwards by the
   missing clearance (about 12 cm) at turns sharper than ~30°, or a rolling turn there.
5. **Steady cross-slope.** The follower has no integral term and `SlideField` only learns pivot slides: at 3 cm/s
   sideways the robot leaves the route 13 times and stops near the end, sliding inside the base safety zone. Lead: learn the drift in translation
   as well (lateral bias per area) and hold a crab angle.
6. **Creeping uphill reads as a blockage.** A resume link driven at the approach minimum (80 units ≈ 5 cm/s) against
   a strong slope advances under 3 cm in 3 s: blockage presumed, estimated obstacle, and its reserve covering the base
   approach point can end the mission ("route end inside the reserve of a discovered obstacle"); at 6 cm/s two false
   blockages each left about 13 m of cutting around their estimated obstacle. Lead: raise the command once before
   presuming a blockage when it is at the approach minimum, and never let an estimated blockage swallow the base
   approach point.
7. **A branch that lets go after 5 s still costs a bypass.** The blockage becomes a permanent estimated obstacle for
   the mission (skipped cutting, detour). Lead: one retry (back-off 20 cm, same line) before estimating the obstacle.

## Limits of the bench

The disturbed runs are sensitive: a small change of the tracking or of the resume (for example the shorter resume
after a trajectory exit, integrated the same day) moves the stop of the cross-slope and degraded-RTK runs by minutes,
so compare whole rows rather than single times.

The ground model is kinematic (drift and yaw imposed, no wheel-ground forces), the drift is a constant push while
driving, and the robot gains are those measured on 2026-10-10. Numbers compare scenarios with each other; they do not
replace trials on the robot.
