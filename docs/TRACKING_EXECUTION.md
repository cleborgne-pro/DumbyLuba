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
| Heading confirmed again at the start of each drive, also for the drives chained by the sync with the Mammotion app (move, join, outline) | Confirmed over the short link before the outline (15 cm): 10° off on a few draws of the bench, outline driven askew, chassis into the element being recorded (7 draws out of 60) | After the first confirmation, the gap is measured again over the whole straight line while the robot keeps driving straight (up to 1 m, noise of the two ends only: ≈ 1.4° over 60 cm); for the drives of the sync, the gap confirmed by the previous drive over at least 40 cm of line is kept while the robot has not moved since ([`NavHeadingMemory`](../app/src/main/java/fr/luba/poc/simulation/NavHeadingMemory.kt): 30 cm, 5°, 1 min) |
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

```sh
# Turn bench (rolling U-turns, loops and curves per manoeuvre; ≈ 1 min): -PexecutionProfileZones=U|Potager|zamboni...
./gradlew :app:testDebugUnitTest --tests 'fr.luba.poc.simulation.TurnExecutionProfileTest' \
  -PexecutionProfile=true -i | grep "TURNS SUMMARY"
```

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

## Curves: tangent reference and feed-forward (2026-10-28)

Request of 2026-10-28: "improve the tracking of the computed route further, especially turns and manoeuvres". Measured
on the robot that day (Redmi gateway, Zamboni 59°, 1,353 positions): in-place pivots precise (p90 6 cm, no time lost);
**rolling U-turns of the Turn Optimizer at 0.07 m/s on the arc, deviation median 8.3 / p90 14.3 / max 24.9 cm (peak at
the exit), about 8 cm over the first metre after it, 14 to 15 s per U**. Plan and diagnosis:
[TRACKING_TURNS_AND_BORDER_CONTACTS.md](TRACKING_TURNS_AND_BORDER_CONTACTS.md#4-step-c--turns-rolling-u-and-tight-curves).

What the bench showed (C0, same seeds as below): the speed in curves was capped at 0.6 × half of the rotation limit
(30 %), the rest being kept for a proportional correction that had to carry the whole curve; the reference heading was
the chord 25 cm ahead of the followed edge, and on 2.4 cm edges the followed edge is reached up to 15 cm before its end
(`ARRIVAL_M`), so the reference led the tangent by about 25°: the robot cut inside the arc (≈ 15 cm), then pivoted
3 to 6 times per U to catch up with the exit heading.

| Change (`PathFollower`, mirrored in `gw_follow.cpp`) | Rule |
|---|---|
| Tangent reference (C1) | when the turn of the **arc vertices** (one edge shorter than 15 cm, `CURVE_ARC_EDGE_M`) over the 0.5 m window ahead, plus those skipped between the nearest edge and the followed one, exceeds `CURVE_TANGENT_DEGREES` = 3°, the reference heading is the tangent of the route at the nearest point (each vertex turn spread over 10 cm); lateral offset measured on that edge; straight stretches and the bends of polygonal paths keep the chord, bit for bit (`straight pass is unchanged`); so do the 0.5 m before the stop of a tight rounded turn and the 15 cm after any stop vertex (planned pivot) |
| Feed-forward (C2) | steer = (error × 1.5 + v × κ) / gain, bounded by 0.8 × the rotation limit; κ = signed curvature over ±10 cm, taken 0.25 s of travel ahead (`CURVE_LEAD_S`: the robot's rotation lags its command by about that much after the compensated delay); S-bends keep their sign |
| Curve speed (C2) | `curveSpeed` = min(0.6 × rotation limit × calibrated gain, 22 °/s) / κ (`CURVE_FF_SHARE`, `CURVE_MAX_RATE`), × 0.7 while the gain is not calibrated (kept) |
| No pivot inside an arc (C3) | in a curve and within 15 cm of the line, in-place pivot only beyond 40° (instead of 25°) |
| Exit of a curve (C3) | speed of the curve kept 0.3 m after its last curved vertex, then raised to the set speed over 0.5 m (`exitLimit`) |
| One model (C4) | `TurnOptimizer.drive` uses `curveSpeed` and `exitLimit`: a manoeuvre is planned at the speed the tracking drives it |

### Rolling U-turns, before → after

`TurnExecutionProfileTest` (opt-in, `-PexecutionProfile=true`), nominal robot (0.6 s, 0.11 °/s·u), averages per U.
Duration: from the first to the last curved vertex (true centre), then the first metre after it. Deviation: true
centre to the route inside the U. The isolated U is one Turn Optimizer U between two 3 m passes 0.75 m apart, with the
gains already calibrated (a U in the middle of a mission).

| Route, speed | Duration per U | Next metre | Speed on the arc | Deviation median / p90 / max | First metre after | Pivots per U |
|---|---|---|---|---|---|---|
| Isolated U, 30 % | 16.0 → 10.8 s | 8.0 → 8.8 s | 0.054 → 0.114 m/s | 14.2 / 15.4 / 15.6 → 3.7 / 4.8 / 4.9 cm | 13.3 → 4.5 cm | 4 → 0 |
| Isolated U, 63 % (planner default) | 13.2 → 8.8 s | 3.8 → 6.0 s | 0.054 → 0.125 m/s | 16.2 / 17.9 / 18.1 → 0.9 / 2.4 / 2.5 cm | 16.6 → 1.9 cm | 3 → 0 |
| Isolated U, 100 % | 11.6 → 9.6 s | 2.4 → 5.0 s | 0.072 → 0.125 m/s | 15.8 / 18.9 / 19.1 → 1.4 / 4.7 / 5.0 cm | 17.8 → 0.4 cm | 5 → 0 |
| Potager, Lawn friendly, 30 % (22 U) | 15.2 → 12.2 s | 8.3 → 9.4 s | 0.054 → 0.096 m/s | 15.3 / 17.2 / 19.7 → 1.0 / 2.8 / 4.1 cm | 18.5 → 4.8 cm | 3.5 → 0 |
| Potager, Lawn friendly, 63 % | 13.5 → 9.3 s | 3.7 → 5.8 s | 0.054 → 0.124 m/s | 15.5 / 17.5 / 19.2 → 1.6 / 3.4 / 5.3 cm | 17.0 → 5.5 cm | 3.2 → 0 |
| Potager, Lawn friendly, 100 % | 11.6 → 9.3 s | 2.4 → 4.9 s | 0.063 → 0.127 m/s | 15.1 / 17.7 / 22.1 → 2.2 / 5.0 / 13.6 cm | 21.0 → 6.1 cm | 2.6 → 0 |
| Aire de jeux, Lawn friendly, 30 % (13 U) | 15.0 → 12.7 s | 9.4 → 10.3 s | 0.054 → 0.091 m/s | 15.2 / 17.5 / 19.5 → 0.8 / 2.5 / 4.4 cm | 18.5 → 4.0 cm | 3.8 → 0 |
| Aire de jeux, Lawn friendly, 63 % | 13.4 → 9.6 s | 4.8 → 6.6 s | 0.054 → 0.122 m/s | 15.9 / 18.0 / 19.3 → 1.5 / 3.3 / 6.3 cm | 17.7 → 4.3 cm | 4.5 → 0 |
| Aire de jeux, Lawn friendly, 100 % | 11.9 → 9.4 s | 3.7 → 6.1 s | 0.063 → 0.119 m/s | 15.2 / 17.9 / 20.3 → 2.6 / 4.7 / 7.1 cm | 17.6 → 5.7 cm | 3.8 → 0 |
| Potager, Zamboni, 30 % (22 U) | 15.7 → 13.2 s | 8.2 → 9.4 s | 0.054 → 0.095 m/s | 14.2 / 16.4 / 18.5 → 1.2 / 2.8 / 4.6 cm | 16.3 → 4.3 cm | 3.0 → 0 |
| Potager, Zamboni, 63 % | 13.5 → 9.6 s | 3.7 → 5.8 s | 0.054 → 0.131 m/s | 14.4 / 17.6 / 19.0 → 1.6 / 5.1 / 10.1 cm | 17.1 → 11.3 cm | 3.3 → 0 |
| Potager, Zamboni, 100 % | 11.1 → 9.5 s | 2.4 → 4.9 s | 0.066 → 0.129 m/s | 14.0 / 18.1 / 21.0 → 1.8 / 4.2 / 13.6 cm | 20.0 → 6.1 cm | 2.4 → 0 |
| Jardin d'Eden, Zamboni, 30 % (11 U) | 15.5 → 13.3 s | 8.2 → 9.1 s | 0.054 → 0.097 m/s | 14.1 / 16.8 / 19.3 → 1.3 / 2.6 / 5.4 cm | 17.8 → 6.2 cm | 3.4 → 0 |
| Jardin d'Eden, Zamboni, 63 % | 13.8 → 9.9 s | 3.6 → 5.7 s | 0.054 → 0.121 m/s | 14.9 / 17.4 / 19.9 → 1.8 / 4.5 / 8.9 cm | 18.4 → 11.6 cm | 3.6 → 0 |
| Jardin d'Eden, Zamboni, 100 % | 11.7 → 9.6 s | 2.4 → 4.8 s | 0.063 → 0.120 m/s | 14.4 / 17.9 / 21.7 → 2.4 / 4.6 / 9.1 cm | 20.4 → 6.0 cm | 3.0 → 0 |

All four robots of `RouteExecutionRegressionTest` (delays 0.4 to 0.8 s, gains 0.09 to 0.13, heading off by 20°): no
pivot inside a U at any speed; largest deviation inside a U 14.3 cm at 63 % (19.4 to 20.4 cm before), 15.4 cm at
100 % (19.8 to 22.1 cm before). Curves of the outer lap and rounded borders, 63 %, nominal: p90 14.0 to 15.3 cm → 5.3 to
5.9 cm, max 14.3 to 17.2 cm → 10.8 to 13.9 cm.

Acceptance of the plan: p90 inside the U at most half of the baseline (0.13 to 0.31 of it) and max ≤ 15 cm (nominal,
every route and speed: 13.6 cm at most); no pivot in an arc. **Duration per U ≤ 0.65 × baseline is not reached**:
0.67 (isolated U) to 0.72 at 63 %, 0.68 to 0.86 at 30 %, 0.79 to 0.86 at 100 %; with the next metre, a U costs
17.2 → 15.1 s at 63 % on Potager. The arc speed is set by the share of the rotation limit (0.6, the largest of the
0.4 / 0.5 / 0.6 sweep, below) and by the 22 °/s cap; the hold after the exit costs about 2 s per U at 63 % (variants
below). First metre after the U above 6 cm in 5 of the 15 route × speed cases (up to 11.6 cm, Zamboni at 63 %). The
isolated U with the gains not yet calibrated (first U of a first mission: × 0.7 of the curve speed) stays at 14.4 s
at 63 % (14.6 s before), but at 1 cm of deviation instead of 17.

### Settings swept

Measured during the development, before the 22 °/s cap and the arc-edge rule. Feed-forward share (exit: no hold, 0.3 m
ramp), Potager Lawn friendly, nominal robot at 63 %, largest deviation inside a U over the four robots at 100 %:

| `CURVE_FF_SHARE` | Duration per U (63 %) | p90 / max (63 %) | Max, four robots, 100 % |
|---|---|---|---|
| 0.4 | 14.3 s | 4.7 / 6.6 cm | 11.5 cm |
| 0.5 | 10.9 s | 3.8 / 5.7 cm | 16.9 cm |
| **0.6** (kept) | 8.8 s | 4.2 / 7.1 cm | 20.7 cm |
| 0.7 (outside the plan, for reference) | 7.7 s | 4.6 / 9.0 cm | 24.3 cm |

Exit of the curve at 0.6 (nominal, 63 %, U then next metre, first metre after the U, Potager / Aire de jeux): no hold
and no ramp 7.0 + 3.5 s, 13.8 / 13.2 cm and pivots inside the U again; ramp over 0.3 m 8.8 + 3.9 s, 7.2 / 8.0 cm;
ramp over 0.5 m 8.9 + 4.3 s, 6.6 / 9.2 cm; **hold 0.3 m then ramp 0.5 m (kept, as planned)** 9.1 + 5.7 s, 7.0 / 5.3 cm.
Feed-forward lead 0.25 s against 0.35 s: no measurable difference (kept 0.25, the measured inertia).

Rotation cap (`CURVE_MAX_RATE`, full bench at 100 %, 175 missions, before the arc-edge rule): none (32 °/s) 6 missions
beyond 30 cm; 25 °/s 6; **22 °/s 1**; 20 °/s 3; 18 °/s 0. All interrupted missions are the robot with 0.8 s of delay and
0.09 °/s·u (gain believed 0.108 after calibration) at the exit of a curve. The painted route of 2026-10-10
(`TrackingPrecisionTest`) went to 28 cm at 100 % without the cap (limit 22 cm) and passes with it.

Arc-edge rule (`CURVE_ARC_EDGE_M`): with every bend counted as a curve, the outlines of the map sync (chamfered corners,
16.5 cm edges) were driven as curves and the chassis left the recording corridor (`MapSyncBenchTest`, 2 to 3 failures
out of 4 runs); counting only arcs of edges under 10 cm, the transit links of the bench (14 cm edges) lost the curve
tracking (6 missions out of 175 beyond 30 cm at 100 %); 15 cm keeps both (3 out of 175, `MapSyncBenchTest` 2 failures
in 24 runs, against 1 in 10 with the former tracking: that test is not deterministic).

### Whole missions (`ExecutionProfileTest`, 7 zones × 25 robots, Fast routes)

| Speed | Interrupted missions (> 30 cm) | Mean maximum deviation | Worst deviation | Nominal duration | Pivots (nominal) |
|---|---:|---:|---:|---:|---:|
| 30 % | 0 → 0 | 0.177 → 0.114 m | 0.212 → 0.166 m | 40,385 → 40,023 s (−0.9 %) | 1,400 → 1,266 |
| 50 % | 0 → 0 | 0.178 → 0.131 m | 0.209 → 0.223 m | 24,796 → 24,377 s (−1.7 %) | 1,429 → 1,282 |
| 100 % | 3 → 3 | 0.209 → 0.186 m | 0.372 → 0.314 m | 15,063 → 15,016 s (−0.3 %) | 1,454 → 1,289 |

The gain is in the curves of the outer lap and the rounded borders (Fast routes have no rolling U): the maximum of
most missions was there.

### The Turn Optimizer follows

`TurnOptimizer.drive` now uses the same curve speed and exit (`PathFollower.curveSpeed`, `exitLimit`): at the planner
speed (0.3 m/s, 63 %) a rolling U between passes 0.75 m apart, with 0.7 m of each pass, is planned at 12.8 s instead of
about 19 s, against 16.8 s for the pivots. Consequences: Fast picks the U when passes are at least 0.7 m apart (spacing
of 0.7 m or more); Lawn friendly picks the rounded loop rather than two pivots between neighbouring passes when it fits
(loop 6 s longer, 180° less in place); the Zamboni of Aire de jeux at 0° now finds its order (8 cells, 81 m of transit
against 104 m in Zigzag) instead of falling back to the Zigzag order. No penalty for the rolling U (plan: only if the
field ratio exceeds 1.3 after this change, `ARC_TIME_FACTOR` not added).

"4.6 s estimated" per U (open point of the plan): neither the planner's flat model (0.3 m/s and 45 °/s: 7.8 s for a U
of 1.15 m) nor `TurnOptimizer.drive` (about 14 s of arc before, 8 to 9 s now) gives it. The only estimate of that order
is the one the map screen shows for mowing (`MowingForecaster`: route length at the task speed, adjusted by the
history factor, "pivots excluded"): 1.15 m at 0.3 m/s is 3.8 s, 4.6 s with a history factor of 1.2. That estimate does
not model the curves; it is left as is because its history factor already absorbs the real duration of the turns
(adding the curve time would count it twice), and its task speed (0.3 m/s) is not the tracking's speed setting.

### Logs (C5)

At the start of a route, `route_manoeuvres` lists the manoeuvres found from the edges (`RouteManoeuvres`: rolling U,
rounded loop, curve; edge range, degrees, metres, planned duration). While it is driven, `route_curve_stats` gives for
each U, loop or curve of 45° or more: duration, median speed between positions, largest distance to its edges,
source `local` (Fold tracking) or `gateway` (progress of the Redmi or ESP32 gateway with the published position). No
frame or `SimEdge` field changed. The same detection measures the bench, so the next field log compares directly.

## Limits

Bench model: ground, slope, wheel slip, tall grass, battery and the real firmware behavior are not modeled; real
gains and delays still need to be confirmed on the robot. Durations exclude recharging, docking and blade state
changes. Safety thresholds (30 cm, progress guard, envelope, obstacles, bumper) are unchanged. To be validated on
the robot, blades OFF first, under supervision.
