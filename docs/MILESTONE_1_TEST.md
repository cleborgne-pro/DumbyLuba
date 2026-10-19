# Physical test — milestone 1

> [!NOTE]
> Procedure written for the first versions (0.1.x), still valid for a first connection with the robot stationary.
> The former "Debug" panel is now the **Service** tab: menu paths and labels below are those of the current app.
> For everyday use, a [gateway placed on the robot](features/gateway.md) holds the Bluetooth link; this test is done
> in direct Bluetooth next to the robot. Current behavior: [Bluetooth connection](features/bluetooth-connection.md),
> [safety](features/safety.md), [logs](features/logs-and-data.md).

## Goal

Check the UUIDs, the connection, the reports and their rate on **your LUBA 2 AWD 1000 and its firmware**. This milestone does not make the robot move. It does not validate stopping a moving robot, the motor watchdog or 30 minutes of manual mode.

## Preparation

1. In Mammotion, check that the cutting discs are OFF and the robot is stopped, in a clear area.
2. Note the BLE name, main firmware and Android version. Battery above 30 % recommended.
3. Close the Mammotion app to limit BLE conflicts. Do not remove its pairing.
4. Install the APK ([installation](../README.md#installation)). The phone can be attached near the robot, without obstructing the sensors, protections or the physical stop button.

## Connection and telemetry

1. Open "DumbyLuba", allow Bluetooth. Android 8–11: also allow and enable location. On this phone, Service →
   **Wi-Fi gateway** must be on the **Direct** role (*Direct Bluetooth*).
2. Service → **Bluetooth connection** → **Search for robots**, wait up to 15 s, then **Connect** on **your**
   `Luba-VS…` (by name/address) in **Detected robots**. The name alone does not distinguish a 1000 capacity from another capacity.
3. Check "BLE connected — telemetry received". A bare GATT connection without reports does not validate M1.
4. Compare battery and state with Mammotion after the test. Note the raw state and RTK codes. Missing fields stay unknown; data kept after disconnection is historical.
5. Leave the app in the foreground for 5 to 10 minutes without movement. Note report count, interruptions, MTU, RSSI, position rate and firmware if provided.
6. Press STOP (global STOP bar). In Service → **Logs**, check the `STOP linear=0 angular=0` request, then the GATT write confirmation or the explicit error. The link is then closed. This test does not prove a mechanical stop since the robot was stationary.
7. Export the log: Service → **Logs** → **Export all (ZIP)**. JSONL = one JSON object per line; includes Unix time, monotonic time, events, frames and telemetry.

## Acquisition stops (robot always stationary)

- Reconnect, then put the app in the background. *Changed since this procedure was written*: a foreground service
  now keeps the link in the background; only hold-to-run activities (joystick, tests, campaign) stop. STOP and
  disconnection only happen when that service does not hold the link ([details](BACKGROUND_LINK.md)). No automatic
  resume in either case.
- Reconnect, then turn off Android Bluetooth: BLE failure/loss expected, without claiming that the STOP was sent.
- If reports do not arrive, the adjustable timeout (Service → **Advanced settings** → **Telemetry validity delay**,
  10 s by default, 5–30 s, changeable with the robot disconnected) leads to FAILED/STOP/close. An ACK or heartbeat must not artificially extend telemetry.
- If the connection fails, export the log; do not repeat unlock, reset, motor test or pairing removal commands.

## Feedback required before milestone 2

```text
Phone / Android version:
LUBA name / main firmware:
BLE detection: yes/no
GATT connection / MTU:
First telemetry after: ... s
Battery consistent:
Raw state / self-check / sensors:
Raw RTK / positionType:
X/Y position and heading available:
Observed position rate: ... Hz
Connection duration:
Interruptions / errors:
STOP: write confirmed / error / observed delay
Background:
Bluetooth disabled:
Log exported:
```

M1 is physically validated if the connection and consistent reports remain available throughout the test, the zero STOP is written over a working link, and app losses/stops are detected without automatic resume. Partial compatibility or a firmware refusal leads to adapting the protocol within the same milestone, before any movement.

## Fundamental limit

After a crash, an Android kill or a radio loss, no phone software can guarantee that STOP is sent. The firmware's stop watchdog will have to be measured during a very short and slow movement test at M2, under supervision with access to the physical stop. No prolonged or autonomous test before this proof.

The logs contain the BLE address, positions and robot information: keep them as private data. No Mammotion account or credential is collected in this milestone.
