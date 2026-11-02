package fr.luba.poc.ble

import fr.luba.poc.i18n.tr
import fr.luba.poc.obstacles.LocalObstacle

// Constants and route outcomes shared by [RobotBle] and its collaborators (same package, module-internal).

/** Recoveries by recomputing the path, per move to a point. */
internal const val MAX_NAV_RECOVERIES = 3
/** Minimum battery to start a sync with the Mammotion map (several outlines may be driven in a row). */
internal const val SYNC_BATTERY_FLOOR = 40
/** Back-off after a contact: raw speed (≈ 10 cm/s, like leaving the station) and maximum duration. */
internal const val BACK_OFF_LINEAR = 100
/** Maximum number of slide map cells sent to the gateway (≈ 20 bytes each, 16 KB frame). */
internal const val SLIDE_CELLS_TO_GATEWAY = 300
internal const val BACK_OFF_TIMEOUT_MS = 20_000L

/** Bumper contact during a route: pose at contact (fused tracking heading, cleared by the STOP). */
internal data class RouteContact(val code: Int, val x: Double, val y: Double, val heading: Double, val atMs: Long)
/** Straight back-off that left its line sideways (wheel slip): after a contact during a mission, recovered like a trajectory exit. */
internal class BackOffDeviation(message: String) : IllegalStateException(message)
/** Obstacle discovered while tracking a route: [index] = current edge. */
internal class ObstacleEncounter(val index: Int, val contact: RouteContact?, val added: List<LocalObstacle>,
                                message: String,
                                /** Wheels turning without moving ([RouteBlocked]): handled like a contact without its code, recorded as a blockage. */
                                val blockage: Boolean = false) : Exception(message)
/**
 * Tracking handed to the gateway and lost by it (start order lost in a Wi-Fi outage, gateway restarted):
 * robot stopped by the gateway, [index] = last known edge. The route resumes from the actual pose.
 */
internal class RouteLegLost(val index: Int, message: String) : IllegalStateException(message)
/** End of the working hours during a mowing: robot returned to (or stayed on) the base, mission stopped. */
internal class WorkWindowEnded : IllegalStateException(tr("End of the working hours", "Fin de la plage horaire de travail"))
/** Maximum number of tracking runs lost by the gateway resumed during one mission (beyond that, the link is deemed too unstable). */
internal const val MAX_LOST_LEG_RECOVERIES = 4
internal const val MAX_RECONNECTS = 5

internal fun ByteArray.hex() = joinToString("") { "%02x".format(it.toInt() and 255) }

/** Listening delay before concluding that only one robot is in range. */
internal const val AUTO_CONNECT_SETTLE_MS = 4000L
