# Painted zone and border following

- **Painted zone**: real mowing, blades running, limited to a temporary zone painted on the map. Since 2026-11-01 it is
  chosen in Map → **Mow**, at the zone choice (**Paint a zone**), and then runs as the usual mowing task (settings,
  route preview, simulation, start, resume); it was before the Service → **Trial mowing** page, which now only hosts
  the [finishing of the gaps](features/mowing-history.md#finishing-of-the-gaps) of a mowing (Service → **Finishing the gaps**,
  opened from the mowing report).
- **Border following** (Service → Supervised tests) (its own entry since 0.54.0, after the navigation test): a single border followed at a
  chosen offset, blades stopped by default (see below). Before 0.38.1, it was a second mode of debug mowing, behind a
  selector at the top of the page.

The border following and finishing pages follow the order of the test pages: safety warning, tracking, map, settings with the supervision box **I
am supervising on site, area cleared** last, then the buttons. An interrupted run is resumed from the map ("Interrupted
(STOP, link, app closed): resume from the point reached, from the map.", see
[resume](features/mowing-resume.md)); starting replaces a task saved for resume (the page says so).

## Principle

- Map → **Mow** → **Paint a zone** (instead of **Whole zones**; mowing only, the survey routes keep whole zones).
- **Paint / Erase / Move**: one finger paints (or erases) on the map itself, two fingers pan, zoom and rotate it.
  "Move" disables the brush (a tap then selects nothing). **Brush size**: Ø 0.6 to 4 m (Ø 1.2 m by default). **Undo**
  (last stroke) and **Clear all**; the panel shows the retained area ("33.2 m² outside no-go zones · 1 painted zone").
  Code: `map/ZonePaint.kt` (state, tools, drawing), `map/MapGestures.kt` (one-finger stroke), `map/MapPanels.kt`
  (`ZoneChoicePanel`).
- Only paint **inside existing zones** is kept: it only appears there, and the
  kept area is outlined in orange. No-go zones are excluded from the orange outline and from the displayed area
  ("m² outside no-go"); the planner goes around them (lap of the enclosed no-go zone), as well as learned obstacles and the
  surroundings of the base, as for mowing whole zones.
- The kept area is split into simple outlines (a painted ring gives several pieces; the hole is never
  filled). Fringes narrower than 20 cm and pieces smaller than 0.5 m² are ignored.
- The painted zone is kept for the session (so you can close the zone choice and come back), for the map that was read.

## Route

The painting is a zone choice like any other (`PaintedZone.area`, `ZonePaintState`):

1. the pieces become temporary zones ("Painted zone 1"…, negative identifiers, ordered from the robot or the base)
   added to a **copy** of the map, the planning map of the task: they are the zone selection, so the task settings
   (mode, direction, heights, borders…), the route preview, the start point (a chosen start is not saved for a
   temporary zone) and the simulation apply as for recorded zones;
2. preparation (`MowingSimulation`) and BLE control (`startRobotRoute`) use the **unchanged robot
   map** and the same navigation (`RoutePlanning.mission`): same driving areas, margins, base safety zone and route
   checks, with the temporary zones as continuous-blade zones (`PaintedZone.bladeZones`), kept by the checkpoint for
   the resume ("Partial mowing interrupted");
3. start from the robot's position (or from the base if unknown), then return to base.

`PaintedZone.prepare` (fixed settings) still prepares the finishing of the gaps (`FinishingPass`).

### Blades kept running within a zone

Blades no longer stop at every link (`BladeContinuity`, applied to all local mowing, including from the
map): a link between two passes of the same zone (border → zigzag, pass → pass) keeps the blades
running if it stays entirely inside that zone (5 cm tolerance). Blades are stopped only to reach
the first pass, change zone (including from one temporary painted zone to another), cross another
zone, return to the base (or recharge). Signal survey: blades always stopped.

Reference map: a single blades-on range (no stop) for Jardin d'Eden, Aire de jeux,
Piscine, Fosse and Potager; Figuier and Enfer keep one or two stops for links that go through the
neighboring Piscine.

**Settings**: those of the mowing task (Map → Mow → **Next**).

The only difference from mowing zones: minimum required coverage of 80 % instead of 95 %. A small
painted area has proportionally more rounded edges out of reach of the cut (offset of the outer
lap). The planned coverage is shown after the computation.

### Enclosed no-go zones and learned obstacles

The lap of an enclosed no-go zone (8 cm from the recorded outline) goes around the outside of a learned obstacle that touches it,
with the same reserve as outer laps (`CoveragePlanner`). If this lap remains impassable for the chassis, or
if the first lap leaves the zone (paint tight against the no-go zone), it is dropped (reported after the computation): the zigzags keep the reserve around the no-go zone and
coverage is still checked. Additional no-go laps explicitly requested that would leave the zone remain
refused ("No-go border too close to the outer boundary"). Previously, this case refused the whole computation ("Chassis collision during the turn…"),
also when mowing a whole zone with a learned obstacle stuck to a no-go zone.

## During mowing

- **STOP · stop the robot and the blades** at the bottom of the page, also available from the map ("Mowing
  continues if you leave this page; STOP remains available here and on the map.").
- Mowing continues if you leave the page, like mowing started from the map.
- The protections of real BLE tracking apply: obstacle, bumper contact, route deviation, unexpected robot
  state, battery, stale telemetry.

## Border following

To check in the field the distance actually kept along an outline (`BorderFollow`,
`BorderDebugPanel`).

- **Selection**: a tap on the map selects the closest border (at most 1.5 m; no-go zone preferred on a
  tie), highlighted in orange; **Choose from the list** (then **Change border**) offers every zone boundary and every
  no-go zone (numbered, attached to their zone).
- **Offset between the robot and the outline** from 0 to 100 cm (1 cm steps): the recorded outline is the outer side
  of the robot, whose center runs half a width (25.7 cm) further. Zone boundary: lap on the inside; no-go zone:
  lap on the outside. Other no-go zones (at the same offset), learned obstacles (chassis pivot included) and the base
  safety zone are bypassed. 0 cm leaves 1 cm between the side of the robot and the outline.
  A learned obstacle near the docking corridor has its margin adjusted as for any mission
  ([obstacles near the base](features/obstacles.md#obstacles-near-the-base)).
- **Chassis**: each loop must pass the mission navigation checks (center area, LUBA
  envelope, base). Otherwise corners are rounded (radius ½, 1 then 1.5 robot lengths), then the offset is increased in
  2 cm steps, up to +20 cm. The applied offset, the rounding and the planned deviation from the outline (min, median) are
  shown; beyond that, the computation is refused.
- **Blades**: **Blades running** switch, **off by default** ("Following only, blades stopped along the whole route").
  On: "The loop mows the border; links and return with the blades stopped", at the **Cutting height** shown below
  it. **Travel speed** 10 to 100 % (shared setting). The start button reads **Follow · blades OFF** or **Mow · X mm**;
  STOP reads **STOP · stop the robot** without blades.
- **Route**: entry into the loop at the reachable point closest to the robot, lap, links
  and return to base with blades stopped (`MowingSimulation`, same preparation and same BLE control as mowing).
- **Field measurement**: during the lap (position within 35 cm of the planned route), current, minimum and median
  deviation from the recorded outline, and deviation from the planned route (median, max). The measurement is kept for the session.
- **Use X cm for mowing** (zone boundary): carries the chosen offset over to the mowing's **Outer margin**
  (5 cm minimum, "Mowing uses the offset chosen here (5 cm minimum).").
- **Validated borders** (`BorderValidation`, `BorderValidationStore`, preferences `border-validations`): after a
  lap, **Validate this border at X cm** stores, per outline (map identifier), the chosen offset, the applied
  offset, the date and the measured deviation (min, median) if any. A fingerprint of the outline rounded to the centimeter
  goes with the validation: a redrawn outline goes back to "to be revalidated". On the service map, each border is
  drawn in dark green (validated) or red (to be validated), the selected border on an orange halo; the list shows
  the state and offset of each, and the "Followed border" group the number of validated borders. Selecting a validated
  border restores its offset, and **Reuse the validated offset (X cm)** brings it back after a change; **Remove the validation** clears it. An outline changed since the
  loop cannot be validated ("Outline changed since this loop: run the loop again before validating it.").

## Tests

`BorderValidationTest`: offset stored per outline, redrawn outline to be revalidated, sub-centimeter noise ignored,
storage read back (unreadable lines ignored).

`BorderFollowTest` (reference map): selection and labels, tap on the map, Aire de jeux boundary at 10 and
40 cm (median deviation = applied offset, never closer), lap of a no-go zone at 20 cm, blades running on the lap
only, no edge inside a no-go zone, entry as close as possible to the start, out-of-range offset refused; real station and
obstacles of Oct 14 (one 82 cm from the approach point): margin reduced from 30 to 17 cm, physical radius kept, computation and
start accepted from the base and from the garden; object placed on the approach point: refusal naming the obstacle.

`PaintedZoneTest` (reference map): paint outside zones ignored, clipping to zones, eraser and holes,
temporary zones ordered from the start, task planned on them and prepared on the unchanged robot map with them as
continuous-blade zones, validated plan, cutting passes inside the paint; `MapPaintedZoneTest` (map screen): Mow →
Paint a zone → Next → Route preview → Start, route on the robot map; case of the capture
of Oct 10 (Aire de jeux, enclosed no-go zones, learned obstacle stuck to the no-go zone, real base and robot pose), area outside
no-go zones. `ObstaclePlannerTest`: no-go lap around a stuck obstacle, no-go zone too close to the boundary.
