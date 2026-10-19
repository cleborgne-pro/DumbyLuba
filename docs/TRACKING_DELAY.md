# Navigation test: zigzag and "deviation above 30 cm" stop

## Observation (Oct 10, afternoon)

Navigation test over 3 stripes stopped after 2.09 m: "Route deviation above 30 cm (30.5 cm measured)". A go-to-point run recorded at 13:12 shows the cause: the lateral deviation oscillates by ±25–30 cm over ≈ 8 s (heading between 175° and 212°), at 100 % speed. The 30 cm check works as intended; it is the tracking that is underdamped.

## Measurements from the log

- Rotation: ≈ 0.10–0.11 °/s per unit (as expected), speed ≈ 1.1 mm/s per unit.
- **Effective delay command → measured heading ≈ 0.5 s** (heading fit), up to ≈ 0.8 s according to the zigzag. The former simulation assumed 0.2 s.
- A model at 0.7–0.8 s reproduces the ≈ 30 cm oscillation; without compensation, the stripe route exceeds 30 cm from 0.6 s of delay (0.37 m at 0.6 s, 0.38 m at 0.8 s, 0.58 m at 1.0 s).

## Fix

`DeadReckoning(commandDelayMs = 600)`: the pose is predicted at the moment the next command will take effect (including commands already sent), and the rotation/speed calibration accounts for the same delay. Stripe route and straight line at 100 %: ≤ 0.25 m for delays from 0.2 to 1.0 s (`PathFollowerTest` tests).

**Limited to the navigation test and go-to-point** (`runMapNavTest`) at first; superseded the same day: mowing, the
BLE route and the gateway's tracking (`LegRunner`) now use `DeadReckoning.ROUTE_COMMAND_DELAY_MS` (600 ms), see
[below](#real-mowing-zigzag-and-pivots-in-the-middle-of-a-stripe-oct-10-1455). Original text: mowing and the BLE route kept `commandDelayMs = 0`: on the Piscine route, which has many turns, the compensation brings no demonstrated benefit (0.46–0.8 m versus 0.22–0.48 m depending on the simulated delay), and this tracking still has to be validated separately.

The 30 cm limit is unchanged. To be validated on the robot, blades OFF, under supervision.

## Log readable from the Mac

The log is now copied to `/sdcard/Android/data/fr.luba.poc/files/diagnostics/<launch>/` (last 5 launches, 96 MB maximum per launch): `adb pull` is enough, no manual export needed. Test reports `campaign-*.json` included. See [logs](features/logs-and-data.md#logs).

## Cutting test while moving: blades restarted only once (Oct 10, 14:07)

First test: stripe 1 with blades running, blades stopped, 0.8 m link, then stop "deviation above 30 cm (30.3 cm)" **at the start of stripe 2**, before any blade rotation.

Causes found in the log:

1. **End of range declared on the predicted pose.** The link was "reached" while the real robot was still 18–29 cm from the corner. The next range started immediately and the deviation from the new stripe, measured on a position 814 ms old, was 30.3 cm.
2. **Full-speed start after a pivot on a predicted heading.** Real heading lagging by 20–25°: the link is driven diagonally (23 cm deviation).
3. **Deviation measured on two edges only.** The go-to-point run of 14:05 had skipped a 13 cm edge on the predicted pose: a 45 cm deviation reported while the published position was 6 cm from the route.

Fixes (navigation test and go-to-point):

- `RangeArrival`: at the end of a range (blade state change, destination), stop then wait for a position **later than the stop** (delay + 300 ms). If the robot stopped more than 8 cm short along the edge and more than 15 cm from the point, the approach resumes (at most 3 times). Blades only change state at the real corner.
- `DeadReckoning.pivotUnconfirmed()`: after a pivot, forward motion limited to 150 units (≈ 0.14 m/s) until the position that measures the heading reached.
- `PathFollower.deviation()`: deviation of the published position from the current edge and from edges already left over at most 1 m (lag of the position behind the predicted pose).

Test `actual cutting test of 2026-10-10 all ranges chain under 30 cm` (`PathFollowerTest`): same route, positions at 1 Hz, delays from 0.4 to 1 s, inertia, rotation gains from 0.08 to 0.13 °/s·u, deviation checked on the published position. Without the fixes: stop at 30.2 cm on edge 4 (as on the robot). With them: the 6 ranges run one after another, max deviation ≤ 0.26 m, range ends ≤ 0.17 m from the corner. The 30 cm limit remains unchanged.

## Regression: "presumed blockage, bumper contact not confirmed" at the end of a range (Oct 10, 14:20)

The next two tests: stop at 15.5–16 cm from the point, just above the 15 cm tolerance, which served both as the tracking target and as acceptance. The approach resume boiled down to a 55–65 unit command for 200 ms: the predicted pose immediately "arrived", whereas at these speeds the LUBA 2 hardly moves (≈ 1 cm/s). Since the progress guard was not reset during the wait at standstill, it counted that time: 3 s later, stop "bumper contact not confirmed" with no real contact.

Fixes:

- Tracking target at the end of a range at **12 cm** (`RangeArrival.TARGET_M`), acceptance on the measured position at **18 cm** (`ACCEPT_M`). The gap between the two forces a real resume of at least 6 cm predicted.
- Minimum forward speed on approach of **80 units** (`APPROACH_MIN_LINEAR`), versus 20 previously.
- Progress guard reset during the commanded stop (waiting for the position and the blades).

Tests: `approach resume after a short stop without presumed blockage` (`PathFollowerTest`) (short stop from 14 to 45 cm, 7 cm to the side, heading at 12°, friction from 0 to 70 units, guard already anchored). With the old settings, it fails as on the robot ("presumed blockage at 0.15 m"). The full real route is simulated with friction from 0 to 55 units: 72 cases, max deviation 0.26 m, range ends ≤ 0.19 m.

## Real mowing: zigzag and pivots in the middle of a stripe (Oct 10, 14:55)

Debug mowing on a painted zone (127 edges, 50 % speed): on straight stripes, deviation oscillating by ±10–20 cm (period ≈ 5 s) and pivots on the spot in the middle of a stripe (commands `0/±313`), 0.12 m/s on average.

Measurements from the log (fit of headings and of distances between positions): **delay command → effect ≈ 0.6 s** for pivots as well as forward motion, 0.11 °/s and 1.08 mm/s per unit.

Causes:

1. **Delay not compensated during mowing.** Only the navigation test predicted the pose at the moment the command takes effect; mowing corrected with a 0.6 s lag.
2. **Underdamped tracking.** Aiming at a point 40 cm ahead on the route: the lateral correction converged over 40 cm, hence the oscillation as soon as the delay was not perfectly compensated.
3. **Estimated heading skewed by a single noisy position.** A heading / travel direction difference > 20° was adopted at once: at 0.13 m/s, 1 cm of RTK noise is enough, the estimated heading jumped by 20–30° and the robot pivoted in the middle of the stripe.
4. **Pivot 15 cm before the corner** (arrival radius) and range end 12 cm before the point: the next stripe started offset by as much.
5. **Speed calibration during acceleration**: the robot had barely moved, the gain dropped by 30 % and the stop came too late.

Fixes:

- Mowing: `DeadReckoning(commandDelayMs = ROUTE_COMMAND_DELAY_MS = 600)`, slow forward motion after a pivot not yet measured, range end confirmed by `RangeArrival` (except for the base approach).
- `PathFollower`: line tracking — reference heading over 25 cm ahead of the robot, extended along the axis beyond a corner, corrected by `atan(deviation / max(0.8 m, 2 s × v))`; pivot at the real vertex (remaining ≤ 4 cm along the edge), range end at the point on the axis; braking at constant deceleration (0.25 m/s², 0.15 at range end); speed limited in tight curves; pivot mid-line only if the heading departs from the route by more than 25°.
- `DeadReckoning`: confirmed heading correction weighted by the distance traveled, a large difference adopted only if it is confirmed over two positions; speed calibrated only at steady state.

Test `TrackingPrecisionTest` (`painted zone mowing of 2026-10-10 in straight lines without zigzag or pivot in stripe`): real route of Oct 10 replayed (positions at 1 Hz, RTK noise 1 cm and 1°, delays 0.4–0.8 s + inertia 0.25 s, rotation 0.09–0.13 °/s·u, initial heading wrong by 15°):

| Speed | Max deviation | RMS on stripes | Pivots in stripe | Average speed | Range ends |
|---|---|---|---|---|---|
| Old tracking, 50 % (simulated) | 0.26 m | 6.4 cm | 23 | 0.118 m/s | 12 cm |
| 30 % | ≤ 0.16 m | 1.2 cm | 0 | 0.105 m/s | ≤ 8 cm |
| 50 % | ≤ 0.16 m | 1.5 cm | 0 | 0.158 m/s | ≤ 7 cm |
| 100 % | ≤ 0.19 m | 3.5 cm | 0 | 0.234 m/s | ≤ 12 cm |

The remaining max deviation is in the border curve (radius ≈ 0.7 m) and includes the lag of the published position. Known limitation: with 1 s of real delay (+ inertia, without friction), the robot overshoots a range end point by 20–25 cm (deviation ≤ 27 cm, below the 30 cm stop). The 30 cm limit is unchanged. To be validated on the robot.

## Execution bench for whole zones (Oct 10, evening)

The prepared route of each zone of the reference map is replayed with the real mowing loop,
175 robots modeled per speed (delays 0.4–0.8 s, rotation gains ±20 %, RTK noise). Fixes:
first heading confirmation over 15 cm of cumulative straight line, rotation calibration without the ends of
pivots, tight rounded turns treated as corners, corner approach without a ramp, normal forward speed
after a pivot if the last pivots were well predicted, reduced speed when the measured deviation grows.
Fix of a real mowing defect: the blade range end no longer waited for the position measured
after the stop (`arrival?.check(...) ?: true`). Interrupted missions (> 30 cm): 2 → 0 at 30 %, 3 → 0 at
50 %, 45 → 4 at 100 % (1 with the calibration remembered from one mission to the next); duration −0.4 / −3.0 / −4.5 %. Details, rejected attempts and reproduction: [TRACKING_EXECUTION.md](TRACKING_EXECUTION.md).
