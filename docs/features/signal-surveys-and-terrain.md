# Signal surveys (heatmaps) and terrain

Every time the robot moves, the app automatically maps, meter by meter, the quality of Wi-Fi, RTK, Bluetooth,
mobile network and GNSS. With a gateway phone placed on the robot, it also reconstructs the terrain. Surveys are
shown as layers on top of the map.

| | |
|---|---|
| **Access** | Service → Garden and history → **Signal surveys** (status, preview, history, **Show on map**, dedicated route); Map → **Layers** side button and layer bar; Map → **Map** sheet → *Signal heatmap* (**Show the heatmap**, layer, **Layer bar**, **Heatmap history**, **Flat-ground reference**) |
| **Status** | Implemented; terrain validated in simulation (terrain recovered within 4 cm), real-world accuracy still to be measured |
| **Code** | `map/SignalSurvey.kt`, `SignalSurveyStore.kt`, `SignalLiveMap.kt`, `SignalHistoryDialog.kt`, `SignalServicePage.kt`, `SignalOverlay.kt`, `MapLayerBar.kt`, `MapLayerSettings.kt`, `MapSettingsSheet.kt`, `SurveyPlanner.kt`, `Relief.kt`, `ReliefUi.kt`, `gateway/PhoneTiltSensor.kt`; recording in `ble/RobotBle.kt` |
| **Tests** | `SignalSurveyTest`, `SignalLiveMapTest`, `SignalSurveyStoreTest`, `SignalOverlayTest`, `MapLayerBarTest`, `MapLayerChoiceTest`, `ReliefModelTest`, `MountEstimatorTest`, `TiltCalibrationTest`, `GatewayTiltTest` |
| **Reference** | [Terrain: how it works and its limits](../RELIEF.md) |

<p align="center">
  <img src="../images/survey-settings.png" width="170" alt="Signal survey">
  <img src="../images/heatmap-wifi.png" width="170" alt="Wi-Fi heatmap">
  <img src="../images/terrain-shading.png" width="170" alt="Signals and terrain">
  <img src="../images/terrain-3d.png" width="170" alt="3D view">
</p>

## Automatic survey

No action is needed: as soon as the robot moves on a complete map (mowing, move to a point, return to base, manual
control, dedicated route), its measurements feed the **continuous map** (see below). No command is sent to the robot:
the app only reuses the telemetry it already receives. Nothing is recorded while the robot is charging, on the demo
map or without a complete robot map.

Service → Garden and history → **Signal surveys** (hub subtitle "Wi-Fi, RTK and Bluetooth on the map") shows:

- **Continuous map**: **Version** (date of the current version), **Readings** ("N readings · 1 m N cells"),
  **Layers** (measured layers), **Last reading** and **Map** ("current robot map", or "other map (preview on its
  original map)"). Before any reading: "No readings yet: they are added as soon as the robot moves on a complete
  map." A write failure shows "Could not save the survey".
- **Preview**: the version on its map, colored by the chosen layer (layer chips and legend).
- **Browse** → **Heatmap history** (see below).
- **Dedicated route (optional)** → **Prepare a survey route** (disabled with "Load the robot map first" until the
  robot map is complete).
- **Show on map** (bottom button): opens the map with the layer bar and the continuous map overlaid.

## Dedicated survey route (optional)

To measure the whole garden in one go (and get a better terrain estimate thanks to the grid crossings):
Service → **Signal surveys** → **Prepare a survey route**. The map opens on the **Survey zones** panel ("Tap the zones
to drive through, blades stopped: they will be surveyed in this order."); this route is no longer a **Mow** mode. It
is not offered while a route, a mowing or a simulation is running.

1. Choose the zones. Adjacent zones are merged: their shared boundaries are not driven.
2. **Survey route** page, *Signal survey route* group, **Full loop** / **Grid** choice:
   - **Full loop**: outer contour of the merged zones, offset inwards;
   - **Grid**: two families of crossing passes inside the zones, outside no-go zones and obstacles.

   | Setting | Range | Default |
   |---|---|---|
   | Grid spacing (Grid only) | 1 to 10 m, 1 m steps | 3 m |
   | Edge margin | 5 to 100 cm between the side of the robot and the boundary, 5 cm steps | 10 cm (remembered, independent of mowing) |
   | Survey speed | 0.2 to 0.6 m/s | 0.3 m/s (same value as the mowing task speed) |

3. **Route preview**: route, distance and simulated estimate (links, pivots, returns and recharges).
   **Start the survey**, or **Simulate** ([below](#simulating-a-survey)). The blades are locked off; from a
   simulation, **Run this route** only offers **Follow, blades stopped**.

The readings of a dedicated route also feed the continuous map, and it is archived in the heatmap history. The run
appears in [task history](mowing-history.md) as a **Signal survey** (without adjusting the mowing estimates) and,
if interrupted, can be resumed from the point reached ([resume](mowing-resume.md)).

## Measurements

- Dedicated route: at most one measurement per second, kept only within 30 cm of the measurement route (not during
  approaches, returns and recharges), with a freshness of 2 s for the position and each signal.
- Average per 1 m cell, **with no extrapolation** to areas not driven over. A zero or invalid RSSI (outside −127 to
  −1 dBm) counts as unavailable data; a layer is only offered once it has real values.
- Tilt for the terrain: the gateway reading is used if it is less than 2 s old (see [Terrain](#terrain)).
- Vivid red → orange → yellow → green palette, almost opaque (0.50.3).

| Layer | Red → green |
|---|---|
| Wi-Fi | −80 → −50 dBm (orange around −70, yellow around −60) |
| Bluetooth, Mobile | −100 → −50 dBm (yellow around −67) |
| RTK FIX | 0 → 100 % of the time in FIX |
| GNSS satellites | 0 → 30 satellites |
| Slope | green when flat, red at 30 % and above |
| Terrain | blue at the lowest, brown at the highest |

## Continuous map

Outside dedicated surveys, every movement of the robot feeds a **continuous map** of the signals (at most one
measurement per second and only after 30 cm of movement; each 1 m cell keeps its 6 most recent measurements). A new
version is opened every day, or when the robot map changes, starting from the readings of the previous version for
the same robot. The screen is refreshed every 10 s and the version saved every 30 s. The last 10 versions are
retained in the history; dedicated surveys are never deleted.

## Displaying and browsing

- **Layers** button in the map's side bar ("Show the signal layers" / "Hide the layers"): shows or collapses the
  layer bar and the heatmap.
- Overlaid survey: the continuous map, including on the Mammotion account map or a reloaded map as long as most of
  the measurements fall within its footprint plus 2 m (same reference frame); or a survey chosen from the history
  (**Show on map**), identified in the bar by a label such as "Survey of Oct 12" or "Continuous map of Oct 12", the
  cross returning to the continuous map. With no survey, the bar reads "No survey" and its icon opens the
  history.
- **Layer** bar at the bottom of the map: **show / hide** eye, the **Obstacles** and **Difficulties** toggles
  ([encountered obstacles and difficulties](obstacles.md#encountered-obstacles-and-difficulties)), one button per
  measured layer (**Wi-Fi**, **RTK**, **BLE**, **Mobile**, **Satellites**, and **Slope** / **Terrain** only when the
  terrain can be computed), "Simulated" label for a simulated heatmap. Tapping the displayed layer hides it. The
  settings icon opens the **Signals and terrain** sheet: group "Measured heatmap · N readings" (or "Simulated
  heatmap"), **Color** chips (all layers, plus **Hide**) with the legend, **Terrain shading**, **Gateway phone
  orientation**, **Flat-ground reference**, **3D view: <layer> on the terrain**, **Saved heatmaps** (history). The
  chosen layer, the eye, the collapsed bar and the shading are kept until the next change, across map openings as
  well as across launches (`MapLayerSettings`, 0.45.2).
- Map → **Map** sheet → *Signal heatmap*: **Show the heatmap** (or **Show the simulated heatmap**) and layer chips
  ("No signal survey on this map: open the history to show one." when there is none), **Layer bar** switch,
  **Heatmap history**, **Flat-ground reference** ([map sheet](map.md#map-sheet)).
- **Heatmap history**: each route is archived with its date, type, layers and the contours of the map used. Filters
  **All / Real / Simulated / Continuous** ("No heatmap saved for this filter." when empty). Each row shows the date,
  Robot or Simulation, the mode (Full loop, Signal grid, or "Continuous map (today's version)") and the number of
  readings. A survey opens on its original map, even after a map change or a restart ("Old survey: the boundaries of
  this map were not kept." for archives from before map snapshots), with **Survey**, **Source**, **Mode**,
  **Readings**, a **Map** / **3D** toggle when the terrain is available, the *Display* controls (color, shading,
  phone orientation) and **Show on map**, which overlays it on the main map. **Back to the list** returns to the
  list.

## Simulating a survey

**Simulate** from the preview progressively generates a heatmap of **deterministic dummy signals** (and a fictional
terrain of about ±50 cm), with the same layers and legends. It is labeled "Simulated heatmap" and archived
separately (filter **Simulated**); the phone orientation and the flat-ground reference do not apply to it.

## Terrain

The robot transmits neither altitude nor tilt. The terrain comes from **the accelerometer of the gateway phone laid
flat on the robot**:

1. the gateway smooths its accelerometer and sends the measured vertical every 500 ms;
2. combined with the robot's heading, this gives the slope along and across the direction of travel;
3. the phone's mounting error is estimated at the grid crossings;
4. the slopes are integrated (least squares) into **relative heights** on the cells driven over.

- **Phone orientation** on the robot: detected automatically from starts and braking (any angle), or chosen by hand
  (top towards the front, back, left, right).
- **Phone orientation** choices: **Automatic** (default), **Saved** (only with a flat-ground reference that holds an
  orientation), **Top towards the front**, **Top towards the back**, **Top towards the left**, **Top towards the
  right**; remembered and applied to the history too.
- **Flat-ground reference** (Signals and terrain sheet, or Map sheet → *Signal heatmap*): robot stopped on flat
  ground, **Save the current position** records the current tilt as zero and the current orientation (readings over
  4 s, at least 5 needed; refused if the phone moves or tilts by more than 30°). **Clear the reference** goes back to
  automatic estimation. Details in [Flat-ground reference](../RELIEF.md#flat-ground-reference).
- **Terrain shading** (on by default): the signal color darkens or lightens with the slope, like a topographic map;
  not applicable on the **Terrain** layer.
- **3D view**: the layer's color draped over the shape of the terrain, **Height exaggeration** from ×1 to ×20 in
  steps of 1 (×5 by default), rotatable with a finger.

## Limitations

- Without FIX, the position may be degraded.
- No terrain with the ESP32 gateway (it has no accelerometer) or in direct Bluetooth.
- Real radio coverage and terrain still have to be validated by routes in the field.
