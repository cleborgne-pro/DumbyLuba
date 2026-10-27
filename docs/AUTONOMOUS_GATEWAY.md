# Autonomous gateway: the Fold generates the route, the Redmi follows it

## Implementation (2026-10-12)

**Implemented on 2026-10-12 and tested in simulation first** (simulated robot, including behind a real simulated
Bluetooth link: tracking, safety stops and Wi-Fi drops). It has since been used on the robot: routes handed to the
Redmi gateway during the tests of Oct 14 to 16 (return to base on Oct 15, mowing of a zone on Oct 16), whose incidents
led to the fixes of 0.38.0, 0.47.0, 0.60.1 and 0.62.0 (see the [CHANGELOG](../CHANGELOG.md) and
[ANDROID_GATEWAY.md](ANDROID_GATEWAY.md#gatt-errors-during-mowing-and-recharge-2026-10-16-1325-and-1349)). The
checklist "To validate on the robot" below has not been run as a whole (not verified).

### What this changes

Before: the Fold drove the robot frame by frame through the Redmi. A Wi-Fi silence of more than 2 s stopped the robot.
Now, for **every mode that follows a route** (mowing, signal survey — big loop or grid —, transits,
return to base, resume after recharge), the Fold prepares the route and the Redmi follows it on its own:

- **Good Wi-Fi**: the Redmi streams progress, telemetry and signal survey samples live; the Fold shows the
  map, the trace and progress as before, and STOP interrupts tracking.
- **Wi-Fi down**: the robot **carries on by itself**. The Fold no longer treats the drop as a link loss (telemetry
  monitoring is suspended, the route status shows "Gateway Wi-Fi lost for N s · the robot
  carries on by itself") and retries the link every second. When it comes back, the gateway hands over the tracking state, the samples
  taken during the drop (no gaps, no duplicates) and the outcome.
- **Terrain** (Slope and Terrain layers of signal mode): the tilt of the phone mounted on the robot is attached to each sample
  by the Redmi itself (its accelerometer), so also during a Wi-Fi drop.
- **Signal mode**: an area without Wi-Fi no longer stops the test; it shows as a hole in the robot's Wi-Fi layer
  and the Bluetooth / RTK / GNSS samples are all kept.

Obstacle bypasses, recharging, docking, leaving the station and the back-off after a contact **stay
on the Fold** (they need the planner and are short): at each of these points, the robot is stopped and waits for the Fold.
Exceptions added later: a gateway announcing `dock-exit` runs the base exit itself
([below](#leaving-the-station-through-the-gateway-2026-10-13)); docking uses the official Mammotion return, requested by
the gateway itself when the Fold is unreachable (0.38.0, see "Known limitations").

### Split of responsibilities

```
Fold: map, route generation, envelope surfaces, supervision, STOP, bypasses, recharge, docking
   │  route (edges) + free physical surface (WKB) sent once each, then "follow from edge N"
   ▼
Redmi: LegRunner (same loop as RobotBle.followPath), RobotObserver (telemetry, errors, lift), safety stop
   │  Bluetooth a few centimeters away
   ▼
LUBA 2
```

- The on-board tracking is **the same code** as on the Fold (`autonomy/LegRunner.kt`, a line-by-line port of
  `followAll` / `followPath`: blade ranges, manual mode for the bumper, confirmed blade gate, height, predicted pose,
  30 cm deviation, lack of progress, stall, chassis envelope, obstacles, battery).
- Envelope check: the Fold sends only its planner's **free physical surface** (`TrajectoryPlanner.physicalSurface`,
  WKB); the Redmi does not have to rebuild the geometry (8.9 s + 3.5 s measured). The check (`TrajectoryEnvelope.motionCollision`)
  is the same code on both sides.
- Robot link keepalive (sync every 5 s, report subscription every 8 s): handled by the Redmi
  during tracking; the Fold writes nothing more to the robot (`RobotBle.send` is held back as long as tracking is handed to the gateway).
- Protocol: version 2 unchanged. The gateway announces `caps=autonomy` in WELCOME (ESP32 too since its firmware 0.3.0); without this announcement the Fold
  drives frame by frame as before. The pilot answers with `LEG_HELLO` before opening the robot link: an old pilot, which
  does not do this, receives no tracking frame (it would take them for a fault). Frames 0x80–0x97 (`autonomy/LegFrames.kt`): blob upload (route, surface, with CRC),
  `LEG_START`, `LEG_ABORT`, `LEG_OBSTACLES`, `LEG_SYNC`, `LEG_ACK`; return frames `LEG_PROGRESS`, `LEG_SAMPLE`, `LEG_RESULT`,
  `LEG_STATE`, `LEG_EVENT`. Pilot setting: `AutonomySettings` (on by default).

### Tracking outcomes (`LegOutcome`)

| Outcome | The Redmi has… | The Fold… |
|---|---|---|
| `DONE` | followed the whole route, zero speed written | moves to the next step (docking, next range) |
| `LOW_BATTERY` | stopped at edge N, blades OFF | starts the return to base and recharge (unchanged) |
| `CONTACT` | stopped as soon as the error message (-1014) arrived, pose and trace of the last points | prepares the bypass (`avoidObstacle`, unchanged) |
| `OBSTACLE_ADDED` | stopped, added obstacle not foreseen on the trajectory | same |
| `FAILED` | stopped, blades OFF (stale position, deviation > 30 cm, envelope, lift, robot error, Bluetooth link, gateway battery…) | stops the mission with the reason |
| `ABORTED` | stopped on request | — |

In every case the Redmi writes the safety stop itself (blades OFF if they may be spinning, then zero speed) **before**
returning the outcome: a robot standing still out of Wi-Fi range never has its blades running.

### Safety rules specific to autonomy

- **The pilot takes back control with any write**: a `WRITE` frame from the pilot during tracking interrupts it
  first (the Fold, for its part, announces its stop with `LEG_ABORT` before any stop command).
- **Deliberate departure** of the pilot (link closed): tracking is interrupted, robot stopped. **Wi-Fi drop**: tracking continues.
- **No pilot for more than 20 min**: safety stop (`GatewayAutonomy.ORPHAN_LIMIT_MS`).
- **Battery of the phone mounted on the robot** below 5 % and not charging (`GatewayHealth.CUTTING_MIN_BATTERY` = 4 %): blades
  forbidden (a cutting range stops or is not started). Moves with blades OFF, including the return to base, remain
  allowed down to 0 %: if the Redmi shuts down, the BLE link drops and the robot stops by itself.
- **Critical overheating** of the phone mounted on the robot (thermal level ≥ 4): safety stop.
- **Freshness**: tracking only starts if the robot state and position received by the gateway are less than 2 s old; along
  the way, the same guards as on the Fold (`NavSafety`) apply to the telemetry received by the Redmi.
- A stop requested on the Fold during a Wi-Fi drop is kept and delivered as soon as Wi-Fi returns (link retried
  for 60 s after the command). The Fold does not keep trying forever: after 25 min of outage (the gateway stopped on its own at 20 min),
  the link is declared lost.
- The Fold drains its write queue to the robot (up to 4 s) before starting tracking: a write arriving after `LEG_START`
  would interrupt tracking.
- A restarted Redmi (or a start order lost in a Wi-Fi drop) loses the tracking: on reconnection, the Fold sees "tracking
  lost" and, since 0.47.0, resumes the route from the robot's actual position instead of stopping the mission, at most
  4 times per mission ("…: 4 resumes already done, link too unstable" beyond that; `RobotBle.MAX_LOST_LEG_RECOVERIES`).
  Tracking the Fold is not expecting (app relaunched) is interrupted as soon as it reconnects.
- **Docking and recharging** (0.62.0): during the recharge cycle (return, Mammotion docking, charging), the Fold holds the
  gateway link through Wi-Fi outages as during tracking (waiting up to 1 min for a fresh state during a drop), with no
  maintenance write during the outage. If the link drops anyway, mowing resumes by itself once the robot is charging
  and the link is back ([mowing resume](features/mowing-resume.md#automatic-resume-after-losing-the-gateway-during-recharging)).
- While recovering an outage, the Fold logs a status line every 30 s and gives up after 25 min ("gateway unreachable
  for 25 min · …", `GatewayLink.OUTAGE_LIMIT_MS`).

### Gateway fallen off the robot (1.3.0)

The Redmi lies on the robot's back. If it falls on the lawn during a route, the robot drives on until the Bluetooth
link drops, then mowing goes on over the phone at the next passes. The gateway now notices it from its own Bluetooth
signal to the robot (`autonomy/GatewayDetachment.kt`, wired in `GatewayRobot`):

- During every tracking, the gateway reads the RSSI of its robot link once a second (`Op.Rssi` without owner, one at
  a time in the GATT queue; local Bluetooth, answered in a few ms). The pilot's own RSSI reads through the Wi-Fi are
  unchanged (every 30 s, never in motion).
- **Mounted level**: median of the readings within 6 dB of it. It is only **confirmed** after 5 readings while the robot
  drove 3 m from where the learning started, all within ±6 dB of the median, at −50 dBm or better (−24 dBm measured on
  2026-10-11). A signal that changes with the robot's position before that (phone on the ground on purpose, used as a
  fixed relay) restarts the learning: it is never confirmed, nothing is concluded. The level is kept from one tracking
  to the next and relearned when the gateway changes robot.
- **Drop point**: robot position (fresh, ≤ 2 s) at the last reading at the mounted level, so within one reading of
  where the phone fell (0.4 m at 0.4 m/s).
- **Verdict**: 3 consecutive readings at least 15 dB below the mounted level (about 20 dB at 2 m in free field) with the
  robot at least 1.5 m from the drop point. Weak readings with the robot still next to the drop point (stopped,
  pivoting) are not enough. On a verdict the gateway stops the robot at once (blades OFF if they may spin, zero speed,
  as for a lift), the tracking ends `FAILED` with "Gateway fell off the robot near x=…, y=… (Bluetooth −52 dBm instead
  of −24 dBm, robot 1.6 m away)", and a `gateway_detached` tracking event carries the fields `x`, `y`, `rssiDbm`,
  `mountedDbm`, `robotX`, `robotY`, `distanceM`, `probable`.
- **Stale drop point** (2026-10-25): the drop point is only trusted if the first weak reading of a streak comes at
  most 3 s (`ANCHOR_FRESH_MS`) after the last mounted reading, or with the robot within 0.75 m of it. Otherwise the
  readings stopped in between (between two trackings) while the robot moved, and nothing tells where a phone would
  have fallen: the level is learned again from the new readings (no stop). Field case: −19 dBm until the end of a
  tracking at 15:54:46, −51 dBm from the next one at 15:54:50, verdict "fell near" a point 1.5 m back left a minute
  earlier, while the same phone then drove the robot 12 m with a steady −24 dBm. After a verdict the drop point is
  known: the next tracking concludes again from it while the phone stays on the ground.
- **Bluetooth lost first** (robot link closed during a tracking): if the last reading was already 10 dB below the
  mounted level and the robot 0.75 m or more from the drop point, the same verdict with `probable` set ("probably
  fell"); otherwise a plain Bluetooth loss.
- **Pilot**: the event rebuilds the text in the pilot's language, sets `RobotUi.gatewayDrop` (red **Gateway** marker on
  the map, cleared at the next tracking handed to the gateway), shows the status and raises the critical
  `GATEWAY_DETACHED` alert with the position. The mission stops on the tracking outcome (no automatic resume: the
  text is not a link loss for `DockResume`); the task history labels the interruption "Gateway fell off the robot".
  Tracking events are not replayed after a Wi-Fi outage: if the event is lost, the outcome text still carries the
  position (in the unexpected-stop alert and the history), without the map marker.
- Not covered: tracking driven frame by frame from the pilot (switch off, ESP32 gateway before 0.3.0), and moves made by the pilot
  between trackings (bypass, back-off, recharge); the detection resumes at the next tracking.

Checks: `GatewayDetachmentTest` (9: fall detected within one reading of the drop point a few seconds later, noisy
steady signal over 300 readings, robot stopped next to the drop point, fixed relay never confirmed including a pass
close to it, no confirmation without driving, link lost while falling or with a steady signal, rearm, fields
round trip), `GatewayAutonomyTest` (real TCP session and simulated robot behind the Bluetooth link: phone falling at
x = 4 m on a 9 m cutting route → robot stopped 1.4 to 4 m further, blades OFF, outcome `FAILED`, event within 0.8 m
of the drop point; steady −26 ± 3 dBm over 7 m → `DONE`, no event). Not yet tried on the robot.

### Known limitations

- The route speed set on the Fold is the one at the **start of tracking**; changing it along the way only takes effect at the next tracking.
- Bypass, recharge and leaving the station need Wi-Fi (short sequences near the base): a robot stopped
  at one of these steps waits for the Fold.
- **Docking without a pilot (0.38.0)**: if the Fold is unreachable when a route ends at the approach point (return to
  base, end of mowing), the gateway itself requests the official Mammotion return, under the same conditions as the Fold:
  stop confirmed, robot no more than 20 cm from the approach point on a position less than 2 s old, nominal protection,
  bumper free; blades OFF and exit from manual mode first (`OrphanDock`). A Fold that comes back finds the robot
  returning or charging: it only monitors the charging contact, without rewriting anything. Tests of Oct 14: the
  Fold's Wi-Fi had dropped during the return, and the robot had stayed stopped in front of the base for nearly 1 h 20 min.
- A bumper contact or an obstacle added during a Wi-Fi drop stops the robot; it waits for the Fold (no bypass without it).
  It stays still, blades OFF, with no automatic return to base; the back-off and the bypass (including the direct link of
  transits since 0.37.0) are computed by the Fold when it returns. See
  [bypass, who computes what](OBSTACLE_BYPASS.md#who-computes-what-in-real-mowing-fold-or-gateway).
- ESP32: ported in firmware 0.3.0, with its own limits ([below](#esp32-gateway-2026-10-23)).

### Checks done (none on the robot)

- `LegRunnerTest` (14 at the time, 28 today): simulated robot (0.6 s delay, inertia, friction, RTK noise) — full tracking, survey without blades, low
  battery, contact, added / known obstacle, stale position, host failure, lost link, leaving the envelope, resume at an
  edge, propagated cancellation (not taken for a safety stop), missing robot data.
- `EndToEndLegTest` (11): pilot ↔ gateway in memory — route sent once, Wi-Fi dropped mid-tracking and replay of
  samples with no gaps or duplicates, outcome delivered on return, deliberate departure, requested stop (including right after the start, 40 rounds),
  pilot absent too long, unexpected tracking, obstacle list too long for one frame, codecs.
- `GatewayAutonomyTest` (11, real time): real TCP session, real `GatewayRobot`, simulated robot behind the Bluetooth link that
  answers in BluFi frames — normal end, Wi-Fi dropped without a safety stop, contact, lift with blades OFF (and nothing restarts
  afterwards), pilot write, deliberate departure, host battery, silent robot, old pilot that receives no tracking frame.
- `GatewayLinkAutonomyTest` (3, real time): the pilot's real `GatewayLink` — Wi-Fi tunnel dropped mid-tracking then restored (never
  declared lost, outcome delivered), stop command given during the drop delivered on reconnection, and without the hold a drop is still a link loss.
- Full project suite at the time: 1,075 tests (with terrain mode merged), 0 failures (the 21 skipped are the optional bench tests).

Defects found and fixed during these tests or in the independent code review:
- **Race condition**: telemetry arrives on a different thread from the tracking loop; a report arriving between reading the clock and the
  check gave a negative age ("stale state"). The robot view (`RobotView`) is read in one block before the clock.
- Tracking writes still queued (keepalive blades ON, setpoint) that went out after the safety stop: purged before the stop.
- Cancellation of tracking taken for a safety stop (`CancellationException` is an `IllegalStateException`): propagated.
- Frames exceeding 4 KB (obstacle list, outcome with trace) that would have dropped the link: maximum payload raised to 16 KB,
  tracking refused locally (and done here) if the list still exceeds one frame.
- Stop command given during a Wi-Fi drop lost by the old writer, or sent before the robot link was reopened:
  double-ended queue, stale writer hands the frame back, robot link reopened before any held frame.
- Stop not confirmed by the gateway (`stopConfirmed` false) treated as a safety stop on the pilot side.
- Link hold set even though sending the tracking had failed; route decoding under the safety loop lock;
  abandoned uploads never released; observer left armed when the robot link dropped during tracking.
- Stop requested right after `LEG_START`, before the tracking thread had taken over: the tracking body never ran and the
  slot stayed occupied (atomic start, slot released in every case, test over 40 rounds).
- Route evicted between the check and taking the slot; refusal of another tracking (or of an invalid frame) taken for a refusal of the
  current tracking; tracking not stopped when the pilot gives up on a local fault (the stop is sent in every case).
- Pilot from an older version that would have received an unknown frame (`LEG_STATE`) and dropped the link: tracking frames sent
  only after `LEG_HELLO`.

To unify: the tracking loop exists in two copies (`RobotBle.followPath` for local tracking, `LegRunner` for the gateway).
It is ported line by line and the tests compare their behavior, but any threshold or guard change must be made
in both places until local tracking is moved onto `LegRunner` with a local `LegEnv`.

Known difference from tracking on the Fold: the signal survey sample is taken within 30 cm of the cutting edges neighboring the current edge
(the Fold takes it within 30 cm of any survey contour); the Fold's "tracking" phase is replaced by the tracking's
"main route" flag. Same samples as long as the robot stays on its route (deviation ≤ 30 cm checked).

### To validate on the robot

Measurement (since 1.2.0): open each task in the history, section **Route tracking**
([mowing history](features/mowing-history.md#route-tracking-quality-since-120)). **Tracked by** must read
"Gateway on the robot, autonomous tracking" with at least one tracking handed to the gateway; compare **Deviation from
the stripes** (median, 95 %, max) with the same zone mowed from the Fold over direct Bluetooth, and read the Wi-Fi
outage in **Position holes over 15 s**. Reference in simulation (`RobotBleTonteTest`, direct Bluetooth): median 1 cm,
95 % 12 cm, max 29 cm, no hole. Write the figures in a dated report in `docs/`.

1. Short mowing then a big-loop signal survey, good Wi-Fi: same trajectories as with tracking on the Fold (compare the deviation from the route).
2. Cut the router's Wi-Fi during a big loop: the robot must carry on, the Fold must show "Wi-Fi lost… carries on by itself", and the
   map must fill in on return (hole in the robot's Wi-Fi layer, continuous Bluetooth / RTK samples).
3. STOP on the Fold during and after a drop; bumper contact during tracking; low battery (return to base); lift.
4. Redmi: phone battery life during tracking (screen off by default since 0.27.9), heating.


## ESP32 gateway (2026-10-23)

The ESP32 gateway (firmware 0.3.0, `firmware/esp32-gateway`) runs the same autonomous tracking as the Redmi. The
Kotlin code is ported to C++ in `lib/gwcore` and the pilot needs nothing new: the gateway announces
`caps=autonomy,dock-exit,ota` in WELCOME.

| Kotlin | C++ (`lib/gwcore/src`) |
|---|---|
| `LegFrames`, `LegSpec`, `LegResult`, `LegProgress`, `LegBlobs` | `gw_leg_frames` (byte compatible with `DataOutputStream`, modified UTF-8, CRC32) |
| `LegRunner`, `LegEnv` | `gw_leg_runner`, `gw_leg_env` |
| `GatewayAutonomy`, `OrphanDock` | `gw_autonomy` |
| `GatewayDetachment` | `gw_detachment` |
| `RobotObserver`, `TelemetryReducer`, `RobotErrorMonitor`/`Catalog`, `LiftGuard` | `gw_observer`, `gw_telemetry`, `gw_robot_errors` |
| `NavSafety`, `SelfCheck`, `CollisionLatch`, `ProtectionPause`, `OffMapLatch`, `HeightSequence`, `MotionSetpoints`, `ModelSupport` | `gw_nav_safety` |
| `PathFollower`, `RangeArrival`, `RouteBladeGate`, `MotionProgressGuard` | `gw_follow` |
| `DeadReckoning` | `gw_reckoning` |
| `ReturnDrift`, `PivotSlide`, `RouteRecharge`, `SlideField`, `PivotSlideMeter`, `DockExit`, `SignalCoverage` | `gw_drift` |
| `TrajectoryEnvelope`, `AreaIndex`, WKB, `FollowObstacleGuard` | `gw_envelope`, `gw_geometry`, `gw_obstacle_guard` |
| `GatewayRobot` (host: own writes, coalesced setpoints, urgent stop, own RSSI, docking without pilot) | `src/robot.cpp` |
| `GatewaySession` (`LEG_HELLO`, frames only to a pilot that announced them) | `src/session.cpp` |

Differences from the Redmi:
- **Memory**: blobs of 1.5 MB at most (8 MB on Android), 2 routes and 2 surfaces kept (4 on Android), an upload refused
  when the free memory is short after forgetting the older blobs ("Mémoire insuffisante sur la passerelle ESP32…"; the
  buffer and the decoded form must fit in the largest free block), 30,000 survey samples per tracking
  at most (8 h at one per second). Large blocks go to the 4 MB PSRAM.
- **No phone sensors**: no tilt in the survey samples (no Slope/Terrain layer), no thermal stop. Blades are forbidden
  when the board battery reads 0 % (IP5306 level, below 25 %) without power, with the Android text.
- **Clock**: the 20 min without a pilot are measured on the monotonic clock (not shifted by the NTP synchronisation).
- **Envelope**: `leavesStraight` computes the new ground swept outside the surface exactly (Green's formula on the
  nearby edges) instead of the JTS boolean operations (difference below 1e-13 m² on 437 reference cases); the surface
  validity check covers closed rings, crossing or overlapping edges and holes inside their shell, not single touching
  points. The C++ envelope gives the same answers as JTS on the reference map (1000 poses, 800 translations, 1000 escapes).
- **Gateway page**: **Stop the autonomous tracking** (robot stopped, blades OFF); updates over Wi-Fi and **Release the
  robot** are refused while a tracking runs.
- Tracking stop texts are the French ones (as a French Redmi), translated by the pilot like the other wire texts.

Checks (computer, `pio test -e native`): `test_autonomy` 43 (frame codecs; the `LegRunnerTest` scenarios on the
port of `SimulatedRobotEnv`; gateway end to end: upload, refusals, Wi-Fi drop with sample replay, abort, deliberate
departure, stop right after the start over 40 rounds, pilot absent too long, docking without pilot, unconfirmed stop;
gateway fallen off; memory short), `test_telemetry` 32, `test_geom` 20, `test_follow` 59, `test_core` 27. On the board
(no robot): capabilities announced, a 990 KB route (30,000 edges) accepted 8 to 11 s after the start of its upload (about
2 s of decoding) three times in a row, a 2,000-vertex surface indexed in 0.7 to 1.2 s, the start refused with "Robot
non connecté en Bluetooth sur la passerelle"; internal heap low point 48 KB. Not tried on the robot, nor measured
on the board: the time of a tick (doubles emulated on the ESP32) and the robot's MTU (with MTU 23, a movement command
takes two frames and the 4-frame queue limit may be reached) are to be checked at the first real tracking.

## Leaving the station through the gateway (2026-10-13)

Test of Oct 13: the base exit, still driven from the Fold over Wi-Fi, failed at around 20 cm. A gateway
that announces `dock-exit` runs it itself (`LegSpec.dockExit`, a one-edge route base → link point, `DockExit`
building block shared with the pilot in direct Bluetooth); an older gateway lets the Fold drive it. Tracking
now renews the report subscription as soon as it starts.

## Chosen split (study of 2026-10-11)

```
Fold: map, route generation (all zones), supervision, STOP
   │  full route sent once (JSON, ~550 KB for the 7 zones)
   ▼
Redmi A1 (on the robot): route tracking at 5 Hz, on-board checks, BLE setpoints
   │  Bluetooth a few centimeters away (~40 ms)
   ▼
LUBA 2
```

Wi-Fi leaves the control loop: on Oct 11 the base exit was interrupted by writes of 270 to
730 ms caused by the Redmi's Wi-Fi (`ANDROID_GATEWAY.md`), while the Bluetooth leg stayed fast.

## Is the Redmi A1 powerful enough for tracking? Yes (measured on 2026-10-11)

Phone: Redmi A1 (220733SG), MediaTek Helio A22 (MT6761), 4 × Cortex-A53 at 2 GHz, Android 12 Go in 32-bit
(`armeabi-v7a`), 1.8 GB of RAM, `ro.config.low_ram=true`, Java heap limited to 128 MB.

Current gateway load (relay only, pilot attached): 0 to 4 % of one core, 127 MB resident.

### Bench

`FollowerDeviceBenchmark` (test APK, `mode=follower`) replays on the phone **a mowing of all the zones**
of the reference map (Jardin d'Eden, Aire de jeux, Piscine, Figuier, Enfer, Fosse, Potager): 6,529 edges,
5.2 km, generated on the Mac by `FollowerRouteExport` (same planner as the Fold, real base, 25 cm
spacing, 95 % coverage). At each 200 ms tick, the computations of the `RobotBle` tracking loop:

- decoding of three **real** telemetry frames (Fold log of Oct 11, reports at 5 Hz): BluFi,
  protobuf, `TelemetryReducer`;
- pose correction and prediction (`DeadReckoning`, 600 ms command delay), `PathFollower` command,
  deviation from the route;
- envelope check on the navigation geometry (`motionCollision`);
- BluFi encoding of the setpoint.

Closed-loop mode (`stepM=0`): a kinematic robot executes the setpoints computed by the Redmi. No
ViewModel, service or robot command.

### Results (Redmi A1, closed loop, full mission)

| Measurement | Value |
|---|---|
| Simulated ticks | 489,709 (27.2 h of simulated mowing, 41 ranges) |
| Compute per tick, average | **0.25 ms** CPU, i.e. **0.12 % of one core** at 5 Hz |
| Duration per tick p50 / p95 / p99 | 0.14 / 0.69 / 0.99 ms (budget: 200 ms) |
| Ticks > 20 ms / > 100 ms | 86 / 4 out of 489,709 |
| Worst tick | 847 ms (system pause, in telemetry decoding) |
| Maximum deviation from the route | 28.5 cm, never above 30 cm |
| Decoding of the received route (544 KB) | 0.17 s |
| Navigation + physical geometry (once, at start) | 8.9 s + 3.5 s |
| Memory added by the geometry | 1.7 to 4.5 MB |

Robot simulated on rails, at 3 cm per tick (0.15 m/s): 0.33 ms CPU per tick on average, p99 1.7 ms.

### Conclusion

- **Route tracking: more than 100× headroom** on the 200 ms budget, negligible load. The Redmi can track
  the whole mission.
- **Rare pauses of up to 0.85 s** (Android Go, low memory): 4 ticks out of nearly 500,000. These pauses stay under
  the 1.5 s GATT operation timeout, but the implementation must use a dedicated high-priority thread and limit
  allocations in the loop. Compare with Wi-Fi: 80 writes out of 503 above 500 ms on Oct 11.
- **Preparation at start: ~12 s on the Redmi** to rebuild the geometry for the envelope checks, once,
  with the robot stopped. This can be done during the base exit, or as soon as the route is received.
- **Generation stays on the Fold**: 9.6 s on the Mac for the 7 zones, and the heaviest step of the pipeline.
  Obstacle bypasses also stay on the Fold (measurement of Oct 12 below).
- Bench limitations: ideal kinematic robot (no slip and no real delay); 6 ranges not finished within the
  tick budget (end-of-range docking not simulated); the simulated robot's envelope alerts do not validate
  safety. The bench measures computing power, not field behavior.

Raw measurements (0.25 m step, 0.03 m step, closed loop): `AUTONOMOUS_GATEWAY_MEASUREMENTS_2026-10-11.jsonl`.

## Obstacle bypass: on the Fold (measured on 2026-10-12)

Bench `FollowerDeviceBenchmark.runBypass` (`mode=bypass`): bumper contact in the middle of 12 cutting passes spread over
the all-zones mission (6,529 edges), back-off along the traveled trace then repair of the remaining route by
`ObstacleBypass.repair`, the function used in real mowing, on the geometry built by the phone. Robot stopped,
blades OFF during this computation in real mowing.

| Phone | Geometry (once) | Repair p50 | Repair max | Successful repairs |
|---|---|---|---|---|
| Redmi A1 | 12.5 s | **12.1 s** | **31.1 s** | 10 / 12 |
| Fold (SM-F971B) | 1.4 s | 1.5 s | 3.4 s | 10 / 12 |

- The Redmi is **8 times slower**: 12 to 31 s stopped at the contact, versus 1.5 to 3.4 s for the Fold. The Redmi
  would stop the robot (blades OFF, zero speed) and ask the Fold for the repair, which sends back the repaired route (split
  chosen and implemented on Oct 12: `CONTACT` outcome). With no reachable Fold, the Redmi can compute it itself, robot stopped, or leave the
  robot stopped.
- The two failures are identical on both phones ("Back-off impossible after the contact" at the start of a zone,
  synthetic trace too short or chassis against an edge): these are safety refusals by the repair, not an
  effect of computing power.

Raw measurements: `GATEWAY_BYPASS_MEASUREMENTS_2026-10-12.jsonl`.

## Reproducing

```bash
# Reference route (Mac): all zones
./gradlew :app:testDebugUnitTest --tests 'fr.luba.poc.planning.FollowerRouteExport' \
  -PfollowerRouteOutput=$PWD/app/src/test/resources/follower-reference-route.json
# Bench on the phone (interrupts the app, hence the gateway: robot stopped; relaunch the app afterwards)
./gradlew :app:assembleDebugAndroidTest
adb -s <redmi> install -r -t app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
adb -s <redmi> shell am instrument -w -r -e mode follower -e stepM 0 \
  fr.luba.poc.test/fr.luba.poc.planning.PlannerDeviceBenchmark
# Obstacle bypass (same test APK): 12 contacts spread over the mission
adb -s <redmi> shell am instrument -w -r -e mode bypass -e samples 12 \
  fr.luba.poc.test/fr.luba.poc.planning.PlannerDeviceBenchmark
adb -s <redmi> shell am start -n fr.luba.poc/.MainActivity
```

## Move to point (0.28.0, 2026-10-13)

"Go to point" (and the recorded obstacle bypass) is now tracked by the gateway like mowing:
same `LegSpec` (one range with blades OFF, `shown`), route and physical surface of the move plan, known obstacles.
Before, the Fold wrote every setpoint over Wi-Fi: writes of 300 to 750 ms, repeated `nav_motion_starved`,
jerky robot then stopped by the lift guard (14:00:02, "18 cm traveled for 137 cm expected"). Outcomes
are handled by the Fold as before: contact → back-off, photo and recomputed path; added obstacle → local link;
deviation > 30 cm or envelope out of the corridor → catch-up from the actual position; any other stop → end of the move.
Without a gateway (direct Bluetooth) or for a cutting test: local tracking unchanged.

Docking is no longer driven by the Fold: see `STATION_BLE.md` (official Mammotion return from the approach point).
