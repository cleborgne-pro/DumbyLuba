# Obstacles encountered and difficulties heatmap — 2026-10-13

Every difficulty met while the robot moves autonomously is recorded with its position on the
map, shown in layers and taken into account by later routes. Code: `difficulty/` (model,
heatmap, presumed obstacles, storage), `map/DifficultyLayers.kt` (layers, detail card), `map/ObstacleSheet.kt`
(exclusion size), `ui/DifficultiesPanel.kt` (Service → Garden and history → Obstacles), recording in
`ble/RobotBle.kt` (`recordDifficulty`). User guide: [obstacles](features/obstacles.md#encountered-obstacles-and-difficulties).

## What is recorded

Only during autonomous movement: local route (mowing, survey, station route, return), leg
delegated to the gateway, go-to-point, native mowing. Never under manual control nor during a test.

| Type | Weight | Source |
|---|---:|---|
| Bumper contact | 1.0 | **as soon as the contact code is received** (`recordContactOnReceipt`, since 0.43.1: estimated center in front of the bumper, heading), whatever happens next; then refined under the same card by the bypass (`avoidObstacle`: stopped position, side) or by navigation (`captureLocalIncident`) |
| Blockage | 1.0 | stop "Presumed blockage", "Wheels commanded without progress", "Station exit blocked"; robot stuck/trapped/wheel stuck/wheel motor blocked codes (1008, 1028, 1201, 1214, 1412, 1421, 11112, 11113, 11122, 2000701, 6001501, 6001601) |
| Slip | 0.6 | measured progress below 55 % of the robot's usual progress, above the stop threshold (25 %): flagged without stopping (`LiftGuard.slipping`); slip codes (1218, 11132, 11134) |
| Lift | 0.8 | "Safety sensor changed", lift or tilt codes (1004, 1030, 2502, 2801, 11131, 11133) |
| No progress | 0.7 | "No progress for 15 s", "Range too long" |
| Route deviation | 0.4 | "Route deviation > 30 cm", drift out of the safe zone |

Stops are classified by their reason (`DifficultyClassifier.fromReason`), errors by their code
(`fromErrorCode`). The same type within 2 m and 30 s is recorded only once (the stop and the error that
caused it). A contact recorded on receipt and then refined by the bypass remains a single contact
(`DifficultyLog.refine`: same place within 2 m, same contact time, same identifier, hence the same card and
the same photo). Position: the estimated center of the object for a contact, otherwise the robot's last position.

Before 0.43.1, a contact was only recorded at the end of a successful bypass. On Oct 15 at 11:00, the post
that was hit (mission stopped without back-off) appeared nowhere: no contact, no presumed obstacle, so nothing to
save. The same was true of any mission stopped by the contact: 8 bypasses reached, 5th contact at the
same place, unknown position or bypass impossible.

**Slip**: the raw speed calibration is approximate (a robot at 50-60 % of nominal speed
is normal). The threshold is therefore relative: median of the last 40 windows of 2.5 s at constant command
(at least 6). Heavy slip below 25 % remains a stop (lift or blockage).

Storage: `filesDir/difficulties.json` (at most 2,000 events, atomic write, unreadable entry
ignored). `difficulty` event in the log. **Dismissing** a contact (object removed, false contact) removes it from
the heatmap and from presumed obstacles without deleting it; **Clear log** deletes everything (photos and
saved obstacles are kept).

## Heatmap

Each non-dismissed event contributes around its point: weight × e^(−d²/2σ²), σ = 0.6 m, range 1.8 m,
halved every 60 days (ignored after one year). 1 m cells: score at the center, or the highest
of the events located in the cell. A recent contact or blockage is worth ≈ 1 at its point.

## Use in routes

1. **Presumed obstacles** (option "Avoid obstacles hit several times", on by default):
   two or more contacts less than 75 cm apart, at least 10 min apart (distinct
   movements), within the last 180 days, outside saved obstacles (group center farther than the saved obstacle's
   radius + margin + 0.3 m); or one contact **less than 4 h old**
   ("Recently hit obstacle", 0.39.1: resuming an interrupted mowing went straight back to the object that was hit). An "Other" obstacle at the center of the
   contacts, radius = spread + 10 cm (0.2 to 1 m), margin 0.3 m. They enter the preparation (coverage
   passes, links, mission navigation, physical geometry) like a saved obstacle
   (`RobotUi.planningObstacles`), are checked during tracking (if they were not in the prepared
   route, they trigger a bypass) and are sent to the gateway. They are not saved:
   **Save** turns one into an editable local obstacle.
2. **Difficult areas avoided when moving** (option "Bypass difficult areas when moving",
   on by default): cells with score ≥ 0.8, widened by 25 cm, given to the mission navigation
   (`TrajectoryPlanner.avoidAreas`). Movements (departure, return to base, recharge, navigation
   links) first look for a path in the comfort area minus these zones, kept if its
   length does not exceed 1.5 × the usual path + 4 m; otherwise the usual path is used. It is never an
   exclusion: safe area, envelope, passages and base zone are unchanged, and mowing still
   covers these zones.
3. **Bypass side**: the cost of a tight detour around a newly found obstacle adds 2 m per
   meter traveled and per score point ([bypass](OBSTACLE_BYPASS.md#tight-detour-and-side-choice-since-0350)).

## Map layers

In the layer bar, two toggles independent of the displayed signal, kept between launches:

- **Obstacles** (shown by default): red dot per contact (circled in white if it has a photo),
  presumed obstacles as red dotted lines. Tapping a contact opens its card: photo taken after the
  back-off (matched by contact time, otherwise the closest within 90 s), date, origin, code, estimated position,
  bypass side, contacts at the same place, **Dismiss** or **Save the obstacle**. Before
  saving, the card sets the **size of the exclusion zone**: type (a pole suggests a 0.10 m radius,
  a tree 0.30 m…), **Object radius** (0.05 to 2 m) and **Margin around the object** (0 to 2 m), in 5 cm steps,
  shortcuts **Margin 0.3 m / 0.5 m / 1.0 m**; the **Excluded zone** line gives radius + margin and the minimum
  distance of the robot's center (+ 0.43 m). Tapping inside the dotted circle of a presumed obstacle opens its
  contact; the saved obstacle then takes the center and radius of the contact group. Tapping a **saved obstacle**
  opens the same size editor, with **Save** and **Delete** (then **Confirm deletion**).
- **Difficulties**: yellow → red heatmap, dots colored by type for the other difficulties, presumed
  obstacles.

## Service → Obstacles

The **Encountered obstacles and difficulties** group lists the counters per type (non-dismissed events), the
**Presumed obstacles** with a **Save** button each (saved as "Encountered obstacle (…)"), the 15 most recent
**Encountered obstacles** (photo, date, origin, position, bypass side; a tap opens the card) and **Clear the
difficulty log** (confirmation dialog). The **Use in routes** group holds the two options above, the **Map layers**
group the two layer toggles.

## Automated validation

- `DifficultiesTest`: classification of reasons and codes, decay and kernel, dismissed contact, presumed
  obstacles (distinct movements, rebounds, obstacle already saved, dismissed contact, distant contacts),
  log (duplicates, JSON, unknown entry), **movement that goes around a difficult area** (and the usual
  path when a difficult band blocks the whole width), **bypass side opposite to known
  difficulties**.
- `LiftGuardTest.slipIsFlaggedAgainstTheUsualProgressWithoutStopping`: a slow but steady robot is never
  flagged as slipping; a drop to 40 % of the usual progress is flagged without stopping.
- `DifficultyLayersTest`: photo matched to the contact, tap opening the closest contact, dismissed contact
  ignored.

To be validated on the robot: classification of real stops, slip on wet grass or on slopes, and relevance
of presumed obstacles after a few mowing runs.
