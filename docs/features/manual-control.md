# Manual control

Two on-screen joysticks to drive the robot, with no map and no RTK. The blades stay stopped. With the
[gateway](gateway.md) mounted on the robot, control works **remotely over Wi-Fi**, and the robot's
[camera](camera.md) is shown large above the joysticks.

| | |
|---|---|
| **Access** | Service → Robot → **Manual control**; Map → **Manual**; Home → gamepad icon (same page everywhere) |
| **Status** | Implemented and used on the real robot; bumper reception and stopping after a real BLE loss still to be validated |
| **Code** | `manual/Joystick.kt` (response curve, notches), `manual/DriveJoystick.kt` (joysticks and deck), `manual/ManualDrive.kt` (shared control loop), `manual/JoystickPanel.kt`, `manual/ManualSpeed.kt`, `manual/DrivenRecordPage.kt` and `manual/DrivenView.kt` (record by driving page), `map/DrivenRecording.kt`, `ui/RobotPanel.kt` (`ManualPage`), `ble/RobotBle.kt` (`manualInput`, `stopManual`, `stopMammotionTask`, `startDrivenRecording`) |

<p align="center">
  <img src="../images/manual-control.png" width="200" alt="Manual control">
</p>

## Usage

1. Tick **I am supervising the robot**.
2. Set the **Maximum speed** (10 to 100 %, 30 % by default; the label gives the estimated speed in m/s). The last
   value is kept from one opening and one launch to the next.
3. Left joystick **Forward / reverse**, right joystick **Turn**. Both can be used at the same time; releasing an
   axis returns it to zero. The status reads "Control active — release to stop".
4. Releasing both joysticks, the round **STOP** between them, cancelling or leaving the page sends STOP and purges
   pending movements.

The checkbox and the speed are locked while a joystick is held.

### Joysticks (version 1.12.0)

Each joystick is a capsule along its single axis (vertical for forward / reverse, horizontal for turning), so the
direction it drives is visible at a glance:

- the finger is captured **anywhere on the pad**, not only on the knob; each pad keeps its own finger, so both can be
  held together;
- the knob follows the finger and springs back to the centre on release (display only: the axis command returns to
  zero at once); a green fill grows from the centre to the knob and the arrow (▲ ▼, ⟲ ⟳) of the driven side turns
  green;
- the **response is progressive**: zero in the 12 % dead zone, then rising from zero (no jump when leaving the dead
  zone) along a curve that gives fine control at low speed and the full chosen speed at the end stop — half the
  travel gives about a quarter of the maximum speed;
- a **haptic tick** is felt at each third of the travel and a stronger one at the end stop, marked by dots on the
  track, so the speed can be dosed without looking at the screen;
- under each pad, its live output ("Forward 18 %", "Left 9 %"), as a share of the maximum speed;
- locked (grey knob, padlock) until the supervision is confirmed or while the robot cannot be driven.

## Settings and commands

| Item | Value |
|---|---|
| Command rate | 5 Hz (every 200 ms) |
| Dead zone | 12 % |
| Response | progressive beyond the dead zone (exponent 1.6): half travel ≈ 26 % of the maximum speed, end stop 100 % |
| Haptic notches | each third of the travel beyond the dead zone, stronger at the end stop |
| 100 % | 1000 units forward, 450 in rotation (≈ 1 m/s, ≈ 45°/s; speeds above 0.5 m/s estimated, not measured) |
| Axes | independent: turning does not reduce the forward speed |
| Blades | always stopped; the robot is driven in manual mowing mode with blades OFF (state 20, renewed every 500 ms, left on release), the only mode where the LUBA 2 reports a bumper contact |

## Remote control with the camera

1. Gateway in place and phone in *Control via the gateway* mode ([gateway](gateway.md)).
2. Open manual control: the live view is shown large above the joysticks and the stream is requested while the page
   is open (Mammotion account connected, robot not on 4G; otherwise the view gives the reason). The view can be
   hidden; it reconnects on its own ([camera](camera.md#live-view-on-the-map-and-in-manual-control-reconnection)).
3. Drive while watching the view.

If the pilot phone goes silent for more than 2 s (Wi-Fi down, app frozen), the gateway stops the robot. The video
arrives with a slight delay: keep the speed moderate.

## Guards

- LUBA 2 (`Luba-VS…` or X series `Luba-VP…`) ready (state 11) to start; state 11 or 20 accepted while driving. Fresh state (≤ 2 s),
  battery > 30 %, blades stopped, no protection (self-check 0 or 10; 25 accepted beyond the mapped zones, see
  [safety](safety.md#self-check-25-beyond-the-mapped-zones-version-1110)), no bumper or lift alert, no mission, test, map
  reading, height adjustment or Mammotion task cancellation in progress. A refusal reads "Control refused: …".
- A joystick input not refreshed for 500 ms, a protection, a bumper or lift alert, a robot error, a stale state, a
  link loss or a saturated send queue (more than 4 commands) stops control with a critical alert; it only resumes
  with a new press.
- Joystick movements feed the [lift detection](safety.md#lift) and the signal surveys.
- Going to the background and the global STOP use the usual stop ([safety](safety.md)).

## Recording a zone, a passage or a no-go zone by driving

As in the Mammotion app, a new shape is recorded by driving the robot along it with the joysticks (with the camera
view through the gateway). Unlike the Mammotion app, **nothing is written to the robot**: the shape is saved on the
phone as a [local correction](map.md#mammotion-zones-and-dumbyluba-zones) and joins the map used everywhere in the app
(1.4.0).

<p align="center">
  <img src="../images/record-by-driving.png" width="200" alt="Record by driving">
</p>

Since 1.12.0, recording has its **own page**, laid out for a folded Fold: the map fills the screen in the background,
the live camera sits at the top and the joysticks at the bottom. Access: manual control → **Record by driving**
(shows the recording in progress, "Zone · 12.4 m", to resume it), or Map → **Map** sheet → *Local corrections* →
**Record by driving**. Back returns to the page it was opened from.

| Area | Content |
|---|---|
| Header | back, title, red **REC** badge with the recorded length (grey when paused), bin to **cancel the recording** (confirmation) |
| Top | live camera view, 16:9, full width (can be hidden; "Show camera" brings it back) |
| Map (background) | zoomed in (≈ 11 m across) and **centred on the robot, which it follows** while it moves (position glided between two readings); the robot to scale, the zones, the trace (blue), its start with the 1 m closing circle, a dashed line from the last recorded point to the robot, a 1 m grid and a scale bar |
| Map buttons | **Follow the robot** (green while following; dragging the map suspends it, the button resumes it), **north up / heading up** (the map turns so the robot's nose points up, like the camera), zoom **+** / **−** (pinching works too) |
| Notes on the map | refusal before starting, no RTK FIX, pause, distance left to close the outline, outline closed, control refusal, robot position unknown, saved result; before starting, what the chosen kind records |
| Bottom | recording bar, then the joystick deck: forward / reverse, maximum speed (− / +, by 10 %, with the estimated m/s) and round **STOP**, turn |

The joysticks stay locked under a "I am supervising the robot" card until it is tapped (blades stopped, area clear,
physical stop within reach). Leaving the page stops the robot but keeps the recording.

1. Choose **Zone**, **Passage** or **No-go**, then **Start** (robot connected, robot map read, no route in progress).
   Reports go to 200 ms while recording.
2. Drive the robot while watching the camera and the map. Only fresh **RTK FIX** positions are recorded, one point
   every 15 cm; without FIX, "No RTK FIX: positions are not recorded until it comes back (straight join)".
   - **Pause** / **Resume** (round button): move the robot without recording; the trace resumes in a straight line
     from its last point.
   - **Erase 1 m** (undo arrow): removes the last meter of the trace, to redo a badly driven piece.
3. **Finish**: for a zone or a no-go, once the outline is closed ("Outline closed: you can finish", at least 4 m and
   back within 1 m of the start; otherwise "Bring the robot back to the starting point (2.4 m left, 1.0 m max)"); for a
   passage, after at least 1 m.
4. The map frames the result, drawn dashed, and the bottom panel shows it: **Save** or **Back** (to the recording);
   the bin of the header cancels it.

| Shape | What is kept | Checks |
|---|---|---|
| **Zone** | the centre trace **grown by half the chassis width** (25.7 cm), like a Mammotion boundary which describes the outer side of the chassis: the chassis really went everywhere in it | crossing trace refused ("The trace crosses itself: record it again without crossing"); outline that stays in the existing zones refused |
| **Passage** | the centre trace (axis), simplified to 5 cm; the robot travels on it like on a Mammotion passage (axis ± half-width + 0.45 m), blades stopped, never mowed | ends that are not inside a zone ("1 end(s) inside a zone out of 2…") and crossing of a no-go zone are flagged |
| **No-go** | the centre trace **shrunk by half the chassis width**: the robot drove around the obstacle | loop too tight refused |

For a zone, when the outline overlaps an existing zone, two choices are offered:

- **New DumbyLuba zone** (with a **Zone name**, "DL zone 1" by default): only the part outside the existing zones is
  kept, so that no surface is mowed twice. It is refused if the zones cut it into several pieces.
- **Enlarge** *zone*: the most overlapped zone (Mammotion or DumbyLuba) grows by the outline (saved as an edit,
  "driven enlargement").

The result gives the area, the part **beyond the Mammotion boundaries** ("12.4 m² beyond the Mammotion boundaries: this
surface only exists on this phone (hatched on the map), the robot and the Mammotion app do not know it. Mowed under your
responsibility."), and warns when a new zone is **not connected** to the others: "This zone is not connected to the
others: record a passage from an existing zone, otherwise the robot cannot reach it." Straight joins of more than
1.5 m without FIX are flagged too.

After **Save**: "Saved: DumbyLuba zone Verger · 41.0 m². It is on the map and in Map → Correct the map → Corrections
(disable, delete)." Leaving the page keeps the recording in progress; it ends only with **Save** or **Cancel**.

## Mammotion task

The page's **Mammotion task** group offers **Stop the Mammotion task** to interrupt a task started from the official
app: only if a Mammotion task is observed (otherwise "No active or paused Mammotion task observed"), it cancels a
Mammotion return in progress, pauses and cancels the task, stops the blades, sends zero speed, then waits up to 15 s
for the ready state with blades OFF ("Task stopped: READY and blades OFF received"; otherwise "Cancellation not
confirmed: check the state in Mammotion; no local resume"). Requires a fresh state and no route, test or manual
control running.
