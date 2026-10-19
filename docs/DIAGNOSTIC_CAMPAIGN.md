# Diagnostic campaign — a single visit to the LUBA

Diagnostics version included in `0.1.2-m1-blade-tests`; today in Service → Diagnostics → **Bluetooth diagnostics** (user guide: [tests and diagnostics](features/tests-and-diagnostics.md#bluetooth-diagnostics)). These tests remain at milestone 1: **robot stationary, blades OFF, no non-zero speed command**. They do not validate 30 minutes of manual control while moving.

## To do once on site

1. In Mammotion, check that the blades are OFF and the robot is stationary in a clear area, then close Mammotion.
2. Open the POC, detect and connect your LUBA. Wait for a robot state with a known battery level and sensors showing blades OFF. The battery must be above 30 %.
3. In Service → Diagnostics → **Bluetooth diagnostics**, choose the **Final monitoring**: 30 min by default (or 1, 5, 10, 60 min / **Unlimited**). Leave all cases ticked for the first full campaign (*30 s of zero commands at 5 Hz*, *3 STOP / reconnection cycles*, *Hide reports (monitoring)*, *Local error → STOP → reconnection*).
4. Tick **Robot stationary, blades stopped: I am supervising**, then **Run diagnostics**.
5. Leave the phone in the foreground on the robot. The app keeps the screen on during the campaign. A locked screen, leaving the app, a user stop or a failure cancel the rest.
6. Come back at the end: detailed result on screen, then **Export (ZIP)** (or Service → Logs → **Export all (ZIP)**). The chosen duration is that of the final monitoring: allow about 2–3 extra minutes for the preliminary diagnostics, depending on connections and the chosen timeout.

An unexpected failure stops the campaign: the app does not attempt a reconnection to hide an interruption. You can read all results obtained and export the logs, without rerunning each case manually. A case that could not run stays in `plannedCases`, without a PASS result.

## Automatically chained cases

| Case | What it measures | Success condition |
|---|---|---|
| Local self-checks (9) | Protobuf STOP, MTU/fragmentation, CRC, encryption, sequence, incomplete fragment, watchdog, empty report, position/heading scales | PASS LOCAL; no BLE |
| Initial telemetry 30 s | Continuous reception, fresh robot state, battery and blades | New reports; fresh state; no failure |
| Zeros at 5 Hz, 30 s (optional) | Rate of real commands without motion, delays and GATT queue | Zero commands written; queue drained; telemetry maintained |
| STOP / reconnection ×3 (optional) | Zero STOP, close, rediscovery of services/MTU/CCCD, reports coming back | STOP acknowledged by GATT before each reconnection; telemetry restored and 5 s of observation |
| Local silence (optional) | App path of the telemetry watchdog | Reports deliberately masked after decoding; expected timeout; GATT STOP confirmed; planned reconnection successful |
| Local exception (optional) | Stop path on an exception in the app callback | Deliberate local exception; GATT STOP confirmed; planned reconnection successful |
| Long monitoring | BLE stability, report/position rate, battery, raw quality, phone | No unexpected interruption during the chosen duration |
| Final STOP | Writing the zero protobuf and closing | Last STOP fragment confirmed by GATT |

The silence test keeps the raw radio notifications in the logs but no longer passes them to the telemetry reducer. It is **not** a real radio failure. The exception test sends no corrupted frame to the robot. The CRC/encryption/sequence/invalid fragment tests are local only. No reset, unlocking, bond removal, blade activation or bypass of any safety.

Repeated commands use the real `DrvMotionCtrl` protobuf with zero speeds, at 5 Hz like the upstream joystick worker. Their GATT success proves neither that manual mode is acquired or maintained, nor that the firmware executes a motor command. Any protobuf ACKs received are decoded and logged, without inventing their presence or their correlation.

## Monitors running simultaneously

During each connection:

- All RX/TX frames, BluFi sequences, decoded protobufs, attribute/type/sender/receiver, protobuf sequence and robot timestamp.
- Connection/disconnection and Android codes, inventory of services/characteristics/descriptors, properties, negotiated MTU and CCCD result.
- Queued commands and completed commands; distinction between logical commands / GATT fragments, count of zeros requested and completed.
- Wait time in the queue, duration of writes and RSSI reads, GATT STOP confirmation and latency since the request.
- Robot battery, state, sensors, self-check, X/Y, heading, raw RTK, position type/quality, state changes.
- Report and position intervals: mean, p50, p95, maximum; real reception counter. Bounded histograms: percentiles saturated at 10,000 ms, exact maximum.
- Changes of position type and RTK status (without translating these unknown codes into "RTK losses"), maximum position drift relative to the start of each stationary connection.
- Every 5 s: age of telemetry/state/position, RSSI, phone battery/temperature/power supply, Android thermal state if available, heap memory, disk space and maximum interval of the monitoring loop.
- Campaign, case, app run and connection identifiers to correlate events.

Passive measurements run simultaneously; GATT operations are serialized and only one campaign can be active. The app does not run several tests disturbing the same robot concurrently.

## Files and recovery after closing

Private storage: `files/diagnostics/<appRunId>/`.

- `0000.jsonl`, `0001.jsonl`, etc.: structured JSON events, rotated after about 8 MiB per file.
- `campaign-<id>.json`: configuration/plan, local and BLE results, current state, metrics and summary. Report replaced atomically with disk synchronization; updated at each case and every 5 s during connections.
- ZIP: all kept log folders, reports and migrated old M1 logs. JSONL only: events of the current run.

After a relaunch, a report still RUNNING from a previous process becomes `INTERRUPTED_PROCESS_END_UNCONFIRMED`. This marker proves neither a specific crash nor that a STOP was sent. No test resumes automatically. Writes may be lost on a hard kill; the report represents the last persisted state.

Log queue limited to 4,096 writes, cap of about 256 MiB of new events per run, and stop if less than 32 MiB of space is available. A logging failure forbids/interrupts the campaign. No automatic cleanup of historical logs; export them regularly and use the Android settings if you want to clear the app's data.

## Stop criteria and interpretation

Global STOP always available. Battery ≤ 30 %, stale/unknown robot state, blades not confirmed OFF, BLE loss, unexpected timeout/exception, GATT saturation or disk failure: FAILED and STOP requested if possible. User STOP / leaving the foreground: ABORTED.

Planned simulated interruptions are identified by their cases and are not counted as unexpected failures. A planned reconnection requires the previous STOP to have been confirmed by GATT. Otherwise the campaign fails and the following cases are not run.

`PASS BLE ONLY` indicates that the criteria of this stationary campaign were met. A mechanical stop, the motor watchdog after a crash/real radio loss, conflicts with the Mammotion app, behavior with the screen off and physical safeties are not validated by these simulations.

## Additional physical checks, during the same visit

After the campaign, if you want to enrich the logs: reconnect and deliberately send the app to the background; reconnect and turn off Android Bluetooth. These actions must stop acquisition without automatic resume. They are not part of the automatic sequence, because the app cannot bring itself back to the foreground or silently re-enable the radio.

Send back the ZIP with the LUBA's name/firmware and any unusual behavior observed. No Mammotion account/token is requested. The exports contain the BLE address and positions: keep them as private data.

The height/rotation tests requested afterwards are separate, in Service → Robot → **Cutting height** and Service → Supervised tests → **Blade test**: see [BLADE_TESTS.md](BLADE_TESTS.md). They are never added to the automatic plan. The ZIP also includes their supervised reports.

## Fix 0.1.3 — robot already paired

The search now lists LUBAs already paired with Android, even without a new radio advertisement. Their RSSI stays unknown until a real measurement. BLE advertisements and paired device names are logged to diagnose the detection filters. If another app holds the connection, close it before connecting the POC.

Observation on the phone: LUBA present among the paired devices, active LE bond, several POC scans with no result kept. This does not demonstrate the exact radio cause. Real GATT connection to be rechecked in the field.
