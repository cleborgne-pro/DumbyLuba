# Lightweight navigation on the map

Service → Supervised tests → **Navigation test** (formerly Debug → Navigation) replaces the random walk with a metric route from the actual position. The planned route is previewed on the page without any motor command (refreshed every 2 s, or with **Refresh the planned route**); starting the run recomputes the start from a fresh position. User guide: [tests and diagnostics](features/tests-and-diagnostics.md#navigation-test).

The plan contains up to three 3 m stripes, 0.8 m apart, then a piece of outer border and a piece of no-go/obstacle outline, each limited to 4 m. The outlines are those of the real map, never those of the circle that bounds the test. Elements outside the radius or unreachable are left out, and the counts actually planned are displayed. To test a no-go zone, place the robot in an area near an exclusion and choose a large enough radius.

The full map and a start inside a zone are required. The planner subtracts no-go zones and local obstacle reserves, keeps a 0.9 m margin and connects the elements with paths inside the free area and within the chosen radius. A start too close to a boundary is refused.

Tracking uses the local position and heading received: rotation on the spot before moving forward, forward speed from the **Forward speed** slider (10–100 %, i.e. 50–500 raw units, shared with the map's travel speed) and rotation capped by **Maximum rotation speed** (60–450 units), slowdown near the point, STOP at each point reached within 15 cm. The local mathematical heading and the angular sign are consistent with the logs of Oct 7 (forward motion with heading -135° towards decreasing x/y, rotation -150 with decreasing heading). The convention is not that of a compass heading.

Stops: state/position older than 2 s, protection, sensor alert, new robot error event, deviation from the segment above 30 cm, map/exclusion margin reached, no progress for 15 s, or maximum duration. Reaching the maximum duration is ABORTED, never PASS. The current position and a point 30 cm ahead of the robot are checked before every forward command. Reports are requested every 200 ms; the actual rate still has to be checked on the firmware.

Blades are OFF during this controller validation. The run can be started by holding **Hold to drive (X max)** or, with **Automatic start**, with **Start · X min · blades stopped**; maximum duration 1, 2, 5 or 10 min, radius 2 to 15 m. STOP, leaving the screen and going to the background all still stop it. The preview and the actual trace appear together on the map, and the `map_navigation` report keeps every planned point.

JVM tests: `MapNavTestTest` checks the start, stripes, borders/no-go zones, every link, the radius, local reserves, refusal of invalid starts and tracking commands. Mechanical behavior and RTK accuracy still have to be validated in the field.
