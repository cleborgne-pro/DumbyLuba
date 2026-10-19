> Update of Oct 11: mowing, surveys, station routes and go-to-point no longer stop on contact. A straight back-off is checked against the trace, then the remaining route is repaired by the mission's navigation: see [obstacle bypass](OBSTACLE_BYPASS.md). The repair described below (`LocalReplanner`) now only applies to the map-based navigation runs (Service → Navigation test, Obstacle tests → Bypass) when a saved obstacle, without any contact, blocks the remaining segment. A contact during such a run, or during a move to a point, triggers the straight back-off then a path recomputed to the destination.

# Local route repair

The supervised BLE run, blades OFF, stops motion commands as soon as a new collision is reported (1014/1207) or a local obstacle blocks the remaining segment. Bumper health alerts do not prove a contact: they remain stop reasons without resume.

The incident is logged with timestamp, RTK coordinates, heading, freshness, segment and mission. The center of the unknown obstacle is estimated from the front bumper; this estimate keeps the heading assumptions of Teach mode. Raw coordinates stay in the persistent history.

After STOP, tracking requires a new position and a displacement of less than 3 cm over 600 ms, as well as all existing guards. The computation is limited to a 4 m disc. Borders, no-go zones, obstacles, margins, rotation envelope and minimum turning radius restrict the free space. Each incident adds a 10 cm reserve, capped at 60 cm. The link rejoins a later point of the route and keeps the original suffix. No direct path through the obstacle can be resumed.

If the contact leaves the robot inside the reserve, if a fresh position or rotation space is missing, or if no link is available within 4 m, it stays stopped ("Stop held: no safe link within 4 m"). The local repair itself never commands a reversing move. At most three recoveries are allowed per run.

Once three incidents are associated with the same obstacle, an `incident_heatmap` event is produced and Service → Obstacles shows "Recurring incidents: adjust the position or the margin." under that obstacle. The former incident heat around obstacles is no longer drawn: the map now shows the [difficulty heatmap](DIFFICULTIES.md). The Mammotion map is never modified automatically.

Native mowing does not provide a route that the local follower can execute: collisions stop that mission through the native safety sequence and record the incident, without claiming to replace its firmware route.

Automated validation: bypass that keeps the suffix, contact without rotation space, barrier without a link, border, history and correction threshold. Physical validation of the bumper and of the bypass still has to be done under supervision, blades OFF.
