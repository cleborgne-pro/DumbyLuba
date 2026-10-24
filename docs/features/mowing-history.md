# Task history, route replay and end-of-mowing report

Every task started on the robot (mowing, signal survey, run with blades stopped) is recorded from start to finish,
with every actual position received: it can be found in the history and its route can be replayed on the map. For a
real mowing, the actual duration, recharges and battery are compared with the estimate shown at the start; the
observed deviations correct the following estimates.

| | |
|---|---|
| **Access** | Map → **History** (latest tasks); Service → Garden and history → **Task history**; report shown at the end of mowing |
| **Status** | Implemented and covered by JVM tests (`MowingHistoryTest`, `TaskTrackTest`, `TaskJournalTest`, `BlackBoxTest`, `MowingScoreTest`, `TaskHistoryTextTest`, `RouteDeviationTest`, `CoverageMapTest`, `HistoryComposeTest`, `MowingEstimateTest`, `FinishPolicyTest`, `FinishingPassTest`; screenshots `UiShotsHistoriqueTest`); replay to be validated on long real mowings |
| **Code** | `history/MowingHistory.kt` (measurement, comparison, calibration, storage), `history/TaskTrack.kt` (timestamped track, sorting, storage), `history/TaskEvent.kt` (typed task journal), `history/BlackBox.kt` (incident black boxes), `history/TaskExport.kt` (export), `history/TaskHistoryUi.kt` (latest tasks sheet, detail, replay), `history/CoverageMap.kt` and `history/RealCoverage.kt` (real coverage), `history/MowingScore.kt` (Mowing Score), `history/MowingHistoryUi.kt` (report, score, comparison, Task history page), `ble/RobotBle.kt` (`historyBegin`, `trackBegin`, `historyEnd`) |

## What is recorded

Every task in which the robot follows a prepared route is recorded:

| Task | Started from | Effect on estimates |
|---|---|---|
| **Mowing** | **Mow**, or **Run this route → Mow** from a simulation (blades running) | adjusts them; end-of-mowing report opened |
| **Signal survey** | Service → Signal surveys → dedicated run ([signal surveys](signal-surveys-and-terrain.md#dedicated-survey-route-optional)) | none |
| **Run with blades stopped** | **Run this route → Follow, blades stopped** | none |

Station routes (**Recharge**, leaving the base), moves to a point and random walk are not included.

| Measurement | Detail |
|---|---|
| Estimate at start | total duration and planned recharges, computed from the robot's **actual battery** at the start and the calibration at that time; it stays frozen |
| Time off charge | mowing, links, returns to base and docking |
| Time on charge | charging contact established (including recharging before the start) |
| Battery | consumed off charge and recharged on charge, over the same intervals as the durations |
| Recharges | returns made to recharge during the mowing |
| Progress | cutting meters covered out of the planned meters (for a survey or a run: share of the prepared route traveled) |
| Incidents | obstacles bypassed, resumes after interruption |
| Context | zones, area, route mode, cutting height, speed, return and resume thresholds |

The measurement is taken every 2 s from telemetry. A gap of more than 30 s (connection lost, app suspended) counts
neither in duration nor in battery. The record is saved every 30 s: a task still "in progress" when the app restarts
is classified as **Interrupted** with the reason "App closed during mowing".

A [resume](mowing-resume.md) extends the record of the interrupted mowing: durations, battery and recharges add up and
the final report covers the whole mowing. A resume of a mowing that has no record (older than the history) is not
measured.

A task is **Finished** when it ends normally or when every cutting stripe has been covered (even if the final docking
fails afterwards); otherwise it is **Interrupted**, with the stop cause.

## End-of-mowing report

At the end of a mowing (completed, or interrupted by a STOP, an error or a lost connection), the **Mowing report**
opens on top of the current screen. When the mowing is finished, a **Mowing finished** notification (info) gives the
zones and the duration verdict ([notifications](notifications.md)). Surveys and runs with blades stopped open no
report; their detail stays available in the history.

The report starts with a status line ("Mowing finished", or "Mowing interrupted at 45 %: cause") and shows:

- **Mowing Score** (once the track is loaded, see [below](#mowing-score)): score out of 100 and the points taken off
  by each factor.
- **Mowing**: zones, start and end, area (with the share mowed if interrupted), **Cutting covered** (meters covered /
  planned), **Settings** (route mode, height, speed) and **Incidents** (obstacles bypassed, resumes).
- **Duration** (**Duration (part mowed)** if interrupted): estimated and actual, with "of which mowing and travel" and
  "of which recharging". The verdict is "As estimated" within ±10 %, otherwise "X % longer (or shorter) than planned";
  "Too short to compare" when the estimate is one minute or less.
- **Recharges**: estimated and actual, with "As estimated" or "N more (fewer) recharges than planned".
- **Battery and pace**: battery at start and end, **Consumed · recharged** points; **Duration / theoretical run**
  (time off charge divided by the run time at the set speed: pivots, links, returns and slowdowns), **Observed
  runtime** (minutes per 100 % off charge) and **Recharge 0 → 100 %**, each compared with the value used by the
  estimate ("planned"), or "not enough data".
- **Next estimates**: the values used from now on.
- **Use for estimates** toggle (on by default, available once the task is over): turn it off for a test or an
  abnormal mowing; it then no longer corrects the next estimates and the history marks it "excluded". **Close**
  closes the report.

For an interrupted mowing, the estimated duration and recharges are scaled down to the part mowed (a planned recharge
is only expected if the mowed part reaches it); the full estimate is shown after "of".

## Mowing Score

The **Mowing Score** sums up the quality of a mowing out of 100: 100 minus the points taken off by five factors, each
shown with the value measured and its points ("82 % of the zones mowed · −7 / 40"), points taken off in orange. It is
computed when the report or the detail opens, from the record, the track, the task journal and the real coverage
(`history/MowingScore.kt`):

| Factor | Measure | Points taken off |
| --- | --- | --- |
| **Coverage** | share of the zones really mowed ([real coverage](#real-coverage)); without it, share of the route covered, marked "(estimate)" | (1 − share) × 40 |
| **Overlaps** | area mowed twice or more / area mowed (real coverage only; "not measured" otherwise) | beyond 15 %, 0.5 point per %, 15 at most |
| **Rotations** | pivots on the spot in the track (60° or more while the rotation centre moves less than 15 cm; recharging and docking left out) against the pivots of the prepared route (corners of the stripes, two per zigzag turn) | 0.3 point per % beyond the planned pivots, 15 at most |
| **Reversing** | distance driven against the heading (moves of 3 cm or more) | 0.5 point per metre beyond 1.5 m per run (leaving the base, resumes), 10 at most |
| **Incidents** | incidents of the [task journal](#replay-of-the-actual-route) (obstacle, contact, blockage, lift, robot error, STOP not asked by the user, link lost), those less than 10 s apart counting once; without a journal, the obstacles bypassed, marked "(obstacles only)" | 4 per incident, 20 at most |

A note under the score recalls these rules and, without real coverage, that coverage is estimated and overlaps are
left out: a task recorded before the real coverage or the journal therefore scores with what it has.

**Compare two mowings** (Task history page, from two finished mowings): choose the first then the second mowing (the
rows of the other mowings; **Cancel** goes back), then both are shown side by side, the older on the left: date,
zones, outcome, settings, duration, recharges, battery used, then the score and each factor. The back gesture returns
to the history.

## Adjusted estimates

The route preview (**Total time**, **Recharges**) and the pre-computation estimate use the same formula as before
(distance, speed, runtime, recharge time, return and resume thresholds), adjusted by the robot's real mowings:

| Quantity | Calculation | Minimum measurement per mowing |
|---|---|---|
| Duration factor | time off charge / run time of the part mowed, average weighted by duration (bounded to ×0.5 … ×4) | 5 min off charge, 5 % mowed |
| Runtime | cumulative time off charge / cumulative battery consumed (bounded to 10 … 600 min) | 5 min and 5 points consumed |
| 0 → 100 % recharge | cumulative time on charge / cumulative battery recharged (bounded to 10 … 600 min) | 10 points recharged |

- The **8 most recent mowings** of the connected robot are taken into account (all recorded mowings if this robot has
  none yet), excluding discarded mowings, surveys and runs with blades stopped.
- A quantity without enough measurements keeps the setting (runtime 60 min, recharge 30 min by default).
- With a connected robot, the estimate starts from its actual battery; otherwise from the simulation setting.
- The note under the preview gives the source: "Estimate adjusted on N real mowings: duration ×1.25 · runtime 52 min ·
  recharge 70 min for 100 %", or, as long as no mowing adjusts it, "Simplified estimate: recharging included, return
  trips and pivots excluded. It will adjust on the next real mowings."
- The simulation keeps its own settings: calibration only changes real-mowing estimates.
- During a mowing, the decision to finish without recharging at the low threshold uses a cautious model: the shortest
  runtime and the longest duration factor between the estimate frozen at start and the measurements of the current
  mowing (`MowingCalibration.finishModel`; see
  [estimating the energy for the remainder](real-mowing.md#estimating-the-energy-for-the-remainder-0480)).

During mowing, the **Mowing in progress** page shows the elapsed time against the estimated duration ("1 h 05 /
≈ 2 h 10 estimated") and the planned recharges.

## History

On the map, the **History** button (replacing the former Tasks button) opens **Latest tasks**: the current task under
**In progress**, then **Completed · N most recent** (the 12 most recent finished tasks). Each row shows the type and
zones ("Mowing · Piscine"), then the date, the outcome (**Finished**, **Interrupted**), the actual duration with its
deviation from the estimate ("1 h 12 (+8 %)"), the recharges, the share mowed or covered if interrupted, and
"excluded" for a mowing left out of the estimates. **Full history** ("All tasks and adjusted estimates") opens
Service → **Task history**.

The **Task history** page (Service → Garden and history; the hub row shows "Last: mowing · date", or "Reports and
actual routes" when empty) contains:

- **In progress**: the running task, its duration against the estimate and "Mowed · recharges" (or "Covered ·
  recharges");
- **Next estimates**: duration factor, runtime and recharge 0 → 100 % currently used ("setting" when not adjusted),
  with the number of mowings used; without enough measurements, a note says that each real mowing of at least 5 min
  adjusts them;
- **Compare two mowings** ("Score, coverage, duration and recharges side by side"), from two finished mowings, see
  [Mowing Score](#mowing-score);
- **Tasks · N**: every finished task, newest first ("No task recorded…" when empty);
- a note: the last 200 tasks are kept on the phone, with the actual route of the 40 most recent.

Tapping a task opens its **detail** (title "Mowing of <date>", "Signal survey of <date>"…): status line, replay of the
actual route, then the report (full report and **Use for estimates** toggle for a mowing; for a survey or a run:
zones, start, end, duration, route covered / planned and mode, battery, obstacles bypassed, and a note that it does not
adjust the mowing estimates).

**Mow** now takes over the role of the former Tasks button: it reopens the already computed preview if there is one,
otherwise the zone selection (the back arrow takes the preview back to the parameters, then to the zones).

The last 200 tasks are kept in `mowing-history.json` (private storage, atomic write; a task recorded by an earlier
version is a mowing). Each report is also traced in the log (`mowing_history_start`, `mowing_report` with `kind`), with
the wet grass index ([rain and weather](rain-and-weather.md#wet-grass-and-slips-when-pivoting-0441)).

## Replay of the actual route

The detail of a task shows the **Actual route** section: the **task map** (the one at start time, even if the robot's
map has changed since) with:

- the **prepared route**: cutting stripes as planned bands and links as a thin orange line; **Prepared route** toggle
  (on by default, unavailable when the task context was not recorded);
- **every actual position** received during the task, colored by activity: green for mowing (blades running), blue
  for moves with blades stopped, orange for returns, docking and recharging (legend under the controls: **Mowing**,
  **Blades stopped**, **Return, recharge**). The part already replayed is bright, the rest faded;
- the **robot** at the chosen moment, with its heading (interpolated between two positions).

**Play** / pause replays the route at ×10, ×60 (default), ×300 or ×1000 (recharges run 20 times faster); the replay
button goes back to the start. The **slider** places the robot at any moment, with the time of day, the time elapsed
since the start, the activity and the battery. **Recorded positions** gives their number and the distance actually
covered (jumps between unjoined pieces excluded). Two-finger zoom, rotation and panning. Without a recorded map, the
robot's current map is shown with a note.

When the task journal holds incidents, a row under the speeds jumps between them: **Previous incident** / **Next
incident** move the replay (paused) to the incident before or after the cursor, and the text gives the incident at
the cursor ("Incident 2 / 5 · Bumper contact", or "5 incidents" before the first one). Tapping an incident of the
**Task journal**, or **Show in the replay** in a black box, does the same.

Recording (track):

- every position received from the robot (center of rotation, like the map trace), timestamped, with heading,
  activity and battery; a position is kept after 8 cm of movement, 6° of rotation or a change of activity, and every
  10 s when stationary;
- since track format 2, each position also keeps the **last speed command** written by the phone (raw linear and
  angular units of the protocol, empty when the phone did not drive the robot in the last 3 s: route handed to the
  gateway, native return), the **measured speed** between two received positions (m/s), the **RTK status** (4 = FIX)
  and the number of **satellites**, and the **link state** (direct Bluetooth, gateway relay, gateway autonomous
  tracking, gateway Wi-Fi cut);
- written in batches every 10 s (or every 300 positions) to `task-tracks/` (raw `.trk` file while the run goes on,
  extended on each resume, plus a JSON context file: map and prepared route at start time); the detail of a task in
  progress also includes the positions not yet written; a last position truncated by a crash is ignored;
- at the end of each run (end or interruption of the task), the raw positions are **compressed** into a `.trkz` file
  (gzip; a 3 h mowing takes a few hundred KB raw, less once compressed); a resume writes a new raw file, merged at the next
  end. Tracks recorded in format 1 stay readable (the new values are then unknown);
- two positions more than 3 m or 60 s apart are not joined (connection lost, robot moved);
- size bounds: the tracks of the **40 most recent tasks** at most, 12 MB of raw positions, 512 KB of events and
  2 MB of black boxes per task (beyond that, "Track size limit reached: further positions of this task are not saved" in the log), 64 MB for
  all tasks together (the oldest tracks are deleted first); older tasks keep their report.

Task journal: each task also records its **typed, timestamped events** (`.evt` file next to the track, one JSON line
each, with the robot position, the battery and a few raw values: error code, route phase, reason). They are shown in
the **Task journal** section of the detail (time, time since the start, type, text and battery; incidents in orange;
30 events, then **Show all N events**), and exported with the logs:

| Type | Recorded on |
| --- | --- |
| Start / Resume | start of the task, resume of an interrupted task, resume after a recharge, resume after the gateway was lost during charging |
| Return to base / Recharge | route phase changing to return or to charging (once per change), return at the end of the working hours |
| Obstacle / Bumper contact / Obstacle bypassed | obstacle handled during the route (counted in **Obstacles**), bumper contact, remaining route repaired |
| Blockage | terrain difficulty recorded as a blockage, no progress or wheel slip; robot protection during mowing |
| Lift / Robot error | lift detected; robot error code; incident during docking or charging |
| STOP | every stop request during the task (user, failure or safety, with `intentional`), STOP signalled by the gateway |
| Link lost / Link restored | Bluetooth or gateway lost, gateway Wi-Fi cut and its end, link keep-alive lost, motion setpoints starved |
| End | finished or interrupted, with the reason |

A same event repeated within 2 s is kept once, and a run keeps at most 1,500 events (the end always).

Black Box: while a task runs, the phone keeps a sliding memory of the last minute: the robot state every 0.4 s at
most (rotation centre, heading, last speed command, measured speed, battery, robot state, sensor bits, RTK status,
satellites, link state, route phase, Bluetooth and Wi-Fi signal), every command written to the robot (label and
setpoint), and the app log events (raw frames, write acknowledgements and periodic telemetry left out). Each incident
of the journal (obstacle, bumper contact, blockage, lift, robot error, STOP, link lost) **freezes a black box** 3 s
after it: the 45 s before the incident and the 3 s after. Incidents less than 10 s apart go in the same box ("Link
lost + STOP"); a task keeps its first 30 boxes. Each box is written to a `.box` file next to the track (one JSON line
each), logged as `task_black_box`, and exported with the task (`blackBoxes` of the task JSON file).

The **Black Box** section of the detail lists the boxes ("14:02:31 · Bumper contact + STOP", with the link, command,
battery and RTK at the incident and the number of states, commands and events). A tap opens one: **Show in the
replay**, the text of the incident, **Robot (every 2 s)** (time from the incident, position, heading, command, speed,
robot state, RTK, link), **Commands** (the last 15) and **Log** (the last 40 events), times relative to the incident
("−12.0 s", "+1.2 s").

A task recorded before version 0.53.0 has no track: the detail says "Positions not recorded: replay covers tasks
started since version 0.53.0." and only shows the robot's current map.

## Route tracking quality (since 1.2.0)

Under the replay, the **Route tracking** section measures how well the route was followed, so that a mowing tracked by
the gateway on the robot can be compared with one tracked by the pilot phone
([validation of autonomous tracking](../AUTONOMOUS_GATEWAY.md#to-validate-on-the-robot)):

| Row | Meaning |
|---|---|
| **Tracked by** | **Pilot phone, direct Bluetooth**, **Pilot phone through the gateway relay** or **Gateway on the robot, autonomous tracking**, as decided at the start and at each resume (several if they differ), with the number of route trackings actually handed to the gateway |
| **Deviation from the stripes · median · 95 % · max** | distance from each position recorded **with the blades running** to the nearest cutting stripe prepared at start; a position more than 3 m from every stripe counts as 3 m and the maximum is then shown "≥ 300 cm" |
| **Positions beyond 30 cm** | positions past the tracking limit (the tracking stops the robot beyond it), out of the positions measured |
| **Position holes over 15 s** | holes between two positions off charge (stationary positions are kept every 10 s): Wi-Fi of the gateway or link lost; number and longest |

The deviation is computed against the route frozen at the start: an obstacle bypass or a route repaired after a contact
counts as a deviation. Tasks recorded before 1.2.0 show the deviation (their track has the prepared route) but not
the tracking link. The start and end-of-task log events carry the link (`trackingLink`, `trackingLinks`) and the
number of trackings handed to the gateway (`gatewayLegs`).

## Real coverage

For a mowing, the detail also shows the **Real coverage** section: what was actually mowed according to the track,
not according to the prepared route. The cutting width (40 cm) is swept around every position recorded **with the
blades running** (green trace), on a 10 cm grid restricted to the task zones minus the no-go zones; two positions
are joined only within the same piece of track (more than 3 m or 60 s apart, they are not: a lost link does not draw
a stripe).

| Row | Meaning |
|---|---|
| **Covered** | share of the task zones mowed, rounded down ("96 % · 152 m² of 159 m²") |
| one row per zone | the same for each zone when the task has several ("Fond · 0 % · 0 m² of 16 m²") |
| **Mowed twice or more** | area swept again by a later pass (overlaps, U-turns, resumes) |
| **Gaps of 0.25 m² or more** | finished task only: number and total area of the portions left unmowed, wide enough for a pass |

Strips narrower than about 30 cm (unreachable edges along the boundaries, raster noise between two passes) are not
counted as gaps. On the replay map, the **Real coverage** toggle (on by default) draws the mowed area in green (darker
where it was mowed twice) and, once the task is finished, the gaps in orange.

The same layer is drawn on the main map ([Real coverage layer](map.md#real-coverage-layer)) during the mowing and
after it, and the mowing bar shows "Covered x %" ([real mowing](real-mowing.md)). The gaps are polygons in map
metres that the planner accepts as a target, like a painted area (`CoverageMap.gapArea` →
`PaintedZone.prepare`): technical details in [REAL_COVERAGE.md](../REAL_COVERAGE.md). A track recorded before this
version has no task zones: all the zones of its map are measured.

## Finishing of the gaps

When a finished mowing leaves at least **1 m²** unmowed (and no more than 30 % of its zones: beyond, resume or mow
again), the end-of-mowing report and the task sheet show a **Finishing** group: **Left unmowed** "3.2 m² in 4 gaps" and
**Prepare the finishing**. The button is offered for the connected robot of that mowing with its complete map;
otherwise a note asks to connect it.

**Prepare the finishing** closes the report and opens Service → **Trial mowing** targeting those gaps (orange outlines)
instead of a painted zone; **Paint a zone instead** goes back to painting. **Compute** plans a short route: each gap is
widened 20 cm over the mowed grass so that a lap runs on its edge and the passes cover it, even about 1 m wide; along a
zone boundary the lap is the usual border lap. The **Finishing of the gaps** group then shows:

| Row | Meaning |
|---|---|
| **Route** | length of the whole route and its share of a new mowing of the zones ("79 m · 12 % of a new mowing (636 m)") |
| **Robot envelope** | every cutting pass checked with the LUBA 2 footprint inside the safe surface of the robot map |

The route is refused when a pass leaves the safe surface or when it exceeds half a new mowing ("Finishing refused: …;
mow the zones again"). It then runs like any trial mowing: supervision box, blades, protections, return to base and
resume from the map. Details in [REAL_COVERAGE.md](../REAL_COVERAGE.md#finishing).

## Limitations

- The track only contains the positions received by the phone: during a gateway Wi-Fi outage, the route followed does
  not appear in the replay (the map trace, however, is filled in by the gateway), and the real coverage misses it
  when the hole is longer than 3 m or 60 s.
- Real coverage trusts the reported position (RTK) and the blade state: a wrong position or a cut wider or narrower
  than 40 cm (tall grass, height pass) shifts it accordingly.
- Battery is read in whole points: a short mowing measures runtime poorly, hence the minimum thresholds.
- Recharging is not linear (slower near full): the recharge time used is an average over the recharges actually made,
  between the set return and resume thresholds.
- The duration factor lumps together everything that is not running at the set speed; a change of speed or route
  mode shifts it until new mowings correct it.
- Dates and numbers follow the interface language ([language](interface.md#language)); a stop reason is stored in the language
  of the time it was recorded.
