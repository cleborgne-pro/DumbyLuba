# Obstacles: contact, back-off, bypass and learned obstacles

A bumper contact no longer ends the mission: the robot stops, backs off along its track, then bypasses the obstacle
with the remaining route. Fixed obstacles can be saved on the phone so that later mowing runs avoid them.

| | |
|---|---|
| **Access** | Automatic during a mission; Service → Garden and history → **Obstacles** (list, difficulties, photos, simulation); Service → Supervised tests → **Obstacle tests**; map layers **Obstacles** and **Difficulties** |
| **Status** | Implemented and simulated; reversing and contact during the back-off still to be validated on the robot, blades stopped first |
| **Detection** | **bumper only**: the ultrasonic sensors are not read (no distance in the known protocol), the obstacle is detected on contact |
| **Code** | `simulation/ObstacleBypass.kt`, `ObstacleEncounterSimulation.kt`, `obstacles/` (`Obstacles.kt`, `ObstacleStore.kt`, `FollowObstacleGuard.kt`, `ObstacleCourse.kt`, `TeachSafety.kt`, `BorderFrontal.kt`), `difficulty/`, `map/DifficultyLayers.kt`, `map/ObstacleSheet.kt`, `ui/ObstaclesPanel.kt`, `ui/DifficultiesPanel.kt`, `ui/ObstacleSimPanel.kt` |
| **References** | [Bypass: how it works and its limits](../OBSTACLE_BYPASS.md) · [Obstacle tests](../OBSTACLE_TESTS.md) · [Encountered obstacles and difficulties](../DIFFICULTIES.md) · [Local replanning](../LOCAL_REPLANNING.md) · [Robot camera](camera.md) |

<p align="center">
  <img src="../images/obstacles.png" width="200" alt="Service → Obstacles">
  <img src="../images/obstacle-simulation.png" width="200" alt="Obstacle simulation">
</p>

## Contact during a mission: back-off and bypass

Applies to mowing, signal surveys, station routes and go-to-point moves.

1. **Stop**: immediate STOP (send queue purged), blades stopped and confirmed, two stationary positions.
2. **Estimated obstacle** in front of the bumper: radius 0.2 m, margin 0.3 m. It applies to the current mission,
   then for 4 h as a presumed obstacle (resume and later mowing runs); it can be saved permanently from its record on
   the map or from Service → Obstacles. A contact that the robot republishes with a different timestamp is not a second contact.
3. **Back-off** in a straight line along the traveled track, heading unchanged, ≈ 10 cm/s, from 30 cm to 1 m. The
   chassis sweep is checked against the borders, no-go zones and obstacles; lateral deviation ≤ 12 cm, 20 s at
   most. Keep-alive commands no longer pile up when the link through the gateway is slow.
   **Heading hold (1.1.4)**: when a wheel slips and the robot turns while reversing (more than 8° away from the wanted
   heading), the reverse pauses and the robot **pivots in place** back to the back-off heading, slightly tilted
   towards its line so it returns onto it, then reverses again (not in the first 10 cm, pivot checked against the
   borders and obstacles, at most 6 pivots of 4 s). If the deviation still exceeds 12 cm, the mowing no longer
   stops: the repaired route is kept and the robot is brought back like after a
   [trajectory exit](real-mowing.md#leaving-the-trajectory-back-off-and-resume).
4. **Photo** of the obstacle if the option is enabled ([camera](camera.md#obstacle-photo-after-a-contact)).
5. **Repair of the remaining route**: the portions too close to the obstacle are removed, including later passes
   that cross it again; the kept pieces are joined by a **tight detour** around the obstacle (≈ 1.2 m from its
   center), **on the most suitable side**: the shortest one away from borders, the **inner** side near a zone
   border, an exclusion or a saved obstacle (less than 1.5 m). When no detour is possible, the same navigation as
   in preparation joins the pieces.
   Detours and links keep a **15 cm tracking tolerance** between the chassis and the borders, exclusions and
   obstacles, including the pivot back onto the pass: otherwise the robot rejoins the pass a little further on (up
   to 4 m). On Oct 14, a link brought the robot back to pivot right at the border and tracking stopped. Near a
   border pass (25 cm from the border), no link keeps this tolerance: the safest one is taken, and a departure from
   the trajectory is recovered ([real mowing](real-mowing.md#leaving-the-trajectory-back-off-and-resume)).
   The first link is driven with blades stopped; the following ones, entirely inside the mowed area, keep the
   blades on. The return to base is always kept.
   **Move without mowing** (link between zones, access, return to base): the robot does not go back onto the line
   just behind the obstacle; the whole move is recomputed along the shortest path to its destination (start of the
   next cut or the base approach point, with its arrival heading).
6. Message "Obstacle bypassed on the left/right[, inner side] · N link(s), X m of cut left around it". An
   **Obstacle encountered** notification (warning level, one per robot, updated on each contact) gives the contact
   position and the photo ([notifications](notifications.md)).

**Collision lock (self-check 25).** On contact, the LUBA 2 often publishes `self_check 25` in the same report, one
second before code -1014 appears in its error history. Tracking then stops the robot immediately and waits up to 2 s
for the contact code; once the contact is received, the sequence above applies. The back-off (step 3) is allowed
under this lock, since it moves the robot away from the object. The route only resumes once the robot has released
the lock (3 s stable, 30 s at most); otherwise the stop is maintained, with the instruction to free the robot.
Without a contact code within 2 s, the lock is now treated **as a contact** (0.49.1): back-off, wait for the lock to
be released, obstacle estimated in front of the robot and bypass. Before, the mission waited 30 s for the robot to
clear its protection by itself, which it does not do as long as it stays against the object (Oct 15, 17:36: mowing
stopped).
The stop (step 1) is also confirmed under this lock: until then, confirming the two stationary positions required a
nominal self-check, and mowing stopped with "Robot protection active" before even the back-off, the only action that
releases the lock (observed on the end-to-end test bench; the bypass is prepared by the pilot phone, whether
tracking is done by it or by the gateway).

**Back-off without a straight track (0.49.1).** The back-off follows the robot's track. After a pivot, the robot
arrives from another direction and there is no straight track behind it: the back-off is then a straight line of
**30 cm**, with the chassis checked against borders, no-go zones and known obstacles, as for unlocking. Before,
mowing stopped ("Back-off impossible after contact", Oct 15 at 17:33 and 17:34, twice at the same flower bed in the
Fosse).

An obstacle saved during the mission on the remaining route triggers the same repair, without back-off. The
go-to-point move backs off and then recomputes its path. The obstacle it records on contact is placed in front of
the position confirmed stationary before the back-off: estimated from the last published position (one per second),
it could overlap the chassis stopped 10 to 20 cm further on, and the move stopped with "no back-off on the track and
no safe path".

**Guard during tracking**: an obstacle absent from the prepared route triggers a bypass as soon as the robot nears
its margin. An obstacle that the route already bypasses only stops the robot if it enters 15 cm into its margin
(never into its physical radius): the route passes 5 cm from the margin, and heading noise during a pivot used to
stop mowing ("Local obstacle inside the robot envelope").

**Limits and stops maintained**

- 8 bypasses at most per mission.
- An object larger than the estimated disc is touched again further on; at the 5th contact within 2.5 m (object too
  large, to be drawn as a no-go zone), or when no detour exists, the segment is deferred to the end of the mowing and
  tried again there, given up if blocked again ([smart resume](mowing-resume.md#smart-resume-after-a-recharge-or-a-blockage)).
- Wheels turning without the robot moving (soft ground, root, object without bumper code): the place is recorded as a
  blockage and an estimated obstacle in front of the robot, bypassed like a contact.
- Back-off impossible, no link, obstacle in a narrow passage, contact during docking: stop, progress kept for the
  [resume](mowing-resume.md). A contact on the return route to recharge or on the resume link is bypassed as during
  mowing (since 0.47.0).
- A ring of about 1.2 m around the obstacle remains unmowed; saving it lets the next mowing run go around it.
- Code 1207 (continuous bumper fault) never counts as a contact.
- A contact during a native Mammotion mission stops that mission (its firmware route cannot be repaired locally) and
  is recorded.

**Who computes the bypass**

The Fold, always. With the autonomous gateway (Redmi mounted on the robot), the Redmi tracks the route, detects the
contact, stops the robot (blades OFF) and notifies the Fold; the Fold makes the robot back off, computes the bypass
and sends the repaired route back to the Redmi. Same computations as the obstacle simulation.

**If the Fold is unreachable (Wi-Fi down)**: without a contact, the robot carries on alone (safety stop after
20 min without the Fold). After a contact, it **stays stopped, blades OFF, at the contact point** until the Fold
comes back, which then resumes the back-off and the bypass; it does not return to the base on its own. Details:
[bypass, who computes what](../OBSTACLE_BYPASS.md#who-computes-what-in-real-mowing-fold-or-gateway).

## Encountered obstacles and difficulties

Every bumper contact during an autonomous move is **recorded on the map** as soon as it is received, even if the
mission then stops (since 0.43.1), with the estimated position of the object. The bypass then adds the chosen side
and the **photo** taken after the back-off, under the same record. The other difficulties (blockage, **wheel
slip**, lift, lack of progress, deviation from the route) are recorded too, at the robot's position. Details:
[encountered obstacles and difficulty heatmap](../DIFFICULTIES.md).

- **Map layers**: **Obstacles** (contacts as red dots, presumed obstacles as dotted outlines) and **Difficulties**
  (yellow → red heatmap), in the layer bar and the **Map** sheet (*On the map*). Tapping a contact opens its record:
  photo, date, origin, code, estimated position, bypass side, number of contacts at this place, **Dismiss** (object
  removed, false contact) or **Save the obstacle**. The **Obstacles** layer is shown by default; both choices are kept
  between launches.
- **Exclusion size chosen on the map** (since 0.43.0): tapping a contact, the dotted circle of a presumed obstacle
  or a **saved obstacle** opens its record. There you choose the type (the type suggests its usual radius: swing leg
  0.12 m, tree 0.30 m, pole 0.10 m, hole 0.35 m, low branch 0.15 m, other 0.20 m), the **Object radius** (0.05 to
  2 m) and the **Margin around the object** (0 to 2 m), both in 5 cm steps, with shortcuts **Margin 0.3 m / 0.5 m /
  1.0 m**. The record shows the excluded area (radius + margin) and the minimum distance of the robot's
  center (+ 0.43 m turning envelope). **Save** recomputes the preview and, during a mission, repairs the remaining
  route; a saved obstacle's record also lets you **Delete** it (two taps: **Delete**, then **Confirm deletion**). On Oct 15, a post that had been hit could
  only be saved with the size of an estimated contact (radius 0.2 m, margin 0.3 m).
- **Presumed obstacles**: two or more contacts at the same place (less than 75 cm apart) during separate moves, or
  **a single contact less than 4 h old** ("Recently hit obstacle", since 0.39.1). A presumed obstacle is an "Other"
  obstacle centered on the contacts, radius = their spread + 10 cm (0.2 to 1 m), margin 0.3 m; contacts older than
  180 days and contacts near a saved obstacle do not count. Routes prepared afterwards,
  including the resume of an interrupted mowing run, avoid them like a saved obstacle (option enabled by default):
  the robot no longer heads straight back to the object it has just hit. **Dismiss** the contact if the object has
  been removed.
- **Difficult areas avoided when moving**: with blades stopped, the route bypasses the red areas of the heatmap if
  the detour stays reasonable (at most ×1.5 + 4 m); mowing always covers them (option enabled by default).
- Obstacle bypasses also choose the less difficult side.

## Service → Obstacles and Obstacle tests

Data and tests are kept apart:

- Service → Garden and history → **Obstacles** (no robot command):
  - **Map** of the saved obstacles (avoided at radius + margin + robot turning 0.43 m);
  - **Saved obstacles**: position, distance to the robot, radius, margin, incident count. **Edit** (type — Swing leg,
    Tree, Pole, Hole, Low branch, Other; **East offset (+x)** and **North offset (+y)** from −5 to +5 m in 10 cm
    steps; **Obstacle radius** 0.05 to 2 m and **Safety margin** 0 to 2 m in 5 cm steps; the avoided area is shown),
    **Incident** (Bumper contact, Rubbing, Collision, Near miss or Observation, optional note, robot position) and
    **Delete** (confirmation dialog). From 3 incidents, "Recurring incidents: adjust the position or the margin.";
  - **Encountered obstacles and difficulties**: counters by type, **Presumed obstacles** with **Save**, the last 15
    **Encountered obstacles** (photo, date, bypass side; a tap opens the record), **Clear the difficulty log**
    (confirmation; saved obstacles and photos are kept);
  - **Use in routes**: **Avoid obstacles hit several times** and **Bypass difficult areas when moving** (both on by
    default);
  - **Map layers**: **"Obstacles" layer**, **"Difficulties" layer** and **"Real coverage" layer**
    ([map](map.md#real-coverage-layer)) toggles;
  - **Photos after contact**: **Photograph the obstacle after the back-off** toggle, **Photo camera** choice, last
    photo status and the 12 latest photos (a tap enlarges; nearest saved obstacle named);
  - **Simulation**: entry to **Obstacle simulation**.
- Service → Supervised tests → **Obstacle tests**, four tabs:

| Tab | Role |
|---|---|
| **Contact** | check that the bumper is received, robot stationary: **Listen to the bumper · manual mode (30 s)** (manual mowing mode with blades OFF, state 20, where the LUBA 2 publishes the contact) or **Listen in ready mode (30 s)**, then press the front bumper; **I heard the beep / touched the bumper** timestamps the observation. A 1014 contact validates detection for this connection (no longer required for Learn) |
| **Learn** | **Slow approach until contact (1 m max)**: robot placed 30 to 80 cm from the obstacle, in line; it moves forward slowly (raw speed 80, ≈ 7.6 cm/s, 30 s and 1 m at most) until contact, then suggests an obstacle (type, radius 0.05 to 2 m, margin 0 to 2 m, 0.2 m by default) to **Save** or **Ignore**. No prior Contact test is needed: without a received contact the approach still stops after 1 m or 30 s, and a contact during it validates detection. A robot that halts against the object (collision lock 25, or 3 s without moving) is no longer an interruption: the error history is read for 3 s, the 1014 or the lock give **Contact detected**, a halt after at least 5 cm without any code gives **Stopped in front of an object** with the obstacle proposed (bumper not validated); only a robot that never moved ends as Interrupted. **Obstacle in front of the robot** (Identify without contact) places an obstacle against the bumper from the robot's fresh pose, without any command: distance (0 to 4 m), type, radius and margin adjustable before saving |

**Contact while driving.** With the joystick (manual control or record by driving), a bumper contact (1014), or the
collision lock (self-check 25) that announces it, stops the robot and opens the **Obstacle touched while driving**
sheet over any screen: object estimated in front of the bumper (behind the chassis when reversing), **Save the
obstacle** with its type, radius and margin, or **Dismiss**. Closed without a choice, the contact stays in the
difficulty log and on the map (origin "manual control"), where it can be saved later. The 1014 that arrives after the
lock refines the same contact. Bumper / lift alert bits still raise the critical alert as well, since a lift is possible.
The LUBA 2 only gives the 1014 when its error history is read: the joystick now reads it every 500 ms (before
2026-10-22 nothing read it while driving, so a contact went unseen or ended as "Wheels commanded without progress").
A stop on wheels without progress while driving also keeps the 3 s window open, so a late 1014 is still logged.
A contact the robot dates more than **15 s** before it is read is not placed on the map (`contact_late` in the log):
the robot has moved since, and its current position is not where it hit. On 2026-10-25 the contacts of learning
trials on swing poles, read 20 to 63 s later while driving by hand, were logged about 1 m away and then avoided by
every mowing computation as presumed obstacles.
The LUBA 2 also only records the contact in **manual mowing mode** (state 20): driven in ready mode (11), a pressed
bumper left no trace at all (Fold log of 2026-10-22, 16:48-16:49: joystick and slow approach, error history unchanged,
state 11 throughout). The joystick and the slow approach therefore drive in manual mode with blades OFF, renewed every
500 ms like routes do; releasing the joystick leaves that mode.
| **Bypass** | **Start bypass**: choose a saved obstacle (nearest by default); the robot goes to the opposite side (1 m beyond its avoided area) and comes back, blades stopped, at the set **Forward speed**. Start between avoided area + 0.5 m and 10 m from the center. Passed if the robot's side (0.26 m) never reached the obstacle surface; minimum observed gap shown |
| **Border** | frontal test of an outer border or a no-go zone (see below) |

Common conditions (shown live as preconditions): LUBA 2 (`Luba-VS…` or X series `Luba-VP…`), fresh state (≤ 2 s), battery > 30 %, robot
ready (state 11), no protection, bumper without alert, blades stopped, no Bluetooth diagnostics, blade test or route
running, map read. A supervision checkbox is required on each tab, and leaving the page stops the running test. A
bumper health alert (bits 0–2 of `sensor_status`) aborts the test without a proposal. Details:
[obstacle tests](../OBSTACLE_TESTS.md).

Obstacles are stored **on the phone only**; no no-go zone is created on the robot. They count in planning with
their radius, their margin and the robot's turning envelope (0.43 m).

### Frontal border test (0.63.0)

A zone's recorded outline is read as the chassis **side**: on the border lap, the robot's side runs at the set
margin (5 cm by default). At the end of a pass, it is the **front** that faces the outline, and the LUBA 2 is longer
(0.69 m) than it is wide (0.513 m). The planner accounts for this: passes end with the center at half-width +
margin + 20 cm from the outline (51 cm by default), so the bumper is at 16 cm and the front corners at 8 cm during
the pivot; facing a no-go zone, the center is at pivot radius + margin + 1 cm. The test checks these distances in
the field against the real border.

1. Choose the outline (zones and no-go zones of the map). The target point is the point of the outline **closest
   to the robot**, on a side at least 1.11 m long, at least 56 cm from its ends: bring the robot near the spot to
   test first. The preview shows the planned center, bumper and corners at the end of the pass, and rejects a test
   line that would leave the zone, run along another no-go zone or cross a saved obstacle.
2. **Positioning**: go-to-point move (same tracking and same guards as the go-to-point move, blades stopped),
   ending with a 50 cm straight line perpendicular to the outline: the robot stops facing it, 30 cm before the end
   of the pass.
3. **Slow advance to the end of the pass** (≈ 6.6 cm/s), in manual mode with blades OFF, where the LUBA 2 publishes
   the bumper contact. Stop at the point where mowing passes end, position measured after the stop: measured
   distance from the bumper to the outline and overshoot of the planned point. **Visual check**: "End of pass OK",
   "Too far", or "Advance to the edge".
4. **Advance to the edge** (≈ 4.7 cm/s), optional: stop on **bumper contact**, on **Edge reached · stop and measure** (edge not
   detectable, flower bed, low border), or when the bumper would pass the outline by 15 cm (physical tolerance of
   the maneuvers). The summary gives the position of the edge relative to the outline and the **actual clearance
   at the end of the pass**: bumper and corners at the pivot, based on the observed edge.

A contact during the test records **no obstacle** and no difficulty (it is the expected measurement). Lateral
deviation above 15 cm, no progress for 4 s, stale position, protection or robot error: STOP. Leaving the screen
stops the test. Distances are measured along the normal to the outline from the center of rotation (published
point + 8 cm), with the bumper assumed to be 0.345 m in front of it; positions at 1 Hz, hence a few centimetres of
uncertainty. After a contact, the robot stays against the border: free it with the joystick. Time limits: 60 s for
the advance to the end of the pass, 3 min for the visual check, 90 s for the advance to the edge. Results: PASS
(end of pass OK), FAILED (too far), NO_EDGE (limit reached without contact or reported edge),
CONTACT_BEFORE_PASS_END, ABORTED. Result logged (`border_frontal_result`).

### Obstacles near the base

The robot must always be able to reach the approach point in front of the base, pivot there, then reverse into the
station. A learned obstacle near this docking corridor (often a contact while leaving the base) has its **margin
reduced just enough** to free this pivot: between the obstacle's center and the corridor there must be room for its
radius, the margin, the turning envelope (0.43 m) and 2 cm of play. The **physical radius is never reduced**. The
saved margin does not change; the adjusted margin is used to compute every mission (mowing route preview and its
passes, border following, painted zone, obstacle simulation, recharge) and by the real tracking guards, on the Fold
as well as on the gateway. The launch logs
`obstacle_dock_margin` (saved margin and applied margin).

If the object itself prevents the pivot, the base really is unreachable. The computation is then refused with a
message naming the obstacle and its distance to the corridor, instead of the former "Base entry axis outside the
accessible passage". The obstacle must then be deleted or its position corrected.

## Obstacle simulation

Service → Obstacles → **Obstacle simulation**: replays a contact, a back-off and a bypass on the map, with the same
functions as real mowing and **no command to the robot**.

| Setting | Range | Default |
|---|---|---|
| Shape | Square, Round, Triangle | Square |
| Side or diameter | 0.1 to 3 m in 0.1 m steps | 0.6 m |
| Orientation | 0 to 180° in 5° steps | 0° |
| Zone | a zone of the map | zone containing the robot, otherwise the first |
| Playback speed | ×1 to ×40 | ×10 |
| Start near the object | 4 m before the first pass over it | on |

Place the object with a tap (drag to move it; two fingers pan and zoom), **Prepare route**, then **Start**,
**Play** / **Pause**, **Restart** and **End** (plays to the end). The orientation slider is hidden for the round
shape. A base with its docking axis and a valid plan are required. Map colors: gray prepared route, orange route
followed (repaired after contact), red object and contacts, circles estimated obstacles.

The simulated robot is driven by the **same navigation module as real mowing** (line tracking, predicted pose
between two positions, pivots at corners, stop and confirmation at every blade state change, safety stop beyond
30 cm of deviation), at the **forward speed set** for the robot. Only the mechanics are simulated: command delay of
about 0.6 s, inertia, friction and positions published once per second, based on LUBA 2 measurements. Bluetooth
exchanges, blades and camera are absent. The robot icon goes through every computed pose (since 0.37.1; before, it
lagged by about one second and cut corners).
