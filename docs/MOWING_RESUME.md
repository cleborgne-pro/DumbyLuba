# Resuming an interrupted mowing

A local mowing (Map → **Mow**, blades running) can last several hours with its recharges. Before the checkpoint, a STOP, a link loss, closing the app or restarting the phone lost all progress: mowing had to start again from the beginning.

## Checkpoint

At the start of any coverage task (mowing, "Follow, blades stopped", signal survey, border following, trial
mowing; before 0.61.0, only mowing with blades running), the app saves in its private storage, one file per robot (`mission-checkpoint-<hash>.json`, atomic write with `fsync`):

- the **complete preview** of the plan (`PreviewJson`): the plan depends on the robot's start position and cannot be recomputed identically;
- the SHA-256 fingerprint (to the millimeter) of the prepared mowing edges and a stable map key;
- the height, the recharge thresholds, the edge reached and the position projected onto that edge, the cutting meters traveled, recharges and resumes;
- the reason for the interruption;
- the **continuous-blade zones** of the preparation (`bladeZones`, 0.61.0): the painted zone of a trial mowing;
  absent (null) for a preparation from the map, which takes the preview's zones. The task (`MissionTask`: mowing,
  blades stopped, survey, border, trial) is deduced from the preview, the blades and these zones;
- the **height passes** (0.45.0): `Preview.cutHeights` and the height of each cutting path (`pathHeights`),
  carried into the prepared edges (`SimEdge.heightMm`) and into the fingerprint; an older checkpoint is read back
  as a single pass and keeps its fingerprint;
- the **presumed obstacles and difficult areas** counted by the plan preparation (0.40.2);
- the **start base** (`MissionCheckpoint.base`, 0.64.2): station, docking axis and approach distance used
  for the plan, saved only if they exactly match the prepared route. `RoutePlanning.resume`
  reuses them (`planningBase`) as long as the current station is the same (`MissionProgress.sameStation`: 25 cm, 15°);
  beyond that, or for an older checkpoint, the current base. Reason: on redocking, the station observed while
  recharging replaced the transmitted base from a 5 cm or 5° difference (`StationReader`), the re-prepared plan changed and the
  resume was refused ("Re-prepared route differs" or "Link unreachable");
- the **robot's actual trail** (0.45.2) in `mission-trail-<hash>.bin` (binary, mowing start date then
  points as floats), written at most every 15 s and immediately at start, resume and interruption, deleted
  with the checkpoint. On resume, the in-memory trail of the same mowing is reused, otherwise the one on disk: the map
  shows the whole route traveled since the start. The trail is capped at 12,000 points by simplifying its older
  part to within 2 cm (`MissionTrail`), the last 2,000 points remaining raw.

During tracking, progress only moves forward (a transient back-off does not move it back). It is written at most every 5 s or every 2 m of cutting, and immediately at start, at each recharge and at each interruption. If the app is killed, the last write prevails: at worst 5 s or 2 m are redone. The end of the last mowing stripe deletes the checkpoint; if the final return to base then fails, it can be restarted from Map → Charging station.

## Resume

The map shows the task title and its progress (`MissionTask`: **Mowing interrupted**, **Route with blades stopped
interrupted**, **Signal survey interrupted**, **Border following interrupted**, **Trial mowing interrupted**, e.g.
"Mowing interrupted · 42 %"), with the time, the cause (`InterruptionSummary`), the height and the zones, then
**Resume**, a **Discard** link (with confirmation) and a **⋯** menu (**Unblock**, **Resume further on**, **Stop
details**). User-facing behavior: [resume feature page](features/mowing-resume.md).

**Resume**:

1. re-prepares the route from the saved preview (`RoutePlanning.resume`, shared by all tasks), with the current map, base, accesses and saved obstacles, but with the presumed obstacles and difficult areas **from the start**: a contact that has occurred since (most often the one that interrupted the mowing) becomes a presumed obstacle that does not change the plan; it is bypassed during tracking ("Obstacle added on the route"). A checkpoint older than 0.40.2 recomputes this context as of the start date;
2. refuses if the map, the plan fingerprint or the robot differ ("Map changed since the interruption: resume
   impossible, start a new mowing", "Re-prepared route differs (base, access or obstacles changed): resume
   impossible, start a new mowing", "Resume reserved for the robot of the interrupted mowing"), or if no stripe is left
   ("Mowing already finished: use return to base");
3. resumes the interrupted edge **30 cm before** the point reached (overlap, never before the start of the edge), then the remaining edges and the return to base;
4. computes the link from the robot's actual position with the same checks as for a normal mowing: footprint, obstacles, base safety zone, clearance of an obstacle reserve. If the robot is on the base, it waits for the high recharge threshold and leaves in a straight line.

### Unblock and Resume further on (0.50.0)

The resume route (step 1) is prepared once and shared by the resume, the "Resume further on" preview and
"Unblock"; it is redone if the checkpoint, the map, the base, the accesses or the obstacles change.

**Unblock** (`RobotBle.backOffBeforeResume`, `ResumeBackOff`): straight back-off, heading unchanged, blades OFF, at most
`ResumeBackOff.MAX_M` = 1.5 m. Back-off guide, in order of preference: recent actual trail (robot within 0.5 m of its end),
prepared route over the last 6 meters before the stop point (`plannedBefore`, robot within 0.5 m), otherwise a straight
line behind the robot. Candidate points (`ObstacleBypass.backOffCandidates`, 10 cm steps from 30 cm) stay within
0.25 m of the guide and stop before the first step that would hit an edge, a no-go zone or a physical obstacle; the point
kept is the first where the robot can pivot and drive with the margins of the resume route
(`ObstacleBypass.backOff`), otherwise the farthest that allows a physical pivot, otherwise the farthest admissible.
Execution is that of the back-off after contact (`reverseStraight`: lateral deviation ≤ 12 cm with heading hold by in-place pivots since 1.1.4, progress, envelope,
blades, battery, state). Refusal: link, activity in progress, stale state, robot charging, STOP lock, protection
other than the collision lock, unknown position or heading. Under the collision lock (25), the back-off is allowed as
after a contact, then the release of the lock is awaited. Log: `resume_back_off_start|done|failed|stopped|refused`.

**Resume further on** (`MissionProgress.skipAhead`, `skipToward`, `skipped`, `ResumeSkipView`): the distance is
counted in cutting meters along the prepared edges from the stop point; blades-stopped links do not
count and the resulting point is always on a stripe. Slider bounded to the remainder minus 0.5 m. A tap on the
map picks the closest point (at most 3 m) on the remaining stripes; the already mowed part of the interrupted
stripe brings it back to the stop point. At start, `startRoute` checks that the point is on a stripe, never before the
stop point, then moves the checkpoint (`edge`, `point`, `doneMeters`) and accumulates `skippedMeters` (optional field
of the file, 0 for an older checkpoint). The route restarts from the chosen point **without** overlap
(`MissionResume.overlap = false`). Log: `mission_resume` with `skippedMeters` and `skippedTotalMeters`.

All the start guards of a mowing apply (link, fresh state, battery, position, blades, no other activity). Resuming is always a user action: no automatic resume after reconnection or restart, except after an
announced update of the ESP32 gateway (`ota_begin` event, "Updates during mowing" setting on):
`RobotBle.planGatewayUpdateResume` then restarts the same resume (same guards) as soon as the link has been ready for 8 s with
a fresh position, at most 3 attempts within 6 min, then a `RESUME_FAILED` alert. Second exception (0.62.0,
`simulation/DockResume.kt`): a gateway link lost during the recharge cycle of a mowing (`rechargeCycle`
true from the return at the low threshold until the start of leaving the base, or while charging for a start from the base). `RobotBle.release`
(link closed) and a route failure due to a link cause (`DockResume.linkCause`: Wi-Fi cutoff in progress,
"Link lost", "Gateway Wi-Fi"…) call `planDockResume`, which restarts the same resume as soon as the
link has been ready for 8 s, with a report less than 2 s old and the robot charging outside a Mammotion return
(`DockResume.docked`), at most 3 attempts within 15 min, then `RESUME_FAILED`. Log: `dock_resume_planned`,
`dock_resume_resumed`, `dock_resume_skipped`, `dock_resume_cancelled`, `dock_resume_failed`. Docking itself
holds the link (`GatewayLink.holdUntil`) and waits up to 1 min for a fresh state during a held cutoff.

Starting a new mowing replaces the checkpoint; the preview says so before **Start**.

## Smart resume

`simulation/SmartResume.kt`. The checkpoint keeps, besides `edge`/`point` (position in the portion in progress), the
end of that portion (`activeEnd`, null: end of the plan), the following portions in order (`deferred`, list of
`MissionSpan(from, to, blockedAt)` of `PlanPos(edge, point)`) and the blocked pose when the portion in progress is a
deferred retry (`activeBlocked`). Without them the progress is linear, as before; older files are read as linear.
`doneMeters` is the total minus the cutting metres of the portions.

- **Route**: `RobotRoute.missionEdges` with `MissionResume.of(checkpoint)` builds the portions (`spanRoute`: prepared
  edges of each portion without its leading/trailing transits, blades-OFF links `TrajectoryPlanner.between` between
  portions, overlap of 30 cm at a portion starting mid-edge), then the usual departure, return and checks
  (`requirePath`, base zone). `MissionEdges.engineIndex` maps each route edge to its prepared edge (-1: link).
- **Progress** (`SmartResume.advance`): monotonic in the portion in progress; a position inside a later portion makes
  it the portion in progress (what is left of the previous one beyond the overlap stays deferred); a position within
  the overlap before the resume point is ignored.
- **After a recharge** (`afterRecharge`): candidates = rotations of the remainder at a block start (cutting edge after
  a prepared transit) or at the start of a deferred portion; cost = transits kept inside the portions + links start →
  … → approach point computed by the navigation, straight-line lower bound to prune (40 evaluations at most). Chosen
  if it saves at least `MIN_GAIN_M` (2 m); plans with several heights are not reordered. Used by `RobotBle.startRoute`
  when the robot starts docked (manual or automatic resume, event `route_smart_resume`) and by the in-mission recharge
  cycle after the base exit (`replanMission`: same building, then `ObstacleBypass.repair` around the obstacles
  discovered during the mission; failure ⇒ usual resume at the interrupted point, event `route_replan_failed`).
- **After a blockage**: `RouteBlocked` (presumed blockage 3 s, no progress 15 s, Fold or gateway leg) goes through
  the contact bypass with an estimated obstacle (`ObstacleEncounter.blockage`). `deferBlockage` is called when the
  bypass is impossible (repair failure, 5th contact within 2.5 m) or after `DriftRecovery.MAX_SAME_SPOT` exits in a
  row at the same place: `deferBlocked` moves the segment from the progress to the first point of the remainder at
  least `CLEARANCE_M` (1.5 m) from the blocked pose and `MIN_SKIP_M` (0.5 m) further to the end, with `blockedAt`;
  back-off of `BACK_OFF_M` (0.5 m) along the trail (`DriftRecovery.starts`), route rebuilt from there. A retry blocked
  within `SAME_SPOT_M` (1.5 m) of its `blockedAt` gives the segment up (`skippedMeters`), and a deferred segment that
  cannot be routed around the discovered obstacles is given up too (`abandonNear`). Nothing left: straight return to
  the approach point. At most `MAX_BLOCKAGES` (6) per mission; on a station route, no deferral (stop as before).
  Events `route_blockage_deferred` (position, deferred and given-up metres, target), difficulty `BLOCKED`.
- **Not covered**: the map simulation (`MowingSimulation`) still resumes at the interrupted point after a recharge;
  "Resume further on" is refused on a reordered remainder.

## Limitations

- Borders already done are not redone; the stripe between the robot's actual stop and the saved point is covered by the 30 cm overlap and by the projection of the measured position.
- A change to the map (including a [local map correction](features/map.md#correcting-the-map-local-corrections)), the base (beyond 25 cm or 15°), the accesses or the saved obstacles invalidates the resume: for safety, no approximate plan is substituted. Saving as an obstacle a contact that occurred during mowing also invalidates it.
- A re-preparation failure is shown on the map ("Resume failed: …") and logged (`simulation_preparation`, result `RESUME_FAILED`).
- The checkpoint does not survive clearing the app's data.
- Physical validation (STOP in the middle of a stripe then resume, closing the app during a recharge) still has to be done under supervision.

### Resume impossible after a contact (0.40.1, Oct 14 at 17:31)

Mowing stopped during the back-off that followed a bumper contact ("Back-off deviation >12 cm", edge 494, 55 %). The contact, recorded at (−8.24; 8.89), became a "recently hit" presumed obstacle (radius 0.2 m, margin 0.3 m) on the interrupted stripe. The resume took this obstacle into account: the stripe crossed its reserve and the preparation failed before control even started (no `robot_route_refused` or `mission_resume` in the log). Fixed in 0.40.2.

## Verification

`ResumeOptionsTest` (JVM) covers "Resume further on" (skip along stripes only, bounds, tap on the map,
skipped stripes, start without overlap, refusal before the stop or on a link, saving of skipped meters) and
"Unblock" (choice of guide, closest safe point, stop before a no-go zone).

`MissionResumeBaseTest` (JVM): same plan resumed with the station observed after redocking (1.3 cm, 8°), station
moved by one meter still refused, same-station tolerance (axis around 0°/360°, unknown axis), base read back from
storage. `RobotBleIncidentsTest` "station re-observed on redocking" replays the mowing interrupted by the end of
working hours, redocked 6 cm further, then resumed through the app's path (`RobotBench.resumeEngine`).

`SmartResumeTest` (JVM): reordering after a recharge on a synthetic plan (far cell, cell near the base, far cell:
≥ 15 m of transit saved, same cutting metres on every prepared edge), no reordering when the interrupted point is on
the way or with height passes, progress followed across the portions to the end, overlap not taken as a jump, blockage
deferred then given up when blocked again, deferred portion given up near the blocked place only, storage of the
portions. `SmartResumeReferenceTest` (JVM, reference map, Aire de jeux + Potager + Piscine + Fosse, real navigation):
interrupted at 20 / 35 / 50 / 65 / 80 % of the plan, the reordered route from the approach point has 17.4 / 33.2 /
35.4 / 19.0 m less transit than the usual resume (unchanged at 80 %), passes `missionEdges` and keeps every
remaining stripe. `RobotBleBlockageTest` (bench, end to end): wheels spinning on soft ground ⇒ blockage on the map,
back-off, bypass, mowing complete; checkpoint with a deferred segment restored after a restart, resumed from the base,
the robot goes back over the blocked place and the mowing completes.

`MissionResumeAvoidanceTest` (JVM) reproduces the contact on the interrupted stripe: original plan recovered with the saved context or the one recomputed as of the start date, context preserved by storage, old files read. `MissionCheckpointTest` (JVM) covers: exact round trip of the preview and same fingerprint after re-preparation, sensitivity of the fingerprint and of the map key, overlap and bounds of the resume, cutting meters, monotonic progress, matching of the resumed route's indices, refusal with no mowing left, storage, deletion and corrupted file.

## All tasks and go-to-point (0.61.0)

Since 2026-10-16, the current task is remembered so it can resume exactly where the robot stopped, after an
app restart or otherwise.

- **Coverage tasks**: the checkpoint above applies to every tracked task (`RobotBle.startRoute`, intent
  FOLLOW). Supervised tests (border, painted zone) are prepared without presumed obstacles or avoided areas: they
  declare it at start (`startRobotRoute(…, avoidance)`), the checkpoint records it, and `RoutePlanning.resume` rebuilds
  exactly their plan from the map (`MissionTaskResumeTest` on the reference map). The history keeps the task type
  (mowing, blades stopped, survey) and its progress follows the checkpoint.
- **Go-to-point** (`PointResume`, `point-resume-<hash>.json`): destination saved at start, reason
  added on stop (STOP included), deleted on arrival, on abandon or when a coverage task starts. No reason
  after a restart: the app was closed during the movement. The resume recomputes the path from the
  actual position (`RobotBleEssaisTest`).
- Not resumed: navigation and cutting-while-moving tests, obstacle bypass, blade or
  height tests (short tests, to be restarted), return to base (to be restarted from Charging station).

