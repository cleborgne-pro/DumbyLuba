# Interface inspired by the Mammotion app — 0.21.0

Design history of the interface. The current user view of each screen is in
[features/interface.md](features/interface.md); the sections below are dated, and later sections replace earlier
ones where they differ.

Reference: Mammotion app v1.x as shown in the screenshots of the LUBA 2 AWD V6.0 manual
(2024.09). The colors are loosely inspired by it; they are approximations, not an official
style guide, and no Mammotion graphic element (logo, icon, image) is reused. The 2026 redesign of the app (iOS) is not documented by screenshots and was not
used as a model.

## Navigation

- **Device / Service / Me** tabs.
- Device: bell (notifications), link status pill (Bluetooth, gateway, account, offline), gear, large rendering
  of the robot (a tap opens the map), name and "Ready" state, battery (with the return or resume threshold) and
  positioning Fix/Float/Single under the name, white "My map" card with a **Start mowing** pill and a manual control
  button (the earlier Map / Manual / Device shortcut tiles are gone).
- The map and manual control are pages pushed from Device: back arrow,
  tab bar hidden, system back handled. The STOP banner stays visible as soon as a link
  or a test is active.
- Me groups the Mammotion account, appearance, notifications and version.

## Showcase home screen (0.23.0)

- Photo of the detected model (`ui/RobotModel.kt`, `drawable/robot_photo_*.webp`, since 0.33.0; vector illustration
  `drawable/robot_illustration.xml` if the model is unknown): radial halo, elliptical ground shadow, fade-in. A tap opens the map.
- The home screen extends under the status bar; content limited to 560 dp wide (Fold unfolded).
- Header: bell, link badge (Bluetooth, account, offline), settings.
- "MAMMOTION" overline, robot name in large type, colored status badge.
- Indicators: battery, positioning, area (or share mowed during a local mowing).
- "My map" card with the thumbnail and a "Start mowing" button (today with a manual control button next to it;
  the Map / Manual / Device shortcut tiles of 0.23.0 were removed).
- Theme: refined neutrals, thin rule (`Hairline`) on the cards, more generous corners, tighter
  titles, letter-spaced capital overlines for the settings groups. Tab titles
  (Service, Me) enlarged with a subtitle; the Service robot card reuses the photo.

## Service (0.22.0)

Superseded by the [0.54.0 organization](#service-reorganized-2026-10-15-0540).

Contents: robot card (state, battery, connection), then three groups.
- **Robot**: Bluetooth connection, Manual control, Blades and cutting height, Obstacles.
- **Tests and diagnostics**: Navigation test, Bluetooth diagnostics, Detailed telemetry.
- **Data and settings**: Logs, Local gateway, Advanced settings.

Each entry opens a page with the same layout: safety warning if the robot or the
blades move, state, grouped settings, fixed main action at the bottom (pill, or red "hold
to…" button during the action), then results. Stops in red. The robot's commands and guards
are unchanged. Raw codes (bits, MTU, states) are reserved for detailed telemetry.

Account, simulation, notifications and heatmap history follow the same layout; the
simulation no longer offers a second zone selection (reminder of the order chosen on the map).

LubaBrain (editable map) was removed in 0.22.0; it will be rebuilt later.

## Map

- Since 0.64.0, rendering inspired by the current Mammotion app: light gray background, flat pale
  green zones with a green outline, flat ochre no-go zones, passages as a gray band dotted with
  white, white passes and border laps (laps on a lighter edging that makes the margins visible along the
  boundaries and around no-go zones), base safety zone as a gray disc, station as a round badge with a
  lightning bolt when it is small on screen. Order numbers in "ink".
- Status pill at the top: link (Bluetooth or gateway), positioning, battery, weather ([rain and
  weather](features/rain-and-weather.md)).
- Right vertical bar: station (leave / recharge, under supervision), recenter, move
  to a point.
- Compact map bottom: heatmap layer bar (eye, Wi-Fi / RTK / BLE…, settings), then
  **Mow / Manual / History / Map** action bar (**Follow** instead of **Mow** during a mowing); no "Ready" chip (state shown in the pill).
- During a local mowing or a simulation: white progress card (mowed %, battery,
  time) with STOP.

## Mowing task

1. **Mow**: zones are chosen directly on the map. Each tapped zone gets a number;
   the numbered list under the map lets you remove a zone. The displayed order is the mowing
   order: the computation no longer reorders the zones.
2. **Task settings**: mode tiles on two rows (Zigzag, Optimized, Checkerboard; Zamboni, Spiral, both experimental; the signal survey route opens
   from Service → Signal surveys and only shows Full loop / Grid), cutting
   height and speed (values in blue, green sliders), collapsible advanced settings (spacing,
   perimeter and no-go zone laps, perimeter first, recharge thresholds). Fixed bar:
   zones in order, "Zones" and "Route preview", area and estimated duration before computation.
3. **Route preview**: time, recharges, area, distance, numbered route map,
   verification; "Simulate" (gray pill) and "Start" (black pill), enabled once the
   links are verified.

## Related fixes

- Duplicates removed: contradictory estimate on the map (meters / 12), multiple STOP
  buttons, "Stop the Mammotion task" in the map options, "Shared route" labels.
- The preview numbers the zones according to the order actually computed.

## Animated charging (0.25.2, API source since 0.25.3)

Source of the charging state: the **Mammotion account** telemetry (API + MQTT), which carries the
same device report as BLE (`report.dev`: battery and `chargeState`). Without a BLE link,
the app polls the account every 30 s on the home screen and on the map, only in the
foreground; each poll waits for the position **and** the battery/charging report (15 s at most). If an
active BLE link publishes a more recent report, it takes precedence; otherwise the account is authoritative. An account
measurement older than 3 min (15 s for BLE) no longer shows charging. `chargeState` 1 or 2 =
charging contact established.

When the robot is charging, the home screen shows:

- the "Charging" status chip with a pulsing dot;
- energy rings spreading on the ground under the robot;
- a charging card ("BATTERY" overline): battery gauge whose level slides to the received value, a highlight
  sweeping across the reserve, pulsing lightning bolt, scrolling percentage and animated progress bar with a marker
  at the resume threshold, then "On the station · resume at NN % · <source>" (source: Mammotion account or
  Bluetooth). Red below 20 %, orange below 50 %, green above; "Charge complete" and "Battery full" at 100 %.

On the map, the status pill shows the pulsing charging icon and the animated percentage. The
animations follow the system animation scale. The account stays read-only
(`CloudReadOnly`): no robot command is added.

## Visual check without a device

`./gradlew :app:testDebugUnitTest --tests '*UiScreenshotTest' -PuiShots=/tmp/shots` produces the
screenshots (Robolectric + Roborazzi) of the home screen (including the charging home screen, light and dark), the light and dark map, zone selection,
task settings, the preview computed on the reference map and the Me tab. The
screenshots are skipped during the usual tests.

Not checked by these screenshots: manual control and a real mowing. The Service tab and its pages have been
captured since 0.54.0 (`UiShotsServiceTest`, `UiShotsEssaisTest`). No robot command was changed.

## Consistency redesign (2026-10-12)

All screens use the same building blocks (`ui/components/Components.kt`, `MammotionComponents.kt`,
`Controls.kt`); no more raw Material `Button`, `OutlinedButton`, `Switch`, `Slider` or `OutlinedTextField`
in the screens (except the Camera page).

- **Added building blocks**: `LubaSlider` / `LubaRangeSlider` (thin track, round white thumb ringed with green, no
  tick marks), `ToggleRow` (switch setting, whole row clickable), `LubaTextField`, `StatusNote`
  (result, warning or error in a tinted box, instead of free-form colored text), `LogLines`
  (monospaced logs in a box), `TextAction` (tertiary action, red for delete), `CodeValue`
  (gateway code to copy). Continuous, rounded progress bars.
- **Values**: `KeyValueRow` right-aligns the value; a long value wraps under its label.
- **Dialogs**: close cross in the `LubaDialog` header (`dismissEnabled` to block it during
  a measurement); redundant "Close" / "Cancel" footers are removed, only real actions remain.
- **Service**: contents in four groups (Robot, Robot tests in motion, Stationary robot diagnostics,
  Data and settings; replaced by five groups in 0.54.0), one-line subtitles. Gateway and relay redone: role as a segmented selector
  (Direct / Gateway / Pilot), titled groups, code highlighted, logs in a box.
- **Map**: unified floating panels (title, subtitle, cross). Task settings: zones in a navigable row
  at the top, footer reduced to the estimate and "Route preview". Preview: warnings grouped
  in a single note, discreet status line above Simulate / Start.
- **Tests** (blades, obstacles, debug mowing, manual control): preconditions summarized in one
  expandable line, lighter steps, shorter explanations; guards and stops unchanged.
- **Me / Account / Terrain / Weather / Notifications**: Mammotion account shown only once, version as a
  single line, fields and switches harmonized.

No robot command, guard, activation condition or planning logic changed.

Screenshots: `./gradlew :app:testDebugUnitTest --tests '*UiScreenshotTest' --tests '*UiShots*' -PuiShots=/tmp/shots`
(94 screenshots, light and dark). Not captured: tab bar and STOP banner (in the activity), Camera
page, states during a real test.

## Service reorganized (2026-10-15, 0.54.0)

Contents in five groups, from everyday use to diagnostics; the robot card opens the connection:

- **Connection**: Bluetooth connection · Wi-Fi gateway · Home server.
- **Robot**: Manual control · Cutting height · Camera.
- **Garden and history**: Task history · Obstacles (→ Obstacle simulation) · Signal surveys · Rain and weather.
- **Supervised tests**: Command cutoff · Navigation test · Border following · Obstacle tests · Blade
  test · Trial mowing (painted zone).
- **Diagnostics**: Bluetooth diagnostics · Detailed telemetry · Sensors and perception · Logs · Advanced settings.

Test page layout: conditions (robot not connected, mowing in progress), safety warning, tracking (map,
state), settings with the supervision checkbox last, results, notes; fixed main action at the bottom. The
data pages (Obstacles, History, Surveys) have neither guard nor warning. `ServicePage.parent` sets the back target of a
sub-page (Obstacle simulation → Obstacles).

## Living map (2026-10-13)

`map/MapFx.kt` dresses all maps (full screen and embedded maps, including the route preview):

- **Lawn** (`drawLawn`): originally a raised lawn (drop shadow, gradient, hatched no-go zones). Since 0.64.0 it is
  drawn like the Mammotion app: pale green fills, green outlines drawn after all the fills, flat ochre no-go zones
  with a thin outline, passages as a gray band dotted with white (see [Map](#map)).
- **Background** chosen in the Map sheet (`MapDisplaySettings`, Compose state read at draw time): metric grid
  tied to the map (`drawMetricGrid`, only the visible part is drawn), dot pattern fixed on screen
  (`mapDotGrid`) or plain background.
- **Effects layer** (`MapFxLayer`), a separate Canvas with the same camera: only this light layer is
  redrawn every frame, never the map and its passes.
  - Robot glides between two readings (over the observed interval between two positions, at most 950 ms since
    0.37.1; heading interpolated along the shortest path; direct jump beyond 5 m), two green ripples if the position is fresh, gray ring for the last FIX.
  - White comet with a green tail travelling the planned route in mowing order (5 to 16 s per lap),
    hidden during a simulation, a tracked mowing or a progressive drawing (`start` set).
  - Purple ripple around the start, white flash on the zone just selected.
- Map fade-in (96 → 100 %), animated camera recentering, halo under the real and
  simulated mowing trace.
- System animations disabled: effects frozen, no infinite loop.

### Readability pass (0.56.0)

- `map/MapDisplay.kt`: persisted display settings (`MapDisplay`, background, zone names, scale, comet),
  grid, zone names (`zoneLabels` computed once per map, `drawZoneNames` at constant size and
  counter-rotated), scale bar (`MapScaleBar`), moves with blades stopped (`drawTransitPath`, theme
  color `Transit`) and target of the move to a point.
- `map/MapSettingsSheet.kt`: **Map** sheet (replaces the former dialog) and illustrated legend, each symbol
  drawn with the map's colors and stroke widths.
- Local obstacles, presumed obstacles, contacts and difficulties get the "1 screen dp" unit (`unit`): their
  strokes no longer thicken when zooming.
- State outside `MapScreen` (`MapDisplaySettings`, `MapExtraLayers` objects) and reads in `IsolatedBox`:
  Dalvik register limit (`tools/check-dex-registers.py`).
- Screenshots: `carte-reglages`, `carte-obstacles-trajet` (`UiShotsCarteTest`).

Purely visual: no route computation, command or guard changed.

## Bilingual interface (0.65.0)

Every text is in English by default and in French on a French phone; numbers, dates and plurals follow the language.
Details in [interface → Language](features/interface.md#language). Effect screenshots:
`--tests '*UiShotsMapFxTest' -PuiShots=…` (clock advanced by hand).
