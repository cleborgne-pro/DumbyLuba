# Mowing simulation

Replays the prepared route on the map, sped up: departure, mowing, returns to base, recharges and resumes. The
simulation sends **no commands** to the robot; it is used to check a plan before running it for real.

| | |
|---|---|
| **Access** | Map → **Mow** → zones → **Task settings** → **Route preview** → **Simulate** |
| **Status** | Implemented and covered by JVM tests (full cycles, recharges, separate zones, outdoor base) |
| **Code** | `simulation/Simulation.kt`, `SimulationSession.kt`, `SimulationDialog.kt`, `ShortestRoutes.kt`, `FinishPolicy.kt`, `map/MapScreen.kt` (simulation bar) |
| **Reference** | [Simulation guide and model limitations](../MOWING_SIMULATION.md) |

<p align="center">
  <img src="../images/simulation.png" width="200" alt="Mowing simulation">
</p>

## Scenario

**Simulate** is enabled in the route preview once the links are checked ("Checking the links · n/total") and the
base and its docking axis are known (any base on the demo map). It starts at once, without any new geometric
computation, with the task settings (task speed, return / resume thresholds) and this scenario:

| Setting | Range | Default |
|---|---|---|
| Mowing already done | 0 to 100 % | 0 % |
| Battery at start | 0 to 100 % | 100 % |
| Autonomy (100 % → 0 %) | 5 to 180 min | 60 min |
| Full recharge | 5 to 120 min | 30 min |
| Playback speed | ×1, ×5, ×20, ×60, ×120, ×300 | ×20 |

The **Mowing simulation** dialog (**Survey simulation** for a survey route; "Accelerated local simulation: no command
is sent to the robot.") holds a *Route* group (zones, station, **Choose the zones and compute** / **Edit the task**)
and a *Scenario* group with the first four sliders, and its **Start the simulation** button reads "Checking
accesses…" while the links are prepared. These two groups are only shown before a simulation starts; since the
dialog is now opened from the simulation bar of a running simulation, they are not reachable in the current version
and **Simulate** uses the default scenario above (behavior read from the code, not verified on the phone). The
playback speed stays adjustable.

## Run

- The bottom bar shows **Simulation · phase**, "Mowed x %", "Battery x %", the simulated minutes and a progress bar,
  with play/pause, the speed menu (×N) and "⋯". A tap on the bar or "⋯" opens the **Mowing simulation** dialog: phase,
  "x % mowed" ("measured" for a survey), **Battery**, **Simulated time**, **Recharges**, **Pause** / **Resume**, the
  last 12 log lines, **Playback speed**, **Finish and prepare another scenario** (back to the zone choice) and **Show
  on map**.
- Phases shown: *Leaving the base · slow forward*, *Start / resume*, *Mowing*, *Return to base*, *Alignment before
  recharge*, *Recharge return · reversing*, *Recharge*, *Mowing finished · at the base*, *Simulation blocked*.
- The robot heads back when it only has the energy needed to reach the base above the return threshold, recharges
  up to the resume threshold, then sets off again **from the exact point of interruption**. As on the robot
  (0.48.0): at the low threshold, it **finishes** the mowing if the remainder plus the return leave it at 10 % or
  more (energy increased by 25 %); while charging, it **leaves before the resume threshold** as soon as the battery
  covers the remainder above the low threshold. The simplified estimate (without a prepared engine) follows the same
  rule.
- **Leaving base**: every departure from the station (first departure and resume after recharge) starts, as on the
  robot, with a slow straight forward run (0.1 m/s, the docking speed) from the base to the link point, without
  pivoting on the base. The link to the mowing follows at normal speed. This time is included in the battery
  reserve checked before each resume and in the duration estimate.
- Starting battery: same rule as real mowing, immediate departure from the return threshold + 10 points (capped at
  the resume threshold), otherwise an initial recharge up to the resume threshold.
- Explicit blocks, shown in red under the bar: "Autonomy insufficient to reach the resume point and come back",
  "Battery exhausted before the base", "Physical trajectory impossible". An impossible scenario stops instead of
  looping.
- Map: path covered in blue, departure/return/resume in orange, a to-scale LUBA silhouette oriented along its
  heading, wheels animated by its movement. The real robot stays visible next to it. Phase log, simulated time,
  number of recharges.
- A simulation is cleared when a connected robot shows another map than the simulated one.

## Running the simulated route

Outside the demo and account maps, the **Simulated route** bar ("Run this route on the robot", "⋯") opens a sheet
whose *Run this route* group offers **Follow, blades stopped** (dry run of the route) or **Mow** (absent for a survey
route); "Connect the robot to run this route." otherwise. Both pause the simulation. It is exactly the same prepared
route, with the same geometric checks ([real mowing](real-mowing.md)). Not offered for a plan that is not executable.

## Model and limitations

- Task speed, pivots on the spot at 45°/s, docking in reverse at 0.1 m/s, approach point 1 to 1.5 m from the base
  ("Theoretical model (docking 0.1 m/s, linear battery, pivot 45°/s, no inertia or slope)").
- Linear battery, **no slope, inertia or slip**; recharging is instantaneous on screen but its time is counted.
- The session survives tab changes and pauses in the background ("Paused: app moved to the background, restart
  playback" line in the log, restart playback on return); it is lost if the process is killed.
- Diagnostics: leaving base, each departure and return, the end and any block (with its cause, position and
  battery) are written to the `simulation_transit` log, including every phase crossed during one accelerated step
  (0.59.0).
- The simulation checks the plan; it does not replace physical validation.

## See also

- [Obstacle simulation](obstacles.md#obstacle-simulation): contact, back-off and bypass on the map.
- [Simulated heatmap](signal-surveys-and-terrain.md#simulating-a-survey): fake signals to explore surveys.
