> Update of October 8: stationary test in manual mode with blades OFF, 1207 not validating, repeated errors deduplicated and presumed-blockage guard.

> Update of October 7: bits 0–2 report bumper health and do not prove a contact. The older tests below were corrected based on the analysis of the October 7 logs. The approach now requires a collision event detected during the stationary test on the same connection. A health alert stops the test without creating a proposal.

# Obstacle detection and handling tests

Service → Supervised tests → **Obstacle tests** offers four tabs, strictly with blades OFF and under on-site supervision (user guide: [obstacles](features/obstacles.md#service--obstacles-and-obstacle-tests)). The saved obstacles, the difficulties and the photos are in Service → Garden and history → **Obstacles**, without any robot command.

- **Contact** (stationary bumper): 30 s to press the front bumper. **Listen to the bumper · manual mode (30 s)** first puts the robot in manual mowing mode with blades OFF (state 20, confirmed within 5 s, renewed every 500 ms), where the LUBA 2 publishes the contact; **Listen in ready mode (30 s)** keeps the robot ready (11) and sends a zero speed every 200 ms. **I heard the beep / touched the bumper** timestamps the observation (`bumper_beep_observed`) without validating a contact. A received 1014 contact validates detection for the current connection; a 1207 fault cancels this validation.
- **Learn** (slow approach): **Slow approach until contact (1 m max)**, in manual mode with blades OFF (state 20, confirmed within 5 s, renewed every 500 ms: the LUBA 2 records no contact in ready mode), raw speed 80 (≈ 7.6 cm/s; at 20 the LUBA 2 did not move and the approach always ended as a presumed blockage), 30 s maximum, movement limited to 1 m around the start, fresh position required. When the robot halts by itself (collision lock 25, or no translation for 3 s), it is stopped and the error history read for 3 s: a 1014 gives **Contact detected**; without a code, the lock 25 also counts as a contact, a robot that had moved at least 5 cm gives **Stopped in front of an object** (obstacle proposed, bumper not validated), and a robot that never moved gives Interrupted with the reason. Since 2026-10-22 it no longer waits for the Contact tab: those limits stop it even if no contact is received, and a contact received during the approach validates detection for the connection. Use a soft obstacle and keep the physical stop within reach. **Obstacle in front of the robot** (Identify without contact) creates a proposal from the fresh pose (≤ 2 s) without any command, against the bumper by default, distance adjustable from 0 to 4 m. A bumper contact while driving with the joystick opens the obstacle sheet directly (see [obstacles](features/obstacles.md)).
- **Bypass**: real move around a saved obstacle (opposite side, then back), blades stopped, on a path computed by the local route planner (same reserves as transits; the straight line crosses the obstacle), 10 min at most; deviation, footprint outside the safe area or contact = STOP, and a contact is recorded as an incident.
- **Border**: frontal border test, see [obstacles](features/obstacles.md#frontal-border-test-0630).

Preconditions (Contact and Learn, shown live): LUBA 2 identified as `Luba-VS` or `Luba-VP`, no Bluetooth diagnostics, blade test or route running, map read, robot state ≤ 2 s old, battery > 30 %, robot ready (11), no protection (self-check 0 or 10), bumper without alert, blades stopped.

A fresh contact triggers STOP, flushes pending forward commands, produces CONTACT_DETECTED and ends the test without resuming. No contact gives NOT_DETECTED; loss of telemetry, a stale position, a protection, active blades, a bumper health alert, another robot error or the distance limit give ABORTED. Leaving the screen, putting the app in the background or pressing STOP also ends the test.

Reports contain the raw sensor, the position and the duration. These tests validate the received bumper signal and the STOP software reaction. They validate neither the mechanical stop, nor visual detection at a distance, nor an autonomous bypass.

## Obstacle learning (Teach)

On contact, the obstacle position is estimated by `TeachObstacle.estimateCenter`: RTK position of the contact, heading, LUBA 2 front bumper distance (0.345 m, half the length of the 690 × 513 mm chassis), then the estimated physical radius along the same axis. The heading is assumed to be in degrees, counter-clockwise around +x: this assumption still has to be checked against an observed movement (`MAMMOTION_PROTOCOL.md`). Without a fresh position and heading, no obstacle is proposed.

The proposal shows the type (swing leg, tree, post, hole, low branch, other), the estimated physical radius and the safety margin, both adjustable before saving. The estimated position is recomputed live when the radius changes.

## Local obstacles

Each obstacle stores: position, estimated physical radius, safety margin, type, label and incident history (bumper contact, rubbing, collision, near miss, observation), with the robot position when available. Stored locally in the app's private file (`obstacles.json`): **no Mammotion no-go zone is created**, neither on the robot nor in the cloud.

After creation, position (±5 m offset, 10 cm steps), radius (0.05–2 m) and margin (0–2 m, both in 5 cm steps) and type can be edited by hand (Service → Obstacles → **Edit**, or a tap on the obstacle on the map); incidents are added to the history (**Incident**, optional note up to 500 characters). At most 512 obstacles and 200 incidents per obstacle. Obstacles are drawn on the maps (physical circle, margin, turning envelope dotted).

## Robot volume in the planner

The planner receives each obstacle as a reserve disc for the robot reference point: physical radius + safety margin + LUBA rotation envelope (half-diagonal 0.43 m, the corners sweep it during turns). Coverage passes, borders and links respect this disc; the planner's general margin is added on top without double counting (the planning hole is reduced accordingly). JVM tests: `ObstaclePlannerTest`. Since October 11, an obstacle discovered during a route (contact or obstacle added) is bypassed by tracking: see [obstacle bypass](OBSTACLE_BYPASS.md).

## Additional diagnostics of October 8, after the 13:14 tests

The manual test renews manual mode with discs OFF every 500 ms, without a stream of zero speeds during the wait (that stream coincided with the return to READY after two seconds). STOP is still sent at the start and at the end. A `bumper_diagnostic` event every second records requested/observed mode, sensors, self-check, knife_status, RPM and state age; associated error and cutter requests. The beep button still timestamps the observation, without validating a contact. Compare the READY and manual tests, check that mode 20 is still observed at the time of the beep and look for a fresh 1014. Whether the mode is actually held and whether collisions are received still has to be checked physically.

### Signed contact confirmed in the logs

On October 8 at 13:48:50 and 13:48:59, `systemUpdateBuf` delivered two fresh `-1014` events during the manual stationary test. The code only recognized `+1014`, hence ABORTED instead of CONTACT_DETECTED. Both signs of 1014 now confirm the contact; both signs of 1207 remain a non-validating fault. The raw code and timestamp are still kept and the initial history remains a reference. No 1104 was observed in this extract.
