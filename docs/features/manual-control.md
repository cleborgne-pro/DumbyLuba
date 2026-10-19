# Manual control

Two on-screen joysticks to drive the robot, with no map and no RTK. The blades stay stopped. With the
[gateway](gateway.md) mounted on the robot, control works **remotely over Wi-Fi**, and the robot's
[camera](camera.md) is shown large above the joysticks.

| | |
|---|---|
| **Access** | Service → Robot → **Manual control**; Map → **Manual**; Home → gamepad icon (same page everywhere) |
| **Status** | Implemented and used on the real robot; bumper reception and stopping after a real BLE loss still to be validated |
| **Code** | `manual/Joystick.kt`, `manual/JoystickPanel.kt`, `manual/ManualSpeed.kt`, `ui/RobotPanel.kt` (`ManualPage`), `ble/RobotBle.kt` (`manualInput`, `stopManual`, `stopMammotionTask`) |

<p align="center">
  <img src="../images/manual-control.png" width="200" alt="Manual control">
</p>

## Usage

1. Tick **I am supervising the robot**.
2. Set the **Maximum speed** (10 to 100 %, 30 % by default; the label gives the estimated speed in m/s). The last
   value is kept from one opening and one launch to the next.
3. Left joystick **Forward / reverse**, right joystick **Turn**. Both can be used at the same time; releasing an
   axis returns it to zero. The status reads "Control active — release to stop".
4. Releasing both joysticks, **Stop the robot**, cancelling or leaving the page sends STOP and purges pending
   movements.

The checkbox and the speed are locked while a joystick is held.

## Settings and commands

| Item | Value |
|---|---|
| Command rate | 5 Hz (every 200 ms) |
| Dead zone | 12 % |
| 100 % | 1000 units forward, 450 in rotation (≈ 1 m/s, ≈ 45°/s; speeds above 0.5 m/s estimated, not measured) |
| Axes | independent: turning does not reduce the forward speed |
| Blades | always stopped |

## Remote control with the camera

1. Gateway in place and phone in *Control via the gateway* mode ([gateway](gateway.md)).
2. Open manual control: the live view is shown large above the joysticks and the stream is requested while the page
   is open (Mammotion account connected, robot not on 4G; otherwise the view gives the reason). The view can be
   hidden; it reconnects on its own ([camera](camera.md#live-view-on-the-map-and-in-manual-control-reconnection)).
3. Drive while watching the view.

If the pilot phone goes silent for more than 2 s (Wi-Fi down, app frozen), the gateway stops the robot. The video
arrives with a slight delay: keep the speed moderate.

## Guards

- LUBA 2 (`Luba-VS…`) ready (state 11) to start; state 11 or 20 accepted while driving. Fresh state (≤ 2 s),
  battery > 30 %, blades stopped, no protection (self-check 0 or 10), no bumper or lift alert, no mission, test, map
  reading, height adjustment or Mammotion task cancellation in progress. A refusal reads "Control refused: …".
- A joystick input not refreshed for 500 ms, a protection, a bumper or lift alert, a robot error, a stale state, a
  link loss or a saturated send queue (more than 4 commands) stops control with a critical alert; it only resumes
  with a new press.
- Joystick movements feed the [lift detection](safety.md#lift) and the signal surveys.
- Going to the background and the global STOP use the usual stop ([safety](safety.md)).

## Mammotion task

The page's **Mammotion task** group offers **Stop the Mammotion task** to interrupt a task started from the official
app: only if a Mammotion task is observed (otherwise "No active or paused Mammotion task observed"), it cancels a
Mammotion return in progress, pauses and cancels the task, stops the blades, sends zero speed, then waits up to 15 s
for the ready state with blades OFF ("Task stopped: READY and blades OFF received"; otherwise "Cancellation not
confirmed: check the state in Mammotion; no local resume"). Requires a fresh state and no route, test or manual
control running.
