# Maintenance

| | |
|---|---|
| **Access** | Service → Robot → **Maintenance** |
| **Needs** | robot connected (gateway or direct Bluetooth); no Mammotion account |
| **Robot commands** | none: the counters are read, the blade change is noted on the phone only |

## Robot counters

The robot publishes its maintenance counters in its Bluetooth reports (`rpt_maintain`, subscription `RIT_MAINTAIN`,
requested since this version on every connection; before, only the Mammotion account read them). The page shows:

- **Distance traveled** (km) and **Working time** (h);
- **Battery cycles**;
- **Blade counter (robot)**: hours of blade rotation counted by the robot, with the alert threshold set in the
  Mammotion app if there is one (none on the LUBA 2 of 2026-10-20: 779 km, 1178 h, 426 cycles, 182 h of blades).

## Blade reminder

- **Blades replaced** (bottom button, confirmation): the robot blade counter read at that moment becomes the
  reference for this robot, with the date. Nothing is sent to the robot and its counter is not reset.
- **Change every**: interval in hours of blade rotation, 20 to 300 h in 10 h steps (100 h by default), common to the robots.
- **Used since the change**: hours since the reference, against the interval, with a progress bar; red once reached.
  If the robot counter becomes lower than the reference (reset in the Mammotion app), it counts from that reset.
- Without a noted change, the Mammotion threshold is used if one is set, otherwise no reminder: the page asks to tap
  **Blades replaced** at the next change.

Once the interval is reached, the **Blades to replace** alert (warning, [local alerts](notifications.md)) is raised
once per connection while it stays due, with the hours used and the interval; tapping **Blades replaced** after the
change clears it.

Code: `maintenance/BladeMaintenance.kt` (wear, settings), `ui/MaintenancePage.kt`, `protocol/Telemetry.kt`
(`maintenance`). Tests: `BladeWearTest`, `MaintenanceTelemetryTest`.
