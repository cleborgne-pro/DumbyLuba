# Real mowing driven by the app

The phone makes the robot follow the prepared route, command by command, over Bluetooth: speed and rotation at
5 Hz, blades and cutting height. Mammotion's native planner is never used for mowing. Only the final docking at the
base is handed over to the robot's official return.

| | |
|---|---|
| **Access** | Map → **Mow** → zones → **Task settings** → **Route preview** → **Start**; or the **Simulated route** bar → **Run this route** |
| **Status** | Implemented; partial real-world tests on a LUBA 2 AWD. The full mowing → recharge → resume cycle is still to be validated in the field |
| **Code** | `ble/RobotBle.kt` (`startRoute`, `dock`, `observeWorkWindow`), `mowing/WorkWindow.kt`, `map/MowingLiveBar.kt`, `ui/MowingLiveStats.kt`, `simulation/PathFollower.kt`, `RobotRoute.kt`, `RouteRecharge.kt`, `DockExit.kt`, `NativeDocking.kt`, `DeadReckoning.kt`, `protocol/ExternalNavigationPolicy.kt` |
| **References** | [BLE route](../ROBOT_ROUTE_BLE.md) · [Modelled tracking](../TRACKING_EXECUTION.md) · [Tracking delay](../TRACKING_DELAY.md) · [BLE navigation audit](../BLE_NAVIGATION_AUDIT.md) |

> [!CAUTION]
> Start with **Follow, blades stopped** on clear ground, robot in sight, STOP within reach, and first run the
> [command cutoff](safety.md#command-cutoff-motor-watchdog) test.

<p align="center">
  <img src="../images/mowing-in-progress.png" width="200" alt="Mowing in progress">
</p>

## Mowing bar

During mowing, the bottom bar of the map shows, on a first line "Local mowing · Mowed x %":

- **Mowed x %**: share of the meters of cutting stripes already mowed over the whole mission, resumes included.
  Moves with blades stopped (leaving base, links, return) do not count, and the percentage no longer drops back to 0
  after a bypass or a trajectory recovery (before 0.41.0, it counted route edges). It reaches 100 % once the stripes
  are done, during the return. For a route without cutting (recharge, survey), "Route x %" follows the edges;
- **Blades**: rotation reported by the robot ("Blades 2,980 rpm", "Blades running" without a speed, "Blades stopped",
  "Blades?" in orange while the robot has not reported it);
- **Height** reported by the robot, with the target during an adjustment ("Height 55 → 40 mm"), including when
  changing [height pass](mowing-preparation.md#2-task-settings);
- **Battery** and **duration** (actual / estimated, "1 h 05 / ≈ 2 h 10");
- **Recharge n/total** (0.62.0): recharges started since the task began, resumes included, out of the total planned
  at launch (raised if the task needs more); absent for a task that plans no recharge;
- **Covered x %**: share of the task zones actually mowed so far according to the track
  ([real coverage](mowing-history.md#real-coverage)), once something is mowed;
- then the current step, a **STOP** button and "⋯", with a progress bar below.

"⋯" (or a tap on the bar) opens the **Local mowing** sheet: **State** (current step), **Height**, **Recharge**
("return 20 % · resume 90 %"), **Recharges** ("1 / ≈ 2"), the **Travel speed** slider (0.62.0: it no longer takes up
space on the map during mowing) and **Stop**. When a signal heatmap is shown, the same sheet is titled **Signals and
terrain** and starts with the heatmap settings.

If the mowing flow is opened again while a local mowing runs, the **Mowing in progress** page replaces the task
settings: *Progress* (step, "Mowed x %", "Share of cutting stripes mowed, excluding travel with blades stopped.",
**Blades**, **Reported height**), *Settings* (**Cutting height**, **Recharge**, **Battery**, **Recharges**,
**Duration** "… / ≈ … estimated", **Travel speed**) and a **STOP** button. With several height passes, the cutting
height shows the current pass ("Pass 2/3 · 55 mm"); "Mowed x %" counts all passes.

## Start conditions

Starting is refused, with an explicit message ("Start refused: …" in the mowing bar and the preview), if any of
these conditions is missing:

- LUBA 2 robot (`Luba-VS…`) connected, ready (state 11), self-check nominal;
- state and position received less than 2 s ago;
- map identical to that of the connected robot; base known with its docking axis;
- blades confirmed stopped, no bumper alert;
- battery above the 5 % reserve (off the station);
- height within the range of the connected model (25 to 70 mm on a LUBA 2, 55 to 100 mm on an H version);
- no other activity in progress (manual control, test, Mammotion task, back-off, unlock, command cutoff test:
  "Finish the current activity first"), Bluetooth send queue not saturated;
- on the base: charging observed within 25 cm of the configured base;
- within the [working hours](#working-hours), if enabled (mowing and leaving base; return to base is always
  allowed).

## Run

1. Reports requested every 200 ms. If the robot is on the base: **straight-line exit**, slowly (≈ 10 cm/s, no
   pivot, heading within ±10°, ±10 cm corridor, charging contact release confirmed).
2. Link to the first cutting point, then edge tracking. Each segment with the same blade state is followed
   **without stopping**: look-ahead target along the route, heading correction while moving, pivot on the spot at
   sharp corners, slowing down before a corner or the end of a segment.
3. **Blades**: the height must be reported by the robot before cutting; rotation is confirmed within 5 s, stopping
   within 3 s. Links outside zones are done with blades stopped. **Height passes** (0.45.0): each edge of the
   prepared route carries the height of its pass; at the first edge of a new pass, tracking stops, commands the new
   height with blades running and the robot stopped, and waits for it as at the start. A bypass or a recharge keeps
   the height of the current pass. With the gateway, each pass is sent as a separate tracking job at its height.
4. **Recharge** at the low threshold (see below), then resume at the interrupted point.
5. **End**: route to the approach point in front of the base, then docking. Message "Mowing finished · charging
   contact confirmed".

### Controlled position: the centre of rotation

The LUBA 2 publishes a point located **8 cm behind its center of rotation**, on its axis. Measured over 212 pivots
of the Oct 15 mowing at Jardin d'Eden, this offset is the same everywhere in the zone. During a pivot on the spot,
this point turns around the center: a U-turn moved it by about 16 cm. Tracking saw this as overshooting the ends of
passes, and the departure pivot "moved" the robot 10 cm towards the base obstacle.

Tracking (Fold, gateway, move to a point, simulation) now controls the **center of rotation**: the published point
moved forward by 8 cm along the fused heading. Route deviation, end of pass, pivot slip, chassis envelope and
obstacles are computed on this center. Heading and speed are still measured on the published point.

## Speed

- **Travel speed** slider from 10 to 100 % (30 % on first use), adjustable during mowing from the mowing bar
  details ("⋯", 0.62.0: no more permanent panel on the map) and in the **Move to a point** panel, and kept for later mowings, even after restarting the
  app.
- Speeds remain **raw units** not calibrated in m/s: 100 % ≈ 500 units (≈ 0.48 m/s measured), 30 % = 150 units.
- The rotation gain is calibrated online and stored per robot; beyond a 12 cm deviation, speed is reduced
  progressively.

## Automatic stops

The route stops (STOP and blades stopped) if:

- the route deviation exceeds **30 cm** (60 cm during a pivot and the reconvergence that follows it, see
  [Pivot on a slope](#pivot-on-a-slope));
- no progress for 15 s, or less than 3 cm travelled in 3 s (blockage);
- a segment lasts longer than its budget (120 s + time at 5 cm/s);
- the robot envelope touches an obstacle or leaves the safe corridor;
- state or position are more than 2 s old, a robot error or a protection appears;
- the link is lost ([Bluetooth connection](bluetooth-connection.md#link-loss)).

A bumper contact does not end the mission: it triggers a [back-off and a bypass](obstacles.md). Nor does leaving the
trajectory (deviation of more than 30 cm, envelope outside the safe corridor, return drift outside the safe area,
robot inside the reserve of an obstacle the route is already bypassing): the robot backs off and then resumes
([below](#leaving-the-trajectory-back-off-and-resume)). Nor does a transient robot protection: the mission waits for it
to clear ([below](#transient-protection-pause-then-resume)).

## Leaving the trajectory: back-off and resume

When tracking (Fold or gateway) stops because the robot strays from the route or its chassis leaves, or is about to
leave, the safe corridor:

1. STOP, blades stopped, physical stop confirmed by two positions.
2. **Straight-line back-off** along the trail the robot just covered, heading unchanged, blades stopped, to the first
   point (10 cm to 1 m) where the chassis is entirely back inside the zone. No back-off if the chassis stayed inside.
   If no point of the trail behind the robot brings the chassis back inside (typically after a pivot: the trail is
   then ahead of the robot), the robot first moves **straight ahead**, blades stopped, provided this only moves its
   chassis away from the border (1.1.5).
3. **Link with blades stopped** back to the route, level with the interrupted spot; if that would bring the robot
   right up against the border (15 cm tracking tolerance, next metre included), it rejoins the route up to 4 m
   further on.
4. Mowing continues (blades restarted on the pass). `route_drift_recovery` event in the log.

A new exit less than 1 m from the previous one rejoins the route 1 m further each time. The stop is kept on the 4th
consecutive exit at the same place (passage too narrow: widen the zone or mark it as a no-go), after 30 resumes in
the mission, if the chassis can get back in neither by reversing along its trail nor by moving straight ahead, or
for a drift into the base safety zone.

Robot inside the reserve of an obstacle already being bypassed (pivot or skid near an obstacle, for example in front
of the base): same resume. The link starts with a **straight-line clearance** checked against the physical radius of
the obstacles; during this clearance, only a movement that brings the robot closer to the obstacle, or a pivot on the
spot inside its reserve, stops tracking. The clearance stays active as long as the measured position has not passed
its end.

## Transient protection: pause then resume

The robot sometimes publishes a non-nominal self-check on a single report (protection 41 on Oct 15, back to 10 at the
next position). Tracking always stops (STOP, blades stopped) and no threshold is relaxed; then, instead of ending the
mowing:

1. the mission **waits** for the robot to clear its protection by itself (ready or manual state, self-check 0 or 10)
   and checks that it is **stable for 3 s**;
2. it resumes via the same link as after leaving the trajectory, **without skipping any cutting**
   (`route_protection_pause` then `route_protection_cleared` events). On Oct 19 at 14:50, a collision lock (25)
   without a contact code appeared during a 150° pivot that had slid the robot 24 cm towards the border; once it
   cleared, the resume was refused ("Back-off impossible: chassis outside the zone along the whole trail") because
   the trail was ahead of the robot. Since 1.1.5 the resume leaves straight ahead in that case.

The stop is kept if the protection lasts more than **30 s**, if it is the lock after STOP (self-check 11, to be
unlocked) or the Mammotion app's working hours (self-check 23), for any other robot state, or after **5 pauses** in
the mission. Lift and bumper contact keep their own stops. Same rule whether tracking is done by the Fold or by the
gateway (`simulation/ProtectionPause.kt`).

Before this pause, the robot is stopped and tracking waits **2 s** for a bumper contact code: the **collision lock**
(self-check 25) is published with the contact, before code -1014 is read from the error history. On Oct 15 at 11:00,
a contact against a post had been mistaken for a transient protection: no back-off, a 30 s wait, robot left against
the post. The contact now goes through the back-off and bypass
([obstacles](obstacles.md#contact-during-a-mission-back-off-and-bypass), `protocol/CollisionLatch.kt`). The
lock after STOP (11) is stopped without waiting.

## Working hours

Map → **Mow** → **Task settings** → **Working hours** group (0.51.0): switch **Limit mowing hours** ("Mowing allowed
at any time" when off, "Mowing allowed 09:00 – 20:00" when on), then a range slider: a single window per day, in
15 min steps, at least one hour, not crossing midnight, 09:00 – 20:00 by default (`mowing/WorkWindow.kt`, disabled by
default, kept between launches and shared by all tasks). Outside the window:

- **no mowing starts** (no leaving base, no resume, no automatic resume after a gateway update): "Start refused:
  outside the working hours (09:00 – 20:00), adjustable in Task settings";
- **mowing in progress stops** at the next tracking check (every second, like the low battery threshold): STOP,
  blades stopped, return along the same route as for a recharge, docking, then end of the mission. Progress is kept:
  the **Mowing interrupted** panel offers the resume, allowed once the window reopens. During a recharge, the mission
  stops where it is, robot on the base. Tracking handed to the gateway: the gateway is asked to stop and the outcome
  returns the edge reached. Log: `work_window_end`, `gateway_leg_work_window`, `work_window_docked`;
- **a robot left outside** (more than 1 m from the base, not charging, with no activity or Mammotion task, self-check
  nominal, fresh state) is brought back along the same route as **Recharge**, once per out-of-hours period
  (`work_window_return`). On failure, a "Return to base failed" alert is raised ("Outside the working hours: automatic return to base
  failed (…)", `work_window_return_failed`).

The check runs as long as the app is active, including in the background with the link service; a gateway Wi-Fi
outage delays stopping a handed-over tracking job until the link comes back. Working hours set in the Mammotion app
add to these: outside that window, the robot simply refuses to work
([self-check 23](safety.md#mammotion-app-working-hours-self-check-23)), with no return to base or resume. It
is better to disable it and keep DumbyLuba's.

## Pivot on a slope

On a slope, the robot slides downhill while pivoting on the spot. On Oct 15, at the bottom of Jardin d'Eden, each
U-turn pivot moved it by 20 to 45 cm, versus 10 cm at most on flat ground. The gateway then stopped with "Route
deviation >30 cm" at almost every U-turn: 12 stops and resumes in 10 minutes.

Since 0.41.0, during a pivot and the reconvergence that follows it, the tolerated deviation goes up to **60 cm**. The
chassis must stay within the safe area and outside the base safety zone. Tracking then returns to the line while
moving. The tolerance ends as soon as the robot is back within 15 cm of the line (after at least 30 cm of travel), or
at the latest after 2 m. Beyond 60 cm, or outside the safe area, the "Slip…" stop is recovered like a trajectory exit
(back-off and resume). Same rule on the Fold, the gateway and the simulation (`simulation/ReturnDrift.kt`,
`PivotSlide`).

### Slip map (0.44.0)

The robot pivots on the spot: any movement of its center between two positions without commanded forward motion is a
**slip**. Each pivot is measured during mowing (Fold or gateway) and feeds a **slip map** in one-metre cells, kept
from one mowing to the next (`filesDir/slide-field.json`). No sensor is needed. Recent measurements weigh more, so
wet or dry grass shows up in the map after a few pivots (the grass wetness from the garden weather rescales the
values, see [rain and weather](rain-and-weather.md#wet-grass-and-slips-when-pivoting-0441)). An aberrant measurement (robot pushed or hit) is ignored.

Once a cell has enough measurements (at least 3 s of pivoting), tracking uses it in two ways:

- **End of pass**: the pivot starts earlier (or later) by the expected slip along the pass axis, at most 15 cm, so
  that the robot lands on the vertex;
- **Prediction**: the pose predicted during a pivot includes the expected slip; tracking corrects from the next
  command instead of waiting for the measured position.

The 60 cm tolerance above does not change. The gateway receives the cells around the route, at most 300 (optional
final field of the tracking description, ignored by an older gateway). It sends its measurements back to the Fold
(`gateway_leg_pivot_slide` log event; `route_pivot_slide` for local tracking).

## Recharging during mowing

1. Battery at the **return** threshold (20 % by default): the robot estimates the energy for the rest of the route
   (remaining cutting, links and final return). If it can **finish and then go home** arriving at the base with at
   least **10 %**, it carries on without stopping; the emergency return then drops to 10 % (shown as "↓ 10 %").
   Otherwise the interrupted edge is stored, the blades stop and the robot follows the local route to the approach
   point in front of the base.
2. **Docking**: robot stopped (two still positions within 3 cm), at most 20 cm from the approach point, exit from
   manual mode then **official Mammotion return**, which only handles the docking. The return must start within 15 s
   and the charging contact must come within 5 min. The robot may report the charging contact one second before
   leaving the "return" state (14): the recharge waits for that exit (30 s at most) instead of interrupting the
   mowing ("Robot state incompatible with the station" on Oct 15, with the robot actually charging, before 0.45.1).
3. **Charging** up to the **resume** threshold (90 % by default), or **less if the remainder is short**: the robot
   sets off again as soon as the battery covers leaving the base, the link, the remaining cutting and the final
   return while staying above the low threshold ("↑ 45 %" and "departure at 45 % (short remainder)" instead of
   90 %). 6 h at most, charging contact required. A brief incident during charging (late robot report, gateway Wi-Fi
   outage, contact or state misread once) is tolerated for **2 min**; the robot stays still on the base and the
   resume waits for a fresh, nominal state. Only a lasting incident stops the mowing (progress kept). With the
   gateway, a Wi-Fi outage during charging is handled as during tracking: the link is not declared lost and link
   maintenance (synchronization, report subscription) is suspended until reconnection.
4. Straight-line exit, shortest link recomputed from the actual position, resume at the interrupted point. The
   mowing bar shows "Recharge n/total".
   If the gateway link still drops during this cycle (return, docking, charging), mowing resumes by itself once the
   robot is charging on the base and the link is back (0.62.0, see
   [resume](mowing-resume.md#automatic-resume-after-losing-the-gateway-during-recharging)). The
   tracking pose estimate (fused heading, latest positions and commands) restarts from scratch after the exit, with
   calibration kept: kept from the return, it took the route from the base to the exit point for a straight line and
   "confirmed" a heading wrong by 150°, leading to a pivot in the wrong direction and a stop in the base zone
   (end-to-end test bench).

### Return reserve ("Never Die Outside")

Far from the base, waiting for the low threshold could leave too little battery to come back. During a mowing, the
threshold watched is therefore raised to **the estimated cost of the return plus the return reserve** (Task settings,
10 % by default), whenever that is above the low threshold:

- return cost: distance from the robot to the approach point in front of the base × detour of the real return path,
  plus the docking corridor, at the estimate speed and duration factor, docking time and the 25 % energy margin
  included ([remainder estimate](#estimating-the-energy-for-the-remainder-0480)); the detour (real path / straight
  line, 1 to 4, 1.5 before the first measure) is measured at the start of each blade range on the mission's
  navigation (no-go zones, obstacles), off the tracking loop; the threshold is refreshed every 5 s;
- the threshold never goes below the low threshold (near the base nothing changes) nor above the resume threshold
  minus 15 points (each recharge then buys some mowing, instead of leaving at 60 % to come back at 55 %) or 80 %; it is shown next to the battery like the 10 % floor ("↓ 34 %");
- when it is reached above the low threshold, the robot makes the same choice as at the low threshold: finish then go
  home if the remainder allows it (10 % floor), otherwise return to recharge ("Return reserve (34 %) · BLE return ·
  blades OFF"; log `route_return_reserve` with the battery, the threshold, the reserve and the detour);
- with the gateway, the threshold in force when a tracking job is handed over is sent with it (the gateway stops at
  it on its own);
- surveys, runs with blades stopped and debug mowings keep the fixed low threshold (no mowing measurement).

The simulation applies the same rule (`ReturnReserve`, the reserve of the task settings): on the reference map, every
zone with a 20 min runtime and a low threshold of 10 or 20 % ends at the base, with several returns decided by the
reserve and every arrival at least at the reserve (`ReturnReserveTest`).

Since 0.47.0, the return route (low threshold) and the resume link (high threshold) get the same recoveries as
mowing: bypass after a contact, recovery from a trajectory exit, pause on robot protection. A tracking job **lost by
the gateway** (start order lost in a Wi-Fi outage, gateway restarted) is resumed from the robot's actual position,
at most 4 times per mowing; leaving base is resent at most 2 times. Before, at the low threshold on Oct 15
(16:15:10), a 2 s Wi-Fi outage right at the start of the return had stopped the mowing with the robot at 20 % far
from the base ("Tracking lost: the gateway no longer has a record of the tracking").

### Estimating the energy for the remainder (0.48.0)

Remaining duration = remaining meters ÷ estimate speed × duration factor, plus 60 s for docking; energy = duration ÷
runtime, increased by **25 %** (pivots, slips, bypasses). Runtime and duration factor are the more cautious of the
estimate frozen at departure (calibrated on previous mowings) and the measurements of the current mowing. Example
from Oct 15 (measured runtime 82 min, factor 1.64): at the 20 % threshold, a remainder of about 50 m is finished
without recharging; beyond that, the robot heads back. Without mowing measurements (survey, debug mowing), the robot
heads back and recharges up to the high threshold as before. Log: `route_finish_below_low`, `route_finish_refused`,
`route_early_departure`. The simulation and the displayed estimate apply the same rule (`FinishPolicy`).

## Threshold shown next to the battery

The map status pill and the home screen show, next to the battery level, the threshold that applies:

- robot **not charging**: low threshold, the return-to-base level ("↓ 20 %" on the map, "45 % · return at 20 %" on
  the home screen); the battery turns red at this threshold;
- robot **charging**: high threshold, the mowing resume level ("↑ 90 %"; "resume at 90 %" and a marker on the bar
  of the charging card on the home screen).

During mowing, these are its thresholds; otherwise those of the next task (Task settings). Mowing finished below the
low threshold: the 10 % floor; shortened recharge: the departure level.

## Robot trail

The green trail follows the recorded positions (the center of rotation), kept for the whole mowing, resumes
included (simplified beyond 12,000 points, see [resume](mowing-resume.md#saving)), while the planned route still to
do is drawn in white. When the robot was moved without being traced (return via
"Recharge", mowing resumed from the base with the trail kept from a distant zone), the trail is **broken** instead of
being joined by a straight line across the zones (jump of more than 3 m between two positions). Back-offs after a
contact or a trajectory exit never follow that jump.

## Saving and resuming

Progress is saved throughout any real mowing: after a STOP, a lost link or the app being closed, mowing can resume
where it stopped ([resume](mowing-resume.md)).

## End-of-mowing report

Every real mowing is measured (duration off and on charge, battery, recharges) and compared with the estimate frozen
at departure. At the end, a report opens and a "Mowing finished" notification is posted; the mowing bar and the
**Mowing in progress** page show the elapsed duration against the estimated duration. The differences adjust later estimates ([mowing history](mowing-history.md)).

## In the background

Mowing continues when the app goes to the background, as long as the foreground service holds the link. The "robot
connected" notification offers **STOP robot**. Removing the app from recent tasks sends STOP then disconnects.

## Through a gateway

With a [gateway](gateway.md) mounted on the robot, the pilot phone prepares the route and can **hand it over to the
gateway**, which follows it on its own: a Wi-Fi outage no longer stops the robot.

## Limitations

- Speeds are not calibrated in m/s; the simulator kinematics are a model.
- The robot stopping on its own when the app goes silent is not demonstrated by default
  ([command cutoff](safety.md#command-cutoff-motor-watchdog)).
- App-driven alignment and reversing in front of the base were abandoned in favour of the official docking (test on
  2026-10-13: rotation had no effect, reversing too slow).
