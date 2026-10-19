# Modelled execution of mowing routes — October 10, 2026 (evening)

## Why an execution bench

Earlier campaigns measured route **computation** (distance, transit, U-turns, duration estimated by a simple
model). They did not say how long **BLE tracking** actually takes to drive those routes, nor whether it stays
under the 30 cm safety stop over whole zones.

`RouteExecutionModel` (tests) replays a zone's prepared route with **the same loop as real mowing**
(`RobotBle.followPath`; the gateway's autonomous tracking, `autonomy/LegRunner.kt`, is a line-by-line port of it, see
[AUTONOMOUS_GATEWAY.md](AUTONOMOUS_GATEWAY.md)): one range per blade state, STOP between two ranges, 5 Hz commands on the predicted pose
(`DeadReckoning`, 600 ms delay compensated), limited forward speed while the heading is not confirmed or a pivot
not measured, end of range confirmed by `RangeArrival`, deviation and lack of progress checked against the
**published** position.

Robot modeled from the Oct 10 surveys: positions at 1 Hz with 1 cm RTK noise and 1° heading noise, command →
effect delay of 0.6 s, first-order inertia (0.25 s), friction (30 units forward, 20 in rotation), 0.11 °/s and
1.08 mm/s per unit. Variants: delays of 0.4 / 0.6 / 0.8 s, rotation gains of 0.09 and 0.13 °/s·u (tracking assumes
0.12), initial heading off by 20°, four noise draws per robot: **175 missions per speed** (7 zones × 25 robots).

Routes: reference map, recorded base, same computation, navigation and preparation as the map screen (25 cm
spacing, 95 % threshold, recorded track), from the first cutting point to the base approach point.

## What the bench revealed

| Finding | Effect | Fix |
|---|---|---|
| First heading confirmation over 8–10 cm traveled between two positions | 1 cm of RTK noise skewed the direction by 30–40°; "confirmed" heading off by 37°, wrongful pivot, 47 cm deviation when leaving Figuier | Confirmation over 15 cm of cumulative straight line (chord, noise only at the ends); a blatant deviation (≥ 45°, the wrong published heading of Oct 10) is still adopted from the first measurement |
| Re-alignment of the confirmed heading on 8 cm chords at low speed | Two measurements wrong by more than 20° adopted as a re-alignment (−23°) in a curve | Chord of at least 15 cm, also for an already confirmed heading; measurement possible even below 8 cm/s |
| Rotation gain calibrated on every pivot interval | At the end of a pivot, small commands and residual rotation (delay, inertia): gain overestimated by 20 %, predicted heading ahead in curves | Calibration only during steady rotation or between two "at rest" positions (total rotation / cumulative commands) |
| Gain not yet calibrated at the start of a mission (curved borders first) | Robot turning 25 % less than expected: exits from curves beyond 30 cm at 100 % | Curve speed computed with 70 % of the assumed rotation while the gain is not calibrated |
| Rounded no-go corners (≈ 55° over 7 cm) followed as a curve | Impossible radius even at minimum speed: the robot crawled at 5 cm/s and drifted up to 30 cm away | Tight rounded turn (cumulative > 30° over ≤ 15 cm) treated as a corner: one stop and one pivot in the middle of the portion |
| Braking to stop 8 cm before each corner, then a slow approach | ≈ 1 s lost at each corner (two per U-turn) | Minimum approach speed reached 2 cm before the pivot point (plateau kept for real delays up to 1 s) |
| Systematic slow advance after every pivot | ≈ 1 s at 0.14 m/s after every pivot, even when pivots are perfectly predicted | Normal advance if the last 3 pivots were predicted within 6° (error measured at the position that confirms each pivot); a single larger error restores the slow advance |
| End of a blade range (end of the approach route, zone change): "wait for a measured position after the stop" turned into "arrival accepted" (`?: true`, since the afternoon of Oct 10) | End of range declared on the predicted pose alone, 0.6–1 s ahead of the robot: blades switched before the real corner, as in the cutting test of Oct 10 | Actually wait for the position after the stop (`RangeArrival`), resume the approach if the robot stopped short; the base approach is still accepted directly (docking) |
| Motion gains restarting from the defaults (0.12 °/s·u) on every mission | First curved borders followed with a wrong rotation gain until it is calibrated | Calibration stored per robot (`motion_calibration` preferences, BLE address; also returned by the gateway at the end of autonomous tracking), reused on the next start of mowing and of the navigation test; bounded and always re-calibrated online |
| No reaction to a growing deviation | The robot kept its speed until the safety stop | Beyond 12 cm of measured deviation, speed reduced progressively down to half the set speed at 25 cm |
| Tracking the next edge after a clearance on the predicted pose (tests of Oct 15) | Measured position still inside the obstacle margin while the next edge, checked with the full margin, was already being followed: 30 cm clearance refused | Clearance stays active until the measured position has passed its end (`PathFollower.escaping`); during clearance, only an approach towards the obstacle or a pivot inside its margin stops tracking (`FollowObstacleGuard.intrudes`) |

Rejected trials: a pivot gain of 2.0 or 2.5 (instead of 1.5) saved 0.5 to 3 % but exceeded the thresholds of the
historical tests with a 1 s delay; a stop in the middle of the whole rounded section (instead of the first portion
over 30°) was slightly slower and less accurate. A controller that slowed down to the minimum approach speed
blocked heading measurement (heading off by 20° never re-aligned at 5 cm/s). A deceleration of 0.35 m/s² (instead
of 0.25) only saved 0.1 to 0.7 % and exceeded the thresholds of the historical tests. On the computation side, a
point-in-polygon parity per cell reference point (`AreaIndex`), flagged by the JFR profile as ≈ 20 % of samples,
did not change the real duration of computation and preparation (2.7–2.9 s warm for the 7 zones on a Mac M4,
before and after): discarded.

## Results

Nominal robot (0.6 s delay, 0.11 °/s·u), tracking time from the first cutting point to the base approach point,
without recharging:

| Zone | 30 % | 50 % | 100 % |
|---|---:|---:|---:|
| Jardin d'Eden | 2,683 → 2,678 s (−0.2 %) | 1,630 → 1,593 s (−2.3 %) | 950 → 911 s (−4.1 %) |
| Aire de jeux | 6,825 → 6,786 s (−0.6 %) | 4,326 → 4,194 s (−3.1 %) | 2,694 → 2,572 s (−4.5 %) |
| Piscine | 12,936 → 12,903 s (−0.3 %) | 7,754 → 7,574 s (−2.3 %) | 4,394 → 4,247 s (−3.3 %) |
| Figuier | 5,131 → 5,108 s (−0.4 %) | 3,377 → 3,244 s (−3.9 %) | 2,212 → 2,086 s (−5.7 %) |
| Enfer | 4,677 → 4,644 s (−0.7 %) | 3,102 → 2,961 s (−4.5 %) | 2,044 → 1,928 s (−5.7 %) |
| Fosse | 11,070 → 11,022 s (−0.4 %) | 6,815 → 6,628 s (−2.7 %) | 4,072 → 3,896 s (−4.3 %) |
| Potager | 3,485 → 3,475 s (−0.3 %) | 2,247 → 2,178 s (−3.1 %) | 1,437 → 1,362 s (−5.2 %) |
| **Total** | **46,807 → 46,616 s (−0.4 %)** | **29,251 → 28,372 s (−3.0 %)** | **17,803 → 17,002 s (−4.5 %)** |

Robustness over the 175 missions per speed (all robots):

| Speed | Interrupted missions (> 30 cm) | Mean maximum deviation | Worst deviation |
|---|---:|---:|---:|
| 30 % (default) | 2 → **0** | 0.186 → 0.149 m | 0.333 → 0.199 m |
| 50 % | 3 → **0** | 0.191 → 0.150 m | 0.473 → 0.213 m |
| 100 % | 45 → **4** | 0.264 → 0.202 m | 0.761 → 0.307 m |

With the calibration from a previous mission (`-PexecutionProfileWarm=true`): 0 interrupted missions at 30 and
50 %, **1** at 100 %, mean maximum deviation of 0.18 m at 100 %; durations unchanged.

Tuning of the planner's duration model (speed, rotation, cost per stop) checked against the bench: the settings
"calibrated" on the robot (0.13 m/s, 27 °/s, 1.5 to 2 s per stop) yield routes that take 0.1 to 1.2 % longer to
drive than the current setting (0.3 m/s, 45 °/s, 0 s), which is kept.

At 30 % (default setting), duration is dominated by the set forward speed (0.13 m/s) and rotation limited to
≈ 27 °/s: the time saving is small, the main benefit is accuracy. The 4 missions still interrupted at 100 %
combine the longest delay (0.8 s) and a rotation gain off by 25 %.

## Reproduction

```sh
# Full bench (≈ 20 s on Mac M4): summary per speed, -PexecutionProfileVerbose=true for every mission
./gradlew :app:testDebugUnitTest --tests 'fr.luba.poc.simulation.ExecutionProfileTest' \
  -PexecutionProfile=true -PexecutionProfileSeeds=4 -i | grep EXEC
# Step-by-step trace of one case
./gradlew :app:testDebugUnitTest --tests 'fr.luba.poc.simulation.ExecutionProfileTest.traceExecution' \
  -PexecutionProfile=true -PtraceZone=Fosse -PtraceSpeed=100 -PtraceDelay=800 -PtraceDegs=0.13 \
  -PtraceSeed=813 -PtraceFrom=1391 -PtraceEdges=3 -i | grep TRACE
```

`RouteExecutionRegressionTest` (normal suite, ≈ 5 s) guards Aire de jeux and Enfer at 30, 50 and 100 %: no mission
beyond 27 cm at 30 and 50 % whatever the robot, nominal robot under 30 cm at 100 %, no blocking and no pivot in the
middle of a stripe, minimum average speed.

## Published point behind the centre of rotation (0.43.2)

The LUBA 2 publishes a point 8 cm behind its center of rotation (`DeadReckoning.PUBLISHED_POINT_BEHIND_M`, measured
on Oct 15 over 212 pivots). Tracking now controls the center, estimated from the published point and the fused
heading (`DeadReckoning.measured`). The bench models this offset (`RouteExecutionModel.Robot.publishedBehindM`); so
do the gateway's simulated robot and the obstacle simulation. Comparison over 4 reference zones × 3 speeds ×
4 robots, with and without compensation (`PublishedPointTest` for the Jardin d'Eden):

| Measure | Without compensation | With compensation |
|---|---|---|
| Mean real deviation (RMS) | 2.3 to 12.5 cm | 2.1 to 9.3 cm, better in 41 of the 48 cases |
| Maximum checked deviation (stop at 30 cm), 100 % | 20 to 36 cm | 17 to 24 cm |
| Maximum checked deviation, 30 % | 16 to 24 cm | 17 to 23 cm |
| Duration | reference | 2 to 8 % shorter |
| Maximum real deviation, 30–50 % | 12 to 20 cm | 16 to 23 cm |

The last row only occurs in the very tight curves of the outer lap (4 cm edges, radius ≈ 0.4 m, saturated
rotation): the robot cuts the corner there. Without compensation, the 8 cm offset acted as unintended anticipation.
With compensation, tracking returns to the behavior it was tuned for (original bench, where the published point was
the center). These curves remain an area for improvement.

## Pivot on a slope and slide map (0.44.0)

The simulated robot slides during its on-the-spot rotations (`SimulatedRobot.Params.pivotSlide`). On the Jardin
d'Eden route at 50 %, with the slope of Oct 15 (3.5 cm/s downhill at the bottom of the zone, 2 cm/s in the middle,
0.9 cm/s at the top), a first mowing learns the slide map (17 cells), then a second one uses it
(`SlideFieldTest.learnedSlideFieldBringsTheRobotBackOnTheEdenStripes`):

| Mowing | Mean real deviation | Max real deviation | Max checked deviation | Pivots | Duration |
|---|---|---|---|---|---|
| Without map | 4.1 cm | 17.6 cm | 17.8 cm | 65 | 1393 s |
| With the learned map | 3.5 cm | 18.4 cm | 18.3 cm | 56 | 1379 s |

The maximum remains in the tight curves of the outer lap (see above), where the slope plays no part.

## Limits

Bench model: ground, slope, wheel slip, tall grass, battery and the real firmware behavior are not modeled; real
gains and delays still need to be confirmed on the robot. Durations exclude recharging, docking and blade state
changes. Safety thresholds (30 cm, progress guard, envelope, obstacles, bumper) are unchanged. To be validated on
the robot, blades OFF first, under supervision.
