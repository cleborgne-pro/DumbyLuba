# Command cutoff — demonstrating the motor watchdog

Service → Supervised tests → **Command cutoff**.

## Why

The app computes the route and sends every speed command itself (`DrvMotionCtrl`, 5 Hz). If it freezes or crashes in the middle of mowing, no command reaches the robot any more, not even STOP. Safety then depends on the firmware: does it stop the wheels on its own when commands stop? The protocol does not document it (`MAMMOTION_PROTOCOL.md`: "stop delay after commands cease", to be measured). This test measures it on **your** robot and **your** firmware.

## Procedure

1. Preconditions (explicit refusal otherwise): LUBA 2 (`Luba-VS…` or X series `Luba-VP…`) ready (state 11), state younger than 2 s, battery > 30 %, no protection, bumper without alert, blades confirmed OFF, no other mission or test, fresh RTK FIX position, robot map read, **2.5 m clear in front of the robot**: in the same zone, more than half a width + 10 cm from the edge, outside no-go zones and local obstacles.
2. Tick **I am supervising on site, 2.5 m clear in front of the robot**, then hold **Hold for the test (≈ 10 s)**. **Forward**: **Forward speed** slider, raw speed 10–120 units in steps of 10, 30 by default, straight line, blades OFF, at least 3 s and ≥ 10 cm observed (otherwise failure after 6 s).
3. **Silence**: no more motion commands at all (neither speed nor STOP). Synchronization and the report subscription continue; positions are requested every 200 ms (published at 1 Hz by the LUBA 2).
4. **Verdict**:
   - *Stopped on its own*: three consecutive distinct positions at most 3 cm apart. Reported delay = upper bound (start of immobility minus the time the last command was queued). **Watchdog demonstrated** if this bound is ≤ 2.5 s.
   - *Did not stop on its own*: the robot moves more than 1 m from its last moving position, or is not confirmed stationary 6 s after the last command. The app then sends STOP.
   - *Inconclusive*: not enough positions, protection, link loss, button released, screen left or app sent to the background.
5. Final STOP, always (no effect if the robot has already stopped).

Releasing the button at any time sends STOP immediately; the test becomes inconclusive.

## Stored result

Per robot (Bluetooth address): date, firmware, speed, verdict, delay bound, distance, observed speed. An inconclusive test does not erase a previous conclusive result. The result is shown:

- on the page (**Last result**: "Motor watchdog demonstrated", "Stops on its own, but too slowly", "Does not stop on its own: app STOP required" or "Inconclusive test", with date, firmware, commanded speed, stop bound, distance and observed speed);
- in the Service summary ("demonstrated", "not demonstrated", "to test", or "in progress");
- in the route preview before **Start** ("Robot self-stop not demonstrated (Service → Command cutoff): supervision required.") and on the **Mowing interrupted** and **Move to a point interrupted** panels ("Stay nearby during the resume."), as long as it is not demonstrated.

It does not prevent mowing: all mowing remains supervised.

## Limitations

- Positions at 1 Hz: the delay is only known to within a second. A bound of 0.8 s means "stopped at most 0.8 s after the last command", not a measured reaction time.
- The test simulates a **frozen app with the link still up**. A complete loss of Bluetooth (phone switched off, out of range, process killed by Android) is not covered: the firmware may react differently. Observe it separately, with a physical stop within reach.
- A single test at one speed does not hold for all speeds, nor for another firmware: repeat the test after a robot update.
- Manual mode (state 20), used during mowing, is not tested here: the test starts from the ready state (11), like manual control.

## Verification

`CommandSilenceProbeTest` (JVM) covers: stopping on its own and the upper bound, a single repeated position being insufficient, timeout without stopping, excessive drift, missing positions, slow stop not demonstrated, forward command without movement, clearance in front of the robot, corrupted result ignored. Physical validation still has to be done on site, under supervision.
