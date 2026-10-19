# Signals and terrain in a single view

Signal surveys (the continuous map fed by every robot move, or the dedicated route Service → **Signal surveys** →
**Prepare a survey route** → **Full loop** or **Grid**, see [signal surveys](features/signal-surveys-and-terrain.md))
show signal quality **and** the terrain relief in a single interface, the **Signals and terrain** sheet (settings
icon of the map's layer bar):

- **Color**: the selected layer, Wi-Fi by default (also RTK FIX, Bluetooth, Mobile, GNSS satellites, Slope, Terrain).
- **Terrain shading** (on by default, remembered): each cell keeps the signal color and gets darker or lighter
  according to the slope, like a topographic map. Grazing light from the northwest (45° elevation, slopes exaggerated
  ×4 so that a lawn stays readable): dark slopes = slope facing away from the northwest. The legend gives the signal
  color then the terrain ("Terrain: height difference N cm, as shading (dark slopes: facing away from the
  north-west)", or "shading hidden"). Not applicable on the **Terrain** layer, whose color already shows the terrain.
- **3D view: <layer> on the terrain** (for example "3D view: Wi-Fi on the terrain"): the shape is that of the
  terrain (**Height exaggeration** ×1 to ×20 in steps of 1, ×5 by default), the color that of the selected layer; a
  cell without a measurement for that layer stays gray. "Drag to rotate".
- The **Slope** (green when flat, red at 30 % and above) and **Terrain** (blue at the lowest, brown at the highest) layers
  remain available as color alone.

The same view is used live, in simulation and in the heatmap history (**Map** / **3D** toggle); the Service →
**Signal surveys** preview shows the color and legend only.

## Where the measurements come from

The robot's known protocol transmits neither altitude nor tilt. Wi-Fi and Bluetooth cannot measure terrain
(resolution of about a meter at best, only RSSI is accessible). Terrain is therefore reconstructed from
**the accelerometer of the gateway phone lying flat on the robot**:

1. The Redmi smooths its accelerometer over about 1 s (vibrations, jolts) and appends the measured vertical to the end
   of the HEALTH frame, every 500 ms. The protocol stays at version 2: an older pilot ignores these bytes,
   and the ESP32 gateway does not send them (no terrain with it).
2. Each survey measurement (at most 1 Hz; along the measurement route for a dedicated route, after 30 cm of movement
   for the continuous map) also records the robot's heading and that vertical if it is less than 2 s old.
3. Phone tilt + chosen orientation (top of the phone towards the front, rear, left or
   right of the robot) + robot heading = terrain slope on the map.
4. The **mounting error** (phone not perfectly flat, chassis) rotates with the robot whereas the terrain slope
   stays fixed on the map: it is estimated on cells crossed in several directions (grid
   crossings, back-and-forth runs), then removed. The legend shows the correction applied.
5. The mean slopes of the 1 m cells are integrated by least squares between measured neighboring cells
   (conjugate gradient): this gives a **relative height** above the lowest measured point.

## Reading

- **Slope**: green when flat, red at 30 % and beyond; the legend gives the maximum slope.
- **Terrain**: blue at the lowest point, then green, yellow, brown at the highest point; the legend gives the height difference.
- The legend ends with the mounting correction: "phone corrected by the flat-ground reference", "phone mounting
  offset corrected (x % front, y % side)" or "mounting offset not corrected (no round trip)".
- No absolute altitude, no extrapolation: only cells that were traveled are colored.
- A tilt reading is ignored when the phone is tilted by more than 40° or its acceleration is outside 7.5 to
  12 m/s² (phone not flat or jolt). Without any tilt the terrain layers stay empty: "No tilt received: the Redmi
  gateway lying flat on the robot sends the slope."; with unusable tilts: "Tilts received but unusable: phone not
  flat (screen facing the sky) or wrong orientation."
- Unconnected pieces (separate areas without a common neighboring cell) have independent heights; the legend
  says so.
- **Grid recommended**: the crossings allow the mounting error to be corrected and connect the passes
  together. A large lap alone gives a ring, without the inside.

## Phone orientation: automatic

By default (**Automatic**), the orientation of the phone on the robot is deduced from the robot's movements alone,
at any angle (not just the four positions):

1. **Starts and braking**: at the start of a pass, after the pivot, the start-up thrust adds to the first
   measurements and then disappears; when braking, it is the opposite. The tilt lags behind the position (smoothing of about
   1 s on the gateway, sent every 0.5 s): the thrust may fall on the 2nd or 3rd measurement. Each difference therefore compares
   the first 3 measurements of a pass with the next 3 (and the last 3 with the previous 3). These differences point
   towards the front of the robot in the phone's plane, **including direction**. At least 6 are needed, consistently oriented;
   otherwise detection moves on to the next step rather than risk a wrong direction. Up to version 0.33.0, only the
   1st measurement was compared with the 2nd: with about 1 s of lag, the orientation found was flipped by 180° and **the
   terrain appeared inverted** (blue at the top, brown at the bottom).
2. Failing that (starts too gentle), **terrain consistency**: with a wrong orientation, the slopes do not form a
   terrain and leave an integration residual. This fixes the axis but not front/rear (an inverted terrain is still a
   terrain): the top of the phone is then assumed to point rather towards the front. On flat ground, nothing stands out.
3. Without any clue: top towards the front is assumed.

The legend shows the orientation kept and its source ("Phone: top 35° to the left of the front (deduced from the
robot's starts and stops, 24 readings)"; other sources: "chosen by hand", "saved on flat ground", "deduced from
terrain consistency, front/back assumed", "not determined, top towards the front assumed"). The four manual positions (**Top towards the front**, **Top towards the back**, **Top towards the left**, **Top
towards the right**) remain available under **Gateway phone orientation** if detection gets it wrong. The start
and braking measurements (the first 3 and, when braking, the last 3 measurements of a pass) are excluded from the
terrain: their thrust would read as a slope (0.5 m/s² ≈ 5 %).

If the terrain looks inverted (blue on the bumps, brown in the hollows), the orientation kept is flipped: choose the
actual position of the phone by hand. A **flat-ground reference** records the current orientation: taken with
a flipped orientation, it keeps it; redo it after choosing the right position.

## Flat-ground reference

**Flat-ground reference** button ("Signals and terrain" sheet, or Map → **Map** sheet → *Signal heatmap*; not offered
for a simulated survey): robot **stopped on flat, level ground** (terrace, driveway, garage), gateway phone fixed in
its survey position, without touching the robot. The dialog shows the **Current reading** (tilt and current
orientation) and the **Saved reference** if any. **Save the current position** ("Capturing… keep the robot still")
is enabled once a tilt is received ("No tilt received: control the robot through the gateway (Redmi mounted on the
robot)." otherwise). For 4 s, the app receives the gateway's tilt and records:

- **the tilt zero**: the vertical as seen by the phone. All measurements are then corrected by this
  reference (exact rotation, also valid for a mount tilted up to 30°); the mounting error is no longer estimated;
- **the current orientation** (that of the displayed survey, detected or chosen): it becomes the **Saved** orientation,
  selected by default. Without a survey or a manual choice, only the tilt is recorded and automatic detection
  stays active.

Success: "Reference saved (phone tilted by N°)." Refused if fewer than 5 measurements are received ("Not enough
readings received from the gateway (n/5): …"), unstable measurements (> 0.15 m/s²: "Unstable readings (…): robot or
phone moving. Try again with the robot stopped."), an inconsistent reading (acceleration outside 8.8 to 10.8 m/s²),
or phone tilted by more than 30° ("Phone tilted by more than 30°: lay it flat, screen facing the sky."). **Clear
the reference** goes back to automatic estimation (and from **Saved** back to **Automatic**). The reference applies to all real
surveys, including in the history: redo it if the phone is moved on the robot. The ESP32 gateway has no
accelerometer: neither terrain nor reference with it.

## Setup

- Gateway phone **flat, screen facing the sky**, fixed (it must not slide during the survey), in
  any direction.
- Orientation: **Automatic** by default in the "Signals and terrain" panel (setting remembered, also applied to
  the history, recomputed over the whole survey).
- Measurement smoothed over 1 s: a slow survey is more accurate.

## Validation

- JVM tests (`ReliefModelTest`): simulated grid survey over a known hilly terrain reconstructed within 4 cm
  (root mean square), mounting error of 3° / −2° recovered within 1 %, wrong orientation detectable, back-and-forth
  on a constant 10 % slope, archiving of tilt measurements and reading of old archives.
- Combined view (`ReliefModelTest`): neutral shading when flat, Wi-Fi hue unchanged and modulated by the terrain, Wi-Fi
  color carried onto the terrain in 3D, legend explaining the absence of terrain without tilt.
- Automatic orientation (`MountEstimatorTest`): synthetic grid survey with starts, braking and pivots,
  phone rotated by 0°, 37°, 90°, 180° or 245°: orientation recovered within 12°, front/rear included;
  without acceleration, axis recovered from the terrain within 8°; flat terrain without acceleration: no conclusion.
- Flat-ground reference (`TiltCalibrationTest`): exact correction of a mount tilted by 15°, refusal of unstable
  measurements, too few measurements or a phone tilted by more than 30°, terrain identical to that of a flat phone
  with a mount tilted by 12°, saved orientation applied, automatic detection kept without an orientation.
- Gateway tests (`GatewayTiltTest`): extended HEALTH frame readable by an old pilot, accelerometer filter.
- **Not validated on the robot**: actual sharpness of the Luba's starts as seen by the Redmi (detection assumes
  clean starts after pivots), actual accuracy of the Redmi A1 accelerometer on the moving hood, and
  consistency of the heading sent by the robot with the measured tilt. Recommended first test: grid on a
  known slope, compared with a spirit level or a tape measurement.
