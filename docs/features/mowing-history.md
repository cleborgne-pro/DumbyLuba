# Task history, route replay and end-of-mowing report

Every task started on the robot (mowing, signal survey, run with blades stopped) is recorded from start to finish,
with every actual position received: it can be found in the history and its route can be replayed on the map. For a
real mowing, the actual duration, recharges and battery are compared with the estimate shown at the start; the
observed deviations correct the following estimates.

| | |
|---|---|
| **Access** | Map → **History** (latest tasks); Service → Garden and history → **Task history**; report shown at the end of mowing |
| **Status** | Implemented and covered by JVM tests (`MowingHistoryTest`, `TaskTrackTest`, `TaskHistoryTextTest`, `RouteDeviationTest`, `HistoryComposeTest`, `MowingEstimateTest`, `FinishPolicyTest`; screenshots `UiShotsHistoriqueTest`); replay to be validated on long real mowings |
| **Code** | `history/MowingHistory.kt` (measurement, comparison, calibration, storage), `history/TaskTrack.kt` (timestamped track, sorting, storage), `history/TaskHistoryUi.kt` (latest tasks sheet, detail, replay), `history/MowingHistoryUi.kt` (report, Task history page), `ble/RobotBle.kt` (`historyBegin`, `trackBegin`, `historyEnd`) |

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

Recording (track):

- every position received from the robot (center of rotation, like the map trace), timestamped, with heading,
  activity and battery; a position is kept after 8 cm of movement, 6° of rotation or a change of activity, and every
  10 s when stationary; a 3 h mowing fits in a few hundred KB;
- written in batches every 10 s (or every 300 positions) to `task-tracks/` (one binary file per task, extended on each
  resume, plus a JSON context file: map and prepared route at start time); the detail of a task in progress also
  includes the positions not yet written; a last position truncated by a crash is ignored;
- two positions more than 3 m or 60 s apart are not joined (connection lost, robot moved);
- only the tracks of the **40 most recent tasks** are kept; older ones keep their report.

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

## Limitations

- The track only contains the positions received by the phone: during a gateway Wi-Fi outage, the route followed does
  not appear in the replay (the map trace, however, is filled in by the gateway).
- Battery is read in whole points: a short mowing measures runtime poorly, hence the minimum thresholds.
- Recharging is not linear (slower near full): the recharge time used is an average over the recharges actually made,
  between the set return and resume thresholds.
- The duration factor lumps together everything that is not running at the set speed; a change of speed or route
  mode shifts it until new mowings correct it.
- Dates and numbers follow the interface language ([language](interface.md#language)); a stop reason is stored in the language
  of the time it was recorded.
