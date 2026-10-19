# Local BLE navigation — audit of October 8, 2026 — built-in recharge cycle

> **Historical audit (October 8, 2026).** It describes the state of the app on that date. Since then, among other
> changes: automatic missions continue with the app in the background while the link service holds the link
> ([background link](BACKGROUND_LINK.md)); a lost link is reconnected automatically (link only); some automatic resumes
> of mowing exist (after a gateway update, after losing the gateway during recharging, after re-docking; see
> [mowing resume](features/mowing-resume.md)); with a [gateway](features/gateway.md), the route can be tracked by the
> gateway itself. The last paragraphs' statement "no automatic resume after reconnection" is therefore no longer true
> in those cases. The other values below (speeds, thresholds, tolerances, six-hour cap) are not verified against the
> current code; the current behavior is described in [real mowing](features/real-mowing.md) and
> [station and moves](features/station-and-moves.md).

The **Mow** button now applies the prepared local route with `startRobotRoute`, like **Mow this route**. The selected height is passed to the BLE control. Motion is computed in the app and sent through `DrvMotionCtrl`, including links, the return, the final back-off and leaving the base. The blades are stopped, and their stop confirmed, before transits. Real speeds remain conservative raw units (30/20); the speed in m/s only sets the simulation.

The Debug route also uses `DrvMotionCtrl`. The simulation is local and sends no robot command. A simulation start error is displayed without closing the app.

The former native mowing entry points are disabled. A filter on outgoing messages blocks firmware coverage preparation, start, resume, global return and automatic leaving of the station. Map reads, manual commands and stop commands remain allowed. An emergency pause does not delegate navigation.

The low and high thresholds of the preparation panel also apply to the real robot (low 10–80 %, high up to 100 %, minimum gap of 5 points). The battery level **reported by the robot** triggers the return at `battery <= low threshold`, and the resume at `battery >= high threshold`. The critical stop reserve is 5 %: it does not guarantee that the remaining battery is enough to complete a long return.

At the low threshold, STOP and the blade stop are confirmed by a new OFF state and stable positions. The interruption point and the segment index are kept in memory. Outbound and resume links are checked against the robot envelope. If no safe link exists from the interruption point, the mission stops: no shortcut and no firmware route is launched.

On approach, the robot aligns with the recorded real axis of the station, then backs up at raw speed -10, without rotating during the back-off. A lateral deviation above 10 cm, a heading drift above 8°, an obstacle, a lost position or an exceeded docking timeout cause a STOP. The approach and the return to the interruption point use a 5 cm tolerance. Geometric arrival alone does not validate docking: a fresh charge state of 1 or 2, close to the base, is required.

While recharging, no motion is sent. BLE and robot states must stay fresh; the blades must be OFF and charging contact maintained. The position may grow stale while stopped, but must become fresh again before leaving. The wait is capped at six hours. At the high threshold: BLE exit with blades OFF, confirmation that contact has ended, link to the interruption point, then continuation of the remaining segment. No simulated recharge time is used as proof. At the end of mowing, a last return and back-off end on a confirmed charging contact, with no further exit.

Launching from a base that is already charging also waits for the high threshold before departure. The station axis must be set: an axis inferred for the simulation is not enough for the real robot. STOP, closing, going to the background or BLE loss cancel the cycle; there is no automatic resume after reconnection or an app restart.

JVM tests cover the reference simulation, BLE route continuity, the exact thresholds, the fresh contact required to resume, outbound/resume links with blades OFF and the back-off guards, the navigation and blade guards, and the refusal of native commands. They prove neither mechanical motion nor a complete real mowing. These points require a supervised test on the robot with telemetry and physical observation.
