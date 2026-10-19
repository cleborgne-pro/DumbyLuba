# Resuming an interrupted mowing

A mowing lasting several hours does not start again from zero after a STOP, a lost connection, the app being closed or
the phone restarting. Since 0.61.0, the same applies to **every task in which the robot follows a route**: run with
blades stopped, signal survey, border following and trial mowing (painted zone) resume at the point reached, and so
does a [move to a point](station-and-moves.md#move-to-a-point) towards the same destination.

| | |
|---|---|
| **Access** | Map → **Mowing interrupted · X %** panel (title of the interrupted task) |
| **Status** | Implemented and covered by JVM tests; to be validated on a long real mowing |
| **Code** | `simulation/MissionCheckpoint.kt`, `simulation/PointResume.kt`, `simulation/MissionTrail.kt`, `simulation/ResumeBackOff.kt`, `map/ResumeSkipView.kt`, `map/InterruptionSummary.kt`, `map/MapScreen.kt` (`InterruptedMowingPanel`), `simulation/DockResume.kt`, `planning/RoutePlanning.kt` (`resume`), `ble/RobotBle.kt` |
| **Reference** | [Resume: saving, resuming and limitations](../MOWING_RESUME.md) |

<p align="center">
  <img src="../images/resume.png" width="200" alt="Mowing interrupted">
</p>

## Saving

During any coverage task — mowing from the map, "Follow, blades stopped", signal survey, border following and trial
mowing (Service → Supervised tests) — the app writes one file per robot to its private storage, atomically:

- the full plan preview (it depends on the starting position and cannot be recomputed identically);
- the SHA-256 fingerprint of the mowing edges and a map key;
- height, recharge thresholds, edge and point reached, meters mowed, recharges;
- the height passes and the height of each stripe (0.45.0): a resume in the second pass restarts at that pass's height,
  then carries on with the following ones;
- the reason for the interruption;
- the starting base (station, docking axis, approach; 0.64.2);
- for a trial mowing, the **painted zones** where the blades keep running between two passes (0.61.0);
- the **robot's actual trace** (0.45.2), in a neighbouring file, tied to the mowing by its start date.

The file is written at most every 5 s or every 2 m of cutting, and immediately at the start, on each recharge and on
each interruption. At worst, 5 s or 2 m are redone. Finishing the last stripe deletes the save.

## Resume

The map panel bears the name of the task: **Mowing interrupted**, **Route with blades stopped interrupted**,
**Signal survey interrupted**, **Border following interrupted** or **Trial mowing interrupted**, followed by the
percentage ("Mowing interrupted · 42 %"). A task started from the supervised tests can also be resumed from the map,
with the same plan (same obstacles, blade zones). The panel shows the percentage mowed (all passes included), the
time and the cause, then a line with the height ("Pass 2/3 · 55 mm" with several passes), the zones in order, the
recharges, the resumes and the skipped meters ("1 recharge(s) · 2 resume(s) · 12 m skipped"). Below: the automatic
resume status if any, the resume error, "Failed to write the progress on the phone." on a storage error, the
back-off status, and "Connect the robot to resume (30 cm overlap at the interrupted point)." (robot disconnected) or
"Stay nearby during the resume." (robot self-stop not demonstrated).

Compact panel (0.62.0, small Fold screen): **Resume** is the only button ("Preparing…" while the resume route is
prepared), **Discard** is a link, and **Unblock** ("Back-off with blades stopped to a safe position"), **Resume
further on** ("Skip an obstacle or a difficult passage") and **Stop details** are in the **⋯** menu. While it is shown, the stopped-mowing bar (same
percentage) and the layers bar are hidden to keep the map visible; the same goes for the "Robot stuck" panel.

- **Resume** re-prepares the route from the saved preview, with the current map, accesses and obstacles and the
  starting base (0.64.2): on re-docking, the station observed while recharging can be a few centimeters or degrees away
  from the starting one (RTK noise, axis taken during docking) and must not change the resumed plan. A station moved by
  more than 25 cm or turned by more than 15° counts as a new base. The mowing then restarts **30 cm before** the point
  reached (overlap). The link is computed from the actual position with the usual checks; if the robot is on the base,
  it waits for the resume threshold, then leaves in a straight line. The trace already covered stays on the map and
  keeps growing: the route history covers the whole mowing, recharges and resumes included (0.45.2).
- **Discard** deletes the save, after confirmation ("Discard the interrupted task?" — "The progress is cleared: the
  next task will start from the beginning."). The resume needs a known base ("Unknown base: place the base before
  resuming") and a complete map; a preparation failure shows "Resume failed: …".
- **Unblock** (0.50.0): the robot backs up in a straight line, blades stopped, heading unchanged, to a safe position a
  little before the blockage (at most 1.5 m): the first one where it can pivot and move with the mowing margins. It
  follows its actual trace, otherwise the route mowed just before the stop, otherwise a free line behind it; borders,
  no-go zones and obstacles are checked at every step. **STOP** (in place of **Resume**) stops the back-off. The
  robot then stays stopped ("Robot backed off…"): tap **Resume** or **Resume further on**.
- **Resume further on** (0.50.0): to avoid an obstacle or a difficult passage, choose the distance to skip along the
  remaining stripes (slider "+ X m" in 0.1 m steps, up to the remainder minus 0.5 m; **− 1 m** / **+ 1 m** buttons) or
  tap a stripe on the map ("Set the distance or tap a stripe: the purple dot shows the resume."). The map shows live
  the resume point (purple "Resume" label), the stop point (red), the skipped stripes (orange, not mowed) and the
  remaining stripes. **Resume at + X m** starts from that point, without overlap; links with blades stopped do not
  count in the distance. The cross closes this mode. With less than one meter left: "Less than one meter of stripe
  left: nothing to skip." The skipped meters are added to the progress and recalled in the panel ("12 m skipped").

All the start conditions of a [real mowing](real-mowing.md#start-conditions) apply.

## Automatic resume after losing the gateway during recharging

Since 0.62.0, a mowing whose connection with the gateway drops during the recharge cycle (return at the low
threshold, docking via the official Mammotion return, charging, up to leaving the base) **resumes by itself**: the
panel shows "Automatic resume as soon as the robot is charging on the base and the gateway is back", then the mowing
restarts as with **Resume** (charging up to the resume threshold if needed, straight-line exit, link to the
interrupted point). Conditions: connection back and stable for 8 s, fresh robot report, charging contact and Mammotion
return complete, working hours open; at most 3 attempts in 15 min. Otherwise an "Automatic resume failed" alert asks
for a manual resume. **Discard** or a requested disconnection cancels the automatic resume. Only connection causes are
resumed: a robot protection, a contact or a refused docking leave the resume to the user.

## Resume refused

"…: resume impossible, start a new mowing" if:

- the robot is different ("Resume reserved for the robot of the interrupted mowing");
- the map has changed ("Map changed since the interruption: resume impossible, start a new mowing");
- the re-prepared route differs ("Re-prepared route differs (base, access or obstacles changed): resume impossible,
  start a new mowing": base moved by more than 25 cm or 15°, accesses or obstacles changed);
- the mowing was already finished ("Mowing already finished: use return to base").

These messages appear as "Start refused: …" on the panel.

## Things to know

- Resuming is **a user action**: there is no automatic resume after reconnection. Two exceptions: an ESP32 gateway
  update announced by the gateway, with the "Updates during mowing" setting on
  ([gateway](gateway.md#esp32-gateway)): the mowing resumes by itself, with the same guards, within 6 min; and a
  loss of the gateway during recharging (above).
- Only one task is saved per robot: starting a new task (mowing, survey, border, test) replaces the save; the map
  preview and the supervised tests warn about it before starting.
- The save survives the app being closed, not its data being cleared.

## Stop cause shown (0.49.1)

The **Mowing interrupted** panel gives the cause in one sentence, with the time: "Obstacle contact", "Obstacle
contact: the robot could not back off", "Robot stuck: the wheels turn without moving forward", "Link lost with the
gateway", "Link lost with the robot", "Stopped with the STOP button", "Robot left its route", "Battery too low",
"Robot protection active", "App closed or phone restarted during mowing"…; the full technical message is behind
**⋯** → **Stop details**. Robot still stuck (collision lock, robot STOP, protection): the unblocking panel ("Robot
stuck against an obstacle", "Robot locked after STOP"…) replaces it, recalls the task and its percentage and offers
**Discard**; the resume panel appears once the robot is freed ([map](map.md#during-a-mission)).
