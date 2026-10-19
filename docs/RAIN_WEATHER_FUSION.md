# Rain handling by fusing the sensor and the radar

First step delivered (0.25.1): checking the garden's weather and warning about rain before and during mowing, in **observation mode** (see "Current state" below; user view in [rain and weather](features/rain-and-weather.md)). Fusion with the robot's sensor and the automatic actions remain to be done. Mowing must tolerate a few isolated drops: a briefly wet sensor must not trigger a return to base on its own. The decision combines the on-board sensor, the precipitation observed around the garden and its short-term trend.

## Data to combine

- Sensor: dry, wet or unknown state, freshness of the report, continuous wet duration and trigger frequency. The protocol exposes `RainStateCfg.rain_status`; its meaning and availability on the robot's firmware remain to be validated.
- Precipitation radar: intensity around the garden's GPS coordinates, observation timestamps and movement of rain cells. Use a small area around the garden to account for the radar resolution and the location uncertainty.
- Nowcast: arrival, duration and trend of a shower over the next few minutes, with a confidence indication. A radar extrapolation remains an estimate.
- Complementary weather: forecast precipitation and recent accumulation to put the radar into context. A model-based forecast must not be presented as a radar observation.

The position must be a reliable latitude and longitude for the garden, obtained from the robot if available or configured by the user. The local X/Y coordinates currently used for navigation cannot be used directly by a weather service. The phone's position is only a substitute if its presence in the garden is confirmed.

## Expected decisions

| Situation | Planned behavior |
| --- | --- |
| Sensor briefly wet, radar dry or very light rain | Continue and step up monitoring |
| Persistent wetness and confirmed significant local rain | Stop the blades, suspend mowing and request a return to base |
| Significant shower imminent and converging radar track | Prepare a suspension; early return depending on confidence, forecast duration and travel time |
| Rain forecast but sensor dry and radar without local rain | Continue under monitoring |
| Sensor wet for a long time but radar dry | Report the contradiction; fallback timer and sensor diagnostics |
| Weather unavailable or stale | Show degraded mode; use the sensor with a bounded timer |
| Sensor unknown or stale | Do not treat this state as "dry"; degraded decision made explicit |

The intensity, accumulation, persistence and confidence thresholds will be adjustable and calibrated on real tests. Do not arbitrarily equate "three drops" with a measured quantity: the sensor does not count drops.

## Timers and resume

Provide distinct states: mowing allowed, stepped-up monitoring, suspension, return requested and waiting to resume. Use different thresholds and durations to interrupt and to resume, to avoid back-and-forth during intermittent showers.

Resuming requires a sufficiently dry period and a lasting weather improvement. Take the recent accumulation into account to avoid resuming immediately on a soaked lawn; this accumulation is not a direct measurement of grass wetness. Provide an adjustable minimum delay and a manual resume if the data remain contradictory.

Loss of network must neither cause repeated returns nor allow a wet sensor to be ignored indefinitely. The fallback timers remain to be defined and tested.

## Integration into the project

Add a Rain and weather screen showing the sources, their freshness, the sensor, wetness persistence, the local radar, the trend and the decision with its reason. Example: "Continue: sensor wet for 12 s, light local rain". Done in part: Service → Garden and history → **Rain and weather** shows the decision, the radar frames, the movement and the forecast; the sensor line reads "Rain sensor: not read yet".

Start with an observation mode: compute and log the decisions without commanding the robot. Add reproducible simulated scenarios for isolated drops, persistent showers, contradictions and network loss. Enable the automatic actions after validation on the robot.

Keep in the reports the data that justified each state change, the thresholds used and the commands requested. Distinguish a return request, its acceptance and the actual arrival at the base.

## Technical points to check

1. Decode and query the sensor without unintentionally changing its configuration.
2. Check whether the native rain protection already forces a return; do not promise to tolerate a few drops if the robot interrupts mowing by itself. Any change to this protection must be explicit and reversible.
3. Choose a radar provider based on coverage of the garden, access to intensity data, resolution, cadence, license, cost and availability. Radar images alone do not guarantee a usable intensity.
4. Define the units and intervals of each source before comparing intensities and accumulations.
5. Check the return-to-base path and its confirmation on the real firmware.
6. Limit requests, keep a timestamped cache and mark data that are too old as stale. Send only the location needed by the chosen provider.

## Validation criteria

- A brief trigger with light weather requests no return.
- Confirmed persistent rain causes a single transition to suspension, then return.
- Stale, missing or contradictory data are never interpreted as certainly dry weather.
- Intermittent showers do not create a stop-and-resume loop.
- Decisions remain explainable on the Rain and weather page and in the exports.
- Mechanical protections, STOP and link checks keep priority.

## Wet grass (0.44.1)

`GrassWetness` combines the weighted rain of the last 72 hours and dew (saturated air, then drying by the cumulative
vapor pressure deficit) into an index from 0 to 1. It is used for slipping on pivots
([rain and weather](features/rain-and-weather.md#wet-grass-and-slips-when-pivoting-0441)) and not for the rain
decision. Open-Meteo `hourly` request (temperature, dew point, humidity, rain, vapor pressure deficit;
`past_days=3`), every hour, with the same coordinates as the forecast. A reading older than 3 h is not used
(`GrassWetness.STALE_MS`).

## Complementary weather reference

[Open-Meteo documentation](https://open-meteo.com/en/docs): data by latitude and longitude, and precipitation variables. Current conditions rely on weather models with a 15-minute step; this source can complement the radar but does not replace it.

## Current state (0.25.1)

Code: `app/src/main/java/fr/luba/poc/weather/`. The animated radar panel of 0.25.0 was removed in favor of a simpler version: the radar is analyzed in the background, without display. Tests: `RainWeatherTest`, `GrassWetnessTest`, `WeatherPillLabelTest`, `WeatherServiceTest` (no Internet access).

- **Garden position**: Service → Garden and history → Rain and weather → Settings → Garden position (or from the weather detail of the map's status pill). Paste "lat, lon", the phone's approximate position (the `ACCESS_COARSE_LOCATION` permission is only requested at that moment) or the position published by the robot if received (`rpt_rtk.mqtt_rtk_info` or `NavLatLonUp`, decoded into `Telemetry.geoLat/geoLon`).
- **Observed rain**: RainViewer radar (Universal Blue palette, the past frames listed by RainViewer at 10 min intervals, 512 px tiles at zoom 7), read within a frame of about 130 km around the garden (320 px at zoom 8); a frame with a missing tile is marked incomplete, left out of the decision and fetched again on the next refresh; analyzed frames are cached in memory: rain over the garden, distance to the nearest rain, approach **extrapolated** from the latest images (the free "nowcast" no longer exists since 2026).
- **Forecast**: Open-Meteo `minutely_15` (model) over 8 h (32 steps), refreshed every 15 min; stale after 90 min.
- **Map**: weather segment in the top status pill (Dry, Monitoring, Rain imminent, Rain, End of rain, Weather unavailable; "Rain ≈ N min" with an estimated arrival) as soon as the position is set and monitoring is on; a tap opens "Garden weather" (reason, observed rain, forecast accumulations, first forecast rain, grass, position, refresh).
- **Before mowing**: the launch screen (route step) checks observed rain and the forecast over the **estimated mowing duration** (the coming hour if unknown, at least 15 min) (`MowingRainCheck`): warning box if it is raining, rain is imminent, rain ended less than 30 min ago, or rain is forecast (≥ 0.1 mm in a quarter of an hour or ≥ 0.2 mm accumulated), otherwise "No rain forecast during the mowing (≈ duration)". A forecast horizon that is too short ("forecast available until HH:MM") and a forecast older than 90 min ("Weather forecast unavailable: rain not checked") are flagged.
- **During mowing** (`RobotBle.observeAlerts`): local "Rain during mowing" alert (warning, a single notification per episode) if the same check over the next 30 min warns: rain observed, imminent, recent or forecast within 30 min; the weather segment of the pill turns orange ("Rain forecast"). **No command is sent to the robot.**
- **Decision** (`RainMonitor`, thresholds in `RainThresholds`): rain at ≥ 23 dBZ (≈ 1 mm/h) within 1.5 km of the garden when the previous image already had ≥ 20 dBZ there, or ≥ 30 dBZ (≈ 2.7 mm/h) on one image; imminent if the estimated arrival is ≤ 20 min, or ≥ 0.5 mm forecast over 30 min with rain within 15 km; monitoring for a weaker echo (≥ 15 dBZ), rain within 15 km or ≥ 0.2 mm forecast within the hour; 30 min without rain before returning to dry ("End of rain" meanwhile); radar stale after 30 min, forecast after 90 min. An isolated echo stays under watch. The approach is a linear fit of the nearest-rain distance (searched up to 60 km) over the images of the last 40 min. A wet robot sensor would confirm rain at 23 dBZ on a single image, but the sensor is not read yet.
- **Service → Rain and weather**: decision and reason, the last 7 radar images analyzed, estimated movement, forecast, grass state, "Rain sensor: not read yet", Settings (Garden position, "Monitor rain" switch, Refresh now).
- **Network**: radar refreshed every 5 min while monitoring is active (≈ 1 KB of list + a few radar tiles per new image), forecast every 15 min, hourly weather (wet grass) every hour. Only the garden's coordinates (4 decimal places) are sent to Open-Meteo; RainViewer only receives tile numbers. Requests identify themselves as "DumbyLuba" with the app version.

Still to do: decoding the rain sensor (`RainStateCfg`), calibrating thresholds on real showers, then, after validation, automatic suspension and return.
